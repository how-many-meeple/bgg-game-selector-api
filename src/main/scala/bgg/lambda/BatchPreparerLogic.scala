package bgg.lambda

import bgg.cache.GameCache
import bgg.domain.GameId
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Json, parser}
import io.circe.syntax.*

class BatchPreparerLogic(gameCache: GameCache) extends StrictLogging:

  private val ChunkSize = 300

  def handle(eventJson: String): String =
    val (gameIds, sourceType, sourceId) = parseInput(eventJson)
    val uncached = filterCached(gameIds)
    val chunks = uncached.grouped(ChunkSize).toList

    logger.info(
      s"BatchPreparer: ${gameIds.size} total, ${gameIds.size - uncached.size} cached, " +
        s"${uncached.size} to fetch in ${chunks.size} chunks"
    )

    val result = chunks.map { chunk =>
      Json.obj(
        "gameIds" -> chunk.map(_.value).asJson,
        "sourceType" -> Json.fromString(sourceType),
        "sourceId" -> Json.fromString(sourceId)
      )
    }
    Json.fromValues(result).noSpaces

  private def parseInput(json: String): (List[GameId], String, String) =
    parser
      .parse(json)
      .toOption
      .flatMap { j =>
        for
          ids <- j.hcursor.downField("gameIds").as[List[Int]].toOption
          st <- j.hcursor.downField("sourceType").as[String].toOption
          si <- j.hcursor.downField("sourceId").as[String].toOption
        yield (ids.map(GameId(_)), st, si)
      }
      .getOrElse(throw RuntimeException("Invalid input JSON for BatchPreparer"))

  private def filterCached(ids: List[GameId]): List[GameId] =
    val cached = gameCache.loadBatch(ids)
    val cachedIds = cached.map(_.id).toSet
    ids.filterNot(cachedIds.contains)
