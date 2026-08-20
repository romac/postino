package postino

import munit.FunSuite

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException, InputStream, OutputStream}
import scala.collection.immutable.{ListMap, SortedMap, SortedSet}
import scala.io.Source as ScalaSource

final class PostinoSuite extends FunSuite:
  final case class Sensor(id: U16, temp: Int, label: String) derives Codec

  final case class Envelope(
      sensor: Sensor,
      readings: Vector[Short],
      note: Option[String],
      bytes: Array[Byte]
  ) derives Codec

  final case class UpstreamBasic(st: U16, ei: Byte, sf: U64, tt: U32) derives Codec

  sealed trait UpstreamBasicEnum derives Codec
  final case class UpstreamBasicBib() extends UpstreamBasicEnum derives Codec
  final case class UpstreamBasicBim() extends UpstreamBasicEnum derives Codec
  final case class UpstreamBasicBap() extends UpstreamBasicEnum derives Codec

  final case class UpstreamEnumStruct(eight: Byte, sixt: U16) derives Codec

  sealed trait UpstreamDataEnum derives Codec
  final case class UpstreamBib(value: U16)                extends UpstreamDataEnum derives Codec
  final case class UpstreamBim(value: U64)                extends UpstreamDataEnum derives Codec
  final case class UpstreamBap(value: Byte)               extends UpstreamDataEnum derives Codec
  final case class UpstreamKim(value: UpstreamEnumStruct) extends UpstreamDataEnum derives Codec
  final case class UpstreamChi(a: Byte, b: U32)           extends UpstreamDataEnum derives Codec
  final case class UpstreamSho(first: U16, second: Byte)  extends UpstreamDataEnum derives Codec

  final case class UpstreamNewtype(value: U32) derives Codec
  final case class UpstreamTupleStruct(value: (Byte, U16)) derives Codec

  sealed trait Message
  final case class Ping()                   extends Message derives Codec
  final case class Pong(id: U16)            extends Message derives Codec
  final case class Data(bytes: Array[Byte]) extends Message derives Codec

  sealed trait WideMessage
  final case class HighDiscriminant() extends WideMessage derives Codec

  sealed trait DerivedMessage derives Codec
  final case class DerivedPing()                   extends DerivedMessage derives Codec
  final case class DerivedPong(id: U16)            extends DerivedMessage derives Codec
  final case class DerivedData(bytes: Array[Byte]) extends DerivedMessage derives Codec

  given Codec[Message] =
    Postino
      .sum[Message]
      .variant(0, Codec[Ping])
      .variant(1, Codec[Pong])
      .variant(2, Codec[Data])
      .build

  given Codec[WideMessage] =
    Postino
      .sum[WideMessage]
      .variant(128, Codec[HighDiscriminant])
      .build

  test("fixture corpus is language-neutral and pinned to postcard 1.1.3"):
    assertEquals(rustFixtureCorpus.metadata("postcard-test-vectors-version"), "1")
    assertEquals(rustFixtureCorpus.metadata("postcard-version"), "1.1.3")
    assertEquals(rustFixtureCorpus.metadata("upstream-tag"), "postcard/v1.1.3")
    assertEquals(
      rustFixtureCorpus.metadata("upstream-commit"),
      "718aa6a6850456017c19eeff67303c633f875736"
    )
    assertEquals(rustFixtures.size, 75)
    assertEquals(
      rustFixtures.values.map(_.flavor).toSet,
      Set("raw", "cobs", "crc32-iso-hdlc", "crc32-iscsi")
    )
    assert(
      rustFixtures.view
        .filterKeys(_.startsWith("upstream_"))
        .values
        .forall(_.source.startsWith("https://github.com/jamesmunns/postcard/blob/"))
    )

  test("Postino matches upstream postcard 1.1.3 golden vectors"):
    assertFixtureRoundTrip("upstream_unit", ())
    assertFixtureRoundTrip("upstream_bool_false", false)
    assertFixtureRoundTrip("upstream_bool_true", true)
    assertFixtureRoundTrip("upstream_u8_5", 5.toByte)
    assertFixtureRoundTrip("upstream_u16_42439", U16.unsafeFromInt(0xa5c7))
    assertFixtureRoundTrip("upstream_u32_3450549266", U32.unsafeFromLong(0xcdab3412L))
    assertFixtureRoundTrip(
      "upstream_u64_1311768467294899695",
      U64.unsafeFromBigInt(BigInt("1234567890abcdef", 16))
    )
    assertFixtureRoundTrip("upstream_i16_max", Short.MaxValue)
    assertFixtureRoundTrip("upstream_i16_min", Short.MinValue)
    assertFixtureRoundTrip("upstream_char_z", 'z')
    assertFixtureRoundTrip("upstream_char_cent", '¢')

    assertEquals(
      Postino.decode[Char](fixtureBytes("upstream_char_gothic")),
      Left(PostinoError.InvalidChar(BigInt(0x10348)))
    )
    assertEquals(
      Postino.decode[Char](fixtureBytes("upstream_char_pleading_face")),
      Left(PostinoError.InvalidChar(BigInt(0x1f97a)))
    )

    assertFixtureRoundTrip(
      "upstream_struct_basic",
      UpstreamBasic(
        U16.unsafeFromInt(0xabcd),
        0xfe.toByte,
        U64.unsafeFromBigInt(BigInt("12344321abcddcba", 16)),
        U32.unsafeFromLong(0xacacacacL)
      )
    )
    assertFixtureRoundTrip[UpstreamBasicEnum]("upstream_enum_basic_bim", UpstreamBasicBim())
    assertFixtureRoundTrip[UpstreamDataEnum](
      "upstream_enum_data_bim",
      UpstreamBim(U64.unsafeFromBigInt(U64.MaxValue))
    )
    assertFixtureRoundTrip[UpstreamDataEnum](
      "upstream_enum_data_bib",
      UpstreamBib(U16.unsafeFromInt(0xffff))
    )
    assertFixtureRoundTrip[UpstreamDataEnum]("upstream_enum_data_bap", UpstreamBap(0xff.toByte))
    assertFixtureRoundTrip[UpstreamDataEnum](
      "upstream_enum_data_kim",
      UpstreamKim(UpstreamEnumStruct(0xf0.toByte, U16.unsafeFromInt(0xacac)))
    )
    assertFixtureRoundTrip[UpstreamDataEnum](
      "upstream_enum_data_chi",
      UpstreamChi(0x0f.toByte, U32.unsafeFromLong(0xc7c7c7c7L))
    )
    assertFixtureRoundTrip[UpstreamDataEnum](
      "upstream_enum_data_sho",
      UpstreamSho(U16.unsafeFromInt(0x6969), 0x07.toByte)
    )
    assertFixtureRoundTrip(
      "upstream_tuple_u8_u16",
      (0x12.toByte, U16.unsafeFromInt(0xc7a5))
    )
    assertFixtureRoundTrip("upstream_newtype_u32", UpstreamNewtype(U32.unsafeFromLong(5)))
    assertFixtureRoundTrip(
      "upstream_tuple_struct",
      UpstreamTupleStruct((0xa0.toByte, U16.unsafeFromInt(0x1234)))
    )
    assertFixtureRoundTrip("upstream_seq_u8", Vector[Byte](1, 2, 3, 4))
    assertFixtureRoundTrip("upstream_string", "helLO!")
    assertFixtureRoundTrip[SortedMap[Byte, Byte]](
      "upstream_map_u8_u8",
      SortedMap[Byte, Byte](
        1.toByte -> 5.toByte,
        2.toByte -> 6.toByte,
        3.toByte -> 7.toByte,
        4.toByte -> 8.toByte
      )
    )

    val cstringBytes = bytes('h', 'e', 'L', 'l', 'o')
    assertFixtureEncoded("upstream_cstring_bytes", cstringBytes)
    assertEquals(
      Postino.decode[Array[Byte]](fixtureBytes("upstream_cstring_bytes")).map(unsigned),
      Right(unsigned(cstringBytes))
    )

  test("Postino matches upstream postcard COBS and CRC golden vectors"):
    assertEquals(
      Postino.encodeCobs(false).map(unsigned),
      Right(fixture("upstream_cobs_false"))
    )
    assertEquals(Postino.decodeCobs[Boolean](fixtureBytes("upstream_cobs_false")), Right(false))
    assertEquals(
      Postino.encodeCobs("1").map(unsigned),
      Right(fixture("upstream_cobs_string_1"))
    )
    assertEquals(Postino.decodeCobs[String](fixtureBytes("upstream_cobs_string_1")), Right("1"))
    assertEquals(
      Postino.encodeCobs("Hi!").map(unsigned),
      Right(fixture("upstream_cobs_string_hi"))
    )
    assertEquals(
      Postino.decodeCobs[String](fixtureBytes("upstream_cobs_string_hi")),
      Right("Hi!")
    )

    val bytesValue = bytes(0x01, 0x00, 0x20, 0x30)
    assertEquals(
      Postino.encodeCobs(bytesValue).map(unsigned),
      Right(fixture("upstream_cobs_bytes"))
    )
    assertEquals(
      Postino.decodeCobs[Array[Byte]](fixtureBytes("upstream_cobs_bytes")).map(unsigned),
      Right(unsigned(bytesValue))
    )
    assertEquals(
      Postino.encodeCrc(Crc.Crc32Iscsi, bytesValue).map(unsigned),
      Right(fixture("upstream_crc32c_bytes"))
    )
    assertEquals(
      Postino
        .decodeCrc[Array[Byte]](Crc.Crc32Iscsi, fixtureBytes("upstream_crc32c_bytes"))
        .map(unsigned),
      Right(unsigned(bytesValue))
    )

  test("primitive codecs match Rust postcard fixture bytes"):
    assertFixtureRoundTrip("bool_true", true)
    assertFixtureRoundTrip("byte_minus_one", (-1).toByte)
    assertFixtureRoundTrip("u8_255", 0xff.toByte)
    assertFixtureRoundTrip("i16_minus_two", (-2).toShort)
    assertFixtureRoundTrip("i32_300", 300)
    assertFixtureRoundTrip("i64_minus_one", -1L)
    assertFixtureRoundTrip("i64_min", Long.MinValue)
    assertFixtureRoundTrip("char_e_acute", '\u00e9')
    assertFixtureRoundTrip("i128_300", BigInt(300))
    assertFixtureRoundTrip("i128_minus_one", BigInt(-1))
    assertFixtureRoundTrip("i128_min", -(BigInt(1) << 127))
    assertFixtureRoundTrip(
      "u128_340282366920938463463374607431768211455",
      U128.unsafeFromBigInt(U128.MaxValue)
    )
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

  test("U128 constructors enforce unsigned values"):
    assertEquals(U128.fromBigInt(-1), Left(PostinoError.InvalidUnsignedValue("u128", BigInt(-1))))
    assertEquals(
      U128.fromBigInt(U128.MaxValue + 1),
      Left(PostinoError.InvalidUnsignedValue("u128", U128.MaxValue + 1))
    )
    assertEquals(U128.fromBigInt(U128.MaxValue).map(_.toBigInt), Right(U128.MaxValue))

  test("char codec rejects values Scala Char cannot represent as Rust char"):
    assertEquals(Postino.encode(0xd800.toChar), Left(PostinoError.InvalidChar(BigInt(0xd800))))
    assertEquals(Postino.decode[Char](bytes(0x00)), Left(PostinoError.InvalidCharLength(0)))
    assertEquals(
      Postino.decode[Char](bytes(0x02, 0x61, 0x62)),
      Left(PostinoError.InvalidCharLength(2))
    )
    assertEquals(
      Postino.decode[Char](bytes(0x04, 0xf0, 0x9f, 0x98, 0x80)),
      Left(PostinoError.InvalidChar(BigInt(0x1f600)))
    )

  test("i128 codec rejects values outside the signed 128-bit range"):
    assertEquals(Postino.encode(BigInt(1) << 127), Left(PostinoError.VarintOverflow("i128")))
    assertEquals(
      Postino.encode(-(BigInt(1) << 127) - 1),
      Left(PostinoError.VarintOverflow("i128"))
    )

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
    assertFixtureRoundTrip[Option[Option[Int]]]("option_option_i32_some_none", Some(None))
    assertFixtureRoundTrip[Option[Option[Int]]]("option_option_i32_some_some_300", Some(Some(300)))
    assertFixtureRoundTrip("empty_vec_i16", List.empty[Short])
    assertFixtureRoundTrip("list", List[Short](1, -1, 300))

  test("fixed arrays, tuples, results, and sorted sets match Rust postcard fixture bytes"):
    val fixed = FixedArray.unsafeFrom[U16, 3](
      Vector(U16.unsafeFromInt(1), U16.unsafeFromInt(300), U16.unsafeFromInt(65535))
    )

    assertFixtureRoundTrip("fixed_array_u16_3", fixed)
    assertFixtureRoundTrip(
      "tuple_u8_u16_i32",
      (0x12.toByte, U16.unsafeFromInt(300), -2)
    )
    assertFixtureRoundTrip[Either[Int, U16]](
      "result_u16_i32_ok_300",
      Right(U16.unsafeFromInt(300))
    )
    assertFixtureRoundTrip[Either[Int, U16]]("result_u16_i32_err_minus_two", Left(-2))
    assertFixtureRoundTrip(
      "sorted_set_i16",
      SortedSet[Short]((-2).toShort, 1.toShort, 300.toShort)
    )

    assertEquals(
      FixedArray.from[Int, 2](List(1)),
      Left(PostinoError.FixedArrayLengthMismatch(2, 1))
    )
    assertEquals(
      Postino.decode[Either[Int, U16]](bytes(0x02)),
      Left(PostinoError.UnknownVariant(2))
    )

  test("maps match Rust postcard fixture bytes"):
    val sorted = SortedMap(1 -> "one", 2 -> "two")

    assertFixtureRoundTrip[SortedMap[Int, String]]("map_i32_string", sorted)
    assertFixtureEncoded[Map[Int, String]]("map_i32_string", sorted)
    assertEquals(
      Postino.decode[Map[Int, String]](fixtureBytes("map_i32_string")),
      Right(sorted.toMap)
    )

  test("map encoding preserves the provided iteration order"):
    val ordered: Map[Int, String] = ListMap(2 -> "two", 1 -> "one")

    assertEquals(
      Postino.encode[Map[Int, String]](ordered).map(unsigned),
      Right(Vector(0x02, 0x04, 0x03, 0x74, 0x77, 0x6f, 0x02, 0x03, 0x6f, 0x6e, 0x65))
    )

  test("sequence decode rejects lengths over the configured maximum"):
    val decodeOptions = DecodeOptions(maxCollectionLength = 2, maxCollectionElements = 10)

    assertEquals(
      Postino.decode[List[Unit]](bytes(0x03), decodeOptions),
      Left(PostinoError.CollectionLengthTooLarge(3, 2))
    )

  test("map decode rejects lengths over the configured maximum"):
    val decodeOptions = DecodeOptions(maxCollectionLength = 2, maxCollectionElements = 10)

    assertEquals(
      Postino.decode[Map[Unit, Unit]](bytes(0x03), decodeOptions),
      Left(PostinoError.CollectionLengthTooLarge(3, 2))
    )

  test("sequence decode applies a total collection element budget"):
    val decodeOptions = DecodeOptions(maxCollectionLength = 3, maxCollectionElements = 5)

    assertEquals(
      Postino.decode[List[List[Unit]]](bytes(0x02, 0x03, 0x03), decodeOptions),
      Left(PostinoError.CollectionElementLimitExceeded(3, 0, 5))
    )

  test("map decode applies the total collection element budget to keys and values"):
    val decodeOptions = DecodeOptions(maxCollectionLength = 3, maxCollectionElements = 5)

    assertEquals(
      Postino.decode[Map[Unit, Unit]](bytes(0x03), decodeOptions),
      Left(PostinoError.CollectionElementLimitExceeded(6, 5, 5))
    )

  test("reader rejects negative byte lengths distinctly"):
    assertEquals(
      Reader.from(Array.emptyByteArray).readBytes(-1),
      Left(PostinoError.NegativeLength(-1))
    )

  test("byte blob decode rejects lengths over the configured maximum"):
    val decodeOptions = DecodeOptions(maxByteLength = 4)

    assertEquals(
      Postino.decodeFrom[String](
        ByteArrayInputStream(bytes(0xff, 0xff, 0xff, 0xff, 0x07)),
        decodeOptions
      ),
      Left(PostinoError.ByteLengthTooLarge(Int.MaxValue, 4))
    )

  test("streaming encode writes postcard bytes to an OutputStream"):
    val output = ByteArrayOutputStream()

    assertEquals(Postino.encodeTo(300, output), Right(()))
    assertEquals(unsigned(output.toByteArray), fixture("i32_300"))

  test("streaming decode reads postcard bytes from an InputStream"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")
    val input  = ByteArrayInputStream(fixtureBytes("sensor"))

    assertEquals(Postino.decodeFrom[Sensor](input), Right(sensor))

  test("streaming decode rejects trailing bytes on finite InputStreams"):
    val input = ByteArrayInputStream(bytes(0x01, 0xff))

    assertEquals(Postino.decodeFrom[Boolean](input), Left(PostinoError.TrailingBytes(1, 1)))

  test("streaming I/O failures report structured errors"):
    val failingInput = new InputStream:
      def read(): Int =
        throw IOException("boom")

    val failingOutput = new OutputStream:
      def write(value: Int): Unit =
        throw IOException("full")

    assertEquals(
      Postino.decodeFrom[Boolean](failingInput),
      Left(PostinoError.Io("read byte", "boom"))
    )
    assertEquals(
      Postino.encodeTo(true, failingOutput),
      Left(PostinoError.Io("write byte", "full"))
    )

  test("case class products encode constructor fields without names or length"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")
    assertFixtureRoundTrip("sensor", sensor)

  test("COBS framing matches Rust postcard fixture bytes"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")

    assertEquals(Postino.encodeCobs(false).map(unsigned), Right(Vector(0x01, 0x01, 0x00)))
    assertEquals(Postino.encodeCobs(sensor).map(unsigned), Right(fixture("sensor_cobs")))
    assertEquals(Postino.decodeCobs[Sensor](fixtureBytes("sensor_cobs")), Right(sensor))

  test("COBS decode rejects malformed frames"):
    assertEquals(
      Postino.decodeCobs[Boolean](bytes(0x02, 0x01)),
      Left(PostinoError.CobsFraming("missing terminator"))
    )
    assertEquals(
      Postino.decodeCobs[Boolean](bytes(0x02, 0x01, 0x00, 0x00)),
      Left(PostinoError.CobsZeroInPayload(2))
    )
    assertEquals(
      Postino.decodeCobs[Boolean](bytes(0x05, 0x01, 0x00)),
      Left(PostinoError.CobsFraming("invalid run length"))
    )

  test("COBS encoding does not add an empty block after a full non-zero run"):
    val payload = Array.fill(254)(1.toByte)
    val frame   = Array(0xff.toByte) ++ payload ++ Array(0.toByte)

    assertEquals(Cobs.encode(payload).map(unsigned), Right(unsigned(frame)))
    assertEquals(Cobs.decode(frame).map(unsigned), Right(unsigned(payload)))

  test("CRC framing matches Rust postcard fixture bytes"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")

    assertEquals(Postino.encodeCrc(sensor).map(unsigned), Right(fixture("sensor_crc32")))
    assertEquals(
      Postino.encodeCrc(Crc.Crc32Fast, sensor).map(unsigned),
      Right(fixture("sensor_crc32"))
    )
    assertEquals(Postino.decodeCrc[Sensor](fixtureBytes("sensor_crc32")), Right(sensor))

  test("CRC-32C implementation matches the standard check value"):
    val input = "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII)

    assertEquals(unsigned(Crc.Crc32Iscsi.checksum(input)), Vector(0x83, 0x92, 0x06, 0xe3))

  test("CRC decode rejects short and mismatched checksums"):
    assertEquals(
      Postino.decodeCrc[Boolean](bytes(0x01, 0x02, 0x03)),
      Left(PostinoError.CrcPayloadTooShort(3, 4))
    )

    val corrupted = fixtureBytes("sensor_crc32")
    corrupted(corrupted.length - 1) = (corrupted.last ^ 0xff).toByte

    val expected = unsigned(Crc.Crc32Fast.checksum(fixtureBytes("sensor")))
    val actual   = unsigned(corrupted.takeRight(Crc.Crc32Fast.widthBytes))
    assertEquals(
      Postino.decodeCrc[Sensor](corrupted),
      Left(PostinoError.CrcMismatch(expected, actual))
    )

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
    assertFixtureRoundTrip[WideMessage]("enum_discriminant_128", HighDiscriminant())

    assertEquals(
      Postino
        .decode[Message](fixtureBytes("enum_data"))
        .map:
          case Data(value) => unsigned(value)
          case other       => fail(s"expected Data, got $other")
      ,
      Right(Vector(9, 8, 7))
    )

  test("derived sums encode and decode declaration-order discriminants"):
    assertFixtureRoundTrip[DerivedMessage]("derived_enum_ping", DerivedPing())
    assertFixtureRoundTrip[DerivedMessage](
      "derived_enum_pong",
      DerivedPong(U16.unsafeFromInt(0xabcd))
    )
    assertFixtureEncoded[DerivedMessage]("derived_enum_data", DerivedData(Array[Byte](9, 8, 7)))

    assertEquals(
      Postino
        .decode[DerivedMessage](fixtureBytes("derived_enum_data"))
        .map:
          case DerivedData(value) => unsigned(value)
          case other              => fail(s"expected DerivedData, got $other")
      ,
      Right(Vector(9, 8, 7))
    )

    assertEquals(
      Postino.decode[DerivedMessage](bytes(0x7f)),
      Left(PostinoError.UnknownVariant(127))
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
    assertEquals(
      Postino.decode[U128](bytes(Vector.fill(19)(0x80)*)),
      Left(PostinoError.VarintTooLong(19))
    )
    assertEquals(
      Postino.decode[U128](bytes((Vector.fill(18)(0xff) :+ 0x04)*)),
      Left(PostinoError.VarintOverflow("u128"))
    )
    assertEquals(Postino.decode[Message](bytes(0x7f)), Left(PostinoError.UnknownVariant(127)))

  private final case class RustFixture(
      flavor: String,
      schema: String,
      value: String,
      bytes: Vector[Int],
      source: String
  )

  private final case class RustFixtureCorpus(
      metadata: Map[String, String],
      fixtures: Map[String, RustFixture]
  )

  private lazy val rustFixtureCorpus: RustFixtureCorpus =
    loadRustFixtures()

  private lazy val rustFixtures: Map[String, RustFixture] =
    rustFixtureCorpus.fixtures

  private def assertFixtureRoundTrip[A](name: String, value: A)(using codec: Codec[A]): Unit =
    assertFixtureEncoded(name, value)
    assertEquals(Postino.decode[A](fixtureBytes(name)), Right(value))

  private def assertFixtureEncoded[A](name: String, value: A)(using encoder: Encoder[A]): Unit =
    assertEquals(Postino.encode(value).map(unsigned), Right(fixture(name)))

  private def fixture(name: String): Vector[Int] =
    rustFixtures.getOrElse(name, fail(s"missing Rust postcard fixture '$name'")).bytes

  private def fixtureBytes(name: String): Array[Byte] =
    fixture(name).map(_.toByte).toArray

  private def loadRustFixtures(): RustFixtureCorpus =
    val resource =
      Option(getClass.getClassLoader.getResourceAsStream("postcard-1.1.3-vectors.tsv"))
        .getOrElse(fail("missing postcard-1.1.3-vectors.tsv test resource"))

    val source = ScalaSource.fromInputStream(resource, "UTF-8")
    try
      val lines = source.getLines().toVector
      val metadata =
        lines
          .takeWhile(_.startsWith("# "))
          .foldLeft(Map.empty[String, String]): (metadata, line) =>
            line.stripPrefix("# ").split(": ", 2) match
              case Array(key, _) if metadata.contains(key) =>
                fail(s"duplicate fixture metadata '$key'")
              case Array(key, value) => metadata.updated(key, value)
              case _                 => fail(s"invalid fixture metadata: $line")

      val body           = lines.dropWhile(_.startsWith("# "))
      val expectedHeader = "name\tflavor\tschema\tvalue\tbytes\tsource"
      if body.headOption != Some(expectedHeader) then
        fail(s"invalid fixture header: ${body.headOption.getOrElse("missing")}")

      val fixtures =
        body.tail.zipWithIndex.foldLeft(Map.empty[String, RustFixture]):
          case (fixtures, (line, index)) =>
            val parts = line.split("\t", -1)
            if parts.length != 6 then fail(s"invalid fixture line ${index + 6}: $line")

            val name   = parts(0)
            val flavor = parts(1)
            val schema = parts(2)
            val value  = parts(3)
            val source = parts(5)
            val required = Vector(
              "name"   -> name,
              "flavor" -> flavor,
              "schema" -> schema,
              "value"  -> value,
              "source" -> source
            )
            required
              .collectFirst { case (field, "") => field }
              .foreach: field =>
                fail(s"empty $field in fixture line ${index + 6}")
            if !Set("raw", "cobs", "crc32-iso-hdlc", "crc32-iscsi").contains(flavor) then
              fail(s"unknown fixture flavor '$flavor'")

            val values =
              parts(4)
                .split("\\s+")
                .toVector
                .filter(_.nonEmpty)
                .map: byte =>
                  val value = Integer.parseInt(byte, 16)
                  if value > 0xff then fail(s"fixture byte out of range: $byte")
                  value
            val fixture = RustFixture(flavor, schema, value, values, source)

            if fixtures.contains(name) then fail(s"duplicate Rust postcard fixture '$name'")
            fixtures.updated(name, fixture)

      RustFixtureCorpus(metadata, fixtures)
    finally source.close()

  private def bytes(values: Int*): Array[Byte] =
    values.map(_.toByte).toArray

  private def unsigned(values: Array[Byte]): Vector[Int] =
    values.toVector.map(_ & 0xff)
