package bgg.routes

import bgg.domain.SourceType
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

trait SqsSender:
  def send(sourceType: SourceType, sourceId: String): Unit

class AwsSqsSender(sqsClient: SqsClient, queueUrl: String) extends SqsSender with StrictLogging:
  def send(sourceType: SourceType, sourceId: String): Unit =
    val body = Json.obj(
      "source_type" -> Json.fromString(sourceType.toPathSegment),
      "source_id"   -> Json.fromString(sourceId),
    ).noSpaces
    val request = SendMessageRequest.builder()
      .queueUrl(queueUrl)
      .messageBody(body)
      .build()
    sqsClient.sendMessage(request)
    logger.debug(s"Queued prefetch for ${sourceType.toPathSegment}/$sourceId")

// No-op for local dev / SQLite mode where SQS is not configured
class NoOpSqsSender extends SqsSender:
  def send(sourceType: SourceType, sourceId: String): Unit = ()
