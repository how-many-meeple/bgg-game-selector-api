"""
Vector synchronization utilities.

Provides hooks to keep the vector store in sync with the game cache.
"""

import logging
from datetime import datetime

from boardgamegeek.objects.games import BoardGame

from boardgame.vector_generation import GameVectorGenerator
from boardgame.vector_store import GameVector, VectorStore

log = logging.getLogger(__name__)


class VectorSync:
    """Synchronizes game cache with vector store"""

    def __init__(self, vector_store: VectorStore, min_ratings: int = 100):
        """
        Initialize VectorSync.

        Args:
            vector_store: VectorStore implementation
            min_ratings: Minimum number of user ratings required to vectorize a game (default: 100)
        """
        self.vector_store = vector_store
        self.min_ratings = min_ratings

    def should_vectorize_game(self, game_data: dict) -> tuple[bool, str]:
        """
        Determine if a game should be vectorized based on ratings and age.

        Rules:
        - Standard: 100+ ratings required
        - New games (published this year or last year): 10+ ratings required
        - Very new games (this/last year but <10 ratings): skipped

        Args:
            game_data: Game data dictionary

        Returns:
            Tuple of (should_vectorize: bool, reason: str)
        """
        # Check multiple locations for usersrated (could be top-level or in stats)
        users_rated = (
            game_data.get("usersrated", 0)
            or game_data.get("users_rated", 0)
            or (
                game_data.get("stats", {}).get("usersrated", 0)
                if isinstance(game_data.get("stats"), dict)
                else 0
            )
        )

        year_published = game_data.get("yearpublished") or game_data.get("year")

        # Calculate game age in years
        current_year = datetime.now().year

        if year_published:
            try:
                game_year = int(year_published)
                years_old = current_year - game_year
            except (ValueError, TypeError):
                years_old = 999  # Treat invalid years as old games
        else:
            years_old = 999  # Unknown year = treat as old game

        # Apply rating rules based on age
        if years_old <= 1:
            # New game (this year or last year): require 10+ ratings
            if users_rated >= 10:
                return (True, f"new game ({game_year}) with {users_rated} ratings")
            else:
                return (
                    False,
                    f"new game ({game_year}) but only {users_rated} ratings (min: 10)",
                )
        else:
            # Standard game: require 100+ ratings
            if users_rated >= self.min_ratings:
                return (True, f"{users_rated} ratings")
            else:
                return (False, f"only {users_rated} ratings (min: {self.min_ratings})")

    def sync_game(self, game: BoardGame) -> None:
        """
        Generate and save vector for a single game.

        Only vectorizes games that meet the ratings threshold based on their age.

        Args:
            game: BoardGame object to vectorize
        """
        try:
            game_data = game.data()

            # Check if game should be vectorized
            should_vectorize, reason = self.should_vectorize_game(game_data)

            if not should_vectorize:
                log.debug(
                    f"Skipping vector for game {game.id} ({game.name}) - {reason}"
                )
                return

            vector = GameVectorGenerator.generate(game_data)

            game_vector = GameVector(
                game_id=game.id,
                name=game.name,
                vector=vector,
            )

            self.vector_store.save(game_vector)
            log.debug(f"Synced vector for game {game.id} ({game.name}) - {reason}")

        except Exception as e:
            log.error(f"Failed to sync vector for game {game.id}: {e}")

    def sync_games(self, games: list[BoardGame]) -> None:
        """
        Generate and save vectors for multiple games.

        Args:
            games: List of BoardGame objects to vectorize
        """
        for game in games:
            self.sync_game(game)
