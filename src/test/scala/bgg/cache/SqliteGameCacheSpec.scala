package bgg.cache

import bgg.domain.*
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}

class SqliteGameCacheSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val testDb = "test_game_cache.sqlite"
  private var cache: SqliteGameCache = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(testDb)): Unit
    cache = SqliteGameCache(testDb, ttlSeconds = 3600)

  override def afterEach(): Unit =
    cache.close()
    Files.deleteIfExists(Paths.get(testDb)): Unit

  private def testGame(id: Int = 1, name: String = "Test Game"): GameData =
    GameData(
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

  "SqliteGameCache" should:
    "return None for a game not in cache" in:
      cache.load(GameId(999)) shouldBe None

    "save and load a game" in:
      val game = testGame()
      cache.save(game)
      cache.load(GameId(1)) shouldBe Some(game)

    "not overwrite an already-cached game (cache-aside semantics)" in:
      cache.save(testGame(name = "Original"))
      cache.save(testGame(name = "Should Not Overwrite"))
      cache.load(GameId(1)).map(_.name) shouldBe Some("Original")

    "load multiple games independently" in:
      cache.save(testGame(1, "Game One"))
      cache.save(testGame(2, "Game Two"))
      cache.load(GameId(1)).map(_.name) shouldBe Some("Game One")
      cache.load(GameId(2)).map(_.name) shouldBe Some("Game Two")

    "evict expired games" in:
      val shortTtlCache = SqliteGameCache(testDb, ttlSeconds = -1) // already expired
      try
        shortTtlCache.save(testGame())
        shortTtlCache.evictExpired()
        shortTtlCache.load(GameId(1)) shouldBe None
      finally shortTtlCache.close()

    "preserve unexpired games during eviction" in:
      cache.save(testGame())
      cache.evictExpired()
      cache.load(GameId(1)) should not be None

    "round-trip all game fields correctly" in:
      val game = testGame()
      cache.save(game)
      val loaded = cache.load(GameId(1))
      loaded shouldBe Some(game)
