package bgg

import bgg.cache.DynamoDbGameCache
import bgg.domain.*
import bgg.prefetch.{DynamoDbPrefetchStatusStore, PrefetchStatus}
import bgg.store.{DynamoDbVectorStore, StoredVector}
import bgg.vector.GameVector
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.Instant
import scala.jdk.CollectionConverters.*

class DynamoDbIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5"))
    .withServices(Service.DYNAMODB)

  private var client: DynamoDbClient = _

  override def beforeAll(): Unit =
    localstack.start()
    client = DynamoDbClient
      .builder()
      .endpointOverride(localstack.getEndpointOverride(Service.DYNAMODB))
      .region(Region.of(localstack.getRegion))
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(localstack.getAccessKey, localstack.getSecretKey)
        )
      )
      .build()

    createTable("game-cache", "id", ScalarAttributeType.S)
    createTable("vectors", "game_id", ScalarAttributeType.N)
    createTable("prefetch-status", "id", ScalarAttributeType.S)

  override def afterAll(): Unit =
    if client != null then client.close()
    localstack.stop()

  private def createTable(name: String, keyName: String, keyType: ScalarAttributeType): Unit =
    client.createTable(
      CreateTableRequest
        .builder()
        .tableName(name)
        .keySchema(KeySchemaElement.builder().attributeName(keyName).keyType(KeyType.HASH).build())
        .attributeDefinitions(
          AttributeDefinition.builder().attributeName(keyName).attributeType(keyType).build()
        )
        .billingMode(BillingMode.PAY_PER_REQUEST)
        .build()
    ): Unit

  private val testGame = GameData(
    id = GameId(174430),
    name = "Gloomhaven",
    yearPublished = Some(2017),
    minPlayers = Some(1),
    maxPlayers = Some(4),
    minPlayingTime = Some(60),
    maxPlayingTime = Some(120),
    playingTime = Some(120),
    ratingAverage = Some(8.7),
    ratingAverageWeight = Some(3.86),
    expansion = false,
    mechanics = List("Hand Management", "Campaign"),
    categories = List("Fantasy", "Adventure"),
    playerSuggestions = Nil,
    usersRated = Some(50000)
  )

  "DynamoDbGameCache" should:
    "save and load a game" in:
      val cache = DynamoDbGameCache(client, "game-cache", 3600)

      cache.save(testGame)
      val loaded = cache.load(GameId(174430))

      loaded shouldBe defined
      loaded.get.name shouldBe "Gloomhaven"
      loaded.get.id shouldBe GameId(174430)
      loaded.get.yearPublished shouldBe Some(2017)
      loaded.get.mechanics shouldBe List("Hand Management", "Campaign")

    "return None for unknown game" in:
      val cache = DynamoDbGameCache(client, "game-cache", 3600)
      cache.load(GameId(999999)) shouldBe None

    "not overwrite existing game (conditional put)" in:
      val cache = DynamoDbGameCache(client, "game-cache", 3600)
      val modified = testGame.copy(name = "MODIFIED")

      cache.save(testGame)
      cache.save(modified)

      val loaded = cache.load(GameId(174430))
      loaded.get.name shouldBe "Gloomhaven"

  "DynamoDbVectorStore" should:
    "save and load a vector" in:
      val store = DynamoDbVectorStore(client, "vectors")
      val vector = GameVector(Vector.fill(155)(0.5))
      val now = Instant.parse("2024-01-15T10:30:00Z")

      store.save(StoredVector(GameId(100), "Test Game", vector, now))
      val loaded = store.load(GameId(100))

      loaded shouldBe defined
      loaded.get.gameId shouldBe GameId(100)
      loaded.get.name shouldBe "Test Game"
      loaded.get.vector.values should have size 155
      loaded.get.updatedAt shouldBe now

    "return None for unknown game" in:
      val store = DynamoDbVectorStore(client, "vectors")
      store.load(GameId(888888)) shouldBe None

    "upsert vector on second save" in:
      val store = DynamoDbVectorStore(client, "vectors")
      val v1 = GameVector(Vector.fill(155)(0.1))
      val v2 = GameVector(Vector.fill(155)(0.9))
      val now = Instant.now()

      store.save(StoredVector(GameId(200), "Game V1", v1, now))
      store.save(StoredVector(GameId(200), "Game V2", v2, now))

      val loaded = store.load(GameId(200))
      loaded.get.name shouldBe "Game V2"
      loaded.get.vector.values.head shouldBe 0.9

    "loadAll returns all stored vectors" in:
      val store = DynamoDbVectorStore(client, "vectors")
      val all = store.loadAll()
      all.size should be >= 2

  "DynamoDbPrefetchStatusStore" should:
    "set and get prefetch status" in:
      val store = DynamoDbPrefetchStatusStore(client, "prefetch-status")

      store.set(SourceType.Collection, "testuser", PrefetchStatus.Pending)
      val record = store.get(SourceType.Collection, "testuser")

      record shouldBe defined
      record.get.status shouldBe PrefetchStatus.Pending
      record.get.sourceId shouldBe "testuser"

    "update status on subsequent set" in:
      val store = DynamoDbPrefetchStatusStore(client, "prefetch-status")

      store.set(SourceType.Collection, "testuser2", PrefetchStatus.Pending)
      store.set(SourceType.Collection, "testuser2", PrefetchStatus.Completed)

      val record = store.get(SourceType.Collection, "testuser2")
      record.get.status shouldBe PrefetchStatus.Completed

    "return None for unknown key" in:
      val store = DynamoDbPrefetchStatusStore(client, "prefetch-status")
      store.get(SourceType.Collection, "nobody") shouldBe None

    "store and retrieve reason" in:
      val store = DynamoDbPrefetchStatusStore(client, "prefetch-status")

      store.set(SourceType.GeeKList, "99999", PrefetchStatus.NotFound, "List not found")
      val record = store.get(SourceType.GeeKList, "99999")

      record.get.reason shouldBe "List not found"

    "handle different source types independently" in:
      val store = DynamoDbPrefetchStatusStore(client, "prefetch-status")

      store.set(SourceType.Collection, "user1", PrefetchStatus.Completed)
      store.set(SourceType.GeeKList, "user1", PrefetchStatus.Failed, "timeout")

      store.get(SourceType.Collection, "user1").get.status shouldBe PrefetchStatus.Completed
      store.get(SourceType.GeeKList, "user1").get.status shouldBe PrefetchStatus.Failed
