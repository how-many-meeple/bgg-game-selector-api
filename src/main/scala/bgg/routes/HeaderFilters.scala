package bgg.routes

import bgg.domain.GameFilters
import sttp.model.Header

// Extracts GameFilters from raw request headers (passed as a list from Tapir)
object HeaderFilters:

  def fromHeaders(headers: List[Header]): GameFilters =
    def get(name: String): Option[String] =
      headers.find(_.name.equalsIgnoreCase(name)).map(_.value)

    GameFilters(
      playerCount           = get("Bgg-Filter-Player-Count").flatMap(_.toIntOption),
      useRecommendedPlayers = get("Bgg-Filter-Using-Recommended-Players")
        .map(_.toLowerCase != "false").getOrElse(true),
      minDuration           = get("Bgg-Filter-Min-Duration").flatMap(_.toIntOption),
      maxDuration           = get("Bgg-Filter-Max-Duration").flatMap(_.toIntOption),
      complexity            = get("Bgg-Filter-Complexity").flatMap(_.toDoubleOption),
      minRating             = get("Bgg-Filter-Min-Rating").flatMap(_.toDoubleOption),
      mechanics             = get("Bgg-Filter-Mechanic")
        .map(_.stripPrefix("[").stripSuffix("]").split(",").map(_.trim).toList)
        .getOrElse(Nil),
      includeExpansions     = get("Bgg-Include-Expansions")
        .map(_.toLowerCase == "true").getOrElse(false),
      fieldWhitelist        = get("Bgg-Field-Whitelist")
        .map(_.split(",").map(_.trim).toList),
    )
