import abc
from typing import Optional

from boardgamegeek.objects.games import BaseGame, PlayerSuggestion
import logging
from werkzeug.datastructures import EnvironHeaders

log = logging.getLogger()


class Filter(metaclass=abc.ABCMeta):
    _use_recommended_players_count = "Bgg-Filter-Using-Recommended-Players"
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
                             headers.get(Filter._use_recommended_players_count),
                             DurationFilter(headers.get(Filter._min_duration),
                                            headers.get(Filter._max_duration)))


class PlayersFilter(Filter):
    def __init__(self, filter_player_count_header: Optional[str], filter_recommended_header: Optional[str],
                 successor=None):
        super().__init__(successor)
        self._players_count = int(filter_player_count_header) if filter_player_count_header else None
        self._use_recommended = True
        if filter_recommended_header and filter_recommended_header.lower() == "false":
            self._use_recommended = False

    def filter(self, game: BaseGame) -> bool:
        def is_matching_request(player_count: Optional[int], min_players: int, max_players: int) -> bool:
            return player_count and (max_players < player_count or player_count < min_players)

        matches_request = is_matching_request(self._players_count, game.min_players, game.max_players)
        if self._use_recommended:
            try:
                min_players, max_players = self.recommended_players(game)
                matches_request = is_matching_request(self._players_count, min_players, max_players)
            except AttributeError:
                logging.info(f"no recommendations found for game {game}")

        if matches_request:
            return True
        elif self._successor:
            return self._successor.filter(game)
        return False

    def recommended_players(self, game: BaseGame) -> (int, int):
        recommended = [players.numeric_player_count for players in game.player_suggestions if
                       players.best > players.not_recommended or players.recommended > players.not_recommended]
        if len(recommended) > 0:
            return min(recommended), max(recommended)
        raise AttributeError


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
