package postino

import munit.FunSuite

import scala.io.Source

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

  test("primitive codecs match Rust postcard fixture bytes"):
    assertFixtureRoundTrip("bool_true", true)
    assertFixtureRoundTrip("byte_minus_one", (-1).toByte)
    assertFixtureRoundTrip("i16_minus_two", (-2).toShort)
    assertFixtureRoundTrip("i32_300", 300)
    assertFixtureRoundTrip("float_1", 1.0f)
    assertFixtureRoundTrip("double_1_5", 1.5d)
    assertFixtureRoundTrip("u16_65535", U16.unsafeFromInt(0xffff))
    assertFixtureRoundTrip("u32_4294967295", U32.unsafeFromLong(0xffffffffL))
    assertFixtureRoundTrip("u64_18446744073709551615", U64.unsafeFromBigInt(U64.MaxValue))

  test("U64 constructors distinguish signed values from unsigned bit patterns"):
    assertEquals(U64.fromLong(-1L), Left(PostinoError.InvalidUnsignedValue("u64", BigInt(-1))))
    assertEquals(U64.fromUnsignedLong(-1L).toUnsignedLong, -1L)
    assertEquals(U64.fromUnsignedLong(-1L).toBigInt, U64.MaxValue)
    assertEquals(U64.fromUnsignedLong(Long.MinValue).toBigInt, BigInt(1) << 63)

  test("strings and byte arrays match Rust postcard fixture bytes"):
    assertFixtureRoundTrip("string", "postino")
    assertFixtureEncoded("bytes", Array[Byte](0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte))

    assertEquals(
      Postino.decode[Array[Byte]](fixtureBytes("bytes")).map(unsigned),
      Right(Vector(0xde, 0xad, 0xbe, 0xef))
    )

  test("options and sequences match Rust postcard fixture bytes"):
    assertFixtureRoundTrip("option_i32_none", Option.empty[Int])
    assertFixtureRoundTrip("option_i32_some_300", Option(300))
    assertFixtureRoundTrip("list", List[Short](1, -1, 300))

  test("sequence decode rejects lengths over the configured maximum"):
    val decodeOptions = DecodeOptions(maxCollectionLength = 2, maxCollectionElements = 10)

    assertEquals(
      Postino.decode[List[Unit]](bytes(0x03), decodeOptions),
      Left(PostinoError.CollectionLengthTooLarge(3, 2))
    )

  test("sequence decode applies a total collection element budget"):
    val decodeOptions = DecodeOptions(maxCollectionLength = 3, maxCollectionElements = 5)

    assertEquals(
      Postino.decode[List[List[Unit]]](bytes(0x02, 0x03, 0x03), decodeOptions),
      Left(PostinoError.CollectionElementLimitExceeded(3, 0, 5))
    )

  test("reader rejects negative byte lengths distinctly"):
    assertEquals(
      Reader.from(Array.emptyByteArray).readBytes(-1),
      Left(PostinoError.NegativeLength(-1))
    )

  test("case class products encode constructor fields without names or length"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")
    assertFixtureRoundTrip("sensor", sensor)

  test("nested products decode Rust-generated fixture bytes"):
    val envelope =
      Envelope(
        Sensor(U16.unsafeFromInt(7), 42, "rack"),
        Vector[Short](-1, 0, 1),
        Some("ok"),
        Array[Byte](1, 2, 3)
      )

    assertFixtureEncoded("envelope", envelope)

    val decoded = Postino.decode[Envelope](fixtureBytes("envelope"))
    assertEquals(decoded.map(_.sensor), Right(Sensor(U16.unsafeFromInt(7), 42, "rack")))
    assertEquals(decoded.map(_.readings), Right(Vector[Short](-1, 0, 1)))
    assertEquals(decoded.map(_.note), Right(Some("ok")))
    assertEquals(decoded.map(envelope => unsigned(envelope.bytes)), Right(Vector(1, 2, 3)))

  test("derived product decode returns an error when the constructor rejects decoded fields"):
    final case class NonNegative(value: Int) derives Codec:
      require(value >= 0, "value must be non-negative")

    assertEquals(
      Postino.decode[NonNegative](bytes(0x01)),
      Left(
        PostinoError.ProductConstructionFailed(
          "NonNegative",
          "requirement failed: value must be non-negative"
        )
      )
    )

  test("derived product decode reports the failing field name"):
    final case class Flags(left: Boolean, right: Boolean) derives Codec

    assertEquals(
      Postino.decode[Flags](bytes(0x01, 0x02)),
      Left(PostinoError.ProductFieldFailed("Flags", "right", PostinoError.InvalidBoolean(2)))
    )

  test("explicit sums encode and decode u32 discriminants"):
    assertFixtureRoundTrip[Message]("enum_ping", Ping())
    assertFixtureRoundTrip[Message]("enum_pong", Pong(U16.unsafeFromInt(0xabcd)))
    assertFixtureEncoded[Message]("enum_data", Data(Array[Byte](9, 8, 7)))

    assertEquals(
      Postino
        .decode[Message](fixtureBytes("enum_data"))
        .map:
          case Data(value) => unsigned(value)
          case other       => fail(s"expected Data, got $other")
      ,
      Right(Vector(9, 8, 7))
    )

  test("explicit sums reject ambiguous runtime variant matches"):
    sealed trait Overlap
    trait LeftOverlap                     extends Overlap
    trait RightOverlap                    extends Overlap
    final case class Specific(value: Int) extends LeftOverlap with RightOverlap derives Codec

    val leftCodec = new Codec[LeftOverlap]:
      def encode(value: LeftOverlap, out: Writer): Either[PostinoError, Unit] =
        Right(())

      def decode(in: Reader): Either[PostinoError, LeftOverlap] =
        Left(PostinoError.UnexpectedEnd)

    val rightCodec = new Codec[RightOverlap]:
      def encode(value: RightOverlap, out: Writer): Either[PostinoError, Unit] =
        Right(())

      def decode(in: Reader): Either[PostinoError, RightOverlap] =
        Left(PostinoError.UnexpectedEnd)

    val codec =
      Postino
        .sum[Overlap]
        .variant(0, leftCodec)
        .variant(1, rightCodec)
        .build

    assertEquals(
      Postino.encode[Overlap](Specific(42))(using codec),
      Left(PostinoError.AmbiguousVariant(classOf[Specific].getName, Vector(0L, 1L)))
    )

  test("top-level decode rejects trailing bytes"):
    assertEquals(
      Postino.decode[Boolean](bytes(0x01, 0x00)),
      Left(PostinoError.TrailingBytes(1, 1))
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

  private lazy val rustFixtures: Map[String, Vector[Int]] =
    loadRustFixtures()

  private def assertFixtureRoundTrip[A](name: String, value: A)(using codec: Codec[A]): Unit =
    assertFixtureEncoded(name, value)
    assertEquals(Postino.decode[A](fixtureBytes(name)), Right(value))

  private def assertFixtureEncoded[A](name: String, value: A)(using encoder: Encoder[A]): Unit =
    assertEquals(Postino.encode(value).map(unsigned), Right(fixture(name)))

  private def fixture(name: String): Vector[Int] =
    rustFixtures.getOrElse(name, fail(s"missing Rust postcard fixture '$name'"))

  private def fixtureBytes(name: String): Array[Byte] =
    fixture(name).map(_.toByte).toArray

  private def loadRustFixtures(): Map[String, Vector[Int]] =
    val resource =
      Option(getClass.getClassLoader.getResourceAsStream("postcard-1.1.3.hex"))
        .getOrElse(fail("missing postcard-1.1.3.hex test resource"))

    val source = Source.fromInputStream(resource, "UTF-8")
    try
      source
        .getLines()
        .zipWithIndex
        .foldLeft(Map.empty[String, Vector[Int]]):
          case (fixtures, (line, index)) =>
            val parts = line.split(":", 2)
            if parts.length != 2 then fail(s"invalid fixture line ${index + 1}: $line")

            val name = parts(0).trim
            val values =
              parts(1).trim
                .split("\\s+")
                .toVector
                .filter(_.nonEmpty)
                .map: byte =>
                  Integer.parseInt(byte, 16)

            if fixtures.contains(name) then fail(s"duplicate Rust postcard fixture '$name'")
            fixtures.updated(name, values)
    finally source.close()

  private def bytes(values: Int*): Array[Byte] =
    values.map(_.toByte).toArray

  private def unsigned(values: Array[Byte]): Vector[Int] =
    values.toVector.map(_ & 0xff)
