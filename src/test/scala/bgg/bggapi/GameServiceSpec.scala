package bgg.bggapi

import bgg.TestFixtures.testGame
import bgg.cache.{MemoryGameCache, PlaysCache, RequestCache, TestCacheProvider}
import bgg.domain.*
import bgg.store.SqliteVectorStore
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}
import java.time.Instant
import scala.collection.mutable

class GameServiceSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val vectorDb = "test_game_service_vectors.sqlite"
  private var vectorStore: SqliteVectorStore = _
  private var gameCache: MemoryGameCache = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(vectorDb)): Unit
    vectorStore = SqliteVectorStore(vectorDb)
    gameCache = MemoryGameCache()

  override def afterEach(): Unit =
    vectorStore.close()
    Files.deleteIfExists(Paths.get(vectorDb)): Unit

  private class MemoryPlaysCache(fresh: Boolean = false) extends PlaysCache:
    val store: mutable.Map[String, List[PlayData]] = mutable.Map.empty
    def save(username: String, plays: List[PlayData]): Unit = store(username) = plays
    def load(username: String): Option[List[PlayData]] = store.get(username)
    def isFresh(username: String, maxAgeSeconds: Long): Boolean = fresh && store.contains(username)
    def append(username: String, plays: List[PlayData]): Unit =
      store(username) = store.getOrElse(username, Nil) ++ plays
    def maxPlayId(username: String): Option[Int] = store.get(username).flatMap(_.map(_.playId).maxOption)
    def touch(username: String): Unit = ()

  private class MemoryRequestCache extends RequestCache:
    private val store: mutable.Map[String, String] = mutable.Map.empty
    def load[T: io.circe.Decoder](key: String): Option[T] =
      store.get(key).flatMap(json => io.circe.parser.decode[T](json).toOption)
    def save[T: io.circe.Encoder](key: String, value: T, ttlSeconds: Long, now: Instant): Unit =
      store(key) = io.circe.syntax.EncoderOps(value).asJson.noSpaces

  private val testPlay = PlayData(
    playId = 1,
    gameId = GameId(13),
    gameName = "Catan",
    date = "2024-03-15",
    quantity = 1,
    length = 60,
    players = List(PlayPlayer("alice", "Alice", Some("10"), win = true))
  )

  private def stubClient(
      playsPage1: Either[Fail, List[PlayData]] = Right(Nil)
  ): BggClient = new BggClient:
    def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
    def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
    def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
    def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(Nil)
    def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
    def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] =
      if page == 1 then playsPage1 else Right(Nil)

  "resolvePlays" should:
    "serve from cache when plays are cached" in:
      val playsCache = MemoryPlaysCache()
      playsCache.save("alice", List(testPlay))
      val caches = TestCacheProvider(gameCache, vectorStore, playsCache = playsCache)
      val service = GameService(stubClient(), caches, 50, () => Instant.now())

      val result = service.resolvePlays("alice")

      result shouldBe Right(List(testPlay))

    "fetch from BGG and cache when no cached plays" in:
      val playsCache = MemoryPlaysCache()
      val caches = TestCacheProvider(gameCache, vectorStore, playsCache = playsCache)
      val client = stubClient(playsPage1 = Right(List(testPlay)))
      val service = GameService(client, caches, 50, () => Instant.now())

      val result = service.resolvePlays("bob")

      result shouldBe Right(List(testPlay))
      playsCache.store.get("bob") shouldBe Some(List(testPlay))

    "return error when BGG fetch fails and no cache" in:
      val playsCache = MemoryPlaysCache()
      val caches = TestCacheProvider(gameCache, vectorStore, playsCache = playsCache)
      val client = stubClient(playsPage1 = Left(Fail.BggUserNotFound("ghost")))
      val service = GameService(client, caches, 50, () => Instant.now())

      val result = service.resolvePlays("ghost")

      result shouldBe Left(Fail.BggUserNotFound("ghost"))

    "paginate until empty page" in:
      var pageRequests = List.empty[Int]
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(Nil)
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] =
          pageRequests = pageRequests :+ page
          if page <= 2 then Right(List(testPlay.copy(playId = page)))
          else Right(Nil)

      val playsCache = MemoryPlaysCache()
      val caches = TestCacheProvider(gameCache, vectorStore, playsCache = playsCache)
      val service = GameService(client, caches, 50, () => Instant.now())

      val result = service.resolvePlays("paginated")

      result.isRight shouldBe true
      result.toOption.get should have size 2
      pageRequests shouldBe List(1, 2, 3)

    "stop pagination on error after first page" in:
      var pageRequests = List.empty[Int]
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(Nil)
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] =
          pageRequests = pageRequests :+ page
          if page == 1 then Right(List(testPlay))
          else Left(Fail.BggRateLimited("rate limited"))

      val playsCache = MemoryPlaysCache()
      val caches = TestCacheProvider(gameCache, vectorStore, playsCache = playsCache)
      val service = GameService(client, caches, 50, () => Instant.now())

      val result = service.resolvePlays("ratelimited")

      result shouldBe Right(List(testPlay))

  "fetchAndCachePlays" should:
    "fetch and save plays to cache when not cached" in:
      val playsCache = MemoryPlaysCache()
      val caches = TestCacheProvider(gameCache, vectorStore, playsCache = playsCache)
      val client = stubClient(playsPage1 = Right(List(testPlay)))
      val service = GameService(client, caches, 50, () => Instant.now())

      service.fetchAndCachePlays("dave")

      playsCache.store.get("dave") shouldBe Some(List(testPlay))

    "skip fetch when cache is fresh" in:
      var fetchCount = 0
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(Nil)
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] =
          fetchCount += 1
          Right(List(testPlay))

      val playsCache = MemoryPlaysCache(fresh = true)
      playsCache.save("dave", List(testPlay))
      val caches = TestCacheProvider(gameCache, vectorStore, playsCache = playsCache)
      val service = GameService(client, caches, 50, () => Instant.now())

      service.fetchAndCachePlays("dave")

      fetchCount shouldBe 0

    "not crash when fetch fails" in:
      val playsCache = MemoryPlaysCache()
      val caches = TestCacheProvider(gameCache, vectorStore, playsCache = playsCache)
      val client = stubClient(playsPage1 = Left(Fail.BggUserNotFound("ghost")))
      val service = GameService(client, caches, 50, () => Instant.now())

      noException should be thrownBy service.fetchAndCachePlays("ghost")
      playsCache.store.get("ghost") shouldBe None

  "resolveHotGames" should:
    "cache result in request cache and serve from cache on second call" in:
      var fetchCount = 0
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] =
          fetchCount += 1
          Right(List(GameId(1)))
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(List(testGame(1, "Catan")))
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val reqCache = MemoryRequestCache()
      val caches = TestCacheProvider(gameCache, vectorStore, requestCache = reqCache)
      val service = GameService(client, caches, 50, () => Instant.now())

      val result1 = service.resolveHotGames()
      val result2 = service.resolveHotGames()

      result1.isRight shouldBe true
      result2.isRight shouldBe true
      fetchCount shouldBe 1

  "resolveGameIds (vectorize paths)" should:
    "vectorize a mature game with enough ratings" in:
      val matureGame = testGame(1, "Old Classic").copy(yearPublished = Some(2010), usersRated = Some(1000))
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(List(matureGame))
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val caches = TestCacheProvider(gameCache, vectorStore)
      val service = GameService(client, caches, 50, () => Instant.now())

      service.resolveGameIds(List(GameId(1)))

      vectorStore.load(GameId(1)) should be(defined)

    "not vectorize a mature game with too few ratings" in:
      val lowRated = testGame(2, "Obscure").copy(yearPublished = Some(2010), usersRated = Some(5))
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(List(lowRated))
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val caches = TestCacheProvider(gameCache, vectorStore)
      val service = GameService(client, caches, 50, () => Instant.now())

      service.resolveGameIds(List(GameId(2)))

      vectorStore.load(GameId(2)) shouldBe None

    "vectorize a new game with minimum ratings" in:
      val currentYear = java.time.Year.now(java.time.ZoneOffset.UTC).getValue
      val newGame = testGame(3, "Brand New").copy(yearPublished = Some(currentYear), usersRated = Some(15))
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(List(newGame))
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val caches = TestCacheProvider(gameCache, vectorStore)
      val service = GameService(client, caches, 50, () => Instant.now())

      service.resolveGameIds(List(GameId(3)))

      vectorStore.load(GameId(3)) should be(defined)

    "not vectorize a new game with too few ratings" in:
      val currentYear = java.time.Year.now(java.time.ZoneOffset.UTC).getValue
      val tooNew = testGame(4, "Too New").copy(yearPublished = Some(currentYear), usersRated = Some(3))
      val client = new BggClient:
        def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def fetchHotGames(): Either[Fail, List[GameId]] = Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(List(tooNew))
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
        def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = Right(Nil)

      val caches = TestCacheProvider(gameCache, vectorStore)
      val service = GameService(client, caches, 50, () => Instant.now())

      service.resolveGameIds(List(GameId(4)))

      vectorStore.load(GameId(4)) shouldBe None
