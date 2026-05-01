# Postino v0 Compatibility Boundary

Postino v0 targets the Rust `postcard` 1.x non-COBS wire format produced by
`postcard::to_stdvec`.

Supported:

- booleans as one byte (`0` or `1`)
- `i8` / `u8` as one raw byte
- `i16`, `i32`, and `i64` as zigzag-encoded unsigned varints
- `u16`, `u32`, `u64`, and `usize`-length prefixes as unsigned varints
- `f32` and `f64` as little-endian IEEE-754 bit patterns
- UTF-8 strings as `varint(usize)` byte length followed by bytes
- byte arrays as `varint(usize)` byte length followed by raw bytes
- options as a one-byte tag (`0` for `None`, `1` for `Some`) followed by the value
- sequences as `varint(usize)` length followed by each element
- case classes/products as constructor fields in order, with no field names and no length prefix
- explicit ADTs/enums as a `u32` varint discriminant followed by the selected payload

Deferred:

- Serde attributes
- schema evolution
- COBS
- CRC
- streaming flavors
- Circe integration
