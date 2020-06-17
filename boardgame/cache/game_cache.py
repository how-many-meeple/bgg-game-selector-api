import json
import logging
import sys
from typing import Optional, List

import mysql.connector
from mysql.connector import Error

from boardgamegeek.objects.games import BoardGame

from boardgame.database import DatabaseConnectionDetails


class GameCache:
    def __init__(self, database_details: DatabaseConnectionDetails):
        try:
            connection = mysql.connector.connect(host=database_details.host,
                                                 database=database_details.database,
                                                 user=database_details.username,
                                                 password=database_details.password)
            self._conn = connection
            if self._conn.is_connected():
                self.prepare_schema()
        except Error:
            logging.error("Unable to load game cache", exc_info=sys.exc_info())

    def prepare_schema(self):
        if not self._conn.is_connected():
            return
        curs = self._conn.cursor()
        curs.execute('''CREATE TABLE IF NOT EXISTS cached_game (
            id INT NOT NULL PRIMARY KEY, 
            cache_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            data LONGTEXT)''')

        curs.close()

    def save(self, game: BoardGame):
        if not self._conn.is_connected():
            return
        binds = (game.id, json.dumps(game.data()))
        curs = self._conn.cursor(prepared=True)
        curs.execute('''REPLACE INTO cached_game VALUES (%s, NOW(), %s)''', binds)
        self._conn.commit()
        curs.close()

    def load(self, game_id: str) -> Optional[BoardGame]:
        if not self._conn.is_connected():
            return None
        binds = (game_id,)
        curs = self._conn.cursor(prepared=True)
        curs.execute('''SELECT data FROM cached_game WHERE id=%s''', binds)
        data = curs.fetchone()
        curs.close()
        return BoardGame(json.loads(data[0])) if data else None

    def load_stale(self) -> Optional[List[int]]:
        if not self._conn.is_connected():
            return None
        curs = self._conn.cursor()
        logging.info("Getting list of stale games...")
        curs.execute('''SELECT id FROM cached_game WHERE cache_timestamp <= NOW() - INTERVAL 7 DAY''')
        data = curs.fetchall()
        curs.close()
        data = [game_id for (game_id,) in data]
        logging.info(f"Found {len(data)} stale games")
        return data if data else None

    def last_refresh(self) -> Optional[str]:
        if not self._conn.is_connected():
            return None
        curs = self._conn.cursor()
        curs.execute('''SELECT MAX(cache_timestamp) FROM cached_game''')
        data = curs.fetchone()
        curs.close()
        return data[0].__str__() if data else None

    def close(self):
        if self._conn.is_connected():
            self._conn.close()
