package bgg.prefetch

import bgg.domain.SourceType
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}

class PrefetchStatusStoreSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val testDb = "test_prefetch_status.sqlite"
  private var store: SqlitePrefetchStatusStore = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(testDb)): Unit
    store = SqlitePrefetchStatusStore(testDb)

  override def afterEach(): Unit =
    store.close()
    Files.deleteIfExists(Paths.get(testDb)): Unit

  "SqlitePrefetchStatusStore" should:
    "return None for unknown source" in:
      store.get(SourceType.Collection, "unknown") shouldBe None

    "store and retrieve pending status" in:
      store.set(SourceType.Collection, "testuser", PrefetchStatus.Pending)
      val record = store.get(SourceType.Collection, "testuser")
      record.map(_.status) shouldBe Some(PrefetchStatus.Pending)

    "update status from pending to processing" in:
      store.set(SourceType.Collection, "testuser", PrefetchStatus.Pending)
      store.set(SourceType.Collection, "testuser", PrefetchStatus.Processing)
      store.get(SourceType.Collection, "testuser").map(_.status) shouldBe Some(PrefetchStatus.Processing)

    "store failure reason" in:
      store.set(SourceType.GeeKList, "123", PrefetchStatus.Failed, reason = "BGG rate limited")
      store.get(SourceType.GeeKList, "123").map(_.reason) shouldBe Some("BGG rate limited")

    "be queueable when no record exists" in:
      store.isQueueable(SourceType.Collection, "newuser") shouldBe true

    "not be queueable when status is pending" in:
      store.set(SourceType.Collection, "testuser", PrefetchStatus.Pending)
      store.isQueueable(SourceType.Collection, "testuser") shouldBe false

    "not be queueable when status is completed" in:
      store.set(SourceType.Collection, "testuser", PrefetchStatus.Completed)
      store.isQueueable(SourceType.Collection, "testuser") shouldBe false

    "be queueable again after a failed attempt" in:
      store.set(SourceType.Collection, "testuser", PrefetchStatus.Failed)
      store.isQueueable(SourceType.Collection, "testuser") shouldBe true

    "track collection and geeklist separately for same id" in:
      store.set(SourceType.Collection, "shared", PrefetchStatus.Completed)
      store.set(SourceType.GeeKList, "shared", PrefetchStatus.Failed)
      store.get(SourceType.Collection, "shared").map(_.status) shouldBe Some(PrefetchStatus.Completed)
      store.get(SourceType.GeeKList, "shared").map(_.status) shouldBe Some(PrefetchStatus.Failed)
