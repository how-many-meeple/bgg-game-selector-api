package bgg.cache

import bgg.SafeOps.{decodeJson, withStatement}
import bgg.domain.{GameData, GameId}
import com.typesafe.scalalogging.{Logger, StrictLogging}
import io.circe.syntax.*

import java.sql.{Connection, DriverManager}
import java.time.Instant

class SqliteGameCache(dbPath: String, ttlSeconds: Int) extends GameCache with AutoCloseable with StrictLogging:
  private val conn: Connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
  prepareSchema()

  private given Logger = logger

  def close(): Unit = conn.close()

  private def prepareSchema(): Unit =
    val sql =
      """CREATE TABLE IF NOT EXISTS cached_game (
         id TEXT NOT NULL PRIMARY KEY,
         cache_timestamp INTEGER NOT NULL,
         data TEXT NOT NULL)"""
    val stmt = conn.createStatement()
    stmt.execute(sql)
    stmt.close()

  def save(game: GameData): Unit =
    val sql =
      "INSERT INTO cached_game (id, cache_timestamp, data) SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM cached_game WHERE id=?)"
    withStatement(conn, sql) { ps =>
      ps.setString(1, game.id.asString)
      ps.setLong(2, Instant.now().getEpochSecond)
      ps.setString(3, game.asJson.noSpaces)
      ps.setString(4, game.id.asString)
      ps.executeUpdate()
    }
    logger.debug(s"Cached game ${game.id.value} (${game.name})")

  def load(id: GameId): Option[GameData] =
    withStatement(conn, "SELECT data FROM cached_game WHERE id=?") { ps =>
      ps.setString(1, id.asString)
      val rs = ps.executeQuery()
      val result =
        if rs.next() then decodeJson[GameData](rs.getString(1), s"game $id from cache")
        else None
      rs.close()
      result
    }

  def evictExpired(): Unit =
    val cutoff = Instant.now().getEpochSecond - ttlSeconds
    withStatement(conn, "DELETE FROM cached_game WHERE cache_timestamp < ?") { ps =>
      ps.setLong(1, cutoff)
      val deleted = ps.executeUpdate()
      if deleted > 0 then logger.info(s"Evicted $deleted expired games from cache")
    }
