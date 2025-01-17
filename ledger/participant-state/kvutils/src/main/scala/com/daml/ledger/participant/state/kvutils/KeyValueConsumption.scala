// Copyright (c) 2019 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.kvutils

import com.daml.ledger.participant.state.kvutils.Conversions._
import com.daml.ledger.participant.state.kvutils.DamlKvutils._
import com.daml.ledger.participant.state.v1._
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.Ref.{LedgerString, Party}
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.digitalasset.ledger.api.domain.PartyDetails
import com.google.common.io.BaseEncoding
import com.google.protobuf.ByteString

import scala.collection.breakOut
import scala.collection.JavaConverters._

/** Utilities for producing [[Update]] events from [[DamlLogEntry]]'s committed to a
  * key-value based ledger.
  */
object KeyValueConsumption {

  sealed trait AsyncResponse extends Serializable with Product
  final case class PartyAllocationResponse(submissionId: String, result: PartyAllocationResult)
      extends AsyncResponse
  final case class PackageUploadResponse(submissionId: String, result: UploadPackagesResult)
      extends AsyncResponse

  def packDamlLogEntry(entry: DamlStateKey): ByteString = entry.toByteString
  def unpackDamlLogEntry(bytes: ByteString): DamlLogEntry = DamlLogEntry.parseFrom(bytes)

  /** Construct a participant-state [[Update]] from a [[DamlLogEntry]].
    *
    * This method is expected to be used to implement [[com.daml.ledger.participant.state.v1.ReadService.stateUpdates]].
    *
    * @param entryId: The log entry identifier.
    * @param entry: The log entry.
    * @return [[Update]] constructed from log entry.
    */
  def logEntryToUpdate(entryId: DamlLogEntryId, entry: DamlLogEntry): List[Update] = {

    val recordTime = parseTimestamp(entry.getRecordTime)

    entry.getPayloadCase match {
      case DamlLogEntry.PayloadCase.PACKAGE_UPLOAD_ENTRY =>
        entry.getPackageUploadEntry.getArchivesList.asScala.map { archive =>
          Update.PublicPackageUploaded(
            archive,
            if (entry.getPackageUploadEntry.getSourceDescription.nonEmpty)
              Some(entry.getPackageUploadEntry.getSourceDescription)
            else None,
            Ref.LedgerString.assertFromString(entry.getPackageUploadEntry.getParticipantId),
            recordTime
          )
        }(breakOut)

      case DamlLogEntry.PayloadCase.PACKAGE_UPLOAD_REJECTION_ENTRY =>
        List.empty

      case DamlLogEntry.PayloadCase.PARTY_ALLOCATION_ENTRY =>
        List(
          Update.PartyAddedToParticipant(
            Party.assertFromString(entry.getPartyAllocationEntry.getParty),
            entry.getPartyAllocationEntry.getDisplayName,
            Ref.LedgerString.assertFromString(entry.getPartyAllocationEntry.getParticipantId),
            recordTime
          )
        )

      case DamlLogEntry.PayloadCase.PARTY_ALLOCATION_REJECTION_ENTRY =>
        List.empty

      case DamlLogEntry.PayloadCase.TRANSACTION_ENTRY =>
        List(txEntryToUpdate(entryId, entry.getTransactionEntry, recordTime))

      case DamlLogEntry.PayloadCase.CONFIGURATION_ENTRY =>
        List(Update.ConfigurationChanged(parseDamlConfigurationEntry(entry.getConfigurationEntry)))

      case DamlLogEntry.PayloadCase.REJECTION_ENTRY =>
        List(rejectionEntryToUpdate(entry.getRejectionEntry))

      case DamlLogEntry.PayloadCase.PAYLOAD_NOT_SET =>
        sys.error("entryToUpdate: PAYLOAD_NOT_SET!")
    }
  }

  /** Construct a participant-state [[AsyncResponse]] from a [[DamlLogEntry]].
    *
    * This method is expected to be used to implement [[com.daml.ledger.participant.state.v1.WriteService.allocateParty]]
    * and [[com.daml.ledger.participant.state.v1.WriteService.uploadPackages]], both of which require matching requests
    * with asynchronous responses.
    *
    * @param entryId: The log entry identifier.
    * @param entry: The log entry.
    * @return [[Update]] constructed from log entry.
    */
  def logEntryToAsyncResponse(
      entryId: DamlLogEntryId,
      entry: DamlLogEntry,
      participantName: String): Option[AsyncResponse] = {

    entry.getPayloadCase match {
      case DamlLogEntry.PayloadCase.PACKAGE_UPLOAD_ENTRY =>
        if (participantName == entry.getPackageUploadEntry.getParticipantId)
          Some(
            PackageUploadResponse(
              entry.getPackageUploadEntry.getSubmissionId,
              UploadPackagesResult.Ok
            )
          )
        else None

      case DamlLogEntry.PayloadCase.PACKAGE_UPLOAD_REJECTION_ENTRY =>
        if (participantName == entry.getPackageUploadRejectionEntry.getParticipantId)
          Some(packageRejectionEntryToAsynchResponse(entry.getPackageUploadRejectionEntry))
        else None

      case DamlLogEntry.PayloadCase.PARTY_ALLOCATION_ENTRY =>
        if (participantName == entry.getPartyAllocationEntry.getParticipantId)
          Some(
            PartyAllocationResponse(
              entry.getPartyAllocationEntry.getSubmissionId,
              PartyAllocationResult.Ok(
                PartyDetails(
                  Party.assertFromString(entry.getPartyAllocationEntry.getParty),
                  if (entry.getPartyAllocationEntry.getDisplayName.isEmpty)
                    None
                  else
                    Some(entry.getPartyAllocationEntry.getDisplayName),
                  entry.getPartyAllocationEntry.getParticipantId == participantName
                )
              )
            )
          )
        else None

      case DamlLogEntry.PayloadCase.PARTY_ALLOCATION_REJECTION_ENTRY =>
        if (participantName == entry.getPartyAllocationRejectionEntry.getParticipantId)
          Some(partyRejectionEntryToAsynchResponse(entry.getPartyAllocationRejectionEntry))
        else None

      case DamlLogEntry.PayloadCase.TRANSACTION_ENTRY =>
        None

      case DamlLogEntry.PayloadCase.CONFIGURATION_ENTRY =>
        None

      case DamlLogEntry.PayloadCase.REJECTION_ENTRY =>
        None

      case DamlLogEntry.PayloadCase.PAYLOAD_NOT_SET =>
        sys.error("entryToUpdate: PAYLOAD_NOT_SET!")
    }
  }

  private def rejectionEntryToUpdate(rejEntry: DamlRejectionEntry): Update.CommandRejected = {

    Update.CommandRejected(
      submitterInfo = parseSubmitterInfo(rejEntry.getSubmitterInfo),
      reason = rejEntry.getReasonCase match {
        case DamlRejectionEntry.ReasonCase.DISPUTED =>
          RejectionReason.Disputed(rejEntry.getDisputed.getDetails)
        case DamlRejectionEntry.ReasonCase.INCONSISTENT =>
          RejectionReason.Inconsistent
        case DamlRejectionEntry.ReasonCase.RESOURCES_EXHAUSTED =>
          RejectionReason.ResourcesExhausted
        case DamlRejectionEntry.ReasonCase.MAXIMUM_RECORD_TIME_EXCEEDED =>
          RejectionReason.MaximumRecordTimeExceeded
        case DamlRejectionEntry.ReasonCase.DUPLICATE_COMMAND =>
          RejectionReason.DuplicateCommand
        case DamlRejectionEntry.ReasonCase.PARTY_NOT_KNOWN_ON_LEDGER =>
          RejectionReason.PartyNotKnownOnLedger
        case DamlRejectionEntry.ReasonCase.SUBMITTER_CANNOT_ACT_VIA_PARTICIPANT =>
          RejectionReason.SubmitterCannotActViaParticipant(
            rejEntry.getSubmitterCannotActViaParticipant.getDetails
          )
        case DamlRejectionEntry.ReasonCase.REASON_NOT_SET =>
          sys.error("rejectionEntryToUpdate: REASON_NOT_SET!")
      }
    )
  }

  private def partyRejectionEntryToAsynchResponse(
      rejEntry: DamlPartyAllocationRejectionEntry): PartyAllocationResponse = {

    PartyAllocationResponse(
      submissionId = rejEntry.getSubmissionId,
      result = rejEntry.getReasonCase match {
        case DamlPartyAllocationRejectionEntry.ReasonCase.INVALID_NAME =>
          PartyAllocationResult.InvalidName(rejEntry.getInvalidName.getDetails)
        case DamlPartyAllocationRejectionEntry.ReasonCase.ALREADY_EXISTS =>
          PartyAllocationResult.AlreadyExists
        case DamlPartyAllocationRejectionEntry.ReasonCase.PARTICIPANT_NOT_AUTHORIZED =>
          PartyAllocationResult.ParticipantNotAuthorized
        case DamlPartyAllocationRejectionEntry.ReasonCase.REASON_NOT_SET =>
          sys.error("rejectionEntryToUpdate: REASON_NOT_SET!")
      }
    )
  }

  private def packageRejectionEntryToAsynchResponse(
      rejEntry: DamlPackageUploadRejectionEntry): PackageUploadResponse = {

    PackageUploadResponse(
      submissionId = rejEntry.getSubmissionId,
      result = rejEntry.getReasonCase match {
        case DamlPackageUploadRejectionEntry.ReasonCase.INVALID_PACKAGE =>
          UploadPackagesResult.InvalidPackage(rejEntry.getInvalidPackage.getDetails)
        case DamlPackageUploadRejectionEntry.ReasonCase.PARTICIPANT_NOT_AUTHORIZED =>
          UploadPackagesResult.ParticipantNotAuthorized
        case DamlPackageUploadRejectionEntry.ReasonCase.REASON_NOT_SET =>
          sys.error("rejectionEntryToUpdate: REASON_NOT_SET!")
      }
    )
  }

  /** Transform the transaction entry into the [[Update.TransactionAccepted]] event. */
  private def txEntryToUpdate(
      entryId: DamlLogEntryId,
      txEntry: DamlTransactionEntry,
      recordTime: Timestamp): Update.TransactionAccepted = {
    val relTx = Conversions.decodeTransaction(txEntry.getTransaction)
    val hexTxId = LedgerString.assertFromString(BaseEncoding.base16.encode(entryId.toByteArray))

    Update.TransactionAccepted(
      optSubmitterInfo = Some(parseSubmitterInfo(txEntry.getSubmitterInfo)),
      transactionMeta = TransactionMeta(
        ledgerEffectiveTime = parseTimestamp(txEntry.getLedgerEffectiveTime),
        workflowId =
          Some(txEntry.getWorkflowId).filter(_.nonEmpty).map(LedgerString.assertFromString),
      ),
      transaction = makeCommittedTransaction(entryId, relTx),
      transactionId = hexTxId,
      recordTime = recordTime,
      referencedContracts = List.empty // TODO(JM): rename this to additionalContracts. Always empty here.
    )
  }

  private def makeCommittedTransaction(
      txId: DamlLogEntryId,
      tx: SubmittedTransaction): CommittedTransaction = {
    tx
    /* Assign absolute contract ids */
      .mapContractIdAndValue(
        toAbsCoid(txId, _),
        _.mapContractId(toAbsCoid(txId, _))
      )
  }

}
