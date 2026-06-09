import os
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


class TestCacheRequestDynamoDBStorage:
    @mock_aws
    def test_storage_inherits_from_dict_storage(self):
        from requests_cache.backends.base import DictStorage

        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        assert isinstance(storage, DictStorage)

    @mock_aws
    def test_storage_has_serializer_attribute(self):
        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        assert hasattr(storage, "serializer")

    @mock_aws
    def test_storage_has_serialize_method(self):
        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        assert hasattr(storage, "serialize")
        assert callable(storage.serialize)

    @mock_aws
    def test_storage_has_deserialize_method(self):
        storage = CacheRequestDynamoDBStorage("test-table", "us-east-1", 3600)
        assert hasattr(storage, "deserialize")
        assert callable(storage.deserialize)


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
