package co.topl.daml.driver

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.codahale.metrics.SharedMetricRegistries
import com.daml.daml_lf_dev.DamlLf.Archive
import com.daml.ledger.api.health.HealthChecks
import com.daml.ledger.configuration.{Configuration, LedgerTimeModel}
import com.daml.ledger.participant.state.index.v2.LedgerConfiguration
import com.daml.ledger.participant.state.v2.WritePackagesService
import com.daml.ledger.participant.state.v2.metrics.{TimedReadService, TimedWriteService}
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.lf.archive.{ArchivePayloadParser, DarParser, DarReader}
import com.daml.lf.data.Ref.SubmissionId
import com.daml.lf.engine.Engine
import com.daml.logging.LoggingContext.newLoggingContext
import com.daml.metrics.{JvmMetricSet, Metrics}
import com.daml.platform.apiserver.{ApiServerConfig, StandaloneApiServer}
import com.daml.platform.configuration.{CommandConfiguration, PartyConfiguration, SubmissionConfiguration}
import com.daml.platform.indexer.{IndexerConfig, StandaloneIndexerServer}
import com.daml.platform.store.LfValueTranslationCache
import com.daml.platform.store.dao.events.LfValueTranslation
import com.daml.resources.ProgramResource
import com.daml.telemetry.{DefaultTelemetryContext, OpenTelemetryTracer, Spans}
import io.opentelemetry.api.trace.Span
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

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
                config = IndexerConfig(config.participantId, config.jdbcUrl, config.startupMode, enableAppendOnlySchema = false),
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
                  databaseConnectionPoolSize = ???,
                  databaseConnectionTimeout = ???,
                  tlsConfig = config.tlsConfig,
                  maxInboundMessageSize = config.maxInboundMessageSize,
                  initialLedgerConfiguration = ???,
                  configurationLoadTimeout = ???,
                  eventsPageSize = config.eventsPageSize,
                  eventsProcessingParallelism = ???,
                  portFile = config.portFile.map(_.toPath),
                  seeding = config.seeding,
                  managementServiceTimeout = config.managementServiceTimeout,
                  enableAppendOnlySchema = true,
                  maxContractStateCacheSize = ???,
                  maxContractKeyStateCacheSize = ???,
                  enableMutableContractStateCache = ???,
                  maxTransactionsInMemoryFanOutBufferSize = ???,
                  enableInMemoryFanOutForLedgerApi = ???,
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
