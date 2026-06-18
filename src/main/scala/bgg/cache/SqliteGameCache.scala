package bgg.cache

import bgg.SafeOps.{decodeJson, resultSetIterator, withStatement}
import bgg.SqliteStore
import bgg.domain.{GameData, GameId}
import io.circe.syntax.*

import java.time.Instant

class SqliteGameCache(dbPath: String)
    extends SqliteStore(
      dbPath,
      """CREATE TABLE IF NOT EXISTS cached_game (
         id TEXT NOT NULL PRIMARY KEY,
         cache_timestamp INTEGER NOT NULL,
         data TEXT NOT NULL)"""
    )
    with GameCache:

  def save(game: GameData, now: Instant): Unit =
    val sql =
      "INSERT INTO cached_game (id, cache_timestamp, data) SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM cached_game WHERE id=?)"
    withStatement(conn, sql) { ps =>
      ps.setString(1, game.id.asString)
      ps.setLong(2, now.getEpochSecond)
      ps.setString(3, game.asJson.noSpaces)
      ps.setString(4, game.id.asString)
      ps.executeUpdate(): Unit
    }
    logger.debug(s"Cached game ${game.id.value} (${game.name})")

  def load(id: GameId): Option[GameData] =
    withStatement(conn, "SELECT cache_timestamp, data FROM cached_game WHERE id=?") { ps =>
      ps.setString(1, id.asString)
      val rs = ps.executeQuery()
      val result =
        if rs.next() then
          val cachedAt = Instant.ofEpochSecond(rs.getLong(1))
          decodeJson[GameData](rs.getString(2), s"game $id from cache").filter { game =>
            !AdaptiveTtl.isExpired(cachedAt, game.yearPublished, Instant.now())
          }
        else None
      rs.close()
      result
    }

  def evictExpired(): Unit =
    withStatement(conn, "SELECT id, cache_timestamp, data FROM cached_game") { ps =>
      val rs = ps.executeQuery()
      val now = Instant.now()
      val expired = resultSetIterator(rs).flatMap { r =>
        val cachedAt = Instant.ofEpochSecond(r.getLong(2))
        decodeJson[GameData](r.getString(3), "game from cache").collect {
          case game if AdaptiveTtl.isExpired(cachedAt, game.yearPublished, now) => r.getString(1)
        }
      }.toList
      rs.close()
      if expired.nonEmpty then
        withStatement(conn, s"DELETE FROM cached_game WHERE id IN (${expired.map(_ => "?").mkString(",")})") { del =>
          expired.zipWithIndex.foreach { (id, i) => del.setString(i + 1, id) }
          val deleted = del.executeUpdate()
          logger.info(s"Evicted $deleted expired games from cache")
        }
    }
