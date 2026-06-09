import logging
import time
from typing import Optional

import boto3
from botocore.exceptions import ClientError

logger = logging.getLogger(__name__)


class CacheRequestBackendDynamoDB:
    def __init__(self, table_name: str, ttl: int, region: str = "us-east-1"):
        self.table_name = table_name
        self.ttl = ttl
        self.region = region
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

    def get(self, key: str) -> Optional[str]:
        try:
            response = self.table.get_item(Key={"id": f"request_{key}"})
            item = response.get("Item")

            if not item:
                return None

            ttl = item.get("ttl", 0)
            if ttl > 0 and ttl < int(time.time()):
                return None

            return item.get("data")

        except ClientError as e:
            logger.error(f"Error getting cache key {key}: {e}")
            return None

    def set(self, key: str, value: str):
        try:
            ttl_timestamp = int(time.time()) + self.ttl
            self.table.put_item(
                Item={"id": f"request_{key}", "data": value, "ttl": ttl_timestamp}
            )
        except ClientError as e:
            logger.error(f"Error setting cache key {key}: {e}")

    def delete(self, key: str):
        try:
            self.table.delete_item(Key={"id": f"request_{key}"})
        except ClientError as e:
            logger.error(f"Error deleting cache key {key}: {e}")

    def clear(self):
        pass


class CacheRequestBackendMemory:
    def __init__(self, ttl: int):
        self.ttl = ttl
        self._cache = {}
        self._timestamps = {}

    def get(self, key: str) -> Optional[str]:
        if key not in self._cache:
            return None

        if self._timestamps.get(key, 0) < time.time():
            del self._cache[key]
            del self._timestamps[key]
            return None

        return self._cache[key]

    def set(self, key: str, value: str):
        self._cache[key] = value
        self._timestamps[key] = time.time() + self.ttl

    def delete(self, key: str):
        self._cache.pop(key, None)
        self._timestamps.pop(key, None)

    def clear(self):
        self._cache.clear()
        self._timestamps.clear()
