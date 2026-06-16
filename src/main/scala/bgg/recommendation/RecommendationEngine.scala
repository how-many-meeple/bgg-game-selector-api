package bgg.recommendation

import bgg.cache.GameCache
import bgg.domain.GameId
import bgg.filter.GameFilter
import bgg.store.VectorStore
import bgg.vector.{GameVector, VectorMath}
import com.typesafe.scalalogging.StrictLogging

case class RecommendedGame(
    gameId: GameId,
    name: String,
    similarityScore: Double,
)

object RecommendationEngine extends StrictLogging:

  def recommend(
      tasteVector: GameVector,
      vectorStore: VectorStore,
      gameCache: GameCache,
      limit: Int,
      excludeIds: Set[GameId],
      filters: bgg.domain.GameFilters,
  ): List[RecommendedGame] =
    val allVectors = vectorStore.loadAll()
    logger.info(s"Loaded ${allVectors.size} vectors for recommendation scoring")

    val candidates = scoreCandidates(tasteVector, allVectors, excludeIds)
    val sorted     = candidates.sortBy(-_.similarityScore)

    if filters == bgg.domain.GameFilters.default then sorted.take(limit)
    else applyFiltersUntilLimit(sorted, gameCache, filters, limit)

  private def scoreCandidates(
      tasteVector: GameVector,
      allVectors: List[bgg.store.StoredVector],
      excludeIds: Set[GameId],
  ): List[RecommendedGame] =
    allVectors
      .filterNot(sv => excludeIds.contains(sv.gameId))
      .map { sv =>
        RecommendedGame(
          gameId          = sv.gameId,
          name            = sv.name,
          similarityScore = VectorMath.cosineSimilarity(tasteVector, sv.vector),
        )
      }

  private def applyFiltersUntilLimit(
      sorted: List[RecommendedGame],
      gameCache: GameCache,
      filters: bgg.domain.GameFilters,
      limit: Int,
  ): List[RecommendedGame] =
    val filter = GameFilter.fromFilters(filters)
    sorted.iterator
      .filter { candidate =>
        gameCache.load(candidate.gameId) match
          case None =>
            logger.debug(s"Game ${candidate.gameId.value} not in cache — skipping from recommendations")
            false
          case Some(game) =>
            !filter.excludes(game)
      }
      .take(limit)
      .toList
