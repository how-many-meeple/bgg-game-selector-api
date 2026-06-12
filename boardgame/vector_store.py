"""
Vector storage implementations for game recommendations.

Provides abstract base class and concrete implementations for:
- SQLite vector store (for local development)
- DynamoDB vector store (for production/AWS deployment)

Unlike the game cache, this is a permanent store that is never auto-deleted,
only updated when games change. Vectors are never deleted, only replaced via upsert.
"""

import abc
import json
import logging
import sqlite3
from datetime import datetime, timezone
from typing import Optional

log = logging.getLogger(__name__)


class GameVector:
    """Data model for a game's vector representation"""

    def __init__(
        self,
        game_id: int,
        name: str,
        vector: list[float],
        updated_at: Optional[str] = None,
    ):
        self.game_id = game_id
        self.name = name
        self.vector = vector
        self.updated_at = updated_at or datetime.now(timezone.utc).isoformat()

    def to_dict(self) -> dict:
        return {
            "game_id": self.game_id,
            "name": self.name,
            "vector": self.vector,
            "updated_at": self.updated_at,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "GameVector":
        return cls(
            game_id=data["game_id"],
            name=data["name"],
            vector=data["vector"],
            updated_at=data.get("updated_at"),
        )


class VectorStore(metaclass=abc.ABCMeta):
    """Abstract base class for vector storage implementations"""

    @abc.abstractmethod
    def save(self, game_vector: GameVector) -> None:
        """Save or update a game vector (upsert - never deletes, only replaces)"""
        pass

    @abc.abstractmethod
    def load(self, game_id: int) -> Optional[GameVector]:
        """Load a game vector by ID. Returns None if not found."""
        pass

    @abc.abstractmethod
    def load_all(self) -> list[GameVector]:
        """Load all game vectors from the store"""
        pass


class SQLiteVectorStore(VectorStore):
    """SQLite-based permanent vector store"""

    def __init__(self, db_path: str):
        self.db_path = db_path
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self.prepare_schema()

    def prepare_schema(self) -> None:
        curs = self._conn.cursor()
        curs.execute(
            """CREATE TABLE IF NOT EXISTS game_vectors (
            game_id INTEGER NOT NULL PRIMARY KEY,
            name TEXT NOT NULL,
            vector TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )"""
        )
        self._conn.commit()
        log.debug("SQLite vector store schema prepared")

    def save(self, game_vector: GameVector) -> None:
        """Save or update a game vector (upsert)"""
        try:
            curs = self._conn.cursor()
            curs.execute(
                """INSERT OR REPLACE INTO game_vectors (game_id, name, vector, updated_at)
                VALUES (?, ?, ?, ?)""",
                (
                    game_vector.game_id,
                    game_vector.name,
                    json.dumps(game_vector.vector),
                    game_vector.updated_at,
                ),
            )
            self._conn.commit()
            log.debug(
                f"Saved vector for game {game_vector.game_id} ({game_vector.name})"
            )
        except Exception as e:
            log.error(f"Error saving vector for game {game_vector.game_id}: {e}")
            raise

    def load(self, game_id: int) -> Optional[GameVector]:
        """Load a game vector by ID"""
        try:
            curs = self._conn.cursor()
            curs.execute(
                """SELECT game_id, name, vector, updated_at
                FROM game_vectors WHERE game_id=?""",
                (game_id,),
            )
            row = curs.fetchone()
            if row:
                return GameVector(
                    game_id=row[0],
                    name=row[1],
                    vector=json.loads(row[2]),
                    updated_at=row[3],
                )
            return None
        except Exception as e:
            log.error(f"Error loading vector for game {game_id}: {e}")
            return None

    def load_all(self) -> list[GameVector]:
        """Load all game vectors from the store"""
        try:
            curs = self._conn.cursor()
            curs.execute(
                """SELECT game_id, name, vector, updated_at FROM game_vectors"""
            )
            rows = curs.fetchall()
            vectors = []
            for row in rows:
                vectors.append(
                    GameVector(
                        game_id=row[0],
                        name=row[1],
                        vector=json.loads(row[2]),
                        updated_at=row[3],
                    )
                )
            log.debug(f"Loaded {len(vectors)} vectors from store")
            return vectors
        except Exception as e:
            log.error(f"Error loading all vectors: {e}")
            return []


class DynamoDBVectorStore(VectorStore):
    """
    DynamoDB-based permanent vector store.

    Unlike the game cache, this table has NO TTL - vectors are permanent
    and only updated when games change via upsert operations.
    """

    def __init__(self, table_name: str, region: str = "us-east-1"):
        self.table_name = table_name

        try:
            import boto3
            from botocore.exceptions import ClientError

            self._ClientError = ClientError
            self.dynamodb = boto3.resource("dynamodb", region_name=region)
            self.table = self.dynamodb.Table(table_name)
        except ImportError:
            raise ImportError(
                "boto3 is required for DynamoDB vector store. Install with: pip install boto3"
            )

    def save(self, game_vector: GameVector) -> None:
        """Save or update a game vector (upsert)"""
        try:
            item = {
                "game_id": game_vector.game_id,
                "name": game_vector.name,
                "vector": json.dumps(game_vector.vector),
                "updated_at": game_vector.updated_at,
            }
            self.table.put_item(Item=item)
            log.debug(
                f"Saved vector for game {game_vector.game_id} ({game_vector.name})"
            )
        except Exception as e:
            log.error(f"Error saving vector for game {game_vector.game_id}: {e}")
            raise

    def load(self, game_id: int) -> Optional[GameVector]:
        """Load a game vector by ID"""
        try:
            response = self.table.get_item(Key={"game_id": game_id})
            if "Item" in response:
                item = response["Item"]
                return GameVector(
                    game_id=item["game_id"],
                    name=item["name"],
                    vector=json.loads(item["vector"]),
                    updated_at=item["updated_at"],
                )
            return None
        except Exception as e:
            log.error(f"Error loading vector for game {game_id}: {e}")
            return None

    def load_all(self) -> list[GameVector]:
        """
        Load all game vectors from the store using scan.

        Note: This performs a full table scan which can be expensive for large datasets.
        Consider optimization strategies (caching, OpenSearch integration) if the
        dataset grows beyond ~10k vectors or if cost becomes an issue.
        """
        try:
            vectors = []
            response = self.table.scan()

            for item in response.get("Items", []):
                vectors.append(
                    GameVector(
                        game_id=item["game_id"],
                        name=item["name"],
                        vector=json.loads(item["vector"]),
                        updated_at=item["updated_at"],
                    )
                )

            # Handle pagination
            while "LastEvaluatedKey" in response:
                response = self.table.scan(
                    ExclusiveStartKey=response["LastEvaluatedKey"]
                )
                for item in response.get("Items", []):
                    vectors.append(
                        GameVector(
                            game_id=item["game_id"],
                            name=item["name"],
                            vector=json.loads(item["vector"]),
                            updated_at=item["updated_at"],
                        )
                    )

            log.info(f"Loaded {len(vectors)} vectors from DynamoDB")
            return vectors
        except Exception as e:
            log.error(f"Error loading all vectors: {e}")
            return []
