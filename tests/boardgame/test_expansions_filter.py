from unittest import TestCase
from unittest.mock import Mock

from boardgamegeek.objects.games import BoardGame

from boardgame.filter import ExpansionsFilter


class TestExpansionsFilter(TestCase):
    def test_filter_passes_to_successor(self):
        mock_filter = Mock(ExpansionsFilter)
        mock_game = Mock(BoardGame)
        expansions_filter = ExpansionsFilter(None)
        expansions_filter.set_successor(mock_filter)
        expansions_filter.filter(mock_game)
        mock_filter.filter.assert_called_once()

    def test_filter_returns_true_if_game_is_an_expansion_and_we_dont_want_them(self):
        mock_game = Mock(BoardGame)
        mock_game.expansion = True
        expansions_filter = ExpansionsFilter('false')
        self.assertTrue(expansions_filter.filter(mock_game))

    def test_filter_returns_false_if_game_is_not_expansion(self):
        mock_game = Mock(BoardGame)
        mock_game.expansion = False
        expansions_filter = ExpansionsFilter('false')
        self.assertFalse(expansions_filter.filter(mock_game))

    def test_filter_returns_false_if_game_is_an_expansion_and_we_want_them(self):
        mock_game = Mock(BoardGame)
        mock_game.expansion = True
        expansions_filter = ExpansionsFilter('true')
        self.assertFalse(expansions_filter.filter(mock_game))
