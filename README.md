# Postino

Postino is a Scala 3 implementation of the Rust `postcard` 1.x non-COBS wire
format. The v0 goal is byte-for-byte compatibility with `postcard::to_stdvec`
for a small, explicit subset of the format.

The supported boundary is documented in [docs/compatibility.md](docs/compatibility.md).
Treat that file as the compatibility spec.

## Status

Postino is intentionally small:

- no runtime dependency in the core module
- no schema registry
- no streaming API
- no COBS or CRC support
- no Serde attribute support
- optional scodec adapter in a separate Mill module

Mill publishing metadata is configured for local Maven/Ivy publishing and
Sonatype Central release tasks. The default development coordinates are
`me.romac::postino:0.1.0-SNAPSHOT` and
`me.romac::postino-scodec:0.1.0-SNAPSHOT`; set `POSTINO_GROUP_ID` and
`POSTINO_VERSION` when publishing under different coordinates.

## Modules

The build uses Mill.

- Core library: sources in [src/postino](src/postino), tests in
  [test/src/postino](test/src/postino)
- Optional scodec adapter: sources in [postino-scodec/src](postino-scodec/src),
  tests in [postino-scodec/test/src](postino-scodec/test/src)
- Rust fixture generator: [interop/rust-fixtures](interop/rust-fixtures)
- Checked-in Rust fixture bytes:
  [interop/fixtures/postcard-1.1.3.hex](interop/fixtures/postcard-1.1.3.hex)

## Build And Test

Always use the checked-in Mill launcher and pass `--no-server`.

```text
./mill --no-server compile
./mill --no-server test
./mill --no-server postinoScodec.test
./mill --no-server __.test
./mill --no-server fmt
```

Normal Scala tests read the checked-in Rust fixture file and stay offline.

To regenerate Rust `postcard` fixture bytes and compare them with the committed
fixture file:

```text
./mill --no-server interopTest
```

That command requires a working Rust toolchain and Cargo.

## Publishing

The core and scodec modules both expose Mill publish tasks.

Default local coordinates:

```scala
ivy"me.romac::postino:0.1.0-SNAPSHOT"
ivy"me.romac::postino-scodec:0.1.0-SNAPSHOT"
```

Publish both modules to a local Maven repository path:

```text
./mill --no-server publishM2Local --m2RepoPath out/local-m2
./mill --no-server postinoScodec.publishM2Local --m2RepoPath out/local-m2
```

Publish with release coordinates:

```text
POSTINO_GROUP_ID=me.romac POSTINO_VERSION=0.1.0 \
  ./mill --no-server publishM2Local --m2RepoPath out/local-m2

POSTINO_GROUP_ID=me.romac POSTINO_VERSION=0.1.0 \
  ./mill --no-server postinoScodec.publishM2Local --m2RepoPath out/local-m2
```

`publishLocal` publishes to the local Ivy repository. `publishSonatypeCentral`
is available on each module for a real Central release once credentials, PGP
signing, and project license metadata are in place.

## Core API

The public entry points are `Postino.encode` and `Postino.decode`.

```scala
import postino.*

val encoded: Either[PostinoError, Array[Byte]] =
  Postino.encode(300)

val decoded: Either[PostinoError, Int] =
  Postino.decode[Int](Array(0xd8.toByte, 0x04.toByte))
```

Encoding and decoding return `Either[PostinoError, A]`. Top-level decode rejects
trailing bytes:

```scala
Postino.decode[Boolean](Array(0x01.toByte, 0x00.toByte))
// Left(PostinoError.TrailingBytes(1))
```

## Codecs

Postino uses three type classes:

```scala
trait Encoder[-A]:
  def encode(value: A, out: Writer): Either[PostinoError, Unit]

trait Decoder[+A]:
  def decode(in: Reader): Either[PostinoError, A]

trait Codec[A] extends Encoder[A] with Decoder[A]
```

Most users should provide or summon `Codec[A]`. `Encoder[A]` and `Decoder[A]`
are accepted by `Postino.encode` and `Postino.decode` for top-level values and
can be useful in hand-written codecs, but the built-in collection instances and
`derives Codec` are bidirectional. Nested element types and derived product
fields must provide a full `Codec[A]`.

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

Scala has no native unsigned integer types matching Rust `u16`, `u32`, and
`u64`, so Postino exposes wrappers:

```scala
val port: Either[PostinoError, U16] =
  U16.fromInt(8080)

val max: U64 =
  U64.unsafeFromBigInt(U64.MaxValue)
```

Use the safe constructors when decoding external input into your own model.
The `unsafeFrom...` helpers throw `IllegalArgumentException` and are mainly for
tests, examples, and constants.

Rust `u8` values use the raw `Byte` codec. Values above 127 appear as negative
Scala `Byte` values; mask with `byte & 0xff` when you need the unsigned integer
view.

## Product Derivation

Case classes derive positional codecs with Scala 3 mirrors.

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

## Explicit Sums And Enums

Sums are not derived automatically. The Rust enum discriminant is part of the
wire schema, so Postino requires each variant to be registered explicitly.

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

Encoding writes a `u32` varint discriminant followed by the selected variant
payload. Decoding reads that discriminant and dispatches to the registered
variant codec.

Duplicate discriminants fail when `.build` is called. If more than one
registered runtime class matches a value during encoding, encoding fails with
`PostinoError.AmbiguousVariant` instead of silently choosing the first match.
Keep registrations concrete and non-overlapping.

## Wire Format Notes

Postino v0 matches the following `postcard` conventions:

- booleans are one byte: `0` for false, `1` for true
- `i8` and `u8` are one raw byte
- signed integers use zigzag encoding, then unsigned varint encoding
- unsigned integers use unsigned varints
- `f32` and `f64` use little-endian IEEE-754 bit patterns
- strings encode a `usize` varint byte length followed by UTF-8 bytes
- byte arrays encode a `usize` varint byte length followed by raw bytes
- `Option[A]` uses a one-byte tag: `0` for `None`, `1` for `Some`
- sequences encode a `usize` varint length followed by elements
- products are positional
- explicit sums use a `u32` varint discriminant

The JVM cannot allocate arrays larger than `Int.MaxValue`, so decoded length
prefixes larger than that fail with `PostinoError.LengthTooLarge`.

## Errors

`PostinoError` is a sealed error model. Current errors include:

- `UnexpectedEnd`
- `VarintTooLong`
- `VarintOverflow`
- `LengthTooLarge`
- `InvalidBoolean`
- `InvalidOptionTag`
- `InvalidUtf8`
- `TrailingBytes`
- `InvalidUnsignedValue`
- `ProductConstructionFailed`
- `UnknownVariant`
- `UnmatchedVariant`
- `AmbiguousVariant`

Use `error.message` for a concise human-readable string. Pattern match on the
case type when program behavior depends on the error.

## Scodec Adapter

The optional scodec module adapts a `postino.Codec[A]` to an
`scodec.Codec[A]`.

```scala
import postino.*
import postino.scodec.PostinoScodec

val codec: scodec.Codec[Int] =
  PostinoScodec.toScodec[Int]
```

The adapter:

- reports `SizeBound.unknown`
- requires byte-aligned input
- returns the unconsumed byte-aligned remainder from decode
- maps `PostinoError.message` into `scodec.Err`

## Compatibility Fixtures

Rust compatibility is checked through a fixture generator under
[interop/rust-fixtures](interop/rust-fixtures). It serializes representative
Rust values with `postcard::to_stdvec` and prints hex bytes. The checked-in
fixture file is loaded by Scala tests.

When expanding the supported boundary:

1. Add a Rust fixture in [interop/rust-fixtures/src/main.rs](interop/rust-fixtures/src/main.rs).
2. Run `./mill --no-server interopTest`.
3. Commit the updated [interop/fixtures/postcard-1.1.3.hex](interop/fixtures/postcard-1.1.3.hex).
4. Add Scala tests that encode to and decode from the new fixture.
5. Update [docs/compatibility.md](docs/compatibility.md).

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

Unsupported features should stay explicit. Do not make the core depend on
ecosystem integrations; add integrations as separate modules.

## Development Rules

Before committing changes:

```text
./mill --no-server fmt
./mill --no-server __.test
```

Run `./mill --no-server interopTest` when a change can affect wire
compatibility or fixture coverage.
