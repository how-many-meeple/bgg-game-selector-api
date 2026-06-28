package bgg.cache

import bgg.SafeOps.{decodeJson, tryAwsCall}
import bgg.domain.PlayData
import com.typesafe.scalalogging.{Logger, StrictLogging}
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.Instant
import scala.jdk.CollectionConverters.*

class DynamoDbPlaysCache(client: DynamoDbClient, tableName: String, clock: () => Instant = () => Instant.now())
    extends PlaysCache
    with StrictLogging:

  private given Logger = logger
  private given Encoder[List[PlayData]] = Encoder.encodeList(PlayData.encoder)
  private given Decoder[List[PlayData]] = Decoder.decodeList(playDataDecoder)

  private val playDataDecoder: Decoder[PlayData] = Decoder.instance { c =>
    import bgg.domain.{GameId, PlayPlayer}
    for
      playId <- c.downField("play_id").as[Int]
      gameId <- c.downField("game_id").as[Int]
      gameName <- c.downField("game_name").as[String]
      date <- c.downField("date").as[String]
      quantity <- c.downField("quantity").as[Int]
      length <- c.downField("length").as[Int]
      players <- c.downField("players").as[List[PlayPlayer]]
    yield PlayData(playId, GameId(gameId), gameName, date, quantity, length, players)
  }

  private given Decoder[bgg.domain.PlayPlayer] = Decoder.instance { c =>
    for
      username <- c.downField("username").as[String]
      name <- c.downField("name").as[String]
      score <- c.downField("score").as[Option[String]]
      win <- c.downField("win").as[Boolean]
    yield bgg.domain.PlayPlayer(username, name, score, win)
  }

  private val TtlDays = 7L

  def save(username: String, plays: List[PlayData]): Unit =
    val now = clock()
    val ttl = now.plusSeconds(TtlDays * 24 * 3600).getEpochSecond
    val item = Map(
      "username" -> AttributeValue.fromS(username.toLowerCase),
      "data" -> AttributeValue.fromS(plays.asJson.noSpaces),
      "cached_at" -> AttributeValue.fromN(now.getEpochSecond.toString),
      "ttl" -> AttributeValue.fromN(ttl.toString)
    ).asJava

    val request = PutItemRequest.builder().tableName(tableName).item(item).build()
    try
      client.putItem(request)
      logger.debug(s"Cached ${plays.size} plays for $username")
    catch
      case e: Exception =>
        logger.error(s"Error saving plays for $username", e)

  def load(username: String): Option[List[PlayData]] =
    val request = GetItemRequest
      .builder()
      .tableName(tableName)
      .key(Map("username" -> AttributeValue.fromS(username.toLowerCase)).asJava)
      .build()

    tryAwsCall(client.getItem(request), s"Error loading plays for $username")
      .filter(_.hasItem)
      .flatMap(response => decodeJson[List[PlayData]](response.item().get("data").s(), s"plays for $username"))

  def isFresh(username: String, maxAgeSeconds: Long): Boolean =
    val request = GetItemRequest
      .builder()
      .tableName(tableName)
      .key(Map("username" -> AttributeValue.fromS(username.toLowerCase)).asJava)
      .projectionExpression("cached_at")
      .build()

    tryAwsCall(client.getItem(request), s"Error checking freshness for $username")
      .filter(_.hasItem)
      .flatMap { response =>
        Option(response.item().get("cached_at")).map(_.n().toLong)
      }
      .exists { cachedAt =>
        val age = clock().getEpochSecond - cachedAt
        age < maxAgeSeconds
      }
