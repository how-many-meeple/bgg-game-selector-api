package bgg.bggapi

import bgg.cache.GameCache
import bgg.domain.{Fail, GameData, GameId}
import bgg.store.{StoredVector, VectorStore}
import bgg.vector.VectorMath
import com.typesafe.scalalogging.StrictLogging

import java.time.{Instant, Year}

// Orchestrates fetching games — cache-first, BGG API for misses, vector sync on new games
class GameService(
    bggClient: BggClient,
    gameCache: GameCache,
    vectorStore: VectorStore,
    vectorMinRatings: Int,
    clock: () => Instant,
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

  def search(query: String): Either[Fail, List[GameData]] =
    bggClient.searchGames(query)

  private def partitionCached(ids: List[GameId]): (List[GameData], List[GameId]) =
    val (hits, misses) = ids.partition(id => gameCache.load(id).isDefined)
    val hitData        = hits.flatMap(gameCache.load)
    (hitData, misses)

  private def cacheAndSync(game: GameData): Unit =
    gameCache.save(game)
    syncVector(game)

  private def syncVector(game: GameData): Unit =
    val (shouldSync, reason) = shouldVectorize(game)
    if shouldSync then
      val vector = VectorMath.generateGameVector(game)
      vectorStore.save(StoredVector(
        gameId    = game.id,
        name      = game.name,
        vector    = vector,
        updatedAt = clock(),
      ))
      logger.debug(s"Synced vector for game ${game.id.value} (${game.name}) — $reason")
    else
      logger.debug(s"Skipping vector for game ${game.id.value} (${game.name}) — $reason")

  private val NewGameThresholdYears  = 1
  private val NewGameMinRatings      = 10

  private def shouldVectorize(game: GameData): (Boolean, String) =
    val usersRated = game.usersRated.getOrElse(0)
    val currentYear = Year.now(java.time.ZoneOffset.UTC).getValue
    val yearsOld = game.yearPublished.map(y => currentYear - y).getOrElse(Int.MaxValue)

    if yearsOld <= NewGameThresholdYears then
      if usersRated >= NewGameMinRatings then (true, s"new game with $usersRated ratings")
      else (false, s"new game but only $usersRated ratings (min: $NewGameMinRatings)")
    else
      if usersRated >= vectorMinRatings then (true, s"$usersRated ratings")
      else (false, s"only $usersRated ratings (min: $vectorMinRatings)")
