import abc
import logging
import sqlite3
import time
from enum import StrEnum
from typing import Optional

from config import Config


class SourceType(StrEnum):
    COLLECTION = "collection"
    GEEKLIST = "geeklist"


log = logging.getLogger(__name__)

PENDING = "pending"
PROCESSING = "processing"
COMPLETED = "completed"
NOT_FOUND = "not_found"
FAILED = "failed"

_TTL_SECONDS = {
    PENDING: 15 * 60,
    PROCESSING: 15 * 60,
    COMPLETED: Config.REQUEST_CACHE_DURATION,
    NOT_FOUND: 86400,
    FAILED: 20 * 60,
}

RETRYABLE_STATUSES = {FAILED}


def _status_key(source_type: SourceType, source_id: str) -> str:
    return f"{source_type}:{source_id}"


class PrefetchStatusStore(metaclass=abc.ABCMeta):

    @abc.abstractmethod
    def get(self, source_type: SourceType, source_id: str) -> Optional[dict]:
        pass

    @abc.abstractmethod
    def set(
        self, source_type: SourceType, source_id: str, status: str, reason: str = ""
    ) -> None:
        pass

    def is_queueable(self, source_type: SourceType, source_id: str) -> bool:
        item = self.get(source_type, source_id)
        if item is None:
            return True
        return item["status"] in RETRYABLE_STATUSES


class SQLitePrefetchStatusStore(PrefetchStatusStore):

    def __init__(self, db_path: str):
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._prepare_schema()

    def _prepare_schema(self) -> None:
        curs = self._conn.cursor()
        curs.execute(
            """CREATE TABLE IF NOT EXISTS prefetch_status (
            id TEXT NOT NULL PRIMARY KEY,
            source_type TEXT NOT NULL,
            source_id TEXT NOT NULL,
            status TEXT NOT NULL,
            reason TEXT NOT NULL DEFAULT '',
            ttl INTEGER NOT NULL
            )"""
        )
        self._conn.commit()

    def get(self, source_type: SourceType, source_id: str) -> Optional[dict]:
        try:
            curs = self._conn.cursor()
            curs.execute(
                "SELECT source_type, source_id, status, reason, ttl FROM prefetch_status WHERE id=?",
                (_status_key(source_type, source_id),),
            )
            row = curs.fetchone()
            if not row:
                return None
            source_type_, source_id_, status, reason, ttl = row
            if ttl < int(time.time()):
                return None
            return {
                "source_type": SourceType(source_type_),
                "source_id": source_id_,
                "status": status,
                "reason": reason,
                "ttl": ttl,
            }
        except Exception:
            log.exception("Failed to read prefetch status")
            return None

    def set(
        self, source_type: SourceType, source_id: str, status: str, reason: str = ""
    ) -> None:
        ttl = int(time.time()) + _TTL_SECONDS[status]
        try:
            curs = self._conn.cursor()
            curs.execute(
                """INSERT INTO prefetch_status (id, source_type, source_id, status, reason, ttl)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET status=excluded.status, reason=excluded.reason, ttl=excluded.ttl""",
                (
                    _status_key(source_type, source_id),
                    source_type,
                    source_id,
                    status,
                    reason,
                    ttl,
                ),
            )
            self._conn.commit()
        except Exception:
            log.exception("Failed to write prefetch status")


class DynamoDBPrefetchStatusStore(PrefetchStatusStore):

    def __init__(self, table_name: str, region: str = "us-east-1"):
        import boto3

        self._table = boto3.resource("dynamodb", region_name=region).Table(table_name)

    def get(self, source_type: SourceType, source_id: str) -> Optional[dict]:
        try:
            response = self._table.get_item(
                Key={"id": _status_key(source_type, source_id)}
            )
            item = response.get("Item")
            if not item:
                return None
            if int(item["ttl"]) < int(time.time()):
                return None
            return {
                "source_type": SourceType(item["source_type"]),
                "source_id": item["source_id"],
                "status": item["status"],
                "reason": item.get("reason", ""),
                "ttl": int(item["ttl"]),
            }
        except Exception:
            log.exception("Failed to read prefetch status")
            return None

    def set(
        self, source_type: SourceType, source_id: str, status: str, reason: str = ""
    ) -> None:
        ttl = int(time.time()) + _TTL_SECONDS[status]
        item = {
            "id": _status_key(source_type, source_id),
            "source_type": source_type,
            "source_id": source_id,
            "status": status,
            "reason": reason,
            "ttl": ttl,
        }
        try:
            self._table.put_item(Item=item)
        except Exception:
            log.exception("Failed to write prefetch status")
