from unittest import TestCase
from unittest.mock import Mock

from boardgamegeek.objects.games import BoardGame

from boardgame.filter import MechanicFilter


class TestMechanicFilter(TestCase):
    def test_filter_passes_to_successor(self):
        mock_filter = Mock(MechanicFilter)
        mock_game = Mock(BoardGame)
        mock_game.mechanics = None
        mechanic_filter = MechanicFilter(None, mock_filter)
        mechanic_filter.filter(mock_game)
        mock_filter.filter.assert_called_once()

    def test_filter_returns_true_if_game_has_no_mechanics(self):
        mock_game = Mock(BoardGame)
        mock_game.mechanics = None
        mechanic_filter = MechanicFilter('[Cooperative Play]')
        self.assertTrue(mechanic_filter.filter(mock_game))

    def test_filter_returns_false_if_game_matches_some_mechanics(self):
        mock_game = Mock(BoardGame)
        mock_game.mechanics = ["Cooperative Play"]
        mechanic_filter = MechanicFilter('[Cooperative Play, Card Drafting, Grid Movement]')
        self.assertFalse(mechanic_filter.filter(mock_game))

    def test_filter_returns_true_if_game_doesnt_match_mechanics(self):
        mock_game = Mock(BoardGame)
        mock_game.mechanics = ["Card Drafting"]
        mechanic_filter = MechanicFilter('[Cooperative Play, Grid Movement]')
        self.assertTrue(mechanic_filter.filter(mock_game))
