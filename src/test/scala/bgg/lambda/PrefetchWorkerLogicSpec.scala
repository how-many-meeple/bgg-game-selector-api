package bgg.lambda

import bgg.TestFixtures.{stubClient, testGame}
import bgg.bggapi.{BggClient, GameService}
import bgg.cache.{MemoryGameCache, TestCacheProvider}
import bgg.domain.*
import bgg.prefetch.{PrefetchStatus, SqlitePrefetchStatusStore}
import bgg.store.SqliteVectorStore
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}
import java.time.Instant

class PrefetchWorkerLogicSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val prefetchDb = "test_worker_prefetch.sqlite"
  private val vectorDb = "test_worker_vectors.sqlite"
  private var prefetchStore: SqlitePrefetchStatusStore = _
  private var vectorStore: SqliteVectorStore = _
  private var gameCache: MemoryGameCache = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit
    Files.deleteIfExists(Paths.get(vectorDb)): Unit
    prefetchStore = SqlitePrefetchStatusStore(prefetchDb)
    vectorStore = SqliteVectorStore(vectorDb)
    gameCache = MemoryGameCache()

  override def afterEach(): Unit =
    prefetchStore.close()
    vectorStore.close()
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit
    Files.deleteIfExists(Paths.get(vectorDb)): Unit

  private def makeLogic(client: BggClient): PrefetchWorkerLogic =
    val gameService = GameService(client, TestCacheProvider(gameCache, vectorStore), 50, () => Instant.now())
    PrefetchWorkerLogic(gameService, prefetchStore)

  "PrefetchWorkerLogic" should:

    "set status to Completed on successful collection prefetch" in:
      val games = List(testGame(1, "Catan"), testGame(2, "Pandemic"))
      val client = stubClient(
        collectionResult = Right(List(GameId(1), GameId(2))),
        gamesResult = Right(games)
      )
      val logic = makeLogic(client)

      logic.process(PrefetchMessage("collection", "testuser"))

      val record = prefetchStore.get(SourceType.Collection, "testuser")
      record.map(_.status) shouldBe Some(PrefetchStatus.Completed)

    "set status to Completed on successful geeklist prefetch" in:
      val games = List(testGame(10, "Chess"))
      val client = stubClient(
        geeklistResult = Right(List(GameId(10))),
        gamesResult = Right(games)
      )
      val logic = makeLogic(client)

      logic.process(PrefetchMessage("geeklist", "12345"))

      val record = prefetchStore.get(SourceType.GeeKList, "12345")
      record.map(_.status) shouldBe Some(PrefetchStatus.Completed)

    "set status to NotFound when collection user doesn't exist" in:
      val client = stubClient(collectionResult = Left(Fail.BggUserNotFound("ghost")))
      val logic = makeLogic(client)

      logic.process(PrefetchMessage("collection", "ghost"))

      val record = prefetchStore.get(SourceType.Collection, "ghost")
      record.map(_.status) shouldBe Some(PrefetchStatus.NotFound)
      record.map(_.reason).get should include("ghost")

    "set status to NotFound when geeklist doesn't exist" in:
      val client = stubClient(geeklistResult = Left(Fail.BggListNotFound("99999")))
      val logic = makeLogic(client)

      logic.process(PrefetchMessage("geeklist", "99999"))

      val record = prefetchStore.get(SourceType.GeeKList, "99999")
      record.map(_.status) shouldBe Some(PrefetchStatus.NotFound)
      record.map(_.reason).get should include("99999")

    "set status to Failed on BGG rate limit" in:
      val client = stubClient(collectionResult = Left(Fail.BggRateLimited("Too many")))
      val logic = makeLogic(client)

      logic.process(PrefetchMessage("collection", "testuser"))

      val record = prefetchStore.get(SourceType.Collection, "testuser")
      record.map(_.status) shouldBe Some(PrefetchStatus.Failed)
      record.map(_.reason).get should include("BggRateLimited")

    "set status to Processing before completion" in:
      var statusDuringFetch: Option[PrefetchStatus] = None
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] =
          statusDuringFetch = prefetchStore.get(SourceType.Collection, username).map(_.status)
          Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(Nil)
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val logic = makeLogic(client)
      logic.process(PrefetchMessage("collection", "testuser"))

      statusDuringFetch shouldBe Some(PrefetchStatus.Processing)

    "handle invalid source_type gracefully without crashing" in:
      val client = stubClient()
      val logic = makeLogic(client)

      noException should be thrownBy logic.process(PrefetchMessage("invalid_type", "testuser"))

    "set status to Completed on successful hot games prefetch" in:
      val games = List(testGame(1, "Catan"))
      val hotClient = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(List(GameId(1)))
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(games)
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)
      val logic = makeLogic(hotClient)

      logic.process(PrefetchMessage("hot", "trending"))

      val record = prefetchStore.get(SourceType.Hot, "trending")
      record.map(_.status) shouldBe Some(PrefetchStatus.Completed)

    "handleSqsEvent should process valid SQS records" in:
      val games = List(testGame(1, "Catan"))
      val client = stubClient(
        collectionResult = Right(List(GameId(1))),
        gamesResult = Right(games)
      )
      val logic = makeLogic(client)

      val event = """{"Records":[{"body":"{\"source_type\":\"collection\",\"source_id\":\"testuser\"}"}]}"""
      val result = logic.handleSqsEvent(event)

      result shouldBe """{"statusCode":200}"""
      prefetchStore.get(SourceType.Collection, "testuser").map(_.status) shouldBe Some(PrefetchStatus.Completed)

    "handleSqsEvent should handle invalid JSON gracefully" in:
      val client = stubClient()
      val logic = makeLogic(client)

      val result = logic.handleSqsEvent("not json at all")

      result shouldBe """{"statusCode":200}"""

    "handleSqsEvent should handle invalid message body gracefully" in:
      val client = stubClient()
      val logic = makeLogic(client)

      val event = """{"Records":[{"body":"not a valid message"}]}"""
      val result = logic.handleSqsEvent(event)

      result shouldBe """{"statusCode":200}"""

    "handleSqsEvent should handle record with missing body field" in:
      val client = stubClient()
      val logic = makeLogic(client)

      val event = """{"Records":[{"notBody":"something"}]}"""
      val result = logic.handleSqsEvent(event)

      result shouldBe """{"statusCode":200}"""

    "cache fetched games during prefetch" in:
      val games = List(testGame(1, "Catan"))
      val client = stubClient(
        collectionResult = Right(List(GameId(1))),
        gamesResult = Right(games)
      )
      val logic = makeLogic(client)

      logic.process(PrefetchMessage("collection", "testuser"))

      gameCache.load(GameId(1)).map(_.name) shouldBe Some("Catan")
