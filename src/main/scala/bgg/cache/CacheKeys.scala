package bgg.cache

/** Central place for building DynamoDB cache keys.
  *
  * BGG usernames (and other user-supplied identifiers) are case-insensitive, so every key derived from one is
  * normalized to trimmed lowercase. Constructing the keys here keeps the API read path and the Step Functions write
  * path from ever diverging on casing or surrounding whitespace.
  */
object CacheKeys:
  /** Trim + lowercase so a given identifier always maps to one key regardless of input casing. */
  def normalize(id: String): String = id.trim.toLowerCase

  def collectionIds(username: String): String = s"collection-ids:${normalize(username)}"
  def collectionDates(username: String): String = s"collection-dates:${normalize(username)}"
  def geeklistIds(listId: String): String = s"geeklist-ids:${normalize(listId)}"
