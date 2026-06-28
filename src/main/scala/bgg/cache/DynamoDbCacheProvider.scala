package bgg.cache

import bgg.config.AwsConfig
import bgg.store.{DynamoDbVectorStore, VectorStore}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import java.time.Instant

class DynamoDbCacheProvider(client: DynamoDbClient, aws: AwsConfig, clock: () => Instant = () => Instant.now())
    extends CacheProvider:
  val gameCache: GameCache = DynamoDbGameCache(client, aws.dynamoGameTable)
  val vectorStore: VectorStore = DynamoDbVectorStore(client, aws.dynamoVectorTable)
  val requestCache: RequestCache = DynamoDbRequestCache(client, aws.dynamoRequestTable)
  val playsCache: PlaysCache = DynamoDbPlaysCache(client, aws.dynamoPlaysTable, clock)
