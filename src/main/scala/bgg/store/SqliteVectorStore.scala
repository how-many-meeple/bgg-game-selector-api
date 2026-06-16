package bgg.store

import bgg.domain.GameId
import bgg.vector.GameVector
import com.typesafe.scalalogging.StrictLogging
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}

import java.sql.{Connection, DriverManager}
import java.time.Instant

class SqliteVectorStore(dbPath: String) extends VectorStore with AutoCloseable with StrictLogging:
  private val conn: Connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
  prepareSchema()

  def close(): Unit = conn.close()

  private def prepareSchema(): Unit =
    val sql =
      """CREATE TABLE IF NOT EXISTS game_vectors (
         game_id INTEGER NOT NULL PRIMARY KEY,
         name TEXT NOT NULL,
         vector TEXT NOT NULL,
         updated_at TEXT NOT NULL)"""
    val stmt = conn.createStatement()
    stmt.execute(sql)
    stmt.close()

  def save(sv: StoredVector): Unit =
    val sql = "INSERT OR REPLACE INTO game_vectors (game_id, name, vector, updated_at) VALUES (?,?,?,?)"
    val ps  = conn.prepareStatement(sql)
    ps.setInt(1, sv.gameId.value)
    ps.setString(2, sv.name)
    ps.setString(3, sv.vector.values.asJson.noSpaces)
    ps.setString(4, sv.updatedAt.toString)
    ps.executeUpdate()
    ps.close()

  def load(id: GameId): Option[StoredVector] =
    val sql = "SELECT game_id, name, vector, updated_at FROM game_vectors WHERE game_id=?"
    val ps  = conn.prepareStatement(sql)
    ps.setInt(1, id.value)
    val rs = ps.executeQuery()
    val result = if rs.next() then rowToStoredVector(rs) else None
    rs.close()
    ps.close()
    result

  def loadAll(): List[StoredVector] =
    val sql = "SELECT game_id, name, vector, updated_at FROM game_vectors"
    val ps  = conn.prepareStatement(sql)
    val rs  = ps.executeQuery()
    val results = Iterator.continually(rs).takeWhile(_.next()).flatMap(rowToStoredVector).toList
    rs.close()
    ps.close()
    results

  private def rowToStoredVector(rs: java.sql.ResultSet): Option[StoredVector] =
    decode[Vector[Double]](rs.getString(3)) match
      case Left(e) =>
        logger.error(s"Failed to decode vector for game ${rs.getInt(1)}", e)
        None
      case Right(vec) =>
        Some(StoredVector(
          gameId    = GameId(rs.getInt(1)),
          name      = rs.getString(2),
          vector    = GameVector(vec),
          updatedAt = Instant.parse(rs.getString(4)),
        ))
