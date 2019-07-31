package com.digitalasset.platform.sandbox.stores.ledger.sql.util

import com.digitalasset.platform.sandbox.stores.ledger.sql.dao.{
  H2DatabaseLedgerDao,
  LedgerDao,
  PostgresLedgerDao
}
import com.digitalasset.platform.sandbox.stores.ledger.sql.serialisation.{
  ContractSerializer,
  KeyHasher,
  TransactionSerializer,
  ValueSerializer
}

object JdbcServerType {

  def dbType(jdbcUrl: String): String = jdbcUrl match {
    case h2 if h2.startsWith("jdbc:h2:") => "h2database"
    case _ => "postgres"
  }

  def daoBuilder(jdbcUrl: String): (
      DbDispatcher,
      ContractSerializer,
      TransactionSerializer,
      ValueSerializer,
      KeyHasher) => LedgerDao = dbType(jdbcUrl) match {
    case "postgres" => PostgresLedgerDao.apply
    case "h2database" => H2DatabaseLedgerDao.apply
    case unknown => throw new IllegalArgumentException(s"Unknown database type $unknown")
  }
}
