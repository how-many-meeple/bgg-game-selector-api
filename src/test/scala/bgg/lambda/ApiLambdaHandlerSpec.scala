package bgg.lambda

import bgg.bggapi.{BggClient, GameService}
import bgg.cache.MemoryGameCache
import bgg.config.*
import bgg.domain.*
import bgg.prefetch.{PrefetchStatus, SqlitePrefetchStatusStore}
import bgg.routes.{ApiEndpoints, NoOpSqsSender}
import bgg.store.{SqliteVectorStore, StoredVector}
import bgg.vector.VectorMath
import io.circe.generic.auto.*
import io.circe.parser.{parse => parseJson}
import io.circe.Json
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sttp.tapir.serverless.aws.lambda.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files, Paths}
import java.time.Instant

class ApiLambdaHandlerSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val prefetchDb = "test_lambda_handler_prefetch.sqlite"
  private val vectorDb = "test_lambda_handler_vectors.sqlite"
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

  private def stubClient(
      collectionResult: Either[Fail, List[GameId]] = Right(Nil),
      geeklistResult: Either[Fail, List[GameId]] = Right(Nil),
      gamesResult: Either[Fail, List[GameData]] = Right(Nil)
  ): BggClient = new BggClient:
    def fetchCollection(username: String): Either[Fail, List[GameId]] = collectionResult
    def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = geeklistResult
    def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = gamesResult
    def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)

  private def makeHandler(bggClient: BggClient): SyncLambdaHandler[AwsRequestV1] =
    val gameService = GameService(bggClient, gameCache, vectorStore, 50, () => Instant.now())
    val endpoints = ApiEndpoints(gameService, gameCache, vectorStore, prefetchStore, NoOpSqsSender(), testConfig)
    SyncLambdaHandler(endpoints.all, AwsSyncServerOptions.default)

  private def sendEvent(handler: SyncLambdaHandler[AwsRequestV1], eventJson: String): (Int, Json) =
    val input = ByteArrayInputStream(eventJson.getBytes("UTF-8"))
    val output = ByteArrayOutputStream()
    handler.handleRequest(input, output, null)
    val responseStr = output.toString("UTF-8")
    val json = parseJson(responseStr).getOrElse(Json.Null)
    val statusCode = json.hcursor.get[Int]("statusCode").getOrElse(-1)
    (statusCode, json)

  private def responseBody(json: Json): Json =
    val isBase64 = json.hcursor.get[Boolean]("isBase64Encoded").getOrElse(false)
    val rawBody = json.hcursor.get[String]("body").getOrElse("")
    val bodyStr =
      if isBase64 then new String(java.util.Base64.getDecoder.decode(rawBody), "UTF-8")
      else rawBody
    parseJson(bodyStr).getOrElse(Json.Null)

  private def apiGatewayGetEvent(path: String, resource: String): String =
    s"""{
       |  "resource": "$resource",
       |  "path": "$path",
       |  "httpMethod": "GET",
       |  "headers": {"Accept": "application/json"},
       |  "queryStringParameters": null,
       |  "requestContext": {
       |    "resourceId": "test",
       |    "resourcePath": "$resource",
       |    "httpMethod": "GET",
       |    "protocol": "HTTP/1.1",
       |    "identity": {"sourceIp": "127.0.0.1", "userAgent": "test"},
       |    "domainName": "test.execute-api.us-east-1.amazonaws.com",
       |    "apiId": "testapi"
       |  },
       |  "body": null,
       |  "isBase64Encoded": false
       |}""".stripMargin

  private def apiGatewayPostEvent(path: String, resource: String, body: String): String =
    s"""{
       |  "resource": "$resource",
       |  "path": "$path",
       |  "httpMethod": "POST",
       |  "headers": {"Accept": "application/json", "Content-Type": "application/json"},
       |  "queryStringParameters": null,
       |  "requestContext": {
       |    "resourceId": "test",
       |    "resourcePath": "$resource",
       |    "httpMethod": "POST",
       |    "protocol": "HTTP/1.1",
       |    "identity": {"sourceIp": "127.0.0.1", "userAgent": "test"},
       |    "domainName": "test.execute-api.us-east-1.amazonaws.com",
       |    "apiId": "testapi"
       |  },
       |  "body": ${Json.fromString(body).noSpaces},
       |  "isBase64Encoded": false
       |}""".stripMargin

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

  "SyncLambdaHandler with API Gateway V1 events" should:
    "route GET /health and return 200" in:
      val handler = makeHandler(stubClient())
      val event = apiGatewayGetEvent("/health", "/health")
      val (status, json) = sendEvent(handler, event)

      status shouldBe 200
      val body = responseBody(json)
      body.hcursor.get[String]("status").toOption shouldBe Some("ok")

    "route GET /collection/:username and return 200 with games" in:
      val games = List(testGame(1, "Catan"), testGame(2, "Pandemic"))
      val client = stubClient(
        collectionResult = Right(List(GameId(1), GameId(2))),
        gamesResult = Right(games)
      )
      val handler = makeHandler(client)
      val event = apiGatewayGetEvent("/collection/testuser", "/collection/{username}")
      val (status, json) = sendEvent(handler, event)

      status shouldBe 200
      val body = responseBody(json)
      body.asArray.map(_.size) shouldBe Some(2)

    "route GET /collection/:username and return 404 for unknown user" in:
      val client = stubClient(collectionResult = Left(Fail.BggUserNotFound("ghost")))
      val handler = makeHandler(client)
      val event = apiGatewayGetEvent("/collection/ghost", "/collection/{username}")
      val (status, json) = sendEvent(handler, event)

      status shouldBe 404
      val body = responseBody(json)
      body.hcursor.get[String]("error").toOption.get should include("ghost")

    "route GET /collection/:username and return 202 when prefetch is pending" in:
      prefetchStore.set(SourceType.Collection, "testuser", PrefetchStatus.Pending)
      val handler = makeHandler(stubClient())
      val event = apiGatewayGetEvent("/collection/testuser", "/collection/{username}")
      val (status, _) = sendEvent(handler, event)

      status shouldBe 202

    "route POST /prefetch and return 202 for new prefetch" in:
      val handler = makeHandler(stubClient())
      val body = """{"sourceType":"collection","sourceId":"testuser"}"""
      val event = apiGatewayPostEvent("/prefetch", "/prefetch", body)
      val (status, json) = sendEvent(handler, event)

      status shouldBe 202
      val respBody = responseBody(json)
      respBody.hcursor.get[String]("status").toOption shouldBe Some("pending")

    "route POST /recommendations/from-games and return 200" in:
      val g1 = testGame(1, "Catan")
      val g2 = testGame(2, "Pandemic")
      gameCache.save(g1)
      gameCache.save(g2)
      vectorStore.save(StoredVector(GameId(1), "Catan", VectorMath.generateGameVector(g1), Instant.now()))
      vectorStore.save(StoredVector(GameId(2), "Pandemic", VectorMath.generateGameVector(g2), Instant.now()))

      val handler = makeHandler(stubClient())
      val body = """{"gameIds":[1],"limit":5}"""
      val event = apiGatewayPostEvent("/recommendations/from-games", "/recommendations/from-games", body)
      val (status, json) = sendEvent(handler, event)

      status shouldBe 200
      val respBody = responseBody(json)
      respBody.hcursor.get[Int]("inputGamesCount").toOption shouldBe Some(1)

    "route GET /recommendations/schema and return 200" in:
      val handler = makeHandler(stubClient())
      val event = apiGatewayGetEvent("/recommendations/schema", "/recommendations/schema")
      val (status, json) = sendEvent(handler, event)

      status shouldBe 200
      val body = responseBody(json)
      body.hcursor.get[Int]("total_dimensions").toOption shouldBe Some(155)

    "include Content-Type header in the response" in:
      val handler = makeHandler(stubClient())
      val event = apiGatewayGetEvent("/health", "/health")
      val (_, json) = sendEvent(handler, event)

      val headers = json.hcursor.downField("headers").focus.flatMap(_.asObject)
      headers.isDefined shouldBe true
      headers.get.toMap.contains("Content-Type") shouldBe true

    "return 404 for unmatched routes" in:
      val handler = makeHandler(stubClient())
      val event = apiGatewayGetEvent("/nonexistent", "/nonexistent")
      val (status, _) = sendEvent(handler, event)

      status shouldBe 404

    "route GET /search/:query and return 200 with empty list" in:
      val handler = makeHandler(stubClient())
      val event = apiGatewayGetEvent("/search/pandemic", "/search/{game_name}")
      val (status, json) = sendEvent(handler, event)

      status shouldBe 200
      val body = responseBody(json)
      body.asArray shouldBe Some(Vector.empty)

    "route POST /prefetch and return 400 for invalid sourceType" in:
      val handler = makeHandler(stubClient())
      val body = """{"sourceType":"invalid","sourceId":"test"}"""
      val event = apiGatewayPostEvent("/prefetch", "/prefetch", body)
      val (status, json) = sendEvent(handler, event)

      status shouldBe 400
      val respBody = responseBody(json)
      respBody.hcursor.get[String]("error").toOption.get should include("invalid")

    "route POST /prefetch and return 400 for empty sourceId" in:
      val handler = makeHandler(stubClient())
      val body = """{"sourceType":"collection","sourceId":"  "}"""
      val event = apiGatewayPostEvent("/prefetch", "/prefetch", body)
      val (status, json) = sendEvent(handler, event)

      status shouldBe 400
      val respBody = responseBody(json)
      respBody.hcursor.get[String]("error").toOption.get should include("empty")

    "route POST /recommendations/from-games and return 404 when no games in cache" in:
      val handler = makeHandler(stubClient())
      val body = """{"gameIds":[999],"limit":5}"""
      val event = apiGatewayPostEvent("/recommendations/from-games", "/recommendations/from-games", body)
      val (status, json) = sendEvent(handler, event)

      status shouldBe 404
      val respBody = responseBody(json)
      respBody.hcursor.get[String]("error").toOption should be(defined)

    "route GET /prefetch/status/:sourceType/:sourceId and return 404 when no record" in:
      val handler = makeHandler(stubClient())
      val event =
        apiGatewayGetEvent("/prefetch/status/collection/unknown", "/prefetch/status/{source_type}/{source_id}")
      val (status, json) = sendEvent(handler, event)

      status shouldBe 404
      val respBody = responseBody(json)
      respBody.hcursor.get[String]("error").toOption.get should include("No prefetch run found")

    "route GET /prefetch/status/:sourceType/:sourceId and return 200 with status" in:
      prefetchStore.set(SourceType.Collection, "myuser", PrefetchStatus.Completed)
      val handler = makeHandler(stubClient())
      val event = apiGatewayGetEvent("/prefetch/status/collection/myuser", "/prefetch/status/{source_type}/{source_id}")
      val (status, json) = sendEvent(handler, event)

      status shouldBe 200
      val respBody = responseBody(json)
      respBody.hcursor.get[String]("status").toOption shouldBe Some("completed")
