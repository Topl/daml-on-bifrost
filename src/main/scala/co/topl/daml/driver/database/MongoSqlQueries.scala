package co.topl.daml.driver.database

import anorm.SqlParser.{long, str}
import anorm._
import com.daml.ledger.configuration.LedgerId
import com.daml.ledger.on.sql.Index
import com.daml.ledger.on.sql.queries.Queries.{LogTable, MetaTable, StateTable}
import com.daml.ledger.on.sql.queries.{CommonQueries, Queries, QueriesFactory}
import com.daml.ledger.participant.state.kvutils.{KVOffsetBuilder, Raw}

import java.sql.Connection
import scala.util.Try

final class MongoSqlQueries(offsetBuilder: KVOffsetBuilder)(implicit connection: Connection)
    extends CommonQueries(offsetBuilder) {

  private val MetaTableKey = 0

  override def updateOrRetrieveLedgerId(providedLedgerId: LedgerId): Try[LedgerId] = Try {
    SQL"INSERT INTO $MetaTable (table_key, ledger_id) VALUES ($MetaTableKey, $providedLedgerId) ON CONFLICT DO NOTHING"
      .executeInsert()
    SQL"SELECT ledger_id FROM $MetaTable WHERE table_key = $MetaTableKey"
      .as(str("ledger_id").single)
  }

  override def insertRecordIntoLog(key: Raw.LogEntryId, value: Raw.Envelope): Try[Index] = Try {
    SQL"INSERT INTO #$LogTable (entry_id, envelope) VALUES ($key, $value) RETURNING sequence_no"
      .as(long("sequence_no").single)
  }

  override def truncate() = Try {
    SQL"truncate #$StateTable".executeUpdate()
    SQL"truncate #$LogTable".executeUpdate()
    SQL"truncate #$MetaTable".executeUpdate()
    ()
  }

  override protected val updateStateQuery =
    s"INSERT INTO $StateTable (key, key_hash, value) VALUES ({key}, {key_hash}, {value}) ON CONFLICT(key_hash) DO UPDATE SET value = {value}"
}

object MongoSqlQueries extends QueriesFactory {

  override def apply(offsetBuilder: KVOffsetBuilder, connection: Connection) = {
    implicit val conn: Connection = connection
    new MongoSqlQueries(offsetBuilder)
  }
}
