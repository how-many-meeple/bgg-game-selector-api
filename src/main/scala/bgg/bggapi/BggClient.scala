package bgg.bggapi

import bgg.domain.{Fail, GameData, GameId, PlayData}

trait BggClient:
  def fetchGamesByIds(ids: List[GameId]): Either[Fail, List[GameData]]
  def fetchCollection(username: String, retries: Int = 6): Either[Fail, List[GameId]]
  def fetchGeeklist(listId: String): Either[Fail, List[GameId]]
  def fetchHotGames(): Either[Fail, List[GameId]]
  def searchGames(query: String): Either[Fail, List[GameData]]
  def fetchPlays(username: String, page: Int): Either[Fail, List[PlayData]]
