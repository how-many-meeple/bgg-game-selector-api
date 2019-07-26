from unittest import TestCase
from unittest.mock import Mock

from boardgamegeek.objects.games import BoardGame

from boardgame.filter import ComplexityFilter


class TestComplexityFilter(TestCase):
    def test_filter_passes_to_successor(self):
        mock_filter = Mock(ComplexityFilter)
        mock_game = Mock(BoardGame)
        mock_game.rating_average_weight = None
        expansions_filter = ComplexityFilter(None, mock_filter)
        expansions_filter.filter(mock_game)
        mock_filter.filter.assert_called_once()

    def test_filter_returns_false_if_game_has_no_rating(self):
        mock_game = Mock(BoardGame)
        mock_game.rating_average_weight = None
        expansions_filter = ComplexityFilter('1')
        self.assertFalse(expansions_filter.filter(mock_game))

    def test_filter_returns_false_if_game_has_same_rating(self):
        mock_game = Mock(BoardGame)
        mock_game.rating_average_weight = 1
        expansions_filter = ComplexityFilter('1')
        self.assertFalse(expansions_filter.filter(mock_game))

    def test_filter_returns_true_if_game_has_greater_rating(self):
        mock_game = Mock(BoardGame)
        mock_game.rating_average_weight = 1.1
        expansions_filter = ComplexityFilter('1')
        self.assertTrue(expansions_filter.filter(mock_game))

    def test_filter_returns_false_if_game_has_lesser_rating(self):
        mock_game = Mock(BoardGame)
        mock_game.rating_average_weight = 0.9
        expansions_filter = ComplexityFilter('1')
        self.assertFalse(expansions_filter.filter(mock_game))
