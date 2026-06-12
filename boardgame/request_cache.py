import logging
import pickle
import time
from typing import Optional

import boto3
import requests_cache
from requests_cache.backends.base import DictStorage
from botocore.exceptions import ClientError

logger = logging.getLogger(__name__)


class CacheRequestDynamoDBStorage(DictStorage):
    """
    Dict-like storage wrapper for DynamoDB table.

    Provides dict-like interface for requests_cache to store HTTP responses in DynamoDB.
    """

    def __init__(self, table_name: str, region: str, ttl: int):
        super().__init__()
        self.table_name = table_name
        self.region = region
        self.ttl = ttl
        self._dynamodb = None
        self._table = None

    @property
    def dynamodb(self):
        if self._dynamodb is None:
            self._dynamodb = boto3.resource("dynamodb", region_name=self.region)
        return self._dynamodb

    @property
    def table(self):
        if self._table is None:
            self._table = self.dynamodb.Table(self.table_name)
        return self._table

    def __getitem__(self, key):
        try:
            response = self.table.get_item(Key={"id": f"request_{key}"})
            item = response.get("Item")
            if not item:
                raise KeyError(key)
            ttl = item.get("ttl", 0)
            if ttl > 0 and ttl < int(time.time()):
                raise KeyError(key)
            return pickle.loads(item["data"].value)
        except ClientError:
            raise KeyError(key)

    def __setitem__(self, key, value):
        try:
            ttl_timestamp = int(time.time()) + self.ttl
            self.table.put_item(
                Item={
                    "id": f"request_{key}",
                    "data": boto3.dynamodb.types.Binary(pickle.dumps(value)),
                    "ttl": ttl_timestamp,
                }
            )
        except ClientError as e:
            logger.error(f"Error setting cache key {key}: {e}")

    def __delitem__(self, key):
        try:
            self.table.delete_item(Key={"id": f"request_{key}"})
        except ClientError as e:
            logger.error(f"Error deleting cache key {key}: {e}")

    def __contains__(self, key):
        try:
            self[key]
            return True
        except KeyError:
            return False

    def get(self, key, default=None):
        try:
            return self[key]
        except KeyError:
            return default

    def pop(self, key, default=None):
        try:
            value = self[key]
            del self[key]
            return value
        except KeyError:
            return default

    def clear(self):
        pass


class CacheRequestBackendDynamoDB:
    """
    DynamoDB-backed cache for BGG API requests (Lambda-compatible).

    Provides requests_cache.CachedSession via .cache attribute for BGG library compatibility.
    """

    def __init__(self, table_name: str, ttl: int, region: str = "us-east-1"):
        self.table_name = table_name
        self.ttl = ttl
        self.region = region

        dynamo_storage = CacheRequestDynamoDBStorage(table_name, region, ttl)

        self.cache = requests_cache.CachedSession(
            backend="memory",
            expire_after=ttl,
            allowable_codes=(200,),
        )
        self.cache.cache.responses = dynamo_storage


class CacheRequestBackendMemory:
    """
    Memory-backed cache for BGG API requests (local dev and Lambda).

    Provides requests_cache.CachedSession via .cache attribute for BGG library compatibility.
    """

    def __init__(self, ttl: int):
        self.ttl = ttl
        # BGG library expects .cache to be a requests_cache.CachedSession
        self.cache = requests_cache.CachedSession(
            backend="memory",
            expire_after=ttl,
            allowable_codes=(200,),
        )


class CacheRequestBackendSQLite:
    """
    SQLite-backed cache for BGG API requests (local dev, persistent across restarts).

    Provides requests_cache.CachedSession via .cache attribute for BGG library compatibility.
    """

    def __init__(self, ttl: int, db_path: str = "bgg_request_cache.sqlite"):
        self.ttl = ttl
        self.db_path = db_path
        # BGG library expects .cache to be a requests_cache.CachedSession
        self.cache = requests_cache.CachedSession(
            cache_name=db_path.replace(".sqlite", ""),
            backend="sqlite",
            expire_after=ttl,
            allowable_codes=(200,),
        )
