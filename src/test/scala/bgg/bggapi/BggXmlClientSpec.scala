package bgg.bggapi

import bgg.config.BggConfig
import bgg.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.{Header, StatusCode}

class BggXmlClientSpec extends AnyWordSpec with Matchers:

  private val defaultConfig = BggConfig(
    accessToken = "",
    timeoutSeconds = 10,
    retries = 3,
    retryDelaySeconds = 0
  )

  private val authenticatedConfig = defaultConfig.copy(accessToken = "test-token-123")

  private def stubBackend(
      statusCode: StatusCode = StatusCode.Ok,
      body: String = "<items></items>"
  ) =
    SyncBackendStub.whenAnyRequest
      .thenRespondAdjust(body, statusCode)

  private val collectionXml =
    """<items totalitems="3">
      |  <item objecttype="thing" objectid="174430" subtype="boardgame">
      |    <name sortindex="1">Gloomhaven</name>
      |  </item>
      |  <item objecttype="thing" objectid="167791" subtype="boardgame">
      |    <name sortindex="1">Terraforming Mars</name>
      |  </item>
      |  <item objecttype="thing" objectid="169786" subtype="boardgame">
      |    <name sortindex="1">Scythe</name>
      |  </item>
      |</items>""".stripMargin

  private val emptyCollectionXml = """<items totalitems="0"></items>"""

  private val geeklistXml =
    """<geeklist>
      |  <item objecttype="thing" objectid="174430" subtype="boardgame">
      |    <body>Great game</body>
      |  </item>
      |  <item objecttype="thing" objectid="167791" subtype="boardgame">
      |    <body>Also great</body>
      |  </item>
      |  <item objecttype="comment" objectid="99999">
      |    <body>This is a comment, not a game</body>
      |  </item>
      |</geeklist>""".stripMargin

  private val emptyGeeklistXml =
    """<geeklist>
      |  <item objecttype="comment" objectid="99999">
      |    <body>Only comments here</body>
      |  </item>
      |</geeklist>""".stripMargin

  private val thingXml =
    """<items>
      |  <item type="boardgame" id="174430">
      |    <name type="primary" value="Gloomhaven"/>
      |    <yearpublished value="2017"/>
      |    <minplayers value="1"/>
      |    <maxplayers value="4"/>
      |    <minplaytime value="60"/>
      |    <maxplaytime value="120"/>
      |    <playingtime value="120"/>
      |    <link type="boardgamemechanic" value="Hand Management"/>
      |    <link type="boardgamecategory" value="Adventure"/>
      |    <statistics>
      |      <ratings>
      |        <average value="8.7"/>
      |        <averageweight value="3.86"/>
      |        <usersrated value="50000"/>
      |      </ratings>
      |    </statistics>
      |  </item>
      |</items>""".stripMargin

  private val searchXml =
    """<items total="2">
      |  <item type="boardgame" id="174430">
      |    <name type="primary" value="Gloomhaven"/>
      |  </item>
      |  <item type="boardgame" id="291457">
      |    <name type="primary" value="Gloomhaven: Jaws of the Lion"/>
      |  </item>
      |</items>""".stripMargin

  "fetchCollection" should:
    "parse game IDs from collection XML" in:
      val backend = stubBackend(body = collectionXml)
      val client = BggXmlClient(defaultConfig, backend)

      val result = client.fetchCollection("testuser")

      result shouldBe Right(List(GameId(174430), GameId(167791), GameId(169786)))

    "use correct URL with query params" in:
      var capturedUrl = ""
      val backend = SyncBackendStub
        .whenRequestMatchesPartial { request =>
          capturedUrl = request.uri.toString
          ResponseStub.adjust(collectionXml, StatusCode.Ok)
        }
      val client = BggXmlClient(defaultConfig, backend)

      client.fetchCollection("myuser")

      capturedUrl should include("xmlapi2/collection")
      capturedUrl should include("username=myuser")
      capturedUrl should include("own=1")
      capturedUrl should include("excludesubtype=boardgameexpansion")

    "return BggUserNotFound when items are empty" in:
      val backend = stubBackend(body = emptyCollectionXml)
      val client = BggXmlClient(defaultConfig, backend)

      val result = client.fetchCollection("unknownuser")

      result shouldBe Left(Fail.BggUserNotFound("unknownuser"))

  "fetchGeeklist" should:
    "parse game IDs filtering by objecttype=thing" in:
      val backend = stubBackend(body = geeklistXml)
      val client = BggXmlClient(defaultConfig, backend)

      val result = client.fetchGeeklist("12345")

      result shouldBe Right(List(GameId(174430), GameId(167791)))

    "use URL path based on list ID" in:
      var capturedUrl = ""
      val backend = SyncBackendStub
        .whenRequestMatchesPartial { request =>
          capturedUrl = request.uri.toString
          ResponseStub.adjust(geeklistXml, StatusCode.Ok)
        }
      val client = BggXmlClient(defaultConfig, backend)

      client.fetchGeeklist("54321")

      capturedUrl should include("xmlapi/geeklist/54321")

    "return BggListNotFound when no thing items exist" in:
      val backend = stubBackend(body = emptyGeeklistXml)
      val client = BggXmlClient(defaultConfig, backend)

      val result = client.fetchGeeklist("99999")

      result shouldBe Left(Fail.BggListNotFound("99999"))

  "fetchGamesByIds" should:
    "parse game data from thing XML" in:
      val backend = stubBackend(body = thingXml)
      val client = BggXmlClient(defaultConfig, backend)

      val result = client.fetchGamesByIds(List(GameId(174430)))

      result.isRight shouldBe true
      val games = result.toOption.get
      games should have size 1
      games.head.id shouldBe GameId(174430)
      games.head.name shouldBe "Gloomhaven"
      games.head.yearPublished shouldBe Some(2017)
      games.head.minPlayers shouldBe Some(1)
      games.head.maxPlayers shouldBe Some(4)
      games.head.minPlayingTime shouldBe Some(60)
      games.head.maxPlayingTime shouldBe Some(120)
      games.head.ratingAverage shouldBe Some(8.7)
      games.head.ratingAverageWeight shouldBe Some(3.86)
      games.head.mechanics shouldBe List("Hand Management")
      games.head.categories shouldBe List("Adventure")

    "batch IDs into groups of 20" in:
      var requestCount = 0
      val backend = SyncBackendStub
        .whenRequestMatchesPartial { _ =>
          requestCount += 1
          ResponseStub.adjust(thingXml, StatusCode.Ok)
        }
      val client = BggXmlClient(defaultConfig, backend)

      val ids = (1 to 45).map(GameId(_)).toList
      client.fetchGamesByIds(ids)

      requestCount shouldBe 3

    "join IDs with commas in the request" in:
      var capturedUrl = ""
      val backend = SyncBackendStub
        .whenRequestMatchesPartial { request =>
          capturedUrl = request.uri.toString
          ResponseStub.adjust(thingXml, StatusCode.Ok)
        }
      val client = BggXmlClient(defaultConfig, backend)

      client.fetchGamesByIds(List(GameId(100), GameId(200), GameId(300)))

      capturedUrl should include("xmlapi2/thing")
      capturedUrl should include("id=100,200,300")
      capturedUrl should include("stats=1")

    "return empty list on failure for a batch" in:
      val backend = stubBackend(statusCode = StatusCode.TooManyRequests)
      val client = BggXmlClient(defaultConfig.copy(retries = 1), backend)

      val result = client.fetchGamesByIds(List(GameId(1)))

      result shouldBe Right(Nil)

  "searchGames" should:
    "return empty list for queries shorter than 3 characters" in:
      val backend = stubBackend()
      val client = BggXmlClient(defaultConfig, backend)

      client.searchGames("ab") shouldBe Right(Nil)
      client.searchGames("a") shouldBe Right(Nil)
      client.searchGames("") shouldBe Right(Nil)

    "search with correct URL params" in:
      var capturedUrl = ""
      val backend = SyncBackendStub
        .whenRequestMatchesPartial { request =>
          capturedUrl = request.uri.toString
          ResponseStub.adjust("<items></items>", StatusCode.Ok)
        }
      val client = BggXmlClient(defaultConfig, backend)

      client.searchGames("gloomhaven")

      capturedUrl should include("xmlapi2/search")
      capturedUrl should include("query=gloomhaven")
      capturedUrl should include("type=boardgame")

    "limit results to 20 games" in:
      val manyResultsXml =
        "<items>" +
          (1 to 25)
            .map(i => s"""<item type="boardgame" id="$i"><name type="primary" value="Game $i"/></item>""")
            .mkString +
          "</items>"
      var fetchCount = 0
      val backend = SyncBackendStub
        .whenRequestMatchesPartial { request =>
          val url = request.uri.toString
          if url.contains("search") then ResponseStub.adjust(manyResultsXml, StatusCode.Ok)
          else
            fetchCount += 1
            ResponseStub.adjust(thingXml, StatusCode.Ok)
        }
      val client = BggXmlClient(defaultConfig, backend)

      client.searchGames("game")

      fetchCount shouldBe 20

  "retry logic" should:
    "retry on 202 responses" in:
      var requestCount = 0
      val backend = SyncBackendStub
        .whenRequestMatchesPartial { _ =>
          requestCount += 1
          if requestCount < 3 then ResponseStub.adjust("", StatusCode.Accepted)
          else ResponseStub.adjust(collectionXml, StatusCode.Ok)
        }
      val client = BggXmlClient(defaultConfig, backend)

      val result = client.fetchCollection("testuser")

      result.isRight shouldBe true
      requestCount shouldBe 3

    "return BggRateLimited after exhausting retries on 202" in:
      val backend = stubBackend(statusCode = StatusCode.Accepted, body = "")
      val client = BggXmlClient(defaultConfig.copy(retries = 2), backend)

      val result = client.fetchCollection("testuser")

      result shouldBe a[Left[?, ?]]
      result.left.toOption.get shouldBe a[Fail.BggRateLimited]

    "return BggRateLimited on 429 response" in:
      val backend = stubBackend(statusCode = StatusCode.TooManyRequests, body = "")
      val client = BggXmlClient(defaultConfig, backend)

      val result = client.fetchCollection("testuser")

      result shouldBe a[Left[?, ?]]
      result.left.toOption.get shouldBe a[Fail.BggRateLimited]

    "return IncorrectInput on unexpected status codes" in:
      val backend = stubBackend(statusCode = StatusCode.InternalServerError, body = "")
      val client = BggXmlClient(defaultConfig, backend)

      val result = client.fetchCollection("testuser")

      result shouldBe a[Left[?, ?]]
      result.left.toOption.get shouldBe a[Fail.IncorrectInput]

  "auth headers" should:
    "send Bearer token when accessToken is non-empty" in:
      var capturedHeaders: Map[String, String] = Map.empty
      val backend = SyncBackendStub
        .whenRequestMatchesPartial { request =>
          capturedHeaders = request.headers.map(h => h.name -> h.value).toMap
          ResponseStub.adjust(collectionXml, StatusCode.Ok)
        }
      val client = BggXmlClient(authenticatedConfig, backend)

      client.fetchCollection("testuser")

      capturedHeaders should contain("Authorization" -> "Bearer test-token-123")

    "not send Authorization header when accessToken is empty" in:
      var capturedHeaders: Map[String, String] = Map.empty
      val backend = SyncBackendStub
        .whenRequestMatchesPartial { request =>
          capturedHeaders = request.headers.map(h => h.name -> h.value).toMap
          ResponseStub.adjust(collectionXml, StatusCode.Ok)
        }
      val client = BggXmlClient(defaultConfig, backend)

      client.fetchCollection("testuser")

      capturedHeaders should not contain key("Authorization")
