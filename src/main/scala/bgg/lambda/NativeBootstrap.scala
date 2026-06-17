package bgg.lambda

import io.circe.generic.auto.*
import io.circe.parser.{decode as jsonDecode, parse}
import io.circe.syntax.*
import sttp.tapir.serverless.aws.lambda.*

import java.net.{HttpURLConnection, URI}
import java.nio.charset.StandardCharsets

object NativeBootstrap:

  private lazy val runtimeApi = sys.env("AWS_LAMBDA_RUNTIME_API")
  private lazy val handler = sys.env.getOrElse("LAMBDA_HANDLER", "api")
  private lazy val route = handler match
    case h if h.contains("PrefetchWorker") => buildWorkerRoute()
    case _                                 => buildApiRoute()

  private val corsHeaders = Map(
    "Access-Control-Allow-Origin" -> "*",
    "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers" -> "Content-Type, Bgg-Filter-Player-Count, Bgg-Filter-Using-Recommended-Players, Bgg-Filter-Min-Duration, Bgg-Filter-Max-Duration, Bgg-Filter-Complexity, Bgg-Filter-Min-Rating, Bgg-Filter-Mechanic, Bgg-Include-Expansions, Bgg-Field-Whitelist"
  )

  private def buildApiRoute(): String => String =
    val endpoints = ApiLambdaHandler.buildEndpoints()
    val interpreter = AwsSyncServerInterpreter(AwsSyncServerOptions.default)
    val apiRoute = interpreter.toRoute(endpoints.all)
    eventJson =>
      val isOptions = parse(eventJson).toOption
        .flatMap(_.hcursor.downField("httpMethod").as[String].toOption)
        .contains("OPTIONS")

      if isOptions then
        AwsResponse(
          isBase64Encoded = false,
          statusCode = 200,
          headers = corsHeaders,
          body = ""
        ).asJson.noSpaces
      else
        val response = jsonDecode[AwsRequestV1](eventJson) match
          case Right(v1) => apiRoute(v1.toV2)
          case Left(err) =>
            AwsResponse(
              isBase64Encoded = false,
              statusCode = 500,
              headers = Map("Content-Type" -> "application/json"),
              body = s"""{"error":"Failed to parse event: ${err.getMessage}"}"""
            )
        response.copy(headers = response.headers ++ corsHeaders).asJson.noSpaces

  private def buildWorkerRoute(): String => String =
    val worker = PrefetchWorkerLogic.create()
    eventJson => worker.handleSqsEvent(eventJson)

  def main(args: Array[String]): Unit =
    System.err.println(s"[bootstrap] Starting handler=$handler")
    pollLoop()

  @scala.annotation.tailrec
  private def pollLoop(): Nothing =
    val (requestId, event) = nextInvocation()
    try
      val response = route(event)
      postResponse(requestId, response)
    catch
      case e: Exception =>
        System.err.println(s"[bootstrap] Error processing $requestId: ${e.getMessage}")
        postError(requestId, e)
    pollLoop()

  private def nextInvocation(): (String, String) =
    withConnection(s"http://$runtimeApi/2018-06-01/runtime/invocation/next", "GET") { conn =>
      val requestId = conn.getHeaderField("Lambda-Runtime-Aws-Request-Id")
      val body = readBody(conn)
      (requestId, body)
    }

  private def postResponse(requestId: String, body: String): Unit =
    withConnection(s"http://$runtimeApi/2018-06-01/runtime/invocation/$requestId/response", "POST") { conn =>
      writeBody(conn, body)
      conn.getResponseCode: Unit
    }

  private def postError(requestId: String, e: Exception): Unit =
    withConnection(s"http://$runtimeApi/2018-06-01/runtime/invocation/$requestId/error", "POST") { conn =>
      val errorBody = s"""{"errorMessage":"${Option(e.getMessage).getOrElse(
          e.getClass.getName
        )}","errorType":"${e.getClass.getName}"}"""
      writeBody(conn, errorBody)
      conn.getResponseCode: Unit
    }

  private def withConnection[T](url: String, method: String)(f: HttpURLConnection => T): T =
    val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod(method)
    try f(conn)
    finally conn.disconnect()

  private def writeBody(conn: HttpURLConnection, body: String): Unit =
    conn.setDoOutput(true)
    conn.setRequestProperty("Content-Type", "application/json")
    val os = conn.getOutputStream
    os.write(body.getBytes(StandardCharsets.UTF_8))
    os.close()

  private def readBody(conn: HttpURLConnection): String =
    val source = scala.io.Source.fromInputStream(conn.getInputStream, "UTF-8")
    try source.mkString
    finally source.close()
