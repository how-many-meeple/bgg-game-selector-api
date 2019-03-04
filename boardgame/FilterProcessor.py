from boardgamegeek.objects.games import BaseGame

from boardgame import Filter


class FilterProcessor(object):
    def __init__(self, game_filter: Filter):
        self._filter = game_filter

    def filter_game(self, game: BaseGame) -> bool:
        return self._filter.filter(game)
