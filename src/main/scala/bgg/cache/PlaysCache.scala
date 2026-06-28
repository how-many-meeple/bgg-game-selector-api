package bgg.cache

import bgg.domain.PlayData

trait PlaysCache:
  def save(username: String, plays: List[PlayData]): Unit
  def load(username: String): Option[List[PlayData]]
  def isFresh(username: String, maxAgeSeconds: Long): Boolean

class NoOpPlaysCache extends PlaysCache:
  def save(username: String, plays: List[PlayData]): Unit = ()
  def load(username: String): Option[List[PlayData]] = None
  def isFresh(username: String, maxAgeSeconds: Long): Boolean = false
