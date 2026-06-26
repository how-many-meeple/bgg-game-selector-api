package bgg.config

// Config loaded from application.conf / environment variables at startup.
// Validated on load — invalid config fails fast rather than at first use.
case class AppConfig(
    bgg: BggConfig,
    cache: CacheConfig,
    aws: AwsConfig,
    server: ServerConfig
)

case class BggConfig(
    accessToken: String,
    timeoutSeconds: Int,
    retries: Int,
    retryDelaySeconds: Int
)

enum CacheBackend:
  case DynamoDB, SQLite, Memory

case class CacheConfig(
    backend: CacheBackend,
    requestCacheTtlSeconds: Int,
    gameCacheTtlSeconds: Int,
    vectorMinRatings: Int,
    sqliteRequestCachePath: String,
    sqliteGameCachePath: String,
    sqliteVectorStorePath: String,
    sqlitePrefetchStatusPath: String
)

case class AwsConfig(
    region: String,
    dynamoRequestTable: String,
    dynamoGameTable: String,
    dynamoVectorTable: String,
    dynamoPrefetchTable: String,
    dynamoPlaysTable: String,
    prefetchSqsUrl: String
)

case class ServerConfig(
    host: String,
    port: Int,
    allowedOrigins: List[String]
)

object AppConfig:
  def load(): AppConfig =
    import com.typesafe.config.ConfigFactory
    val c = ConfigFactory.load()

    val bgg = BggConfig(
      accessToken = c.getString("bgg.accessToken"),
      timeoutSeconds = c.getInt("bgg.timeoutSeconds"),
      retries = c.getInt("bgg.retries"),
      retryDelaySeconds = c.getInt("bgg.retryDelaySeconds")
    )

    val backendStr = c.getString("cache.backend")
    val backend = backendStr match
      case "dynamodb" => CacheBackend.DynamoDB
      case "sqlite"   => CacheBackend.SQLite
      case _          => CacheBackend.Memory

    val cache = CacheConfig(
      backend = backend,
      requestCacheTtlSeconds = c.getInt("cache.requestCacheTtlSeconds"),
      gameCacheTtlSeconds = c.getInt("cache.gameCacheTtlSeconds"),
      vectorMinRatings = c.getInt("cache.vectorMinRatings"),
      sqliteRequestCachePath = c.getString("cache.sqliteRequestCachePath"),
      sqliteGameCachePath = c.getString("cache.sqliteGameCachePath"),
      sqliteVectorStorePath = c.getString("cache.sqliteVectorStorePath"),
      sqlitePrefetchStatusPath = c.getString("cache.sqlitePrefetchStatusPath")
    )

    val aws = AwsConfig(
      region = c.getString("aws.region"),
      dynamoRequestTable = c.getString("aws.dynamoRequestTable"),
      dynamoGameTable = c.getString("aws.dynamoGameTable"),
      dynamoVectorTable = c.getString("aws.dynamoVectorTable"),
      dynamoPrefetchTable = c.getString("aws.dynamoPrefetchTable"),
      dynamoPlaysTable = c.getString("aws.dynamoPlaysTable"),
      prefetchSqsUrl = c.getString("aws.prefetchSqsUrl")
    )

    val server = ServerConfig(
      host = c.getString("server.host"),
      port = c.getInt("server.port"),
      allowedOrigins = {
        import scala.jdk.CollectionConverters.*
        c.getStringList("server.allowedOrigins").asScala.toList
      }
    )

    AppConfig(bgg, cache, aws, server)
