// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.stores.ledger.sql.migration

import com.digitalasset.platform.sandbox.stores.ledger.sql.util.JdbcServerType
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

class FlywayMigrations(ds: DataSource, jdbcUrl: String) {
  import FlywayMigrations._

  private val logger = LoggerFactory.getLogger(getClass)

  def migrate(): Unit = {
    try {
      val dbType = JdbcServerType.dbType(jdbcUrl)
      val flyway = configurationBase(dbType).dataSource(ds).load()
      logger.info(s"running Flyway migration..")
      val stepsTaken = flyway.migrate()
      logger.info(s"Flyway schema migration finished successfully applying ${stepsTaken} steps.")
    } catch {
      case NonFatal(e) =>
        logger.error("an error occurred while running schema migration", e)
        //TODO: shall we quit gracefully if something goes off track?
        //there is little point in communicating this error in a typed manner, we should rather blow up
        throw e
    }
  }

}

object FlywayMigrations {

  def configurationBase(dbType: String) = Flyway.configure.locations("classpath:db/migration/" + dbType)

  def apply(ds: DataSource, jdbcUrl: String): FlywayMigrations = new FlywayMigrations(ds, jdbcUrl)
}
