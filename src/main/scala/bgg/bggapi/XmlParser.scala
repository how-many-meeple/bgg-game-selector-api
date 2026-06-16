package bgg.bggapi

import bgg.domain.{GameData, GameId, PlayerSuggestion}

import scala.xml.{Node, NodeSeq}

// Parses BGG XML API v2 /thing responses into GameData
private[bggapi] object XmlParser:

  def parseThings(xml: scala.xml.Elem): List[GameData] =
    (xml \ "item").toList.flatMap(parseItem)

  private def parseItem(item: Node): Option[GameData] =
    val idOpt = (item \ "@id").headOption.map(_.text.toInt)
    idOpt.map { id =>
      val name = (item \ "name")
        .find(n => (n \ "@type").text == "primary")
        .map(n => (n \ "@value").text)
        .getOrElse("")
      val yearPublished = intAttr(item \ "yearpublished", "@value")
      val minPlayers = intAttr(item \ "minplayers", "@value")
      val maxPlayers = intAttr(item \ "maxplayers", "@value")
      val minPlayTime = intAttr(item \ "minplaytime", "@value")
      val maxPlayTime = intAttr(item \ "maxplaytime", "@value")
      val playTime = intAttr(item \ "playingtime", "@value")
      val expansion = (item \ "@type").text == "boardgameexpansion"
      val mechanics = attrValues(item \ "link", "boardgamemechanic")
      val categories = attrValues(item \ "link", "boardgamecategory")
      val suggestions = parsePollSuggestions(item)

      val stats = (item \ "statistics" \ "ratings").headOption
      val ratingAvg = stats.flatMap(s => doubleAttr(s \ "average", "@value"))
      val ratingWeight = stats.flatMap(s => doubleAttr(s \ "averageweight", "@value"))
      val usersRated = stats.flatMap(s => intAttr(s \ "usersrated", "@value"))

      GameData(
        id = GameId(id),
        name = name,
        yearPublished = yearPublished,
        minPlayers = minPlayers,
        maxPlayers = maxPlayers,
        minPlayingTime = minPlayTime,
        maxPlayingTime = maxPlayTime,
        playingTime = playTime,
        ratingAverage = ratingAvg,
        ratingAverageWeight = ratingWeight,
        expansion = expansion,
        mechanics = mechanics,
        categories = categories,
        playerSuggestions = suggestions,
        usersRated = usersRated
      )
    }

  private def parsePollSuggestions(item: Node): List[PlayerSuggestion] =
    val poll = (item \ "poll").find(n => (n \ "@name").text == "suggested_numplayers")
    poll.toList.flatMap { p =>
      (p \ "results").toList.flatMap { results =>
        val playerCount = (results \ "@numplayers").text
        // Skip ranges like "4+" which can't be parsed as Int
        val countOpt = playerCount.toIntOption
        countOpt.map { count =>
          val votes = (results \ "result")
          def votesFor(v: String) = votes
            .find(n => (n \ "@value").text == v)
            .map(n => (n \ "@numvotes").text.toIntOption.getOrElse(0))
            .getOrElse(0)
          PlayerSuggestion(
            numericPlayerCount = count,
            best = votesFor("Best"),
            recommended = votesFor("Recommended"),
            notRecommended = votesFor("Not Recommended")
          )
        }
      }
    }

  private def intAttr(nodes: NodeSeq, attr: String): Option[Int] =
    nodes.headOption.flatMap(n => (n \ attr).text.toIntOption)

  private def doubleAttr(nodes: NodeSeq, attr: String): Option[Double] =
    nodes.headOption.flatMap(n => (n \ attr).text.toDoubleOption)

  private def attrValues(links: NodeSeq, linkType: String): List[String] =
    links.filter(n => (n \ "@type").text == linkType).map(n => (n \ "@value").text).toList
