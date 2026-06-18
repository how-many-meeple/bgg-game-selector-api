package bgg.cache

import bgg.domain.{GameData, GameId}

import java.time.Instant

trait GameCache:
  def save(game: GameData, now: Instant): Unit
  def load(id: GameId): Option[GameData]
  def evictExpired(): Unit
