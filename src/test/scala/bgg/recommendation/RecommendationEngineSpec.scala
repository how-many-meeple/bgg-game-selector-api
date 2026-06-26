package bgg.recommendation

import bgg.TestFixtures.testGame
import bgg.cache.MemoryGameCache
import bgg.domain.*
import bgg.store.{SqliteVectorStore, StoredVector}
import bgg.vector.VectorMath
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}
import java.time.Instant

class RecommendationEngineSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val testDb = "test_rec_vectors.sqlite"
  private var vectorStore: SqliteVectorStore = _
  private var gameCache: MemoryGameCache = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(testDb)): Unit
    vectorStore = SqliteVectorStore(testDb)
    gameCache = MemoryGameCache()

  override def afterEach(): Unit =
    vectorStore.close()
    Files.deleteIfExists(Paths.get(testDb)): Unit

  "RecommendationEngine" should:
    "return empty list when vector store is empty" in:
      val taste = VectorMath.buildTasteVector(List(testGame(1, "Input")))
      val result = RecommendationEngine.recommend(
        taste,
        vectorStore,
        gameCache,
        limit = 5,
        excludeIds = Set.empty,
        filters = GameFilters.default
      )
      result shouldBe empty

    "return top N similar games" in:
      val g1 = testGame(1, "Catan")
      val g2 = testGame(2, "Pandemic")
      val g3 = testGame(3, "Chess")
      vectorStore.save(StoredVector(g1.id, g1.name, VectorMath.generateGameVector(g1), Instant.now()))
      vectorStore.save(StoredVector(g2.id, g2.name, VectorMath.generateGameVector(g2), Instant.now()))
      vectorStore.save(StoredVector(g3.id, g3.name, VectorMath.generateGameVector(g3), Instant.now()))

      val taste = VectorMath.buildTasteVector(List(g1))
      val result = RecommendationEngine.recommend(
        taste,
        vectorStore,
        gameCache,
        limit = 2,
        excludeIds = Set(GameId(1)),
        filters = GameFilters.default
      )

      result.size shouldBe 2
      result.map(_.gameId.value).toSet should not contain 1

    "exclude specified game IDs" in:
      val g = testGame(1, "Excluded")
      vectorStore.save(StoredVector(g.id, g.name, VectorMath.generateGameVector(g), Instant.now()))

      val taste = VectorMath.buildTasteVector(List(g))
      val result = RecommendationEngine.recommend(
        taste,
        vectorStore,
        gameCache,
        limit = 5,
        excludeIds = Set(GameId(1)),
        filters = GameFilters.default
      )

      result.map(_.gameId.value) should not contain 1

    "apply filters and skip games not in cache" in:
      val g1 = testGame(1, "Catan").copy(minPlayers = Some(2), maxPlayers = Some(4))
      val g2 = testGame(2, "Solo Game").copy(minPlayers = Some(1), maxPlayers = Some(1))
      val g3 = testGame(3, "Party Game").copy(minPlayers = Some(4), maxPlayers = Some(10))
      gameCache.save(g1, Instant.now())
      gameCache.save(g2, Instant.now())
      vectorStore.save(StoredVector(g1.id, g1.name, VectorMath.generateGameVector(g1), Instant.now()))
      vectorStore.save(StoredVector(g2.id, g2.name, VectorMath.generateGameVector(g2), Instant.now()))
      vectorStore.save(StoredVector(g3.id, g3.name, VectorMath.generateGameVector(g3), Instant.now()))

      val taste = VectorMath.buildTasteVector(List(g1))
      val filters = GameFilters.default.copy(playerCount = Some(2))
      val result = RecommendationEngine.recommend(
        taste,
        vectorStore,
        gameCache,
        limit = 5,
        excludeIds = Set.empty,
        filters = filters
      )

      result.map(_.gameId) should not contain GameId(2)
      result.map(_.gameId) should not contain GameId(3)

    "sort results by similarity score descending" in:
      val g1 = testGame(1, "Game 1")
      val g2 = testGame(2, "Game 2")
      vectorStore.save(StoredVector(g1.id, g1.name, VectorMath.generateGameVector(g1), Instant.now()))
      vectorStore.save(
        StoredVector(
          g2.id,
          g2.name,
          VectorMath.generateGameVector(g2.copy(mechanics = List("Dice Rolling"))),
          Instant.now()
        )
      )

      val taste = VectorMath.buildTasteVector(List(g1))
      val result = RecommendationEngine.recommend(
        taste,
        vectorStore,
        gameCache,
        limit = 5,
        excludeIds = Set.empty,
        filters = GameFilters.default
      )

      result.map(_.similarityScore) shouldBe result.map(_.similarityScore).sorted.reverse
