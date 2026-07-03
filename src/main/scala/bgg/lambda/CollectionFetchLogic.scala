package bgg.lambda

import bgg.bggapi.BggClient
import bgg.cache.{CacheKeys, RequestCache}
import bgg.domain.{CollectionItem, Fail, GameId, SourceType}
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Json, parser}
import io.circe.syntax.*

import java.time.Instant

case class CollectionFetchInput(sourceType: String, sourceId: String)
case class CollectionFetchOutput(gameIds: List[Int], sourceType: String, sourceId: String)

class CollectionFetchLogic(
    bggClient: BggClient,
    retries: Int,
    requestCache: Option[RequestCache] = None,
    clock: () => Instant = () => Instant.now()
) extends StrictLogging:

  private val IdsCacheTtlSeconds = 24L * 3600

  def handle(eventJson: String): String =
    val result = for
      input <- parseInput(eventJson)
      items <- fetchItems(input)
    yield
      cacheIds(input, items.map(_.id))
      cacheCollectionDates(input, items)
      CollectionFetchOutput(items.map(_.id.value), input.sourceType, input.sourceId)

    result match
      case Right(output) =>
        Json
          .obj(
            "gameIds" -> output.gameIds.asJson,
            "sourceType" -> Json.fromString(output.sourceType),
            "sourceId" -> Json.fromString(output.sourceId)
          )
          .noSpaces
      case Left(fail) =>
        throw toLambdaException(fail)

  private def cacheIds(input: CollectionFetchInput, ids: List[GameId]): Unit =
    val cacheKey = SourceType.fromString(input.sourceType).toOption match
      case Some(SourceType.Collection) => Some(CacheKeys.collectionIds(input.sourceId))
      case Some(SourceType.GeeKList)   => Some(CacheKeys.geeklistIds(input.sourceId))
      case _                           => None

    for
      cache <- requestCache
      key <- cacheKey
    do cache.save(key, ids.map(_.value), IdsCacheTtlSeconds, clock())

  // Per-user collection dates are cached under a separate key so the collection endpoint
  // can enrich its response; see GameService.resolveCollection. Only collections carry dates.
  private def cacheCollectionDates(input: CollectionFetchInput, items: List[CollectionItem]): Unit =
    if SourceType.fromString(input.sourceType).contains(SourceType.Collection) then
      val dates = CollectionItem.datesByIdString(items)
      requestCache.foreach(_.save(CacheKeys.collectionDates(input.sourceId), dates, IdsCacheTtlSeconds, clock()))

  private def parseInput(json: String): Either[Fail, CollectionFetchInput] =
    parser
      .parse(json)
      .toOption
      .flatMap { j =>
        for
          st <- j.hcursor.downField("sourceType").as[String].toOption
          si <- j.hcursor.downField("sourceId").as[String].toOption
        yield CollectionFetchInput(st, si)
      }
      .toRight(Fail.IncorrectInput("Invalid input JSON"))

  private def fetchItems(input: CollectionFetchInput): Either[Fail, List[CollectionItem]] =
    SourceType.fromString(input.sourceType) match
      case Left(err) => Left(Fail.IncorrectInput(err))
      case Right(SourceType.Collection) =>
        logger.info(s"Fetching collection for ${input.sourceId} with $retries retries")
        bggClient.fetchCollection(input.sourceId, retries)
      case Right(SourceType.GeeKList) =>
        logger.info(s"Fetching geeklist ${input.sourceId}")
        bggClient.fetchGeeklist(input.sourceId).map(datelessItems)
      case Right(SourceType.Hot) =>
        logger.info(s"Fetching hot games")
        bggClient.fetchHotGames().map(datelessItems)
      case Right(SourceType.Plays) =>
        Left(Fail.IncorrectInput("Plays is not a valid source type for collection fetch"))

  // Geeklists and hot games have no collection-level dates; wrap their ids to share one flow.
  private def datelessItems(ids: List[GameId]): List[CollectionItem] =
    ids.map(id => CollectionItem(id, None))

  private def toLambdaException(fail: Fail): RuntimeException = fail match
    case Fail.BggRateLimited(msg) => BggRateLimitedException(msg)
    case Fail.BggUserNotFound(u)  => BggUserNotFoundException(s"User not found: $u")
    case Fail.BggListNotFound(id) => BggListNotFoundException(s"List not found: $id")
    case other                    => RuntimeException(other.toString)

case class BggRateLimitedException(msg: String) extends RuntimeException(msg)
case class BggUserNotFoundException(msg: String) extends RuntimeException(msg)
case class BggListNotFoundException(msg: String) extends RuntimeException(msg)
