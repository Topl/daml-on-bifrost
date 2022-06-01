package co.topl.daml.mongodb.database

import co.topl.daml.mongodb.database.Database.{ConnectionAcquisitionException, ConnectionPool, Reader, Writer}

import java.sql.{Connection, DriverManager, SQLException}
import java.util.concurrent.Executors
import javax.sql.DataSource
import com.daml.concurrent.{ExecutionContext, Future}
import com.daml.dec.DirectExecutionContext
import com.daml.ledger.on.sql.queries._
import com.daml.ledger.participant.state.kvutils.KVOffsetBuilder
import com.daml.ledger.resources.ResourceOwner
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.metrics.{Metrics, Timed}
import com.daml.resources.ProgramResource.StartupException
import com.zaxxer.hikari.HikariDataSource
import scalaz.syntax.bind._

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

final class Database(
  queries:       QueriesFactory,
  offsetBuilder: KVOffsetBuilder,
  metrics:       Metrics
)(implicit
  readerConnectionPool: ConnectionPool[Reader],
  writerConnectionPool: ConnectionPool[Writer]
) {

  def inReadTransaction[T](name: String)(
    body:                        ReadQueries => Future[Reader, T]
  ): Future[Reader, T] =
    inTransaction(name)(body)

  def inWriteTransaction[T](name: String)(body: Queries => Future[Writer, T]): Future[Writer, T] =
    inTransaction(name)(body)

  def inTransaction[X, T](name: String)(body: Queries => Future[X, T])(implicit
    connectionPool:             ConnectionPool[X]
  ): Future[X, T] = {
    import connectionPool.executionContext
    val connection =
      try Timed.value(
        metrics.daml.ledger.database.transactions.acquireConnection(name),
        connectionPool.acquireConnection()
      )
      catch {
        case exception: SQLException =>
          throw new ConnectionAcquisitionException(name, exception)
      }
    Timed.future(
      metrics.daml.ledger.database.transactions.run(name),
      Future[X] {
        body(new TimedQueries(queries(offsetBuilder, connection), metrics))
          .andThen {
            case Success(_) => connection.commit()
            case Failure(_) => connection.rollback()
          }
          .andThen { case _ =>
            connection.close()
          }
      }.join
    )
  }
}

object Database {
  private val logger = ContextualizedLogger.get(classOf[Database])

  // We use a prefix for all tables to ensure the database schema can be shared with the index.
  private val TablePrefix = "ledger_"

  // This *must* be 1 right now. We need to insert entries into the log in order; otherwise, we
  // might end up dispatching (head + 2) before (head + 1), which will result in missing out an
  // event when reading the log.
  //
  // To be able to process commits in parallel, we will need to fail reads and retry if there are
  // entries missing.
  private val MaximumWriterConnectionPoolSize: Int = 1

  def owner(
    jdbcUrl:                 String,
    offsetBuilder:           KVOffsetBuilder,
    metrics:                 Metrics
  )(implicit loggingContext: LoggingContext): ResourceOwner[UninitializedDatabase] =
    (jdbcUrl match {
      case url if url.startsWith("jdbc:mongodb:") =>
        MongoSingleConnectionDatabase.owner(RDBMS.MongoDB, jdbcUrl, offsetBuilder, metrics)
      case _ =>
        throw new InvalidDatabaseException(s"Unknown database")
    }).map { database =>
      logger.info(s"Connected to the ledger over JDBC.")
      database
    }

  object MongoSingleConnectionDatabase {

    def owner(
      system:        RDBMS,
      jdbcUrl:       String,
      offsetBuilder: KVOffsetBuilder,
      metrics:       Metrics
    ): ResourceOwner[UninitializedDatabase] =
      for {
        readerWriterDataSource <- ResourceOwner.forCloseable(() =>
          newHikariDataSource(jdbcUrl, maxPoolSize = Some(MaximumWriterConnectionPoolSize))
        )
        adminDataSource <- ResourceOwner.forCloseable(() => newHikariDataSource(jdbcUrl))
        readerWriterExecutorService <- ResourceOwner.forExecutorService(() =>
          Executors.newFixedThreadPool(MaximumWriterConnectionPoolSize)
        )
      } yield {
        implicit val readerWriterExecutionContext: ExecutionContext[Reader with Writer] =
          ExecutionContext.fromExecutorService(readerWriterExecutorService)
        implicit val readerWriterConnectionPool: ConnectionPool[Reader with Writer] =
          new ConnectionPool(readerWriterDataSource)
        implicit val adminConnectionPool: ConnectionPool[Migrator] =
          new ConnectionPool(adminDataSource)(DirectExecutionContext)
        new UninitializedDatabase(system, offsetBuilder, metrics)
      }
  }

  private def newHikariDataSource(
    jdbcUrl:     String,
    readOnly:    Boolean = false,
    maxPoolSize: Option[Int] = None
  ): HikariDataSource = {
    val pool = new HikariDataSource()
    pool.setAutoCommit(false)
    pool.setJdbcUrl(jdbcUrl)
    pool.setReadOnly(readOnly)
    maxPoolSize.foreach(pool.setMaximumPoolSize)
    DriverManager.getDrivers.asIterator().forEachRemaining(x => println(x.toString))
    pool
  }

  sealed trait RDBMS {
    val name: String

    val queries: QueriesFactory
  }

  object RDBMS {

    object MongoDB extends RDBMS {
      override val name = "MongoDB"

      override val queries = MongoSqlQueries.apply
    }
  }

  class UninitializedDatabase(
    system:        RDBMS,
    offsetBuilder: KVOffsetBuilder,
    metrics:       Metrics
  )(implicit
    readerConnectionPool: ConnectionPool[Reader],
    writerConnectionPool: ConnectionPool[Writer],
    adminConnectionPool:  ConnectionPool[Migrator]
  ) {

    //    private val flyway: Flyway =
    //      Flyway
    //        .configure()
    //        .placeholders(Map("table.prefix" -> TablePrefix).asJava)
    //        .table(TablePrefix + Flyway.configure().getTable)
    //        .dataSource(adminConnectionPool.dataSource)
    //        .locations(s"classpath:/com/daml/ledger/on/sql/migrations/${system.name}")
    //        .load()
    //
    def migrate(): Database =
      //      flyway.migrate()
      new Database(system.queries, offsetBuilder, metrics)

    //    def migrateAndReset()(implicit
    //      executionContext: ExecutionContext[Migrator]
    //    ): Future[Migrator, Database] = {
    //      val db = migrate()
    //      db.inTransaction("ledger_reset") { queries =>
    //        Future.fromTry(queries.truncate())
    //      }(adminConnectionPool)
    //        .map(_ => db)
    //    }
    //
    //    def clear(): this.type = {
    //      flyway.clean()
    //      this
    //    }
  }

  sealed trait Context

  sealed trait Reader extends Context

  sealed trait Writer extends Context

  sealed trait Migrator extends Context

  final class ConnectionPool[+P](
    val dataSource:                DataSource
  )(implicit val executionContext: ExecutionContext[P]) {
    def acquireConnection(): Connection = dataSource.getConnection()
  }

  final class InvalidDatabaseException(message: String) extends RuntimeException(message) with StartupException

  final class ConnectionAcquisitionException(name: String, cause: Throwable)
      extends RuntimeException(s"""Failed to acquire the connection during "$name".""", cause)

}
