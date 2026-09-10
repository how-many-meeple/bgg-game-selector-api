package bgg.store

import bgg.SafeOps.{decodeJson, tryAwsCall}
import bgg.domain.GameId
import bgg.vector.{GameVector, VectorCodec}
import com.typesafe.scalalogging.{Logger, StrictLogging}
import ox.*
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*
import scala.util.Try

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

  // Above this many rows, decode scan results in parallel; below it the fork overhead is not worth it.
  private val ParallelDecodeThreshold = 500

  def save(sv: StoredVector): Unit =
    val item = Map(
      "game_id" -> AttributeValue.fromN(sv.gameId.asString),
      "name" -> AttributeValue.fromS(sv.name),
      "vector" -> AttributeValue.fromB(SdkBytes.fromByteArray(VectorCodec.encode(sv.vector.values))),
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

  def loadAll(): List[StoredVector] = loadAllRows(applyProjection = true)

  /** One-off migration: reload every vector and re-save it so legacy JSON rows become Binary.
    * Idempotent — re-saving an already-binary row is a no-op change. Returns rows rewritten.
    */
  def rewriteAll(): Int =
    val all = loadAllRows(applyProjection = false)
    all.foreach(save)
    logger.info(s"Rewrote ${all.size} vectors to binary encoding")
    all.size

  /** Full-table scan with optional projection. When applyProjection is true, projects only the
    * fields needed for scoring (game_id, name, vector) to reduce Scan bytes billed. When false,
    * all attributes (including updated_at) are returned; rewriteAll uses this to preserve
    * updated_at when backfilling rows.
    */
  private def loadAllRows(applyProjection: Boolean): List[StoredVector] =
    val kind = if applyProjection then "projected" else "unprojected"
    tryAwsCall(
      {
        val rawItems = scanRawItems(applyProjection, exclusiveStartKey = None, acc = Nil)
        val result = decodeItems(rawItems)
        logger.info(s"Loaded ${result.size} $kind vectors from DynamoDB")
        result
      },
      s"Error loading all $kind vectors from DynamoDB"
    ).getOrElse(Nil)

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
  private def scanRawItems(
      applyProjection: Boolean,
      exclusiveStartKey: Option[java.util.Map[String, AttributeValue]],
      acc: List[java.util.Map[String, AttributeValue]]
  ): List[java.util.Map[String, AttributeValue]] =
    val reqBuilder = ScanRequest.builder().tableName(tableName)
    // Project only what scoring needs; Scan bills by bytes read. `name` is reserved, alias via #n.
    if applyProjection then
      reqBuilder
        .projectionExpression("game_id, #n, vector")
        .expressionAttributeNames(Map("#n" -> "name").asJava)
    exclusiveStartKey.foreach(k => reqBuilder.exclusiveStartKey(k))
    val response = client.scan(reqBuilder.build())
    val page = response.items().asScala.toList
    val updated = page.reverse ::: acc
    if response.hasLastEvaluatedKey then
      scanRawItems(applyProjection, Some(response.lastEvaluatedKey()), updated)
    else updated.reverse

  private def decodeItems(items: List[java.util.Map[String, AttributeValue]]): List[StoredVector] =
    if items.size < ParallelDecodeThreshold then items.flatMap(parseItem)
    else
      val parallelism = math.max(1, Runtime.getRuntime.availableProcessors())
      parLimit(parallelism)(items.map(item => () => parseItem(item))).flatten.toList

  private def parseItem(item: java.util.Map[String, AttributeValue]): Option[StoredVector] =
    Try {
      decodeVector(item.get("vector")).map { vec =>
        StoredVector(
          gameId = GameId(item.get("game_id").n().toInt),
          name = item.get("name").s(),
          vector = GameVector(vec),
          // updated_at is absent from projected reads (scanRawItems); fall back to epoch when not present.
          updatedAt = Option(item.get("updated_at")).map(a => Instant.parse(a.s())).getOrElse(Instant.EPOCH)
        )
      }
    }.recover { case ex =>
      val gameIdStr = Try(item.get("game_id").n()).getOrElse("unknown")
      logger.warn(s"Failed to parse DynamoDB row for game_id=$gameIdStr: ${ex.getMessage}")
      None
    }.getOrElse(None)

  private def decodeVector(attr: AttributeValue): Option[Vector[Double]] =
    Option(attr) match
      case Some(a) if a.b() != null =>
        VectorCodec.decode(a.b().asByteArray()) match
          case Right(v)  => Some(v)
          case Left(err) => logger.warn(s"Failed to decode binary vector: $err"); None
      case Some(a) if a.s() != null =>
        decodeJson[Vector[Double]](a.s(), "legacy JSON vector from DynamoDB")
      case _ => None
