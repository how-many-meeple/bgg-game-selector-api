package bgg.routes

import bgg.bggapi.GameService
import bgg.cache.GameCache
import bgg.config.AppConfig
import bgg.domain.*
import bgg.filter.GameFilter
import bgg.prefetch.{PrefetchStatus, PrefetchStatusStore}
import bgg.recommendation.RecommendationEngine
import bgg.store.VectorStore
import bgg.vector.{VectorDimensions, VectorMath, MechanicVocabulary, CategoryVocabulary}
import com.typesafe.scalalogging.StrictLogging
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Codec, Json}
import sttp.model.{Header, StatusCode}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

import java.util.Base64

// Request / response models
case class PrefetchRequest(sourceType: String, sourceId: String)
object PrefetchRequest:
  given Codec[PrefetchRequest] = deriveCodec

case class PrefetchResponse(status: String, message: String)
object PrefetchResponse:
  given Codec[PrefetchResponse] = deriveCodec

case class RecommendRequest(gameIds: List[Int], limit: Option[Int], excludeIds: Option[List[Int]])
object RecommendRequest:
  given Codec[RecommendRequest] = deriveCodec

case class RecommendResponse(
    recommendations: List[RecommendedGameJson],
    inputGamesCount: Int,
    tasteVectorDimensions: Int
)
object RecommendResponse:
  given Codec[RecommendResponse] = deriveCodec

case class RecommendedGameJson(gameId: Int, name: String, similarityScore: Double)
object RecommendedGameJson:
  given Codec[RecommendedGameJson] = deriveCodec

case class StatusResponse(status: String, sourceType: String, sourceId: String)
object StatusResponse:
  given Codec[StatusResponse] = deriveCodec

class ApiEndpoints(
    gameService: GameService,
    gameCache: GameCache,
    vectorStore: VectorStore,
    prefetchStore: PrefetchStatusStore,
    sqsSender: SqsSender,
    config: AppConfig,
    httpBackend: sttp.client4.SyncBackend = sttp.client4.DefaultSyncBackend()
) extends StrictLogging:

  import ErrorOutput.*
  import ApiEndpoints.*

  private val headers: EndpointInput[List[Header]] = extractFromRequest(_.headers.toList)

  // GET /health
  val healthEndpoint = baseEndpoint.get
    .in("health")
    .out(jsonBody[Json])
    .handle { _ =>
      Right(
        Json.obj(
          "status" -> Json.fromString("ok"),
          "cache_backend" -> Json.fromString(config.cache.backend.toString.toLowerCase)
        )
      )
    }

  // GET /collection/:username
  val collectionEndpoint = baseEndpoint.get
    .in("collection" / path[String]("username"))
    .in(headers)
    .out(jsonBody[List[Json]])
    .handle { (username, hdrs) =>
      checkPrefetchBlock(SourceType.Collection, username)
        .getOrElse {
          val filters = HeaderFilters.fromHeaders(hdrs)
          gameService.resolveCollection(username).map { games =>
            FieldReduction(GameFilter(games, filters), filters.fieldWhitelist)
          }
        }
    }

  // GET /geeklist/:id
  val geeklistEndpoint = baseEndpoint.get
    .in("geeklist" / path[String]("geekListId"))
    .in(headers)
    .out(jsonBody[List[Json]])
    .handle { (listId, hdrs) =>
      checkPrefetchBlock(SourceType.GeeKList, listId)
        .getOrElse {
          val filters = HeaderFilters.fromHeaders(hdrs)
          gameService.resolveGeeklist(listId).map { games =>
            FieldReduction(GameFilter(games, filters), filters.fieldWhitelist)
          }
        }
    }

  // GET /search/:query
  val searchEndpoint = baseEndpoint.get
    .in("search" / path[String]("query"))
    .out(jsonBody[List[Json]])
    .handle { query =>
      gameService.search(query).map(games => FieldReduction(games, None))
    }

  // POST /prefetch
  val prefetchEndpoint = baseEndpoint.post
    .in("prefetch")
    .in(jsonBody[PrefetchRequest])
    .out(statusCode and jsonBody[PrefetchResponse])
    .handle { req =>
      SourceType.fromString(req.sourceType) match
        case Left(e) => Left(Fail.IncorrectInput(e))
        case Right(sourceType) =>
          val sourceId = req.sourceId.trim
          if sourceId.isEmpty then Left(Fail.IncorrectInput("source_id must not be empty"))
          else if !prefetchStore.isQueueable(sourceType, sourceId) then
            val status = prefetchStore.get(sourceType, sourceId).map(_.status.dbKey).getOrElse("pending")
            Right((StatusCode.Ok, PrefetchResponse(status, "Already queued or recently completed")))
          else
            sqsSender.send(sourceType, sourceId)
            prefetchStore.set(sourceType, sourceId, PrefetchStatus.Pending)
            Right((StatusCode.Accepted, PrefetchResponse("pending", "Queued for prefetch")))
    }

  // GET /prefetch/status/:sourceType/:sourceId
  val prefetchStatusEndpoint = baseEndpoint.get
    .in("prefetch" / "status" / path[String]("sourceType") / path[String]("sourceId"))
    .out(jsonBody[Json])
    .handle { (sourceTypeStr, sourceId) =>
      SourceType.fromString(sourceTypeStr) match
        case Left(e) => Left(Fail.IncorrectInput(e))
        case Right(sourceType) =>
          prefetchStore.get(sourceType, sourceId) match
            case None =>
              Left(Fail.NotFound(s"No prefetch run found for $sourceTypeStr/$sourceId"))
            case Some(record) =>
              Right(
                Json.obj(
                  "source_type" -> Json.fromString(sourceTypeStr),
                  "source_id" -> Json.fromString(sourceId),
                  "status" -> Json.fromString(record.status.dbKey),
                  "reason" -> Json.fromString(record.reason)
                )
              )
    }

  // POST /recommendations/from-games
  val recommendationsEndpoint = baseEndpoint.post
    .in("recommendations" / "from-games")
    .in(jsonBody[RecommendRequest])
    .in(headers)
    .out(jsonBody[RecommendResponse])
    .handle { (req, hdrs) =>
      val gameIds = req.gameIds.map(GameId(_))
      val limit = req.limit.getOrElse(DefaultRecommendationLimit)
      val excludeIds = req.excludeIds.getOrElse(Nil).map(GameId(_)).toSet ++ gameIds.toSet
      val filters = HeaderFilters.fromHeaders(hdrs)

      val games = gameIds.flatMap(id => gameCache.load(id).toList)
      if games.isEmpty then Left(Fail.NotFound("None of the provided game IDs were found in cache"))
      else
        val tasteVector = VectorMath.buildTasteVector(games)
        val recommendations = RecommendationEngine.recommend(
          tasteVector = tasteVector,
          vectorStore = vectorStore,
          gameCache = gameCache,
          limit = limit,
          excludeIds = excludeIds,
          filters = filters
        )
        Right(
          RecommendResponse(
            recommendations = recommendations.map(r =>
              RecommendedGameJson(
                r.gameId.value,
                r.name,
                math.round(r.similarityScore * ScorePrecision) / ScorePrecision
              )
            ),
            inputGamesCount = games.size,
            tasteVectorDimensions = VectorDimensions
          )
        )
    }

  // GET /recommendations/schema
  val schemaEndpoint = baseEndpoint.get
    .in("recommendations" / "schema")
    .out(jsonBody[Json])
    .handle { _ =>
      Right(
        Json.obj(
          "total_dimensions" -> Json.fromInt(VectorDimensions),
          "mechanics" -> Json.obj(
            "start_index" -> Json.fromInt(0),
            "count" -> Json.fromInt(MechanicVocabulary.size),
            "vocabulary" -> MechanicVocabulary.asJson
          ),
          "categories" -> Json.obj(
            "start_index" -> Json.fromInt(MechanicVocabulary.size),
            "count" -> Json.fromInt(CategoryVocabulary.size),
            "vocabulary" -> CategoryVocabulary.asJson
          )
        )
      )
    }

  // GET /cors-proxy/:b64url
  val corsProxyEndpoint = baseEndpoint.get
    .in("cors-proxy" / path[String]("b64url"))
    .out(byteArrayBody)
    .out(header[String]("Content-Type"))
    .out(header[String]("Cache-Control"))
    .handle { b64url =>
      decodeProxyUrl(b64url) match
        case Left(e) => Left(Fail.IncorrectInput(e))
        case Right(url) =>
          try
            val response = sttp.client4.quick.quickRequest
              .get(sttp.model.Uri.unsafeParse(url))
              .response(sttp.client4.asByteArrayAlways)
              .send(httpBackend)
            Right(
              (
                response.body,
                response.headers.find(_.name == "content-type").map(_.value).getOrElse("application/octet-stream"),
                ImmutableCacheControl
              )
            )
          catch case e: Exception => Left(Fail.IncorrectInput(s"Proxy request failed: ${e.getMessage}"))
    }

  // Checks if a prefetch result blocks the main collection/geeklist request.
  // Returns Some(result) if the request should be blocked, None to continue normally.
  private def checkPrefetchBlock(sourceType: SourceType, sourceId: String): Option[Either[Fail, List[Json]]] =
    prefetchStore.get(sourceType, sourceId) match
      case None => None
      case Some(record) =>
        record.status match
          case PrefetchStatus.Pending | PrefetchStatus.Processing =>
            Some(Left(Fail.PrefetchInProgress(record.status.dbKey)))
          case PrefetchStatus.NotFound =>
            Some(Left(Fail.BggUserNotFound(sourceId)))
          case PrefetchStatus.Failed =>
            Some(Left(Fail.BggRateLimited(record.reason.ifEmpty(s"Previous attempt to load '$sourceId' failed"))))
          case PrefetchStatus.Completed => None

  val all: List[sttp.tapir.server.ServerEndpoint[Any, sttp.shared.Identity]] = List(
    healthEndpoint,
    collectionEndpoint,
    geeklistEndpoint,
    searchEndpoint,
    prefetchEndpoint,
    prefetchStatusEndpoint,
    recommendationsEndpoint,
    schemaEndpoint,
    corsProxyEndpoint
  )

object ApiEndpoints:
  val DefaultRecommendationLimit = 10
  val ScorePrecision = 10000.0
  val ImmutableCacheControl = "public, max-age=31536000, immutable"

private def decodeProxyUrl(raw: String): Either[String, String] =
  // Strip leading underscore added by HMM frontend, restore base64url padding
  val stripped = raw.stripPrefix("_")
  val padded = stripped + "=" * ((-stripped.length) % 4)
  try
    val decoded = new String(Base64.getUrlDecoder.decode(padded))
    if decoded.startsWith("http://") || decoded.startsWith("https://") then Right(decoded)
    else Left("Not a valid URL to proxy")
  catch case _: Exception => Left("Unable to decode requested proxy item")

extension (s: String) private def ifEmpty(fallback: String): String = if s.isEmpty then fallback else s
