package bgg.prefetch

import bgg.domain.SourceType
import com.typesafe.scalalogging.StrictLogging
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.Instant
import scala.jdk.CollectionConverters.*

class DynamoDbPrefetchStatusStore(client: DynamoDbClient, tableName: String)
    extends PrefetchStatusStore
    with StrictLogging:

  def get(sourceType: SourceType, sourceId: String): Option[PrefetchRecord] =
    try
      val response = client.getItem(
        GetItemRequest.builder()
          .tableName(tableName)
          .key(Map("id" -> AttributeValue.fromS(statusKey(sourceType, sourceId))).asJava)
          .build()
      )
      if response.hasItem then
        val item      = response.item()
        val expiresAt = Instant.ofEpochSecond(item.get("ttl").n().toLong)
        val record    = PrefetchRecord(
          sourceType = SourceType.fromString(item.get("source_type").s()).getOrElse(sourceType),
          sourceId   = item.get("source_id").s(),
          status     = parseStatus(item.get("status").s()),
          reason     = Option(item.get("reason")).map(_.s()).getOrElse(""),
          expiresAt  = expiresAt,
        )
        Option.when(!record.isExpired)(record)
      else None
    catch
      case e: Exception =>
        logger.error("Failed to read prefetch status from DynamoDB", e)
        None

  def set(sourceType: SourceType, sourceId: String, status: PrefetchStatus, reason: String = ""): Unit =
    val item = Map(
      "id"          -> AttributeValue.fromS(statusKey(sourceType, sourceId)),
      "source_type" -> AttributeValue.fromS(sourceType.toPathSegment),
      "source_id"   -> AttributeValue.fromS(sourceId),
      "status"      -> AttributeValue.fromS(status.dbKey),
      "reason"      -> AttributeValue.fromS(reason),
      "ttl"         -> AttributeValue.fromN(PrefetchTtl.expiresAt(status).getEpochSecond.toString),
    ).asJava

    try client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build()): Unit
    catch case e: Exception => logger.error("Failed to write prefetch status to DynamoDB", e)

  private def parseStatus(s: String): PrefetchStatus = s match
    case "pending"    => PrefetchStatus.Pending
    case "processing" => PrefetchStatus.Processing
    case "completed"  => PrefetchStatus.Completed
    case "not_found"  => PrefetchStatus.NotFound
    case _            => PrefetchStatus.Failed
