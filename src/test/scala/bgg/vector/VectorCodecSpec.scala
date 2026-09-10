package bgg.vector

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class VectorCodecSpec extends AnyWordSpec with Matchers:

  "VectorCodec" should:
    "round-trip a vector within float32 tolerance" in:
      val original = Vector(0.0, 1.0, 0.5, 0.123456789, -0.25)
      val decoded = VectorCodec.decode(VectorCodec.encode(original))
      decoded.isRight shouldBe true
      val values = decoded.toOption.get
      values should have size original.size
      values.zip(original).foreach { case (got, exp) => got shouldBe exp +- 1e-6 }

    "produce 4 bytes per element" in:
      VectorCodec.encode(Vector.fill(155)(0.5)).length shouldBe 155 * 4

    "round-trip an empty vector" in:
      VectorCodec.decode(VectorCodec.encode(Vector.empty)) shouldBe Right(Vector.empty)

    "reject bytes whose length is not a multiple of 4" in:
      VectorCodec.decode(Array[Byte](1, 2, 3)) match
        case Left(msg) => msg should include ("not a multiple of 4")
        case Right(_) => fail("expected Left")
