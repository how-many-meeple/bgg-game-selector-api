import time
import unittest
from unittest.mock import Mock, patch

import pytest
from botocore.exceptions import ClientError
from moto import mock_aws

from boardgame.request_cache import (
    CacheRequestBackendDynamoDB,
    CacheRequestBackendMemory,
)


class TestCacheRequestBackendMemory(unittest.TestCase):
    def setUp(self):
        self.cache = CacheRequestBackendMemory(ttl=2)

    def test_get_nonexistent_key_returns_none(self):
        result = self.cache.get("nonexistent")
        assert result is None

    def test_set_and_get_returns_correct_value(self):
        self.cache.set("key1", "value1")
        result = self.cache.get("key1")
        assert result == "value1"

    def test_expired_key_returns_none(self):
        self.cache.set("key1", "value1")
        time.sleep(2.1)
        result = self.cache.get("key1")
        assert result is None

    def test_delete_removes_key(self):
        self.cache.set("key1", "value1")
        self.cache.delete("key1")
        result = self.cache.get("key1")
        assert result is None

    def test_clear_removes_all_keys(self):
        self.cache.set("key1", "value1")
        self.cache.set("key2", "value2")
        self.cache.clear()
        assert self.cache.get("key1") is None
        assert self.cache.get("key2") is None

    def test_multiple_keys_stored_independently(self):
        self.cache.set("key1", "value1")
        self.cache.set("key2", "value2")
        assert self.cache.get("key1") == "value1"
        assert self.cache.get("key2") == "value2"

    def test_overwrite_existing_key(self):
        self.cache.set("key1", "value1")
        self.cache.set("key1", "value2")
        result = self.cache.get("key1")
        assert result == "value2"

    def test_delete_nonexistent_key_no_error(self):
        self.cache.delete("nonexistent")

    def test_boundary_ttl_zero_seconds(self):
        cache = CacheRequestBackendMemory(ttl=0)
        cache.set("key1", "value1")
        time.sleep(0.1)
        result = cache.get("key1")
        assert result is None

    def test_performance_bulk_operations(self):
        start = time.time()
        for i in range(1000):
            self.cache.set(f"key{i}", f"value{i}")
        set_time = time.time() - start

        start = time.time()
        for i in range(1000):
            self.cache.get(f"key{i}")
        get_time = time.time() - start

        assert set_time < 1.0
        assert get_time < 1.0


@mock_aws
class TestCacheRequestBackendDynamoDB(unittest.TestCase):
    def setUp(self):
        import boto3

        self.dynamodb = boto3.resource("dynamodb", region_name="us-east-1")
        self.table = self.dynamodb.create_table(
            TableName="test-request-cache",
            KeySchema=[{"AttributeName": "id", "KeyType": "HASH"}],
            AttributeDefinitions=[{"AttributeName": "id", "AttributeType": "S"}],
            BillingMode="PAY_PER_REQUEST",
        )
        self.cache = CacheRequestBackendDynamoDB(
            table_name="test-request-cache", ttl=1, region="us-east-1"
        )

    def test_get_nonexistent_key_returns_none(self):
        result = self.cache.get("nonexistent")
        assert result is None

    def test_set_and_get_returns_correct_value(self):
        self.cache.set("key1", "value1")
        result = self.cache.get("key1")
        assert result == "value1"

    def test_key_prefixed_with_request(self):
        self.cache.set("key1", "value1")
        response = self.table.get_item(Key={"id": "request_key1"})
        assert "Item" in response
        assert response["Item"]["data"] == "value1"

    def test_expired_key_returns_none(self):
        self.cache.set("key1", "value1")
        time.sleep(1.1)
        result = self.cache.get("key1")
        assert result is None

    def test_ttl_attribute_is_set(self):
        current_time = int(time.time())
        self.cache.set("key1", "value1")
        response = self.table.get_item(Key={"id": "request_key1"})
        ttl = response["Item"]["ttl"]
        assert ttl > current_time
        assert ttl <= current_time + 3

    def test_delete_removes_key(self):
        self.cache.set("key1", "value1")
        self.cache.delete("key1")
        result = self.cache.get("key1")
        assert result is None

    def test_multiple_keys_stored_independently(self):
        self.cache.set("key1", "value1")
        self.cache.set("key2", "value2")
        assert self.cache.get("key1") == "value1"
        assert self.cache.get("key2") == "value2"

    def test_overwrite_existing_key(self):
        self.cache.set("key1", "value1")
        self.cache.set("key1", "value2")
        result = self.cache.get("key1")
        assert result == "value2"

    def test_delete_nonexistent_key_no_error(self):
        self.cache.delete("nonexistent")

    def test_get_handles_client_error_gracefully(self):
        with patch.object(self.cache.table, "get_item") as mock_get:
            mock_get.side_effect = ClientError(
                {"Error": {"Code": "500", "Message": "Internal Server Error"}},
                "GetItem",
            )
            result = self.cache.get("key1")
            assert result is None

    def test_set_handles_client_error_gracefully(self):
        with patch.object(self.cache.table, "put_item") as mock_put:
            mock_put.side_effect = ClientError(
                {"Error": {"Code": "500", "Message": "Internal Server Error"}},
                "PutItem",
            )
            self.cache.set("key1", "value1")

    def test_delete_handles_client_error_gracefully(self):
        with patch.object(self.cache.table, "delete_item") as mock_delete:
            mock_delete.side_effect = ClientError(
                {"Error": {"Code": "500", "Message": "Internal Server Error"}},
                "DeleteItem",
            )
            self.cache.delete("key1")

    def test_clear_does_nothing(self):
        self.cache.set("key1", "value1")
        self.cache.clear()
        result = self.cache.get("key1")
        assert result == "value1"

    def test_performance_bulk_operations(self):
        start = time.time()
        for i in range(100):
            self.cache.set(f"key{i}", f"value{i}")
        set_time = time.time() - start

        start = time.time()
        for i in range(100):
            self.cache.get(f"key{i}")
        get_time = time.time() - start

        assert set_time < 10.0
        assert get_time < 10.0

    def test_lazy_initialization_of_dynamodb_connection(self):
        cache = CacheRequestBackendDynamoDB(
            table_name="test-request-cache", ttl=60, region="us-east-1"
        )
        assert cache._dynamodb is None
        assert cache._table is None
        cache.set("key1", "value1")
        assert cache._dynamodb is not None
        assert cache._table is not None

    def test_large_value_storage(self):
        large_value = "x" * 10000
        self.cache.set("large_key", large_value)
        result = self.cache.get("large_key")
        assert result == large_value

    def test_special_characters_in_key(self):
        self.cache.set("key:with:colons", "value1")
        self.cache.set("key/with/slashes", "value2")
        assert self.cache.get("key:with:colons") == "value1"
        assert self.cache.get("key/with/slashes") == "value2"
