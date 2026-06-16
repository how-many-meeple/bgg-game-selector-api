package bgg.store

import bgg.SafeOps.{decodeJson, tryAwsCall}
import bgg.domain.GameId
import bgg.vector.GameVector
import com.typesafe.scalalogging.{Logger, StrictLogging}
import io.circe.syntax.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.Instant
import scala.jdk.CollectionConverters.*

class DynamoDbVectorStore(client: DynamoDbClient, tableName: String) extends VectorStore with StrictLogging:

  private given Logger = logger

  def save(sv: StoredVector): Unit =
    val item = Map(
      "game_id" -> AttributeValue.fromN(sv.gameId.asString),
      "name" -> AttributeValue.fromS(sv.name),
      "vector" -> AttributeValue.fromS(sv.vector.values.asJson.noSpaces),
      "updated_at" -> AttributeValue.fromS(sv.updatedAt.toString)
    ).asJava

    tryAwsCall(
      client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build()),
      s"Error saving vector for game ${sv.gameId.value}"
    )

  def load(id: GameId): Option[StoredVector] =
    tryAwsCall(
      client.getItem(
        GetItemRequest
          .builder()
          .tableName(tableName)
          .key(Map("game_id" -> AttributeValue.fromN(id.asString)).asJava)
          .build()
      ),
      s"Error loading vector for game $id from DynamoDB"
    ).filter(_.hasItem).flatMap(response => parseItem(response.item()))

  def loadAll(): List[StoredVector] =
    tryAwsCall(scanAll(exclusiveStartKey = None, acc = Nil), "Error loading all vectors from DynamoDB")
      .map { result =>
        logger.info(s"Loaded ${result.size} vectors from DynamoDB")
        result
      }
      .getOrElse(Nil)

  @scala.annotation.tailrec
  private def scanAll(
      exclusiveStartKey: Option[java.util.Map[String, AttributeValue]],
      acc: List[StoredVector]
  ): List[StoredVector] =
    val reqBuilder = ScanRequest.builder().tableName(tableName)
    exclusiveStartKey.foreach(k => reqBuilder.exclusiveStartKey(k))
    val response = client.scan(reqBuilder.build())
    val page = response.items().asScala.flatMap(parseItem).toList
    val updated = acc ::: page
    if response.hasLastEvaluatedKey then scanAll(Some(response.lastEvaluatedKey()), updated)
    else updated

  private def parseItem(item: java.util.Map[String, AttributeValue]): Option[StoredVector] =
    decodeJson[Vector[Double]](item.get("vector").s(), "vector from DynamoDB").map { vec =>
      StoredVector(
        gameId = GameId(item.get("game_id").n().toInt),
        name = item.get("name").s(),
        vector = GameVector(vec),
        updatedAt = Instant.parse(item.get("updated_at").s())
      )
    }
