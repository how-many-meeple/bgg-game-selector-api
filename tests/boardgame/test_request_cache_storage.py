import os
import boto3
import pytest
import requests_cache
import tempfile
from moto import mock_aws

from boardgame.request_cache import (
    CacheRequestBackendDynamoDB,
    CacheRequestBackendMemory,
    CacheRequestBackendSQLite,
    CacheRequestDynamoDBStorage,
)


def _create_test_table(table_name="test-table", region="us-east-1"):
    dynamodb = boto3.resource("dynamodb", region_name=region)
    dynamodb.create_table(
        TableName=table_name,
        BillingMode="PAY_PER_REQUEST",
        AttributeDefinitions=[{"AttributeName": "id", "AttributeType": "S"}],
        KeySchema=[{"AttributeName": "id", "KeyType": "HASH"}],
    )


class TestCacheRequestDynamoDBStorage:
    @mock_aws
    def test_storage_inherits_from_dict_storage(self):
        from requests_cache.backends.base import DictStorage

        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        assert isinstance(storage, DictStorage)

    @mock_aws
    def test_setitem_and_getitem_round_trip(self):
        _create_test_table()
        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        value = {"response_data": "some cached response", "status": 200}
        storage["my-key"] = value
        assert storage["my-key"] == value

    @mock_aws
    def test_getitem_raises_keyerror_for_missing_key(self):
        _create_test_table()
        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        with pytest.raises(KeyError):
            _ = storage["nonexistent"]

    @mock_aws
    def test_contains_returns_true_for_stored_key(self):
        _create_test_table()
        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        storage["present"] = {"data": 42}
        assert "present" in storage

    @mock_aws
    def test_contains_returns_false_for_missing_key(self):
        _create_test_table()
        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        assert "absent" not in storage

    @mock_aws
    def test_delitem_removes_key(self):
        _create_test_table()
        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        storage["to-delete"] = {"data": "gone"}
        del storage["to-delete"]
        assert "to-delete" not in storage


class TestCacheRequestBackendIntegration:
    @mock_aws
    def test_memory_backend_has_cached_session(self):
        backend = CacheRequestBackendMemory(3600)
        assert hasattr(backend, "cache")
        assert isinstance(backend.cache, requests_cache.CachedSession)

    @mock_aws
    def test_dynamodb_backend_has_cached_session(self):
        backend = CacheRequestBackendDynamoDB("test-table", 3600, "us-east-1")
        assert hasattr(backend, "cache")
        assert isinstance(backend.cache, requests_cache.CachedSession)

    @mock_aws
    def test_dynamodb_backend_uses_custom_storage(self):
        backend = CacheRequestBackendDynamoDB("test-table", 3600, "us-east-1")
        assert isinstance(backend.cache.cache.responses, CacheRequestDynamoDBStorage)

    def test_sqlite_backend_has_cached_session(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = os.path.join(tmpdir, "test_cache.sqlite")
            backend = CacheRequestBackendSQLite(3600, db_path)
            assert hasattr(backend, "cache")
            assert isinstance(backend.cache, requests_cache.CachedSession)
            backend.cache.close()

    def test_sqlite_backend_persists_across_instances(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = os.path.join(tmpdir, "test_cache.sqlite")
            backend1 = CacheRequestBackendSQLite(3600, db_path)
            backend1.cache.get("https://example.com/test")
            backend1.cache.close()

            backend2 = CacheRequestBackendSQLite(3600, db_path)
            assert hasattr(backend2, "cache")
            assert isinstance(backend2.cache, requests_cache.CachedSession)
            backend2.cache.close()
