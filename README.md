# Postino

Postino is a Scala 3 implementation of the Rust [`postcard`](https://docs.rs/postcard) 1.x non-COBS wire format.

It is useful when a Scala program needs to read or write bytes compatible with Rust `postcard::to_stdvec` for a known schema. The format is not self-describing: both sides must agree on field order, enum discriminants, and supported types.

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

For sbt:

```scala
libraryDependencies += "me.romac" %% "postino" % "0.1.0-M1"
libraryDependencies += "me.romac" %% "postino-scodec" % "0.1.0-M1"
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

## Explicit Sums And Enums

Sums are not derived automatically. The Rust enum discriminant is part of the wire schema, so each variant must be registered explicitly.

```scala
import postino.*

sealed trait Message
final case class Ping() extends Message derives Codec
final case class Pong(id: U16) extends Message derives Codec
final case class Data(bytes: Array[Byte]) extends Message derives Codec

given Codec[Message] =
  Postino
    .sum[Message]
    .variant(0, Codec[Ping])
    .variant(1, Codec[Pong])
    .variant(2, Codec[Data])
    .build
```

Encoding writes a `u32` varint discriminant followed by the selected variant payload. Decoding reads the discriminant and dispatches to the registered codec.

Duplicate discriminants fail when `.build` is called. If more than one registered runtime class matches a value during encoding, encoding fails with `PostinoError.AmbiguousVariant`.

## Supported Types

The core module includes bidirectional codecs for:

- `Unit`
- `Boolean`
- `Byte`
- `Short`
- `Int`
- `Long`
- `Float`
- `Double`
- `String`
- `Array[Byte]`
- unsigned wrappers `U16`, `U32`, and `U64`
- `Option[A]`
- `List[A]`
- `Vector[A]`
- `Array[A]`
- case class products via `derives Codec`
- explicit ADTs and enums via `Postino.sum`

Scala has no native unsigned integer types matching Rust `u16`, `u32`, and `u64`, so Postino exposes wrappers:

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

Rust `u8` values use the raw `Byte` codec. Values above 127 appear as negative Scala `Byte` values; mask with `byte & 0xff` when you need the unsigned integer view.

## Decode Limits

Collection decoders enforce configurable safety limits so small inputs cannot request unbounded decode work. The defaults allow at most 1,000,000 elements in one collection and 1,000,000 collection elements across a whole top-level decode.

```scala
val decoded =
  Postino.decode[List[Int]](
    bytes,
    DecodeOptions(maxCollectionLength = 1024, maxCollectionElements = 4096)
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

## Limitations

Postino v0 does not support:

- COBS
- CRC
- streaming flavors
- Serde attributes
- schema evolution
- `u128` or `i128`
- Rust maps
- Rust `char`
- automatic sum derivation
- Circe integration

## License

Postino is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
