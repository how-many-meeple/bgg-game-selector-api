package bgg.lambda

import bgg.domain.SourceType
import bgg.prefetch.{PrefetchStatus, PrefetchStatusStore}
import com.typesafe.scalalogging.StrictLogging
import io.circe.parser

class StatusUpdaterLogic(prefetchStore: PrefetchStatusStore) extends StrictLogging:

  def handle(eventJson: String): String =
    val (sourceType, sourceId, status, reason) = parseInput(eventJson)
    prefetchStore.set(sourceType, sourceId, status, reason)
    logger.info(s"Updated status: ${sourceType.toPathSegment}:$sourceId -> ${status.dbKey}")
    """{"ok":true}"""

  private def parseInput(json: String): (SourceType, String, PrefetchStatus, String) =
    parser.parse(json).toOption
      .flatMap { j =>
        for
          st <- j.hcursor.downField("sourceType").as[String].toOption
          si <- j.hcursor.downField("sourceId").as[String].toOption
          status <- j.hcursor.downField("status").as[String].toOption
        yield
          val reason = j.hcursor.downField("reason").as[String].getOrElse("")
          val sourceType = SourceType.fromString(st).getOrElse(
            throw RuntimeException(s"Invalid sourceType: $st")
          )
          val prefetchStatus = PrefetchStatus.fromDbKey(status)
          (sourceType, si, prefetchStatus, reason)
      }
      .getOrElse(throw RuntimeException("Invalid input JSON for StatusUpdater"))
