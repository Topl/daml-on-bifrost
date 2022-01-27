package com.daml.ledger.validator

import co.topl.daml.driver.MongoReaderWriter.SqlLedgerStateAccess
import co.topl.daml.driver.database.Database
import com.daml.lf.engine.Engine
import com.daml.metrics.Metrics

object BifrostValidator {

  def createValidator(
    database:            Database,
    metrics:             Metrics,
    logEntryIdAllocator: LogEntryIdAllocator,
    stateValueCache:     StateValueCache,
    engine:              Engine
  ) = SubmissionValidator.createForTimeMode(
    ledgerStateAccess = new SqlLedgerStateAccess(database, metrics),
    logEntryIdAllocator = logEntryIdAllocator,
    checkForMissingInputs = false,
    stateValueCache = stateValueCache,
    engine = engine,
    metrics = metrics
  )
}
