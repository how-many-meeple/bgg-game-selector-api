package bgg.bggapi

import bgg.cache.CacheProvider
import bgg.domain.{CollectionItem, CollectionResult, Fail, GameData, GameId, PlayData}
import bgg.store.StoredVector
import bgg.vector.VectorMath
import com.typesafe.scalalogging.StrictLogging

import java.time.{Instant, Year}

class GameService(
    bggClient: BggClient,
    caches: CacheProvider,
    vectorMinRatings: Int,
    clock: () => Instant
) extends StrictLogging:

  private val gameCache = caches.gameCache
  private val vectorStore = caches.vectorStore
  private val requestCache = caches.requestCache
  private val playsCache = caches.playsCache

  def resolveGameIds(ids: List[GameId]): Either[Fail, List[GameData]] =
    val (cached, missing) = partitionCached(ids)
    if missing.isEmpty then Right(cached.sortBy(_.name))
    else
      bggClient.fetchGamesByIds(missing).map { fetched =>
        fetched.foreach(cacheAndSync)
        (cached ++ fetched).sortBy(_.name)
      }

  private val CollectionIdsTtlSeconds = 24L * 3600

  // Per-user collection metadata (lastModified date) is kept separate from the globally-shared
  // GameData cache so one user's dates never leak into another's collection or /hot.
  def resolveCollection(username: String): Either[Fail, CollectionResult] =
    val idsKey = s"collection-ids:$username"
    val datesKey = s"collection-dates:$username"
    requestCache.load[List[Int]](idsKey) match
      case Some(ids) =>
        logger.debug(s"Collection IDs for $username served from request cache (${ids.size} ids)")
        val lastModified = loadCachedDates(datesKey)
        resolveGameIds(ids.map(GameId(_))).map(games => CollectionResult(games, lastModified))
      case None =>
        bggClient.fetchCollection(username).flatMap { items =>
          requestCache.save(idsKey, items.map(_.id.value), CollectionIdsTtlSeconds, clock())
          requestCache.save(datesKey, CollectionItem.datesByIdString(items), CollectionIdsTtlSeconds, clock())
          val lastModified = CollectionItem.datesByGameId(items)
          resolveGameIds(items.map(_.id)).map(games => CollectionResult(games, lastModified))
        }

  private def loadCachedDates(key: String): Map[GameId, String] =
    requestCache
      .load[Map[String, String]](key)
      .getOrElse(Map.empty)
      .flatMap((idString, date) => idString.toIntOption.map(id => GameId(id) -> date))

  def resolveGeeklist(listId: String): Either[Fail, List[GameData]] =
    val cacheKey = s"geeklist-ids:$listId"
    requestCache.load[List[Int]](cacheKey) match
      case Some(ids) =>
        logger.debug(s"Geeklist IDs for $listId served from request cache (${ids.size} ids)")
        resolveGameIds(ids.map(GameId(_)))
      case None =>
        bggClient.fetchGeeklist(listId).flatMap { ids =>
          requestCache.save(cacheKey, ids.map(_.value), CollectionIdsTtlSeconds, clock())
          resolveGameIds(ids)
        }

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

  def resolvePlays(username: String): Either[Fail, List[PlayData]] =
    playsCache.load(username) match
      case Some(plays) =>
        logger.debug(s"Plays for $username served from cache (${plays.size} plays)")
        Right(plays)
      case None =>
        fetchAllPlays(username).map { plays =>
          playsCache.save(username, plays)
          plays
        }

  private val PlaysCacheTtlSeconds = 24L * 3600

  def fetchAndCachePlays(username: String): Unit =
    if playsCache.isFresh(username, PlaysCacheTtlSeconds) then
      logger.debug(s"Plays cache for $username is fresh, skipping fetch")
    else
      fetchAllPlays(username) match
        case Right(plays) =>
          playsCache.save(username, plays)
          logger.info(s"Fetched and cached ${plays.size} plays for $username")
        case Left(err) =>
          logger.warn(s"Failed to fetch plays for $username: $err")

  private val MaxPlayPages = 200

  private def fetchAllPlays(username: String): Either[Fail, List[PlayData]] =
    @scala.annotation.tailrec
    def loop(page: Int, acc: List[List[PlayData]]): Either[Fail, List[PlayData]] =
      if page > MaxPlayPages then
        logger.warn(s"Hit max page limit ($MaxPlayPages) fetching plays for $username")
        Right(acc.reverse.flatten)
      else
        bggClient.fetchPlays(username, page) match
          case Left(err) if page == 1        => Left(err)
          case Left(_)                       => Right(acc.reverse.flatten)
          case Right(plays) if plays.isEmpty => Right(acc.reverse.flatten)
          case Right(plays)                  => loop(page + 1, plays :: acc)
    loop(1, Nil)

  private def partitionCached(ids: List[GameId]): (List[GameData], List[GameId]) =
    val cached = gameCache.loadBatch(ids)
    val cachedIds = cached.map(_.id).toSet
    val missing = ids.filterNot(cachedIds.contains)
    (cached, missing)

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
