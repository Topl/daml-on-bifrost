package com.daml.platform.apiserver

import akka.actor.ActorSystem
import akka.stream.Materializer
import co.topl.daml.driver.BifrostDriver.config
import com.codahale.metrics.SharedMetricRegistries
import com.daml.ledger.resources.ResourceContext
import com.daml.metrics.Metrics
import com.daml.ports.Port

import scala.concurrent.ExecutionContext

object MinimalGrpcServer extends App {

  implicit val actorSystem = ActorSystem("minimal-grpc-server")
  implicit val executionContext = actorSystem.getDispatcher

  private val metricsRegistry =
    SharedMetricRegistries.getOrCreate(s"ledger-api-server-reference")
  private val metrics = new Metrics(metricsRegistry)

  val executionSequencerFactory = new ExecutionSequencerFactoryOwner()

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
