package bgg.filter

import bgg.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GameFilterSpec extends AnyWordSpec with Matchers:

  private def game(
      expansion: Boolean = false,
      minPlayers: Option[Int] = Some(2),
      maxPlayers: Option[Int] = Some(4),
      minPlayTime: Option[Int] = Some(30),
      maxPlayTime: Option[Int] = Some(60),
      complexity: Option[Double] = Some(2.5),
      rating: Option[Double] = Some(7.0),
      mechanics: List[String] = Nil,
      suggestions: List[PlayerSuggestion] = Nil,
  ): GameData = GameData(
    id = GameId(1),
    name = "Test Game",
    yearPublished = Some(2020),
    minPlayers = minPlayers,
    maxPlayers = maxPlayers,
    minPlayingTime = minPlayTime,
    maxPlayingTime = maxPlayTime,
    playingTime = maxPlayTime,
    ratingAverage = rating,
    ratingAverageWeight = complexity,
    expansion = expansion,
    mechanics = mechanics,
    categories = Nil,
    playerSuggestions = suggestions,
    usersRated = Some(500),
  )

  "ExpansionsFilter" should:
    "exclude expansions by default" in:
      ExpansionsFilter(includeExpansions = false).excludes(game(expansion = true)) shouldBe true

    "include expansions when flag is set" in:
      ExpansionsFilter(includeExpansions = true).excludes(game(expansion = true)) shouldBe false

    "never exclude base games" in:
      ExpansionsFilter(includeExpansions = false).excludes(game(expansion = false)) shouldBe false

  "PlayersFilter" should:
    "pass when no player count filter set" in:
      PlayersFilter(playerCount = None, useRecommended = false).excludes(game()) shouldBe false

    "exclude when player count below min" in:
      PlayersFilter(playerCount = Some(1), useRecommended = false).excludes(game(minPlayers = Some(2))) shouldBe true

    "exclude when player count above max" in:
      PlayersFilter(playerCount = Some(6), useRecommended = false).excludes(game(maxPlayers = Some(4))) shouldBe true

    "use recommended player counts when available" in:
      val suggestions = List(
        PlayerSuggestion(numericPlayerCount = 3, best = 10, recommended = 5, notRecommended = 1),
        PlayerSuggestion(numericPlayerCount = 4, best = 8, recommended = 6, notRecommended = 2),
      )
      val filter = PlayersFilter(playerCount = Some(2), useRecommended = true)
      filter.excludes(game(suggestions = suggestions)) shouldBe true

  "DurationFilter" should:
    "pass when no duration filter set" in:
      DurationFilter(None, None).excludes(game()) shouldBe false

    "exclude when min duration not met" in:
      DurationFilter(minDuration = Some(90), maxDuration = None)
        .excludes(game(minPlayTime = Some(30), maxPlayTime = Some(60))) shouldBe true

    "exclude when max duration exceeded" in:
      DurationFilter(minDuration = None, maxDuration = Some(45))
        .excludes(game(minPlayTime = Some(30), maxPlayTime = Some(90))) shouldBe true

    "pass when duration is within range" in:
      DurationFilter(minDuration = Some(20), maxDuration = Some(90))
        .excludes(game(minPlayTime = Some(30), maxPlayTime = Some(60))) shouldBe false

  "ComplexityFilter" should:
    "pass when no complexity filter set" in:
      ComplexityFilter(None).excludes(game()) shouldBe false

    "exclude when complexity is too high" in:
      ComplexityFilter(Some(2.0)).excludes(game(complexity = Some(4.0))) shouldBe true

    "exclude when complexity is too low" in:
      ComplexityFilter(Some(4.0)).excludes(game(complexity = Some(2.0))) shouldBe true

    "pass when complexity is within ±1" in:
      ComplexityFilter(Some(3.0)).excludes(game(complexity = Some(3.5))) shouldBe false

    "exclude games with no complexity rating when filter is set" in:
      ComplexityFilter(Some(2.5)).excludes(game(complexity = None)) shouldBe true

  "MechanicsFilter" should:
    "pass when no mechanics filter set" in:
      MechanicsFilter(Nil).excludes(game()) shouldBe false

    "pass when game has required mechanic" in:
      MechanicsFilter(List("Worker Placement")).excludes(game(mechanics = List("Worker Placement", "Dice Rolling"))) shouldBe false

    "exclude when game lacks required mechanic" in:
      MechanicsFilter(List("Worker Placement")).excludes(game(mechanics = List("Dice Rolling"))) shouldBe true

    "strip vendor prefixes before comparison" in:
      MechanicsFilter(List("Worker Placement")).excludes(game(mechanics = List("XZ-99 Worker Placement"))) shouldBe false

  "RatingFilter" should:
    "pass when no rating filter set" in:
      RatingFilter(None).excludes(game()) shouldBe false

    "exclude when rating is too low" in:
      RatingFilter(Some(8.0)).excludes(game(rating = Some(7.0))) shouldBe true

    "pass when rating meets minimum" in:
      RatingFilter(Some(7.0)).excludes(game(rating = Some(7.0))) shouldBe false

    "exclude when game has no rating" in:
      RatingFilter(Some(5.0)).excludes(game(rating = None)) shouldBe true

  "GameFilter chain" should:
    "apply all filters and exclude if any match" in:
      val filters = GameFilters.default.copy(includeExpansions = false)
      val expansion = game(expansion = true)
      GameFilter(List(expansion), filters) shouldBe empty

    "return games that pass all filters" in:
      val filters = GameFilters.default
      val g       = game()
      GameFilter(List(g), filters) shouldBe List(g)
