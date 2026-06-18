package bgg.cache

import java.time.{Duration, Instant, Year, ZoneOffset}

object AdaptiveTtl:
  val NewGameTtl: Duration = Duration.ofDays(7)
  val StabilisingTtl: Duration = Duration.ofDays(30)
  val MatureTtl: Duration = Duration.ofDays(90)

  private val NewGameMaxAge = 1
  private val StabilisingMaxAge = 2

  def computeTtl(yearPublished: Option[Int], now: Instant): Duration =
    val currentYear = Year.from(now.atOffset(ZoneOffset.UTC)).getValue
    val age = yearPublished.map(y => currentYear - y).getOrElse(Int.MaxValue)
    if age < NewGameMaxAge then NewGameTtl
    else if age <= StabilisingMaxAge then StabilisingTtl
    else MatureTtl

  def isExpired(cachedAt: Instant, yearPublished: Option[Int], now: Instant): Boolean =
    val ttl = computeTtl(yearPublished, now)
    cachedAt.plus(ttl).isBefore(now)
