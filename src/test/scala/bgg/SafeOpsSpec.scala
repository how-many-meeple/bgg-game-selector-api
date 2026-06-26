package bgg

import com.typesafe.scalalogging.Logger
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory

class SafeOpsSpec extends AnyWordSpec with Matchers:

  private given Logger = Logger(LoggerFactory.getLogger("test"))

  "decodeJson" should:
    "return Some on valid JSON" in:
      SafeOps.decodeJson[Int]("42", "test int") shouldBe Some(42)

    "return None on invalid JSON" in:
      SafeOps.decodeJson[Int]("not-json", "test bad") shouldBe None

  "tryAwsCall" should:
    "return Some on success" in:
      SafeOps.tryAwsCall("hello", "test") shouldBe Some("hello")

    "return None on exception" in:
      SafeOps.tryAwsCall(throw RuntimeException("boom"), "test err") shouldBe None

  "trySqlCall" should:
    "execute normally on success" in:
      var ran = false
      SafeOps.trySqlCall({ ran = true }, "test")
      ran shouldBe true

    "catch exception without propagating" in:
      noException should be thrownBy SafeOps.trySqlCall(throw RuntimeException("boom"), "test err")
