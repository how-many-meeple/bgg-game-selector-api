package bgg.lambda

import bgg.bggapi.{BggXmlClient, GameService}
import bgg.cache.{DynamoDbGameCache, DynamoDbRequestCache}
import bgg.config.AppConfig
import bgg.prefetch.DynamoDbPrefetchStatusStore
import bgg.routes.{ApiEndpoints, AwsSqsSender}
import bgg.store.DynamoDbVectorStore
import io.circe.generic.auto.*
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.sqs.SqsClient
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

    val sqs = SqsClient
      .builder()
      .region(Region.of(config.aws.region))
      .httpClient(UrlConnectionHttpClient.create())
      .build()

    val gameCache = DynamoDbGameCache(dynamo, config.aws.dynamoGameTable)
    val vectorStore = DynamoDbVectorStore(dynamo, config.aws.dynamoVectorTable)
    val requestCache = DynamoDbRequestCache(dynamo, config.aws.dynamoRequestTable)
    val prefetchStore = DynamoDbPrefetchStatusStore(dynamo, config.aws.dynamoPrefetchTable)
    val sqsSender = AwsSqsSender(sqs, config.aws.prefetchSqsUrl)
    val gameService =
      GameService(bggClient, gameCache, vectorStore, requestCache, config.cache.vectorMinRatings, () => Instant.now())

    ApiEndpoints(gameService, gameCache, vectorStore, prefetchStore, sqsSender, config)
