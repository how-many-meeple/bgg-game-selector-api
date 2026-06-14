"""Integration tests for the recommendation engine"""

import unittest

from boardgame.filter import Filter
from boardgame.filter_processor import FilterProcessor
from boardgame.recommendation_engine import RecommendationEngine, RecommendationService
from boardgame.vector_generation import GameVectorGenerator
from boardgame.vector_store import GameVector, VectorStore


class MockVectorStore(VectorStore):
    """In-memory mock vector store for testing"""

    def __init__(self):
        self.vectors = {}

    def save(self, game_vector: GameVector) -> None:
        self.vectors[game_vector.game_id] = game_vector

    def load(self, game_id: int):
        return self.vectors.get(game_id)

    def load_all(self):
        return list(self.vectors.values())


class TestRecommendationEngine(unittest.TestCase):

    def setUp(self):
        """Set up test fixtures"""
        self.vector_store = MockVectorStore()
        self.engine = RecommendationEngine(self.vector_store)

        # Add some test game vectors
        self.test_games = [
            {
                "id": 1,
                "name": "Cooperative Fantasy Game",
                "mechanics": ["Cooperative Game", "Hand Management"],
                "categories": ["Fantasy"],
                "rating_average_weight": 2.0,
                "playing_time": 60,
                "min_players": 2,
                "max_players": 4,
            },
            {
                "id": 2,
                "name": "Heavy Strategy Game",
                "mechanics": ["Worker Placement", "Action Points"],
                "categories": ["Economic"],
                "rating_average_weight": 4.5,
                "playing_time": 120,
                "min_players": 1,
                "max_players": 4,
            },
            {
                "id": 3,
                "name": "Light Party Game",
                "mechanics": ["Dice Rolling", "Push Your Luck"],
                "categories": ["Party Game"],
                "rating_average_weight": 1.5,
                "playing_time": 30,
                "min_players": 3,
                "max_players": 8,
            },
            {
                "id": 4,
                "name": "Another Fantasy Coop",
                "mechanics": ["Cooperative Game", "Dice Rolling"],
                "categories": ["Fantasy", "Adventure"],
                "rating_average_weight": 2.5,
                "playing_time": 90,
                "min_players": 2,
                "max_players": 5,
            },
        ]

        for game in self.test_games:
            vector = GameVectorGenerator.generate(game)
            game_vector = GameVector(
                game_id=game["id"],
                name=game["name"],
                vector=vector,
            )
            self.vector_store.save(game_vector)

    def test_get_similar_games(self):
        """Should return similar games ranked by similarity"""
        # Use game 1 (Cooperative Fantasy) as taste reference
        taste_game = self.test_games[0]
        taste_vector = GameVectorGenerator.generate(taste_game)

        recommendations = self.engine.get_similar_games(
            taste_vector=taste_vector,
            limit=3,
            exclude_ids=[1],  # Exclude the input game itself
        )

        # Should return other games
        self.assertGreater(len(recommendations), 0)
        self.assertLessEqual(len(recommendations), 3)

        # Game 4 (Another Fantasy Coop) should be most similar
        self.assertEqual(recommendations[0].game_id, 4)
        self.assertEqual(recommendations[0].name, "Another Fantasy Coop")

        # Similarity scores should be in descending order
        for i in range(len(recommendations) - 1):
            self.assertGreaterEqual(
                recommendations[i].similarity_score,
                recommendations[i + 1].similarity_score,
            )

    def test_exclude_ids(self):
        """Should exclude specified game IDs"""
        taste_vector = GameVectorGenerator.generate(self.test_games[0])

        recommendations = self.engine.get_similar_games(
            taste_vector=taste_vector,
            limit=10,
            exclude_ids=[1, 2, 3],
        )

        returned_ids = [rec.game_id for rec in recommendations]
        self.assertNotIn(1, returned_ids)
        self.assertNotIn(2, returned_ids)
        self.assertNotIn(3, returned_ids)

    def test_limit_results(self):
        """Should respect result limit"""
        taste_vector = GameVectorGenerator.generate(self.test_games[0])

        recommendations = self.engine.get_similar_games(
            taste_vector=taste_vector,
            limit=2,
            exclude_ids=[1],
        )

        self.assertLessEqual(len(recommendations), 2)

    def test_min_similarity_threshold(self):
        """Should filter by minimum similarity"""
        taste_vector = GameVectorGenerator.generate(self.test_games[0])

        # High threshold should reduce results
        recommendations = self.engine.get_similar_games(
            taste_vector=taste_vector,
            limit=10,
            exclude_ids=[1],
            min_similarity=0.9,  # Very high threshold
        )

        # All returned games should meet threshold
        for rec in recommendations:
            self.assertGreaterEqual(rec.similarity_score, 0.9)

    def test_empty_vector_store(self):
        """Should handle empty vector store gracefully"""
        empty_store = MockVectorStore()
        engine = RecommendationEngine(empty_store)

        taste_vector = GameVectorGenerator.generate(self.test_games[0])

        recommendations = engine.get_similar_games(
            taste_vector=taste_vector,
            limit=10,
        )

        self.assertEqual(len(recommendations), 0)


class MockGame:
    """Minimal BoardGame-like object for filter testing"""

    def __init__(
        self,
        game_id,
        min_players=1,
        max_players=4,
        min_playing_time=30,
        max_playing_time=60,
        rating_average_weight=2.0,
        rating_average=7.0,
        expansion=False,
        mechanics=None,
        player_suggestions=None,
    ):
        self.id = game_id
        self.min_players = min_players
        self.max_players = max_players
        self.min_playing_time = min_playing_time
        self.max_playing_time = max_playing_time
        self.rating_average_weight = rating_average_weight
        self.rating_average = rating_average
        self.expansion = expansion
        self.mechanics = mechanics or []
        self.player_suggestions = player_suggestions or []


class MockGameCache:
    def __init__(self, games):
        self._games = {g.id: g for g in games}

    def load(self, game_id):
        return self._games.get(game_id)

    def timeout_cache(self):
        pass


class TestRecommendationEngineFilters(unittest.TestCase):

    def _make_engine(self, games):
        vector_store = MockVectorStore()
        for game in games:
            data = {
                "id": game.id,
                "mechanics": game.mechanics,
                "categories": [],
                "playing_time": game.max_playing_time,
                "min_players": game.min_players,
                "max_players": game.max_players,
                "rating_average_weight": game.rating_average_weight,
            }
            vector_store.save(
                GameVector(
                    game_id=game.id,
                    name=f"Game {game.id}",
                    vector=GameVectorGenerator.generate(data),
                )
            )
        game_cache = MockGameCache(games)
        return RecommendationEngine(vector_store, game_cache)

    def _taste(self):
        return GameVectorGenerator.generate(
            {"mechanics": ["Hand Management"], "categories": []}
        )

    def _make_filter(self, **header_values):
        return FilterProcessor(Filter.create_filter_chain(header_values))

    def test_player_count_filter(self):
        games = [
            MockGame(1, min_players=2, max_players=4),
            MockGame(2, min_players=5, max_players=8),  # won't fit 3 players
        ]
        engine = self._make_engine(games)
        game_filter = self._make_filter(
            **{
                "Bgg-Filter-Player-Count": "3",
                "Bgg-Filter-Using-Recommended-Players": "false",
            }
        )

        results = engine.get_similar_games(
            self._taste(), limit=10, game_filter=game_filter
        )

        ids = [r.game_id for r in results]
        self.assertIn(1, ids)
        self.assertNotIn(2, ids)

    def test_max_duration_filter(self):
        games = [
            MockGame(1, max_playing_time=45),
            MockGame(2, max_playing_time=180),  # too long
        ]
        engine = self._make_engine(games)
        game_filter = self._make_filter(**{"Bgg-Filter-Max-Duration": "60"})

        results = engine.get_similar_games(
            self._taste(), limit=10, game_filter=game_filter
        )

        ids = [r.game_id for r in results]
        self.assertIn(1, ids)
        self.assertNotIn(2, ids)

    def test_complexity_filter(self):
        games = [
            MockGame(1, rating_average_weight=2.0),   # within +/-1 of target 3.0
            MockGame(2, rating_average_weight=4.5),   # outside +/-1 of target 3.0
        ]
        engine = self._make_engine(games)
        game_filter = self._make_filter(**{"Bgg-Filter-Complexity": "3.0"})

        results = engine.get_similar_games(
            self._taste(), limit=10, game_filter=game_filter
        )

        ids = [r.game_id for r in results]
        self.assertIn(1, ids)
        self.assertNotIn(2, ids)

    def test_min_rating_filter(self):
        games = [
            MockGame(1, rating_average=8.0),
            MockGame(2, rating_average=5.5),  # below threshold
        ]
        engine = self._make_engine(games)
        game_filter = self._make_filter(**{"Bgg-Filter-Min-Rating": "7.0"})

        results = engine.get_similar_games(
            self._taste(), limit=10, game_filter=game_filter
        )

        ids = [r.game_id for r in results]
        self.assertIn(1, ids)
        self.assertNotIn(2, ids)

    def test_expansions_excluded_by_default(self):
        games = [
            MockGame(1, expansion=False),
            MockGame(2, expansion=True),
        ]
        engine = self._make_engine(games)
        game_filter = self._make_filter()

        results = engine.get_similar_games(
            self._taste(), limit=10, game_filter=game_filter
        )

        ids = [r.game_id for r in results]
        self.assertIn(1, ids)
        self.assertNotIn(2, ids)

    def test_expansions_included_when_requested(self):
        games = [
            MockGame(1, expansion=False),
            MockGame(2, expansion=True),
        ]
        engine = self._make_engine(games)
        game_filter = self._make_filter(**{"Bgg-Include-Expansions": "true"})

        results = engine.get_similar_games(
            self._taste(), limit=10, game_filter=game_filter
        )

        ids = [r.game_id for r in results]
        self.assertIn(1, ids)
        self.assertIn(2, ids)

    def test_no_filter_returns_all(self):
        games = [MockGame(i) for i in range(1, 5)]
        engine = self._make_engine(games)

        results = engine.get_similar_games(self._taste(), limit=10)

        self.assertEqual(len(results), 4)

    def test_no_game_cache_skips_filters_with_warning(self):
        vector_store = MockVectorStore()
        vector_store.save(GameVector(game_id=1, name="G1", vector=self._taste()))
        engine = RecommendationEngine(vector_store, game_cache=None)
        game_filter = self._make_filter(**{"Bgg-Filter-Complexity": "1.0"})

        results = engine.get_similar_games(
            self._taste(), limit=10, game_filter=game_filter
        )

        self.assertEqual(len(results), 1)


class TestRecommendationService(unittest.TestCase):

    def setUp(self):
        """Set up test fixtures"""
        self.vector_store = MockVectorStore()
        self.service = RecommendationService(self.vector_store)

        # Add test vectors
        test_games = [
            {
                "id": 1,
                "name": "Game 1",
                "mechanics": ["Hand Management"],
                "categories": ["Fantasy"],
            },
            {
                "id": 2,
                "name": "Game 2",
                "mechanics": ["Hand Management", "Dice Rolling"],
                "categories": ["Fantasy"],
            },
            {
                "id": 3,
                "name": "Game 3",
                "mechanics": ["Worker Placement"],
                "categories": ["Economic"],
            },
        ]

        for game in test_games:
            vector = GameVectorGenerator.generate(game)
            self.vector_store.save(
                GameVector(game_id=game["id"], name=game["name"], vector=vector)
            )

    def test_recommend_from_game_vectors(self):
        """Should generate recommendations from game vectors"""
        # Create taste from games 1 and 2
        game_vectors = [
            GameVectorGenerator.generate(
                {"mechanics": ["Hand Management"], "categories": ["Fantasy"]}
            ),
            GameVectorGenerator.generate(
                {"mechanics": ["Dice Rolling"], "categories": ["Fantasy"]}
            ),
        ]

        recommendations = self.service.recommend_from_game_vectors(
            game_vectors=game_vectors,
            limit=2,
            exclude_ids=[1, 2],
        )

        # Should return game 3
        self.assertGreater(len(recommendations), 0)

    def test_empty_game_vectors(self):
        """Should handle empty input gracefully"""
        recommendations = self.service.recommend_from_game_vectors(
            game_vectors=[],
            limit=10,
        )

        self.assertEqual(len(recommendations), 0)


if __name__ == "__main__":
    unittest.main()
