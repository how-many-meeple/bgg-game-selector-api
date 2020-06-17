import abc
import logging
import os
from typing import List

from boardgamegeek import BGGClient, CacheBackendSqlite, BGGItemNotFoundError
from boardgamegeek.objects.games import BaseGame, BoardGame
from werkzeug.datastructures import EnvironHeaders

from boardgame import filter_processor
from boardgame.database import DatabaseConnectionDetails
from boardgame.field_reduction import FieldReduction
from boardgame.cache.game_cache import GameCache
from boardgame.legacy_api import BGGClientLegacy


class BoardGameUserNotFoundError(Exception):
    def __init__(self, error, message):
        self.error = error
        self.message = message


class BoardGameListNotFoundError(Exception):
    def __init__(self, message):
        self.message = message


class BoardGameFactory(object):
    item_cache_duration = 86400  # one day in seconds
    cache_location = "cache.db"
    database_details = DatabaseConnectionDetails(host=os.getenv('database_host'),
                                                 username=os.getenv('database_username'),
                                                 password=os.getenv('database_password'),
                                                 database=os.getenv('database_name')
                                                 )

    @staticmethod
    def create_client():
        return BGGClient(
            cache=CacheBackendSqlite(path=BoardGameFactory.cache_location, ttl=BoardGameFactory.item_cache_duration),
            timeout=60,
            retry_delay=10, retries=6)

    @staticmethod
    def create_legacy_client():
        return BGGClientLegacy(
            cache=CacheBackendSqlite(path=BoardGameFactory.cache_location, ttl=BoardGameFactory.item_cache_duration),
            timeout=60,
            retry_delay=10,
            retries=6)

    @staticmethod
    def create_game_cache():
        return GameCache(BoardGameFactory.database_details)

    @staticmethod
    def create_player_selector(players: str, headers: EnvironHeaders) -> 'BoardGamePlayerSelector':
        field_reduction = FieldReduction.create_field_reduction(headers)
        player_list = [player.strip() for player in players.split(',')]
        return BoardGamePlayerSelector(BoardGameFactory.create_client(), BoardGameFactory.create_game_cache(),
                                       field_reduction, player_list)

    @staticmethod
    def create_list_selector(list_ids: str, headers: EnvironHeaders) -> 'BoardGameGeekListSelector':
        field_reduction = FieldReduction.create_field_reduction(headers)
        geek_list = [list_id.strip() for list_id in list_ids.split(',')]
        return BoardGameGeekListSelector(BoardGameFactory.create_client(),
                                         BoardGameFactory.create_legacy_client(),
                                         BoardGameFactory.create_game_cache(),
                                         field_reduction,
                                         geek_list)

    @staticmethod
    def create_game_refresher() -> 'BoardGameCronRefresher':
        return BoardGameCronRefresher(BoardGameFactory.create_client(),
                                      BoardGameFactory.create_game_cache())

    @staticmethod
    def create_search() -> 'BoardGameSearch':
        return BoardGameSearch(BoardGameFactory.create_client())


class BoardGameLoadGameFromBgg(object):

    def __init__(self, game_cache: GameCache):
        self.game_cache = game_cache

    def __get_games_from_cache(self, ids: list):
        games_from_cache = []
        for game_id in ids:
            game = self.game_cache.load(game_id)
            if game:
                games_from_cache = games_from_cache + [game]
        return games_from_cache

    def get_games_from_bgg(self, bgg: BGGClient, game_ids, no_cache: bool = False) -> List[BoardGame]:
        uncached_games = []
        found_cache_games = []
        if not no_cache:
            found_cache_games = self.__get_games_from_cache(game_ids)
        cached_ids = self.__extract_ids_from_games(found_cache_games)
        game_list_not_found = [id for id in game_ids if id not in cached_ids]
        if game_list_not_found:
            uncached_games = bgg.game_list(game_list_not_found)
            for game in uncached_games:
                self.game_cache.save(game)
        all_games = found_cache_games + uncached_games
        logging.info(f"{len(all_games)} BGG games loaded, "
                     f"{len(found_cache_games)} from cache, "
                     f"{len(uncached_games)} from BGG")
        return found_cache_games + uncached_games

    def __extract_ids_from_games(self, games: List[BoardGame]):
        return [game.id for game in games]


class BoardGameSelector(BoardGameLoadGameFromBgg, metaclass=abc.ABCMeta):

    def __init__(self, ids: List[str], game_cache: GameCache, field_reduction: FieldReduction):
        super().__init__(game_cache)
        self._ids = ids
        self.game_cache = game_cache
        self._field_reduction = field_reduction

    def __del__(self):
        self.game_cache.close()

    def __get_games(self, ids: List[str]) -> List[BoardGame]:
        games = []
        for bgg_id in ids:
            games = games + self.get_games_for_id(bgg_id)
        games.sort(key=lambda base_game: base_game.name)
        return games

    def get_games_matching_filter(self, game_filter: filter_processor) -> List[dict]:
        filtered_games = [item for item in self.__get_games(self._ids) if not game_filter.filter_game(item)]
        return self._field_reduction.clean_response(filtered_games)

    @abc.abstractmethod
    def get_games_for_id(self, bgg_id: str) -> List[BoardGame]:
        pass


class BoardGamePlayerSelector(BoardGameSelector):

    def __init__(self, bgg: BGGClient, game_cache: GameCache, field_reduction: FieldReduction, users: List[str]):
        super().__init__(users, game_cache, field_reduction)
        self._bgg = bgg

    def get_games_for_id(self, player: str) -> List[BoardGame]:
        try:
            game_list = self._bgg.collection(player, own=True).items
            return self.__get_games_from_collection_list(game_list)
        except BGGItemNotFoundError as error:
            raise BoardGameUserNotFoundError(error, f"No user found called '{player}'")

    def __get_games_from_collection_list(self, games: List[BoardGame]) -> List[BoardGame]:
        game_id_list = [game.id for game in games]
        return self.get_games_from_bgg(self._bgg, game_id_list)


class BoardGameGeekListSelector(BoardGameSelector):

    def __init__(self,
                 bgg: BGGClient,
                 bgg_legacy: BGGClientLegacy,
                 game_cache: GameCache,
                 field_reduction: FieldReduction,
                 geek_lists: List[str]):
        super().__init__(geek_lists, game_cache, field_reduction)
        self._bgg_legacy = bgg_legacy
        self._bgg = bgg

    def get_games_for_id(self, geek_list: str) -> List[BoardGame]:
        game_id_list = [list_obj.object.id for list_obj in self._bgg_legacy.geeklist(geek_list).items]
        if len(game_id_list) == 0:
            raise BoardGameListNotFoundError(f"List not found or contains no games '{geek_list}'")
        return self.__get_games_from_id_list(game_id_list)

    def __get_games_from_id_list(self, game_ids) -> List[BoardGame]:
        return self.get_games_from_bgg(self._bgg, game_ids)


class BoardGameCronRefresher(BoardGameLoadGameFromBgg):

    def __init__(self, bgg: BGGClient, game_cache: GameCache):
        super().__init__(game_cache)
        self._bgg = bgg

    def refresh_stale_games(self):
        game_id_list = self.game_cache.load_stale()
        if game_id_list and len(game_id_list) > 0:
            self.__get_games_from_id_list(game_id_list)

    def __get_games_from_id_list(self, game_ids) -> List[BoardGame]:
        return self.get_games_from_bgg(self._bgg, game_ids, no_cache=True)


class BoardGameSearch(object):

    def __init__(self, bgg: BGGClient):
        self._bgg = bgg

    def search_for_game(self, game_name) -> List[BaseGame]:
        if len(game_name) < 3:
            return []
        return self._bgg.search(game_name, exact=False)
