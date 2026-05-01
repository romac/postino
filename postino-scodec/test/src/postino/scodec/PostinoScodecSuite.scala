package postino.scodec

import munit.FunSuite
import postino.*
import _root_.scodec.bits.BitVector

final class PostinoScodecSuite extends FunSuite:
  final case class Sensor(id: U16, temp: Int, label: String) derives Codec

  test("toScodec encodes with Postino wire bytes"):
    val codec = PostinoScodec.toScodec[Int]

    assertEquals(codec.encode(300).map(_.toHex).toEither, Right("d804"))

  test("toScodec decodes and returns byte-aligned remainder"):
    val codec = PostinoScodec.toScodec[Sensor]
    val bits  = BitVector.fromValidHex("b42429036c6162ffff")

    assertEquals(
      codec.decode(bits).map(result => result.value -> result.remainder.toHex).toEither,
      Right(Sensor(U16.unsafeFromInt(0x1234), -21, "lab") -> "ffff")
    )

  test("toScodec rejects non-byte-aligned input"):
    val codec = PostinoScodec.toScodec[Int]

    assert(codec.decode(BitVector.high(1)).isFailure)
