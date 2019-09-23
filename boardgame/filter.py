import abc
import logging
import re
from typing import Optional, List

from boardgamegeek.objects.games import BoardGame
from werkzeug.datastructures import EnvironHeaders

log = logging.getLogger()


class Filter(metaclass=abc.ABCMeta):
    _include_expansions_header_name = "Bgg-Include-Expansions"
    _use_recommended_players_count_header_name = "Bgg-Filter-Using-Recommended-Players"
    _players_count_header_name = "Bgg-Filter-Player-Count"
    _min_duration_header_name = "Bgg-Filter-Min-Duration"
    _max_duration_header_name = "Bgg-Filter-Max-Duration"
    _max_complexity_duration_header_name = "Bgg-Filter-Max-Complexity"
    _mechanics_header_name = "Bgg-Filter-Mechanic"

    def __init__(self, successor=None):
        self._successor = successor

    @abc.abstractmethod
    def filter(self, game: BoardGame) -> bool:
        pass

    @staticmethod
    def create_filter_chain(headers: EnvironHeaders):
        return ExpansionsFilter(headers.get(Filter._include_expansions_header_name),
                                PlayersFilter(headers.get(Filter._players_count_header_name),
                                              headers.get(Filter._use_recommended_players_count_header_name),
                                              DurationFilter(headers.get(Filter._min_duration_header_name),
                                                             headers.get(Filter._max_duration_header_name),
                                                             ComplexityFilter(headers.get(
                                                                 Filter._max_complexity_duration_header_name),
                                                                 MechanicFilter(headers.get(
                                                                     Filter._mechanics_header_name)
                                                                 )))))


class PlayersFilter(Filter):
    def __init__(self, filter_player_count_header: Optional[str], filter_recommended_header: Optional[str],
                 successor=None):
        super().__init__(successor)
        self._players_count = int(filter_player_count_header) if filter_player_count_header else None
        self._use_recommended = True
        if filter_recommended_header and filter_recommended_header.lower() == "false":
            self._use_recommended = False

    def filter(self, game: BoardGame) -> bool:
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

    def recommended_players(self, game: BoardGame) -> (int, int):
        recommended = [players.numeric_player_count for players in game.player_suggestions if
                       players.best > players.not_recommended or players.recommended > players.not_recommended]
        if len(recommended) > 0:
            return min(recommended), max(recommended)
        raise AttributeError


class ComplexityFilter(Filter):
    def __init__(self, filter_complexity_header: Optional[str],
                 successor=None):
        super().__init__(successor)
        self._complexity = float(filter_complexity_header if filter_complexity_header else 0)

    def filter(self, game: BoardGame) -> bool:
        weight = float(game.rating_average_weight if game.rating_average_weight else 0)
        if self._complexity and weight > self._complexity:
            return True
        elif self._successor:
            return self._successor.filter(game)
        return False


class ExpansionsFilter(Filter):
    def __init__(self, include_expansions_header: Optional[str],
                 successor=None):
        super().__init__(successor)
        self._include_expansions = False
        if include_expansions_header and include_expansions_header.lower() == "true":
            self._include_expansions = True

    def filter(self, game: BoardGame) -> bool:
        if game.expansion is True and self._include_expansions is False:
            return True
        elif self._successor:
            return self._successor.filter(game)
        return False


class DurationFilter(Filter):
    def __init__(self, filter_min_time: Optional[str], filter_max_time: Optional[str], successor=None):
        super().__init__(successor)
        self._min_time = int(filter_min_time) if filter_min_time else None
        self._max_time = int(filter_max_time) if filter_max_time else None

    def filter(self, game: BoardGame) -> bool:
        if (self._min_time and game.min_playing_time < self._min_time) \
                or (self._max_time and game.max_playing_time > self._max_time):
            return True
        elif self._successor:
            return self._successor.filter(game)
        return False


class MechanicFilter(Filter):
    @staticmethod
    def extract(str_mechanic) -> List[str]:
        str_mechanic = str_mechanic.strip("[]")
        if not str_mechanic:
            return []
        return [mechanic.strip() for mechanic in str_mechanic.split(",")]

    def remove_mechanic_prefix(self, mechanic):
        if not mechanic:
            return mechanic
        return re.sub(r"^\w{2,3}-[\w\d]{2,3}\s+", "", mechanic)

    def __init__(self, filter_mechanic: Optional[str], successor=None):
        super().__init__(successor)
        self._filter_mechanics: List[str] = MechanicFilter.extract(filter_mechanic) if filter_mechanic else []

    def filter(self, game: BoardGame) -> bool:
        def check_lists_have_matching_content(game_mechanics: List[str], users_requested_mechanics: List[str]) -> bool:
            if not game_mechanics or len(game_mechanics) == 0:
                return True
            sanitised_game_mechanics = [self.remove_mechanic_prefix(mechanic) for mechanic in game_mechanics]
            sanitised_user_mechanics = [self.remove_mechanic_prefix(mechanic) for mechanic in users_requested_mechanics]
            common_mechanics = set(sanitised_game_mechanics) & set(sanitised_user_mechanics)
            return len(common_mechanics) == 0

        if len(self._filter_mechanics) > 0 and check_lists_have_matching_content(game.mechanics,
                                                                                 self._filter_mechanics):
            return True
        elif self._successor:
            return self._successor.filter(game)
        return False
