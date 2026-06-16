package bgg.prefetch

import bgg.domain.SourceType
import com.typesafe.scalalogging.StrictLogging

import java.sql.{Connection, DriverManager}
import java.time.Instant

class SqlitePrefetchStatusStore(dbPath: String) extends PrefetchStatusStore with AutoCloseable with StrictLogging:
  private val conn: Connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
  prepareSchema()

  def close(): Unit = conn.close()

  private def prepareSchema(): Unit =
    val sql =
      """CREATE TABLE IF NOT EXISTS prefetch_status (
         id TEXT NOT NULL PRIMARY KEY,
         source_type TEXT NOT NULL,
         source_id TEXT NOT NULL,
         status TEXT NOT NULL,
         reason TEXT NOT NULL DEFAULT '',
         expires_at INTEGER NOT NULL)"""
    val stmt = conn.createStatement()
    stmt.execute(sql)
    stmt.close()

  def get(sourceType: SourceType, sourceId: String): Option[PrefetchRecord] =
    val sql = "SELECT source_type, source_id, status, reason, expires_at FROM prefetch_status WHERE id=?"
    val ps  = conn.prepareStatement(sql)
    ps.setString(1, statusKey(sourceType, sourceId))
    val rs = ps.executeQuery()
    val result =
      if rs.next() then
        val expiresAt = Instant.ofEpochSecond(rs.getLong(5))
        val record    = PrefetchRecord(
          sourceType = SourceType.fromString(rs.getString(1)).getOrElse(sourceType),
          sourceId   = rs.getString(2),
          status     = parseStatus(rs.getString(3)),
          reason     = rs.getString(4),
          expiresAt  = expiresAt,
        )
        Option.when(!record.isExpired)(record)
      else None
    rs.close()
    ps.close()
    result

  def set(sourceType: SourceType, sourceId: String, status: PrefetchStatus, reason: String = ""): Unit =
    val sql =
      """INSERT INTO prefetch_status (id, source_type, source_id, status, reason, expires_at)
         VALUES (?,?,?,?,?,?)
         ON CONFLICT(id) DO UPDATE SET status=excluded.status, reason=excluded.reason, expires_at=excluded.expires_at"""
    val ps = conn.prepareStatement(sql)
    ps.setString(1, statusKey(sourceType, sourceId))
    ps.setString(2, sourceType.toPathSegment)
    ps.setString(3, sourceId)
    ps.setString(4, status.dbKey)
    ps.setString(5, reason)
    ps.setLong(6, PrefetchTtl.expiresAt(status).getEpochSecond)
    try ps.executeUpdate(): Unit
    catch case e: Exception => logger.error("Failed to write prefetch status", e)
    finally ps.close()

  private def parseStatus(s: String): PrefetchStatus = s match
    case "pending"    => PrefetchStatus.Pending
    case "processing" => PrefetchStatus.Processing
    case "completed"  => PrefetchStatus.Completed
    case "not_found"  => PrefetchStatus.NotFound
    case _            => PrefetchStatus.Failed
