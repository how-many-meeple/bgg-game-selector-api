package bgg.bggapi

import bgg.cache.{GameCache, RequestCache}
import bgg.domain.{Fail, GameData, GameId}
import bgg.store.{StoredVector, VectorStore}
import bgg.vector.VectorMath
import com.typesafe.scalalogging.StrictLogging

import java.time.{Instant, Year}

class GameService(
    bggClient: BggClient,
    gameCache: GameCache,
    vectorStore: VectorStore,
    requestCache: RequestCache,
    vectorMinRatings: Int,
    clock: () => Instant
) extends StrictLogging:

  def resolveGameIds(ids: List[GameId]): Either[Fail, List[GameData]] =
    val (cached, missing) = partitionCached(ids)
    if missing.isEmpty then Right(cached)
    else
      bggClient.fetchGamesByIds(missing).map { fetched =>
        fetched.foreach(cacheAndSync)
        (cached ++ fetched).sortBy(_.name)
      }

  def resolveCollection(username: String): Either[Fail, List[GameData]] =
    bggClient.fetchCollection(username).flatMap(resolveGameIds)

  def resolveGeeklist(listId: String): Either[Fail, List[GameData]] =
    bggClient.fetchGeeklist(listId).flatMap(resolveGameIds)

  private val HotListCacheKey = "hot:trending"
  private val HotListTtlSeconds = 7L * 24 * 3600

  def resolveHotGames(): Either[Fail, List[GameData]] =
    requestCache.load[List[GameData]](HotListCacheKey) match
      case Some(games) =>
        logger.debug(s"Hot list served from request cache (${games.size} games)")
        Right(games)
      case None =>
        bggClient.fetchHotGames().flatMap(resolveGameIds).map { games =>
          requestCache.save(HotListCacheKey, games, HotListTtlSeconds, clock())
          logger.info(s"Fetched and cached hot list with ${games.size} games")
          games
        }

  def search(query: String): Either[Fail, List[GameData]] =
    bggClient.searchGames(query)

  private def partitionCached(ids: List[GameId]): (List[GameData], List[GameId]) =
    val (misses, cached) = ids.partitionMap(id => gameCache.load(id).toRight(id))
    (cached, misses)

  private def cacheAndSync(game: GameData): Unit =
    gameCache.save(game, clock())
    syncVector(game)

  private def syncVector(game: GameData): Unit =
    val (shouldSync, reason) = shouldVectorize(game)
    if shouldSync then
      val vector = VectorMath.generateGameVector(game)
      vectorStore.save(
        StoredVector(
          gameId = game.id,
          name = game.name,
          vector = vector,
          updatedAt = clock()
        )
      )
      logger.debug(s"Synced vector for game ${game.id.value} (${game.name}) — $reason")
    else logger.debug(s"Skipping vector for game ${game.id.value} (${game.name}) — $reason")

  private val NewGameThresholdYears = 1
  private val NewGameMinRatings = 10

  private def shouldVectorize(game: GameData): (Boolean, String) =
    val usersRated = game.usersRated.getOrElse(0)
    val currentYear = Year.now(java.time.ZoneOffset.UTC).getValue
    val yearsOld = game.yearPublished.map(y => currentYear - y).getOrElse(Int.MaxValue)

    if yearsOld <= NewGameThresholdYears then
      if usersRated >= NewGameMinRatings then (true, s"new game with $usersRated ratings")
      else (false, s"new game but only $usersRated ratings (min: $NewGameMinRatings)")
    else if usersRated >= vectorMinRatings then (true, s"$usersRated ratings")
    else (false, s"only $usersRated ratings (min: $vectorMinRatings)")
