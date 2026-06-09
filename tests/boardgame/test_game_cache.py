"""
Tests for GameCache implementations following RightBICEP principles:
- Right: Are the results right?
- Boundary: Are all the boundary conditions correct?
- Inverse: Can you check inverse relationships?
- Cross-check: Can you cross-check results using other means?
- Error: Can you force error conditions?
- Performance: Are there performance characteristics?
"""

import json
import os
import time
import unittest
from datetime import datetime, timedelta
from unittest.mock import Mock, patch, MagicMock

from boardgamegeek.objects.games import BoardGame

from boardgame.game_cache import SQLiteGameCache


class TestSQLiteGameCache(unittest.TestCase):
    """Tests for SQLite-based game cache"""

    def setUp(self):
        """Set up test fixtures"""
        self.test_db = "test_cache.db"
        self.cache_length = 60  # 60 seconds
        self.cache = SQLiteGameCache(
            cache_length=self.cache_length, cache_file=self.test_db
        )

        # Create test game data
        self.game_data = {
            "id": 174430,
            "name": "Gloomhaven",
            "yearpublished": 2017,
            "minplayers": 1,
            "maxplayers": 4,
            "playingtime": 120,
            "minplaytime": 60,
            "maxplaytime": 120,
            "minage": 14,
            "rating_average": 8.8,
            "rating_average_weight": 3.86,
            "stats": {},
        }
        self.test_game = BoardGame(self.game_data)

    def tearDown(self):
        """Clean up test database"""
        # Close the database connection first
        if hasattr(self.cache, "_conn") and self.cache._conn:
            self.cache._conn.close()
        if os.path.exists(self.test_db):
            os.remove(self.test_db)

    # RIGHT: Are the results right?
    def test_save_and_load_game_returns_correct_data(self):
        """Test that saved game can be loaded with correct data"""
        self.cache.save(self.test_game)
        loaded_game = self.cache.load(174430)

        self.assertIsNotNone(loaded_game)
        self.assertEqual(loaded_game.id, 174430)
        self.assertEqual(loaded_game.name, "Gloomhaven")
        self.assertEqual(loaded_game.min_players, 1)
        self.assertEqual(loaded_game.max_players, 4)

    def test_load_nonexistent_game_returns_none(self):
        """Test that loading a game that doesn't exist returns None"""
        loaded_game = self.cache.load(999999)
        self.assertIsNone(loaded_game)

    # BOUNDARY: Are all boundary conditions correct?
    def test_save_game_with_minimal_data(self):
        """Test saving game with minimal required data"""
        minimal_game_data = {"id": 1, "name": "Test Game", "stats": {}}
        minimal_game = BoardGame(minimal_game_data)

        self.cache.save(minimal_game)
        loaded_game = self.cache.load(1)

        self.assertIsNotNone(loaded_game)
        self.assertEqual(loaded_game.id, 1)
        self.assertEqual(loaded_game.name, "Test Game")

    def test_save_game_with_zero_id(self):
        """Test boundary case with game ID of 0"""
        game_data = {"id": 0, "name": "Zero ID Game", "stats": {}}
        game = BoardGame(game_data)

        self.cache.save(game)
        loaded_game = self.cache.load(0)

        self.assertIsNotNone(loaded_game)
        self.assertEqual(loaded_game.id, 0)

    def test_save_duplicate_game_does_not_overwrite(self):
        """Test that saving the same game twice doesn't create duplicates"""
        self.cache.save(self.test_game)

        # Modify game data and try to save again
        modified_data = self.game_data.copy()
        modified_data["name"] = "Modified Name"
        modified_game = BoardGame(modified_data)
        self.cache.save(modified_game)

        # Should still have original data
        loaded_game = self.cache.load(174430)
        self.assertEqual(loaded_game.name, "Gloomhaven")

    def test_cache_timeout_removes_old_entries(self):
        """Test that timeout_cache removes expired entries"""
        # Create cache with very short timeout
        short_cache = SQLiteGameCache(cache_length=1, cache_file="short_test_cache.db")

        try:
            short_cache.save(self.test_game)
            self.assertIsNotNone(short_cache.load(174430))

            # Wait for cache to expire
            time.sleep(2)
            short_cache.timeout_cache()

            # Should be gone now
            self.assertIsNone(short_cache.load(174430))
        finally:
            if hasattr(short_cache, "_conn") and short_cache._conn:
                short_cache._conn.close()
            if os.path.exists("short_test_cache.db"):
                os.remove("short_test_cache.db")

    def test_cache_timeout_preserves_recent_entries(self):
        """Test that timeout_cache keeps non-expired entries"""
        long_cache = SQLiteGameCache(cache_length=3600, cache_file="long_test_cache.db")

        try:
            long_cache.save(self.test_game)
            long_cache.timeout_cache()

            # Should still be there
            loaded_game = long_cache.load(174430)
            self.assertIsNotNone(loaded_game)
        finally:
            if hasattr(long_cache, "_conn") and long_cache._conn:
                long_cache._conn.close()
            if os.path.exists("long_test_cache.db"):
                os.remove("long_test_cache.db")

    # INVERSE: Can you check inverse relationships?
    def test_save_then_load_is_inverse_of_original(self):
        """Test that save followed by load returns equivalent data"""
        original_data = self.test_game.data()
        self.cache.save(self.test_game)
        loaded_game = self.cache.load(174430)
        loaded_data = loaded_game.data()

        # Should have same keys
        self.assertEqual(set(original_data.keys()), set(loaded_data.keys()))

        # Should have same values for all keys
        for key in original_data.keys():
            self.assertEqual(
                original_data[key], loaded_data[key], f"Mismatch on key: {key}"
            )

    # CROSS-CHECK: Can you cross-check results using other means?
    def test_saved_game_exists_in_database(self):
        """Cross-check that saved game actually exists in SQLite database"""
        self.cache.save(self.test_game)

        # Query database directly
        cursor = self.cache._conn.cursor()
        cursor.execute("SELECT id, data FROM cached_game WHERE id=?", (str(174430),))
        result = cursor.fetchone()

        self.assertIsNotNone(result)
        self.assertEqual(result[0], str(174430))

        # Verify JSON data
        stored_data = json.loads(result[1])
        self.assertEqual(stored_data["name"], "Gloomhaven")

    def test_multiple_games_stored_correctly(self):
        """Cross-check that multiple games can be stored and retrieved independently"""
        game1_data = {"id": 1, "name": "Game One", "stats": {}}
        game2_data = {"id": 2, "name": "Game Two", "stats": {}}
        game3_data = {"id": 3, "name": "Game Three", "stats": {}}

        game1 = BoardGame(game1_data)
        game2 = BoardGame(game2_data)
        game3 = BoardGame(game3_data)

        self.cache.save(game1)
        self.cache.save(game2)
        self.cache.save(game3)

        # Verify all three exist
        self.assertEqual(self.cache.load(1).name, "Game One")
        self.assertEqual(self.cache.load(2).name, "Game Two")
        self.assertEqual(self.cache.load(3).name, "Game Three")

        # Cross-check count in database
        cursor = self.cache._conn.cursor()
        cursor.execute("SELECT COUNT(*) FROM cached_game")
        count = cursor.fetchone()[0]
        self.assertEqual(count, 3)

    # ERROR: Can you force error conditions?
    def test_load_with_invalid_id_type(self):
        """Test error handling with invalid ID type"""
        # Should handle gracefully
        result = self.cache.load(None)
        self.assertIsNone(result)

    def test_load_with_corrupted_data(self):
        """Test handling of corrupted JSON data in cache"""
        # Insert corrupted data directly
        cursor = self.cache._conn.cursor()
        cursor.execute(
            "INSERT INTO cached_game (id, data) VALUES (?, ?)",
            (str(999), "corrupted json {{{"),
        )
        self.cache._conn.commit()

        # Should handle gracefully and return None
        result = self.cache.load(999)
        self.assertIsNone(result)

    @patch("boardgame.game_cache.log")
    def test_save_logs_errors_on_exception(self, mock_log):
        """Test that errors during save are logged"""
        # Close connection to force error
        self.cache._conn.close()

        # Try to save (should fail)
        self.cache.save(self.test_game)

        # Should have logged error
        mock_log.error.assert_called()

    @patch("boardgame.game_cache.log")
    def test_load_logs_errors_on_exception(self, mock_log):
        """Test that errors during load are logged"""
        # Close connection to force error
        self.cache._conn.close()

        # Try to load (should fail)
        result = self.cache.load(174430)

        # Should return None and log error
        self.assertIsNone(result)
        mock_log.error.assert_called()

    # PERFORMANCE: Are there performance characteristics?
    def test_bulk_save_performance(self):
        """Test that bulk saving games completes in reasonable time"""
        start_time = time.time()

        # Save 100 games
        for i in range(100):
            game_data = {"id": i, "name": f"Game {i}", "stats": {}}
            game = BoardGame(game_data)
            self.cache.save(game)

        elapsed = time.time() - start_time

        # Should complete in under 5 seconds
        self.assertLess(elapsed, 5.0, f"Bulk save took {elapsed:.2f}s, expected < 5s")

    def test_bulk_load_performance(self):
        """Test that bulk loading games completes in reasonable time"""
        # First save 100 games
        for i in range(100):
            game_data = {"id": i, "name": f"Game {i}", "stats": {}}
            game = BoardGame(game_data)
            self.cache.save(game)

        start_time = time.time()

        # Load all 100 games
        for i in range(100):
            self.cache.load(i)

        elapsed = time.time() - start_time

        # Should complete in under 2 seconds
        self.assertLess(elapsed, 2.0, f"Bulk load took {elapsed:.2f}s, expected < 2s")


if __name__ == "__main__":
    unittest.main()
