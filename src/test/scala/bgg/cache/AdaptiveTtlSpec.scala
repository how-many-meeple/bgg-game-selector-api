package bgg.cache

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit

class AdaptiveTtlSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-06-18T12:00:00Z")
  private val currentYear = 2026

  "AdaptiveTtl.computeTtl" should:
    "return 7 days for a game less than 1 year old" in:
      AdaptiveTtl.computeTtl(Some(currentYear), now) shouldBe Duration.ofDays(7)

    "return 30 days for a game 1 year old" in:
      AdaptiveTtl.computeTtl(Some(currentYear - 1), now) shouldBe Duration.ofDays(30)

    "return 30 days for a game 2 years old" in:
      AdaptiveTtl.computeTtl(Some(currentYear - 2), now) shouldBe Duration.ofDays(30)

    "return 90 days for a game more than 2 years old" in:
      AdaptiveTtl.computeTtl(Some(currentYear - 3), now) shouldBe Duration.ofDays(90)

    "return 90 days for a very old game" in:
      AdaptiveTtl.computeTtl(Some(2000), now) shouldBe Duration.ofDays(90)

    "return 90 days when yearPublished is None (unknown age treated as mature)" in:
      AdaptiveTtl.computeTtl(None, now) shouldBe Duration.ofDays(90)

  "AdaptiveTtl.isExpired" should:
    "return false when cache is fresh for a new game" in:
      val cachedAt = now.minus(6, ChronoUnit.DAYS)
      AdaptiveTtl.isExpired(cachedAt, Some(currentYear), now) shouldBe false

    "return true when cache exceeds 7 days for a new game" in:
      val cachedAt = now.minus(8, ChronoUnit.DAYS)
      AdaptiveTtl.isExpired(cachedAt, Some(currentYear), now) shouldBe true

    "return false when cache is within 30 days for a 1-2 year old game" in:
      val cachedAt = now.minus(29, ChronoUnit.DAYS)
      AdaptiveTtl.isExpired(cachedAt, Some(currentYear - 1), now) shouldBe false

    "return true when cache exceeds 30 days for a 1-2 year old game" in:
      val cachedAt = now.minus(31, ChronoUnit.DAYS)
      AdaptiveTtl.isExpired(cachedAt, Some(currentYear - 1), now) shouldBe true

    "return false when cache is within 90 days for a mature game" in:
      val cachedAt = now.minus(89, ChronoUnit.DAYS)
      AdaptiveTtl.isExpired(cachedAt, Some(2015), now) shouldBe false

    "return true when cache exceeds 90 days for a mature game" in:
      val cachedAt = now.minus(91, ChronoUnit.DAYS)
      AdaptiveTtl.isExpired(cachedAt, Some(2015), now) shouldBe true
