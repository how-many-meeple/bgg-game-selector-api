package bgg.store

import bgg.domain.GameId
import bgg.vector.GameVector

import java.time.Instant

case class StoredVector(gameId: GameId, name: String, vector: GameVector, updatedAt: Instant)

trait VectorStore:
  def save(sv: StoredVector): Unit
  def load(id: GameId): Option[StoredVector]
  def loadAll(): List[StoredVector]

  /** Like [[loadAll]] but may serve a recent in-memory snapshot to avoid repeated full-table scans. Recommendation
    * scoring reads the whole corpus on every request; on a warm Lambda container this amortises the scan across
    * invocations. Callers that need strong freshness should use [[loadAll]].
    */
  def loadAllCached(): List[StoredVector] = loadAll()
