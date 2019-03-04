from typing import List

from boardgamegeek import BGGClient, CacheBackendSqlite
from boardgamegeek.objects.games import CollectionBoardGame, BaseGame

from boardgame import FilterProcessor


class BoardGameFactory(object):

    @staticmethod
    def create_client():
        return BGGClient(cache=CacheBackendSqlite(path="cache.db", ttl=3600))

    @staticmethod
    def create_selector(player: str) -> 'BoardGameSelector':
        return BoardGameSelector(BoardGameFactory.create_client(), player)

    @staticmethod
    def create_search() -> 'BoardGameSearch':
        return BoardGameSearch(BoardGameFactory.create_client())


class BoardGameSelector(object):

    def __init__(self, bgg: BGGClient, player: str):
        self._bgg = bgg
        self.player = player

    def get_games(self) -> List[CollectionBoardGame]:
        return self._bgg.collection(self.player, own=True).items

    def get_games_matching_filter(self, game_filter: FilterProcessor):
        return [item for item in self.get_games() if not game_filter.filter_game(item)]


class BoardGameSearch(object):

    def __init__(self, bgg: BGGClient):
        self._bgg = bgg

    def search_for_game(self, game_name) -> List[BaseGame]:
        if len(game_name) < 3:
            return []
        return self._bgg.search(game_name, exact=False)
