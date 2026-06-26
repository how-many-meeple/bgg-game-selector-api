package bgg.cache

import bgg.store.VectorStore

trait CacheProvider:
  def gameCache: GameCache
  def vectorStore: VectorStore
  def requestCache: RequestCache
  def playsCache: PlaysCache
