package bgg.routes

import bgg.bggapi.GameService
import bgg.cache.GameCache
import bgg.config.AppConfig
import bgg.domain.{Fail, GameData, GameId, PlayData, SourceType}
import bgg.filter.GameFilter
import bgg.prefetch.{PrefetchStatus, PrefetchStatusStore}
import bgg.recommendation.{RecommendationEngine, RecommendedGame}
import bgg.store.VectorStore
import bgg.vector.{VectorDimensions, VectorMath, MechanicVocabulary, CategoryVocabulary}
import com.typesafe.scalalogging.StrictLogging
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import io.circe.Json
import sttp.model.{Header, StatusCode}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

import java.util.Base64

given Configuration = Configuration.default.withSnakeCaseMemberNames

case class PrefetchRequest(sourceType: String, sourceId: String) derives ConfiguredCodec
case class PrefetchResponse(status: String, message: String) derives ConfiguredCodec

case class RecommendRequest(gameIds: List[Int], limit: Option[Int], excludeIds: Option[List[Int]])
    derives ConfiguredCodec

case class RecommendResponse(
    recommendations: List[RecommendedGameJson],
    inputGamesCount: Int,
    tasteVectorDimensions: Int
) derives ConfiguredCodec

case class RecommendedGameJson(gameId: Int, name: String, similarityScore: Double, game: Option[Json])
    derives ConfiguredCodec

case class StatusResponse(status: String, sourceType: String, sourceId: String) derives ConfiguredCodec

class ApiEndpoints(
    gameService: GameService,
    gameCache: GameCache,
    vectorStore: VectorStore,
    prefetchStore: PrefetchStatusStore,
    sqsSender: SqsSender,
    config: AppConfig
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
  val collectionEndpoint = gameListEndpoint("collection", SourceType.Collection, gameService.resolveCollection)

  // GET /geeklist/:id
  val geeklistEndpoint = gameListEndpoint("geeklist", SourceType.GeeKList, gameService.resolveGeeklist)

  private def gameListEndpoint(
      segment: String,
      sourceType: SourceType,
      resolve: String => Either[Fail, List[GameData]]
  ) =
    baseEndpoint.get
      .in(segment / path[String]("id"))
      .in(headers)
      .out(jsonBody[List[Json]])
      .handle { (id, hdrs) =>
        checkPrefetchBlock(sourceType, id)
          .getOrElse {
            val filters = HeaderFilters.fromHeaders(hdrs)
            resolve(id).map { games =>
              FieldReduction(GameFilter(games, filters), filters.fieldWhitelist)
            }
          }
      }

  // GET /hot
  val hotEndpoint = baseEndpoint.get
    .in("hot")
    .in(headers)
    .out(jsonBody[List[Json]])
    .handle { hdrs =>
      checkPrefetchBlock(SourceType.Hot, "trending")
        .getOrElse {
          val filters = HeaderFilters.fromHeaders(hdrs)
          gameService.resolveHotGames().map { games =>
            FieldReduction(GameFilter(games, filters), filters.fieldWhitelist)
          }
        }
    }

  // GET /game/:id
  val gameEndpoint = baseEndpoint.get
    .in("game" / path[Int]("id"))
    .in(headers)
    .out(jsonBody[Json])
    .handle { (id, hdrs) =>
      val filters = HeaderFilters.fromHeaders(hdrs)
      gameService.resolveGameIds(List(GameId(id))).flatMap {
        case game :: _ => Right(FieldReduction(List(game), filters.fieldWhitelist).head)
        case Nil       => Left(Fail.NotFound(s"Game $id not found"))
      }
    }

  // GET /plays/:username
  val playsEndpoint = baseEndpoint.get
    .in("plays" / path[String]("username"))
    .in(headers)
    .out(jsonBody[List[Json]])
    .handle { (username, hdrs) =>
      val filters = HeaderFilters.fromHeaders(hdrs)
      gameService.resolvePlays(username).map { plays =>
        val distinctIds = plays.map(_.gameId).distinct
        val gameMap = gameCache.loadBatch(distinctIds).map(g => (g.id, g)).toMap

        plays.map { p =>
          val playJson = PlayData.encoder(p)
          val gameJson = gameMap.get(p.gameId).map { game =>
            FieldReduction(List(game), filters.fieldWhitelist).head
          }
          playJson.deepMerge(Json.obj("game" -> gameJson.getOrElse(Json.Null)))
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

      val games = resolveInputGames(gameIds)
      if games.isEmpty then Left(Fail.NotFound("None of the provided game IDs could be resolved"))
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
        val enriched = enrichRecommendations(recommendations)
        Right(
          RecommendResponse(
            recommendations = enriched,
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
            val conn = java.net.URI.create(url).toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
            conn.setRequestMethod("GET")
            conn.setConnectTimeout(10000)
            conn.setReadTimeout(10000)
            try
              val contentType = Option(conn.getContentType).getOrElse("application/octet-stream")
              val is = conn.getInputStream
              val baos = new java.io.ByteArrayOutputStream()
              val buf = new Array[Byte](8192)
              var n = is.read(buf)
              while n >= 0 do
                baos.write(buf, 0, n)
                n = is.read(buf)
              Right((baos.toByteArray, contentType, ImmutableCacheControl))
            finally conn.disconnect()
          catch
            case e: Exception =>
              logger.error(s"CORS proxy failed for $url", e)
              Left(Fail.IncorrectInput(s"Proxy request failed: ${e.getClass.getName}: ${e.getMessage}"))
    }

  private def resolveGamesAsMap(ids: List[GameId]): Map[GameId, GameData] =
    gameService.resolveGameIds(ids) match
      case Right(games) => games.map(g => (g.id, g)).toMap
      case Left(_)      => gameCache.loadBatch(ids).map(g => (g.id, g)).toMap

  private def resolveInputGames(gameIds: List[GameId]): List[GameData] =
    resolveGamesAsMap(gameIds).values.toList

  private def enrichRecommendations(recommendations: List[RecommendedGame]): List[RecommendedGameJson] =
    val gameMap = resolveGamesAsMap(recommendations.map(_.gameId))

    recommendations.map { r =>
      RecommendedGameJson(
        r.gameId.value,
        r.name,
        math.round(r.similarityScore * ScorePrecision) / ScorePrecision,
        gameMap.get(r.gameId).map(_.toJson)
      )
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
    hotEndpoint,
    gameEndpoint,
    playsEndpoint,
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
