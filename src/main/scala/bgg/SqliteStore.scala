package bgg

import com.typesafe.scalalogging.{Logger, StrictLogging}

import java.sql.{Connection, DriverManager}

trait SqliteStore(dbPath: String, schema: String) extends AutoCloseable with StrictLogging:
  protected val conn: Connection = DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
  protected given Logger = logger

  locally:
    val stmt = conn.createStatement()
    try stmt.execute(schema): Unit
    finally stmt.close()

  def close(): Unit = conn.close()
