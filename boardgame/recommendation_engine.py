"""
Game recommendation engine using vector similarity.

Provides filtering, ranking, and recommendation logic based on pre-computed
game vectors and user taste profiles.
"""

import logging
from dataclasses import dataclass
from typing import Optional

from boardgame.vector_generation import VectorSimilarity
from boardgame.vector_store import GameVector, VectorStore

log = logging.getLogger(__name__)


@dataclass
class RecommendedGame:
    """Data model for a recommended game with similarity score"""

    game_id: int
    name: str
    similarity_score: float
    matched_mechanics: Optional[int] = None
    matched_categories: Optional[int] = None

    def to_dict(self) -> dict:
        result = {
            "game_id": self.game_id,
            "name": self.name,
            "similarity_score": round(self.similarity_score, 4),
        }
        if self.matched_mechanics is not None:
            result["matched_mechanics"] = self.matched_mechanics
        if self.matched_categories is not None:
            result["matched_categories"] = self.matched_categories
        return result


class RecommendationEngine:
    """Engine for computing game recommendations based on taste vectors"""

    def __init__(self, vector_store: VectorStore):
        self.vector_store = vector_store

    def get_similar_games(
        self,
        taste_vector: list[float],
        limit: int = 10,
        exclude_ids: Optional[list[int]] = None,
        player_count: Optional[int] = None,
        min_playtime: Optional[int] = None,
        max_playtime: Optional[int] = None,
        min_similarity: float = 0.0,
    ) -> list[RecommendedGame]:
        """
        Find games similar to the given taste vector.

        Args:
            taste_vector: Aggregated user taste vector
            limit: Maximum number of recommendations to return
            exclude_ids: Game IDs to exclude from recommendations
            player_count: Filter by player count support
            min_playtime: Minimum playtime in minutes
            max_playtime: Maximum playtime in minutes
            min_similarity: Minimum similarity threshold (0-1)

        Returns:
            List of recommended games sorted by similarity (highest first)
        """
        exclude_ids = exclude_ids or []

        log.info(
            f"Computing recommendations with filters: "
            f"player_count={player_count}, playtime=[{min_playtime}, {max_playtime}], "
            f"exclude_count={len(exclude_ids)}"
        )

        # Load all vectors from store
        all_vectors = self.vector_store.load_all()
        log.info(f"Loaded {len(all_vectors)} vectors from store")

        if not all_vectors:
            log.warning("No vectors found in store")
            return []

        # Compute similarity scores
        candidates = []
        for game_vector in all_vectors:
            # Skip excluded games
            if game_vector.game_id in exclude_ids:
                continue

            # Compute similarity
            similarity = VectorSimilarity.cosine_similarity(
                taste_vector, game_vector.vector
            )

            # Skip games below similarity threshold
            if similarity < min_similarity:
                continue

            candidates.append(
                RecommendedGame(
                    game_id=game_vector.game_id,
                    name=game_vector.name,
                    similarity_score=similarity,
                )
            )

        log.info(f"Found {len(candidates)} candidates after similarity filtering")

        # Sort by similarity (descending)
        candidates.sort(key=lambda x: x.similarity_score, reverse=True)

        # Apply additional filters if needed (player_count, playtime)
        # Note: These filters require loading full game data
        if player_count or min_playtime or max_playtime:
            candidates = self._apply_game_filters(
                candidates, player_count, min_playtime, max_playtime
            )
            log.info(f"Reduced to {len(candidates)} games after game-level filtering")

        # Return top N results
        return candidates[:limit]

    def _apply_game_filters(
        self,
        candidates: list[RecommendedGame],
        player_count: Optional[int],
        min_playtime: Optional[int],
        max_playtime: Optional[int],
    ) -> list[RecommendedGame]:
        """
        Apply player count and playtime filters to candidates.

        Note: This method is a placeholder. To fully implement filtering,
        you would need to:
        1. Load full game data from game cache for each candidate
        2. Check min_players <= player_count <= max_players
        3. Check min_playing_time <= playtime <= max_playing_time

        For now, we return candidates as-is since filtering requires
        integration with the game cache layer.
        """
        # TODO: Implement game-level filtering by loading from game cache
        # This would require passing GameCache to the engine
        log.warning(
            "Game-level filtering (player_count, playtime) not yet implemented - "
            "returning unfiltered results"
        )
        return candidates


class RecommendationService:
    """High-level service for generating recommendations from game lists"""

    def __init__(self, vector_store: VectorStore):
        self.engine = RecommendationEngine(vector_store)

    def recommend_from_game_vectors(
        self,
        game_vectors: list[list[float]],
        limit: int = 10,
        exclude_ids: Optional[list[int]] = None,
        player_count: Optional[int] = None,
        min_playtime: Optional[int] = None,
        max_playtime: Optional[int] = None,
    ) -> list[RecommendedGame]:
        """
        Generate recommendations from pre-computed game vectors.

        Args:
            game_vectors: List of game vectors to build taste from
            limit: Maximum number of recommendations
            exclude_ids: Game IDs to exclude
            player_count: Filter by player count
            min_playtime: Minimum playtime in minutes
            max_playtime: Maximum playtime in minutes

        Returns:
            List of recommended games
        """
        if not game_vectors:
            log.warning("No game vectors provided for recommendations")
            return []

        # Build taste vector by averaging
        from boardgame.vector_generation import VECTOR_DIMENSIONS

        taste_vector = [0.0] * VECTOR_DIMENSIONS

        for vec in game_vectors:
            for i in range(len(vec)):
                taste_vector[i] += vec[i]

        # Average
        n = len(game_vectors)
        taste_vector = [v / n for v in taste_vector]

        return self.engine.get_similar_games(
            taste_vector=taste_vector,
            limit=limit,
            exclude_ids=exclude_ids,
            player_count=player_count,
            min_playtime=min_playtime,
            max_playtime=max_playtime,
        )
