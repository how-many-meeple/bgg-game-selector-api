package bgg.lambda

import bgg.bggapi.{BggClient, GameService}
import bgg.cache.MemoryGameCache
import bgg.domain.*
import bgg.prefetch.{PrefetchStatus, SqlitePrefetchStatusStore}
import bgg.store.SqliteVectorStore
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}
import java.time.Instant

class PrefetchWorkerSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val prefetchDb = "test_worker_prefetch.sqlite"
  private val vectorDb = "test_worker_vectors.sqlite"
  private var prefetchStore: SqlitePrefetchStatusStore = _
  private var vectorStore: SqliteVectorStore = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit
    Files.deleteIfExists(Paths.get(vectorDb)): Unit
    prefetchStore = SqlitePrefetchStatusStore(prefetchDb)
    vectorStore = SqliteVectorStore(vectorDb)

  override def afterEach(): Unit =
    prefetchStore.close()
    vectorStore.close()
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit
    Files.deleteIfExists(Paths.get(vectorDb)): Unit

  private def makeWorker(bggClient: BggClient): PrefetchWorkerLogic =
    val gameCache = MemoryGameCache(ttlSeconds = 3600)
    val gameService = GameService(bggClient, gameCache, vectorStore, 50, () => Instant.now())
    PrefetchWorkerLogic(gameService, prefetchStore)

  "PrefetchWorkerLogic" should:
    "set status to Completed on successful collection fetch" in:
      val client = stubClient(
        collectionResult = Right(List(GameId(1), GameId(2))),
        gamesResult = Right(List(testGame(1, "Catan"), testGame(2, "Pandemic"))),
      )
      val worker = makeWorker(client)

      worker.process(PrefetchMessage("collection", "testuser"))

      prefetchStore.get(SourceType.Collection, "testuser").map(_.status) shouldBe Some(PrefetchStatus.Completed)

    "set status to Completed on successful geeklist fetch" in:
      val client = stubClient(
        geeklistResult = Right(List(GameId(10))),
        gamesResult = Right(List(testGame(10, "Chess"))),
      )
      val worker = makeWorker(client)

      worker.process(PrefetchMessage("geeklist", "456"))

      prefetchStore.get(SourceType.GeeKList, "456").map(_.status) shouldBe Some(PrefetchStatus.Completed)

    "set status to NotFound when user does not exist" in:
      val client = stubClient(
        collectionResult = Left(Fail.BggUserNotFound("nobody")),
      )
      val worker = makeWorker(client)

      worker.process(PrefetchMessage("collection", "nobody"))

      val record = prefetchStore.get(SourceType.Collection, "nobody")
      record.map(_.status) shouldBe Some(PrefetchStatus.NotFound)
      record.flatMap(r => Option(r.reason).filter(_.nonEmpty)) should not be None

    "set status to NotFound when geeklist does not exist" in:
      val client = stubClient(
        geeklistResult = Left(Fail.BggListNotFound("999")),
      )
      val worker = makeWorker(client)

      worker.process(PrefetchMessage("geeklist", "999"))

      val record = prefetchStore.get(SourceType.GeeKList, "999")
      record.map(_.status) shouldBe Some(PrefetchStatus.NotFound)

    "set status to Failed on unexpected error" in:
      val client = stubClient(
        collectionResult = Left(Fail.BggRateLimited("Too many requests")),
      )
      val worker = makeWorker(client)

      worker.process(PrefetchMessage("collection", "ratelimited"))

      prefetchStore.get(SourceType.Collection, "ratelimited").map(_.status) shouldBe Some(PrefetchStatus.Failed)

    "set status to Processing before fetching" in:
      var statusDuringFetch: Option[PrefetchStatus] = None
      val client = new BggClient:
        def fetchCollection(username: String): Either[Fail, List[GameId]] =
          statusDuringFetch = prefetchStore.get(SourceType.Collection, username).map(_.status)
          Right(Nil)
        def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = Right(Nil)
        def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = Right(Nil)
        def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)

      val worker = makeWorker(client)
      worker.process(PrefetchMessage("collection", "observer"))

      statusDuringFetch shouldBe Some(PrefetchStatus.Processing)

    "log error and skip invalid source_type" in:
      val client = stubClient()
      val worker = makeWorker(client)
      noException should be thrownBy worker.process(PrefetchMessage("invalid_type", "x"))

  private def testGame(id: Int, name: String): GameData = GameData(
    id = GameId(id), name = name, yearPublished = Some(2020),
    minPlayers = Some(2), maxPlayers = Some(4), minPlayingTime = Some(30), maxPlayingTime = Some(60),
    playingTime = Some(60), ratingAverage = Some(7.5), ratingAverageWeight = Some(2.5),
    expansion = false, mechanics = List("Hand Management"), categories = List("Fantasy"),
    playerSuggestions = Nil, usersRated = Some(500),
  )

  private def stubClient(
      collectionResult: Either[Fail, List[GameId]] = Right(Nil),
      geeklistResult: Either[Fail, List[GameId]] = Right(Nil),
      gamesResult: Either[Fail, List[GameData]] = Right(Nil),
  ): BggClient = new BggClient:
    def fetchCollection(username: String): Either[Fail, List[GameId]] = collectionResult
    def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = geeklistResult
    def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = gamesResult
    def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
