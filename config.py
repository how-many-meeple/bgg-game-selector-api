import os
from dotenv import load_dotenv

load_dotenv()


class Config:
    # BGG API Configuration
    BGG_ACCESS_TOKEN = os.getenv('BGG_ACCESS_TOKEN', '')  # Empty string for anonymous access
    BGG_TIMEOUT = int(os.getenv('BGG_TIMEOUT', '60'))
    BGG_RETRY_DELAY = int(os.getenv('BGG_RETRY_DELAY', '10'))
    BGG_RETRIES = int(os.getenv('BGG_RETRIES', '6'))

    # Cache Configuration
    ITEM_CACHE_DURATION = int(os.getenv('ITEM_CACHE_DURATION', '86400'))  # 1 day
    GAME_CACHE_DURATION = int(os.getenv('GAME_CACHE_DURATION', '604800'))  # 1 week

    # Cache Backend Selection
    CACHE_BACKEND = os.getenv('CACHE_BACKEND', 'dynamodb')  # 'dynamodb' or 'sqlite'

    # SQLite Configuration (for local development)
    SQLITE_CACHE_FILE = os.getenv('SQLITE_CACHE_FILE', 'cache.db')

    # DynamoDB Configuration (for production)
    DYNAMODB_TABLE_NAME = os.getenv('DYNAMODB_TABLE_NAME', 'bgg-game-cache')
    DYNAMODB_REGION = os.getenv('AWS_REGION', 'us-east-1')

    # Flask Configuration
    FLASK_ENV = os.getenv('FLASK_ENV', 'production')

    @staticmethod
    def validate():
        """Validate required configuration is present"""
        if Config.CACHE_BACKEND == 'dynamodb':
            required = ['DYNAMODB_TABLE_NAME']
            missing = [var for var in required if not os.getenv(var)]
            if missing:
                print(f"Warning: Missing DynamoDB configuration: {', '.join(missing)}")
                print("Falling back to SQLite cache")
                Config.CACHE_BACKEND = 'sqlite'
