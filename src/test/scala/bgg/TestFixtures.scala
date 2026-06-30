package bgg

import bgg.bggapi.BggClient
import bgg.config.*
import bgg.domain.*

object TestFixtures:

  def testGame(id: Int, name: String): GameData = GameData(
    id = GameId(id),
    name = name,
    yearPublished = Some(2020),
    minPlayers = Some(2),
    maxPlayers = Some(4),
    minPlayingTime = Some(30),
    maxPlayingTime = Some(60),
    playingTime = Some(60),
    ratingAverage = Some(7.5),
    ratingAverageWeight = Some(2.5),
    expansion = false,
    mechanics = List("Hand Management"),
    categories = List("Fantasy"),
    playerSuggestions = Nil,
    usersRated = Some(500)
  )

  def stubClient(
      collectionResult: Either[Fail, List[GameId]] = Right(Nil),
      geeklistResult: Either[Fail, List[GameId]] = Right(Nil),
      hotResult: Either[Fail, List[GameId]] = Right(Nil),
      gamesResult: Either[Fail, List[GameData]] = Right(Nil),
      playsResult: Either[Fail, List[PlayData]] = Right(Nil)
  ): BggClient = new BggClient:
    def fetchCollection(username: String, retries: Int): Either[Fail, List[GameId]] = collectionResult
    def fetchGeeklist(listId: String): Either[Fail, List[GameId]] = geeklistResult
    def fetchHotGames(): Either[Fail, List[GameId]] = hotResult
    def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]] = gamesResult
    def searchGames(query: String): Either[Fail, List[GameData]] = Right(Nil)
    def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]] = playsResult

  val testConfig: AppConfig = AppConfig(
    bgg = BggConfig(accessToken = "", timeoutSeconds = 10, retries = 3, retryDelaySeconds = 1),
    cache = CacheConfig(
      backend = CacheBackend.Memory,
      requestCacheTtlSeconds = 60,
      gameCacheTtlSeconds = 3600,
      vectorMinRatings = 50,
      sqliteRequestCachePath = "",
      sqliteGameCachePath = "",
      sqliteVectorStorePath = "",
      sqlitePrefetchStatusPath = ""
    ),
    aws = AwsConfig(
      region = "us-east-1",
      dynamoRequestTable = "",
      dynamoGameTable = "",
      dynamoVectorTable = "",
      dynamoPrefetchTable = "",
      dynamoPlaysTable = "",
      prefetchSqsUrl = "",
      prefetchStateMachineArn = ""
    ),
    server = ServerConfig(host = "0.0.0.0", port = 8080, allowedOrigins = List("*"))
  )
