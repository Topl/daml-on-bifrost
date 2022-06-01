package co.topl.daml.driver

import com.daml.api.util.TimeProvider
import com.daml.caching
import com.daml.ledger.api.auth.{AuthService, AuthServiceWildcard}
import com.daml.ledger.api.tls.TlsConfiguration
import com.daml.ledger.participant.state.kvutils.app.ParticipantConfig
import com.daml.lf.data.Ref.ParticipantId
import com.daml.platform.apiserver.SeedService.Seeding
import com.daml.platform.configuration.IndexConfiguration
import com.daml.platform.indexer.IndexerStartupMode
import com.daml.ports.Port

import java.io.File
import java.nio.file.Path
import java.time.Duration

final case class Config(
  port:                                         Port,
  portFile:                                     Option[File],
  archiveFiles:                                 List[Path],
  maxInboundMessageSize:                        Int,
  eventsPageSize:                               Int,
  stateValueCache:                              caching.WeightedCache.Configuration,
  lfValueTranslationEventCacheConfiguration:    caching.SizedCache.Configuration,
  lfValueTranslationContractCacheConfiguration: caching.SizedCache.Configuration,
  timeProvider:                                 TimeProvider,
  address:                                      Option[String],
  jdbcUrl:                                      String,
  tlsConfig:                                    Option[TlsConfiguration],
  participantId:                                ParticipantId,
  startupMode:                                  IndexerStartupMode,
  roleLedger:                                   Boolean,
  roleTime:                                     Boolean,
  roleProvision:                                Boolean,
  roleExplorer:                                 Boolean,
  authService:                                  AuthService,
  seeding:                                      Seeding,
  managementServiceTimeout:                     Duration
) {

  def withTlsConfig(modify: TlsConfiguration => TlsConfiguration): Config =
    copy(tlsConfig = Some(modify(tlsConfig.getOrElse(TlsConfiguration.Empty))))
}

object Config {
  val DefaultMaxInboundMessageSize = 4194304

  def default: Config =
    new Config(
      port = Port(0),
      portFile = None,
      archiveFiles = List.empty,
      maxInboundMessageSize = DefaultMaxInboundMessageSize,
      eventsPageSize = IndexConfiguration.DefaultEventsPageSize,
      stateValueCache = caching.WeightedCache.Configuration.none,
      lfValueTranslationEventCacheConfiguration = caching.SizedCache.Configuration.none,
      lfValueTranslationContractCacheConfiguration = caching.SizedCache.Configuration.none,
      timeProvider = TimeProvider.UTC,
      address = Some("0.0.0.0"),
      jdbcUrl = "jdbc:h2:mem:daml_on_bifrost;db_close_delay=-1;db_close_on_exit=false",
      tlsConfig = None,
      participantId = ParticipantId.assertFromString("bifrost-standalone-participant"),
      startupMode = IndexerStartupMode.MigrateAndStart,
      roleLedger = false,
      roleTime = false,
      roleProvision = false,
      roleExplorer = false,
      authService = AuthServiceWildcard,
      seeding = Seeding.Weak,
      managementServiceTimeout = ParticipantConfig.DefaultManagementServiceTimeout
    )
}
