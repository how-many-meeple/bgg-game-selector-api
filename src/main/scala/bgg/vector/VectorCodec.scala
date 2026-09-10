package bgg.vector

import java.nio.ByteBuffer

object VectorCodec:

  def encode(values: Vector[Double]): Array[Byte] =
    val buf = ByteBuffer.allocate(values.size * 4) // big-endian by default
    values.foreach(d => buf.putFloat(d.toFloat))
    buf.array()

  def decode(bytes: Array[Byte]): Either[String, Vector[Double]] =
    if bytes.length % 4 != 0 then Left(s"vector byte length ${bytes.length} is not a multiple of 4")
    else
      val buf = ByteBuffer.wrap(bytes)
      Right(Vector.fill(bytes.length / 4)(buf.getFloat.toDouble))
