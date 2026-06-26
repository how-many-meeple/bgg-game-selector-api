package bgg.lambda

import bgg.bggapi.{BggXmlClient, GameService}
import bgg.cache.{DynamoDbCacheProvider, MemoryCacheProvider, SqliteCacheProvider}
import bgg.config.{AppConfig, CacheBackend}
import bgg.prefetch.{DynamoDbPrefetchStatusStore, SqlitePrefetchStatusStore}
import bgg.routes.{ApiEndpoints, AwsSqsSender, ErrorOutput, NoOpSqsSender}
import com.typesafe.scalalogging.StrictLogging
import ox.*
import sttp.client4.DefaultSyncBackend
import sttp.tapir.server.interceptor.cors.{CORSConfig, CORSInterceptor}
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.server.netty.sync.NettySyncServerOptions
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.shared.Identity
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient

import java.time.Instant

object ApiHandler extends OxApp.Simple with StrictLogging:

  def run(using Ox): Unit =
    val config = AppConfig.load()
    logger.info(s"Starting BGG Game Selector API v${bgg.BuildInfo.version} with ${config.cache.backend} backend")

    val deps = buildDependencies(config)
    startServer(deps, config)

  private def buildDependencies(config: AppConfig)(using Ox) =
    val httpBackend = useInScope(DefaultSyncBackend())(_.close())
    val bggClient = BggXmlClient(config.bgg, httpBackend)

    val (caches, prefetchStore, sqsSender) = config.cache.backend match
      case CacheBackend.DynamoDB =>
        val dynamo = useInScope(
          DynamoDbClient
            .builder()
            .region(Region.of(config.aws.region))
            .httpClient(UrlConnectionHttpClient.create())
            .build()
        )(_.close())
        val sqs = useInScope(
          SqsClient
            .builder()
            .region(Region.of(config.aws.region))
            .httpClient(UrlConnectionHttpClient.create())
            .build()
        )(_.close())
        (
          DynamoDbCacheProvider(dynamo, config.aws),
          DynamoDbPrefetchStatusStore(dynamo, config.aws.dynamoPrefetchTable),
          AwsSqsSender(sqs, config.aws.prefetchSqsUrl)
        )

      case CacheBackend.SQLite =>
        (
          SqliteCacheProvider(config.cache),
          SqlitePrefetchStatusStore(config.cache.sqlitePrefetchStatusPath),
          NoOpSqsSender()
        )

      case CacheBackend.Memory =>
        (
          MemoryCacheProvider(config.cache),
          SqlitePrefetchStatusStore(config.cache.sqlitePrefetchStatusPath),
          NoOpSqsSender()
        )

    val gameService = GameService(bggClient, caches, config.cache.vectorMinRatings, () => Instant.now())
    ApiEndpoints(gameService, caches.gameCache, caches.vectorStore, prefetchStore, sqsSender, config)

  private def startServer(endpoints: ApiEndpoints, config: AppConfig): Unit =
    val serverOptions = NettySyncServerOptions.customiseInterceptors
      .corsInterceptor(
        CORSInterceptor.customOrThrow[Identity](
          CORSConfig.default.maxAge(scala.concurrent.duration.Duration(1, "day"))
        )
      )
      .defaultHandlers(
        msg =>
          if msg == "Not Found" then ValuedEndpointOutput(ErrorOutput.failOutput, bgg.domain.Fail.NotFound(msg))
          else ValuedEndpointOutput(ErrorOutput.failOutput, bgg.domain.Fail.IncorrectInput(msg)),
        notFoundWhenRejected = true
      )
      .options

    NettySyncServer(serverOptions)
      .host(config.server.host)
      .port(config.server.port)
      .addEndpoints(endpoints.all)
      .startAndWait()

    logger.info(s"Server started on ${config.server.host}:${config.server.port}")
