from unittest import TestCase
from unittest.mock import Mock

from boardgamegeek import BGGClient

from boardgame.board_game import BoardGameSearch


class TestBoardGameSearch(TestCase):
    def test_search_for_game_less_than_3_chars(self):
        mock_client = Mock()
        search = BoardGameSearch(mock_client)
        self.assertEqual(search.search_for_game(game_name="no"), [])
        mock_client.search.assert_not_called()

    def test_search_for_game_3_or_more(self):
        game_name = "dix"
        expected_data = "data"
        mock_client = Mock()
        search = BoardGameSearch(mock_client)
        mock_client.search.return_value = expected_data
        self.assertEqual(search.search_for_game(game_name), expected_data)
        mock_client.search.assert_called_with(game_name, exact=False)
