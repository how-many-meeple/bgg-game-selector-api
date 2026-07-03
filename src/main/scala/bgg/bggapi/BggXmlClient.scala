package bgg.bggapi

import bgg.config.BggConfig
import bgg.domain.{CollectionItem, Fail, GameData, GameId, PlayData}
import com.typesafe.scalalogging.StrictLogging
import sttp.client4.*
import sttp.model.StatusCode

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.xml.{Elem, XML}

class BggXmlClient(config: BggConfig, backend: SyncBackend) extends BggClient with StrictLogging:

  private val ApiV2Base = "https://boardgamegeek.com/xmlapi2"
  private val ApiV1Base = "https://boardgamegeek.com/xmlapi"
  private val ThingBatchSize = 20
  private val MinSearchLength = 3
  private val SearchResultLimit = 20
  private val readTimeout = config.timeoutSeconds.seconds

  private def authHeaders: Map[String, String] =
    if config.accessToken.nonEmpty then Map("Authorization" -> s"Bearer ${config.accessToken}")
    else Map.empty

  private def getWithRetry(
      url: String,
      params: Map[String, String] = Map.empty,
      maxRetries: Int = config.retries
  ): Either[Fail, Elem] =
    @tailrec
    def attempt(remaining: Int): Either[Fail, Elem] =
      if remaining <= 0 then Left(Fail.BggRateLimited("BGG rate limit exceeded after retries"))
      else
        val response = basicRequest
          .get(uri"$url".withParams(params))
          .headers(authHeaders)
          .readTimeout(readTimeout)
          .response(asStringAlways)
          .send(backend)

        response.code match
          case StatusCode.Ok =>
            Right(XML.loadString(response.body))
          case StatusCode.Accepted =>
            logger.debug(s"BGG returned 202, retrying in ${config.retryDelaySeconds}s ($remaining retries left)")
            Thread.sleep(config.retryDelaySeconds * 1000L)
            attempt(remaining - 1)
          case StatusCode.TooManyRequests =>
            logger.debug(s"BGG returned 429, retrying in ${config.retryDelaySeconds * 2}s ($remaining retries left)")
            Thread.sleep(config.retryDelaySeconds * 2000L)
            attempt(remaining - 1)
          case code =>
            Left(Fail.IncorrectInput(s"BGG returned unexpected status $code"))

    attempt(maxRetries)

  def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] =
    ids
      .grouped(ThingBatchSize)
      .toList
      .foldLeft[Either[Fail, List[GameData]]](Right(Nil)) { (acc, batch) =>
        acc.flatMap { soFar =>
          val idStr = batch.map(_.value).mkString(",")
          getWithRetry(s"$ApiV2Base/thing", Map("id" -> idStr, "stats" -> "1")) match
            case Left(e @ Fail.BggRateLimited(_)) => Left(e)
            case Left(e) =>
              logger.error(s"Failed to fetch batch $idStr: $e")
              Right(soFar)
            case Right(xml) => Right(soFar ++ XmlParser.parseThings(xml))
        }
      }

  def fetchCollection(username: String, retries: Int = config.retries): Either[Fail, List[CollectionItem]] =
    getWithRetry(
      s"$ApiV2Base/collection",
      Map("username" -> username, "own" -> "1", "excludesubtype" -> "boardgameexpansion"),
      maxRetries = retries
    )
      .flatMap { xml =>
        val items = (xml \ "item")
        if items.isEmpty then Left(Fail.BggUserNotFound(username))
        else
          Right(items.toList.flatMap { n =>
            (n \ "@objectid").headOption.map { id =>
              CollectionItem(GameId(id.text.toInt), collectionLastModified(n))
            }
          })
      }

  // BGG exposes no "dateadded"; status/@lastmodified is the closest per-item date, and for an
  // owned item reflects when it entered the collection. take(10) keeps the date, dropping BGG's time.
  private def collectionLastModified(item: scala.xml.Node): Option[String] =
    (item \ "status" \ "@lastmodified").headOption
      .map(_.text.trim)
      .filter(_.nonEmpty)
      .map(_.take(10))

  def fetchGeeklist(listId: String): Either[Fail, List[GameId]] =
    getWithRetry(s"$ApiV1Base/geeklist/$listId")
      .flatMap { xml =>
        val items = (xml \ "item").filter(n => (n \ "@objecttype").text == "thing")
        if items.isEmpty then Left(Fail.BggListNotFound(listId))
        else Right(items.toList.map(n => GameId((n \ "@objectid").text.toInt)))
      }

  def fetchHotGames(): Either[Fail, List[GameId]] =
    getWithRetry(s"$ApiV2Base/hot", Map("type" -> "boardgame"))
      .map { xml =>
        (xml \ "item").toList.flatMap(n => (n \ "@id").headOption.map(id => GameId(id.text.toInt)))
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

  def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] =
    getWithRetry(s"$ApiV2Base/plays", Map("username" -> username, "page" -> page.toString))
      .map(XmlParser.parsePlays)
