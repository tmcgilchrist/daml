// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.semantictest

import java.time.temporal.ChronoUnit

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import com.digitalasset.api.util.TimestampConversion
import com.digitalasset.daml.lf.command.Commands
import com.digitalasset.daml.lf.data.{Ref, Time}
import com.digitalasset.daml.lf.engine.testing.SemanticTester
import com.digitalasset.daml.lf.engine.Event
import com.digitalasset.daml.lf.language.Ast
import com.digitalasset.daml.lf.transaction.Transaction.{Value => TxValue}
import com.digitalasset.daml.lf.value.Value
import com.digitalasset.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.grpc.adapter.client.akka.ClientAdapter
import com.digitalasset.ledger.api.v1.command_submission_service.SubmitRequest
import com.digitalasset.ledger.api.v1.testing.time_service.{GetTimeRequest, SetTimeRequest}
import com.digitalasset.ledger.api.v1.transaction_filter.{Filters, TransactionFilter}
import com.digitalasset.platform.apitesting.LedgerContext
import com.digitalasset.platform.participant.util.LfEngineToApi
import com.digitalasset.platform.tests.integration.ledger.api.LedgerTestingHelpers
import com.google.protobuf.timestamp.Timestamp

import scalaz.syntax.tag._
import scala.collection.breakOut
import scala.concurrent.{ExecutionContext, Future}

class SemanticTestAdapter(
    lc: LedgerContext,
    packages: Map[Ref.PackageId, Ast.Package],
    parties: Iterable[String],
    timeoutScaleFactor: Double = 1.0,
    commandSubmissionTtlScaleFactor: Double = 1.0
)(
    implicit ec: ExecutionContext,
    am: ActorMaterializer,
    esf: ExecutionSequencerFactory
) extends SemanticTester.GenericLedger {
  override type EventNodeId = String

  private def ledgerId = lc.ledgerId

  private def ttlSeconds = 30 * commandSubmissionTtlScaleFactor

  private val tr = new ApiScenarioTransform(ledgerId.unwrap, packages)

  private def apiCommand(mappedSubmitterName: String, cmds: Commands) =
    LfEngineToApi.lfCommandToApiCommand(
      mappedSubmitterName,
      ledgerId.unwrap,
      cmds.commandsReference,
      "applicationId",
      Some(LfEngineToApi.toTimestamp(cmds.ledgerEffectiveTime.toInstant)),
      Some(
        LfEngineToApi.toTimestamp(
          cmds.ledgerEffectiveTime.toInstant.plusSeconds(ttlSeconds.toLong))),
      cmds
    )

  override def submit(
      submitterText: String,
      submitterName: Ref.Party,
      cmds: Commands,
      opDescription: String)
    : Future[Event.Events[String, Value.AbsoluteContractId, TxValue[Value.AbsoluteContractId]]] = {
    for {
      tx <- LedgerTestingHelpers(
        lc.forParty(submitterText).commandService.submitAndWaitForTransactionId,
        lc.forParty(submitterText),
        timeoutScaleFactor = timeoutScaleFactor)
        .submitAndListenForSingleTreeResultOfCommand(
          SubmitRequest(Some(apiCommand(submitterName, cmds))),
          TransactionFilter(parties.map(_ -> Filters.defaultInstance)(breakOut)),
          true,
          true,
          opDescription = opDescription
        )
      events <- Future.fromTry(tr.eventsFromApiTransaction(tx).toTry)
    } yield events
  }

  override def passTime(dtMicros: Long): Future[Unit] = {
    for {
      time <- getTime
      // set time for a single LedgerContext, assuming that this passes time in the whole of distributed ledger.
      _ <- lc.timeService.setTime(
        SetTimeRequest(
          ledgerId.unwrap,
          Some(time),
          Some(TimestampConversion.fromInstant(
            TimestampConversion.toInstant(time).plus(dtMicros, ChronoUnit.MICROS)))
        ))
    } yield ()
  }

  override def currentTime: Future[Time.Timestamp] =
    getTime.map(
      apiTimestamp =>
        Time.Timestamp
          .fromInstant(TimestampConversion.toInstant(apiTimestamp))
          .fold((s: String) => throw new RuntimeException(s), identity))

  private def getTime: Future[Timestamp] = {
    ClientAdapter
      .serverStreaming(GetTimeRequest(ledgerId.unwrap), lc.timeService.getTime)
      .map(_.getCurrentTime)
      .runWith(Sink.head)
  }
}
