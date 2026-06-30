// Scala 3.3.x is the LTS line; 3.8.x is current. Using LTS for Lambda/GraalVM toolchain stability.
val scala3Version = "3.3.8"

ThisBuild / organization := "bgg"
ThisBuild / scalaVersion := scala3Version
ThisBuild / version      := "3.4.0"

ThisBuild / scalacOptions ++= Seq(
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-deprecation",
  "-feature",
)

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys    := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "bgg",
    name := "bgg-game-selector-api",
    libraryDependencies ++= Seq(
      // Direct-style HTTP server (synchronous Tapir + Ox, no IO monad)
      "com.softwaremill.sttp.tapir" %% "tapir-netty-server-sync"  % "1.13.21",
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"         % "1.13.21",
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle"  % "1.13.21",
      // AWS Lambda native integration (Tapir handles API Gateway event decoding)
      "com.softwaremill.sttp.tapir" %% "tapir-aws-lambda-core"    % "1.13.21",
      // Structured concurrency
      "com.softwaremill.ox"         %% "core"                     % "1.0.5",
      // HTTP client for BGG API (synchronous sttp backend)
      "com.softwaremill.sttp.client4" %% "core"                   % "4.0.25",
      "com.softwaremill.sttp.client4" %% "circe"                  % "4.0.25",
      // JSON
      "io.circe"                    %% "circe-core"               % "0.14.14",
      "io.circe"                    %% "circe-generic"            % "0.14.14",
      "io.circe"                    %% "circe-parser"             % "0.14.14",
      // XML parsing (BGG API v1 & v2 responses)
      "org.scala-lang.modules"      %% "scala-xml"                % "2.3.0",
      // AWS SDK v2 — url-connection-client is sync and GraalVM-friendly (no Netty)
      "software.amazon.awssdk"       % "dynamodb"                 % "2.46.11",
      "software.amazon.awssdk"       % "sqs"                      % "2.46.11",
      "software.amazon.awssdk"       % "sfn"                      % "2.46.11",
      "software.amazon.awssdk"       % "url-connection-client"    % "2.46.11",
      // SQLite (local dev / tests)
      "org.xerial"                   % "sqlite-jdbc"              % "3.45.1.0",
      // Logging
      "com.typesafe.scala-logging"  %% "scala-logging"            % "3.9.5",
      "ch.qos.logback"               % "logback-classic"          % "1.5.6",
      // Config
      "com.typesafe"                 % "config"                   % "1.4.3",
      // AWS Lambda runtime (API Gateway proxy integration)
      "com.amazonaws"                % "aws-lambda-java-core"     % "1.2.3",
      "com.amazonaws"                % "aws-lambda-java-events"   % "3.11.6",
      // Testing
      "org.scalatest"               %% "scalatest"                % "3.2.18"  % Test,
      "org.scalamock"               %% "scalamock"                % "6.0.0"   % Test,
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-stub4-server" % "1.13.21" % Test,
      "org.testcontainers"           % "testcontainers"           % "1.20.4"  % Test,
      "org.testcontainers"           % "localstack"               % "1.20.4"  % Test,
    ),

    // Fat jar for Lambda deployment — NativeBootstrap speaks Lambda Runtime API directly
    assembly / assemblyJarName := "bgg-api-assembly.jar",
    assembly / mainClass := Some("bgg.lambda.NativeBootstrap"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "native-image", "org.xerial", _*)   => MergeStrategy.discard
      case PathList("META-INF", "native-image", "software.amazon.awssdk", "netty-nio-client", _*) => MergeStrategy.discard
      case PathList("META-INF", "native-image", _*) => MergeStrategy.first
      case PathList("META-INF", "services", _*)     => MergeStrategy.concat
      case PathList("META-INF", _*)                 => MergeStrategy.discard
      case PathList("reference.conf")               => MergeStrategy.concat
      case _                                        => MergeStrategy.first
    },

    coverageMinimumStmtTotal := 90,
    coverageMinimumBranchTotal := 90,
    coverageFailOnMinimum := true,
    coverageExcludedPackages := Seq(
      "bgg\\.lambda\\.NativeBootstrap.*",
      "bgg\\.lambda\\.ApiHandler.*",
      "bgg\\.lambda\\.ApiLambdaHandler",
      "bgg\\.lambda\\.PrefetchWorker",
      "bgg\\.lambda\\.PrefetchWorkerLogic\\$",
      "bgg\\.routes\\.AwsSqsSender",
      "bgg\\.routes\\.StepFunctionsTrigger",
      "bgg\\.routes\\.ErrorOutput.*",
      "bgg\\.cache\\.SqliteCacheProvider",
      "bgg\\.cache\\.MemoryCacheProvider",
    ).mkString(";"),
    coverageExcludedFiles := Seq(
      ".*DynamoDbGameCache.*",
      ".*DynamoDbPlaysCache.*",
      ".*DynamoDbRequestCache.*",
      ".*DynamoDbCacheProvider.*",
      ".*DynamoDbPrefetchStatusStore.*",
    ).mkString(";"),

    Test / scalacOptions --= Seq("-Wvalue-discard", "-Wnonunit-statement"),
    Test / fork := true,
    Test / javaHome := Some(file(sys.env.getOrElse("JAVA_HOME", "/usr/lib/jvm/java-21"))),
    Test / javaOptions += "-Xmx1g",

    Compile / mainClass := Some("bgg.lambda.ApiHandler"),
  )

