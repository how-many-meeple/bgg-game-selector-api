package bgg.store

import bgg.domain.GameId
import bgg.vector.GameVector

import java.time.Instant

case class StoredVector(gameId: GameId, name: String, vector: GameVector, updatedAt: Instant)

trait VectorStore:
  def save(sv: StoredVector): Unit
  def load(id: GameId): Option[StoredVector]
  def loadAll(): List[StoredVector]
