package bgg.lambda

import bgg.domain.SourceType
import bgg.prefetch.{PrefetchStatus, SqlitePrefetchStatusStore}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}

class StatusUpdaterLogicSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  private val prefetchDb = "test_status_updater.sqlite"
  private var prefetchStore: SqlitePrefetchStatusStore = _

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit
    prefetchStore = SqlitePrefetchStatusStore(prefetchDb)

  override def afterEach(): Unit =
    prefetchStore.close()
    Files.deleteIfExists(Paths.get(prefetchDb)): Unit

  "StatusUpdaterLogic" should:

    "set status to processing" in:
      val logic = StatusUpdaterLogic(prefetchStore)
      val input = """{"sourceType":"collection","sourceId":"alice","status":"processing"}"""

      logic.handle(input) shouldBe """{"ok":true}"""
      prefetchStore.get(SourceType.Collection, "alice").map(_.status) shouldBe Some(PrefetchStatus.Processing)

    "set status to completed" in:
      val logic = StatusUpdaterLogic(prefetchStore)
      val input = """{"sourceType":"collection","sourceId":"bob","status":"completed"}"""

      logic.handle(input) shouldBe """{"ok":true}"""
      prefetchStore.get(SourceType.Collection, "bob").map(_.status) shouldBe Some(PrefetchStatus.Completed)

    "set status to failed with reason" in:
      val logic = StatusUpdaterLogic(prefetchStore)
      val input = """{"sourceType":"geeklist","sourceId":"123","status":"failed","reason":"BGG timeout"}"""

      logic.handle(input) shouldBe """{"ok":true}"""
      val record = prefetchStore.get(SourceType.GeeKList, "123")
      record.map(_.status) shouldBe Some(PrefetchStatus.Failed)
      record.map(_.reason).get should include("BGG timeout")

    "set status to not_found" in:
      val logic = StatusUpdaterLogic(prefetchStore)
      val input = """{"sourceType":"collection","sourceId":"ghost","status":"not_found","reason":"No user found"}"""

      logic.handle(input) shouldBe """{"ok":true}"""
      prefetchStore.get(SourceType.Collection, "ghost").map(_.status) shouldBe Some(PrefetchStatus.NotFound)

    "throw on invalid source type" in:
      val logic = StatusUpdaterLogic(prefetchStore)
      val input = """{"sourceType":"invalid","sourceId":"test","status":"completed"}"""

      intercept[RuntimeException](logic.handle(input))
