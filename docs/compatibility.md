# Postino v0 Compatibility Boundary

Postino v0 targets the Rust `postcard` 1.x non-COBS wire format produced by
`postcard::to_stdvec`.

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
- ADTs/enums as a `u32` varint discriminant followed by the selected payload

Postcard varints are capped LEB128: `u16`, `u32`, `u64`, and `u128` use at most
3, 5, 10, and 19 bytes respectively. The final allowed byte must terminate the varint,
and payload bits beyond the target width are rejected.

Postcard maps preserve the encoder-side iteration order on the wire. Rust
`BTreeMap` encodes in key order; Rust `HashMap` does not provide a stable wire
order. Postino mirrors this: `Map[K, V]` encodes using the map value's iterator,
while `SortedMap[K, V]` encodes in its `Ordering[K]` order.

Derived sum codecs assign enum discriminants in Scala declaration order (`0..n-1`).
Use `Postino.sum[A].variant(...).build` when the Rust enum uses custom, sparse, or
non-declaration-order discriminants.

Deferred:

- Serde attributes
- schema evolution
- COBS
- CRC
- streaming flavors
- Circe integration

## Verification

Normal Scala tests load checked-in Rust postcard fixture bytes from
`interop/fixtures/postcard-1.1.3.hex`, so `./mill --no-server test` stays fast
and offline.

Run `./mill --no-server interopTest` to regenerate the fixture stream with the
Rust `postcard` crate and compare it against the checked-in fixture file.

Decoded Scala collection lengths are subject to `DecodeOptions` safety limits.
These limits do not change encoded wire bytes; callers can raise or lower them
for their trust boundary.
