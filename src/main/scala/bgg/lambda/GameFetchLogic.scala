package bgg.lambda

import bgg.bggapi.BggClient
import bgg.cache.CacheProvider
import bgg.domain.{Fail, GameData, GameId}
import bgg.store.StoredVector
import bgg.vector.VectorMath
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Json, parser}
import io.circe.syntax.*
import ox.*

import java.time.Instant

case class GameFetchOutput(succeeded: List[Int], failed: List[Int], totalCached: Int)

class GameFetchLogic(
    bggClient: BggClient,
    caches: CacheProvider,
    vectorMinRatings: Int,
    clock: () => Instant
) extends StrictLogging:

  private val SubBatchSize = 20
  private val Parallelism = 5

  def handle(eventJson: String): String =
    val input = parseInput(eventJson)
    val output = fetchGames(input)
    toJson(output)

  private def parseInput(json: String): List[GameId] =
    parser.parse(json).toOption
      .flatMap(_.hcursor.downField("gameIds").as[List[Int]].toOption)
      .map(_.map(GameId(_)))
      .getOrElse(throw RuntimeException("Invalid input JSON for GameFetch"))

  private def fetchGames(ids: List[GameId]): GameFetchOutput =
    val (alreadyCached, missing) = partitionCached(ids)
    logger.info(s"GameFetch: ${ids.size} total, ${alreadyCached.size} already cached, ${missing.size} to fetch")

    if missing.isEmpty then
      return GameFetchOutput(ids.map(_.value), Nil, alreadyCached.size)

    val subBatches = missing.grouped(SubBatchSize).toList
    val results = processSubBatchesParallel(subBatches)

    val succeeded = alreadyCached.map(_.id) ++ results.flatMap(_.succeeded)
    val failed = results.flatMap(_.failed)

    logger.info(s"GameFetch complete: ${succeeded.size} succeeded, ${failed.size} failed")
    GameFetchOutput(succeeded.map(_.value), failed.map(_.value), succeeded.size)

  private case class SubBatchResult(succeeded: List[GameId], failed: List[GameId])

  private def processSubBatchesParallel(subBatches: List[List[GameId]]): List[SubBatchResult] =
    supervised:
      subBatches
        .grouped(Parallelism)
        .toList
        .flatMap { chunk =>
          val forks = chunk.map { batch =>
            forkUnsupervised(processSubBatch(batch))
          }
          forks.map(_.join())
        }

  private def processSubBatch(batch: List[GameId]): SubBatchResult =
    bggClient.fetchGamesByIds(batch) match
      case Right(games) =>
        games.foreach(cacheAndSync)
        val fetchedIds = games.map(_.id).toSet
        val missed = batch.filterNot(fetchedIds.contains)
        SubBatchResult(games.map(_.id), missed)
      case Left(Fail.BggRateLimited(msg)) =>
        logger.warn(s"Rate limited during sub-batch: $msg")
        throw BggRateLimitedException(msg)
      case Left(err) =>
        logger.error(s"Failed to fetch sub-batch: $err")
        SubBatchResult(Nil, batch)

  private def partitionCached(ids: List[GameId]): (List[GameData], List[GameId]) =
    val cached = caches.gameCache.loadBatch(ids)
    val cachedIds = cached.map(_.id).toSet
    val missing = ids.filterNot(cachedIds.contains)
    (cached, missing)

  private def cacheAndSync(game: GameData): Unit =
    caches.gameCache.save(game, clock())
    syncVector(game)

  private def syncVector(game: GameData): Unit =
    val usersRated = game.usersRated.getOrElse(0)
    if usersRated >= vectorMinRatings then
      val vector = VectorMath.generateGameVector(game)
      caches.vectorStore.save(
        StoredVector(gameId = game.id, name = game.name, vector = vector, updatedAt = clock())
      )

  private def toJson(output: GameFetchOutput): String =
    Json.obj(
      "succeeded" -> output.succeeded.asJson,
      "failed" -> output.failed.asJson,
      "totalCached" -> Json.fromInt(output.totalCached)
    ).noSpaces
