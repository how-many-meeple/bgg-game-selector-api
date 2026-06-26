package bgg.cache

import bgg.domain.PlayData

trait PlaysCache:
  def save(username: String, plays: List[PlayData]): Unit
  def load(username: String): Option[List[PlayData]]

class NoOpPlaysCache extends PlaysCache:
  def save(username: String, plays: List[PlayData]): Unit = ()
  def load(username: String): Option[List[PlayData]] = None
