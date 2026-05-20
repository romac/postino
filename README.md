# Postino

Postino is a Scala 3 implementation of the Rust [`postcard`](https://docs.rs/postcard) 1.x wire format.

It is useful when a Scala program needs to read or write bytes compatible with Rust `postcard::to_stdvec`, `postcard::to_stdvec_cobs`, or postcard's CRC flavor for a known schema. The format is not self-describing: both sides must agree on field order, enum discriminants, and supported types.

The supported wire-format boundary is documented in [docs/compatibility.md](docs/compatibility.md).

## Installation

Core module:

```scala
ivy"me.romac::postino:0.1.0-M1"
```

Optional scodec adapter:

```scala
ivy"me.romac::postino-scodec:0.1.0-M1"
```

Optional Circe adapter:

```scala
ivy"me.romac::postino-circe:0.1.0-M1"
```

Optional FS2 adapter:

```scala
ivy"me.romac::postino-fs2:0.1.0-M1"
```

For sbt:

```scala
libraryDependencies += "me.romac" %% "postino" % "0.1.0-M1"
libraryDependencies += "me.romac" %% "postino-scodec" % "0.1.0-M1"
libraryDependencies += "me.romac" %% "postino-circe" % "0.1.0-M1"
libraryDependencies += "me.romac" %% "postino-fs2" % "0.1.0-M1"
```

## Basic Usage

The main entry points are `Postino.encode` and `Postino.decode`.

```scala
import postino.*

val encoded: Either[PostinoError, Array[Byte]] =
  Postino.encode(300)

val decoded: Either[PostinoError, Int] =
  Postino.decode[Int](Array(0xd8.toByte, 0x04.toByte))
```

Encoding and decoding return `Either[PostinoError, A]`. Top-level decode rejects trailing bytes:

```scala
Postino.decode[Boolean](Array(0x01.toByte, 0x00.toByte))
// Left(PostinoError.TrailingBytes(1, 1))
```

## Streaming I/O

Use `Postino.encodeTo` and `Postino.decodeFrom` when the bytes live behind a Java stream and you do not want to buffer the whole message first.

```scala
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import postino.*

val output = ByteArrayOutputStream()
val encoded: Either[PostinoError, Unit] =
  Postino.encodeTo(300, output)

val input = ByteArrayInputStream(output.toByteArray)
val decoded: Either[PostinoError, Int] =
  Postino.decodeFrom[Int](input)
```

`decodeFrom` expects a finite source for raw postcard payloads and checks for trailing bytes by reading to the end of that source. For long-lived sockets or byte streams carrying multiple messages, use a framing layer such as COBS first.

## Framing

Use `Postino.encodeCobs` and `Postino.decodeCobs` for postcard COBS frames. COBS decoding expects a complete frame with a final `0x00` terminator and rejects zero bytes before that terminator.

```scala
val roundTrip: Either[PostinoError, Int] =
  for
    framed <- Postino.encodeCobs(300)
    decoded <- Postino.decodeCobs[Int](framed)
  yield decoded
```

Use `Postino.encodeCrc` and `Postino.decodeCrc` for postcard's trailing-CRC flavor. The default `Crc` is `Crc.Crc32Fast`, compatible with CRC-32/ISO-HDLC.

```scala
val crcRoundTrip: Either[PostinoError, Int] =
  for
    framed <- Postino.encodeCrc(300)
    decoded <- Postino.decodeCrc[Int](framed)
  yield decoded
```

Pass an explicit `Crc` or provide a `given Crc` when a schema uses a different CRC-32 polynomial.

## Deriving Product Codecs

Case classes can derive positional codecs with Scala 3 mirrors.

```scala
import postino.*

final case class Sensor(
    id: U16,
    temp: Int,
    label: String
) derives Codec

val sensor =
  Sensor(U16.unsafeFromInt(0x1234), -21, "lab")

val bytes: Either[PostinoError, Array[Byte]] =
  Postino.encode(sensor)
```

Product encoding matches Rust struct layout:

- constructor fields are encoded in order
- field names are not encoded
- no product length prefix is encoded
- every field type must have a `Codec`

Derived product decoders catch constructor failures, such as `require(...)`, and return `PostinoError.ProductConstructionFailed`.

## Sums

Sealed trait hierarchies can derive codecs when every concrete subtype has its own `Codec` and Scala declaration order matches Rust enum declaration order.

```scala
import postino.*

sealed trait Message derives Codec
final case class Ping() extends Message derives Codec
final case class Pong(id: U16) extends Message derives Codec
final case class Data(bytes: Array[Byte]) extends Message derives Codec
```

Derived sum codecs assign `u32` discriminants in declaration order: `0` for `Ping`, `1` for `Pong`, `2` for `Data`.

Scala 3 `enum` cases are not auto-derived today because each derived sum child must have its own `Codec`. Model the schema as a sealed trait hierarchy like the example above, or use `Postino.sum` with explicitly supplied variant codecs.

Use the explicit builder when the Rust schema uses custom, sparse, or non-declaration-order discriminants.

```scala
import postino.*

sealed trait WideMessage
final case class HighDiscriminant() extends WideMessage derives Codec

given Codec[WideMessage] =
  Postino
    .sum[WideMessage]
    .variant(128, Codec[HighDiscriminant])
    .build
```

Encoding writes a `u32` varint discriminant followed by the selected variant payload. Decoding reads the discriminant and dispatches to the derived or registered codec.

Duplicate discriminants fail when `.build` is called. If more than one registered runtime class matches a value during encoding, encoding fails with `PostinoError.AmbiguousVariant`.

## Supported Types

The core module includes bidirectional codecs for:

- `Unit`
- `Boolean`
- `Char`
- `Byte`
- `Short`
- `Int`
- `Long`
- `BigInt` for Rust `i128`
- `Float`
- `Double`
- `String`
- `Array[Byte]`
- unsigned wrappers `U16`, `U32`, `U64`, and `U128`
- `Option[A]`
- `List[A]`
- `Vector[A]`
- `Array[A]`
- `Map[K, V]`
- `SortedMap[K, V]`
- case class products via `derives Codec`
- declaration-order sealed trait hierarchies via `derives Codec` when every subtype has a `Codec`
- explicit ADTs via `Postino.sum`

Scala has no native unsigned integer types matching Rust `u16`, `u32`, `u64`, and `u128`, so Postino exposes wrappers:

```scala
val port: Either[PostinoError, U16] =
  U16.fromInt(8080)

val max: U64 =
  U64.unsafeFromBigInt(U64.MaxValue)
```

Use the safe constructors when decoding external input into your own model.
The `unsafeFrom...` helpers throw `IllegalArgumentException` and are mainly for tests, examples, and constants.
`U64.fromLong` treats its input as a signed JVM `Long` and rejects negative values; use `U64.fromUnsignedLong(bits)` when you already have the raw unsigned 64-bit bit pattern.
Use `u64.toBigInt` for the numeric unsigned value and `u64.toUnsignedLong` for the raw JVM `Long` bit pattern.
Use `BigInt` for Rust `i128`; values outside the signed 128-bit range fail with `PostinoError.VarintOverflow`.
Use `U128` for Rust `u128`.

Rust `u8` values use the raw `Byte` codec. Values above 127 appear as negative Scala `Byte` values; mask with `byte & 0xff` when you need the unsigned integer view.

Rust `char` values encode as a UTF-8 byte length followed by the scalar's bytes. Use the Scala `Char` codec when they fit in a single non-surrogate UTF-16 code unit. Supplementary Rust scalar values need a schema-level representation other than Scala `Char`.

Map codecs encode exactly the map value's iteration order. Use `SortedMap[K, V]` when you need stable key order compatible with Rust `BTreeMap`; Rust `HashMap` wire order is not stable.

## Decode Limits

Collection and byte-blob decoders enforce configurable safety limits so small inputs cannot request unbounded decode work. The defaults allow at most 1,000,000 elements in one collection, 1,000,000 collection elements across a whole top-level decode, and 1,000,000 bytes in one `String` or `Array[Byte]`.

```scala
val decoded =
  Postino.decode[List[Int]](
    bytes,
    DecodeOptions(
      maxCollectionLength = 1024,
      maxCollectionElements = 4096,
      maxByteLength = 1024 * 1024
    )
  )
```

## Encoder, Decoder, And Codec

Postino exposes separate `Encoder[A]` and `Decoder[A]` type classes, plus `Codec[A]` for bidirectional support.

Most users should provide or summon `Codec[A]`. `Encoder[A]` and `Decoder[A]` are accepted by `Postino.encode` and `Postino.decode` for top-level values and can be useful in hand-written codecs, but the built-in collection instances and `derives Codec` are bidirectional. Nested element types and derived product fields must provide a full `Codec[A]`.

## Scodec Adapter

The optional scodec module adapts a `postino.Codec[A]` to an `scodec.Codec[A]`.

```scala
import postino.*
import postino.scodec.PostinoScodec

val codec: scodec.Codec[Int] =
  PostinoScodec.toScodec[Int]
```

Pass `DecodeOptions` when adapting a codec if the default collection decode limits are not appropriate:

```scala
val codec: scodec.Codec[List[Int]] =
  PostinoScodec.toScodec[List[Int]](
    DecodeOptions(maxCollectionLength = 1024, maxCollectionElements = 4096)
  )
```

The adapter reports `SizeBound.unknown`, requires byte-aligned input, returns the unconsumed byte-aligned remainder from decode, and maps `PostinoError.message` into `scodec.Err`.

## Circe Adapter

The optional Circe module derives an `io.circe.Codec[A]` for Postino schemas. It is meant for debugging, logging, and JSON-facing tools; postcard itself is positional and not self-describing, so this is a schema-driven JSON projection rather than a wire-format conversion.

```scala
import postino.*
import postino.circe.PostinoCirce

final case class Sensor(id: U16, temp: Int, label: String) derives Codec

val codec: io.circe.Codec[Sensor] =
  PostinoCirce.toCirce[Sensor]
```

Products encode as JSON objects using Scala mirror field names. Sums encode as tagged objects:

```json
{ "tag": "Pong", "value": { "id": 43981 } }
```

Maps encode as ordered arrays of `{ "key": ..., "value": ... }` entries so non-string keys and wire-order-sensitive maps stay representable.

Options encode as JSON `null` for `None` and as the inner JSON value for `Some`. That keeps ordinary optional fields compact, but nested options do not round-trip through this JSON projection because `None` and `Some(None)` both become `null`; the postcard wire codec still preserves nested options.

## FS2 Adapter

The optional FS2 module provides COBS-framed pipes for byte streams. Each input value becomes one postcard COBS frame, and decoding waits for the `0x00` frame terminator before handing the frame to `Postino.decodeCobs`.

```scala
import fs2.{Fallible, Stream}
import postino.*
import postino.fs2.PostinoFs2

final case class Sensor(id: U16, temp: Int, label: String) derives Codec

val sensor =
  Sensor(U16.unsafeFromInt(0x1234), -21, "lab")

val framed: Either[Throwable, List[Byte]] =
  Stream
    .emit(sensor)
    .through(PostinoFs2.encodeCobs[Fallible, Sensor])
    .toList
```

Use the same pipe shape to decode framed bytes:

```scala
val decoded: fs2.Pipe[fs2.Fallible, Byte, Sensor] =
  PostinoFs2.decodeCobs[Fallible, Sensor]
```

The pipes require an FS2 effect that can raise `Throwable`, such as `cats.effect.IO` or `fs2.Fallible`. Postino errors are raised as `PostinoFs2Exception`, preserving the structured `PostinoError` value. `PostinoFs2.decodeCobs` buffers one frame at a time and rejects frames larger than `DecodeOptions.maxByteLength`.

## Limitations

Postino v0 does not support:

- Serde attributes
- schema evolution

## License

Postino is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
