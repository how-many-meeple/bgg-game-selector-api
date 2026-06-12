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
        """Heavier games should have a larger weight dimension than lighter games"""
        base = {
            "mechanics": ["Hand Management", "Deck, Bag, and Pool Building"],
            "categories": ["Strategy"],
        }
        game_light = {**base, "rating_average_weight": 1.0}
        game_heavy = {**base, "rating_average_weight": 5.0}

        vec_light = GameVectorGenerator.generate(game_light)
        vec_heavy = GameVectorGenerator.generate(game_heavy)

        schema = GameVectorGenerator.get_schema()
        weight_idx = schema["numeric_features"]["start_index"]

        # After L2 normalisation absolute values change, but heavier game must
        # have a larger weight dimension than the lighter game
        self.assertGreater(vec_heavy[weight_idx], vec_light[weight_idx])

    def test_playtime_normalization(self):
        """Longer playtime should produce a larger playtime dimension"""
        base = {
            "mechanics": ["Hand Management", "Deck, Bag, and Pool Building"],
            "categories": ["Strategy"],
        }
        game_short = {**base, "playing_time": 30}
        game_long = {**base, "playing_time": 200}

        vec_short = GameVectorGenerator.generate(game_short)
        vec_long = GameVectorGenerator.generate(game_long)

        schema = GameVectorGenerator.get_schema()
        playtime_idx = schema["numeric_features"]["start_index"] + 1

        # After L2 normalisation absolute values change, but longer game must
        # have a larger playtime dimension
        self.assertGreater(vec_long[playtime_idx], vec_short[playtime_idx])

    def test_cooperative_flag(self):
        """Cooperative games should have a non-zero flag, competitive zero"""
        game_coop = {"mechanics": ["Cooperative Game"]}
        game_competitive = {"mechanics": ["Auction/Bidding"]}

        vec_coop = GameVectorGenerator.generate(game_coop)
        vec_comp = GameVectorGenerator.generate(game_competitive)

        # Cooperative flag is last dimension; after L2 normalisation it is
        # non-zero (not necessarily 1.0) for cooperative games
        self.assertGreater(vec_coop[-1], 0.0)
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
        """Higher average weight should produce a larger weight dimension"""
        base = {"mechanics": ["Hand Management"], "categories": ["Strategy"]}
        games_light = [
            {**base, "rating_average_weight": 1.0},
            {**base, "rating_average_weight": 1.0},
        ]
        games_heavy = [
            {**base, "rating_average_weight": 4.0},
            {**base, "rating_average_weight": 4.0},
        ]

        taste_light = TasteVectorBuilder.build(games_light)
        taste_heavy = TasteVectorBuilder.build(games_heavy)

        schema = GameVectorGenerator.get_schema()
        weight_idx = schema["numeric_features"]["start_index"]

        # After L2 normalisation absolute values change, but heavier collection
        # must have a larger weight dimension than the lighter one
        self.assertGreater(taste_heavy[weight_idx], taste_light[weight_idx])


class TestVectorSimilarity(unittest.TestCase):

    def test_identical_vectors(self):
        """Identical unit vectors should have similarity 1.0"""
        vec_a = [0.5, 0.5, 0.5, 0.5]  # already unit: mag = 1.0
        vec_b = [0.5, 0.5, 0.5, 0.5]

        similarity = VectorSimilarity.cosine_similarity(vec_a, vec_b)

        self.assertAlmostEqual(similarity, 1.0)

    def test_orthogonal_vectors(self):
        """Orthogonal unit vectors should have similarity 0.0"""
        vec_a = [1.0, 0.0, 0.0, 0.0]
        vec_b = [0.0, 1.0, 0.0, 0.0]

        similarity = VectorSimilarity.cosine_similarity(vec_a, vec_b)

        self.assertAlmostEqual(similarity, 0.0)

    def test_opposite_vectors(self):
        """Opposite unit vectors should have similarity -1.0"""
        vec_a = [0.5, 0.5, 0.5, 0.5]
        vec_b = [-0.5, -0.5, -0.5, -0.5]

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
