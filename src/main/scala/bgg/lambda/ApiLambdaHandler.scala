package bgg.lambda

import bgg.bggapi.{BggXmlClient, GameService}
import bgg.cache.DynamoDbCacheProvider
import bgg.config.AppConfig
import bgg.prefetch.DynamoDbPrefetchStatusStore
import bgg.routes.{ApiEndpoints, StepFunctionsTrigger}
import io.circe.generic.auto.*
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.sfn.SfnClient
import sttp.client4.DefaultSyncBackend
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.serverless.aws.lambda.*
import sttp.shared.Identity

import java.time.Instant

class ApiLambdaHandler extends SyncLambdaHandler[AwsRequestV1](AwsSyncServerOptions.default):

  private val endpoints = ApiLambdaHandler.buildEndpoints()

  override def getAllEndpoints: List[ServerEndpoint[Any, Identity]] = endpoints.all

object ApiLambdaHandler:

  private[lambda] def buildEndpoints(): ApiEndpoints =
    val config = AppConfig.load()
    val httpBackend = DefaultSyncBackend()
    val bggClient = BggXmlClient(config.bgg, httpBackend)

    val dynamo = DynamoDbClient
      .builder()
      .region(Region.of(config.aws.region))
      .httpClient(UrlConnectionHttpClient.create())
      .build()

    val sfn = SfnClient
      .builder()
      .region(Region.of(config.aws.region))
      .httpClient(UrlConnectionHttpClient.create())
      .build()

    val caches = DynamoDbCacheProvider(dynamo, config.aws)
    val prefetchStore = DynamoDbPrefetchStatusStore(dynamo, config.aws.dynamoPrefetchTable)
    val prefetchTrigger = StepFunctionsTrigger(sfn, config.aws.prefetchStateMachineArn)
    val gameService = GameService(bggClient, caches, config.cache.vectorMinRatings, () => Instant.now())

    ApiEndpoints(gameService, caches.gameCache, caches.vectorStore, prefetchStore, prefetchTrigger, config)
