package bgg.cache

import bgg.domain.{GameData, GameId}

import java.time.Instant
import scala.collection.concurrent.TrieMap

class MemoryGameCache extends GameCache:
  private val store: TrieMap[Int, (GameData, Instant)] = TrieMap.empty

  def save(game: GameData, now: Instant): Unit =
    store.putIfAbsent(game.id.value, (game, now)): Unit

  def load(id: GameId): Option[GameData] =
    val now = Instant.now()
    store.get(id.value).collect {
      case (game, cachedAt) if !AdaptiveTtl.isExpired(cachedAt, game.yearPublished, now) => game
    }

  def evictExpired(): Unit =
    val now = Instant.now()
    store.filterInPlace((_, v) => !AdaptiveTtl.isExpired(v._2, v._1.yearPublished, now))
