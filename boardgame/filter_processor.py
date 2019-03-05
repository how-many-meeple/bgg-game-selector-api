from typing import Optional

from boardgamegeek.objects.games import BaseGame

from boardgame import filter


class FilterProcessor(object):
    def __init__(self, game_filter: Optional[filter.Filter]):
        self._filter = game_filter

    def filter_game(self, game: BaseGame) -> bool:
        if not self._filter:
            return False
        return self._filter.filter(game)
