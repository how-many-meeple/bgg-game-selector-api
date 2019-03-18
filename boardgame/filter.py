import abc
from typing import Optional

from boardgamegeek.objects.games import BaseGame
from werkzeug.datastructures import EnvironHeaders


class Filter(metaclass=abc.ABCMeta):
    _players_count = "Bgg-Filter-Player-Count"
    _min_duration = "Bgg-Filter-Min-Duration"
    _max_duration = "Bgg-Filter-Max-Duration"

    def __init__(self, successor=None):
        self._successor = successor

    @abc.abstractmethod
    def filter(self, game: BaseGame) -> bool:
        pass

    @staticmethod
    def create_filter_chain(headers: EnvironHeaders):
        return PlayersFilter(headers.get(Filter._players_count),
                             DurationFilter(headers.get(Filter._min_duration),
                                            headers.get(Filter._max_duration)))


class PlayersFilter(Filter):
    def __init__(self, filter_header: Optional[str], successor=None):
        super().__init__(successor)
        self._players_count = int(filter_header) if filter_header else None

    def filter(self, game: BaseGame) -> bool:
        if self._players_count and (game.max_players < self._players_count or self._players_count < game.min_players):
            return True
        elif self._successor:
            return self._successor.filter(game)
        return False


class DurationFilter(Filter):
    def __init__(self, filter_min_time: Optional[str], filter_max_time: Optional[str], successor=None):
        super().__init__(successor)
        self._min_time = int(filter_min_time) if filter_min_time else None
        self._max_time = int(filter_max_time) if filter_max_time else None

    def filter(self, game: BaseGame) -> bool:
        if (self._min_time and game.min_playing_time < self._min_time) \
                or (self._max_time and game.max_playing_time > self._max_time):
            return True
        elif self._successor:
            return self._successor.filter(game)
        return False
