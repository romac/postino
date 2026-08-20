package postino.fs2

import munit.FunSuite
import postino.*
import _root_.fs2.{Fallible, Stream}

final class PostinoFs2Suite extends FunSuite:
  final case class Sensor(id: U16, temp: Int, label: String) derives Codec

  test("encodeCobs emits the same framed bytes as core Postino"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")

    val encoded =
      Stream
        .emit(sensor)
        .through(PostinoFs2.encodeCobs[Fallible, Sensor])
        .toList

    val expected: Either[Throwable, List[Byte]] =
      Right(Postino.encodeCobs(sensor).toOption.get.toList)
    assertEquals(encoded, expected)

  test("decodeCobs decodes one framed value"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")
    val frame  = Postino.encodeCobs(sensor).toOption.get

    val decoded =
      Stream
        .emits(frame)
        .through(PostinoFs2.decodeCobs[Fallible, Sensor])
        .toList

    assertEquals(decoded, Right(List(sensor)))

  test("decodeCobs decodes adjacent frames"):
    val left  = Sensor(U16.unsafeFromInt(1), 10, "left")
    val right = Sensor(U16.unsafeFromInt(2), 20, "right")
    val bytes =
      Postino.encodeCobs(left).toOption.get ++
        Postino.encodeCobs(right).toOption.get

    val decoded =
      Stream
        .emits(bytes)
        .through(PostinoFs2.decodeCobs[Fallible, Sensor])
        .toList

    assertEquals(decoded, Right(List(left, right)))

  test("decodeCobs decodes frames split across upstream chunks"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")
    val frame  = Postino.encodeCobs(sensor).toOption.get

    val decoded =
      (Stream.emits(frame.take(2)) ++ Stream.emits(frame.drop(2)))
        .through(PostinoFs2.decodeCobs[Fallible, Sensor])
        .toList

    assertEquals(decoded, Right(List(sensor)))

  test("decodeCobs rejects an unterminated frame"):
    val result =
      Stream
        .emits(Array[Byte](0x02, 0x01))
        .through(PostinoFs2.decodeCobs[Fallible, Boolean])
        .toList

    assertPostinoError(result, PostinoError.CobsFraming("missing terminator"))

  test("decodeCobs enforces the configured frame byte limit across chunks"):
    val result =
      (Stream.emits(Array[Byte](0x02)) ++ Stream.emits(Array[Byte](0x01, 0x01)))
        .through(PostinoFs2.decodeCobs[Fallible, Boolean](DecodeOptions(maxByteLength = 2)))
        .toList

    assertPostinoError(result, PostinoError.CobsFrameTooLarge(3, 2))

  test("decodeCobs uses configured Postino decode options"):
    val frame = Postino.encodeCobs(List[Unit]((), ())).toOption.get
    val result =
      Stream
        .emits(frame)
        .through(
          PostinoFs2.decodeCobs[Fallible, List[Unit]](
            DecodeOptions(maxCollectionLength = 1, maxCollectionElements = 10)
          )
        )
        .toList

    assertPostinoError(result, PostinoError.CollectionLengthTooLarge(2, 1))

  test("encodeCrc emits the same framed bytes as core Postino"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")

    val encoded =
      Stream
        .emit(sensor)
        .through(PostinoFs2.encodeCrc[Fallible, Sensor])
        .toList

    val expected: Either[Throwable, List[Byte]] =
      Right(Postino.encodeCrc(sensor).toOption.get.toList)
    assertEquals(encoded, expected)

  test("decodeCrc decodes adjacent frames split across upstream chunks"):
    val left  = Sensor(U16.unsafeFromInt(1), 10, "left")
    val right = Sensor(U16.unsafeFromInt(2), 20, "right")
    val bytes =
      Postino.encodeCrc(left).toOption.get ++
        Postino.encodeCrc(right).toOption.get

    val decoded =
      (Stream.emits(bytes.take(3)) ++ Stream.emits(bytes.slice(3, 8)) ++ Stream.emits(
        bytes.drop(8)
      ))
        .through(PostinoFs2.decodeCrc[Fallible, Sensor])
        .toList

    assertEquals(decoded, Right(List(left, right)))

  test("decodeCrc preserves bytes beyond a discovered payload boundary"):
    val left  = "x" * 100
    val right = "right"
    val bytes =
      Postino.encodeCrc(left).toOption.get ++
        Postino.encodeCrc(right).toOption.get

    val decoded =
      Stream
        .emits(bytes)
        .through(PostinoFs2.decodeCrc[Fallible, String])
        .toList

    assertEquals(decoded, Right(List(left, right)))

  test("CRC streaming supports explicit CRC flavors"):
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")
    val crc    = Crc.Crc32Iscsi
    val frame  = Postino.encodeCrc(crc, sensor).toOption.get

    val encoded =
      Stream
        .emit(sensor)
        .through(PostinoFs2.encodeCrc[Fallible, Sensor](crc))
        .toList
    val decoded =
      Stream
        .emits(frame)
        .through(PostinoFs2.decodeCrc[Fallible, Sensor](crc))
        .toList

    assertEquals(encoded, Right(frame.toList))
    assertEquals(decoded, Right(List(sensor)))

  test("decodeCrc handles zero-byte postcard payloads"):
    val frame = Postino.encodeCrc(()).toOption.get
    val result =
      Stream
        .emits(frame)
        .through(PostinoFs2.decodeCrc[Fallible, Unit])
        .toList

    assertEquals(result, Right(List(())))

  test("decodeCrc rejects mismatched and truncated checksums"):
    val frame     = Postino.encodeCrc(true).toOption.get
    val corrupted = frame.updated(frame.length - 1, (frame.last ^ 0xff).toByte)

    val mismatch =
      Stream
        .emits(corrupted)
        .through(PostinoFs2.decodeCrc[Fallible, Boolean])
        .toList
    val truncated =
      Stream
        .emits(frame.dropRight(1))
        .through(PostinoFs2.decodeCrc[Fallible, Boolean])
        .toList
    val truncatedPayload =
      Stream
        .emits(Array[Byte](0x03, 'a'.toByte))
        .through(PostinoFs2.decodeCrc[Fallible, String])
        .toList

    val expectedChecksum = Crc.Crc32Fast.checksum(Array[Byte](1)).toVector.map(_ & 0xff)
    val actualChecksum   = corrupted.takeRight(Crc.Crc32Fast.widthBytes).toVector.map(_ & 0xff)
    assertPostinoError(mismatch, PostinoError.CrcMismatch(expectedChecksum, actualChecksum))
    assertPostinoError(truncated, PostinoError.CrcFraming("missing checksum"))
    assertPostinoError(truncatedPayload, PostinoError.CrcFraming("truncated payload"))

  test("decodeCrc enforces the configured frame byte limit"):
    val frame = Postino.encodeCrc(true).toOption.get
    val limit = frame.length - 1
    val result =
      Stream
        .emits(frame)
        .through(
          PostinoFs2.decodeCrc[Fallible, Boolean](DecodeOptions(maxByteLength = limit))
        )
        .toList

    assertPostinoError(result, PostinoError.CrcFrameTooLarge(frame.length, limit))

  test("decodeCrc uses configured Postino decode options"):
    val frame = Postino.encodeCrc(List[Unit]((), ())).toOption.get
    val result =
      Stream
        .emits(frame)
        .through(
          PostinoFs2.decodeCrc[Fallible, List[Unit]](
            DecodeOptions(maxCollectionLength = 1, maxCollectionElements = 10)
          )
        )
        .toList

    assertPostinoError(result, PostinoError.CollectionLengthTooLarge(2, 1))

  private def assertPostinoError[A](result: Either[Throwable, A], expected: PostinoError): Unit =
    result match
      case Left(PostinoFs2Exception(error)) => assertEquals(error, expected)
      case Left(other)                      => fail(s"Expected PostinoFs2Exception, got $other")
      case Right(value)                     => fail(s"Expected stream failure, got $value")
