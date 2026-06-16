package bgg.routes

import bgg.bggapi.{BggClient, GameService}
import bgg.cache.MemoryGameCache
import bgg.config.*
import bgg.domain.*
import bgg.prefetch.{PrefetchStatus, SqlitePrefetchStatusStore}
import bgg.store.{SqliteVectorStore, StoredVector}
import bgg.vector.VectorMath
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sttp.client4.*
import sttp.client4.testing.BackendStub
import sttp.model.StatusCode
import sttp.monad.IdentityMonad
import sttp.shared.Identity
import sttp.tapir.server.stub4.TapirStubInterpreter

import java.nio.file.{Files, Paths}
import java.time.Instant

class ApiEndpointsSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val prefetchDb = "test_endpoints_prefetch.sqlite"
  private val vectorDb = "test_endpoints_vectors.sqlite"
  private var prefetchStore: SqlitePrefetchStatusStore = _
  private var vectorStore: SqliteVectorStore = _
  private var gameCache: MemoryGameCache = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit
    Files.deleteIfExists(Paths.get(vectorDb)): Unit
    prefetchStore = SqlitePrefetchStatusStore(prefetchDb)
    vectorStore = SqliteVectorStore(vectorDb)
    gameCache = MemoryGameCache(ttlSeconds = 3600)

  override def afterEach(): Unit =
    prefetchStore.close()
    vectorStore.close()
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit
    Files.deleteIfExists(Paths.get(vectorDb)): Unit

  private def makeEndpoints(bggClient: BggClient): ApiEndpoints =
    val gameService = GameService(bggClient, gameCache, vectorStore, 50, () => Instant.now())
    ApiEndpoints(gameService, gameCache, vectorStore, prefetchStore, NoOpSqsSender(), testConfig)

  private def makeBackend(endpoints: ApiEndpoints): Backend[Identity] =
    TapirStubInterpreter(BackendStub(IdentityMonad))
      .whenServerEndpointsRunLogic(endpoints.all)
      .backend()

  private val testConfig = AppConfig(
    bgg = BggConfig(accessToken = "", timeoutSeconds = 10, retries = 3, retryDelaySeconds = 1),
    cache = CacheConfig(
      backend = CacheBackend.Memory,
      requestCacheTtlSeconds = 60,
      gameCacheTtlSeconds = 3600,
      vectorMinRatings = 50,
      sqliteRequestCachePath = "",
      sqliteGameCachePath = "",
      sqliteVectorStorePath = "",
      sqlitePrefetchStatusPath = ""
    ),
    aws = AwsConfig(
      region = "us-east-1",
      dynamoRequestTable = "",
      dynamoGameTable = "",
      dynamoVectorTable = "",
      dynamoPrefetchTable = "",
      prefetchSqsUrl = ""
    ),
    server = ServerConfig(host = "0.0.0.0", port = 8080, allowedOrigins = List("*"))
  )

  private def testGame(id: Int, name: String): GameData = GameData(
    id = GameId(id),
    name = name,
    yearPublished = Some(2020),
    minPlayers = Some(2),
    maxPlayers = Some(4),
    minPlayingTime = Some(30),
    maxPlayingTime = Some(60),
    playingTime = Some(60),
    ratingAverage = Some(7.5),
    ratingAverageWeight = Some(2.5),
    expansion = false,
    mechanics = List("Hand Management"),
    categories = List("Fantasy"),
    playerSuggestions = Nil,
    usersRated = Some(500)
  )

  private def stubClient(
      collectionResult: Either[Fail, List[GameId]] = Right(Nil),
      geeklistResult: Either[Fail, List[GameId]] = Right(Nil),
      gamesResult: Either[Fail, List[GameData]] = Right(Nil)
  ): BggClient = new BggClient:
    def fetchCollection(username: String): Either[Fail, List[GameId]] = collectionResult
    def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = geeklistResult
    def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = gamesResult
    def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)

  "GET /health" should:
    "return 200 with status ok" in:
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/health")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Ok
      val json = parseJson(response.body).getOrElse(Json.Null)
      json.hcursor.get[String]("status").toOption shouldBe Some("ok")

  "GET /collection/:username" should:
    "return 200 with games on success" in:
      val games = List(testGame(1, "Catan"), testGame(2, "Pandemic"))
      val client = stubClient(
        collectionResult = Right(List(GameId(1), GameId(2))),
        gamesResult = Right(games)
      )
      val endpoints = makeEndpoints(client)
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/collection/testuser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Ok
      val json = parseJson(response.body).getOrElse(Json.Null)
      json.asArray.map(_.size) shouldBe Some(2)

    "return 404 when user not found" in:
      val client = stubClient(collectionResult = Left(Fail.BggUserNotFound("ghost")))
      val endpoints = makeEndpoints(client)
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/collection/ghost")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.NotFound
      val json = parseJson(response.body).getOrElse(Json.Null)
      json.hcursor.get[String]("error").toOption.get should include("ghost")

    "return 503 when BGG rate limits" in:
      val client = stubClient(collectionResult = Left(Fail.BggRateLimited("Too many requests")))
      val endpoints = makeEndpoints(client)
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/collection/testuser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.ServiceUnavailable

    "return 202 when prefetch is pending" in:
      prefetchStore.set(SourceType.Collection, "testuser", PrefetchStatus.Pending)
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/collection/testuser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Accepted

    "return 202 when prefetch is processing" in:
      prefetchStore.set(SourceType.Collection, "testuser", PrefetchStatus.Processing)
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/collection/testuser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Accepted

    "return 404 when prefetch result is not_found" in:
      prefetchStore.set(
        SourceType.Collection,
        "missinguser",
        PrefetchStatus.NotFound,
        "No user found called 'missinguser'"
      )
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/collection/missinguser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.NotFound
      val json = parseJson(response.body).getOrElse(Json.Null)
      json.hcursor.get[String]("error").toOption.get should include("missinguser")

    "return 503 when prefetch failed" in:
      prefetchStore.set(SourceType.Collection, "testuser", PrefetchStatus.Failed, "BGG timed out")
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/collection/testuser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.ServiceUnavailable
      val json = parseJson(response.body).getOrElse(Json.Null)
      json.hcursor.get[String]("error").toOption.get should include("BGG timed out")

    "proceed normally when prefetch is completed" in:
      prefetchStore.set(SourceType.Collection, "testuser", PrefetchStatus.Completed)
      val client = stubClient(
        collectionResult = Right(List(GameId(1))),
        gamesResult = Right(List(testGame(1, "Catan")))
      )
      val endpoints = makeEndpoints(client)
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/collection/testuser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Ok

  "GET /geeklist/:id" should:
    "return 200 with games on success" in:
      val client = stubClient(
        geeklistResult = Right(List(GameId(10))),
        gamesResult = Right(List(testGame(10, "Chess")))
      )
      val endpoints = makeEndpoints(client)
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/geeklist/12345")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Ok

    "return 202 when prefetch is pending" in:
      prefetchStore.set(SourceType.GeeKList, "99999", PrefetchStatus.Pending)
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/geeklist/99999")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Accepted

    "return 404 when geeklist not found via prefetch" in:
      prefetchStore.set(SourceType.GeeKList, "99999", PrefetchStatus.NotFound, "List not found")
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/geeklist/99999")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.NotFound

    "return 503 when geeklist prefetch failed" in:
      prefetchStore.set(SourceType.GeeKList, "99999", PrefetchStatus.Failed, "Timeout")
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/geeklist/99999")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.ServiceUnavailable

  "POST /prefetch" should:
    "return 202 and queue a new prefetch" in:
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .post(uri"http://test/prefetch")
        .body("""{"sourceType":"collection","sourceId":"testuser"}""")
        .contentType("application/json")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Accepted
      prefetchStore.get(SourceType.Collection, "testuser").map(_.status) shouldBe Some(PrefetchStatus.Pending)

    "return 200 when already pending" in:
      prefetchStore.set(SourceType.Collection, "testuser", PrefetchStatus.Pending)
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .post(uri"http://test/prefetch")
        .body("""{"sourceType":"collection","sourceId":"testuser"}""")
        .contentType("application/json")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Ok

    "return 202 when re-queuing after failure" in:
      prefetchStore.set(SourceType.Collection, "testuser", PrefetchStatus.Failed)
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .post(uri"http://test/prefetch")
        .body("""{"sourceType":"collection","sourceId":"testuser"}""")
        .contentType("application/json")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Accepted

    "return 400 for invalid source_type" in:
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .post(uri"http://test/prefetch")
        .body("""{"sourceType":"invalid","sourceId":"testuser"}""")
        .contentType("application/json")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.BadRequest

    "return 400 for empty source_id" in:
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .post(uri"http://test/prefetch")
        .body("""{"sourceType":"collection","sourceId":"  "}""")
        .contentType("application/json")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.BadRequest

  "GET /prefetch/status/:sourceType/:sourceId" should:
    "return 200 with current status" in:
      prefetchStore.set(SourceType.Collection, "testuser", PrefetchStatus.Processing)
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/prefetch/status/collection/testuser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Ok
      val json = parseJson(response.body).getOrElse(Json.Null)
      json.hcursor.get[String]("status").toOption shouldBe Some("processing")

    "return 404 when no prefetch run exists" in:
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/prefetch/status/collection/unknownuser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.NotFound

    "return 400 for invalid source_type" in:
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/prefetch/status/invalid/testuser")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.BadRequest

  "POST /recommendations/from-games" should:
    "return recommendations when games are in cache" in:
      val g1 = testGame(1, "Catan")
      val g2 = testGame(2, "Pandemic")
      gameCache.save(g1)
      gameCache.save(g2)
      vectorStore.save(StoredVector(GameId(1), "Catan", VectorMath.generateGameVector(g1), Instant.now()))
      vectorStore.save(StoredVector(GameId(2), "Pandemic", VectorMath.generateGameVector(g2), Instant.now()))

      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .post(uri"http://test/recommendations/from-games")
        .body("""{"gameIds":[1],"limit":5}""")
        .contentType("application/json")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Ok
      val json = parseJson(response.body).getOrElse(Json.Null)
      json.hcursor.get[Int]("inputGamesCount").toOption shouldBe Some(1)

    "return 404 when no input games found in cache" in:
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .post(uri"http://test/recommendations/from-games")
        .body("""{"gameIds":[999],"limit":5}""")
        .contentType("application/json")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.NotFound

  "GET /recommendations/schema" should:
    "return schema with dimensions info" in:
      val endpoints = makeEndpoints(stubClient())
      val backend = makeBackend(endpoints)
      val response = basicRequest
        .get(uri"http://test/recommendations/schema")
        .response(asStringAlways)
        .send(backend)

      response.code shouldBe StatusCode.Ok
      val json = parseJson(response.body).getOrElse(Json.Null)
      json.hcursor.get[Int]("total_dimensions").toOption shouldBe Some(155)
