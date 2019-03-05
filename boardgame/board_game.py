import abc
from typing import List

from boardgamegeek import BGGClient, CacheBackendSqlite, BGGItemNotFoundError
from boardgamegeek.objects.games import BaseGame

from boardgame import filter_processor
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
    def create_player_selector(players: str) -> 'BoardGamePlayerSelector':
        player_list = [player.strip() for player in players.split(',')]
        return BoardGamePlayerSelector(BoardGameFactory.create_client(), player_list)

    @staticmethod
    def create_list_selector(list_ids: str) -> 'BoardGameGeekListSelector':
        geek_list = [list_id.strip() for list_id in list_ids.split(',')]
        return BoardGameGeekListSelector(BoardGameFactory.create_client(),
                                         BoardGameFactory.create_legacy_client(),
                                         geek_list)

    @staticmethod
    def create_search() -> 'BoardGameSearch':
        return BoardGameSearch(BoardGameFactory.create_client())


class BoardGameSelector(metaclass=abc.ABCMeta):

    def __init__(self, ids: List[str]):
        self._ids = ids

    def __get_games(self, ids: List[str]) -> List[BaseGame]:
        games = []
        for bgg_id in ids:
            games = games + self.get_games_for_id(bgg_id)
        games.sort(key=lambda base_game: base_game.name)
        return games

    def get_games_matching_filter(self, game_filter: filter_processor) -> List[BaseGame]:
        return [item for item in self.__get_games(self._ids) if not game_filter.filter_game(item)]

    @abc.abstractmethod
    def get_games_for_id(self, bgg_id: str) -> List[BaseGame]:
        pass


class BoardGamePlayerSelector(BoardGameSelector):

    def __init__(self, bgg: BGGClient, users: List[str]):
        super().__init__(users)
        self._bgg = bgg

    def get_games_for_id(self, player: str) -> List[BaseGame]:
        try:
            return self._bgg.collection(player, own=True).items
        except BGGItemNotFoundError as error:
            raise BoardGameUserNotFoundError(error, f"No user found called '{player}'")


class BoardGameGeekListSelector(BoardGameSelector):

    def __init__(self, bgg: BGGClient, bgg_legacy: BGGClientLegacy, geek_lists: List[str]):
        super().__init__(geek_lists)
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
