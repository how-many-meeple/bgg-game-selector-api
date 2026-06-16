package bgg.store

import bgg.domain.GameId
import bgg.vector.GameVector
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}
import java.time.Instant

class SqliteVectorStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val testDb = "test_vectors.sqlite"
  private var store: SqliteVectorStore = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(testDb)): Unit
    store = SqliteVectorStore(testDb)

  override def afterEach(): Unit =
    store.close()
    Files.deleteIfExists(Paths.get(testDb)): Unit

  private def sv(id: Int, name: String = "Test"): StoredVector =
    StoredVector(GameId(id), name, GameVector(Vector(0.1, 0.2, 0.3)), Instant.parse("2026-01-01T00:00:00Z"))

  "SqliteVectorStore" should:
    "return None for unknown game" in:
      store.load(GameId(999)) shouldBe None

    "save and load a vector" in:
      val v = sv(1)
      store.save(v)
      store.load(GameId(1)) shouldBe Some(v)

    "upsert — replace existing vector on second save" in:
      store.save(sv(1, "Original"))
      store.save(sv(1, "Updated"))
      store.load(GameId(1)).map(_.name) shouldBe Some("Updated")

    "loadAll returns all stored vectors" in:
      store.save(sv(1, "Game One"))
      store.save(sv(2, "Game Two"))
      store.loadAll().map(_.gameId.value).toSet shouldBe Set(1, 2)

    "loadAll returns empty list when store is empty" in:
      store.loadAll() shouldBe empty

    "round-trip vector values precisely" in:
      val vec = Vector(0.123456789, -0.987654321, 0.0, 1.0)
      val v = StoredVector(GameId(1), "Precise", GameVector(vec), Instant.now())
      store.save(v)
      store.load(GameId(1)).map(_.vector.values) shouldBe Some(vec)
