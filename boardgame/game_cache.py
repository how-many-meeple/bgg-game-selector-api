import json
import sqlite3
from typing import Optional

from boardgamegeek.objects.games import BoardGame


class GameCache:
    def __init__(self, cache_length, cache_file):
        self._conn = sqlite3.connect(cache_file)
        self.cache_length = cache_length
        self.prepare_schema()

    def prepare_schema(self):
        curs = self._conn.cursor()
        curs.execute('''CREATE TABLE IF NOT EXISTS cached_game (
            id NUMBER NOT NULL PRIMARY KEY, 
            cache_timestamp  DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
            data STRING)''')

    def save(self, game: BoardGame):
        binds = (game.id, json.dumps(game.data()), game.id)
        curs = self._conn.cursor()
        curs.execute('''INSERT INTO cached_game (id, data)
        SELECT ?, ?
        WHERE NOT EXISTS(SELECT 1 FROM cached_game WHERE id=?)''', binds)
        self._conn.commit()

    def load(self, game_id: str) -> Optional[BoardGame]:
        binds = (game_id,)
        curs = self._conn.cursor()
        curs.execute('''SELECT data FROM cached_game
        WHERE id=?''', binds)
        data = curs.fetchone()
        return BoardGame(json.loads(data[0])) if data else None

    def timeout_cache(self):
        curs = self._conn.cursor()
        curs.execute(
            f"DELETE FROM cached_game WHERE cache_timestamp < DATETIME(CURRENT_TIMESTAMP, '-{self.cache_length} "
            f"seconds')")
        self._conn.commit()
