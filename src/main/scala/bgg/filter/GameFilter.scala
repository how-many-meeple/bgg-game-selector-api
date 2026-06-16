package bgg.filter

import bgg.domain.{GameData, GameFilters, PlayerSuggestion}

// Pure predicate — returns true if the game should be EXCLUDED
trait GameFilter:
  def excludes(game: GameData): Boolean

// Chain of filters; short-circuits on first exclusion
private[filter] class FilterChain(filters: List[GameFilter]) extends GameFilter:
  def excludes(game: GameData): Boolean = filters.exists(_.excludes(game))

object GameFilter:
  def fromFilters(f: GameFilters): GameFilter =
    FilterChain(List(
      ExpansionsFilter(f.includeExpansions),
      PlayersFilter(f.playerCount, f.useRecommendedPlayers),
      DurationFilter(f.minDuration, f.maxDuration),
      ComplexityFilter(f.complexity),
      MechanicsFilter(f.mechanics),
      RatingFilter(f.minRating),
    ))

  def apply(games: List[GameData], f: GameFilters): List[GameData] =
    val filter = fromFilters(f)
    games.filterNot(filter.excludes)

private[filter] case class ExpansionsFilter(includeExpansions: Boolean) extends GameFilter:
  def excludes(game: GameData): Boolean =
    game.expansion && !includeExpansions

private[filter] case class PlayersFilter(
    playerCount: Option[Int],
    useRecommended: Boolean,
) extends GameFilter:
  private val DefaultMinPlayers = 1
  private val DefaultMaxPlayers = 99

  def excludes(game: GameData): Boolean =
    playerCount match
      case None    => false
      case Some(n) =>
        val (minP, maxP) =
          if useRecommended then
            recommendedRange(game.playerSuggestions).getOrElse(
              (game.minPlayers.getOrElse(DefaultMinPlayers), game.maxPlayers.getOrElse(DefaultMaxPlayers))
            )
          else (game.minPlayers.getOrElse(DefaultMinPlayers), game.maxPlayers.getOrElse(DefaultMaxPlayers))
        n < minP || n > maxP

  private def recommendedRange(suggestions: List[PlayerSuggestion]): Option[(Int, Int)] =
    val supported = suggestions
      .filter(s => s.best > s.notRecommended || s.recommended > s.notRecommended)
      .map(_.numericPlayerCount)
    Option.when(supported.nonEmpty)(supported.min, supported.max)

private[filter] case class DurationFilter(
    minDuration: Option[Int],
    maxDuration: Option[Int],
) extends GameFilter:
  def excludes(game: GameData): Boolean =
    val min = game.minPlayingTime.getOrElse(0)
    val max = game.maxPlayingTime.getOrElse(0)
    minDuration.exists(min < _) || maxDuration.exists(max > _)

private[filter] case class ComplexityFilter(complexity: Option[Double]) extends GameFilter:
  private val ComplexityTolerance = 1.0

  def excludes(game: GameData): Boolean =
    complexity match
      case None    => false
      case Some(c) =>
        game.ratingAverageWeight match
          case None    => true
          case Some(w) => math.abs(w - c) > ComplexityTolerance

private[filter] case class MechanicsFilter(requiredMechanics: List[String]) extends GameFilter:
  def excludes(game: GameData): Boolean =
    if requiredMechanics.isEmpty then false
    else
      val gameMechanics   = game.mechanics.map(normalise).toSet
      val requestedNormal = requiredMechanics.map(normalise).toSet
      (gameMechanics & requestedNormal).isEmpty

  // Strip optional "XX-123 " vendor prefixes that BGG sometimes includes
  private def normalise(m: String): String =
    m.replaceAll("""^\w{2,3}-[\w\d]{2,3}\s+""", "").trim

private[filter] case class RatingFilter(minRating: Option[Double]) extends GameFilter:
  def excludes(game: GameData): Boolean =
    minRating match
      case None    => false
      case Some(r) => game.ratingAverage.forall(_ < r)
