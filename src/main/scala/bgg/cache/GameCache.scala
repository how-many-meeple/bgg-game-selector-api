package bgg.cache

import bgg.domain.{GameData, GameId}

trait GameCache:
  def save(game: GameData): Unit
  def load(id: GameId): Option[GameData]
  def evictExpired(): Unit
