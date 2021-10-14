package com.daml.platform.apiserver

import akka.actor.ActorSystem
import akka.stream.Materializer
import co.topl.daml.driver.BifrostDriver.config
import com.codahale.metrics.SharedMetricRegistries
import com.daml.ledger.resources.{ResourceContext, ResourceOwner}
import com.daml.logging.LoggingContext
import com.daml.metrics.Metrics
import com.daml.platform.apiserver.services.transaction.ApiTransactionService
import com.daml.ports.Port
import com.daml.logging.LoggingContext.newLoggingContext

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object MinimalGrpcServer extends App {

  newLoggingContext { implicit loggingContext =>

    implicit val actorSystem = ActorSystem("minimal-grpc-server")
    implicit val executionContext = actorSystem.getDispatcher
    implicit val loggingContext = LoggingContext


    val metricsRegistry =
      SharedMetricRegistries.getOrCreate(s"ledger-api-server-reference")
    val metrics = new Metrics(metricsRegistry)

    val executionSequencerFactory = new ExecutionSequencerFactoryOwner()


//    val apiTransactionService = ApiTransactionService.create("bifrost",)

    val grpcServer = GrpcServer.owner(
      address = Some("localhost"),
      desiredPort = Port(6865),
      maxInboundMessageSize = 512,
      sslContext = None,
      interceptors = List.empty,
      metrics = metrics,
      servicesExecutor = executionContext,
      services = Seq()
    )
  }
}
