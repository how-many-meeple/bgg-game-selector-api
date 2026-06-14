import json
import logging
from typing import Any, Dict

from boardgame.board_game import (
    BoardGameFactory,
    BoardGameUserNotFoundError,
    BoardGameListNotFoundError,
)
from boardgame.filter_processor import FilterProcessor
from boardgame.prefetch_status import (
    PROCESSING,
    COMPLETED,
    NOT_FOUND,
    FAILED,
    SourceType,
)

logger = logging.getLogger()
logger.setLevel(logging.INFO)

_status_store = BoardGameFactory.create_prefetch_status_store()

_no_op_filter = FilterProcessor([])


def _fetch_collection(source_id: str) -> None:
    selector = BoardGameFactory.create_player_selector(source_id, {})
    selector.get_games_matching_filter(_no_op_filter)


def _fetch_geeklist(source_id: str) -> None:
    selector = BoardGameFactory.create_list_selector(source_id, {})
    selector.get_games_matching_filter(_no_op_filter)


def handler(event: Dict[str, Any], context: Any) -> None:
    for record in event.get("Records", []):
        body = json.loads(record["body"])
        source_type = body["source_type"]
        source_id = body["source_id"]

        source_type = SourceType(source_type)
        logger.info(f"Prefetching {source_type}:{source_id}")
        _status_store.set(source_type, source_id, PROCESSING)

        try:
            if source_type == SourceType.COLLECTION:
                _fetch_collection(source_id)
            else:
                _fetch_geeklist(source_id)

            _status_store.set(source_type, source_id, COMPLETED)
            logger.info(f"Prefetch complete for {source_type}:{source_id}")

        except (BoardGameUserNotFoundError, BoardGameListNotFoundError) as e:
            reason = getattr(e, "message", str(e))
            logger.warning(f"Not found {source_type}:{source_id} — {reason}")
            _status_store.set(source_type, source_id, NOT_FOUND, reason=reason)

        except Exception as e:
            reason = str(e)
            logger.error(
                f"Failed prefetch {source_type}:{source_id} — {reason}", exc_info=True
            )
            _status_store.set(source_type, source_id, FAILED, reason=reason)
