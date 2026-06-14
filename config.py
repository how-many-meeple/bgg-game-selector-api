import os
from dotenv import load_dotenv

load_dotenv()


class Config:
    # BGG API Configuration
    BGG_ACCESS_TOKEN = os.getenv("BGG_ACCESS_TOKEN", "")
    BGG_TIMEOUT = int(os.getenv("BGG_TIMEOUT", "60"))
    BGG_RETRY_DELAY = int(os.getenv("BGG_RETRY_DELAY", "10"))
    BGG_RETRIES = int(os.getenv("BGG_RETRIES", "6"))

    # Cache Configuration
    REQUEST_CACHE_DURATION = int(os.getenv("REQUEST_CACHE_DURATION", "86400"))  # 1 day
    GAME_CACHE_DURATION = int(os.getenv("GAME_CACHE_DURATION", "604800"))  # 1 week

    # Cache Backend Selection
    CACHE_BACKEND = os.getenv(
        "CACHE_BACKEND", "dynamodb"
    )  # 'dynamodb', 'sqlite', or 'memory'

    # DynamoDB Configuration
    DYNAMODB_REQUEST_TABLE = os.getenv("DYNAMODB_REQUEST_TABLE", "bgg-request-cache")
    DYNAMODB_GAME_TABLE = os.getenv("DYNAMODB_GAME_TABLE", "bgg-game-cache")
    DYNAMODB_VECTOR_TABLE = os.getenv("DYNAMODB_VECTOR_TABLE", "bgg-game-vectors")
    DYNAMODB_PREFETCH_TABLE = os.getenv(
        "DYNAMODB_PREFETCH_TABLE", "bgg-prefetch-status"
    )
    DYNAMODB_REGION = os.getenv("AWS_REGION", "us-east-1")

    # Prefetch SQS queue URL
    PREFETCH_SQS_URL = os.getenv("PREFETCH_SQS_URL", "")

    # SQLite Configuration
    SQLITE_REQUEST_CACHE_PATH = os.getenv(
        "SQLITE_REQUEST_CACHE_PATH", "bgg_request_cache.sqlite"
    )
    SQLITE_GAME_CACHE_PATH = os.getenv(
        "SQLITE_GAME_CACHE_PATH", "bgg_game_cache.sqlite"
    )
    SQLITE_VECTOR_STORE_PATH = os.getenv(
        "SQLITE_VECTOR_STORE_PATH", "bgg_game_vectors.sqlite"
    )
    SQLITE_PREFETCH_STATUS_PATH = os.getenv(
        "SQLITE_PREFETCH_STATUS_PATH", "bgg_prefetch_status.sqlite"
    )

    # Vector Store Configuration
    VECTOR_MIN_RATINGS = int(
        os.getenv("VECTOR_MIN_RATINGS", "100")
    )  # Minimum user ratings to vectorize a game

    # Flask Configuration
    FLASK_ENV = os.getenv("FLASK_ENV", "production")

    @staticmethod
    def validate():
        if Config.CACHE_BACKEND == "dynamodb":
            required = ["DYNAMODB_REQUEST_TABLE", "DYNAMODB_GAME_TABLE"]
            missing = [var for var in required if not os.getenv(var)]
            if missing:
                print(f"Warning: Missing DynamoDB configuration: {', '.join(missing)}")
                print("Falling back to in-memory cache")
                Config.CACHE_BACKEND = "memory"
