package postino

import munit.FunSuite

final class PostinoSuite extends FunSuite:
  final case class Sensor(id: U16, temp: Int, label: String) derives Codec

  final case class Envelope(
      sensor: Sensor,
      readings: Vector[Short],
      note: Option[String],
      bytes: Array[Byte]
  ) derives Codec

  sealed trait Message
  final case class Ping()                   extends Message derives Codec
  final case class Pong(id: U16)            extends Message derives Codec
  final case class Data(bytes: Array[Byte]) extends Message derives Codec

  given Codec[Message] =
    Postino
      .sum[Message]
      .variant(0, Codec[Ping])
      .variant(1, Codec[Pong])
      .variant(2, Codec[Data])
      .build

  test("primitive codecs match Rust postcard bytes"):
    assertEncoded(true, 0x01)
    assertEncoded((-1).toByte, 0xff)
    assertEncoded((-2).toShort, 0x03)
    assertEncoded(300, 0xd8, 0x04)
    assertEncoded(1.0f, 0x00, 0x00, 0x80, 0x3f)
    assertEncoded(1.5d, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xf8, 0x3f)
    assertEncoded(U16.unsafeFromInt(0xffff), 0xff, 0xff, 0x03)
    assertEncoded(U32.unsafeFromLong(0xffffffffL), 0xff, 0xff, 0xff, 0xff, 0x0f)
    assertEncoded(
      U64.unsafeFromBigInt(U64.MaxValue),
      0xff,
      0xff,
      0xff,
      0xff,
      0xff,
      0xff,
      0xff,
      0xff,
      0xff,
      0x01
    )

  test("strings and byte arrays use usize length prefixes"):
    assertEncoded("postino", 0x07, 0x70, 0x6f, 0x73, 0x74, 0x69, 0x6e, 0x6f)
    assertEncoded(
      Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte),
      0x04,
      0xde,
      0xad,
      0xbe,
      0xef
    )

    assertDecoded[String](
      bytes(0x07, 0x70, 0x6f, 0x73, 0x74, 0x69, 0x6e, 0x6f),
      "postino"
    )
    assertEquals(
      Postino.decode[Array[Byte]](bytes(0x04, 0xde, 0xad, 0xbe, 0xef)).map(unsigned),
      Right(Vector(0xde, 0xad, 0xbe, 0xef))
    )

  test("options and sequences match postcard tags and lengths"):
    assertEncoded(Option.empty[Int], 0x00)
    assertEncoded(Option(300), 0x01, 0xd8, 0x04)
    assertEncoded(List[Short](1, -1, 300), 0x03, 0x02, 0x01, 0xd8, 0x04)
    assertDecoded[List[Short]](
      bytes(0x03, 0x02, 0x01, 0xd8, 0x04),
      List[Short](1, -1, 300)
    )

  test("case class products encode constructor fields without names or length"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")
    assertEncoded(sensor, 0xb4, 0x24, 0x29, 0x03, 0x6c, 0x61, 0x62)
    assertDecoded[Sensor](
      bytes(0xb4, 0x24, 0x29, 0x03, 0x6c, 0x61, 0x62),
      sensor
    )

  test("nested products decode Rust-generated fixture bytes"):
    val encoded =
      bytes(
        0x07, 0x54, 0x04, 0x72, 0x61, 0x63, 0x6b, 0x03, 0x01, 0x00, 0x02, 0x01, 0x02, 0x6f, 0x6b,
        0x03, 0x01, 0x02, 0x03
      )

    val decoded = Postino.decode[Envelope](encoded)
    assertEquals(decoded.map(_.sensor), Right(Sensor(U16.unsafeFromInt(7), 42, "rack")))
    assertEquals(decoded.map(_.readings), Right(Vector[Short](-1, 0, 1)))
    assertEquals(decoded.map(_.note), Right(Some("ok")))
    assertEquals(decoded.map(envelope => unsigned(envelope.bytes)), Right(Vector(1, 2, 3)))

  test("explicit sums encode and decode u32 discriminants"):
    assertEncoded[Message](Ping(), 0x00)
    assertEncoded[Message](Pong(U16.unsafeFromInt(0xabcd)), 0x01, 0xcd, 0xd7, 0x02)
    assertEncoded[Message](Data(Array[Byte](9, 8, 7)), 0x02, 0x03, 0x09, 0x08, 0x07)

    assertEquals(Postino.decode[Message](bytes(0x00)), Right(Ping()))
    assertEquals(
      Postino.decode[Message](bytes(0x01, 0xcd, 0xd7, 0x02)),
      Right(Pong(U16.unsafeFromInt(0xabcd)))
    )
    assertEquals(
      Postino
        .decode[Message](bytes(0x02, 0x03, 0x09, 0x08, 0x07))
        .map:
          case Data(value) => unsigned(value)
          case other       => fail(s"expected Data, got $other")
      ,
      Right(Vector(9, 8, 7))
    )

  test("top-level decode rejects trailing bytes"):
    assertEquals(
      Postino.decode[Boolean](bytes(0x01, 0x00)),
      Left(PostinoError.TrailingBytes(1))
    )

  test("invalid primitive tags and varints report structured errors"):
    assertEquals(Postino.decode[Boolean](bytes(0x02)), Left(PostinoError.InvalidBoolean(2)))
    assertEquals(Postino.decode[Option[Int]](bytes(0x02)), Left(PostinoError.InvalidOptionTag(2)))
    assertEquals(Postino.decode[U16](bytes(0xff, 0xff, 0xff)), Left(PostinoError.VarintTooLong(3)))
    assertEquals(
      Postino.decode[U16](bytes(0xff, 0xff, 0x04)),
      Left(PostinoError.VarintOverflow("u16"))
    )
    assertEquals(Postino.decode[Message](bytes(0x7f)), Left(PostinoError.UnknownVariant(127)))

  private def assertEncoded[A](value: A, expected: Int*)(using encoder: Encoder[A]): Unit =
    assertEquals(Postino.encode(value).map(unsigned), Right(expected.toVector))

  private def assertDecoded[A](input: Array[Byte], expected: A)(using decoder: Decoder[A]): Unit =
    assertEquals(Postino.decode[A](input), Right(expected))

  private def bytes(values: Int*): Array[Byte] =
    values.map(_.toByte).toArray

  private def unsigned(values: Array[Byte]): Vector[Int] =
    values.toVector.map(_ & 0xff)
