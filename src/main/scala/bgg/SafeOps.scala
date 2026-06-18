package bgg

import com.typesafe.scalalogging.Logger
import io.circe.{Decoder, parser}

import java.sql.{Connection, PreparedStatement, ResultSet}

object SafeOps:
  def decodeJson[T: Decoder](json: String, context: String)(using logger: Logger): Option[T] =
    parser.decode[T](json) match
      case Right(v) => Some(v)
      case Left(e) =>
        logger.error(s"Failed to decode $context", e)
        None

  def tryAwsCall[T](operation: => T, errorMsg: String)(using logger: Logger): Option[T] =
    try Some(operation)
    catch
      case e: Exception =>
        logger.error(errorMsg, e)
        None

  def trySqlCall(operation: => Unit, errorMsg: String)(using logger: Logger): Unit =
    try operation
    catch
      case e: Exception =>
        logger.error(errorMsg, e)

  def withStatement[T](conn: Connection, sql: String)(f: PreparedStatement => T): T =
    val ps = conn.prepareStatement(sql)
    try f(ps)
    finally ps.close()

  def resultSetIterator(rs: ResultSet): Iterator[ResultSet] =
    Iterator.continually(rs).takeWhile(_.next())
