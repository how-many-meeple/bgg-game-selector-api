from unittest import TestCase
from unittest.mock import Mock

from boardgamegeek.objects.games import BoardGame

from boardgame.filter import DurationFilter


class TestDurationFilter(TestCase):
    def test_filter_passes_to_successor(self):
        mock_filter = Mock(DurationFilter)
        mock_game = Mock(BoardGame)
        duration_filter = DurationFilter(None, None, mock_filter)
        duration_filter.filter(mock_game)
        mock_filter.filter.assert_called_once()

    def test_filter_returns_true_when_min_less_than_requirement(self):
        duration_filter = DurationFilter('5', None)
        mock_game = Mock(BoardGame)
        mock_game.min_playing_time = 3
        self.assertTrue(duration_filter.filter(mock_game))

    def test_filter_returns_false_when_min_equal_or_greater_than_requirement(self):
        duration_filter = DurationFilter('5', None)
        mock_game = Mock(BoardGame)
        mock_game.min_playing_time = 5
        self.assertFalse(duration_filter.filter(mock_game))
        mock_game.min_playing_time = 6
        self.assertFalse(duration_filter.filter(mock_game))

    def test_filter_returns_true_when_max_more_than_requirement(self):
        duration_filter = DurationFilter(None, '5')
        mock_game = Mock(BoardGame)
        mock_game.max_playing_time = 6
        self.assertTrue(duration_filter.filter(mock_game))

    def test_filter_returns_false_when_max_equal_or_less_than_requirement(self):
        duration_filter = DurationFilter(None, '5')
        mock_game = Mock(BoardGame)
        mock_game.max_playing_time = 5
        self.assertFalse(duration_filter.filter(mock_game))
        mock_game.max_playing_time = 4
        self.assertFalse(duration_filter.filter(mock_game))
