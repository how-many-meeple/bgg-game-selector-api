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

  private val BatchGetMaxKeys = 100

  def load(id: GameId): Option[GameData] =
    val request = GetItemRequest
      .builder()
      .tableName(tableName)
      .key(Map("id" -> AttributeValue.fromS(id.asString)).asJava)
      .build()

    tryAwsCall(client.getItem(request), s"Error loading game $id from DynamoDB")
      .filter(_.hasItem)
      .flatMap(response => decodeJson[GameData](response.item().get("data").s(), s"game $id from DynamoDB"))

  private val BatchGetMaxRetries = 3

  override def loadBatch(ids: List[GameId]): List[GameData] =
    if ids.isEmpty then return Nil
    ids.distinct
      .grouped(BatchGetMaxKeys)
      .flatMap { chunk =>
        fetchBatchWithRetry(
          chunk.map(id => Map("id" -> AttributeValue.fromS(id.asString)).asJava),
          Nil,
          BatchGetMaxRetries
        )
      }
      .toList

  @scala.annotation.tailrec
  private def fetchBatchWithRetry(
      keys: List[java.util.Map[String, AttributeValue]],
      acc: List[GameData],
      retriesLeft: Int
  ): List[GameData] =
    val keysAndAttrs = KeysAndAttributes.builder().keys(keys.asJava).build()
    val request = BatchGetItemRequest.builder().requestItems(Map(tableName -> keysAndAttrs).asJava).build()
    tryAwsCall(client.batchGetItem(request), "Error batch-loading games from DynamoDB") match
      case None => acc
      case Some(response) =>
        val items = response.responses().getOrDefault(tableName, java.util.Collections.emptyList()).asScala
        val decoded = items.flatMap { item =>
          Option(item.get("data")).flatMap(attr => decodeJson[GameData](attr.s(), "batch game from DynamoDB"))
        }.toList
        val unprocessed = response.unprocessedKeys().asScala.get(tableName)
        unprocessed match
          case Some(remaining) if remaining.keys().size() > 0 && retriesLeft > 0 =>
            logger.warn(s"DynamoDB returned ${remaining.keys().size()} unprocessed keys, retrying")
            fetchBatchWithRetry(remaining.keys().asScala.toList, acc ++ decoded, retriesLeft - 1)
          case Some(remaining) if remaining.keys().size() > 0 =>
            logger.error(s"DynamoDB still has ${remaining.keys().size()} unprocessed keys after retries")
            acc ++ decoded
          case _ => acc ++ decoded
