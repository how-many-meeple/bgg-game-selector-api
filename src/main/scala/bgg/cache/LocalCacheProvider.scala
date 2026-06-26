package bgg.cache

import bgg.config.CacheConfig
import bgg.store.{SqliteVectorStore, VectorStore}

class SqliteCacheProvider(config: CacheConfig) extends CacheProvider:
  val gameCache: GameCache = SqliteGameCache(config.sqliteGameCachePath)
  val vectorStore: VectorStore = SqliteVectorStore(config.sqliteVectorStorePath)
  val requestCache: RequestCache = NoOpRequestCache()
  val playsCache: PlaysCache = NoOpPlaysCache()

class MemoryCacheProvider(config: CacheConfig) extends CacheProvider:
  val gameCache: GameCache = MemoryGameCache()
  val vectorStore: VectorStore = SqliteVectorStore(config.sqliteVectorStorePath)
  val requestCache: RequestCache = NoOpRequestCache()
  val playsCache: PlaysCache = NoOpPlaysCache()
