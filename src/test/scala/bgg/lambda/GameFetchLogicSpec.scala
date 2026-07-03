package bgg.lambda

import bgg.TestFixtures.testGame
import bgg.bggapi.BggClient
import bgg.cache.{MemoryGameCache, TestCacheProvider}
import bgg.domain.*
import bgg.store.SqliteVectorStore
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}
import java.time.Instant

class GameFetchLogicSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val vectorDb = "test_game_fetch_vectors.sqlite"
  private var vectorStore: SqliteVectorStore = _
  private var gameCache: MemoryGameCache = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(vectorDb)): Unit
    vectorStore = SqliteVectorStore(vectorDb)
    gameCache = MemoryGameCache()

  override def afterEach(): Unit =
    vectorStore.close()
    Files.deleteIfExists(Paths.get(vectorDb)): Unit

  private def makeLogic(client: BggClient): GameFetchLogic =
    val caches = TestCacheProvider(gameCache, vectorStore)
    GameFetchLogic(client, caches, 50, () => Instant.now())

  "GameFetchLogic" should:

    "fetch games and return succeeded IDs" in:
      val games = List(testGame(1, "Catan"), testGame(2, "Pandemic"))
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[CollectionItem]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] =
          Right(games.filter(g => ids.contains(g.id)))
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val logic = makeLogic(client)
      val input = """{"gameIds":[1,2]}"""
      val result = parse(logic.handle(input)).toOption.get

      val succeeded = result.hcursor.downField("succeeded").as[List[Int]].toOption.get
      succeeded should contain allOf (1, 2)
      result.hcursor.downField("totalCached").as[Int].toOption.get shouldBe 2

    "skip already-cached games" in:
      val game1 = testGame(1, "Catan")
      gameCache.save(game1, Instant.now())

      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[CollectionItem]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] =
          ids should not contain GameId(1)
          Right(List(testGame(2, "Pandemic")))
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val logic = makeLogic(client)
      val input = """{"gameIds":[1,2]}"""
      val result = parse(logic.handle(input)).toOption.get

      val succeeded = result.hcursor.downField("succeeded").as[List[Int]].toOption.get
      succeeded should contain allOf (1, 2)

    "report failed sub-batches" in:
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[CollectionItem]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] =
          Left(Fail.IncorrectInput("BGG error"))
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val logic = makeLogic(client)
      val input = """{"gameIds":[1,2,3]}"""
      val result = parse(logic.handle(input)).toOption.get

      val failed = result.hcursor.downField("failed").as[List[Int]].toOption.get
      failed should contain allOf (1, 2, 3)

    "throw BggRateLimitedException on rate limit" in:
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[CollectionItem]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] =
          Left(Fail.BggRateLimited("rate limited"))
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val logic = makeLogic(client)
      val input = """{"gameIds":[1,2,3]}"""

      intercept[BggRateLimitedException](logic.handle(input))

    "return all as succeeded when all are already cached" in:
      gameCache.save(testGame(1, "Catan"), Instant.now())
      gameCache.save(testGame(2, "Pandemic"), Instant.now())

      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[CollectionItem]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] =
          fail("Should not be called when all games are cached")
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val logic = makeLogic(client)
      val input = """{"gameIds":[1,2]}"""
      val result = parse(logic.handle(input)).toOption.get

      val succeeded = result.hcursor.downField("succeeded").as[List[Int]].toOption.get
      succeeded should contain allOf (1, 2)
      result.hcursor.downField("failed").as[List[Int]].toOption.get shouldBe empty
