package bgg.routes

import bgg.domain.GameFilters
import sttp.model.Header

object HeaderFilters:

  def fromHeaders(headers: List[Header]): GameFilters =
    val h = Headers(headers)
    GameFilters(
      playerCount = h.int("Bgg-Filter-Player-Count"),
      useRecommendedPlayers = h.bool("Bgg-Filter-Using-Recommended-Players", default = true),
      minDuration = h.int("Bgg-Filter-Min-Duration"),
      maxDuration = h.int("Bgg-Filter-Max-Duration"),
      complexity = h.double("Bgg-Filter-Complexity"),
      minRating = h.double("Bgg-Filter-Min-Rating"),
      mechanics = h.list("Bgg-Filter-Mechanic"),
      includeExpansions = h.bool("Bgg-Include-Expansions", default = false),
      fieldWhitelist = h.optList("Bgg-Field-Whitelist")
    )

  private class Headers(headers: List[Header]):
    private def get(name: String): Option[String] =
      headers.find(_.name.equalsIgnoreCase(name)).map(_.value)

    def int(name: String): Option[Int] = get(name).flatMap(_.toIntOption)
    def double(name: String): Option[Double] = get(name).flatMap(_.toDoubleOption)

    def bool(name: String, default: Boolean): Boolean =
      get(name).map(v => if default then v.toLowerCase != "false" else v.toLowerCase == "true").getOrElse(default)

    def list(name: String): List[String] =
      get(name).map(_.stripPrefix("[").stripSuffix("]").split(",").map(_.trim).toList).getOrElse(Nil)

    def optList(name: String): Option[List[String]] =
      get(name).map(_.split(",").map(_.trim).toList)
