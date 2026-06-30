package bgg.lambda

import bgg.bggapi.BggClient
import bgg.cache.PlaysCache
import bgg.domain.SourceType
import bgg.prefetch.{PrefetchStatus, PrefetchStatusStore}
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Json, parser}

case class PlaysFetchPageInput(username: String, page: Int, sourceType: String, sourceId: String, cachedMaxPlayId: Option[Int])
case class PlaysFetchPageOutput(nextPage: Int, done: Boolean, totalSoFar: Int, username: String, sourceType: String, sourceId: String, cachedMaxPlayId: Option[Int])

class PlaysFetchPageLogic(
    bggClient: BggClient,
    playsCache: PlaysCache,
    prefetchStore: PrefetchStatusStore,
    playsCacheTtlSeconds: Long = 24L * 3600
) extends StrictLogging:

  def handle(eventJson: String): String =
    val input = parseInput(eventJson)
    val result = fetchPage(input)
    toJson(result)

  private def parseInput(json: String): PlaysFetchPageInput =
    parser.parse(json).toOption
      .flatMap { j =>
        for
          username <- j.hcursor.downField("username").as[String].toOption
          page <- j.hcursor.downField("page").as[Int].toOption
          st <- j.hcursor.downField("sourceType").as[String].toOption
          si <- j.hcursor.downField("sourceId").as[String].toOption
        yield
          val cachedMax = j.hcursor.downField("cachedMaxPlayId").as[Int].toOption
          PlaysFetchPageInput(username, page, st, si, cachedMax)
      }
      .getOrElse(throw RuntimeException("Invalid input JSON for PlaysFetchPage"))

  private def fetchPage(input: PlaysFetchPageInput): PlaysFetchPageOutput =
    if input.page == 1 && playsCache.isFresh(input.username, playsCacheTtlSeconds) then
      val total = countTotal(input.username)
      logger.info(s"Plays cache for ${input.username} is fresh ($total plays), skipping fetch")
      prefetchStore.set(SourceType.Plays, input.username, PrefetchStatus.Completed)
      return PlaysFetchPageOutput(
        nextPage = input.page,
        done = true,
        totalSoFar = total,
        username = input.username,
        sourceType = input.sourceType,
        sourceId = input.sourceId,
        cachedMaxPlayId = input.cachedMaxPlayId
      )

    val cachedMaxPlayId = if input.page == 1 then
      val maxId = playsCache.maxPlayId(input.username)
      prefetchStore.set(SourceType.Plays, input.username, PrefetchStatus.Processing)
      maxId
    else
      input.cachedMaxPlayId

    bggClient.fetchPlays(input.username, input.page) match
      case Right(plays) if plays.nonEmpty =>
        val (newPlays, hitCached) = cachedMaxPlayId match
          case Some(maxId) =>
            val fresh = plays.takeWhile(_.playId > maxId)
            (fresh, fresh.size < plays.size)
          case None =>
            (plays, false)

        if newPlays.nonEmpty then
          playsCache.append(input.username, newPlays)

        if hitCached then
          markPlaysComplete(input.username)
          val total = countTotal(input.username)
          logger.info(s"Caught up to cached plays for ${input.username} on page ${input.page} ($total total)")
          PlaysFetchPageOutput(
            nextPage = input.page,
            done = true,
            totalSoFar = total,
            username = input.username,
            sourceType = input.sourceType,
            sourceId = input.sourceId,
            cachedMaxPlayId = cachedMaxPlayId
          )
        else
          updatePagesFetched(input.username, input.page)
          val total = countTotal(input.username)
          logger.info(s"Fetched page ${input.page} for ${input.username}: ${newPlays.size} plays (total: $total)")
          PlaysFetchPageOutput(
            nextPage = input.page + 1,
            done = false,
            totalSoFar = total,
            username = input.username,
            sourceType = input.sourceType,
            sourceId = input.sourceId,
            cachedMaxPlayId = cachedMaxPlayId
          )

      case Right(_) =>
        markPlaysComplete(input.username)
        val total = countTotal(input.username)
        logger.info(s"Plays fetch complete for ${input.username} after ${input.page - 1} pages ($total plays)")
        PlaysFetchPageOutput(
          nextPage = input.page,
          done = true,
          totalSoFar = total,
          username = input.username,
          sourceType = input.sourceType,
          sourceId = input.sourceId,
          cachedMaxPlayId = cachedMaxPlayId
        )

      case Left(err) if input.page == 1 =>
        throw BggRateLimitedException(s"Failed to fetch first page of plays: $err")

      case Left(err) =>
        logger.warn(s"Error on page ${input.page} for ${input.username}: $err, finishing with what we have")
        markPlaysComplete(input.username)
        val total = countTotal(input.username)
        PlaysFetchPageOutput(
          nextPage = input.page,
          done = true,
          totalSoFar = total,
          username = input.username,
          sourceType = input.sourceType,
          sourceId = input.sourceId,
          cachedMaxPlayId = cachedMaxPlayId
        )

  private def markPlaysComplete(username: String): Unit =
    playsCache.touch(username)
    prefetchStore.set(SourceType.Plays, username, PrefetchStatus.Completed)

  private def updatePagesFetched(username: String, page: Int): Unit =
    prefetchStore.set(SourceType.Plays, username, PrefetchStatus.Processing, s"page:$page")

  private def countTotal(username: String): Int =
    playsCache.load(username).map(_.size).getOrElse(0)

  private def toJson(output: PlaysFetchPageOutput): String =
    Json.obj(
      "nextPage" -> Json.fromInt(output.nextPage),
      "done" -> Json.fromBoolean(output.done),
      "totalSoFar" -> Json.fromInt(output.totalSoFar),
      "username" -> Json.fromString(output.username),
      "sourceType" -> Json.fromString(output.sourceType),
      "sourceId" -> Json.fromString(output.sourceId),
      "cachedMaxPlayId" -> output.cachedMaxPlayId.fold(Json.fromInt(0))(Json.fromInt)
    ).noSpaces
