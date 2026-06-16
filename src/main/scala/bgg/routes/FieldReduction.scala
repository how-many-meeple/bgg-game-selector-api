package bgg.routes

import bgg.domain.GameData
import io.circe.Json
import io.circe.syntax.*

object FieldReduction:
  def apply(games: List[GameData], whitelist: Option[List[String]]): List[Json] =
    games.map(g => applyWhitelist(g.asJson, whitelist))

  private def applyWhitelist(json: Json, whitelist: Option[List[String]]): Json =
    whitelist match
      case None => json
      case Some(fields) =>
        json.asObject.fold(json) { obj =>
          Json.fromFields(fields.flatMap(f => obj(f).map(f -> _)))
        }
