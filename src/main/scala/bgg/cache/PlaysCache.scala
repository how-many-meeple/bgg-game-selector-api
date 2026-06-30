package bgg.cache

import bgg.domain.PlayData

trait PlaysCache:
  def save(username: String, plays: List[PlayData]): Unit
  def load(username: String): Option[List[PlayData]]
  def isFresh(username: String, maxAgeSeconds: Long): Boolean
  def append(username: String, plays: List[PlayData]): Unit
  def maxPlayId(username: String): Option[Int]
  def touch(username: String): Unit

class NoOpPlaysCache extends PlaysCache:
  def save(username: String, plays: List[PlayData]): Unit = ()
  def load(username: String): Option[List[PlayData]] = None
  def isFresh(username: String, maxAgeSeconds: Long): Boolean = false
  def append(username: String, plays: List[PlayData]): Unit = ()
  def maxPlayId(username: String): Option[Int] = None
  def touch(username: String): Unit = ()
