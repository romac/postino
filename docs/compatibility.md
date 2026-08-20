# Postino v0 Compatibility Boundary

Postino v0 targets the Rust `postcard` 1.x wire format produced by
`postcard::to_stdvec`, plus the COBS and CRC framing flavors.

Supported:

- booleans as one byte (`0` or `1`)
- `char` as `varint(usize)` UTF-8 byte length followed by the scalar's UTF-8
  bytes. Scala `Char` can carry BMP scalar values only; surrogate code points
  and supplementary scalar values decode as `PostinoError.InvalidChar`
- `i8` / `u8` as one raw byte
- `i16`, `i32`, `i64`, and `i128` as zigzag-encoded unsigned varints
- `u16`, `u32`, `u64`, `u128`, and `usize`-length prefixes as unsigned varints
- `f32` and `f64` as little-endian IEEE-754 bit patterns
- UTF-8 strings as `varint(usize)` byte length followed by bytes
- byte arrays as `varint(usize)` byte length followed by raw bytes
- options as a one-byte tag (`0` for `None`, `1` for `Some`) followed by the value
- sequences as `varint(usize)` length followed by each element
- maps as `varint(usize)` length followed by key/value pairs
- case classes/products as constructor fields in order, with no field names and no length prefix
- sum schemas as a `u32` varint discriminant followed by the selected payload
- COBS frames produced by `postcard::to_stdvec_cobs`, including the final zero terminator
- CRC frames with a trailing little-endian checksum over the encoded payload
- finite Java `InputStream` / `OutputStream` encode/decode through the same wire format

Postcard varints are capped LEB128: `u16`, `u32`, `u64`, and `u128` use at most
3, 5, 10, and 19 bytes respectively. The final allowed byte must terminate the varint,
and payload bits beyond the target width are rejected.

Postcard maps preserve the encoder-side iteration order on the wire. Rust
`BTreeMap` encodes in key order; Rust `HashMap` does not provide a stable wire
order. Postino mirrors this: `Map[K, V]` encodes using the map value's iterator,
while `SortedMap[K, V]` encodes in its `Ordering[K]` order.

Derived sum codecs assign discriminants in Scala declaration order (`0..n-1`)
for sealed trait hierarchies where every child subtype has its own `Codec`.
Scala 3 `enum` cases are not auto-derived today.
Use `Postino.sum[A].variant(...).build` when the Rust enum uses custom, sparse, or
non-declaration-order discriminants.

COBS decode expects one complete frame and is stricter than a stream parser: the
last byte must be the frame terminator, and any earlier zero byte is rejected as
`PostinoError.CobsZeroInPayload`.

The default CRC implementation is `Crc.Crc32Fast`, matching CRC-32/ISO-HDLC
parameters and postcard's little-endian checksum bytes. Callers can pass another
`Crc` value when their Rust side uses a different CRC flavor.

The optional Circe adapter is outside the postcard wire format. It derives a
schema-driven JSON projection from the same Scala mirror shape: products are JSON
objects with field names, sums use `{ "tag": "...", "value": ... }`, and maps use
ordered key/value entry arrays. Options use JSON `null` for `None`, so nested
options are not lossless in the JSON projection.

The optional FS2 adapter is also outside the postcard wire format. It exposes
COBS-framed encode/decode pipes over `fs2.Stream`, raising `PostinoFs2Exception`
for structured `PostinoError` values. Its COBS decoder buffers one frame at a
time and rejects frames larger than `DecodeOptions.maxByteLength`.

Streaming decode for raw postcard payloads expects a finite source and checks for
trailing bytes at the end. Raw postcard is not self-delimiting on an endless byte
stream, so long-lived links should use an explicit framing layer such as COBS.

Deferred:

- Serde attributes
- schema evolution

## Verification

Normal Scala tests load checked-in Rust postcard vectors from `interop/fixtures/postcard-1.1.3-vectors.tsv`, so `./mill --no-server test` stays fast and offline. The TSV corpus records each vector's schema, value, framing flavor, exact bytes, and source. It includes golden cases imported from the pinned postcard 1.1.3 source as well as Postino-specific coverage.

Run `./mill --no-server interopTest` to regenerate the vector corpus with the Rust `postcard` crate and compare it against the checked-in TSV file.

Decoded Scala collection lengths and byte-blob lengths are subject to
`DecodeOptions` safety limits. These limits do not change encoded wire bytes;
callers can raise or lower them for their trust boundary.
