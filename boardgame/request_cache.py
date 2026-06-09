import logging
import time
from typing import Optional

import boto3
import requests_cache
from botocore.exceptions import ClientError

logger = logging.getLogger(__name__)


class CacheRequestDynamoDBStorage:
    """
    Dict-like storage wrapper for DynamoDB table.

    Provides dict-like interface for requests_cache to store HTTP responses in DynamoDB.
    """
    def __init__(self, table_name: str, region: str, ttl: int):
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
            return item.get("data")
        except ClientError:
            raise KeyError(key)

    def __setitem__(self, key, value):
        try:
            ttl_timestamp = int(time.time()) + self.ttl
            self.table.put_item(
                Item={"id": f"request_{key}", "data": value, "ttl": ttl_timestamp}
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

        # Create dict-like storage wrapper for DynamoDB
        dynamo_storage = CacheRequestDynamoDBStorage(table_name, region, ttl)

        # BGG library expects .cache to be a requests_cache.CachedSession
        # Use our DynamoDB dict as the backend storage
        self.cache = requests_cache.CachedSession(
            backend="memory",  # requests_cache will use memory internally
            expire_after=ttl,
            allowable_codes=(200,),
        )
        # Override the cache storage with our DynamoDB storage
        self.cache.cache.responses = dynamo_storage

    @property
    def dynamodb(self):
        if self._dynamodb is None:
            self._dynamodb = boto3.resource("dynamodb", region_name=self.region)
        return self._dynamodb

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
