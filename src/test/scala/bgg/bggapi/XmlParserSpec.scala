package bgg.bggapi

import bgg.domain.{GameId, PlayerSuggestion}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class XmlParserSpec extends AnyWordSpec with Matchers:

  "parsePlays" should:
    "parse a complete play with players" in:
      val xml =
        <plays total="1" page="1">
          <play id="12345" date="2024-03-15" quantity="1" length="90">
            <item name="Catan" objecttype="thing" objectid="13">
            </item>
            <players>
              <player username="alice" name="Alice" score="10" win="1"/>
              <player username="bob" name="Bob" score="7" win="0"/>
            </players>
          </play>
        </plays>

      val result = XmlParser.parsePlays(xml)
      result should have size 1

      val play = result.head
      play.playId shouldBe 12345
      play.gameId shouldBe GameId(13)
      play.gameName shouldBe "Catan"
      play.date shouldBe "2024-03-15"
      play.quantity shouldBe 1
      play.length shouldBe 90
      play.players should have size 2
      play.players.head.username shouldBe "alice"
      play.players.head.name shouldBe "Alice"
      play.players.head.score shouldBe Some("10")
      play.players.head.win shouldBe true
      play.players(1).win shouldBe false

    "parse multiple plays" in:
      val xml =
        <plays total="2" page="1">
          <play id="100" date="2024-01-01" quantity="2" length="45">
            <item name="Pandemic" objecttype="thing" objectid="30549"/>
          </play>
          <play id="101" date="2024-01-02" quantity="1" length="60">
            <item name="Catan" objecttype="thing" objectid="13"/>
          </play>
        </plays>

      val result = XmlParser.parsePlays(xml)
      result should have size 2
      result.map(_.playId) shouldBe List(100, 101)

    "skip plays without a play id" in:
      val xml =
        <plays total="1" page="1">
          <play date="2024-01-01" quantity="1" length="30">
            <item name="Chess" objecttype="thing" objectid="171"/>
          </play>
        </plays>

      XmlParser.parsePlays(xml) shouldBe empty

    "skip plays without a game objectid" in:
      val xml =
        <plays total="1" page="1">
          <play id="200" date="2024-01-01" quantity="1" length="30">
            <item name="Unknown" objecttype="thing"/>
          </play>
        </plays>

      XmlParser.parsePlays(xml) shouldBe empty

    "default quantity to 1 and length to 0 when missing" in:
      val xml =
        <plays total="1" page="1">
          <play id="300" date="2024-06-01">
            <item name="Go" objecttype="thing" objectid="188"/>
          </play>
        </plays>

      val result = XmlParser.parsePlays(xml)
      result should have size 1
      result.head.quantity shouldBe 1
      result.head.length shouldBe 0

    "handle plays with no players element" in:
      val xml =
        <plays total="1" page="1">
          <play id="400" date="2024-02-01" quantity="1" length="30">
            <item name="Solo" objecttype="thing" objectid="999"/>
          </play>
        </plays>

      val result = XmlParser.parsePlays(xml)
      result.head.players shouldBe empty

    "handle player with empty score" in:
      val xml =
        <plays total="1" page="1">
          <play id="500" date="2024-03-01" quantity="1" length="45">
            <item name="Catan" objecttype="thing" objectid="13"/>
            <players>
              <player username="carl" name="Carl" score="" win="0"/>
            </players>
          </play>
        </plays>

      val result = XmlParser.parsePlays(xml)
      result.head.players.head.score shouldBe None

    "return empty list when no plays present" in:
      val xml = <plays total="0" page="1"></plays>
      XmlParser.parsePlays(xml) shouldBe empty

  "parseThings" should:
    "parse a single complete item" in:
      val xml =
        <items>
          <item type="boardgame" id="174430">
            <name type="primary" value="Gloomhaven"/>
            <name type="alternate" value="Some Alternate Name"/>
            <yearpublished value="2017"/>
            <minplayers value="1"/>
            <maxplayers value="4"/>
            <minplaytime value="60"/>
            <maxplaytime value="120"/>
            <playingtime value="120"/>
            <link type="boardgamemechanic" value="Hand Management"/>
            <link type="boardgamemechanic" value="Campaign"/>
            <link type="boardgamecategory" value="Fantasy"/>
            <link type="boardgamecategory" value="Adventure"/>
            <poll name="suggested_numplayers">
              <results numplayers="2">
                <result value="Best" numvotes="50"/>
                <result value="Recommended" numvotes="30"/>
                <result value="Not Recommended" numvotes="5"/>
              </results>
              <results numplayers="3">
                <result value="Best" numvotes="80"/>
                <result value="Recommended" numvotes="20"/>
                <result value="Not Recommended" numvotes="2"/>
              </results>
            </poll>
            <statistics>
              <ratings>
                <average value="8.7"/>
                <averageweight value="3.86"/>
                <usersrated value="50000"/>
              </ratings>
            </statistics>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result should have size 1

      val game = result.head
      game.id shouldBe GameId(174430)
      game.name shouldBe "Gloomhaven"
      game.yearPublished shouldBe Some(2017)
      game.minPlayers shouldBe Some(1)
      game.maxPlayers shouldBe Some(4)
      game.minPlayingTime shouldBe Some(60)
      game.maxPlayingTime shouldBe Some(120)
      game.playingTime shouldBe Some(120)
      game.ratingAverage shouldBe Some(8.7)
      game.ratingAverageWeight shouldBe Some(3.86)
      game.usersRated shouldBe Some(50000)
      game.expansion shouldBe false
      game.mechanics shouldBe List("Hand Management", "Campaign")
      game.categories shouldBe List("Fantasy", "Adventure")
      game.playerSuggestions shouldBe List(
        PlayerSuggestion(numericPlayerCount = 2, best = 50, recommended = 30, notRecommended = 5),
        PlayerSuggestion(numericPlayerCount = 3, best = 80, recommended = 20, notRecommended = 2)
      )

    "parse multiple items" in:
      val xml =
        <items>
          <item type="boardgame" id="1">
            <name type="primary" value="Game One"/>
            <yearpublished value="2000"/>
            <minplayers value="2"/>
            <maxplayers value="4"/>
            <minplaytime value="30"/>
            <maxplaytime value="60"/>
            <playingtime value="45"/>
          </item>
          <item type="boardgame" id="2">
            <name type="primary" value="Game Two"/>
            <yearpublished value="2010"/>
            <minplayers value="3"/>
            <maxplayers value="6"/>
            <minplaytime value="60"/>
            <maxplaytime value="90"/>
            <playingtime value="75"/>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result should have size 2
      result.map(_.name) shouldBe List("Game One", "Game Two")
      result.map(_.id) shouldBe List(GameId(1), GameId(2))

    "return empty list when no items present" in:
      val xml = <items></items>
      XmlParser.parseThings(xml) shouldBe empty

    "identify expansions by type attribute" in:
      val xml =
        <items>
          <item type="boardgameexpansion" id="99">
            <name type="primary" value="Some Expansion"/>
            <yearpublished value="2021"/>
            <minplayers value="2"/>
            <maxplayers value="4"/>
            <minplaytime value="30"/>
            <maxplaytime value="60"/>
            <playingtime value="45"/>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result should have size 1
      result.head.expansion shouldBe true

    "use primary name and ignore alternates" in:
      val xml =
        <items>
          <item type="boardgame" id="5">
            <name type="alternate" value="Alt Name"/>
            <name type="primary" value="Primary Name"/>
            <name type="alternate" value="Another Alt"/>
            <yearpublished value="2015"/>
            <minplayers value="2"/>
            <maxplayers value="4"/>
            <minplaytime value="30"/>
            <maxplaytime value="60"/>
            <playingtime value="45"/>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result.head.name shouldBe "Primary Name"

    "default name to empty string when no primary name exists" in:
      val xml =
        <items>
          <item type="boardgame" id="7">
            <name type="alternate" value="Only Alternate"/>
            <yearpublished value="2015"/>
            <minplayers value="2"/>
            <maxplayers value="4"/>
            <minplaytime value="30"/>
            <maxplaytime value="60"/>
            <playingtime value="45"/>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result.head.name shouldBe ""

    "handle missing optional numeric attributes" in:
      val xml =
        <items>
          <item type="boardgame" id="10">
            <name type="primary" value="Minimal Game"/>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result should have size 1

      val game = result.head
      game.yearPublished shouldBe None
      game.minPlayers shouldBe None
      game.maxPlayers shouldBe None
      game.minPlayingTime shouldBe None
      game.maxPlayingTime shouldBe None
      game.playingTime shouldBe None
      game.ratingAverage shouldBe None
      game.ratingAverageWeight shouldBe None
      game.usersRated shouldBe None
      game.mechanics shouldBe empty
      game.categories shouldBe empty
      game.playerSuggestions shouldBe empty

    "handle non-numeric values in numeric attributes gracefully" in:
      val xml =
        <items>
          <item type="boardgame" id="11">
            <name type="primary" value="Bad Data Game"/>
            <yearpublished value="unknown"/>
            <minplayers value="abc"/>
            <maxplayers value=""/>
            <minplaytime value="30"/>
            <maxplaytime value="60"/>
            <playingtime value="45"/>
            <statistics>
              <ratings>
                <average value="N/A"/>
                <averageweight value=""/>
                <usersrated value="xyz"/>
              </ratings>
            </statistics>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result should have size 1

      val game = result.head
      game.yearPublished shouldBe None
      game.minPlayers shouldBe None
      game.maxPlayers shouldBe None
      game.minPlayingTime shouldBe Some(30)
      game.maxPlayingTime shouldBe Some(60)
      game.ratingAverage shouldBe None
      game.ratingAverageWeight shouldBe None
      game.usersRated shouldBe None

    "skip items without an id attribute" in:
      val xml =
        <items>
          <item type="boardgame">
            <name type="primary" value="No Id Game"/>
            <yearpublished value="2020"/>
            <minplayers value="2"/>
            <maxplayers value="4"/>
            <minplaytime value="30"/>
            <maxplaytime value="60"/>
            <playingtime value="45"/>
          </item>
          <item type="boardgame" id="42">
            <name type="primary" value="Has Id"/>
            <yearpublished value="2020"/>
            <minplayers value="2"/>
            <maxplayers value="4"/>
            <minplaytime value="30"/>
            <maxplaytime value="60"/>
            <playingtime value="45"/>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result should have size 1
      result.head.name shouldBe "Has Id"

    "filter link types correctly for mechanics and categories" in:
      val xml =
        <items>
          <item type="boardgame" id="20">
            <name type="primary" value="Link Test"/>
            <link type="boardgamemechanic" value="Worker Placement"/>
            <link type="boardgamecategory" value="Strategy"/>
            <link type="boardgamedesigner" value="Some Designer"/>
            <link type="boardgamefamily" value="Some Family"/>
            <link type="boardgamemechanic" value="Dice Rolling"/>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result.head.mechanics shouldBe List("Worker Placement", "Dice Rolling")
      result.head.categories shouldBe List("Strategy")

    "skip non-numeric player count ranges in suggestions" in:
      val xml =
        <items>
          <item type="boardgame" id="30">
            <name type="primary" value="Poll Test"/>
            <poll name="suggested_numplayers">
              <results numplayers="2">
                <result value="Best" numvotes="10"/>
                <result value="Recommended" numvotes="5"/>
                <result value="Not Recommended" numvotes="1"/>
              </results>
              <results numplayers="4+">
                <result value="Best" numvotes="3"/>
                <result value="Recommended" numvotes="7"/>
                <result value="Not Recommended" numvotes="20"/>
              </results>
            </poll>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result.head.playerSuggestions should have size 1
      result.head.playerSuggestions.head.numericPlayerCount shouldBe 2

    "handle missing vote values in suggestions" in:
      val xml =
        <items>
          <item type="boardgame" id="31">
            <name type="primary" value="Missing Votes"/>
            <poll name="suggested_numplayers">
              <results numplayers="3">
                <result value="Best" numvotes="10"/>
              </results>
            </poll>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      val suggestion = result.head.playerSuggestions.head
      suggestion.numericPlayerCount shouldBe 3
      suggestion.best shouldBe 10
      suggestion.recommended shouldBe 0
      suggestion.notRecommended shouldBe 0

    "handle non-numeric numvotes gracefully" in:
      val xml =
        <items>
          <item type="boardgame" id="32">
            <name type="primary" value="Bad Votes"/>
            <poll name="suggested_numplayers">
              <results numplayers="2">
                <result value="Best" numvotes="abc"/>
                <result value="Recommended" numvotes="5"/>
                <result value="Not Recommended" numvotes=""/>
              </results>
            </poll>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      val suggestion = result.head.playerSuggestions.head
      suggestion.best shouldBe 0
      suggestion.recommended shouldBe 5
      suggestion.notRecommended shouldBe 0

    "ignore polls with names other than suggested_numplayers" in:
      val xml =
        <items>
          <item type="boardgame" id="33">
            <name type="primary" value="Other Poll"/>
            <poll name="language_dependence">
              <results numplayers="1">
                <result value="Best" numvotes="99"/>
              </results>
            </poll>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result.head.playerSuggestions shouldBe empty

    "handle item with no poll element" in:
      val xml =
        <items>
          <item type="boardgame" id="34">
            <name type="primary" value="No Poll"/>
            <yearpublished value="2019"/>
            <minplayers value="2"/>
            <maxplayers value="4"/>
            <minplaytime value="30"/>
            <maxplaytime value="60"/>
            <playingtime value="45"/>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result.head.playerSuggestions shouldBe empty

    "handle statistics without ratings node" in:
      val xml =
        <items>
          <item type="boardgame" id="35">
            <name type="primary" value="Empty Stats"/>
            <statistics></statistics>
          </item>
        </items>

      val result = XmlParser.parseThings(xml)
      result.head.ratingAverage shouldBe None
      result.head.ratingAverageWeight shouldBe None
      result.head.usersRated shouldBe None
