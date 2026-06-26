package bgg.cache

import bgg.config.AwsConfig
import bgg.store.{DynamoDbVectorStore, VectorStore}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

class DynamoDbCacheProvider(client: DynamoDbClient, aws: AwsConfig) extends CacheProvider:
  val gameCache: GameCache = DynamoDbGameCache(client, aws.dynamoGameTable)
  val vectorStore: VectorStore = DynamoDbVectorStore(client, aws.dynamoVectorTable)
  val requestCache: RequestCache = DynamoDbRequestCache(client, aws.dynamoRequestTable)
  val playsCache: PlaysCache = DynamoDbPlaysCache(client, aws.dynamoPlaysTable)
