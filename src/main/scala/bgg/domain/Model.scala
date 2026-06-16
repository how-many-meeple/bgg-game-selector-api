package bgg.domain

import io.circe.{Codec, Decoder, Encoder}

// Opaque types for domain identifiers — prevents mixing up raw Int/String values
opaque type GameId = Int
object GameId:
  def apply(value: Int): GameId           = value
  def unapply(id: GameId): Some[Int]      = Some(id)
  extension (id: GameId) def value: Int   = id
  given Codec[GameId]                     = Codec.from(Decoder.decodeInt.map(GameId(_)), Encoder.encodeInt.contramap(_.value))

opaque type GeekListId = String
object GeekListId:
  def apply(value: String): GeekListId             = value
  extension (id: GeekListId) def value: String     = id

opaque type Username = String
object Username:
  def apply(value: String): Username               = value
  extension (u: Username) def value: String        = u

enum SourceType:
  case Collection, GeeKList
  def toPathSegment: String = this match
    case Collection => "collection"
    case GeeKList   => "geeklist"

object SourceType:
  def fromString(s: String): Either[String, SourceType] = s.toLowerCase match
    case "collection" => Right(Collection)
    case "geeklist"   => Right(GeeKList)
    case other        => Left(s"Invalid source_type '$other'. Must be one of: collection, geeklist")

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
):
  def toJson: io.circe.Json = GameData.encoder(this)

object GameData:
  given encoder: Encoder[GameData] = io.circe.generic.semiauto.deriveEncoder
  given decoder: Decoder[GameData] = io.circe.generic.semiauto.deriveDecoder

// Community-recommended player counts from BGG voting data
case class PlayerSuggestion(
    numericPlayerCount: Int,
    best: Int,
    recommended: Int,
    notRecommended: Int,
)
object PlayerSuggestion:
  given Codec[PlayerSuggestion] = io.circe.generic.semiauto.deriveCodec

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
    fieldWhitelist: Option[List[String]],
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
    fieldWhitelist = None,
  )
