package bgg.tools

import bgg.store.DynamoDbVectorStore
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/** One-off maintenance: rewrite every stored vector so legacy JSON rows become packed float32 Binary.
  *
  * Idempotent and safe to re-run — re-saving an already-binary row is a no-op change, and dual-read
  * means the table serves both formats throughout. Runs on the JVM against a live table using the
  * ambient AWS credential chain (set AWS_PROFILE to select an account).
  *
  * Usage: `AWS_PROFILE=<profile> sbt "runMain bgg.tools.VectorBackfill [tableName]"`
  * Defaults to the production vector table in us-east-1; override the table via the first arg and
  * the region via AWS_REGION.
  */
object VectorBackfill:

  private val DefaultTable = "production-bgg-game-vectors"
  private val DefaultRegion = "us-east-1"

  def main(args: Array[String]): Unit =
    val region = sys.env.getOrElse("AWS_REGION", DefaultRegion)
    val table = args.headOption.getOrElse(DefaultTable)

    val client = DynamoDbClient
      .builder()
      .region(Region.of(region))
      .httpClient(UrlConnectionHttpClient.create())
      .build()

    try
      val store = DynamoDbVectorStore(client, table)
      println(s"Rewriting all vectors in $table ($region) to binary encoding...")
      val count = store.rewriteAll()
      println(s"Done: $count vectors rewritten.")
    finally client.close()
