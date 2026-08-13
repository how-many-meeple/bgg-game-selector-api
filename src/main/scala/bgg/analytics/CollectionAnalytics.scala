package bgg.analytics

import bgg.domain.{CollectionResult, GameData, PlayerSuggestion}
import io.circe.derivation.{Configuration, ConfiguredCodec}

// Pure folds over an already-resolved collection — no extra data source, no BGG calls.

given Configuration = Configuration.default.withSnakeCaseMemberNames

case class Bucket(label: String, count: Int) derives ConfiguredCodec

case class LabelCount(name: String, count: Int) derives ConfiguredCodec

case class PlayerCountCoverage(playerCount: Int, bestOrRecommended: Int, supported: Int) derives ConfiguredCodec

case class Summary(
    totalGames: Int,
    baseGames: Int,
    expansions: Int,
    averageRating: Option[Double],
    medianRating: Option[Double],
    averageWeight: Option[Double]
) derives ConfiguredCodec

case class CollectionAnalyticsResult(
    summary: Summary,
    complexityDistribution: List[Bucket],
    playtimeDistribution: List[Bucket],
    playerCountCoverage: List[PlayerCountCoverage],
    topMechanics: List[LabelCount],
    topCategories: List[LabelCount],
    acquisitionsByYear: List[LabelCount]
) derives ConfiguredCodec

object CollectionAnalytics:

  private val TopN = 15
  private val MaxPlayerCount = 8
  private val RoundingScale = 100.0

  def analyse(result: CollectionResult): CollectionAnalyticsResult =
    val games = result.games
    CollectionAnalyticsResult(
      summary = summary(games),
      complexityDistribution = complexityDistribution(games),
      playtimeDistribution = playtimeDistribution(games),
      playerCountCoverage = playerCountCoverage(games),
      topMechanics = topLabels(games.flatMap(_.mechanics)),
      topCategories = topLabels(games.flatMap(_.categories)),
      acquisitionsByYear = acquisitionsByYear(result)
    )

  private def round(value: Double): Double = math.round(value * RoundingScale) / RoundingScale

  private def average(values: List[Double]): Option[Double] =
    Option.when(values.nonEmpty)(round(values.sum / values.size))

  private def median(values: List[Double]): Option[Double] =
    if values.isEmpty then None
    else
      val sorted = values.sorted
      val mid = sorted.size / 2
      val m = if sorted.size % 2 == 1 then sorted(mid) else (sorted(mid - 1) + sorted(mid)) / 2.0
      Some(round(m))

  private def summary(games: List[GameData]): Summary =
    val (expansions, baseGames) = games.partition(_.expansion)
    val ratings = games.flatMap(_.ratingAverage)
    Summary(
      totalGames = games.size,
      baseGames = baseGames.size,
      expansions = expansions.size,
      averageRating = average(ratings),
      medianRating = median(ratings),
      averageWeight = average(games.flatMap(_.ratingAverageWeight))
    )

  private def complexityDistribution(games: List[GameData]): List[Bucket] =
    val weights = games.flatMap(_.ratingAverageWeight)
    List(
      Bucket("light [0, 2.0)", weights.count(_ < 2.0)),
      Bucket("medium-light [2.0, 2.5)", weights.count(w => w >= 2.0 && w < 2.5)),
      Bucket("medium [2.5, 3.0)", weights.count(w => w >= 2.5 && w < 3.0)),
      Bucket("medium-heavy [3.0, 4.0)", weights.count(w => w >= 3.0 && w < 4.0)),
      Bucket("heavy [4.0+)", weights.count(_ >= 4.0))
    )

  private def playtimeDistribution(games: List[GameData]): List[Bucket] =
    val times = games.flatMap(g => g.playingTime.orElse(g.maxPlayingTime)).filter(_ > 0)
    List(
      Bucket("filler [0, 30)", times.count(_ < 30)),
      Bucket("short [30, 60)", times.count(t => t >= 30 && t < 60)),
      Bucket("medium [60, 90)", times.count(t => t >= 60 && t < 90)),
      Bucket("long [90, 120)", times.count(t => t >= 90 && t < 120)),
      Bucket("epic [120+)", times.count(_ >= 120))
    )

  // Per player count: games rated best-or-recommended vs merely box-supported.
  private def playerCountCoverage(games: List[GameData]): List[PlayerCountCoverage] =
    (1 to MaxPlayerCount).toList.map { n =>
      val bestOrRec = games.count(g => recommendsPlayerCount(g.playerSuggestions, n))
      val supported = games.count(g => supportsPlayerCount(g, n))
      PlayerCountCoverage(n, bestOrRec, supported)
    }

  private def recommendsPlayerCount(suggestions: List[PlayerSuggestion], n: Int): Boolean =
    suggestions.exists(s =>
      s.numericPlayerCount == n && (s.best > s.notRecommended || s.recommended > s.notRecommended)
    )

  private def supportsPlayerCount(game: GameData, n: Int): Boolean =
    val min = game.minPlayers.getOrElse(1)
    val max = game.maxPlayers.getOrElse(min)
    n >= min && n <= max

  private def topLabels(labels: List[String]): List[LabelCount] =
    labels
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toList
      .sortBy { case (name, count) => (-count, name) }
      .take(TopN)
      .map((name, count) => LabelCount(name, count))

  // lastModified dates look like "2018-02-24"; take the leading year.
  private def acquisitionsByYear(result: CollectionResult): List[LabelCount] =
    result.lastModifiedByGame.values
      .flatMap(date => date.take(4).toIntOption)
      .toList
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toList
      .sortBy((year, _) => year)
      .map((year, count) => LabelCount(year.toString, count))
