package bgg.store

import bgg.domain.GameId
import bgg.vector.GameVector
import com.typesafe.scalalogging.StrictLogging
import io.circe.parser.decode
import io.circe.syntax.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.Instant
import scala.jdk.CollectionConverters.*

class DynamoDbVectorStore(client: DynamoDbClient, tableName: String) extends VectorStore with StrictLogging:

  def save(sv: StoredVector): Unit =
    val item = Map(
      "game_id" -> AttributeValue.fromN(sv.gameId.value.toString),
      "name" -> AttributeValue.fromS(sv.name),
      "vector" -> AttributeValue.fromS(sv.vector.values.asJson.noSpaces),
      "updated_at" -> AttributeValue.fromS(sv.updatedAt.toString)
    ).asJava

    try client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build()): Unit
    catch case e: Exception => logger.error(s"Error saving vector for game ${sv.gameId.value}", e)

  def load(id: GameId): Option[StoredVector] =
    try
      val response = client.getItem(
        GetItemRequest
          .builder()
          .tableName(tableName)
          .key(Map("game_id" -> AttributeValue.fromN(id.value.toString)).asJava)
          .build()
      )
      if response.hasItem then parseItem(response.item()) else None
    catch
      case e: Exception =>
        logger.error(s"Error loading vector for game $id from DynamoDB", e)
        None

  def loadAll(): List[StoredVector] =
    try
      val result = scanAll(exclusiveStartKey = None, acc = Nil)
      logger.info(s"Loaded ${result.size} vectors from DynamoDB")
      result
    catch
      case e: Exception =>
        logger.error("Error loading all vectors from DynamoDB", e)
        Nil

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
    decode[Vector[Double]](item.get("vector").s()) match
      case Left(e) =>
        logger.error(s"Failed to decode vector for item", e)
        None
      case Right(vec) =>
        Some(
          StoredVector(
            gameId = GameId(item.get("game_id").n().toInt),
            name = item.get("name").s(),
            vector = GameVector(vec),
            updatedAt = Instant.parse(item.get("updated_at").s())
          )
        )
