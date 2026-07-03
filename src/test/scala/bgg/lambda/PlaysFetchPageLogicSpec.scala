package bgg.lambda

import bgg.TestFixtures.stubClient
import bgg.cache.PlaysCache
import bgg.domain.*
import bgg.prefetch.{PrefetchStatus, SqlitePrefetchStatusStore}
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}
import scala.collection.mutable

class PlaysFetchPageLogicSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val prefetchDb = "test_plays_fetch_prefetch.sqlite"
  private var prefetchStore: SqlitePrefetchStatusStore = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit
    prefetchStore = SqlitePrefetchStatusStore(prefetchDb)

  override def afterEach(): Unit =
    prefetchStore.close()
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit

  private class TestPlaysCache extends PlaysCache:
    val store: mutable.Map[String, List[PlayData]] = mutable.Map.empty
    var touched: List[String] = Nil
    def save(username: String, plays: List[PlayData]): Unit = store(username) = plays
    def load(username: String): Option[List[PlayData]] = store.get(username)
    def isFresh(username: String, maxAgeSeconds: Long): Boolean = false
    def append(username: String, plays: List[PlayData]): Unit =
      store(username) = store.getOrElse(username, Nil) ++ plays
    def maxPlayId(username: String): Option[Int] = store.get(username).flatMap(_.map(_.playId).maxOption)
    def touch(username: String): Unit = touched = touched :+ username

  private val testPlay = PlayData(1, GameId(100), "Catan", "2024-01-01", 1, 60, Nil)

  "PlaysFetchPageLogic" should:

    "fetch first page and return not done" in:
      val client = stubClient(playsResult = Right(List(testPlay)))
      val playsCache = TestPlaysCache()
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"alice","page":1,"sourceType":"collection","sourceId":"alice"}"""
      val result = parse(logic.handle(input)).toOption.get

      result.hcursor.downField("done").as[Boolean].toOption.get shouldBe false
      result.hcursor.downField("nextPage").as[Int].toOption.get shouldBe 2
      result.hcursor.downField("totalSoFar").as[Int].toOption.get shouldBe 1
      playsCache.store("alice") shouldBe List(testPlay)
      prefetchStore.get(SourceType.Plays, "alice").map(_.status) shouldBe Some(PrefetchStatus.Processing)

    "mark complete when empty page returned" in:
      val client = stubClient(playsResult = Right(Nil))
      val playsCache = TestPlaysCache()
      playsCache.save("bob", List(testPlay))
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"bob","page":2,"sourceType":"collection","sourceId":"bob","cachedMaxPlayId":0}"""
      val result = parse(logic.handle(input)).toOption.get

      result.hcursor.downField("done").as[Boolean].toOption.get shouldBe true
      prefetchStore.get(SourceType.Plays, "bob").map(_.status) shouldBe Some(PrefetchStatus.Completed)
      playsCache.touched should contain("bob")

    "throw on first page failure" in:
      val client = stubClient(playsResult = Left(Fail.BggRateLimited("rate limited")))
      val playsCache = TestPlaysCache()
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"charlie","page":1,"sourceType":"collection","sourceId":"charlie"}"""

      intercept[BggRateLimitedException](logic.handle(input))

    "mark complete on error after first page" in:
      val client = stubClient(playsResult = Left(Fail.BggRateLimited("rate limited")))
      val playsCache = TestPlaysCache()
      playsCache.save("dave", List(testPlay))
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"dave","page":3,"sourceType":"collection","sourceId":"dave","cachedMaxPlayId":0}"""
      val result = parse(logic.handle(input)).toOption.get

      result.hcursor.downField("done").as[Boolean].toOption.get shouldBe true
      prefetchStore.get(SourceType.Plays, "dave").map(_.status) shouldBe Some(PrefetchStatus.Completed)

    "append plays to existing cache" in:
      val play2 = PlayData(2, GameId(200), "Pandemic", "2024-02-01", 1, 45, Nil)
      val client = stubClient(playsResult = Right(List(play2)))
      val playsCache = TestPlaysCache()
      playsCache.save("eve", List(testPlay))
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"eve","page":2,"sourceType":"collection","sourceId":"eve","cachedMaxPlayId":0}"""
      logic.handle(input)

      playsCache.store("eve") should have size 2

    "short-circuit on page 1 when plays cache is fresh" in:
      val client = stubClient(playsResult = Right(List(testPlay)))
      val playsCache = new TestPlaysCache:
        override def isFresh(username: String, maxAgeSeconds: Long): Boolean = true
      playsCache.save("frank", List(testPlay))
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"frank","page":1,"sourceType":"collection","sourceId":"frank"}"""
      val result = parse(logic.handle(input)).toOption.get

      result.hcursor.downField("done").as[Boolean].toOption.get shouldBe true
      result.hcursor.downField("totalSoFar").as[Int].toOption.get shouldBe 1
      prefetchStore.get(SourceType.Plays, "frank").map(_.status) shouldBe Some(PrefetchStatus.Completed)
      playsCache.store("frank") shouldBe List(testPlay)

    "stop fetching when hitting already-cached play ID" in:
      val existingPlay = PlayData(50, GameId(100), "Catan", "2024-01-01", 1, 60, Nil)
      val newPlay = PlayData(60, GameId(200), "Pandemic", "2024-02-01", 1, 45, Nil)
      val oldPlay = PlayData(40, GameId(300), "Ticket", "2023-12-01", 1, 90, Nil)
      val client = stubClient(playsResult = Right(List(newPlay, oldPlay)))
      val playsCache = TestPlaysCache()
      playsCache.save("grace", List(existingPlay))
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"grace","page":2,"sourceType":"collection","sourceId":"grace","cachedMaxPlayId":50}"""
      val result = parse(logic.handle(input)).toOption.get

      result.hcursor.downField("done").as[Boolean].toOption.get shouldBe true
      playsCache.store("grace") should contain(newPlay)
      playsCache.store("grace") should not contain oldPlay
      playsCache.touched should contain("grace")

    "resolve cachedMaxPlayId on first page from existing cache" in:
      val existingPlay = PlayData(100, GameId(100), "Catan", "2024-01-01", 1, 60, Nil)
      val newPlay = PlayData(200, GameId(200), "Pandemic", "2024-02-01", 1, 45, Nil)
      val client = stubClient(playsResult = Right(List(newPlay)))
      val playsCache = TestPlaysCache()
      playsCache.save("heidi", List(existingPlay))
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"heidi","page":1,"sourceType":"collection","sourceId":"heidi"}"""
      val result = parse(logic.handle(input)).toOption.get

      result.hcursor.downField("done").as[Boolean].toOption.get shouldBe false
      result.hcursor.downField("cachedMaxPlayId").as[Int].toOption.get shouldBe 100
      playsCache.store("heidi") should contain(newPlay)
      playsCache.store("heidi") should contain(existingPlay)

    "first-time fetch with no existing cache sets cachedMaxPlayId to 0" in:
      val newPlay = PlayData(10, GameId(100), "Catan", "2024-01-01", 1, 60, Nil)
      val client = stubClient(playsResult = Right(List(newPlay)))
      val playsCache = TestPlaysCache()
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"ivan","page":1,"sourceType":"collection","sourceId":"ivan"}"""
      val result = parse(logic.handle(input)).toOption.get

      result.hcursor.downField("done").as[Boolean].toOption.get shouldBe false
      result.hcursor.downField("cachedMaxPlayId").as[Int].toOption.get shouldBe 0
      playsCache.store("ivan") shouldBe List(newPlay)

    "cachedMaxPlayId of 0 does not filter any plays" in:
      val play1 = PlayData(1, GameId(100), "Catan", "2024-01-01", 1, 60, Nil)
      val play2 = PlayData(2, GameId(200), "Pandemic", "2024-02-01", 1, 45, Nil)
      val client = stubClient(playsResult = Right(List(play2, play1)))
      val playsCache = TestPlaysCache()
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"judy","page":2,"sourceType":"collection","sourceId":"judy","cachedMaxPlayId":0}"""
      val result = parse(logic.handle(input)).toOption.get

      result.hcursor.downField("done").as[Boolean].toOption.get shouldBe false
      playsCache.store("judy") should contain allOf (play1, play2)

    "does not append empty list when all plays on page are already cached" in:
      val existingPlay = PlayData(100, GameId(100), "Catan", "2024-01-01", 1, 60, Nil)
      val oldPlay1 = PlayData(50, GameId(200), "Pandemic", "2024-02-01", 1, 45, Nil)
      val oldPlay2 = PlayData(30, GameId(300), "Ticket", "2023-12-01", 1, 90, Nil)
      val client = stubClient(playsResult = Right(List(oldPlay1, oldPlay2)))
      val playsCache = TestPlaysCache()
      playsCache.save("kate", List(existingPlay))
      val logic = PlaysFetchPageLogic(client, playsCache, prefetchStore)

      val input = """{"username":"kate","page":2,"sourceType":"collection","sourceId":"kate","cachedMaxPlayId":100}"""
      val result = parse(logic.handle(input)).toOption.get

      result.hcursor.downField("done").as[Boolean].toOption.get shouldBe true
      playsCache.store("kate") shouldBe List(existingPlay)
