from unittest import TestCase
from unittest.mock import Mock

from boardgamegeek.objects.games import BoardGame

from boardgame.filter import RatingFilter


class TestRatingFilter(TestCase):
    def test_filter_passes_to_successor(self):
        mock_filter = Mock(RatingFilter)
        mock_game = Mock(BoardGame)
        mock_game.rating_average = None
        rating_filter = RatingFilter(None)
        rating_filter.set_successor(mock_filter)
        rating_filter.filter(mock_game)
        mock_filter.filter.assert_called_once()

    def test_filter_returns_true_if_game_reqs_differ_than_desired_rating(self):
        mock_game = Mock(BoardGame)
        mock_game.rating_average = 2.1
        rating_filter = RatingFilter('2.2')
        self.assertTrue(rating_filter.filter(mock_game))

    def test_filter_returns_true_if_game_reqs_none(self):
        mock_game = Mock(BoardGame)
        mock_game.rating_average = None
        rating_filter = RatingFilter('1')
        self.assertTrue(rating_filter.filter(mock_game))

    def test_filter_returns_true_if_game_reqs_meet_wanted_rating(self):
        mock_game = Mock(BoardGame)
        mock_game.rating_average = 2.3
        rating_filter = RatingFilter('2')
        self.assertFalse(rating_filter.filter(mock_game))
        rating_filter = RatingFilter('2.3')
        self.assertFalse(rating_filter.filter(mock_game))
