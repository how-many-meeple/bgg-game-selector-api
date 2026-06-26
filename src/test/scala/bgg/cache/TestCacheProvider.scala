package bgg.cache

import bgg.store.VectorStore

class TestCacheProvider(
    val gameCache: GameCache,
    val vectorStore: VectorStore,
    val requestCache: RequestCache = NoOpRequestCache(),
    val playsCache: PlaysCache = NoOpPlaysCache()
) extends CacheProvider
