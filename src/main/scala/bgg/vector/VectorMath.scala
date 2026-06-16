package bgg.vector

import bgg.domain.GameData

opaque type GameVector = Vector[Double]

object GameVector:
  def apply(values: Vector[Double]): GameVector          = values
  extension (v: GameVector) def values: Vector[Double]   = v
  extension (v: GameVector) def dimensions: Int          = v.size

object VectorMath:
  private val MaxWeight       = 5.0
  private val MaxPlaytime     = 240.0
  private val MinPlayerCount  = 1.0
  private val MaxPlayerCount  = 10.0
  private val MaxRating       = 10.0

  def l2Normalise(v: Vector[Double]): Vector[Double] =
    val magnitude = math.sqrt(v.map(x => x * x).sum)
    if magnitude == 0.0 then v else v.map(_ / magnitude)

  def cosineSimilarity(a: GameVector, b: GameVector): Double =
    if a.dimensions != b.dimensions then 0.0
    else if a.values.forall(_ == 0.0) || b.values.forall(_ == 0.0) then 0.0
    else a.values.zip(b.values).map(_ * _).sum

  private def clamp(value: Double, min: Double, max: Double): Double =
    math.max(min, math.min(max, value))

  private def minMaxNorm(value: Double, min: Double, max: Double): Double =
    if max == min then 0.0
    else (clamp(value, min, max) - min) / (max - min)

  def generateGameVector(game: GameData): GameVector =
    val mechanics  = game.mechanics.toSet
    val categories = game.categories.toSet

    val mechanicBits  = MechanicVocabulary.map(m => if mechanics.contains(m) then 1.0 else 0.0)
    val categoryBits  = CategoryVocabulary.map(c => if categories.contains(c) then 1.0 else 0.0)

    val weight     = minMaxNorm(game.ratingAverageWeight.getOrElse(0.0), 0, MaxWeight)
    val playtime   = minMaxNorm(game.playingTime.orElse(game.maxPlayingTime).getOrElse(0).toDouble, 0, MaxPlaytime)
    val minPlayers = minMaxNorm(game.minPlayers.getOrElse(1).toDouble, MinPlayerCount, MaxPlayerCount)
    val maxPlayers = minMaxNorm(game.maxPlayers.getOrElse(1).toDouble, MinPlayerCount, MaxPlayerCount)
    val rating     = minMaxNorm(game.ratingAverage.getOrElse(0.0), 0, MaxRating)
    val isCoopFlag = if (mechanics & CooperativeMechanics).nonEmpty then 1.0 else 0.0

    val raw = mechanicBits ++ categoryBits ++ Vector(weight, playtime, minPlayers, maxPlayers, rating, isCoopFlag)
    GameVector(l2Normalise(raw))

  def buildTasteVector(games: List[GameData]): GameVector =
    if games.isEmpty then return GameVector(Vector.fill(VectorDimensions)(0.0))

    val gameVectors = games.map(generateGameVector)
    val nGames      = gameVectors.size
    val nMechanics  = MechanicVocabulary.size
    val nCategories = CategoryVocabulary.size
    val multihotEnd = nMechanics + nCategories
    val numericEnd  = multihotEnd + 5

    // Element-wise sum across all game vectors
    val summed = gameVectors.foldLeft(Vector.fill(VectorDimensions)(0.0)) { (acc, vec) =>
      acc.zip(vec.values).map(_ + _)
    }

    // Multi-hot (mechanics + categories): normalise by max frequency
    val maxFreq = summed.take(multihotEnd).maxOption.filter(_ > 0).getOrElse(1.0)
    val normMultihot = summed.take(multihotEnd).map(_ / maxFreq)

    // Numeric features: average across games
    val normNumeric = summed.slice(multihotEnd, numericEnd).map(_ / nGames)

    // Binary (cooperative): threshold at 0.5
    val normBinary = summed.drop(numericEnd).map(v => if v / nGames > 0.5 then 1.0 else 0.0)

    GameVector(l2Normalise(normMultihot ++ normNumeric ++ normBinary))
