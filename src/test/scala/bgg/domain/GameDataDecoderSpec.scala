package bgg.domain

import io.circe.parser.decode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GameDataDecoderSpec extends AnyWordSpec with Matchers:

  "GameData decoder" should {

    "decode the standard format" in {
      val json = """{
        "id": 68448,
        "name": "7 Wonders",
        "yearpublished": 2010,
        "minplayers": 2,
        "maxplayers": 7,
        "minplaytime": 30,
        "maxplaytime": 30,
        "playingtime": 30,
        "expansion": false,
        "mechanics": ["Hand Management", "Set Collection"],
        "categories": ["Card Game"],
        "stats": {
          "usersrated": 112000,
          "average": 7.66,
          "averageweight": 2.31
        },
        "suggested_players": {
          "total_votes": 2562,
          "results": {
            "1": {"best_rating": 4, "recommended_rating": 17, "not_recommended_rating": 1586},
            "2": {"best_rating": 118, "recommended_rating": 401, "not_recommended_rating": 1387},
            "3": {"best_rating": 535, "recommended_rating": 1351, "not_recommended_rating": 249}
          }
        }
      }"""

      val result = decode[GameData](json)
      result shouldBe a[Right[_, _]]
      val game = result.toOption.get
      game.id.value shouldBe 68448
      game.name shouldBe "7 Wonders"
      game.yearPublished shouldBe Some(2010)
      game.minPlayers shouldBe Some(2)
      game.maxPlayers shouldBe Some(7)
      game.minPlayingTime shouldBe Some(30)
      game.maxPlayingTime shouldBe Some(30)
      game.playingTime shouldBe Some(30)
      game.ratingAverage shouldBe Some(7.66)
      game.ratingAverageWeight shouldBe Some(2.31)
      game.usersRated shouldBe Some(112000)
      game.expansion shouldBe false
      game.mechanics shouldBe List("Hand Management", "Set Collection")
      game.categories shouldBe List("Card Game")
      game.playerSuggestions should have size 3
      game.playerSuggestions.head.numericPlayerCount shouldBe 1
      game.playerSuggestions.head.best shouldBe 4
      game.playerSuggestions.head.recommended shouldBe 17
      game.playerSuggestions.head.notRecommended shouldBe 1586
      game.playerSuggestions(1).numericPlayerCount shouldBe 2
      game.playerSuggestions(2).numericPlayerCount shouldBe 3
    }

    "decode with missing optional fields" in {
      val json = """{
        "id": 99999,
        "name": "Minimal Game",
        "yearpublished": null,
        "minplayers": null,
        "maxplayers": null,
        "minplaytime": null,
        "maxplaytime": null,
        "playingtime": null,
        "expansion": true,
        "mechanics": [],
        "categories": [],
        "stats": {},
        "suggested_players": {
          "total_votes": 0,
          "results": {}
        }
      }"""

      val result = decode[GameData](json)
      result shouldBe a[Right[_, _]]
      val game = result.toOption.get
      game.id.value shouldBe 99999
      game.name shouldBe "Minimal Game"
      game.yearPublished shouldBe None
      game.minPlayers shouldBe None
      game.ratingAverage shouldBe None
      game.usersRated shouldBe None
      game.expansion shouldBe true
      game.playerSuggestions shouldBe empty
    }

    "decode with missing stats and suggested_players" in {
      val json = """{
        "id": 11111,
        "name": "No Stats Game",
        "yearpublished": 2020,
        "minplayers": 1,
        "maxplayers": 4,
        "minplaytime": 60,
        "maxplaytime": 120,
        "playingtime": 90,
        "expansion": false,
        "mechanics": ["Worker Placement"],
        "categories": ["Economic"]
      }"""

      val result = decode[GameData](json)
      result shouldBe a[Right[_, _]]
      val game = result.toOption.get
      game.ratingAverage shouldBe None
      game.usersRated shouldBe None
      game.playerSuggestions shouldBe empty
    }

    "encode PlayData with all fields" in {
      val play = PlayData(
        playId = 123,
        gameId = GameId(456),
        gameName = "Test Game",
        date = "2024-06-01",
        quantity = 2,
        length = 90,
        players = List(
          PlayPlayer("user1", "User One", Some("15"), win = true),
          PlayPlayer("user2", "User Two", None, win = false)
        )
      )

      val json = PlayData.encoder(play)
      json.hcursor.get[Int]("play_id").toOption shouldBe Some(123)
      json.hcursor.get[Int]("game_id").toOption shouldBe Some(456)
      json.hcursor.get[String]("game_name").toOption shouldBe Some("Test Game")
      json.hcursor.get[String]("date").toOption shouldBe Some("2024-06-01")
      json.hcursor.get[Int]("quantity").toOption shouldBe Some(2)
      json.hcursor.get[Int]("length").toOption shouldBe Some(90)

      val players = json.hcursor.downField("players").as[List[io.circe.Json]].toOption.get
      players should have size 2
      players.head.hcursor.get[String]("username").toOption shouldBe Some("user1")
      players.head.hcursor.get[Boolean]("win").toOption shouldBe Some(true)
      players.head.hcursor.get[String]("score").toOption shouldBe Some("15")
      players(1).hcursor.downField("score").focus.get.isNull shouldBe true
    }

    "round-trip through encode/decode" in {
      val original = GameData(
        id = GameId(42),
        name = "Round Trip",
        yearPublished = Some(2023),
        minPlayers = Some(1),
        maxPlayers = Some(5),
        minPlayingTime = Some(45),
        maxPlayingTime = Some(90),
        playingTime = Some(60),
        ratingAverage = Some(8.1),
        ratingAverageWeight = Some(3.5),
        expansion = false,
        mechanics = List("Deck Building"),
        categories = List("Strategy"),
        playerSuggestions = List(PlayerSuggestion(2, 100, 50, 10)),
        usersRated = Some(5000)
      )

      val json = io.circe.syntax.EncoderOps(original).asJson.noSpaces
      val decoded = decode[GameData](json)
      decoded shouldBe Right(original)
    }
  }
