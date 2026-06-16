package bgg.domain

// Open hierarchy — modules can extend with domain-specific subtypes
abstract class Fail

object Fail:
  case class NotFound(what: String) extends Fail
  case class Conflict(msg: String) extends Fail
  case class IncorrectInput(msg: String) extends Fail
  case class Unauthorized(msg: String) extends Fail
  case object Forbidden extends Fail
  // BGG-specific failures
  case class BggRateLimited(msg: String) extends Fail
  case class BggUserNotFound(user: String) extends Fail
  case class BggListNotFound(id: String) extends Fail
  case class PrefetchInProgress(status: String) extends Fail
