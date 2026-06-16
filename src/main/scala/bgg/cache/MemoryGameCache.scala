package bgg.cache

import bgg.domain.{GameData, GameId}

import java.time.Instant
import scala.collection.concurrent.TrieMap

class MemoryGameCache(ttlSeconds: Int) extends GameCache:
  private val store: TrieMap[Int, (GameData, Long)] = TrieMap.empty

  def save(game: GameData): Unit =
    store.putIfAbsent(game.id.value, (game, Instant.now().getEpochSecond)): Unit

  def load(id: GameId): Option[GameData] =
    store
      .updateWith(id.value) {
        case Some((game, ts)) if Instant.now().getEpochSecond - ts < ttlSeconds => Some((game, ts))
        case _                                                                  => None
      }
      .map(_._1)

  def evictExpired(): Unit =
    val cutoff = Instant.now().getEpochSecond - ttlSeconds
    store.filterInPlace((_, v) => v._2 >= cutoff)
