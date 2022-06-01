package co.topl.daml.mongodb

import akka.NotUsed
import akka.stream.scaladsl.Source
import co.topl.daml.mongodb.database.Database
import com.daml.api.util.TimeProvider
import com.daml.caching.Cache
import com.daml.concurrent.{ExecutionContext, Future}
import com.daml.ledger.api.domain
import com.daml.ledger.api.health.Healthy
import com.daml.ledger.configuration.LedgerId
import com.daml.ledger.offset.Offset
import com.daml.ledger.on.sql.queries.Queries
import com.daml.ledger.participant.state.kvutils.api.{CommitMetadata, LedgerReader, LedgerRecord, LedgerWriter}
import com.daml.ledger.participant.state.kvutils.{KVOffsetBuilder, Raw}
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.ledger.validator._
import com.daml.lf.data.Ref
import com.daml.lf.engine.Engine
import com.daml.logging.LoggingContext
import com.daml.metrics.{Metrics, Timed}
import com.daml.platform.akkastreams.dispatcher.Dispatcher
import com.daml.platform.akkastreams.dispatcher.SubSource.RangeSource
import com.daml.platform.common.MismatchException
import com.daml.telemetry.TelemetryContext

import java.util.UUID
import java.util.concurrent.Executors
import scala.util.{Failure, Success}

final class MongoReaderWriter(
  override val ledgerId:     LedgerId = Ref.LedgerString.assertFromString(UUID.randomUUID().toString),
  val participantId:         Ref.ParticipantId,
  metrics:                   Metrics,
  offsetBuilder:             KVOffsetBuilder,
  database:                  Database,
  dispatcher:                Dispatcher[Index],
  committer:                 ValidatingCommitter[Index],
  committerExecutionContext: concurrent.ExecutionContext
) extends LedgerWriter
    with LedgerReader {

  private val startOffset: Offset = offsetBuilder.of(StartIndex)

  override def commit(correlationId: String, envelope: Raw.Envelope, metadata: CommitMetadata)(implicit
    telemetryContext:                TelemetryContext
  ) = committer.commit(correlationId, envelope, participantId)(committerExecutionContext)

  override def events(startExclusive: Option[Offset]): Source[LedgerRecord, NotUsed] =
    dispatcher
      .startingAt(
        offsetBuilder.highestIndex(startExclusive.getOrElse(startOffset)).toInt,
        RangeSource[Index, LedgerRecord]((startExclusive, endInclusive) =>
          Source
            .future(
              Timed
                .value(
                  metrics.daml.ledger.log.read,
                  database.inReadTransaction("read_log") { queries =>
                    Future.fromTry(queries.selectFromLog(startExclusive, endInclusive))
                  }
                )
                .removeExecutionContext
            )
            .mapConcat(identity)
            .mapMaterializedValue(_ => NotUsed)
        )
      )
      .map { case (_, entry) => entry }

  override def currentHealth() = Healthy
}

object MongoReaderWriter {
  val DefaultTimeProvider: TimeProvider = TimeProvider.UTC

  final class Owner(
    ledgerId:                LedgerId,
    participantId:           Ref.ParticipantId,
    metrics:                 Metrics,
    engine:                  Engine,
    jdbcUrl:                 String,
    resetOnStartup:          Boolean,
    offsetVersion:           Byte,
    logEntryIdAllocator:     LogEntryIdAllocator,
    stateValueCache:         StateValueCache = Cache.none,
    timeProvider:            TimeProvider = DefaultTimeProvider
  )(implicit loggingContext: LoggingContext)
      extends ResourceOwner[MongoReaderWriter] {

    override def acquire()(implicit context: ResourceContext): Resource[MongoReaderWriter] = {
      implicit val migratorExecutionContext: ExecutionContext[Database.Migrator] =
        ExecutionContext(context.executionContext)
      val offsetBuilder = new KVOffsetBuilder(offsetVersion)
      for {
        uninitializedDatabase <- Database.owner(jdbcUrl, offsetBuilder, metrics).acquire()
        database <- Resource.fromFuture(
          //          if (resetOnStartup) uninitializedDatabase.migrateAndReset().removeExecutionContext else
          concurrent.Future(uninitializedDatabase.migrate())
        )
        _          <- Resource.fromFuture(updateOrCheckLedgerId(ledgerId, database).removeExecutionContext)
        dispatcher <- new DispatcherOwner(database).acquire()
        committer = new ValidatingCommitter[Index](
          () => timeProvider.getCurrentTime,
          validator = BifrostValidator.createValidator(
            database,
            metrics,
            logEntryIdAllocator = logEntryIdAllocator,
            stateValueCache = stateValueCache,
            engine = engine
          ),
          postCommit = dispatcher.signalNewHead
        )
        committerExecutionContext <- ResourceOwner
          .forExecutorService(() =>
            concurrent.ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
          )
          .acquire()
      } yield new MongoReaderWriter(
        ledgerId,
        participantId,
        metrics,
        offsetBuilder,
        database,
        dispatcher,
        committer,
        committerExecutionContext
      )
    }
  }

  private def updateOrCheckLedgerId(
    providedLedgerId: LedgerId,
    database:         Database
  ): Future[Database.Writer, Unit] =
    database.inWriteTransaction("retrieve_ledger_id") { queries =>
      Future.fromTry(
        queries
          .updateOrRetrieveLedgerId(providedLedgerId)
          .flatMap { ledgerId =>
            if (providedLedgerId != ledgerId) {
              Failure(
                MismatchException.LedgerId(
                  domain.LedgerId(ledgerId),
                  domain.LedgerId(providedLedgerId)
                )
              )
            } else {
              Success(())
            }
          }
      )
    }

  final private class DispatcherOwner(database: Database) extends ResourceOwner[Dispatcher[Index]] {

    override def acquire()(implicit context: ResourceContext): Resource[Dispatcher[Index]] =
      for {
        head <- Resource.fromFuture[Index](
          database
            .inReadTransaction("read_head") { queries =>
              Future.fromTry(queries.selectLatestLogEntryId().map(_.getOrElse(StartIndex)))
            }
            .removeExecutionContext
        )
        dispatcher <- Dispatcher
          .owner[Index](
            name = "sql-participant-state",
            zeroIndex = StartIndex,
            headAtInitialization = head
          )
          .acquire()
      } yield dispatcher
  }

  final class SqlLedgerStateAccess(database: Database, metrics: Metrics) extends LedgerStateAccess[Index] {

    override def inTransaction[T](
      body: LedgerStateOperations[Index] => concurrent.Future[T]
    )(implicit
      executionContext: concurrent.ExecutionContext,
      loggingContext:   LoggingContext
    ): concurrent.Future[T] =
      database
        .inWriteTransaction("commit") { queries =>
          body(new TimedLedgerStateOperations(new SqlLedgerStateOperations(queries), metrics))
        }
        .removeExecutionContext
  }

  final private class SqlLedgerStateOperations(queries: Queries) extends BatchingLedgerStateOperations[Index] {

    override def readState(
      keys: Iterable[Raw.StateKey]
    )(implicit
      executionContext: concurrent.ExecutionContext,
      loggingContext:   LoggingContext
    ): concurrent.Future[Seq[Option[Raw.Envelope]]] =
      Future.fromTry(queries.selectStateValuesByKeys(keys)).removeExecutionContext

    override def writeState(
      keyValuePairs: Iterable[Raw.StateEntry]
    )(implicit
      executionContext: concurrent.ExecutionContext,
      loggingContext:   LoggingContext
    ): concurrent.Future[Unit] =
      Future.fromTry(queries.updateState(keyValuePairs)).removeExecutionContext

    override def appendToLog(
      key:   Raw.LogEntryId,
      value: Raw.Envelope
    )(implicit
      executionContext: concurrent.ExecutionContext,
      loggingContext:   LoggingContext
    ): concurrent.Future[Index] =
      Future.fromTry(queries.insertRecordIntoLog(key, value)).removeExecutionContext
  }
}
