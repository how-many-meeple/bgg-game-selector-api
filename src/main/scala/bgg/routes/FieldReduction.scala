package bgg.routes

import bgg.domain.GameData
import io.circe.Json
import io.circe.syntax.*

object FieldReduction:
  def apply(games: List[GameData], whitelist: Option[List[String]]): List[Json] =
    games.map(g => applyWhitelist(g.asJson, whitelist))

  // Applies the whitelist to JSON that has already been built and enriched with
  // extra fields (e.g. per-user collection metadata) not present on GameData.
  def filterFields(games: List[Json], whitelist: Option[List[String]]): List[Json] =
    games.map(applyWhitelist(_, whitelist))

  private def applyWhitelist(json: Json, whitelist: Option[List[String]]): Json =
    whitelist match
      case None => json
      case Some(fields) =>
        json.asObject.fold(json) { obj =>
          Json.fromFields(fields.flatMap(f => obj(f).map(f -> _)))
        }
