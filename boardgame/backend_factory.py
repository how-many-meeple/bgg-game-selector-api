"""
Backend factory pattern for dynamically selecting storage implementations
based on configuration.

Reduces duplication of match/case statements across the codebase.
"""

from functools import wraps
from typing import Callable, TypeVar

from config import Config

T = TypeVar("T")


def backend_selector(**backend_mapping):
    """
    Decorator that selects backend implementation based on Config.CACHE_BACKEND.

    Usage:
        @backend_selector(
            dynamodb=lambda: DynamoDBCache(...),
            sqlite=lambda: SQLiteCache(...),
            default=lambda: MemoryCache(...)
        )
        def create_cache():
            pass

    Args:
        **backend_mapping: Keyword arguments mapping backend names to factory functions

    Returns:
        Decorated function that returns the appropriate backend instance
    """

    def decorator(func: Callable[[], T]) -> Callable[[], T]:
        @wraps(func)
        def wrapper(*args, **kwargs) -> T:
            backend = Config.CACHE_BACKEND

            # Try exact match first
            if backend in backend_mapping:
                return backend_mapping[backend]()

            # Fall back to default if provided
            if "default" in backend_mapping:
                return backend_mapping["default"]()

            # No match found
            raise ValueError(
                f"No backend implementation found for '{backend}'. "
                f"Available: {list(backend_mapping.keys())}"
            )

        return wrapper

    return decorator
