package bgg.cache

import bgg.domain.{GameData, GameId}
import com.typesafe.scalalogging.StrictLogging
import io.circe.parser.decode
import io.circe.syntax.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.Instant
import scala.jdk.CollectionConverters.*

class DynamoDbGameCache(client: DynamoDbClient, tableName: String, ttlSeconds: Int)
    extends GameCache
    with StrictLogging:

  // No-op: DynamoDB TTL handles eviction automatically
  def evictExpired(): Unit = ()

  def save(game: GameData): Unit =
    val ttl = Instant.now().getEpochSecond + ttlSeconds
    val item = Map(
      "id"              -> AttributeValue.fromS(game.id.value.toString),
      "data"            -> AttributeValue.fromS(game.asJson.noSpaces),
      "cache_timestamp" -> AttributeValue.fromS(Instant.now().toString),
      "ttl"             -> AttributeValue.fromN(ttl.toString),
    ).asJava

    val request = PutItemRequest.builder()
      .tableName(tableName)
      .item(item)
      // Only write if not already cached — avoids redundant writes
      .conditionExpression("attribute_not_exists(id)")
      .build()

    try
      client.putItem(request)
      logger.debug(s"Cached game ${game.id.value} (${game.name})")
    catch
      case _: ConditionalCheckFailedException =>
        logger.debug(s"Game ${game.id.value} already cached")
      case e: Exception =>
        logger.error(s"Error saving game ${game.id.value} to cache", e)

  def load(id: GameId): Option[GameData] =
    val request = GetItemRequest.builder()
      .tableName(tableName)
      .key(Map("id" -> AttributeValue.fromS(id.value.toString)).asJava)
      .build()

    try
      val response = client.getItem(request)
      if response.hasItem then
        decode[GameData](response.item().get("data").s()) match
          case Right(g) => Some(g)
          case Left(e)  =>
            logger.error(s"Failed to decode game $id from DynamoDB", e)
            None
      else None
    catch
      case e: Exception =>
        logger.error(s"Error loading game $id from DynamoDB", e)
        None
