package bgg.store

import bgg.SafeOps.{decodeJson, resultSetIterator, withStatement}
import bgg.SqliteStore
import bgg.domain.GameId
import bgg.vector.GameVector
import io.circe.syntax.*

import java.sql.ResultSet
import java.time.Instant

class SqliteVectorStore(dbPath: String)
    extends SqliteStore(
      dbPath,
      """CREATE TABLE IF NOT EXISTS game_vectors (
         game_id INTEGER NOT NULL PRIMARY KEY,
         name TEXT NOT NULL,
         vector TEXT NOT NULL,
         updated_at TEXT NOT NULL)"""
    )
    with VectorStore:

  def save(sv: StoredVector): Unit =
    withStatement(conn, "INSERT OR REPLACE INTO game_vectors (game_id, name, vector, updated_at) VALUES (?,?,?,?)") {
      ps =>
        ps.setInt(1, sv.gameId.value)
        ps.setString(2, sv.name)
        ps.setString(3, sv.vector.values.asJson.noSpaces)
        ps.setString(4, sv.updatedAt.toString)
        ps.executeUpdate(): Unit
    }

  def load(id: GameId): Option[StoredVector] =
    withStatement(conn, "SELECT game_id, name, vector, updated_at FROM game_vectors WHERE game_id=?") { ps =>
      ps.setInt(1, id.value)
      val rs = ps.executeQuery()
      val result = if rs.next() then rowToStoredVector(rs) else None
      rs.close()
      result
    }

  def loadAll(): List[StoredVector] =
    withStatement(conn, "SELECT game_id, name, vector, updated_at FROM game_vectors") { ps =>
      val rs = ps.executeQuery()
      val results = resultSetIterator(rs).flatMap(rowToStoredVector).toList
      rs.close()
      results
    }

  private def rowToStoredVector(rs: ResultSet): Option[StoredVector] =
    decodeJson[Vector[Double]](rs.getString(3), s"vector for game ${rs.getInt(1)}").map { vec =>
      StoredVector(
        gameId = GameId(rs.getInt(1)),
        name = rs.getString(2),
        vector = GameVector(vec),
        updatedAt = Instant.parse(rs.getString(4))
      )
    }
