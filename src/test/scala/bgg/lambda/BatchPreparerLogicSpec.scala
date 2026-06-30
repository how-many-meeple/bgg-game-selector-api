package bgg.lambda

import bgg.TestFixtures.testGame
import bgg.cache.MemoryGameCache
import bgg.domain.GameId
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

class BatchPreparerLogicSpec extends AnyWordSpec with Matchers:

  "BatchPreparerLogic" should:

    "split IDs into chunks of 300" in:
      val gameCache = MemoryGameCache()
      val logic = BatchPreparerLogic(gameCache)
      val ids = (1 to 650).toList
      val input = s"""{"gameIds":${ids.mkString("[",",","]")},"sourceType":"collection","sourceId":"test"}"""

      val result = parse(logic.handle(input)).toOption.get
      val batches = result.asArray.get
      batches should have size 3
      batches(0).hcursor.downField("gameIds").as[List[Int]].toOption.get should have size 300
      batches(1).hcursor.downField("gameIds").as[List[Int]].toOption.get should have size 300
      batches(2).hcursor.downField("gameIds").as[List[Int]].toOption.get should have size 50

    "filter out already-cached IDs" in:
      val gameCache = MemoryGameCache()
      gameCache.save(testGame(1, "Catan"), Instant.now())
      gameCache.save(testGame(2, "Pandemic"), Instant.now())

      val logic = BatchPreparerLogic(gameCache)
      val input = """{"gameIds":[1,2,3,4,5],"sourceType":"collection","sourceId":"test"}"""

      val result = parse(logic.handle(input)).toOption.get
      val batches = result.asArray.get
      batches should have size 1
      val ids = batches(0).hcursor.downField("gameIds").as[List[Int]].toOption.get
      ids shouldBe List(3, 4, 5)

    "return empty array when all games are cached" in:
      val gameCache = MemoryGameCache()
      gameCache.save(testGame(1, "Catan"), Instant.now())
      gameCache.save(testGame(2, "Pandemic"), Instant.now())

      val logic = BatchPreparerLogic(gameCache)
      val input = """{"gameIds":[1,2],"sourceType":"collection","sourceId":"test"}"""

      val result = parse(logic.handle(input)).toOption.get
      result.asArray.get shouldBe empty

    "preserve sourceType and sourceId in each batch" in:
      val gameCache = MemoryGameCache()
      val logic = BatchPreparerLogic(gameCache)
      val input = """{"gameIds":[1,2,3],"sourceType":"geeklist","sourceId":"12345"}"""

      val result = parse(logic.handle(input)).toOption.get
      val batch = result.asArray.get.head
      batch.hcursor.downField("sourceType").as[String].toOption.get shouldBe "geeklist"
      batch.hcursor.downField("sourceId").as[String].toOption.get shouldBe "12345"
