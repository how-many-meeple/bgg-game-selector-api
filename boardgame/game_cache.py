"""
Game caching implementations for BoardGameGeek data.

Provides abstract base class and concrete implementations for:
- SQLite cache (for local development)
- DynamoDB cache (for production/AWS deployment with automatic TTL)
"""

import abc
import json
import logging
import sqlite3
from datetime import datetime, timedelta, timezone
from typing import Optional

from boardgamegeek.objects.games import BoardGame

log = logging.getLogger(__name__)


class GameCache(metaclass=abc.ABCMeta):
    """Abstract base class for game caching implementations"""

    def __init__(self, cache_length: int):
        self.cache_length = cache_length

    @abc.abstractmethod
    def save(self, game: BoardGame) -> None:
        """Save a game to the cache"""
        pass

    @abc.abstractmethod
    def load(self, game_id: int) -> Optional[BoardGame]:
        """Load a game from the cache by ID. Returns None if not found."""
        pass

    @abc.abstractmethod
    def timeout_cache(self) -> None:
        """Remove expired entries from the cache"""
        pass


class SQLiteGameCache(GameCache):
    """SQLite-based cache for BoardGame objects (for local development)"""

    def __init__(self, cache_length: int, cache_file: str):
        super().__init__(cache_length)
        self._conn = sqlite3.connect(cache_file, check_same_thread=False)
        self.prepare_schema()

    def prepare_schema(self) -> None:
        curs = self._conn.cursor()
        curs.execute(
            """CREATE TABLE IF NOT EXISTS cached_game (
            id TEXT NOT NULL PRIMARY KEY,
            cache_timestamp DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
            data TEXT)"""
        )
        self._conn.commit()

    def save(self, game: BoardGame) -> None:
        binds = (str(game.id), json.dumps(game.data()), str(game.id))
        try:
            curs = self._conn.cursor()
            curs.execute(
                """INSERT INTO cached_game (id, data)
            SELECT ?, ?
            WHERE NOT EXISTS(SELECT 1 FROM cached_game WHERE id=?)""",
                binds,
            )
            self._conn.commit()
            log.debug(f"Cached game {game.id} ({game.name})")
        except Exception as e:
            log.error(f"Error saving game {game.id} to cache: {e}")

    def load(self, game_id: int) -> Optional[BoardGame]:
        binds = (str(game_id),)
        try:
            curs = self._conn.cursor()
            curs.execute("""SELECT data FROM cached_game WHERE id=?""", binds)
            data = curs.fetchone()
            if data:
                log.debug(f"Cache hit for game {game_id}")
                return BoardGame(json.loads(data[0]))
            log.debug(f"Cache miss for game {game_id}")
            return None
        except Exception as e:
            log.error(f"Error loading game {game_id} from cache: {e}")
            return None

    def timeout_cache(self) -> None:
        curs = self._conn.cursor()
        try:
            curs.execute(
                f"DELETE FROM cached_game WHERE cache_timestamp < DATETIME(CURRENT_TIMESTAMP, '-{self.cache_length} seconds')"  # noqa: E501
            )
            deleted = curs.rowcount
            self._conn.commit()
            if deleted > 0:
                log.info(f"Expired {deleted} cached games")
        except Exception as e:
            log.error(f"Error timing out cache: {e}")


class DynamoDBGameCache(GameCache):
    """
    DynamoDB-based cache for BoardGame objects with automatic TTL expiration.

    Uses DynamoDB's native TTL feature for automatic cache expiration,
    which is more cost-effective than manual cleanup operations.
    Configured with PAY_PER_REQUEST billing for cost optimization within AWS free tier.
    """

    def __init__(
        self, table_name: str, cache_length_seconds: int, region: str = "us-east-1"
    ):
        super().__init__(cache_length_seconds)
        self.table_name = table_name

        try:
            import boto3
            from botocore.exceptions import ClientError

            self._ClientError = ClientError
            self.dynamodb = boto3.resource("dynamodb", region_name=region)
            self.table = self.dynamodb.Table(table_name)
        except ImportError:
            raise ImportError(
                "boto3 is required for DynamoDB cache. Install with: pip install boto3"
            )

    def save(self, game: BoardGame) -> None:
        """Save a game to DynamoDB cache with automatic TTL expiration"""
        try:
            ttl = int(
                (
                    datetime.now(timezone.utc) + timedelta(seconds=self.cache_length)
                ).timestamp()
            )
            item = {
                "id": str(game.id),
                "data": json.dumps(game.data()),
                "cache_timestamp": datetime.now(timezone.utc).isoformat(),
                "ttl": ttl,
            }
            self.table.put_item(
                Item=item, ConditionExpression="attribute_not_exists(id)"
            )
            log.debug(
                f"Cached game {game.id} ({game.name}) until {datetime.fromtimestamp(ttl)}"
            )
        except self._ClientError as e:
            if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
                log.debug(f"Game {game.id} already cached")
            else:
                log.error(f"Error saving game {game.id} to cache: {e}")
        except Exception as e:
            log.error(f"Unexpected error saving game {game.id}: {e}")

    def load(self, game_id: int) -> Optional[BoardGame]:
        """Load a game from DynamoDB cache"""
        try:
            response = self.table.get_item(Key={"id": str(game_id)})
            if "Item" in response:
                data = json.loads(response["Item"]["data"])
                log.debug(f"Cache hit for game {game_id}")
                return BoardGame(data)
            log.debug(f"Cache miss for game {game_id}")
            return None
        except Exception as e:
            log.error(f"Error loading game {game_id} from cache: {e}")
            return None

    def timeout_cache(self) -> None:
        """
        No-op for DynamoDB - TTL expiration is handled automatically by AWS.
        This method is kept for interface compatibility with the base class.
        """
        log.debug("DynamoDB TTL handles cache expiration automatically")

    @staticmethod
    def create_table(table_name: str, region: str = "us-east-1") -> None:
        """
        Create the DynamoDB table with TTL enabled.

        This is a utility method for initial AWS setup. The table is configured with:
        - PAY_PER_REQUEST billing mode for cost optimization (no minimum charges)
        - Automatic TTL on the 'ttl' attribute for zero-cost cache expiration
        - Appropriate tags for resource management

        Args:
            table_name: Name of the DynamoDB table to create
            region: AWS region (default: us-east-1)
        """
        try:
            import boto3
            from botocore.exceptions import ClientError
        except ImportError:
            raise ImportError(
                "boto3 is required for DynamoDB cache. Install with: pip install boto3"
            )

        dynamodb = boto3.client("dynamodb", region_name=region)

        try:
            dynamodb.create_table(
                TableName=table_name,
                KeySchema=[{"AttributeName": "id", "KeyType": "HASH"}],
                AttributeDefinitions=[{"AttributeName": "id", "AttributeType": "S"}],
                BillingMode="PAY_PER_REQUEST",  # On-demand pricing for cost optimization
                Tags=[
                    {"Key": "Application", "Value": "bgg-game-selector"},
                    {"Key": "Purpose", "Value": "game-cache"},
                ],
            )

            # Wait for table to be created
            waiter = dynamodb.get_waiter("table_exists")
            waiter.wait(TableName=table_name)

            # Enable TTL on the ttl attribute
            dynamodb.update_time_to_live(
                TableName=table_name,
                TimeToLiveSpecification={"Enabled": True, "AttributeName": "ttl"},
            )

            log.info(f"Created DynamoDB table {table_name} with TTL enabled")
        except ClientError as e:
            if e.response["Error"]["Code"] == "ResourceInUseException":
                log.info(f"Table {table_name} already exists")
            else:
                raise
