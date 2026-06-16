package bgg.vector

import bgg.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class VectorMathSpec extends AnyWordSpec with Matchers:

  private def gameWith(
      mechanics: List[String] = Nil,
      categories: List[String] = Nil,
      complexity: Double = 0.0,
      playingTime: Int = 0,
      minPlayers: Int = 1,
      maxPlayers: Int = 1,
      rating: Double = 0.0
  ): GameData = GameData(
    id = GameId(1),
    name = "Test",
    yearPublished = None,
    minPlayers = Some(minPlayers),
    maxPlayers = Some(maxPlayers),
    minPlayingTime = None,
    maxPlayingTime = Some(playingTime),
    playingTime = Some(playingTime),
    ratingAverage = Some(rating),
    ratingAverageWeight = Some(complexity),
    expansion = false,
    mechanics = mechanics,
    categories = categories,
    playerSuggestions = Nil,
    usersRated = Some(100)
  )

  "generateGameVector" should:
    "produce a vector of the correct dimension" in:
      val v = VectorMath.generateGameVector(gameWith())
      v.dimensions shouldBe VectorDimensions

    "be L2-normalised (magnitude ≈ 1)" in:
      val v = VectorMath.generateGameVector(gameWith(mechanics = List("Hand Management"), rating = 7.5))
      val magnitude = math.sqrt(v.values.map(x => x * x).sum)
      magnitude shouldBe 1.0 +- 1e-9

    "encode a known mechanic at index 0" in:
      val withMechanic = VectorMath.generateGameVector(gameWith(mechanics = List("Hand Management")))
      val withoutMechanic = VectorMath.generateGameVector(gameWith())
      // After normalisation the absolute value shifts, but the dimension should be non-zero
      withMechanic.values(0) should be > withoutMechanic.values(0)

    "produce a higher weight dimension for heavier games" in:
      val light = VectorMath.generateGameVector(gameWith(mechanics = List("Hand Management"), complexity = 1.0))
      val heavy = VectorMath.generateGameVector(gameWith(mechanics = List("Hand Management"), complexity = 5.0))
      val weightIdx = MechanicVocabulary.size + CategoryVocabulary.size
      heavy.values(weightIdx) should be > light.values(weightIdx)

    "produce a higher playtime dimension for longer games" in:
      val short = VectorMath.generateGameVector(gameWith(mechanics = List("Hand Management"), playingTime = 30))
      val long = VectorMath.generateGameVector(gameWith(mechanics = List("Hand Management"), playingTime = 200))
      val ptIdx = MechanicVocabulary.size + CategoryVocabulary.size + 1
      long.values(ptIdx) should be > short.values(ptIdx)

    "set cooperative flag for cooperative game mechanic" in:
      val coop = VectorMath.generateGameVector(gameWith(mechanics = List("Cooperative Game")))
      val competitive = VectorMath.generateGameVector(gameWith(mechanics = List("Auction/Bidding")))
      coop.values.last should be > 0.0
      competitive.values.last shouldBe 0.0

    "handle unknown mechanics without error" in:
      noException should be thrownBy
        VectorMath.generateGameVector(gameWith(mechanics = List("Totally Fictional Mechanic")))

    "return zero vector for empty game" in:
      val v = VectorMath.generateGameVector(gameWith())
      // All values zero except l2-norm leaves it as zero vector (magnitude 0 → not normalised)
      v.values.sum shouldBe 0.0 +- 1e-9

  "cosineSimilarity" should:
    "return 1.0 for identical unit vectors" in:
      val v = GameVector(Vector(0.5, 0.5, 0.5, 0.5))
      VectorMath.cosineSimilarity(v, v) shouldBe 1.0 +- 1e-9

    "return 0.0 for orthogonal vectors" in:
      val a = GameVector(Vector(1.0, 0.0, 0.0, 0.0))
      val b = GameVector(Vector(0.0, 1.0, 0.0, 0.0))
      VectorMath.cosineSimilarity(a, b) shouldBe 0.0 +- 1e-9

    "return -1.0 for opposite unit vectors" in:
      val a = GameVector(Vector(0.5, 0.5, 0.5, 0.5))
      val b = GameVector(Vector(-0.5, -0.5, -0.5, -0.5))
      VectorMath.cosineSimilarity(a, b) shouldBe -1.0 +- 1e-9

    "return 0.0 for dimension mismatch" in:
      val a = GameVector(Vector(1.0, 0.0))
      val b = GameVector(Vector(1.0, 0.0, 0.0))
      VectorMath.cosineSimilarity(a, b) shouldBe 0.0

    "return 0.0 for zero vector" in:
      val zero = GameVector(Vector(0.0, 0.0, 0.0))
      val unit = GameVector(Vector(1.0, 0.0, 0.0))
      VectorMath.cosineSimilarity(zero, unit) shouldBe 0.0

  "buildTasteVector" should:
    "return zero vector for empty input" in:
      VectorMath.buildTasteVector(Nil).dimensions shouldBe VectorDimensions

    "produce correct dimension from multiple games" in:
      val games = List(gameWith(mechanics = List("Hand Management")), gameWith(mechanics = List("Dice Rolling")))
      VectorMath.buildTasteVector(games).dimensions shouldBe VectorDimensions

    "give heavier taste vector to heavier game collection" in:
      val light = List(
        gameWith(mechanics = List("Hand Management"), complexity = 1.0),
        gameWith(mechanics = List("Hand Management"), complexity = 1.0)
      )
      val heavy = List(
        gameWith(mechanics = List("Hand Management"), complexity = 4.0),
        gameWith(mechanics = List("Hand Management"), complexity = 4.0)
      )
      val weightIdx = MechanicVocabulary.size + CategoryVocabulary.size
      VectorMath.buildTasteVector(heavy).values(weightIdx) should be >
        VectorMath.buildTasteVector(light).values(weightIdx)
