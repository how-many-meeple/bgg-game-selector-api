package bgg.vector

import java.nio.ByteBuffer

object VectorCodec:

  private val Float32Bytes = 4

  def encode(values: Vector[Double]): Array[Byte] =
    val buf = ByteBuffer.allocate(values.size * Float32Bytes)
    values.foreach(d => buf.putFloat(d.toFloat))
    buf.array()

  def decode(bytes: Array[Byte]): Either[String, Vector[Double]] =
    if bytes.length % Float32Bytes != 0 then Left(s"vector byte length ${bytes.length} is not a multiple of $Float32Bytes")
    else
      val buf = ByteBuffer.wrap(bytes)
      Right(Vector.fill(bytes.length / Float32Bytes)(buf.getFloat.toDouble))
