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

  private def assertPostinoError[A](result: Either[Throwable, A], expected: PostinoError): Unit =
    result match
      case Left(PostinoFs2Exception(error)) => assertEquals(error, expected)
      case Left(other)                      => fail(s"Expected PostinoFs2Exception, got $other")
      case Right(value)                     => fail(s"Expected stream failure, got $value")
