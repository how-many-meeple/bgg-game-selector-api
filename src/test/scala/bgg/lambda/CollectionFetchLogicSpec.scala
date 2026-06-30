package bgg.lambda

import bgg.TestFixtures.{stubClient, testGame}
import bgg.bggapi.BggClient
import bgg.domain.*
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CollectionFetchLogicSpec extends AnyWordSpec with Matchers:

  "CollectionFetchLogic" should:

    "return game IDs for a valid collection" in:
      val client = stubClient(collectionResult = Right(List(GameId(1), GameId(2), GameId(3))))
      val logic = CollectionFetchLogic(client, retries = 6)
      val input = """{"sourceType":"collection","sourceId":"testuser"}"""

      val result = parse(logic.handle(input)).toOption.get
      val ids = result.hcursor.downField("gameIds").as[List[Int]].toOption.get
      ids shouldBe List(1, 2, 3)
      result.hcursor.downField("sourceType").as[String].toOption.get shouldBe "collection"
      result.hcursor.downField("sourceId").as[String].toOption.get shouldBe "testuser"

    "return game IDs for a geeklist" in:
      val client = stubClient(geeklistResult = Right(List(GameId(10), GameId(20))))
      val logic = CollectionFetchLogic(client, retries = 6)
      val input = """{"sourceType":"geeklist","sourceId":"12345"}"""

      val result = parse(logic.handle(input)).toOption.get
      val ids = result.hcursor.downField("gameIds").as[List[Int]].toOption.get
      ids shouldBe List(10, 20)

    "return game IDs for hot games" in:
      val client = stubClient(hotResult = Right(List(GameId(100))))
      val logic = CollectionFetchLogic(client, retries = 6)
      val input = """{"sourceType":"hot","sourceId":"trending"}"""

      val result = parse(logic.handle(input)).toOption.get
      val ids = result.hcursor.downField("gameIds").as[List[Int]].toOption.get
      ids shouldBe List(100)

    "throw BggRateLimitedException on rate limit" in:
      val client = stubClient(collectionResult = Left(Fail.BggRateLimited("Too many")))
      val logic = CollectionFetchLogic(client, retries = 6)
      val input = """{"sourceType":"collection","sourceId":"testuser"}"""

      val ex = intercept[BggRateLimitedException](logic.handle(input))
      ex.getMessage should include("Too many")

    "throw BggUserNotFoundException when user not found" in:
      val client = stubClient(collectionResult = Left(Fail.BggUserNotFound("ghost")))
      val logic = CollectionFetchLogic(client, retries = 6)
      val input = """{"sourceType":"collection","sourceId":"ghost"}"""

      val ex = intercept[BggUserNotFoundException](logic.handle(input))
      ex.getMessage should include("ghost")

    "throw BggListNotFoundException when list not found" in:
      val client = stubClient(geeklistResult = Left(Fail.BggListNotFound("99999")))
      val logic = CollectionFetchLogic(client, retries = 6)
      val input = """{"sourceType":"geeklist","sourceId":"99999"}"""

      val ex = intercept[BggListNotFoundException](logic.handle(input))
      ex.getMessage should include("99999")

    "throw on invalid source type" in:
      val client = stubClient()
      val logic = CollectionFetchLogic(client, retries = 6)
      val input = """{"sourceType":"invalid","sourceId":"test"}"""

      intercept[RuntimeException](logic.handle(input))
