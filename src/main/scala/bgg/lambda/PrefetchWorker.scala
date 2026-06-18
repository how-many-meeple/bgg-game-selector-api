package bgg.lambda

import bgg.bggapi.{BggXmlClient, GameService}
import bgg.cache.{DynamoDbGameCache, DynamoDbRequestCache, NoOpRequestCache, SqliteGameCache}
import bgg.config.{AppConfig, CacheBackend}
import bgg.domain.{Fail, SourceType}
import bgg.prefetch.{DynamoDbPrefetchStatusStore, PrefetchStatus, PrefetchStatusStore, SqlitePrefetchStatusStore}
import bgg.store.{DynamoDbVectorStore, SqliteVectorStore}
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.typesafe.scalalogging.StrictLogging
import io.circe.{Decoder, Json}
import io.circe.parser.{decode, parse}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import sttp.client4.DefaultSyncBackend

import java.time.Instant
import scala.jdk.CollectionConverters.*

case class PrefetchMessage(sourceType: String, sourceId: String)
object PrefetchMessage:
  given Decoder[PrefetchMessage] = Decoder.forProduct2("source_type", "source_id")(PrefetchMessage.apply)

object PrefetchWorkerLogic:
  def create(): PrefetchWorkerLogic =
    val config = AppConfig.load()
    val httpBackend = DefaultSyncBackend()
    val bggClient = BggXmlClient(config.bgg, httpBackend)
    val dynamo = DynamoDbClient
      .builder()
      .region(Region.of(config.aws.region))
      .httpClient(UrlConnectionHttpClient.create())
      .build()
    val gameCache = DynamoDbGameCache(dynamo, config.aws.dynamoGameTable)
    val vectorStore = DynamoDbVectorStore(dynamo, config.aws.dynamoVectorTable)
    val requestCache = DynamoDbRequestCache(dynamo, config.aws.dynamoRequestTable)
    val prefetchStore = DynamoDbPrefetchStatusStore(dynamo, config.aws.dynamoPrefetchTable)
    val gameService =
      GameService(bggClient, gameCache, vectorStore, requestCache, config.cache.vectorMinRatings, () => Instant.now())
    PrefetchWorkerLogic(gameService, prefetchStore)

class PrefetchWorkerLogic(
    gameService: GameService,
    prefetchStore: PrefetchStatusStore
) extends StrictLogging:

  def handleSqsEvent(eventJson: String): String =
    parse(eventJson).flatMap(_.hcursor.downField("Records").as[List[Json]]) match
      case Left(err) =>
        logger.error(s"Failed to parse SQS event: ${err.getMessage}")
      case Right(records) =>
        records.foreach { record =>
          record.hcursor.downField("body").as[String] match
            case Right(body) =>
              decode[PrefetchMessage](body) match
                case Left(err)  => logger.error(s"Failed to parse SQS message body: $body", err)
                case Right(msg) => process(msg)
            case Left(err) =>
              logger.error(s"Failed to extract body from SQS record: ${err.getMessage}")
        }
    """{"statusCode":200}"""

  def process(msg: PrefetchMessage): Unit =
    SourceType.fromString(msg.sourceType) match
      case Left(err) =>
        logger.error(s"Invalid source_type in message: $err")
      case Right(sourceType) =>
        logger.info(s"Prefetching ${sourceType.toPathSegment}:${msg.sourceId}")
        prefetchStore.set(sourceType, msg.sourceId, PrefetchStatus.Processing)

        val result = sourceType match
          case SourceType.Collection => gameService.resolveCollection(msg.sourceId)
          case SourceType.GeeKList   => gameService.resolveGeeklist(msg.sourceId)
          case SourceType.Hot        => gameService.resolveHotGames()

        result match
          case Right(_) =>
            prefetchStore.set(sourceType, msg.sourceId, PrefetchStatus.Completed)
            logger.info(s"Prefetch complete for ${sourceType.toPathSegment}:${msg.sourceId}")
          case Left(Fail.BggUserNotFound(user)) =>
            val reason = s"No user found called '$user'"
            logger.warn(s"Not found ${sourceType.toPathSegment}:${msg.sourceId} — $reason")
            prefetchStore.set(sourceType, msg.sourceId, PrefetchStatus.NotFound, reason)
          case Left(Fail.BggListNotFound(id)) =>
            val reason = s"List not found or contains no games '$id'"
            logger.warn(s"Not found ${sourceType.toPathSegment}:${msg.sourceId} — $reason")
            prefetchStore.set(sourceType, msg.sourceId, PrefetchStatus.NotFound, reason)
          case Left(fail) =>
            val reason = fail.toString
            logger.error(s"Failed prefetch ${sourceType.toPathSegment}:${msg.sourceId} — $reason")
            prefetchStore.set(sourceType, msg.sourceId, PrefetchStatus.Failed, reason)

class PrefetchWorker extends RequestHandler[SQSEvent, Unit] with StrictLogging:

  private lazy val config = AppConfig.load()
  private lazy val logic = buildLogic()

  override def handleRequest(event: SQSEvent, context: Context): Unit =
    event.getRecords.asScala.foreach { record =>
      decode[PrefetchMessage](record.getBody) match
        case Left(err) =>
          logger.error(s"Failed to parse SQS message: ${record.getBody}", err)
        case Right(msg) =>
          logic.process(msg)
    }

  private def buildLogic(): PrefetchWorkerLogic =
    val httpBackend = DefaultSyncBackend()
    val bggClient = BggXmlClient(config.bgg, httpBackend)

    val (gameService, prefetchStore) = config.cache.backend match
      case CacheBackend.DynamoDB =>
        val dynamo = DynamoDbClient
          .builder()
          .region(Region.of(config.aws.region))
          .httpClient(UrlConnectionHttpClient.create())
          .build()
        val gameCache = DynamoDbGameCache(dynamo, config.aws.dynamoGameTable)
        val vectorStore = DynamoDbVectorStore(dynamo, config.aws.dynamoVectorTable)
        val requestCache = DynamoDbRequestCache(dynamo, config.aws.dynamoRequestTable)
        val prefetchStore = DynamoDbPrefetchStatusStore(dynamo, config.aws.dynamoPrefetchTable)
        val gameService =
          GameService(
            bggClient,
            gameCache,
            vectorStore,
            requestCache,
            config.cache.vectorMinRatings,
            () => Instant.now()
          )
        (gameService, prefetchStore)

      case _ =>
        val gameCache = SqliteGameCache(config.cache.sqliteGameCachePath)
        val vectorStore = SqliteVectorStore(config.cache.sqliteVectorStorePath)
        val prefetchStore = SqlitePrefetchStatusStore(config.cache.sqlitePrefetchStatusPath)
        val gameService =
          GameService(
            bggClient,
            gameCache,
            vectorStore,
            NoOpRequestCache(),
            config.cache.vectorMinRatings,
            () => Instant.now()
          )
        (gameService, prefetchStore)

    PrefetchWorkerLogic(gameService, prefetchStore)
