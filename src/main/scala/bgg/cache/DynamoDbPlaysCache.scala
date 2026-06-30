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
  private val MetaPage = 0

  def save(username: String, plays: List[PlayData]): Unit =
    val now = clock()
    val ttl = now.plusSeconds(TtlDays * 24 * 3600).getEpochSecond
    deleteAllPages(username)
    val maxId = plays.map(_.playId).maxOption.getOrElse(0)
    putPage(username, MetaPage, plays, now, ttl, maxId)

  def load(username: String): Option[List[PlayData]] =
    val request = QueryRequest
      .builder()
      .tableName(tableName)
      .keyConditionExpression("username = :u")
      .expressionAttributeValues(Map(":u" -> AttributeValue.fromS(username.toLowerCase)).asJava)
      .build()

    tryAwsCall(client.query(request), s"Error loading plays for $username")
      .filter(_.hasItems)
      .map { response =>
        response
          .items()
          .asScala
          .toList
          .flatMap { item =>
            decodeJson[List[PlayData]](item.get("data").s(), s"plays chunk for $username").getOrElse(Nil)
          }
          .sortBy(_.playId)(Ordering[Int].reverse)
      }
      .filter(_.nonEmpty)

  def isFresh(username: String, maxAgeSeconds: Long): Boolean =
    val request = GetItemRequest
      .builder()
      .tableName(tableName)
      .key(
        Map(
          "username" -> AttributeValue.fromS(username.toLowerCase),
          "page" -> AttributeValue.fromN(MetaPage.toString)
        ).asJava
      )
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

  def append(username: String, plays: List[PlayData]): Unit =
    val now = clock()
    val ttl = now.plusSeconds(TtlDays * 24 * 3600).getEpochSecond
    val nextPage = nextPageNumber(username)
    putPage(username, nextPage, plays, now, ttl)
    plays.map(_.playId).maxOption.foreach(updateMaxPlayId(username, _))

  def maxPlayId(username: String): Option[Int] =
    val request = GetItemRequest
      .builder()
      .tableName(tableName)
      .key(
        Map(
          "username" -> AttributeValue.fromS(username.toLowerCase),
          "page" -> AttributeValue.fromN(MetaPage.toString)
        ).asJava
      )
      .projectionExpression("max_play_id")
      .build()

    tryAwsCall(client.getItem(request), s"Error getting maxPlayId for $username")
      .filter(_.hasItem)
      .flatMap(r => Option(r.item().get("max_play_id")))
      .map(_.n().toInt)
      .filter(_ > 0)

  def touch(username: String): Unit =
    val now = clock()
    val ttl = now.plusSeconds(TtlDays * 24 * 3600).getEpochSecond
    val request = UpdateItemRequest
      .builder()
      .tableName(tableName)
      .key(
        Map(
          "username" -> AttributeValue.fromS(username.toLowerCase),
          "page" -> AttributeValue.fromN(MetaPage.toString)
        ).asJava
      )
      .updateExpression("SET cached_at = :now, #t = :ttl")
      .expressionAttributeNames(Map("#t" -> "ttl").asJava)
      .expressionAttributeValues(
        Map(
          ":now" -> AttributeValue.fromN(now.getEpochSecond.toString),
          ":ttl" -> AttributeValue.fromN(ttl.toString)
        ).asJava
      )
      .build()
    try client.updateItem(request): Unit
    catch case e: Exception => logger.warn(s"Error touching freshness for $username", e)

  private def putPage(username: String, page: Int, plays: List[PlayData], now: Instant, ttl: Long, maxId: Int = 0): Unit =
    val baseItem = Map(
      "username" -> AttributeValue.fromS(username.toLowerCase),
      "page" -> AttributeValue.fromN(page.toString),
      "data" -> AttributeValue.fromS(plays.asJson.noSpaces),
      "cached_at" -> AttributeValue.fromN(now.getEpochSecond.toString),
      "ttl" -> AttributeValue.fromN(ttl.toString)
    )
    val item = if maxId > 0 then baseItem + ("max_play_id" -> AttributeValue.fromN(maxId.toString))
    else baseItem

    val request = PutItemRequest.builder().tableName(tableName).item(item.asJava).build()
    try
      client.putItem(request)
      logger.debug(s"Cached ${plays.size} plays for $username (page $page)")
    catch
      case e: Exception =>
        logger.error(s"Error saving plays for $username page $page", e)

  private def updateMaxPlayId(username: String, newMaxId: Int): Unit =
    val request = UpdateItemRequest
      .builder()
      .tableName(tableName)
      .key(
        Map(
          "username" -> AttributeValue.fromS(username.toLowerCase),
          "page" -> AttributeValue.fromN(MetaPage.toString)
        ).asJava
      )
      .updateExpression("SET max_play_id = :newMax")
      .conditionExpression("attribute_not_exists(max_play_id) OR max_play_id < :newMax")
      .expressionAttributeValues(Map(":newMax" -> AttributeValue.fromN(newMaxId.toString)).asJava)
      .build()
    try client.updateItem(request): Unit
    catch
      case _: ConditionalCheckFailedException => ()
      case e: Exception => logger.warn(s"Error updating maxPlayId for $username", e)

  private def nextPageNumber(username: String): Int =
    val request = QueryRequest
      .builder()
      .tableName(tableName)
      .keyConditionExpression("username = :u")
      .expressionAttributeValues(Map(":u" -> AttributeValue.fromS(username.toLowerCase)).asJava)
      .projectionExpression("#p")
      .expressionAttributeNames(Map("#p" -> "page").asJava)
      .scanIndexForward(false)
      .limit(1)
      .build()

    tryAwsCall(client.query(request), s"Error getting next page for $username")
      .filter(_.hasItems)
      .map(_.items().get(0).get("page").n().toInt + 1)
      .getOrElse(1)

  private def deleteAllPages(username: String): Unit =
    val request = QueryRequest
      .builder()
      .tableName(tableName)
      .keyConditionExpression("username = :u")
      .expressionAttributeValues(Map(":u" -> AttributeValue.fromS(username.toLowerCase)).asJava)
      .projectionExpression("username, #p")
      .expressionAttributeNames(Map("#p" -> "page").asJava)
      .build()

    tryAwsCall(client.query(request), s"Error listing pages for $username")
      .filter(_.hasItems)
      .foreach { response =>
        response.items().asScala.foreach { item =>
          val deleteReq = DeleteItemRequest
            .builder()
            .tableName(tableName)
            .key(
              Map(
                "username" -> item.get("username"),
                "page" -> item.get("page")
              ).asJava
            )
            .build()
          try client.deleteItem(deleteReq)
          catch case e: Exception => logger.warn(s"Error deleting page for $username", e)
        }
      }
