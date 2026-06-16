package bgg.cache

import bgg.domain.{GameData, GameId}

import java.time.Instant
import scala.collection.concurrent.TrieMap

// In-memory cache for testing and local development — not thread-safe for TTL, fine for single-request Lambda
class MemoryGameCache(ttlSeconds: Int) extends GameCache:
  // TrieMap for lock-free concurrent reads; acceptable for in-memory dev/test use
  private val store: TrieMap[Int, (GameData, Long)] = TrieMap.empty

  def save(game: GameData): Unit =
    store.putIfAbsent(game.id.value, (game, Instant.now().getEpochSecond)): Unit

  def load(id: GameId): Option[GameData] =
    store.get(id.value).flatMap { (game, ts) =>
      if Instant.now().getEpochSecond - ts < ttlSeconds then Some(game)
      else None
    }

  def evictExpired(): Unit =
    val cutoff = Instant.now().getEpochSecond - ttlSeconds
    store.filterInPlace((_, v) => v._2 >= cutoff)
