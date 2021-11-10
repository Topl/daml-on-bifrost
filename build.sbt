import Dependencies._

ThisBuild / scalaVersion := "2.12.13"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "co.topl"
ThisBuild / organizationName := "Topl"

lazy val sdkVersion = "1.17.1"
lazy val akkaVersion = "2.6.17"
lazy val protobufVersion = "3.18.1"
lazy val logbackVersion = "1.2.6"

// This task is used by the integration test to detect which version of Ledger API Test Tool to use.
val printSdkVersion = taskKey[Unit]("printSdkVersion")
printSdkVersion := println(sdkVersion)

resolvers += "Artima Maven Repository" at "https://repo.artima.com/releases"

assemblyMergeStrategy in assembly := {
  case "META-INF/io.netty.versions.properties" =>
    // Looks like multiple versions patch versions of of io.netty are getting
    // into dependency graph, choose one.
    MergeStrategy.first
  case PathList(ps @ _*) if ps.last startsWith "com/fasterxml/jackson" =>
    MergeStrategy.first
  case "META-INF/versions/9/module-info.class" => MergeStrategy.first
  case PathList("google", "protobuf", n) if n endsWith ".proto" =>
    // Both in protobuf and akka
    MergeStrategy.first
  case "module-info.class" =>
    // In all 2.10 Jackson JARs
    MergeStrategy.discard
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
assemblyJarName in assembly := "daml-on-bifrost.jar"

lazy val root = (project in file("."))
  .settings(
    name := "DAML-on-Bifrost Ledger Implementation",
    libraryDependencies ++= Seq(
      scalaTest % Test,
      scalactic,
      "com.daml" % "daml-lf-dev-archive-java-proto" % sdkVersion,
      "com.daml" %% "contextualized-logging" % sdkVersion,
      "com.daml" %% "daml-lf-archive-reader" % sdkVersion,
      "com.daml" %% "daml-lf-data" % sdkVersion,
      "com.daml" %% "daml-lf-engine" % sdkVersion,
      "com.daml" %% "daml-lf-language" % sdkVersion,
      "com.daml" %% "daml-lf-transaction" % sdkVersion,
      "com.daml" %% "testing-utils" % sdkVersion % Test,

      "com.daml" %% "sandbox" % sdkVersion,
      "com.daml" %% "ledger-api-auth" % sdkVersion,

      "com.daml" %% "participant-state" % sdkVersion ,
      "com.daml" %% "participant-state-kvutils" % sdkVersion,
      "com.daml" %% "participant-state-kvutils-app" % sdkVersion,

      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,

      // Protobuf / grpc
      "com.google.protobuf" % "protobuf-java-util" % protobufVersion,
      "com.google.protobuf" % "protobuf-java" % protobufVersion,


      "org.slf4j" % "slf4j-api" % "1.7.32",
      "ch.qos.logback" % "logback-core" % logbackVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "commons-io" % "commons-io" % "2.11.0",

      "org.mongodb" % "mongodb-jdbc" % "1.0.3",

      "co.topl" %% "common" % "1.7.0",
      "co.topl" %% "brambl" % "1.7.0",

    )
  )
