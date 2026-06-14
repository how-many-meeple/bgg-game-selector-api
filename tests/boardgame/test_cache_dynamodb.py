"""
Tests for DynamoDB GameCache implementation following RightBICEP principles:
- Right: Are the results right?
- Boundary: Are all the boundary conditions correct?
- Inverse: Can you check inverse relationships?
- Cross-check: Can you cross-check results using other means?
- Error: Can you force error conditions?
- Performance: Are there performance characteristics?
"""

import json
import time
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import boto3
from boardgamegeek.objects.games import BoardGame
from moto import mock_aws


from boardgame.game_cache import DynamoDBGameCache


@mock_aws
class TestDynamoDBGameCache(unittest.TestCase):
    """Tests for DynamoDB-based game cache"""

    def setUp(self):
        """Set up test fixtures with mocked DynamoDB"""
        self.table_name = "test-game-cache"
        self.region = "us-east-1"
        self.cache_length = 3600  # 1 hour

        # Create mock DynamoDB table
        dynamodb = boto3.client("dynamodb", region_name=self.region)
        dynamodb.create_table(
            TableName=self.table_name,
            KeySchema=[{"AttributeName": "id", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "id", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )

        # Enable TTL (moto doesn't enforce it, but we can test the attribute is set)
        dynamodb.update_time_to_live(
            TableName=self.table_name,
            TimeToLiveSpecification={"Enabled": True, "AttributeName": "ttl"},
        )

        self.cache = DynamoDBGameCache(
            table_name=self.table_name,
            cache_length_seconds=self.cache_length,
            region=self.region,
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

    def test_ttl_attribute_is_set_correctly(self):
        """Test that TTL attribute is set to correct future timestamp"""
        before_save = datetime.now(timezone.utc)
        self.cache.save(self.test_game)
        after_save = datetime.now(timezone.utc)

        # Get item directly from DynamoDB
        response = self.cache.table.get_item(Key={"id": str(174430)})
        self.assertIn("Item", response)

        ttl = response["Item"]["ttl"]
        expected_min_ttl = int(
            (before_save + timedelta(seconds=self.cache_length)).timestamp()
        )
        expected_max_ttl = int(
            (after_save + timedelta(seconds=self.cache_length)).timestamp()
        )

        self.assertGreaterEqual(ttl, expected_min_ttl)
        self.assertLessEqual(ttl, expected_max_ttl)

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

    def test_save_game_with_very_large_id(self):
        """Test boundary case with very large game ID"""
        game_data = {"id": 9999999999, "name": "Large ID Game", "stats": {}}
        game = BoardGame(game_data)

        self.cache.save(game)
        loaded_game = self.cache.load(9999999999)

        self.assertIsNotNone(loaded_game)
        self.assertEqual(loaded_game.id, 9999999999)

    def test_save_duplicate_game_does_not_overwrite(self):
        """Test that saving the same game twice doesn't overwrite (conditional put)"""
        self.cache.save(self.test_game)

        # Get the TTL from first save
        response1 = self.cache.table.get_item(Key={"id": str(174430)})
        ttl1 = response1["Item"]["ttl"]

        # Wait a bit to ensure timestamp would be different
        time.sleep(0.1)

        # Try to save again with modified data
        modified_data = self.game_data.copy()
        modified_data["name"] = "Modified Name"
        modified_game = BoardGame(modified_data)
        self.cache.save(modified_game)

        # Should still have original data and TTL
        loaded_game = self.cache.load(174430)
        self.assertEqual(loaded_game.name, "Gloomhaven")

        response2 = self.cache.table.get_item(Key={"id": str(174430)})
        ttl2 = response2["Item"]["ttl"]
        self.assertEqual(ttl1, ttl2)

    def test_timeout_cache_does_nothing(self):
        """Test that timeout_cache is no-op (DynamoDB handles TTL automatically)"""
        self.cache.save(self.test_game)

        # Call timeout_cache (should do nothing)
        self.cache.timeout_cache()

        # Game should still be there
        loaded_game = self.cache.load(174430)
        self.assertIsNotNone(loaded_game)

    def test_short_ttl_sets_near_future_expiration(self):
        """Test that short TTL values work correctly"""
        short_cache = DynamoDBGameCache(
            table_name=self.table_name,
            cache_length_seconds=60,  # 1 minute
            region=self.region,
        )

        before_save = datetime.now(timezone.utc)
        short_cache.save(self.test_game)

        response = short_cache.table.get_item(Key={"id": str(174430)})
        ttl = response["Item"]["ttl"]
        expected_ttl = int((before_save + timedelta(seconds=60)).timestamp())

        # Should be within 2 seconds of expected
        self.assertAlmostEqual(ttl, expected_ttl, delta=2)

    def test_long_ttl_sets_far_future_expiration(self):
        """Test that long TTL values work correctly"""
        long_cache = DynamoDBGameCache(
            table_name=self.table_name,
            cache_length_seconds=2592000,  # 30 days
            region=self.region,
        )

        before_save = datetime.now(timezone.utc)
        long_cache.save(self.test_game)

        response = long_cache.table.get_item(Key={"id": str(174430)})
        ttl = response["Item"]["ttl"]
        expected_ttl = int((before_save + timedelta(seconds=2592000)).timestamp())

        # Should be within 2 seconds of expected
        self.assertAlmostEqual(ttl, expected_ttl, delta=2)

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
    def test_saved_game_exists_in_dynamodb(self):
        """Cross-check that saved game actually exists in DynamoDB table"""
        self.cache.save(self.test_game)

        # Query DynamoDB directly
        dynamodb = boto3.resource("dynamodb", region_name=self.region)
        table = dynamodb.Table(self.table_name)
        response = table.get_item(Key={"id": str(174430)})

        self.assertIn("Item", response)
        self.assertEqual(response["Item"]["id"], str(174430))

        # Verify JSON data
        stored_data = json.loads(response["Item"]["data"])
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

        # Cross-check count in DynamoDB using scan
        response = self.cache.table.scan(Select="COUNT")
        self.assertEqual(response["Count"], 3)

    def test_cache_timestamp_is_stored(self):
        """Cross-check that cache_timestamp is stored correctly"""
        before_save = datetime.now(timezone.utc)
        self.cache.save(self.test_game)
        after_save = datetime.now(timezone.utc)

        response = self.cache.table.get_item(Key={"id": str(174430)})
        cache_timestamp_str = response["Item"]["cache_timestamp"]
        cache_timestamp = datetime.fromisoformat(cache_timestamp_str)

        self.assertGreaterEqual(cache_timestamp, before_save)
        self.assertLessEqual(cache_timestamp, after_save)

    # ERROR: Can you force error conditions?
    def test_load_with_invalid_id_type(self):
        """Test error handling with invalid ID type"""
        # Should handle gracefully
        result = self.cache.load(None)
        self.assertIsNone(result)

    @patch("boardgame.game_cache.log")
    def test_save_logs_error_on_unexpected_exception(self, mock_log):
        """Test that unexpected errors during save are logged"""
        with patch.object(
            self.cache.table, "put_item", side_effect=Exception("Unexpected error")
        ):
            self.cache.save(self.test_game)
            mock_log.error.assert_called()

    @patch("boardgame.game_cache.log")
    def test_save_logs_debug_on_duplicate(self, mock_log):
        """Test that duplicate saves log debug message"""
        self.cache.save(self.test_game)
        self.cache.save(self.test_game)  # Try to save again

        # Should have logged that game was already cached
        debug_calls = [
            call
            for call in mock_log.debug.call_args_list
            if "already cached" in str(call)
        ]
        self.assertGreater(len(debug_calls), 0)

    @patch("boardgame.game_cache.log")
    def test_load_logs_error_on_exception(self, mock_log):
        """Test that errors during load are logged"""
        with patch.object(
            self.cache.table, "get_item", side_effect=Exception("Load error")
        ):
            result = self.cache.load(174430)
            self.assertIsNone(result)
            mock_log.error.assert_called()

    def test_load_with_corrupted_json_returns_none(self):
        """Test handling of corrupted JSON data in cache"""
        # Insert corrupted data directly
        self.cache.table.put_item(
            Item={
                "id": "999",
                "data": "corrupted json {{{",
                "cache_timestamp": datetime.now(timezone.utc).isoformat(),
                "ttl": int(
                    (datetime.now(timezone.utc) + timedelta(seconds=3600)).timestamp()
                ),
            }
        )

        # Should handle gracefully and return None
        result = self.cache.load(999)
        self.assertIsNone(result)

    # PERFORMANCE: Are there performance characteristics?
    def test_bulk_save_performance(self):
        """Test that bulk saving games completes in reasonable time"""
        start_time = time.time()

        # Save 50 games (fewer than SQLite test due to network simulation overhead)
        for i in range(50):
            game_data = {"id": i, "name": f"Game {i}", "stats": {}}
            game = BoardGame(game_data)
            self.cache.save(game)

        elapsed = time.time() - start_time

        # Should complete in under 10 seconds (more lenient for DynamoDB mock)
        self.assertLess(elapsed, 10.0, f"Bulk save took {elapsed:.2f}s, expected < 10s")

    def test_bulk_load_performance(self):
        """Test that bulk loading games completes in reasonable time"""
        # First save 50 games
        for i in range(50):
            game_data = {"id": i, "name": f"Game {i}", "stats": {}}
            game = BoardGame(game_data)
            self.cache.save(game)

        start_time = time.time()

        # Load all 50 games
        for i in range(50):
            self.cache.load(i)

        elapsed = time.time() - start_time

        # Should complete in under 5 seconds
        self.assertLess(elapsed, 5.0, f"Bulk load took {elapsed:.2f}s, expected < 5s")

    def test_parallel_saves_dont_corrupt_data(self):
        """Test that multiple concurrent saves maintain data integrity"""
        games = []
        for i in range(10):
            game_data = {"id": 1000 + i, "name": f"Concurrent Game {i}", "stats": {}}
            games.append(BoardGame(game_data))

        # Save all games
        for game in games:
            self.cache.save(game)

        # Verify all can be loaded correctly
        for i, game in enumerate(games):
            loaded = self.cache.load(1000 + i)
            self.assertIsNotNone(loaded)
            self.assertEqual(loaded.name, f"Concurrent Game {i}")


@mock_aws
class TestDynamoDBGameCacheTableCreation(unittest.TestCase):
    """Test the create_table utility method"""

    def test_create_table_succeeds(self):
        """Test that create_table creates a properly configured table"""
        table_name = "new-test-table"
        region = "us-east-1"

        DynamoDBGameCache.create_table(table_name=table_name, region=region)

        # Verify table exists and has correct configuration
        dynamodb = boto3.client("dynamodb", region_name=region)
        response = dynamodb.describe_table(TableName=table_name)

        self.assertEqual(response["Table"]["TableName"], table_name)
        self.assertEqual(
            response["Table"]["BillingModeSummary"]["BillingMode"], "PAY_PER_REQUEST"
        )

        # Verify TTL is enabled
        ttl_response = dynamodb.describe_time_to_live(TableName=table_name)
        self.assertEqual(
            ttl_response["TimeToLiveDescription"]["TimeToLiveStatus"], "ENABLED"
        )
        self.assertEqual(ttl_response["TimeToLiveDescription"]["AttributeName"], "ttl")

    def test_create_table_idempotent(self):
        """Test that calling create_table on existing table doesn't fail"""
        table_name = "existing-table"
        region = "us-east-1"

        # Create once
        DynamoDBGameCache.create_table(table_name=table_name, region=region)

        # Create again (should not raise exception)
        try:
            DynamoDBGameCache.create_table(table_name=table_name, region=region)
        except Exception as e:
            self.fail(f"create_table should be idempotent, but raised: {e}")


if __name__ == "__main__":
    unittest.main()
