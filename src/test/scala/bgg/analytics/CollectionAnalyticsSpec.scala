package bgg.analytics

import bgg.domain.{CollectionResult, GameData, GameId, PlayerSuggestion}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CollectionAnalyticsSpec extends AnyWordSpec with Matchers:

  private def game(
      id: Int,
      name: String = "Game",
      weight: Option[Double] = Some(2.5),
      rating: Option[Double] = Some(7.0),
      playingTime: Option[Int] = Some(60),
      minPlayers: Option[Int] = Some(2),
      maxPlayers: Option[Int] = Some(4),
      expansion: Boolean = false,
      mechanics: List[String] = Nil,
      categories: List[String] = Nil,
      suggestions: List[PlayerSuggestion] = Nil
  ): GameData =
    GameData(
      id = GameId(id),
      name = name,
      yearPublished = Some(2020),
      minPlayers = minPlayers,
      maxPlayers = maxPlayers,
      minPlayingTime = playingTime,
      maxPlayingTime = playingTime,
      playingTime = playingTime,
      ratingAverage = rating,
      ratingAverageWeight = weight,
      expansion = expansion,
      mechanics = mechanics,
      categories = categories,
      playerSuggestions = suggestions,
      usersRated = Some(500)
    )

  private def result(games: List[GameData], dates: Map[GameId, String] = Map.empty): CollectionResult =
    CollectionResult(games, dates)

  private def bucket(buckets: List[Bucket], label: String): Int =
    buckets.find(_.label == label).map(_.count).getOrElse(fail(s"no bucket '$label'"))

  "summary" should:
    "count games, base games and expansions" in:
      val r = result(List(game(1), game(2), game(3, expansion = true)))
      val s = CollectionAnalytics.analyse(r).summary
      s.totalGames shouldBe 3
      s.baseGames shouldBe 2
      s.expansions shouldBe 1

    "compute average and median rating" in:
      val r = result(List(game(1, rating = Some(6.0)), game(2, rating = Some(7.0)), game(3, rating = Some(9.0))))
      val s = CollectionAnalytics.analyse(r).summary
      s.averageRating shouldBe Some(7.33)
      s.medianRating shouldBe Some(7.0)

    "average even-sized rating sets across the two middle values" in:
      val r = result(List(game(1, rating = Some(6.0)), game(2, rating = Some(8.0))))
      CollectionAnalytics.analyse(r).summary.medianRating shouldBe Some(7.0)

    "ignore games missing a rating or weight" in:
      val r = result(List(game(1, rating = None, weight = None), game(2, rating = Some(8.0), weight = Some(3.0))))
      val s = CollectionAnalytics.analyse(r).summary
      s.averageRating shouldBe Some(8.0)
      s.averageWeight shouldBe Some(3.0)

    // Right — empty collection is the degenerate boundary
    "return None statistics for an empty collection" in:
      val s = CollectionAnalytics.analyse(result(Nil)).summary
      s.totalGames shouldBe 0
      s.averageRating shouldBe None
      s.medianRating shouldBe None
      s.averageWeight shouldBe None

  "complexity distribution" should:
    "bucket weights on BGG bands" in:
      val games = List(
        game(1, weight = Some(1.5)), // light
        game(2, weight = Some(2.3)), // medium-light
        game(3, weight = Some(2.7)), // medium
        game(4, weight = Some(3.5)), // medium-heavy
        game(5, weight = Some(4.2)) // heavy
      )
      val dist = CollectionAnalytics.analyse(result(games)).complexityDistribution
      bucket(dist, "light [0, 2.0)") shouldBe 1
      bucket(dist, "medium-light [2.0, 2.5)") shouldBe 1
      bucket(dist, "medium [2.5, 3.0)") shouldBe 1
      bucket(dist, "medium-heavy [3.0, 4.0)") shouldBe 1
      bucket(dist, "heavy [4.0+)") shouldBe 1

    // Boundary — values on the band edges land in the upper band
    "place boundary weights in the upper band" in:
      val games = List(game(1, weight = Some(2.0)), game(2, weight = Some(2.5)), game(3, weight = Some(3.0)))
      val dist = CollectionAnalytics.analyse(result(games)).complexityDistribution
      bucket(dist, "light [0, 2.0)") shouldBe 0
      bucket(dist, "medium-light [2.0, 2.5)") shouldBe 1
      bucket(dist, "medium [2.5, 3.0)") shouldBe 1
      bucket(dist, "medium-heavy [3.0, 4.0)") shouldBe 1

  "playtime distribution" should:
    "bucket play times and ignore zero/absent times" in:
      val games = List(
        game(1, playingTime = Some(20)),
        game(2, playingTime = Some(45)),
        game(3, playingTime = Some(75)),
        game(4, playingTime = Some(100)),
        game(5, playingTime = Some(180)),
        game(6, playingTime = Some(0)),
        game(7, playingTime = None)
      )
      val dist = CollectionAnalytics.analyse(result(games)).playtimeDistribution
      bucket(dist, "filler [0, 30)") shouldBe 1
      bucket(dist, "short [30, 60)") shouldBe 1
      bucket(dist, "medium [60, 90)") shouldBe 1
      bucket(dist, "long [90, 120)") shouldBe 1
      bucket(dist, "epic [120+)") shouldBe 1
      dist.map(_.count).sum shouldBe 5

  "player-count coverage" should:
    "count community best-or-recommended and box-supported separately" in:
      val suggestions = List(
        PlayerSuggestion(2, best = 10, recommended = 5, notRecommended = 1),
        PlayerSuggestion(3, best = 1, recommended = 2, notRecommended = 20)
      )
      val games = List(game(1, minPlayers = Some(2), maxPlayers = Some(4), suggestions = suggestions))
      val coverage = CollectionAnalytics.analyse(result(games)).playerCountCoverage

      val two = coverage.find(_.playerCount == 2).get
      two.bestOrRecommended shouldBe 1
      two.supported shouldBe 1

      // 3 is within the box range but the community does not recommend it
      val three = coverage.find(_.playerCount == 3).get
      three.bestOrRecommended shouldBe 0
      three.supported shouldBe 1

      // 5 is outside the box range entirely
      val five = coverage.find(_.playerCount == 5).get
      five.supported shouldBe 0

    "cover player counts 1 through 8" in:
      val coverage = CollectionAnalytics.analyse(result(List(game(1)))).playerCountCoverage
      coverage.map(_.playerCount) shouldBe (1 to 8).toList

  "top mechanics and categories" should:
    "rank by frequency, breaking ties alphabetically" in:
      val games = List(
        game(1, mechanics = List("Worker Placement", "Hand Management")),
        game(2, mechanics = List("Worker Placement", "Deck Building")),
        game(3, mechanics = List("Worker Placement"))
      )
      val top = CollectionAnalytics.analyse(result(games)).topMechanics
      top.head shouldBe LabelCount("Worker Placement", 3)
      // Deck Building and Hand Management both appear once — alphabetical order
      top.map(_.name) shouldBe List("Worker Placement", "Deck Building", "Hand Management")

  "acquisitions by year" should:
    "count games by the year of their lastModified date, ascending" in:
      val games = List(game(1), game(2), game(3))
      val dates = Map(
        GameId(1) -> "2019-05-01",
        GameId(2) -> "2021-03-15",
        GameId(3) -> "2021-11-30"
      )
      val byYear = CollectionAnalytics.analyse(result(games, dates)).acquisitionsByYear
      byYear shouldBe List(LabelCount("2019", 1), LabelCount("2021", 2))

    "be empty when no dates are present" in:
      CollectionAnalytics.analyse(result(List(game(1)))).acquisitionsByYear shouldBe Nil
