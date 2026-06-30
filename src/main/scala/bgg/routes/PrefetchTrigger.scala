package bgg.routes

import bgg.domain.SourceType
import com.typesafe.scalalogging.StrictLogging
import io.circe.Json
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest

import java.time.Instant

trait PrefetchTrigger:
  def trigger(sourceType: SourceType, sourceId: String): Unit

class StepFunctionsTrigger(sfnClient: SfnClient, stateMachineArn: String) extends PrefetchTrigger with StrictLogging:
  def trigger(sourceType: SourceType, sourceId: String): Unit =
    val input = Json.obj(
      "sourceType" -> Json.fromString(sourceType.toPathSegment),
      "sourceId" -> Json.fromString(sourceId)
    ).noSpaces
    val name = s"${sourceType.toPathSegment}-${sourceId}-${Instant.now().toEpochMilli}"
    sfnClient.startExecution(
      StartExecutionRequest.builder()
        .stateMachineArn(stateMachineArn)
        .name(name)
        .input(input)
        .build()
    )
    logger.debug(s"Started Step Functions execution for ${sourceType.toPathSegment}/$sourceId")

class NoOpPrefetchTrigger extends PrefetchTrigger:
  def trigger(sourceType: SourceType, sourceId: String): Unit = ()
