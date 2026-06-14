"""Game recommendation engine using vector similarity."""

import logging
from dataclasses import dataclass
from typing import Optional

from boardgame.filter_processor import FilterProcessor
from boardgame.game_cache import GameCache
from boardgame.vector_generation import VectorSimilarity
from boardgame.vector_store import VectorStore

log = logging.getLogger(__name__)


@dataclass
class RecommendedGame:

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

    def __init__(
        self, vector_store: VectorStore, game_cache: Optional[GameCache] = None
    ):
        self.vector_store = vector_store
        self.game_cache = game_cache

    def get_similar_games(
        self,
        taste_vector: list[float],
        limit: int = 10,
        exclude_ids: Optional[list[int]] = None,
        game_filter: Optional[FilterProcessor] = None,
        min_similarity: float = 0.0,
    ) -> list[RecommendedGame]:
        exclude_ids = exclude_ids or []
        log.info(f"Computing recommendations, exclude_count={len(exclude_ids)}")

        all_vectors = self.vector_store.load_all()
        log.info(f"Loaded {len(all_vectors)} vectors from store")

        if not all_vectors:
            log.warning("No vectors found in store")
            return []

        candidates = []
        for game_vector in all_vectors:
            if game_vector.game_id in exclude_ids:
                continue

            similarity = VectorSimilarity.cosine_similarity(
                taste_vector, game_vector.vector
            )

            if similarity < min_similarity:
                continue

            candidates.append(
                RecommendedGame(
                    game_id=game_vector.game_id,
                    name=game_vector.name,
                    similarity_score=similarity,
                )
            )

        log.info(f"Found {len(candidates)} candidates after similarity scoring")

        candidates.sort(key=lambda x: x.similarity_score, reverse=True)

        if game_filter:
            candidates = self._apply_game_filters(candidates, game_filter, limit=limit)
            log.info(f"Reduced to {len(candidates)} games after game-level filtering")
            if len(candidates) < limit:
                log.warning(
                    f"Filters reduced results to {len(candidates)} (requested {limit}) — "
                    "consider relaxing filter criteria"
                )

        return candidates[:limit]

    def _apply_game_filters(
        self,
        candidates: list[RecommendedGame],
        game_filter: FilterProcessor,
        limit: int = 0,
    ) -> list[RecommendedGame]:
        if not self.game_cache:
            log.warning("No game cache available — skipping game-level filters")
            return candidates

        filtered = []
        for candidate in candidates:
            if limit and len(filtered) >= limit:
                break
            game = self.game_cache.load(candidate.game_id)
            if game is None:
                log.debug(
                    f"Game {candidate.game_id} not in cache — excluding from recommendations"
                )
                continue
            if not game_filter.filter_game(game):
                filtered.append(candidate)

        return filtered


class RecommendationService:

    def __init__(
        self, vector_store: VectorStore, game_cache: Optional[GameCache] = None
    ):
        self.engine = RecommendationEngine(vector_store, game_cache)

    def recommend_from_taste_vector(
        self,
        taste_vector: list[float],
        limit: int = 10,
        exclude_ids: Optional[list[int]] = None,
        game_filter: Optional[FilterProcessor] = None,
    ) -> list[RecommendedGame]:
        """Recommend games from a pre-built, L2-normalised taste vector."""
        return self.engine.get_similar_games(
            taste_vector=taste_vector,
            limit=limit,
            exclude_ids=exclude_ids,
            game_filter=game_filter,
        )

    def recommend_from_game_vectors(
        self,
        game_vectors: list[list[float]],
        limit: int = 10,
        exclude_ids: Optional[list[int]] = None,
        game_filter: Optional[FilterProcessor] = None,
    ) -> list[RecommendedGame]:
        """Recommend games from a list of raw (un-aggregated) per-game vectors."""
        if not game_vectors:
            log.warning("No game vectors provided for recommendations")
            return []

        from boardgame.vector_generation import VECTOR_DIMENSIONS

        taste_vector = [0.0] * VECTOR_DIMENSIONS
        for vec in game_vectors:
            for i in range(len(vec)):
                taste_vector[i] += vec[i]

        n = len(game_vectors)
        taste_vector = VectorSimilarity.l2_normalise([v / n for v in taste_vector])

        return self.recommend_from_taste_vector(
            taste_vector=taste_vector,
            limit=limit,
            exclude_ids=exclude_ids,
            game_filter=game_filter,
        )
