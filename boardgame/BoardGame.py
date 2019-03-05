from typing import List

from boardgamegeek import BGGClient, CacheBackendSqlite, BGGItemNotFoundError
from boardgamegeek.objects.games import CollectionBoardGame, BaseGame

from boardgame import FilterProcessor


class BoardGameUserNotFoundError(Exception):
    def __init__(self, error, message):
        self.error = error
        self.message = message


class BoardGameFactory(object):

    @staticmethod
    def create_client():
        return BGGClient(cache=CacheBackendSqlite(path="cache.db", ttl=3600))

    @staticmethod
    def create_selector(players: str) -> 'BoardGameSelector':
        player_list = [player.strip() for player in players.split(',')]
        return BoardGameSelector(BoardGameFactory.create_client(), player_list)

    @staticmethod
    def create_search() -> 'BoardGameSearch':
        return BoardGameSearch(BoardGameFactory.create_client())


class BoardGameSelector(object):

    def __init__(self, bgg: BGGClient, players: List[str]):
        self._bgg = bgg
        self.players = players

    def get_games(self) -> List[CollectionBoardGame]:
        games = []
        for player in self.players:
            games = games + self.__get_player_games(player)
        games.sort(key=lambda basegame: basegame.name)
        return games

    def __get_player_games(self, player) -> List[CollectionBoardGame]:
        try:
            return self._bgg.collection(player, own=True).items
        except BGGItemNotFoundError as error:
            raise BoardGameUserNotFoundError(error, f"No user found called '{player}'")

    def get_games_matching_filter(self, game_filter: FilterProcessor):
        return [item for item in self.get_games() if not game_filter.filter_game(item)]


class BoardGameSearch(object):

    def __init__(self, bgg: BGGClient):
        self._bgg = bgg

    def search_for_game(self, game_name) -> List[BaseGame]:
        if len(game_name) < 3:
            return []
        return self._bgg.search(game_name, exact=False)
