import abc
import logging
from typing import List

from boardgamegeek import BGGClient, BGGItemNotFoundError, BGGClientLegacy
from boardgamegeek.cache import CacheBackendNone
from boardgamegeek.objects.games import BaseGame, BoardGame
from werkzeug.datastructures import Headers

from boardgame import filter_processor
from boardgame.field_reduction import FieldReduction
from boardgame.game_cache import GameCache
from boardgame.game_list_cache import GameListCache

logger = logging.getLogger()


class BoardGameUserNotFoundError(Exception):
    def __init__(self, error, message):
        self.error = error
        self.message = message


class BoardGameListNotFoundError(Exception):
    def __init__(self, message):
        self.message = message


class BoardGameFactory(object):
    item_cache_duration = 86400  # one day in seconds
    game_cache_duration = 604800  # one week in seconds

    @staticmethod
    def create_client():
        return BGGClient(
            cache=CacheBackendNone(),
            timeout=60,
            retry_delay=10, retries=6)

    @staticmethod
    def create_legacy_client():
        return BGGClientLegacy(
            cache=CacheBackendNone(),
            timeout=60,
            retry_delay=10,
            retries=6)

    @staticmethod
    def create_game_cache():
        return GameCache(cache_length=BoardGameFactory.game_cache_duration, table_name="game_cache")

    @staticmethod
    def create_game_list_cache():
        return GameListCache(cache_length=BoardGameFactory.item_cache_duration, table_name="game_list_cache")

    @staticmethod
    def create_player_selector(players: str, headers: Headers) -> 'BoardGamePlayerSelector':
        field_reduction = FieldReduction.create_field_reduction(headers)
        player_list = [player.strip() for player in players.split(',')]
        return BoardGamePlayerSelector(BoardGameFactory.create_client(),
                                       BoardGameFactory.create_game_cache(),
                                       BoardGameFactory.create_game_list_cache(),
                                       field_reduction, player_list)

    @staticmethod
    def create_list_selector(list_ids: str, headers: Headers) -> 'BoardGameGeekListSelector':
        field_reduction = FieldReduction.create_field_reduction(headers)
        geek_list = [list_id.strip() for list_id in list_ids.split(',')]
        return BoardGameGeekListSelector(BoardGameFactory.create_client(),
                                         BoardGameFactory.create_legacy_client(),
                                         BoardGameFactory.create_game_cache(),
                                         BoardGameFactory.create_game_list_cache(),
                                         field_reduction,
                                         geek_list)

    @staticmethod
    def create_search() -> 'BoardGameSearch':
        return BoardGameSearch(BoardGameFactory.create_client())


class BoardGameSelector(metaclass=abc.ABCMeta):

    def __init__(self,
                 ids: List[str],
                 game_cache: GameCache,
                 game_list_cache: GameListCache,
                 field_reduction: FieldReduction):
        self._game_list_cache = game_list_cache
        self._ids = ids
        self.game_cache = game_cache
        self._field_reduction = field_reduction

    def __get_games(self, ids: List[str]) -> List[BoardGame]:
        games = []
        for bgg_id in ids:
            games = games + self.get_games_for_id(bgg_id)
        games.sort(key=lambda base_game: base_game.name)
        return games

    def __get_games_from_cache(self, ids: list):
        games_from_cache = []
        for game_id in ids:
            game = self.game_cache.load(game_id)
            if game:
                games_from_cache = games_from_cache + [game]
        return games_from_cache

    def get_games_from_bgg(self, bgg: BGGClient, game_ids) -> List[BoardGame]:
        uncached_games = []
        found_cache_games = self.__get_games_from_cache(game_ids)
        cached_ids = self.__extract_ids_from_games(found_cache_games)
        game_list_not_found = [id for id in game_ids if id not in cached_ids]
        if game_list_not_found:
            uncached_games = bgg.game_list(game_list_not_found)
            for game in uncached_games:
                self.game_cache.save(game)
        return found_cache_games + uncached_games

    def __extract_ids_from_games(self, games: List[BoardGame]):
        return [game.id for game in games]

    def get_games_matching_filter(self, game_filter: filter_processor) -> List[dict]:
        filtered_games = [item for item in self.__get_games(self._ids) if not game_filter.filter_game(item)]
        return self._field_reduction.clean_response(filtered_games)

    @abc.abstractmethod
    def get_games_for_id(self, bgg_id: str) -> List[BoardGame]:
        pass


class BoardGamePlayerSelector(BoardGameSelector):

    def __init__(self,
                 bgg: BGGClient,
                 game_cache: GameCache,
                 game_list_cache: GameListCache,
                 field_reduction: FieldReduction,
                 users: List[str]):
        super().__init__(users, game_cache, game_list_cache, field_reduction)
        self._bgg = bgg

    def get_games_for_id(self, player: str) -> List[BoardGame]:
        try:
            game_id_list = self._game_list_cache.collection_games(collection=player,
                                                                  client=self._bgg)
            return self.get_games_from_bgg(self._bgg, game_id_list)
        except BGGItemNotFoundError as error:
            raise BoardGameUserNotFoundError(error, f"No user found called '{player}'")


class BoardGameGeekListSelector(BoardGameSelector):

    def __init__(self,
                 bgg: BGGClient,
                 bgg_legacy: BGGClientLegacy,
                 game_cache: GameCache,
                 game_list_cache: GameListCache,
                 field_reduction: FieldReduction,
                 geek_lists: List[str]):
        super().__init__(geek_lists, game_cache, game_list_cache, field_reduction)
        self._bgg_legacy = bgg_legacy
        self._bgg = bgg

    def get_games_for_id(self, geek_list: str) -> List[BoardGame]:
        game_id_list = self._game_list_cache.geeklist_games(geek_list=geek_list,
                                                            client=self._bgg_legacy)
        if len(game_id_list) == 0:
            raise BoardGameListNotFoundError(f"List not found or contains no games '{geek_list}'")
        return self.__get_games_from_id_list(game_id_list)

    def __get_games_from_id_list(self, game_ids) -> List[BoardGame]:
        return self.get_games_from_bgg(self._bgg, game_ids)


class BoardGameSearch(object):

    def __init__(self, bgg: BGGClient):
        self._bgg = bgg

    def search_for_game(self, game_name) -> List[BaseGame]:
        if len(game_name) < 3:
            return []
        return self._bgg.search(game_name, exact=False)
