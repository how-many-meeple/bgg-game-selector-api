"""Tests for vector generation and similarity computation"""

import unittest

from boardgame.vector_generation import (
    GameVectorGenerator,
    TasteVectorBuilder,
    VectorSimilarity,
    VECTOR_DIMENSIONS,
)


class TestGameVectorGenerator(unittest.TestCase):

    def test_generate_vector_dimensions(self):
        """Vector should have correct fixed dimensionality"""
        game_data = {
            "id": 1,
            "name": "Test Game",
            "mechanics": ["Hand Management", "Dice Rolling"],
            "categories": ["Fantasy"],
            "rating_average_weight": 2.5,
            "playing_time": 60,
            "min_players": 2,
            "max_players": 4,
            "rating_average": 7.5,
        }

        vector = GameVectorGenerator.generate(game_data)

        self.assertEqual(len(vector), VECTOR_DIMENSIONS)
        self.assertIsInstance(vector, list)
        self.assertTrue(all(isinstance(v, float) for v in vector))

    def test_generate_vector_with_minimal_data(self):
        """Should handle games with minimal data"""
        game_data = {"id": 1, "name": "Minimal Game"}

        vector = GameVectorGenerator.generate(game_data)

        self.assertEqual(len(vector), VECTOR_DIMENSIONS)
        # Most values should be 0 for minimal data
        self.assertGreater(vector.count(0.0), VECTOR_DIMENSIONS * 0.8)

    def test_mechanics_encoding(self):
        """Known mechanics should be encoded as 1.0"""
        game_with_mechanic = {
            "mechanics": ["Hand Management"],
            "categories": [],
        }
        game_without_mechanic = {
            "mechanics": [],
            "categories": [],
        }

        vec_with = GameVectorGenerator.generate(game_with_mechanic)
        vec_without = GameVectorGenerator.generate(game_without_mechanic)

        # Vectors should differ
        self.assertNotEqual(vec_with, vec_without)

    def test_unknown_mechanics_ignored(self):
        """Unknown mechanics should not break generation"""
        game_data = {
            "mechanics": ["Unknown Mechanic That Does Not Exist"],
            "categories": [],
        }

        vector = GameVectorGenerator.generate(game_data)

        self.assertEqual(len(vector), VECTOR_DIMENSIONS)

    def test_weight_normalization(self):
        """Weight should be normalized to 0-1 range"""
        game_light = {"rating_average_weight": 1.0}
        game_heavy = {"rating_average_weight": 5.0}

        vec_light = GameVectorGenerator.generate(game_light)
        vec_heavy = GameVectorGenerator.generate(game_heavy)

        # Weight is after mechanics and categories
        schema = GameVectorGenerator.get_schema()
        weight_idx = schema["numeric_features"]["start_index"]

        self.assertAlmostEqual(vec_light[weight_idx], 0.2)
        self.assertAlmostEqual(vec_heavy[weight_idx], 1.0)

    def test_playtime_normalization(self):
        """Playtime should be normalized and capped"""
        game_short = {"playing_time": 30}
        game_long = {"playing_time": 500}  # Above cap

        vec_short = GameVectorGenerator.generate(game_short)
        vec_long = GameVectorGenerator.generate(game_long)

        schema = GameVectorGenerator.get_schema()
        playtime_idx = schema["numeric_features"]["start_index"] + 1

        self.assertAlmostEqual(vec_short[playtime_idx], 30 / 240)
        self.assertAlmostEqual(vec_long[playtime_idx], 1.0)  # Capped at 240

    def test_cooperative_flag(self):
        """Cooperative games should have flag set"""
        game_coop = {"mechanics": ["Cooperative Game"]}
        game_competitive = {"mechanics": ["Auction/Bidding"]}

        vec_coop = GameVectorGenerator.generate(game_coop)
        vec_comp = GameVectorGenerator.generate(game_competitive)

        # Cooperative flag is last dimension
        self.assertEqual(vec_coop[-1], 1.0)
        self.assertEqual(vec_comp[-1], 0.0)


class TestTasteVectorBuilder(unittest.TestCase):

    def test_build_from_single_game(self):
        """Should build taste vector from single game"""
        games = [
            {
                "mechanics": ["Hand Management"],
                "categories": ["Fantasy"],
                "rating_average_weight": 2.0,
            }
        ]

        taste_vector = TasteVectorBuilder.build(games)

        self.assertEqual(len(taste_vector), VECTOR_DIMENSIONS)

    def test_build_from_multiple_games(self):
        """Should aggregate features from multiple games"""
        games = [
            {"mechanics": ["Hand Management"], "categories": ["Fantasy"]},
            {"mechanics": ["Dice Rolling"], "categories": ["Fantasy"]},
        ]

        taste_vector = TasteVectorBuilder.build(games)

        self.assertEqual(len(taste_vector), VECTOR_DIMENSIONS)
        # Fantasy category should be strongly represented (present in both games)

    def test_build_from_empty_list(self):
        """Should handle empty game list gracefully"""
        taste_vector = TasteVectorBuilder.build([])

        self.assertEqual(len(taste_vector), VECTOR_DIMENSIONS)
        # All zeros expected
        self.assertTrue(all(v == 0.0 for v in taste_vector))

    def test_numeric_averaging(self):
        """Numeric features should be averaged"""
        games = [
            {"rating_average_weight": 2.0},
            {"rating_average_weight": 4.0},
        ]

        taste_vector = TasteVectorBuilder.build(games)

        schema = GameVectorGenerator.get_schema()
        weight_idx = schema["numeric_features"]["start_index"]

        # Average of normalized (2/5) and (4/5) = 0.6
        expected = (2.0 / 5 + 4.0 / 5) / 2
        self.assertAlmostEqual(taste_vector[weight_idx], expected, places=2)


class TestVectorSimilarity(unittest.TestCase):

    def test_identical_vectors(self):
        """Identical vectors should have similarity 1.0"""
        vec_a = [1.0, 0.5, 0.0, 1.0]
        vec_b = [1.0, 0.5, 0.0, 1.0]

        similarity = VectorSimilarity.cosine_similarity(vec_a, vec_b)

        self.assertAlmostEqual(similarity, 1.0)

    def test_orthogonal_vectors(self):
        """Orthogonal vectors should have similarity ~0.0"""
        vec_a = [1.0, 0.0, 0.0, 0.0]
        vec_b = [0.0, 1.0, 0.0, 0.0]

        similarity = VectorSimilarity.cosine_similarity(vec_a, vec_b)

        self.assertAlmostEqual(similarity, 0.0)

    def test_opposite_vectors(self):
        """Opposite vectors should have negative similarity"""
        vec_a = [1.0, 1.0, 1.0, 1.0]
        vec_b = [-1.0, -1.0, -1.0, -1.0]

        similarity = VectorSimilarity.cosine_similarity(vec_a, vec_b)

        self.assertAlmostEqual(similarity, -1.0)

    def test_dimension_mismatch(self):
        """Mismatched dimensions should return 0.0"""
        vec_a = [1.0, 0.5]
        vec_b = [1.0, 0.5, 0.3]

        similarity = VectorSimilarity.cosine_similarity(vec_a, vec_b)

        self.assertEqual(similarity, 0.0)

    def test_zero_vector(self):
        """Zero vector should return 0.0"""
        vec_a = [0.0, 0.0, 0.0]
        vec_b = [1.0, 1.0, 1.0]

        similarity = VectorSimilarity.cosine_similarity(vec_a, vec_b)

        self.assertEqual(similarity, 0.0)


class TestVectorSchema(unittest.TestCase):

    def test_schema_completeness(self):
        """Schema should document all dimensions"""
        schema = GameVectorGenerator.get_schema()

        total_dimensions = (
            schema["mechanics"]["count"]
            + schema["categories"]["count"]
            + len(schema["numeric_features"]["features"])
            + len(schema["binary_features"]["features"])
        )

        self.assertEqual(total_dimensions, schema["total_dimensions"])
        self.assertEqual(total_dimensions, VECTOR_DIMENSIONS)


if __name__ == "__main__":
    unittest.main()
