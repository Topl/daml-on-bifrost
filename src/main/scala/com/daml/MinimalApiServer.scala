//package com.daml
//
//import akka.actor.ActorSystem
//import akka.stream.Materializer
//import com.daml.ledger.participant.state.kvutils.app.ParticipantRunMode.LedgerApiServer
//import com.daml.ledger.resources.{ResourceContext, ResourceOwner}
//import com.daml.logging.LoggingContext
//import com.daml.platform.apiserver.{ApiServer, ApiServices, ExecutionSequencerFactoryOwner, LedgerApiServer, TimeServiceBackend}
//import com.daml.platform.configuration.{CommandConfiguration, PartyConfiguration}
//import io.grpc.BindableService
//
//import scala.collection.immutable
//
//object MinimalApiServer extends App {
//
//
//}
//
///**
//  * MinimalApiServer is a basic reference implementation of the Daml ledger
//  * API server for interacting between a Daml contract, ledger driver, and Bifrost node.
//  */
//final class MinimalApiServer()(implicit actorSystem: ActorSystem, materializer: Materializer, loggingContext: LoggingContext)
//  extends ResourceOwner[ApiServer] {
//
//  /**
//    * Initialize the required resources to build a ledger API server. The server
//    * and its components are bound to thread pools that handle execution, message
//    * passing, and proper closing on shutdown.
//    *
//    * @param context the execution context for the resources to use
//    * @return an instance of the Ledger API server
//    */
//  override def acquire()(implicit context: ResourceContext): LedgerApiServer = {
//    // used to create multiple LedgerApiServer instances
//    val executionSequencerFactory = new ExecutionSequencerFactoryOwner()
//    // initializes the set of basic ledger services the API allows to be called
//    val apiServicesOwner = new ApiServices.Owner(
//      // unique ID for this specific participant node
//      participantId = "MinimalApiServer",
//      // allow transactions and commits to be made to contracts and the underlying ledger
//      optWriteService = None,
//      // handles storing and updating daml contract packages
//      indexService = ???,
//      // validates that the participant can make changes to the contracts and/or ledger
//      authorizer = ???,
//      // evaluates transactions and commands
//      engine = ???,
//      // helper to provide correct time for updates and timestamps
//      timeProvider = ???,
//      // can choose between "static time" and "wall-clock time"
//      timeProviderType = ???,
//      // change delay time to connect to ledger and how long to wait/retry connecting to ledger
//      ledgerConfiguration = ???,
//      // configure buffer, backpressure, and parallelization of command requests sent to driver
//      commandConfig = CommandConfiguration.default,
//      // set whether new parties can be created on the fly
//      partyConfig = PartyConfiguration.default,
//      optTimeServiceBackend = None,
//      servicesExecutionContext = executionSequencerFactory.executionContext,
//      metrics = ???,
//      healthChecks = ???,
//      seedService = ???,
//      managementServiceTimeout = ???
//    )
//    new LedgerApiServer()
//  }
//
//
//  val grpcServer = GrpcServer
//
//}