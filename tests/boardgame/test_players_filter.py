from unittest import TestCase
from unittest.mock import Mock

from boardgamegeek.objects.games import BaseGame

from boardgame.filter import PlayersFilter


class TestPlayersFilter(TestCase):
    def test_filter_passes_to_successor(self):
        mock_filter = Mock(PlayersFilter)
        mock_game = Mock(BaseGame)
        duration_filter = PlayersFilter(None, mock_filter)
        duration_filter.filter(mock_game)
        mock_filter.filter.assert_called_once()

    def test_filter_returns_true_if_game_reqs_differ_than_wanted_players(self):
        mock_game = Mock(BaseGame)
        mock_game.min_players = 2
        mock_game.max_players = 5
        player_filter = PlayersFilter('1')
        self.assertTrue(player_filter.filter(mock_game))
        player_filter = PlayersFilter('6')
        self.assertTrue(player_filter.filter(mock_game))

    def test_filter_returns_true_if_game_reqs_meet_wanted_players(self):
        mock_game = Mock(BaseGame)
        mock_game.min_players = 2
        mock_game.max_players = 5
        player_filter = PlayersFilter('3')
        self.assertFalse(player_filter.filter(mock_game))
        player_filter = PlayersFilter('2')
        self.assertFalse(player_filter.filter(mock_game))
        player_filter = PlayersFilter('5')
        self.assertFalse(player_filter.filter(mock_game))
