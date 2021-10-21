package co.topl.daml.driver

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.codahale.metrics.{MetricRegistry, SharedMetricRegistries}
import com.daml.ledger.api.health.HealthChecks
import com.daml.ledger.configuration.Configuration
import com.daml.ledger.participant.state.v2.WritePackagesService
import com.daml.ledger.participant.state.v2.metrics.{TimedReadService, TimedWriteService}
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.lf.archive.DarParser
import com.daml.lf.data.Ref.SubmissionId
import com.daml.lf.engine.Engine
import com.daml.logging.LoggingContext.newLoggingContext
import com.daml.metrics.{JvmMetricSet, Metrics}
import com.daml.platform.apiserver.{ApiServerConfig, StandaloneApiServer}
import com.daml.platform.configuration.{CommandConfiguration, InitialLedgerConfiguration, PartyConfiguration, SubmissionConfiguration}
import com.daml.platform.indexer.{IndexerConfig, StandaloneIndexerServer}
import com.daml.platform.sandbox.config.SandboxConfig
import com.daml.platform.store.LfValueTranslationCache
import com.daml.resources.ProgramResource
import com.daml.telemetry.{DefaultTelemetryContext, OpenTelemetryTracer}
import io.opentelemetry.api.trace.Span
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import com.daml.platform.store.backend

object BifrostDriver extends App {

  implicit val telemetry = DefaultTelemetryContext(OpenTelemetryTracer, Span.current())

  val logger = LoggerFactory.getLogger(this.getClass)

  val config: co.topl.daml.driver.Config = Cli
    .parse(
      args,
      "daml-on-bifrost",
      "A fully compliant DAML Ledger API server backed by the Topl Protocol"
    )
    .getOrElse(sys.exit(1))

  private val metricsRegistry =
    SharedMetricRegistries.getOrCreate(s"ledger-api-server-${config.participantId}")
  private val metrics = new Metrics(metricsRegistry)

  // Initialize Akka and log exceptions in flows.
  val authService = config.authService

  /**
    * Initialize a set of resources to represent a driver server implementation.
    *
    * When a [[BifrostDriver]] is designated as a ''ledger'' role, this driver uses these components:
    * - [[MetricRegistry]]: Collects metrics about the JVM as the server is running
    * - [[BifrostParticipantState]]: Ledger state, allows reads and writes to the ledger through
    *       the ledger API.
    * - [[Config.archiveFiles]]: List of DAR files declared through the CLI and uploaded at runtime.
    * - [[scala.concurrent.ExecutionContextExecutorService]]: Used in [[StandaloneIndexerServer]] and
    *       [[StandaloneApiServer]] to prevent deadlocks when releasing these resources upon shutdown.
    * - [[StandaloneIndexerServer]]: Initializes and updates the contract & event database. Also handles
    *       database migrations with Flyway.
    * - [[StandaloneApiServer]]: Instantiates the API server to receive requests from Daml applications
    *       and HTTP-JSON API requests.
    *
    * @return A single [[ResourceOwner]] for all Bifrost Driver services
    */
  def owner(): ResourceOwner[Unit] = new ResourceOwner[Unit] {
    override def acquire()(implicit context: ResourceContext): Resource[Unit] = {

      newLoggingContext { implicit loggingContext =>
        implicit val actorSystem: ActorSystem = ActorSystem("DamlonBifrostServer")
        implicit val materializer: Materializer = Materializer(actorSystem)

        // DAML Engine for transaction validation.
        val sharedEngine = Engine.StableEngine()
        for {
          // Take ownership of the actor system and materializer so they're cleaned up properly.
          // This is necessary because we can't declare them as implicits in a `for` comprehension.
          _ <- ResourceOwner.forActorSystem(() => actorSystem).acquire()
          _ <- ResourceOwner.forMaterializer(() => materializer).acquire()

          // initialize all configured participants
          _ <- {
            metricsRegistry.registerAll(new JvmMetricSet)
            val lfValueTranslationCache = LfValueTranslationCache.Cache.newInstrumentedInstance(
              eventConfiguration = config.lfValueTranslationEventCacheConfiguration,
              contractConfiguration = config.lfValueTranslationContractCacheConfiguration,
              metrics = metrics
            )
            for {
              ledger <- ResourceOwner
                .forCloseable(
                  () =>
                    new BifrostParticipantState(
                      config.participantId,
                      metrics,
                      sharedEngine
                    )
                )
                .acquire() if config.roleLedger
              _ <- Resource.fromFuture(
                Future.sequence(config.archiveFiles.map(uploadDar(_, ledger)))
              ) if config.roleLedger
              servicesExecutionContext <- ResourceOwner
                .forExecutorService(() => Executors.newWorkStealingPool())
                .map(ExecutionContext.fromExecutorService)
                .acquire()
              _ <- new StandaloneIndexerServer(
                readService = ledger,
                config = IndexerConfig(
                  config.participantId,
                  config.jdbcUrl,
                  config.startupMode,
                  // as of Daml sdk v17.1.1, this must be true because the `ledger_end_sequential_id` column
                  // in the `parameters` table is not created for this upon initialization.
                  enableAppendOnlySchema = true,
                  allowExistingSchema = true
                ),
                servicesExecutionContext = servicesExecutionContext,
                metrics = metrics,
                lfValueTranslationCache = lfValueTranslationCache
              ).acquire() if config.roleLedger
              _ <- new StandaloneApiServer(
                ledgerId = ledger.ledgerId,
                config = ApiServerConfig(
                  participantId = config.participantId,
                  archiveFiles = config.archiveFiles.map(_.toFile),
                  port = config.port,
                  address = config.address,
                  jdbcUrl = config.jdbcUrl,
                  databaseConnectionPoolSize = SandboxConfig.DefaultDatabaseConnectionPoolSize,
                  databaseConnectionTimeout = SandboxConfig.DefaultDatabaseConnectionTimeout,
                  tlsConfig = config.tlsConfig,
                  maxInboundMessageSize = config.maxInboundMessageSize,
                  initialLedgerConfiguration = Some(InitialLedgerConfiguration(
                    configuration = Configuration.reasonableInitialConfiguration,
                    delayBeforeSubmitting = java.time.Duration.ofSeconds(1)
                  )),
                  configurationLoadTimeout = java.time.Duration.ofSeconds(10),
                  eventsPageSize = config.eventsPageSize,
                  eventsProcessingParallelism = SandboxConfig.DefaultEventsProcessingParallelism,
                  portFile = config.portFile.map(_.toPath),
                  seeding = config.seeding,
                  managementServiceTimeout = config.managementServiceTimeout,
                  enableAppendOnlySchema = true,
                  maxContractStateCacheSize = 100,
                  maxContractKeyStateCacheSize = 100,
                  enableMutableContractStateCache = false,
                  maxTransactionsInMemoryFanOutBufferSize = 100,
                  enableInMemoryFanOutForLedgerApi = true,
                ),
                commandConfig = CommandConfiguration.default,
                partyConfig = PartyConfiguration(false),
                submissionConfig = SubmissionConfiguration.default,
                optWriteService = Some(ledger),
                authService = authService,
                healthChecks = new HealthChecks(
                  "read" -> new TimedReadService(ledger, metrics),
                  "write" -> new TimedWriteService(ledger, metrics)
                ),
                metrics = metrics,
                engine = sharedEngine,
                lfValueTranslationCache = lfValueTranslationCache,
                servicesExecutionContext = servicesExecutionContext
              ).acquire() if config.roleLedger
            } yield ()
          }
        } yield ()
      }
    }
  }

  private def uploadDar(from: Path, to: WritePackagesService)(
    implicit executionContext: ExecutionContext
  ): Future[Unit] = {
    val submissionId = SubmissionId.assertFromString(UUID.randomUUID().toString)
    for {
      dar <- Future(
        DarParser.readArchiveFromFile(from.toFile) match {
          case Left(err) => throw new Error(err.msg)
          case Right(archive) => archive
        }
      )
      _ <- to.uploadPackages(submissionId, dar.all, None).toScala
    } yield ()
  }
  new ProgramResource(owner()).run(ResourceContext.apply)

}
