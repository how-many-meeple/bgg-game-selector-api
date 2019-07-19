import abc
from typing import List

from boardgamegeek import BGGClient, CacheBackendSqlite, BGGItemNotFoundError
from boardgamegeek.objects.games import BaseGame
from werkzeug.datastructures import EnvironHeaders

from boardgame import filter_processor
from boardgame.field_reduction import FieldReduction
from boardgame.legacy_api import BGGClientLegacy


class BoardGameUserNotFoundError(Exception):
    def __init__(self, error, message):
        self.error = error
        self.message = message


class BoardGameListNotFoundError(Exception):
    def __init__(self, message):
        self.message = message


class BoardGameFactory(object):

    @staticmethod
    def create_client():
        return BGGClient(cache=CacheBackendSqlite(path="cache.db", ttl=3600), timeout=60, retry_delay=10, retries=6)

    @staticmethod
    def create_legacy_client():
        return BGGClientLegacy(cache=CacheBackendSqlite(path="cache.db", ttl=3600),
                               timeout=60,
                               retry_delay=10,
                               retries=6)

    @staticmethod
    def create_player_selector(players: str, headers: EnvironHeaders) -> 'BoardGamePlayerSelector':
        field_reduction = FieldReduction.create_field_reduction(headers)
        player_list = [player.strip() for player in players.split(',')]
        return BoardGamePlayerSelector(BoardGameFactory.create_client(), field_reduction, player_list)

    @staticmethod
    def create_list_selector(list_ids: str, headers: EnvironHeaders) -> 'BoardGameGeekListSelector':
        field_reduction = FieldReduction.create_field_reduction(headers)
        geek_list = [list_id.strip() for list_id in list_ids.split(',')]
        return BoardGameGeekListSelector(BoardGameFactory.create_client(),
                                         BoardGameFactory.create_legacy_client(),
                                         field_reduction,
                                         geek_list)

    @staticmethod
    def create_search() -> 'BoardGameSearch':
        return BoardGameSearch(BoardGameFactory.create_client())


class BoardGameSelector(metaclass=abc.ABCMeta):

    def __init__(self, ids: List[str], field_reduction: FieldReduction):
        self._ids = ids
        self._field_reduction = field_reduction

    def __get_games(self, ids: List[str]) -> List[BaseGame]:
        games = []
        for bgg_id in ids:
            games = games + self.get_games_for_id(bgg_id)
        games.sort(key=lambda base_game: base_game.name)
        return games

    def get_games_matching_filter(self, game_filter: filter_processor) -> List[dict]:
        filtered_games = [item for item in self.__get_games(self._ids) if not game_filter.filter_game(item)]
        return self._field_reduction.clean_response(filtered_games)

    @abc.abstractmethod
    def get_games_for_id(self, bgg_id: str) -> List[BaseGame]:
        pass


class BoardGamePlayerSelector(BoardGameSelector):

    def __init__(self, bgg: BGGClient, field_reduction: FieldReduction, users: List[str]):
        super().__init__(users, field_reduction)
        self._bgg = bgg

    def get_games_for_id(self, player: str) -> List[BaseGame]:
        try:
            game_list = self._bgg.collection(player, own=True).items
            return self.__get_games_from_collection_list(game_list)
        except BGGItemNotFoundError as error:
            raise BoardGameUserNotFoundError(error, f"No user found called '{player}'")

    def __get_games_from_collection_list(self, games: List[BaseGame]) -> List[BaseGame]:
        game_id_list = [game.id for game in games]
        return self._bgg.game_list(game_id_list)


class BoardGameGeekListSelector(BoardGameSelector):

    def __init__(self, bgg: BGGClient, bgg_legacy: BGGClientLegacy, field_reduction: FieldReduction,
                 geek_lists: List[str]):
        super().__init__(geek_lists, field_reduction)
        self._bgg_legacy = bgg_legacy
        self._bgg = bgg

    def get_games_for_id(self, geek_list: str) -> List[BaseGame]:
        game_id_list = [list_obj.object.id for list_obj in self._bgg_legacy.geeklist(geek_list).items]
        if len(game_id_list) == 0:
            raise BoardGameListNotFoundError(f"List not found or contains no games '{geek_list}'")
        return self.__get_games_from_id_list(game_id_list)

    def __get_games_from_id_list(self, game_ids) -> List[BaseGame]:
        return self._bgg.game_list(game_ids)


class BoardGameSearch(object):

    def __init__(self, bgg: BGGClient):
        self._bgg = bgg

    def search_for_game(self, game_name) -> List[BaseGame]:
        if len(game_name) < 3:
            return []
        return self._bgg.search(game_name, exact=False)
