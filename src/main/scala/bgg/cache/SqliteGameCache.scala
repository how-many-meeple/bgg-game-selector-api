package bgg.cache

import bgg.domain.{GameData, GameId}
import com.typesafe.scalalogging.StrictLogging
import io.circe.parser.decode
import io.circe.syntax.*

import java.sql.{Connection, DriverManager}
import java.time.Instant

class SqliteGameCache(dbPath: String, ttlSeconds: Int) extends GameCache with AutoCloseable with StrictLogging:
  private val conn: Connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
  prepareSchema()

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
    val ps = conn.prepareStatement(sql)
    ps.setString(1, game.id.value.toString)
    ps.setLong(2, Instant.now().getEpochSecond)
    ps.setString(3, game.asJson.noSpaces)
    ps.setString(4, game.id.value.toString)
    ps.executeUpdate()
    ps.close()
    logger.debug(s"Cached game ${game.id.value} (${game.name})")

  def load(id: GameId): Option[GameData] =
    val sql = "SELECT data FROM cached_game WHERE id=?"
    val ps  = conn.prepareStatement(sql)
    ps.setString(1, id.value.toString)
    val rs = ps.executeQuery()
    val result =
      if rs.next() then
        decode[GameData](rs.getString(1)) match
          case Right(g) => Some(g)
          case Left(e)  =>
            logger.error(s"Failed to decode game $id from cache: $e")
            None
      else None
    rs.close()
    ps.close()
    result

  def evictExpired(): Unit =
    val cutoff = Instant.now().getEpochSecond - ttlSeconds
    val sql    = "DELETE FROM cached_game WHERE cache_timestamp < ?"
    val ps     = conn.prepareStatement(sql)
    ps.setLong(1, cutoff)
    val deleted = ps.executeUpdate()
    ps.close()
    if deleted > 0 then logger.info(s"Evicted $deleted expired games from cache")
