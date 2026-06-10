"""Integration tests for the recommendation engine"""

import unittest
from unittest.mock import Mock

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
