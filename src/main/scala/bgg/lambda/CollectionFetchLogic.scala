package bgg.lambda

import bgg.bggapi.BggClient
import bgg.domain.{Fail, GameId, SourceType}
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Json, parser}
import io.circe.syntax.*

case class CollectionFetchInput(sourceType: String, sourceId: String)
case class CollectionFetchOutput(gameIds: List[Int], sourceType: String, sourceId: String)

class CollectionFetchLogic(bggClient: BggClient, retries: Int) extends StrictLogging:

  def handle(eventJson: String): String =
    val result = for
      input <- parseInput(eventJson)
      ids <- fetchIds(input)
    yield CollectionFetchOutput(ids.map(_.value), input.sourceType, input.sourceId)

    result match
      case Right(output) =>
        Json.obj(
          "gameIds" -> output.gameIds.asJson,
          "sourceType" -> Json.fromString(output.sourceType),
          "sourceId" -> Json.fromString(output.sourceId)
        ).noSpaces
      case Left(fail) =>
        throw toLambdaException(fail)

  private def parseInput(json: String): Either[Fail, CollectionFetchInput] =
    parser.parse(json).toOption
      .flatMap { j =>
        for
          st <- j.hcursor.downField("sourceType").as[String].toOption
          si <- j.hcursor.downField("sourceId").as[String].toOption
        yield CollectionFetchInput(st, si)
      }
      .toRight(Fail.IncorrectInput("Invalid input JSON"))

  private def fetchIds(input: CollectionFetchInput): Either[Fail, List[GameId]] =
    SourceType.fromString(input.sourceType) match
      case Left(err) => Left(Fail.IncorrectInput(err))
      case Right(SourceType.Collection) =>
        logger.info(s"Fetching collection for ${input.sourceId} with $retries retries")
        bggClient.fetchCollection(input.sourceId, retries)
      case Right(SourceType.GeeKList) =>
        logger.info(s"Fetching geeklist ${input.sourceId}")
        bggClient.fetchGeeklist(input.sourceId)
      case Right(SourceType.Hot) =>
        logger.info(s"Fetching hot games")
        bggClient.fetchHotGames()
      case Right(SourceType.Plays) =>
        Left(Fail.IncorrectInput("Plays is not a valid source type for collection fetch"))

  private def toLambdaException(fail: Fail): RuntimeException = fail match
    case Fail.BggRateLimited(msg) => BggRateLimitedException(msg)
    case Fail.BggUserNotFound(u)  => BggUserNotFoundException(s"User not found: $u")
    case Fail.BggListNotFound(id) => BggListNotFoundException(s"List not found: $id")
    case other                    => RuntimeException(other.toString)

case class BggRateLimitedException(msg: String) extends RuntimeException(msg)
case class BggUserNotFoundException(msg: String) extends RuntimeException(msg)
case class BggListNotFoundException(msg: String) extends RuntimeException(msg)
