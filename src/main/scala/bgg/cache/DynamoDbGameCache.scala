package bgg.cache

import bgg.SafeOps.{decodeJson, tryAwsCall}
import bgg.domain.{GameData, GameId}
import com.typesafe.scalalogging.{Logger, StrictLogging}
import io.circe.syntax.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.Instant
import scala.jdk.CollectionConverters.*

class DynamoDbGameCache(client: DynamoDbClient, tableName: String) extends GameCache with StrictLogging:

  private given Logger = logger

  def evictExpired(): Unit = ()

  def save(game: GameData, now: Instant): Unit =
    val ttl = AdaptiveTtl.computeTtl(game.yearPublished, now)
    val item = Map(
      "id" -> AttributeValue.fromS(game.id.asString),
      "data" -> AttributeValue.fromS(game.asJson.noSpaces),
      "cache_timestamp" -> AttributeValue.fromS(now.toString),
      "ttl" -> AttributeValue.fromN((now.getEpochSecond + ttl.getSeconds).toString)
    ).asJava

    val request = PutItemRequest
      .builder()
      .tableName(tableName)
      .item(item)
      .conditionExpression("attribute_not_exists(id)")
      .build()

    try
      client.putItem(request)
      logger.debug(s"Cached game ${game.id.value} (${game.name}) with adaptive TTL ${ttl.toDays}d")
    catch
      case _: ConditionalCheckFailedException =>
        logger.debug(s"Game ${game.id.value} already cached")
      case e: Exception =>
        logger.error(s"Error saving game ${game.id.value} to cache", e)

  def load(id: GameId): Option[GameData] =
    val request = GetItemRequest
      .builder()
      .tableName(tableName)
      .key(Map("id" -> AttributeValue.fromS(id.asString)).asJava)
      .build()

    tryAwsCall(client.getItem(request), s"Error loading game $id from DynamoDB")
      .filter(_.hasItem)
      .flatMap(response => decodeJson[GameData](response.item().get("data").s(), s"game $id from DynamoDB"))
