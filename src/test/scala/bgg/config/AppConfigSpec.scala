package bgg.config

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AppConfigSpec extends AnyWordSpec with Matchers:

  "AppConfig.load()" should:
    "read all fields from typesafe config" in:
      System.setProperty("bgg.accessToken", "test-token-123")
      System.setProperty("bgg.timeoutSeconds", "30")
      System.setProperty("bgg.retries", "5")
      System.setProperty("bgg.retryDelaySeconds", "2")
      System.setProperty("cache.backend", "sqlite")
      System.setProperty("cache.requestCacheTtlSeconds", "1000")
      System.setProperty("cache.gameCacheTtlSeconds", "2000")
      System.setProperty("cache.vectorMinRatings", "75")
      System.setProperty("cache.sqliteRequestCachePath", "/tmp/req.sqlite")
      System.setProperty("cache.sqliteGameCachePath", "/tmp/game.sqlite")
      System.setProperty("cache.sqliteVectorStorePath", "/tmp/vec.sqlite")
      System.setProperty("cache.sqlitePrefetchStatusPath", "/tmp/pf.sqlite")
      System.setProperty("aws.region", "eu-west-1")
      System.setProperty("aws.dynamoRequestTable", "req-table")
      System.setProperty("aws.dynamoGameTable", "game-table")
      System.setProperty("aws.dynamoVectorTable", "vec-table")
      System.setProperty("aws.dynamoPrefetchTable", "pf-table")
      System.setProperty("aws.prefetchSqsUrl", "https://sqs.example.com/queue")
      System.setProperty("server.host", "127.0.0.1")
      System.setProperty("server.port", "9090")

      ConfigFactory.invalidateCaches()

      try
        val config = AppConfig.load()

        config.bgg.accessToken shouldBe "test-token-123"
        config.bgg.timeoutSeconds shouldBe 30
        config.bgg.retries shouldBe 5
        config.bgg.retryDelaySeconds shouldBe 2

        config.cache.backend shouldBe CacheBackend.SQLite
        config.cache.requestCacheTtlSeconds shouldBe 1000
        config.cache.gameCacheTtlSeconds shouldBe 2000
        config.cache.vectorMinRatings shouldBe 75
        config.cache.sqliteRequestCachePath shouldBe "/tmp/req.sqlite"
        config.cache.sqliteGameCachePath shouldBe "/tmp/game.sqlite"
        config.cache.sqliteVectorStorePath shouldBe "/tmp/vec.sqlite"
        config.cache.sqlitePrefetchStatusPath shouldBe "/tmp/pf.sqlite"

        config.aws.region shouldBe "eu-west-1"
        config.aws.dynamoRequestTable shouldBe "req-table"
        config.aws.dynamoGameTable shouldBe "game-table"
        config.aws.dynamoVectorTable shouldBe "vec-table"
        config.aws.dynamoPrefetchTable shouldBe "pf-table"
        config.aws.prefetchSqsUrl shouldBe "https://sqs.example.com/queue"

        config.server.host shouldBe "127.0.0.1"
        config.server.port shouldBe 9090
      finally
        System.clearProperty("bgg.accessToken")
        System.clearProperty("bgg.timeoutSeconds")
        System.clearProperty("bgg.retries")
        System.clearProperty("bgg.retryDelaySeconds")
        System.clearProperty("cache.backend")
        System.clearProperty("cache.requestCacheTtlSeconds")
        System.clearProperty("cache.gameCacheTtlSeconds")
        System.clearProperty("cache.vectorMinRatings")
        System.clearProperty("cache.sqliteRequestCachePath")
        System.clearProperty("cache.sqliteGameCachePath")
        System.clearProperty("cache.sqliteVectorStorePath")
        System.clearProperty("cache.sqlitePrefetchStatusPath")
        System.clearProperty("aws.region")
        System.clearProperty("aws.dynamoRequestTable")
        System.clearProperty("aws.dynamoGameTable")
        System.clearProperty("aws.dynamoVectorTable")
        System.clearProperty("aws.dynamoPrefetchTable")
        System.clearProperty("aws.prefetchSqsUrl")
        System.clearProperty("server.host")
        System.clearProperty("server.port")
        ConfigFactory.invalidateCaches()

    "map 'dynamodb' backend string to CacheBackend.DynamoDB" in:
      System.setProperty("cache.backend", "dynamodb")
      ConfigFactory.invalidateCaches()
      try
        val config = AppConfig.load()
        config.cache.backend shouldBe CacheBackend.DynamoDB
      finally
        System.clearProperty("cache.backend")
        ConfigFactory.invalidateCaches()

    "map 'memory' backend string to CacheBackend.Memory" in:
      System.setProperty("cache.backend", "memory")
      ConfigFactory.invalidateCaches()
      try
        val config = AppConfig.load()
        config.cache.backend shouldBe CacheBackend.Memory
      finally
        System.clearProperty("cache.backend")
        ConfigFactory.invalidateCaches()

    "map unknown backend string to CacheBackend.Memory" in:
      System.setProperty("cache.backend", "redis")
      ConfigFactory.invalidateCaches()
      try
        val config = AppConfig.load()
        config.cache.backend shouldBe CacheBackend.Memory
      finally
        System.clearProperty("cache.backend")
        ConfigFactory.invalidateCaches()

    "use defaults from application.conf when no overrides set" in:
      ConfigFactory.invalidateCaches()
      val config = AppConfig.load()

      config.bgg.timeoutSeconds shouldBe 60
      config.bgg.retries shouldBe 6
      config.bgg.retryDelaySeconds shouldBe 10
      config.cache.requestCacheTtlSeconds shouldBe 86400
      config.cache.gameCacheTtlSeconds shouldBe 604800
      config.cache.vectorMinRatings shouldBe 100
      config.aws.region shouldBe "us-east-1"
      config.server.host shouldBe "0.0.0.0"
      config.server.port shouldBe 8080
      config.server.allowedOrigins shouldBe List("*")
