from unittest import TestCase
from unittest.mock import Mock

from boardgamegeek.objects.games import BoardGame

from boardgame.filter import ComplexityFilter


def make_game(weight):
    game = Mock(spec=BoardGame)
    game.rating_average_weight = weight
    return game


class TestComplexityFilter(TestCase):

    # RIGHT: Are the results right?
    # Filter target = 3.0, range = +/-1.0, so 2.0-4.0 is included

    def test_game_at_target_is_included(self):
        f = ComplexityFilter("3.0")
        self.assertFalse(f.filter(make_game(3.0)))

    def test_game_within_range_below_is_included(self):
        f = ComplexityFilter("3.0")
        self.assertFalse(f.filter(make_game(2.0)))

    def test_game_within_range_above_is_included(self):
        f = ComplexityFilter("3.0")
        self.assertFalse(f.filter(make_game(4.0)))

    def test_game_just_outside_range_below_is_excluded(self):
        f = ComplexityFilter("3.0")
        self.assertTrue(f.filter(make_game(1.9)))

    def test_game_just_outside_range_above_is_excluded(self):
        f = ComplexityFilter("3.0")
        self.assertTrue(f.filter(make_game(4.1)))

    def test_no_filter_includes_all_games(self):
        f = ComplexityFilter(None)
        self.assertFalse(f.filter(make_game(5.0)))

    # BOUNDARY: Are all the boundary conditions correct?

    def test_game_with_no_weight_is_excluded_when_filter_active(self):
        f = ComplexityFilter("3.0")
        self.assertTrue(f.filter(make_game(None)))

    def test_game_with_no_weight_is_included_when_no_filter(self):
        f = ComplexityFilter(None)
        self.assertFalse(f.filter(make_game(None)))

    def test_filter_at_low_end_excludes_high_complexity(self):
        # User picks 1.0 (Beginner) — should not see heavy games
        f = ComplexityFilter("1.0")
        self.assertFalse(f.filter(make_game(1.5)))  # 1.5 within 1.0 +/- 1.0
        self.assertTrue(f.filter(make_game(2.1)))   # 2.1 outside range

    def test_filter_at_high_end_excludes_low_complexity(self):
        # User picks 5.0 (Expert) — should not see beginner games
        f = ComplexityFilter("5.0")
        self.assertFalse(f.filter(make_game(4.5)))  # 4.5 within 5.0 +/- 1.0
        self.assertTrue(f.filter(make_game(3.9)))   # 3.9 outside range

    # INVERSE: Can you check inverse relationships?

    def test_passes_to_successor_when_included(self):
        successor = Mock(ComplexityFilter)
        f = ComplexityFilter("3.0")
        f.set_successor(successor)
        f.filter(make_game(3.0))
        successor.filter.assert_called_once()

    def test_does_not_pass_to_successor_when_excluded(self):
        successor = Mock(ComplexityFilter)
        f = ComplexityFilter("3.0")
        f.set_successor(successor)
        f.filter(make_game(5.0))
        successor.filter.assert_not_called()

    def test_no_filter_passes_to_successor(self):
        successor = Mock(ComplexityFilter)
        f = ComplexityFilter(None)
        f.set_successor(successor)
        f.filter(make_game(None))
        successor.filter.assert_called_once()
