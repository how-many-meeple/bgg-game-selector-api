package bgg.routes

import bgg.domain.*
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FieldReductionSpec extends AnyWordSpec with Matchers:

  private def game(id: Int, name: String): GameData = GameData(
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

  "FieldReduction" should:

    "return all fields when whitelist is None" in:
      val games = List(game(1, "Catan"))
      val result = FieldReduction(games, None)

      result should have size 1
      val json = result.head
      json.hcursor.get[String]("name").toOption shouldBe Some("Catan")
      json.hcursor.get[Int]("id").toOption shouldBe Some(1)
      json.hcursor.get[Int]("yearPublished").toOption shouldBe Some(2020)
      json.hcursor.get[Int]("minPlayers").toOption shouldBe Some(2)

    "filter to only specified fields" in:
      val games = List(game(1, "Catan"))
      val result = FieldReduction(games, Some(List("name", "id")))

      result should have size 1
      val json = result.head
      json.hcursor.get[String]("name").toOption shouldBe Some("Catan")
      json.hcursor.get[Int]("id").toOption shouldBe Some(1)
      json.hcursor.get[Int]("yearPublished").toOption shouldBe None
      json.hcursor.get[Int]("minPlayers").toOption shouldBe None

    "return empty JSON objects when whitelist is empty" in:
      val games = List(game(1, "Catan"))
      val result = FieldReduction(games, Some(Nil))

      result should have size 1
      val json = result.head
      json.asObject.get.size shouldBe 0

    "ignore fields that do not exist in the JSON" in:
      val games = List(game(1, "Catan"))
      val result = FieldReduction(games, Some(List("name", "nonExistentField", "alsoMissing")))

      result should have size 1
      val json = result.head
      json.asObject.get.size shouldBe 1
      json.hcursor.get[String]("name").toOption shouldBe Some("Catan")

    "apply field reduction to multiple games" in:
      val games = List(game(1, "Catan"), game(2, "Pandemic"))
      val result = FieldReduction(games, Some(List("name", "id")))

      result should have size 2
      result(0).hcursor.get[String]("name").toOption shouldBe Some("Catan")
      result(0).hcursor.get[Int]("id").toOption shouldBe Some(1)
      result(1).hcursor.get[String]("name").toOption shouldBe Some("Pandemic")
      result(1).hcursor.get[Int]("id").toOption shouldBe Some(2)

    "return an empty list when given no games" in:
      val result = FieldReduction(Nil, Some(List("name")))
      result shouldBe empty

    "return an empty list when given no games and no whitelist" in:
      val result = FieldReduction(Nil, None)
      result shouldBe empty

    "preserve nested structures in whitelisted fields" in:
      val games = List(game(1, "Catan"))
      val result = FieldReduction(games, Some(List("mechanics")))

      result should have size 1
      val json = result.head
      val mechanics = json.hcursor.get[List[String]]("mechanics").toOption
      mechanics shouldBe Some(List("Hand Management"))

    "preserve boolean fields correctly" in:
      val games = List(game(1, "Catan"))
      val result = FieldReduction(games, Some(List("expansion")))

      result should have size 1
      val json = result.head
      json.hcursor.get[Boolean]("expansion").toOption shouldBe Some(false)

    "only include fields present in both whitelist and JSON" in:
      val games = List(game(1, "Catan"))
      val result = FieldReduction(games, Some(List("name", "foo", "mechanics", "bar")))

      result should have size 1
      val json = result.head
      json.asObject.get.size shouldBe 2
      json.hcursor.get[String]("name").toOption shouldBe Some("Catan")
      json.hcursor.get[List[String]]("mechanics").toOption shouldBe Some(List("Hand Management"))
