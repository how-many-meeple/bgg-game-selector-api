package bgg.cache

import bgg.SafeOps.{decodeJson, tryAwsCall}
import com.typesafe.scalalogging.{Logger, StrictLogging}
import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.Instant
import scala.jdk.CollectionConverters.*

trait RequestCache:
  def load[T: Decoder](key: String): Option[T]
  def save[T: Encoder](key: String, value: T, ttlSeconds: Long, now: Instant): Unit

class DynamoDbRequestCache(client: DynamoDbClient, tableName: String) extends RequestCache with StrictLogging:

  private given Logger = logger

  def load[T: Decoder](key: String): Option[T] =
    val request = GetItemRequest
      .builder()
      .tableName(tableName)
      .key(Map("id" -> AttributeValue.fromS(key)).asJava)
      .build()

    tryAwsCall(client.getItem(request), s"Error loading request cache key '$key'")
      .filter(_.hasItem)
      .flatMap(response => decodeJson[T](response.item().get("data").s(), s"request cache '$key'"))

  def save[T: Encoder](key: String, value: T, ttlSeconds: Long, now: Instant): Unit =
    val ttlEpoch = now.getEpochSecond + ttlSeconds
    val item = Map(
      "id" -> AttributeValue.fromS(key),
      "data" -> AttributeValue.fromS(value.asJson.noSpaces),
      "ttl" -> AttributeValue.fromN(ttlEpoch.toString)
    ).asJava

    val request = PutItemRequest.builder().tableName(tableName).item(item).build()
    try client.putItem(request): Unit
    catch
      case e: Exception =>
        logger.error(s"Error saving request cache key '$key'", e)

class NoOpRequestCache extends RequestCache:
  def load[T: Decoder](key: String): Option[T] = None
  def save[T: Encoder](key: String, value: T, ttlSeconds: Long, now: Instant): Unit = ()
