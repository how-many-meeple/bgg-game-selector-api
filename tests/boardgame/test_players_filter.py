from unittest import TestCase
from unittest.mock import Mock

from boardgamegeek.objects.games import BaseGame, PlayerSuggestion

from boardgame.filter import PlayersFilter


class TestPlayersFilter(TestCase):
    def test_filter_passes_to_successor(self):
        mock_filter = Mock(PlayersFilter)
        mock_game = Mock(BaseGame)
        duration_filter = PlayersFilter(None, None, mock_filter)
        duration_filter.filter(mock_game)
        mock_filter.filter.assert_called_once()

    def test_filter_returns_true_if_game_reqs_differ_than_wanted_players(self):
        mock_game = Mock(BaseGame)
        mock_game.min_players = 2
        mock_game.max_players = 5
        player_filter = PlayersFilter('1', 'false')
        self.assertTrue(player_filter.filter(mock_game))
        player_filter = PlayersFilter('6', 'false')
        self.assertTrue(player_filter.filter(mock_game))

    def test_filter_returns_true_if_game_reqs_meet_wanted_players(self):
        mock_game = Mock(BaseGame)
        mock_game.min_players = 2
        mock_game.max_players = 5
        player_filter = PlayersFilter('3', 'false')
        self.assertFalse(player_filter.filter(mock_game))
        player_filter = PlayersFilter('2', 'false')
        self.assertFalse(player_filter.filter(mock_game))
        player_filter = PlayersFilter('5', 'false')
        self.assertFalse(player_filter.filter(mock_game))

    def test_filter_returns_false_only_if_game_reqs_meet_recommendations_players_want(self):
        mock_game = Mock(BaseGame)
        mock_game.player_suggestions = [
            PlayerSuggestion({"player_count": "1", "best": 5, "recommended": 2, "not_recommended": 0}),
            PlayerSuggestion({"player_count": "4", "best": 2, "recommended": 4, "not_recommended": 3}),
            PlayerSuggestion({"player_count": "5", "best": 1, "recommended": 2, "not_recommended": 3})
        ]
        mock_game.min_players = 2
        mock_game.max_players = 5
        player_filter = PlayersFilter('4', None)
        self.assertFalse(player_filter.filter(mock_game))
        player_filter = PlayersFilter('1', None)
        self.assertFalse(player_filter.filter(mock_game))
        player_filter = PlayersFilter('5', None)
        self.assertTrue(player_filter.filter(mock_game))
