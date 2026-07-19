package bgg.store

import bgg.SafeOps.{decodeJson, tryAwsCall}
import bgg.domain.GameId
import bgg.vector.GameVector
import com.typesafe.scalalogging.{Logger, StrictLogging}
import io.circe.syntax.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

class DynamoDbVectorStore(
    client: DynamoDbClient,
    tableName: String,
    clock: () => Instant = () => Instant.now(),
    cacheTtl: Duration = Duration.ofMinutes(5)
) extends VectorStore
    with StrictLogging:

  private given Logger = logger

  // Warm-container snapshot of the full corpus, refreshed at most once per cacheTtl. Vectors change
  // rarely (only on game prefetch/fetch), so serving a slightly stale snapshot to recommendation
  // scoring avoids a full-table Scan on every request. @volatile: Lambda may serve concurrent requests.
  private case class Snapshot(vectors: List[StoredVector], takenAt: Instant):
    def isFreshAt(now: Instant): Boolean = Duration.between(takenAt, now).compareTo(cacheTtl) < 0

  @volatile private var snapshot: Option[Snapshot] = None

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
    ): Unit

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

  override def loadAllCached(): List[StoredVector] =
    val now = clock()
    snapshot match
      case Some(current) if current.isFreshAt(now) =>
        logger.debug(s"Serving ${current.vectors.size} vectors from warm snapshot")
        current.vectors
      case _ =>
        val fresh = loadAll()
        // Don't cache an empty result: loadAll returns Nil both for a genuinely empty corpus and for
        // a swallowed Scan failure, and caching the latter would blank recommendations for the whole
        // TTL. Retrying an empty scan next request is cheap.
        if fresh.nonEmpty then snapshot = Some(Snapshot(fresh, now))
        fresh

  @scala.annotation.tailrec
  private def scanAll(
      exclusiveStartKey: Option[java.util.Map[String, AttributeValue]],
      acc: List[StoredVector]
  ): List[StoredVector] =
    // Project only the attributes recommendation scoring needs; `updated_at` is dropped to cut bytes
    // read (Scan bills by bytes, not rows). `name` is a DynamoDB reserved word, so alias it via #n.
    val reqBuilder = ScanRequest
      .builder()
      .tableName(tableName)
      .projectionExpression("game_id, #n, vector")
      .expressionAttributeNames(Map("#n" -> "name").asJava)
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
        // updated_at is absent from projected reads (scanAll); fall back to epoch when not present.
        updatedAt = Option(item.get("updated_at")).map(a => Instant.parse(a.s())).getOrElse(Instant.EPOCH)
      )
    }
