package bgg.prefetch

import bgg.SafeOps.{trySqlCall, withStatement}
import bgg.SqliteStore
import bgg.domain.SourceType

import java.time.Instant

class SqlitePrefetchStatusStore(dbPath: String)
    extends SqliteStore(
      dbPath,
      """CREATE TABLE IF NOT EXISTS prefetch_status (
         id TEXT NOT NULL PRIMARY KEY,
         source_type TEXT NOT NULL,
         source_id TEXT NOT NULL,
         status TEXT NOT NULL,
         reason TEXT NOT NULL DEFAULT '',
         expires_at INTEGER NOT NULL)"""
    )
    with PrefetchStatusStore:

  def get(sourceType: SourceType, sourceId: String): Option[PrefetchRecord] =
    withStatement(conn, "SELECT source_type, source_id, status, reason, expires_at FROM prefetch_status WHERE id=?") {
      ps =>
        ps.setString(1, statusKey(sourceType, sourceId))
        val rs = ps.executeQuery()
        val result =
          if rs.next() then
            val record = PrefetchRecord(
              sourceType = SourceType.fromString(rs.getString(1)).getOrElse(sourceType),
              sourceId = rs.getString(2),
              status = PrefetchStatus.fromDbKey(rs.getString(3)),
              reason = rs.getString(4),
              expiresAt = Instant.ofEpochSecond(rs.getLong(5))
            )
            Option.when(!record.isExpired)(record)
          else None
        rs.close()
        result
    }

  def set(sourceType: SourceType, sourceId: String, status: PrefetchStatus, reason: String = ""): Unit =
    val sql =
      """INSERT INTO prefetch_status (id, source_type, source_id, status, reason, expires_at)
         VALUES (?,?,?,?,?,?)
         ON CONFLICT(id) DO UPDATE SET status=excluded.status, reason=excluded.reason, expires_at=excluded.expires_at"""
    trySqlCall(
      withStatement(conn, sql) { ps =>
        ps.setString(1, statusKey(sourceType, sourceId))
        ps.setString(2, sourceType.toPathSegment)
        ps.setString(3, sourceId)
        ps.setString(4, status.dbKey)
        ps.setString(5, reason)
        ps.setLong(6, PrefetchTtl.expiresAt(status).getEpochSecond)
        ps.executeUpdate(): Unit
      },
      "Failed to write prefetch status"
    )
