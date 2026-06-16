package bgg.prefetch

import bgg.domain.SourceType

import java.time.Instant

enum PrefetchStatus(val dbKey: String):
  case Pending extends PrefetchStatus("pending")
  case Processing extends PrefetchStatus("processing")
  case Completed extends PrefetchStatus("completed")
  case NotFound extends PrefetchStatus("not_found")
  case Failed extends PrefetchStatus("failed")

object PrefetchStatus:
  def fromDbKey(s: String): PrefetchStatus = s match
    case "pending"    => Pending
    case "processing" => Processing
    case "completed"  => Completed
    case "not_found"  => NotFound
    case _            => Failed

case class PrefetchRecord(
    sourceType: SourceType,
    sourceId: String,
    status: PrefetchStatus,
    reason: String,
    expiresAt: Instant
):
  def isExpired: Boolean = Instant.now().isAfter(expiresAt)

object PrefetchTtl:
  import PrefetchStatus.*
  import scala.concurrent.duration.*

  // Per-status TTLs matching the Python implementation
  def ttlFor(status: PrefetchStatus): FiniteDuration = status match
    case Pending    => 15.minutes
    case Processing => 15.minutes
    case Completed  => 24.hours
    case NotFound   => 24.hours
    case Failed     => 20.minutes

  def expiresAt(status: PrefetchStatus): Instant =
    Instant.now().plusSeconds(ttlFor(status).toSeconds)

trait PrefetchStatusStore:
  def get(sourceType: SourceType, sourceId: String): Option[PrefetchRecord]
  def set(sourceType: SourceType, sourceId: String, status: PrefetchStatus, reason: String = ""): Unit

  def isQueueable(sourceType: SourceType, sourceId: String): Boolean =
    get(sourceType, sourceId) match
      case None         => true
      case Some(record) => record.status == PrefetchStatus.Failed

  private[prefetch] def statusKey(sourceType: SourceType, sourceId: String): String =
    s"${sourceType.toPathSegment}:$sourceId"
