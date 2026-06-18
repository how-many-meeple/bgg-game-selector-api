package bgg.domain

import io.circe.{Codec, Decoder, Encoder}

// Opaque types for domain identifiers — prevents mixing up raw Int/String values
opaque type GameId = Int
object GameId:
  def apply(value: Int): GameId = value
  def unapply(id: GameId): Some[Int] = Some(id)
  extension (id: GameId)
    def value: Int = id
    def asString: String = id.toString
  given Codec[GameId] = Codec.from(Decoder.decodeInt.map(GameId(_)), Encoder.encodeInt.contramap(_.value))

opaque type GeekListId = String
object GeekListId:
  def apply(value: String): GeekListId = value
  extension (id: GeekListId) def value: String = id

opaque type Username = String
object Username:
  def apply(value: String): Username = value
  extension (u: Username) def value: String = u

enum SourceType:
  case Collection, GeeKList, Hot
  def toPathSegment: String = this match
    case Collection => "collection"
    case GeeKList   => "geeklist"
    case Hot        => "hot"

object SourceType:
  def fromString(s: String): Either[String, SourceType] = s.toLowerCase match
    case "collection" => Right(Collection)
    case "geeklist"   => Right(GeeKList)
    case "hot"        => Right(Hot)
    case other        => Left(s"Invalid source_type '$other'. Must be one of: collection, geeklist, hot")

// Core game data model — mirrors the Python BoardGame.data() dict shape for cache compatibility
case class GameData(
    id: GameId,
    name: String,
    yearPublished: Option[Int],
    minPlayers: Option[Int],
    maxPlayers: Option[Int],
    minPlayingTime: Option[Int],
    maxPlayingTime: Option[Int],
    playingTime: Option[Int],
    ratingAverage: Option[Double],
    ratingAverageWeight: Option[Double],
    expansion: Boolean,
    mechanics: List[String],
    categories: List[String],
    playerSuggestions: List[PlayerSuggestion],
    usersRated: Option[Int],
    image: Option[String] = None,
    thumbnail: Option[String] = None
):
  def toJson: io.circe.Json = GameData.encoder(this)

object GameData:
  given encoder: Encoder[GameData] = Encoder.instance { g =>
    import io.circe.Json
    val suggestions = Json.obj(
      "results" -> Json.fromFields(g.playerSuggestions.map { ps =>
        ps.numericPlayerCount.toString -> Json.obj(
          "best_rating" -> Json.fromInt(ps.best),
          "recommended_rating" -> Json.fromInt(ps.recommended),
          "not_recommended_rating" -> Json.fromInt(ps.notRecommended)
        )
      }),
      "total_votes" -> Json.fromInt(g.playerSuggestions.map(ps => ps.best + ps.recommended + ps.notRecommended).sum)
    )
    Json.obj(
      "id" -> Encoder[GameId].apply(g.id),
      "name" -> Json.fromString(g.name),
      "yearpublished" -> g.yearPublished.fold(Json.Null)(Json.fromInt),
      "minplayers" -> g.minPlayers.fold(Json.Null)(Json.fromInt),
      "maxplayers" -> g.maxPlayers.fold(Json.Null)(Json.fromInt),
      "minplaytime" -> g.minPlayingTime.fold(Json.Null)(Json.fromInt),
      "maxplaytime" -> g.maxPlayingTime.fold(Json.Null)(Json.fromInt),
      "playingtime" -> g.playingTime.fold(Json.Null)(Json.fromInt),
      "expansion" -> Json.fromBoolean(g.expansion),
      "mechanics" -> Json.fromValues(g.mechanics.map(Json.fromString)),
      "categories" -> Json.fromValues(g.categories.map(Json.fromString)),
      "stats" -> Json.obj(
        "average" -> g.ratingAverage.fold(Json.Null)(Json.fromDoubleOrNull),
        "averageweight" -> g.ratingAverageWeight.fold(Json.Null)(Json.fromDoubleOrNull),
        "usersrated" -> g.usersRated.fold(Json.Null)(Json.fromInt)
      ),
      "suggested_players" -> suggestions,
      "image" -> g.image.fold(Json.Null)(Json.fromString),
      "thumbnail" -> g.thumbnail.fold(Json.Null)(Json.fromString)
    )
  }

  given decoder: Decoder[GameData] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[GameId]
      name <- c.downField("name").as[String]
      yearPublished <- c.downField("yearpublished").as[Option[Int]]
      minPlayers <- c.downField("minplayers").as[Option[Int]]
      maxPlayers <- c.downField("maxplayers").as[Option[Int]]
      minPlayingTime <- c.downField("minplaytime").as[Option[Int]]
      maxPlayingTime <- c.downField("maxplaytime").as[Option[Int]]
      playingTime <- c.downField("playingtime").as[Option[Int]]
      expansion <- c.downField("expansion").as[Boolean]
      mechanics <- c.downField("mechanics").as[Option[List[String]]]
      categories <- c.downField("categories").as[Option[List[String]]]
      stats = c.downField("stats")
      ratingAverage <- stats.downField("average").as[Option[Double]]
      ratingAverageWeight <- stats.downField("averageweight").as[Option[Double]]
      usersRated <- stats.downField("usersrated").as[Option[Int]]
      suggestedPlayers <- c
        .downField("suggested_players")
        .downField("results")
        .as[Option[Map[String, PlayerResult]]]
      image <- c.downField("image").as[Option[String]]
      thumbnail <- c.downField("thumbnail").as[Option[String]]
    yield
      val playerSuggestions = suggestedPlayers
        .getOrElse(Map.empty)
        .flatMap { (key, result) =>
          key.toIntOption.map(count =>
            PlayerSuggestion(count, result.best_rating, result.recommended_rating, result.not_recommended_rating)
          )
        }
        .toList
        .sortBy(_.numericPlayerCount)
      GameData(
        id,
        name,
        yearPublished,
        minPlayers,
        maxPlayers,
        minPlayingTime,
        maxPlayingTime,
        playingTime,
        ratingAverage,
        ratingAverageWeight,
        expansion,
        mechanics.getOrElse(Nil),
        categories.getOrElse(Nil),
        playerSuggestions,
        usersRated,
        image,
        thumbnail
      )
  }

  private case class PlayerResult(best_rating: Int, recommended_rating: Int, not_recommended_rating: Int)
  private given Decoder[PlayerResult] = io.circe.generic.semiauto.deriveDecoder

// Community-recommended player counts from BGG voting data
case class PlayerSuggestion(
    numericPlayerCount: Int,
    best: Int,
    recommended: Int,
    notRecommended: Int
)
object PlayerSuggestion

// Filters extracted from request headers
case class GameFilters(
    playerCount: Option[Int],
    useRecommendedPlayers: Boolean,
    minDuration: Option[Int],
    maxDuration: Option[Int],
    complexity: Option[Double],
    minRating: Option[Double],
    mechanics: List[String],
    includeExpansions: Boolean,
    fieldWhitelist: Option[List[String]]
)

object GameFilters:
  val default: GameFilters = GameFilters(
    playerCount = None,
    useRecommendedPlayers = true,
    minDuration = None,
    maxDuration = None,
    complexity = None,
    minRating = None,
    mechanics = Nil,
    includeExpansions = false,
    fieldWhitelist = None
  )
