package bgg.routes

import bgg.domain.GameFilters
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sttp.model.Header

class HeaderFiltersSpec extends AnyWordSpec with Matchers:

  private def headers(pairs: (String, String)*): List[Header] =
    pairs.map((name, value) => Header(name, value)).toList

  "HeaderFilters.fromHeaders" should:

    "return default filters when no headers are present" in:
      val filters = HeaderFilters.fromHeaders(Nil)
      filters.playerCount shouldBe None
      filters.useRecommendedPlayers shouldBe true
      filters.minDuration shouldBe None
      filters.maxDuration shouldBe None
      filters.complexity shouldBe None
      filters.minRating shouldBe None
      filters.mechanics shouldBe Nil
      filters.includeExpansions shouldBe false
      filters.fieldWhitelist shouldBe None

    "parse Bgg-Filter-Player-Count header" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Player-Count" -> "4"))
      filters.playerCount shouldBe Some(4)

    "return None for invalid Bgg-Filter-Player-Count" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Player-Count" -> "abc"))
      filters.playerCount shouldBe None

    "return None for empty Bgg-Filter-Player-Count" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Player-Count" -> ""))
      filters.playerCount shouldBe None

    "parse Bgg-Filter-Using-Recommended-Players as true by default" in:
      val filters = HeaderFilters.fromHeaders(Nil)
      filters.useRecommendedPlayers shouldBe true

    "parse Bgg-Filter-Using-Recommended-Players when explicitly true" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Using-Recommended-Players" -> "true"))
      filters.useRecommendedPlayers shouldBe true

    "parse Bgg-Filter-Using-Recommended-Players when false" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Using-Recommended-Players" -> "false"))
      filters.useRecommendedPlayers shouldBe false

    "parse Bgg-Filter-Using-Recommended-Players case-insensitively" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Using-Recommended-Players" -> "FALSE"))
      filters.useRecommendedPlayers shouldBe false

    "treat non-false values for Bgg-Filter-Using-Recommended-Players as true" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Using-Recommended-Players" -> "yes"))
      filters.useRecommendedPlayers shouldBe true

    "parse Bgg-Filter-Min-Duration header" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Min-Duration" -> "30"))
      filters.minDuration shouldBe Some(30)

    "return None for invalid Bgg-Filter-Min-Duration" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Min-Duration" -> "not-a-number"))
      filters.minDuration shouldBe None

    "parse Bgg-Filter-Max-Duration header" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Max-Duration" -> "120"))
      filters.maxDuration shouldBe Some(120)

    "return None for invalid Bgg-Filter-Max-Duration" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Max-Duration" -> "3.5"))
      filters.maxDuration shouldBe None

    "parse Bgg-Filter-Complexity header" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Complexity" -> "2.5"))
      filters.complexity shouldBe Some(2.5)

    "return None for invalid Bgg-Filter-Complexity" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Complexity" -> "medium"))
      filters.complexity shouldBe None

    "parse Bgg-Filter-Min-Rating header" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Min-Rating" -> "7.5"))
      filters.minRating shouldBe Some(7.5)

    "return None for invalid Bgg-Filter-Min-Rating" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Min-Rating" -> "high"))
      filters.minRating shouldBe None

    "parse Bgg-Filter-Mechanic header with single mechanic" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Mechanic" -> "Worker Placement"))
      filters.mechanics shouldBe List("Worker Placement")

    "parse Bgg-Filter-Mechanic header with multiple comma-separated mechanics" in:
      val filters =
        HeaderFilters.fromHeaders(headers("Bgg-Filter-Mechanic" -> "Worker Placement, Dice Rolling, Hand Management"))
      filters.mechanics shouldBe List("Worker Placement", "Dice Rolling", "Hand Management")

    "parse Bgg-Filter-Mechanic header with bracket-wrapped list" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Filter-Mechanic" -> "[Worker Placement, Dice Rolling]"))
      filters.mechanics shouldBe List("Worker Placement", "Dice Rolling")

    "return empty list when Bgg-Filter-Mechanic is absent" in:
      val filters = HeaderFilters.fromHeaders(Nil)
      filters.mechanics shouldBe Nil

    "parse Bgg-Include-Expansions as true" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Include-Expansions" -> "true"))
      filters.includeExpansions shouldBe true

    "parse Bgg-Include-Expansions as false when absent" in:
      val filters = HeaderFilters.fromHeaders(Nil)
      filters.includeExpansions shouldBe false

    "parse Bgg-Include-Expansions case-insensitively" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Include-Expansions" -> "TRUE"))
      filters.includeExpansions shouldBe true

    "treat non-true values for Bgg-Include-Expansions as false" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Include-Expansions" -> "yes"))
      filters.includeExpansions shouldBe false

    "parse Bgg-Field-Whitelist header" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Field-Whitelist" -> "name, id, mechanics"))
      filters.fieldWhitelist shouldBe Some(List("name", "id", "mechanics"))

    "return None for fieldWhitelist when header is absent" in:
      val filters = HeaderFilters.fromHeaders(Nil)
      filters.fieldWhitelist shouldBe None

    "parse Bgg-Field-Whitelist with single field" in:
      val filters = HeaderFilters.fromHeaders(headers("Bgg-Field-Whitelist" -> "name"))
      filters.fieldWhitelist shouldBe Some(List("name"))

    "handle case-insensitive header name lookup" in:
      val filters = HeaderFilters.fromHeaders(headers("bgg-filter-player-count" -> "3"))
      filters.playerCount shouldBe Some(3)

    "parse multiple headers simultaneously" in:
      val filters = HeaderFilters.fromHeaders(
        headers(
          "Bgg-Filter-Player-Count" -> "4",
          "Bgg-Filter-Min-Duration" -> "30",
          "Bgg-Filter-Max-Duration" -> "120",
          "Bgg-Filter-Complexity" -> "3.0",
          "Bgg-Filter-Min-Rating" -> "7.0",
          "Bgg-Filter-Mechanic" -> "Worker Placement, Dice Rolling",
          "Bgg-Include-Expansions" -> "true",
          "Bgg-Field-Whitelist" -> "name, id"
        )
      )
      filters.playerCount shouldBe Some(4)
      filters.minDuration shouldBe Some(30)
      filters.maxDuration shouldBe Some(120)
      filters.complexity shouldBe Some(3.0)
      filters.minRating shouldBe Some(7.0)
      filters.mechanics shouldBe List("Worker Placement", "Dice Rolling")
      filters.includeExpansions shouldBe true
      filters.fieldWhitelist shouldBe Some(List("name", "id"))
