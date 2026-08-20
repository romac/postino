package postino.circe

import munit.FunSuite
import postino.*
import _root_.io.circe.Json

import scala.collection.immutable.SortedSet

final class PostinoCirceSuite extends FunSuite:
  final case class Sensor(id: U16, temp: Int, label: String) derives Codec
  final case class Blob(bytes: Array[Byte]) derives Codec
  final case class Snapshot(
      sensor: Sensor,
      readings: Vector[Int],
      flags: Option[Boolean],
      labels: Map[String, Int]
  ) derives Codec

  sealed trait Message derives Codec
  final case class Ping()        extends Message derives Codec
  final case class Pong(id: U16) extends Message derives Codec

  enum Event derives Codec:
    case Idle
    case Reading(value: Int)

  test("toCirce encodes and decodes products with mirror field names"):
    val codec  = PostinoCirce.toCirce[Sensor]
    val sensor = Sensor(U16.unsafeFromInt(0x1234), -21, "lab")
    val json = Json.obj(
      "id"    -> Json.fromInt(0x1234),
      "temp"  -> Json.fromInt(-21),
      "label" -> Json.fromString("lab")
    )

    assertEquals(codec(sensor), json)
    assertEquals(codec.decodeJson(json), Right(sensor))

  test("toCirce encodes byte arrays as unsigned byte arrays"):
    val codec = PostinoCirce.toCirce[Blob]
    val json = Json.obj(
      "bytes" -> Json.arr(Json.fromInt(0), Json.fromInt(255))
    )

    assertEquals(codec(Blob(Array[Byte](0, -1))), json)
    assertEquals(
      codec.decodeJson(json).map(blob => blob.bytes.toVector.map(_ & 0xff)),
      Right(Vector(0, 255))
    )

  test("toCirce encodes collections and maps with stable schema shapes"):
    val codec = PostinoCirce.toCirce[Snapshot]
    val snapshot =
      Snapshot(
        Sensor(U16.unsafeFromInt(7), 42, "rack"),
        Vector(1, 2),
        Some(true),
        Map("one" -> 1, "two" -> 2)
      )
    val json = Json.obj(
      "sensor" -> Json.obj(
        "id"    -> Json.fromInt(7),
        "temp"  -> Json.fromInt(42),
        "label" -> Json.fromString("rack")
      ),
      "readings" -> Json.arr(Json.fromInt(1), Json.fromInt(2)),
      "flags"    -> Json.fromBoolean(true),
      "labels" -> Json.arr(
        Json.obj("key" -> Json.fromString("one"), "value" -> Json.fromInt(1)),
        Json.obj("key" -> Json.fromString("two"), "value" -> Json.fromInt(2))
      )
    )

    assertEquals(codec(snapshot), json)
    assertEquals(codec.decodeJson(json), Right(snapshot))

  test("toCirce encodes and decodes fixed arrays and sorted sets"):
    val fixedCodec = PostinoCirce.toCirce[FixedArray[Int, 2]]
    val setCodec   = PostinoCirce.toCirce[SortedSet[Int]]
    val fixed      = FixedArray.unsafeFrom[Int, 2](Vector(1, 2))
    val fixedJson  = Json.arr(Json.fromInt(1), Json.fromInt(2))
    val set        = SortedSet(1, 2)
    val setJson    = Json.arr(Json.fromInt(1), Json.fromInt(2))

    assertEquals(fixedCodec(fixed), fixedJson)
    assertEquals(fixedCodec.decodeJson(fixedJson), Right(fixed))
    assertEquals(setCodec(set), setJson)
    assertEquals(setCodec.decodeJson(setJson), Right(set))

  test("toCirce encodes and decodes sums as tagged objects"):
    val codec = PostinoCirce.toCirce[Message]
    val json = Json.obj(
      "tag" -> Json.fromString("Pong"),
      "value" -> Json.obj(
        "id" -> Json.fromInt(0xabcd)
      )
    )

    assertEquals(codec(Pong(U16.unsafeFromInt(0xabcd))), json)
    assertEquals(codec.decodeJson(json), Right(Pong(U16.unsafeFromInt(0xabcd))))

  test("toCirce encodes and decodes directly derived Scala enums"):
    val codec = PostinoCirce.toCirce[Event]
    val json = Json.obj(
      "tag" -> Json.fromString("Reading"),
      "value" -> Json.obj(
        "value" -> Json.fromInt(42)
      )
    )

    assertEquals(codec(Event.Reading(42)), json)
    assertEquals(codec.decodeJson(json), Right(Event.Reading(42)))

  test("toCirce rejects unknown sum tags"):
    val codec = PostinoCirce.toCirce[Message]
    val result = codec.decodeJson(
      Json.obj(
        "tag"   -> Json.fromString("Missing"),
        "value" -> Json.obj()
      )
    )

    assertEquals(result.left.map(_.message), Left("unknown Postino Circe variant tag Missing"))
