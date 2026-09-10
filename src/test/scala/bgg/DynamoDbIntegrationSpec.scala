package bgg

import bgg.cache.DynamoDbGameCache
import bgg.domain.*
import bgg.prefetch.{DynamoDbPrefetchStatusStore, PrefetchStatus}
import bgg.store.{DynamoDbVectorStore, StoredVector}
import bgg.vector.GameVector
import org.scalatest.{BeforeAndAfterAll, Canceled, Outcome}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*

import java.time.Instant

class DynamoDbIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5"))
    .withServices(Service.DYNAMODB)

  private var client: DynamoDbClient = _

  // These tests need a real DynamoDB via LocalStack, which requires Docker. When Docker is
  // unavailable (e.g. CI without a daemon), cancel each test rather than aborting the suite —
  // a missing environment is not a test failure.
  private lazy val dockerAvailable: Boolean =
    try DockerClientFactory.instance().isDockerAvailable
    catch case _: Throwable => false

  override def withFixture(test: NoArgTest): Outcome =
    if !dockerAvailable then Canceled("Docker not available — skipping DynamoDB integration tests")
    else super.withFixture(test)

  override def beforeAll(): Unit =
    if !dockerAvailable then return
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
    if dockerAvailable then localstack.stop()

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
      val cache = DynamoDbGameCache(client, "game-cache")

      cache.save(testGame, Instant.now())
      val loaded = cache.load(GameId(174430))

      loaded shouldBe defined
      loaded.get.name shouldBe "Gloomhaven"
      loaded.get.id shouldBe GameId(174430)
      loaded.get.yearPublished shouldBe Some(2017)
      loaded.get.mechanics shouldBe List("Hand Management", "Campaign")

    "return None for unknown game" in:
      val cache = DynamoDbGameCache(client, "game-cache")
      cache.load(GameId(999999)) shouldBe None

    "not overwrite existing game (conditional put)" in:
      val cache = DynamoDbGameCache(client, "game-cache")
      val modified = testGame.copy(name = "MODIFIED")

      cache.save(testGame, Instant.now())
      cache.save(modified, Instant.now())

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
      loaded.get.vector.values.head shouldBe 0.9 +- 1e-6

    "loadAll returns all stored vectors" in:
      val store = DynamoDbVectorStore(client, "vectors")
      val all = store.loadAll()
      all.size should be >= 2

    "loadAll projects the vector payload despite dropping updated_at" in:
      val store = DynamoDbVectorStore(client, "vectors")
      val v = GameVector(Vector.fill(155)(0.25))
      store.save(StoredVector(GameId(300), "Projected Game", v, Instant.parse("2024-06-01T00:00:00Z")))

      val loaded = store.loadAll().find(_.gameId == GameId(300))
      loaded shouldBe defined
      loaded.get.name shouldBe "Projected Game"
      loaded.get.vector.values should have size 155
      // updated_at is not projected by the projected scan, so it falls back to EPOCH.
      loaded.get.updatedAt shouldBe Instant.EPOCH

    "loadAllCached serves a warm snapshot until the TTL expires" in:
      var now = Instant.parse("2024-01-01T00:00:00Z")
      val store = DynamoDbVectorStore(client, "vectors", () => now, java.time.Duration.ofMinutes(5))

      val first = store.loadAllCached()
      val firstSize = first.size

      // Write a new vector; the cached snapshot must not reflect it before the TTL elapses.
      store.save(StoredVector(GameId(400), "Fresh Game", GameVector(Vector.fill(155)(0.1)), now))
      now = now.plusSeconds(60)
      store.loadAllCached().size shouldBe firstSize

      // Past the TTL the snapshot refreshes and picks up the new vector.
      now = now.plusSeconds(5 * 60)
      val refreshed = store.loadAllCached()
      refreshed.size should be > firstSize
      refreshed.map(_.gameId) should contain(GameId(400))

    "loadAllCached does not cache an empty result so a transient failure never sticks" in:
      // A swallowed Scan failure surfaces as an empty list, identical to a genuinely empty corpus.
      // Caching it would blank recommendations for the whole TTL, so an empty scan must not be cached.
      createTable("empty-vectors", "game_id", ScalarAttributeType.N)
      val now = Instant.parse("2024-01-01T00:00:00Z")
      val store = DynamoDbVectorStore(client, "empty-vectors", () => now, java.time.Duration.ofMinutes(5))

      store.loadAllCached() shouldBe empty

      // A vector written after the empty scan is visible immediately, proving nothing was cached.
      store.save(StoredVector(GameId(500), "Late Game", GameVector(Vector.fill(155)(0.2)), now))
      store.loadAllCached().map(_.gameId) should contain(GameId(500))

    "store the vector attribute as Binary" in:
      import scala.jdk.CollectionConverters.*
      val store = DynamoDbVectorStore(client, "vectors")
      store.save(StoredVector(GameId(600), "Binary Game", GameVector(Vector.fill(155)(0.3)), Instant.now()))

      val raw = client.getItem(
        GetItemRequest.builder().tableName("vectors")
          .key(Map("game_id" -> AttributeValue.fromN("600")).asJava).build()
      )
      raw.item().get("vector").b() should not be null
      raw.item().get("vector").s() shouldBe null

    "dual-read a legacy JSON String vector" in:
      import scala.jdk.CollectionConverters.*
      import io.circe.syntax.*
      val legacy = Vector.fill(155)(0.4)
      client.putItem(
        PutItemRequest.builder().tableName("vectors").item(Map(
          "game_id" -> AttributeValue.fromN("700"),
          "name" -> AttributeValue.fromS("Legacy Game"),
          "vector" -> AttributeValue.fromS(legacy.asJson.noSpaces)
        ).asJava).build()
      )
      val store = DynamoDbVectorStore(client, "vectors")
      val loaded = store.load(GameId(700))
      loaded shouldBe defined
      loaded.get.vector.values should have size 155
      loaded.get.vector.values.head shouldBe 0.4 +- 1e-6

    "loadAll decodes a large corpus completely" in:
      val store = DynamoDbVectorStore(client, "bulk-vectors")
      createTable("bulk-vectors", "game_id", ScalarAttributeType.N)
      val ids = (1000 until 1600).toList
      ids.foreach { i =>
        store.save(StoredVector(GameId(i), s"Bulk $i", GameVector(Vector.fill(155)(i.toDouble / 2000)), Instant.now()))
      }
      val loaded = store.loadAll()
      loaded.map(_.gameId.value).toSet should contain allElementsOf ids
      loaded.foreach(_.vector.values should have size 155)

    "rewriteAll converts legacy JSON rows to Binary" in:
      import scala.jdk.CollectionConverters.*
      import io.circe.syntax.*
      createTable("backfill-vectors", "game_id", ScalarAttributeType.N)
      val originalUpdatedAt = "2024-06-01T00:00:00Z"
      client.putItem(
        PutItemRequest.builder().tableName("backfill-vectors").item(Map(
          "game_id" -> AttributeValue.fromN("800"),
          "name" -> AttributeValue.fromS("Backfill Game"),
          "vector" -> AttributeValue.fromS(Vector.fill(155)(0.6).asJson.noSpaces),
          "updated_at" -> AttributeValue.fromS(originalUpdatedAt)
        ).asJava).build()
      )
      val store = DynamoDbVectorStore(client, "backfill-vectors")

      store.rewriteAll() shouldBe 1

      val raw = client.getItem(
        GetItemRequest.builder().tableName("backfill-vectors")
          .key(Map("game_id" -> AttributeValue.fromN("800")).asJava).build()
      )
      raw.item().get("vector").b() should not be null
      raw.item().get("vector").s() shouldBe null
      store.load(GameId(800)).get.vector.values.head shouldBe 0.6 +- 1e-6
      // updated_at must survive the rewrite, not be destroyed to EPOCH.
      store.load(GameId(800)).get.updatedAt shouldBe Instant.parse(originalUpdatedAt)

    "tolerate a row missing the name attribute without blanking the corpus" in:
      import scala.jdk.CollectionConverters.*
      import bgg.vector.VectorCodec
      createTable("malformed-name-vectors", "game_id", ScalarAttributeType.N)
      val store = DynamoDbVectorStore(client, "malformed-name-vectors")

      // Save two valid rows
      store.save(StoredVector(GameId(901), "Valid Game 1", GameVector(Vector.fill(155)(0.1)), Instant.now()))
      store.save(StoredVector(GameId(902), "Valid Game 2", GameVector(Vector.fill(155)(0.2)), Instant.now()))

      // Insert a row missing the name attribute directly
      client.putItem(
        PutItemRequest.builder().tableName("malformed-name-vectors").item(Map(
          "game_id" -> AttributeValue.fromN("903"),
          "vector" -> AttributeValue.fromB(
            software.amazon.awssdk.core.SdkBytes.fromByteArray(VectorCodec.encode(Vector.fill(155)(0.3)))
          )
        ).asJava).build()
      )

      val loaded = store.loadAll()
      // The two valid rows must be present; the corpus must NOT be blanked
      loaded.size shouldBe 2
      loaded.map(_.gameId) should contain allOf (GameId(901), GameId(902))
      loaded.map(_.gameId) should not contain GameId(903)

    "tolerate a row with malformed updated_at without blanking the corpus" in:
      import scala.jdk.CollectionConverters.*
      import bgg.vector.VectorCodec
      createTable("malformed-timestamp-vectors", "game_id", ScalarAttributeType.N)
      val store = DynamoDbVectorStore(client, "malformed-timestamp-vectors")

      // Save one valid row
      store.save(StoredVector(GameId(911), "Valid Game A", GameVector(Vector.fill(155)(0.5)), Instant.now()))

      // Insert a row with a malformed updated_at timestamp (will be read during rewriteAll, not loadAll)
      client.putItem(
        PutItemRequest.builder().tableName("malformed-timestamp-vectors").item(Map(
          "game_id" -> AttributeValue.fromN("913"),
          "name" -> AttributeValue.fromS("Malformed Timestamp Game"),
          "vector" -> AttributeValue.fromB(
            software.amazon.awssdk.core.SdkBytes.fromByteArray(VectorCodec.encode(Vector.fill(155)(0.7)))
          ),
          "updated_at" -> AttributeValue.fromS("not-a-valid-timestamp")
        ).asJava).build()
      )

      // rewriteAll uses unprojected reads, so it will encounter the malformed timestamp.
      // It must not blank the corpus - the valid row must still be rewritten.
      val rewritten = store.rewriteAll()
      rewritten shouldBe 1  // Only the valid row is rewritten; malformed row is dropped

      // Verify the valid row is present
      val loaded = store.loadAll()
      loaded.map(_.gameId) should contain(GameId(911))

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

    "treat sourceId case-insensitively" in:
      val store = DynamoDbPrefetchStatusStore(client, "prefetch-status")

      store.set(SourceType.Collection, "MixedCase", PrefetchStatus.Completed)
      // A differently-cased/whitespaced sourceId must resolve to the same normalized key.
      store.get(SourceType.Collection, "  mixedcase ").map(_.status) shouldBe Some(PrefetchStatus.Completed)
