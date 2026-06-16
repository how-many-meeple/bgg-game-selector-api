package bgg.routes

import bgg.domain.Fail
import io.circe.generic.semiauto.*
import io.circe.{Codec, Decoder, Encoder}
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

case class ErrorResponse(error: String)
object ErrorResponse:
  given Codec[ErrorResponse] = deriveCodec

object ErrorOutput:
  private val failToStatus: Fail => (StatusCode, ErrorResponse) =
    case Fail.NotFound(what)        => (StatusCode.NotFound, ErrorResponse(what))
    case Fail.BggUserNotFound(user) => (StatusCode.NotFound, ErrorResponse(s"No user found called '$user'"))
    case Fail.BggListNotFound(id) => (StatusCode.NotFound, ErrorResponse(s"List not found or contains no games '$id'"))
    case Fail.IncorrectInput(msg) => (StatusCode.BadRequest, ErrorResponse(msg))
    case Fail.Conflict(msg)       => (StatusCode.Conflict, ErrorResponse(msg))
    case Fail.Unauthorized(msg)   => (StatusCode.Unauthorized, ErrorResponse(msg))
    case Fail.Forbidden           => (StatusCode.Forbidden, ErrorResponse("Forbidden"))
    case Fail.BggRateLimited(msg) => (StatusCode.ServiceUnavailable, ErrorResponse(msg))
    case Fail.PrefetchInProgress(status) => (StatusCode.Accepted, ErrorResponse(status))
    case _                               => (StatusCode.InternalServerError, ErrorResponse("Internal server error"))

  private val statusToFail: ((StatusCode, ErrorResponse)) => Fail =
    case (StatusCode.NotFound, e)           => Fail.NotFound(e.error)
    case (StatusCode.BadRequest, e)         => Fail.IncorrectInput(e.error)
    case (StatusCode.ServiceUnavailable, e) => Fail.BggRateLimited(e.error)
    case (_, e)                             => Fail.IncorrectInput(e.error)

  val failOutput: EndpointOutput[Fail] =
    (statusCode and jsonBody[ErrorResponse]).map(statusToFail)(failToStatus)

  val baseEndpoint: PublicEndpoint[Unit, Fail, Unit, Any] =
    endpoint.errorOut(failOutput)
