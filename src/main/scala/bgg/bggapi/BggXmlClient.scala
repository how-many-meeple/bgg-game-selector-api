package bgg.bggapi

import bgg.config.BggConfig
import bgg.domain.*
import com.typesafe.scalalogging.StrictLogging
import sttp.client4.*
import sttp.model.StatusCode

import scala.annotation.tailrec
import scala.xml.{Elem, XML}

class BggXmlClient(config: BggConfig, backend: SyncBackend) extends BggClient with StrictLogging:

  private val ApiV2Base         = "https://boardgamegeek.com/xmlapi2"
  private val ApiV1Base         = "https://boardgamegeek.com/xmlapi"
  private val ThingBatchSize    = 20
  private val MinSearchLength   = 3
  private val SearchResultLimit = 20

  private def authHeaders: Map[String, String] =
    if config.accessToken.nonEmpty then Map("Authorization" -> s"Bearer ${config.accessToken}")
    else Map.empty

  // BGG uses HTTP 202 to signal "try again later" for collection/geeklist requests
  private def getWithRetry(url: String, params: Map[String, String] = Map.empty): Either[Fail, Elem] =
    @tailrec
    def attempt(remaining: Int): Either[Fail, Elem] =
      if remaining <= 0 then Left(Fail.BggRateLimited("BGG rate limit exceeded after retries"))
      else
        val response = basicRequest
          .get(uri"$url".withParams(params))
          .headers(authHeaders)
          .response(asStringAlways)
          .send(backend)

        response.code match
          case StatusCode.Ok =>
            Right(XML.loadString(response.body))
          case StatusCode.Accepted =>
            logger.debug(s"BGG returned 202, retrying in ${config.retryDelaySeconds}s")
            Thread.sleep(config.retryDelaySeconds * 1000L)
            attempt(remaining - 1)
          case StatusCode.TooManyRequests =>
            Left(Fail.BggRateLimited("BGG is rate limiting requests"))
          case code =>
            Left(Fail.IncorrectInput(s"BGG returned unexpected status $code"))

    attempt(config.retries)

  def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] =
    val results = ids
      .grouped(ThingBatchSize)
      .toList
      .flatMap { batch =>
        val idStr = batch.map(_.value).mkString(",")
        getWithRetry(s"$ApiV2Base/thing", Map("id" -> idStr, "stats" -> "1")) match
          case Left(e)    =>
            logger.error(s"Failed to fetch batch $idStr: $e")
            Nil
          case Right(xml) => XmlParser.parseThings(xml)
      }
    Right(results)

  def fetchCollection(username: String): Either[Fail, List[GameId]] =
    getWithRetry(s"$ApiV2Base/collection", Map("username" -> username, "own" -> "1", "excludesubtype" -> "boardgameexpansion"))
      .flatMap { xml =>
        val items = (xml \ "item")
        if items.isEmpty then Left(Fail.BggUserNotFound(username))
        else Right(items.toList.flatMap(n => (n \ "@objectid").headOption.map(id => GameId(id.text.toInt))))
      }

  def fetchGeeklist(listId: String): Either[Fail, List[GameId]] =
    getWithRetry(s"$ApiV1Base/geeklist/$listId")
      .flatMap { xml =>
        val items = (xml \ "item").filter(n => (n \ "@objecttype").text == "thing")
        if items.isEmpty then Left(Fail.BggListNotFound(listId))
        else Right(items.toList.map(n => GameId((n \ "@objectid").text.toInt)))
      }

  def searchGames(query: String): Either[Fail, List[GameData]] =
    if query.length < MinSearchLength then Right(Nil)
    else
      getWithRetry(s"$ApiV2Base/search", Map("query" -> query, "type" -> "boardgame"))
        .map { xml =>
          val ids = (xml \ "item").toList.map(n => GameId((n \ "@id").text.toInt))
          ids.take(SearchResultLimit).flatMap { id =>
            fetchGamesByIds(List(id)) match
              case Right(games) => games
              case Left(_)      => Nil
          }
        }
