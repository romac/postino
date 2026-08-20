# AGENTS.md

## Project

Postino is a Scala 3 implementation of the Rust [`postcard`](https://docs.rs/postcard) 1.x wire format. v0 prioritizes byte-for-byte wire compatibility with `postcard::to_stdvec` and the supported framing flavors over ecosystem integration. The supported feature subset is defined in `docs/compatibility.md` — treat that file as the spec, and update it when expanding the boundary.

## Build & Common Commands

The build tool is **Mill** (not sbt). A launcher script lives at `./mill`. Always pass `--no-server` to keep runs hermetic.

- Compile: `./mill --no-server compile`
- Run all tests (core + adapters): `./mill --no-server __.test`
- Run only core tests: `./mill --no-server test`
- Run only scodec adapter tests: `./mill --no-server postinoScodec.test`
- Run only Circe adapter tests: `./mill --no-server postinoCirce.test`
- Run only FS2 adapter tests: `./mill --no-server postinoFs2.test`
- Run a single test by name (MUnit): `./mill --no-server test.testOnly -- '*<substring>*'`
- Format Scala sources: `./mill --no-server fmt`
- Regenerate Rust vectors and diff against `interop/fixtures/postcard-1.1.3-vectors.tsv`: `./mill --no-server interopTest` (requires a working `cargo`)

Normal test runs read the checked-in TSV vector corpus, so they stay offline. `interopTest` is the only task that shells out to Cargo.

## Module Layout

Four Mill modules in `build.mill`:

- root (`.`) — the core library. Sources in `src/postino/`, tests in `test/src/postino/`. No third-party deps beyond MUnit (test-only).
- `postinoScodec` — optional adapter that wraps a `postino.Codec[A]` as a `scodec.Codec[A]`. Sources in `postino-scodec/src/`, tests in `postino-scodec/test/src/`. Depends on the core module and `org.scodec::scodec-core`.
- `postinoCirce` — optional adapter that derives an `io.circe.Codec[A]` for Postino schemas. Sources in `postino-circe/src/`, tests in `postino-circe/test/src/`. Depends on the core module and `io.circe::circe-core`.
- `postinoFs2` — optional adapter that exposes COBS-framed FS2 pipes. Sources in `postino-fs2/src/`, tests in `postino-fs2/test/src/`. Depends on the core module and `co.fs2::fs2-core`.

Keep the core dependency-light. New ecosystem integrations belong in their own Mill submodule, not in core.

`interop/rust-fixtures/` is a standalone Cargo project that prints the language-neutral TSV vector corpus to stdout; it is not part of the Scala build and should not gain Scala dependencies. The committed `interop/fixtures/postcard-1.1.3-vectors.tsv` is the source of truth for tests.

## Architecture

The encode/decode pipeline is intentionally small and `Either`-based — there are no exceptions on the happy path and no implicit resource management.

- `Postino.encode` / `Postino.decode` are the raw postcard entry points. Top-level decode rejects trailing bytes (`PostinoError.TrailingBytes`).
- `Postino.encodeTo` / `Postino.decodeFrom` stream raw postcard payloads through `Sink` / `Source` and Java `OutputStream` / `InputStream`. Raw postcard decode expects a finite source so trailing bytes can be checked.
- `Postino.encodeCobs` / `Postino.decodeCobs` wrap the raw payload with postcard COBS framing. Decode expects a full frame with the final zero terminator and rejects earlier zero bytes.
- `Postino.encodeCrc` / `Postino.decodeCrc` wrap the raw payload with postcard's trailing CRC flavor. `Crc.Crc32Fast` is the default CRC-32/ISO-HDLC implementation; pass a `Crc` explicitly for other CRC-32 flavors.
- `Writer` (mutable byte buffer) and `Reader` (cursor over `Array[Byte]`) are the only I/O primitives. Every read/write returns `Either[PostinoError, _]`.
- `Codec[A] = Encoder[A] with Decoder[A]`. `Codec.derived` works for product types via Scala 3 `Mirror.ProductOf` (constructor fields in order, no field names, no length prefix — matches Rust struct layout).
- `Codec.derived` also works for sealed trait hierarchies and Scala 3 enums in the declaration-order case: `Mirror.SumOf` children are assigned `u32` discriminants `0..n-1`. Existing child codecs take precedence, and product-shaped variants are otherwise derived automatically. Use `Postino.sum[A].variant(discriminant, codec).build` for `#[repr]`, serde tags, sparse discriminants, or any schema where Scala declaration order does not exactly match Rust.
- `Postino.exhaustiveSum[A]` is the mirror-aware explicit builder: its type state requires every direct subtype exactly once before `.build` is available. `Codec.defer` lazily initializes direct or mutually recursive codecs.
- The Circe adapter (`PostinoCirce.toCirce`) is schema-driven rather than wire-format-derived: products use mirror field names, sums use `{ "tag": "...", "value": ... }`, and maps use ordered key/value entry arrays.
- The FS2 adapter (`PostinoFs2.encodeCobs` / `PostinoFs2.decodeCobs`) works on COBS-framed streams first; raw postcard payloads remain finite-message APIs because they are not self-delimiting.
- `Varint.scala` implements postcard's LEB128-with-cap varints; signed integers (`i16`/`i32`/`i64`/`i128`) go through zigzag in `PrimitiveCodecs`. Unsigned types are exposed as `U16` / `U32` / `U64` / `U128` value classes in `Unsigned.scala` because Scala has no native unsigned ints — use these whenever modeling Rust `u16`/`u32`/`u64`/`u128`.
- `PostinoError` is a closed sealed trait of structured errors. Add a new case there (with a `message`) rather than threading strings.
- The scodec adapter (`PostinoScodec.toScodec`) requires byte-aligned input and reports `SizeBound.unknown` because postcard is variable-length.

## Conventions

- Scala 3.8.3, new syntax (`-new-syntax`), `-Wunused:imports` is on — keep imports clean.
- Scalafmt config (`.scalafmt.conf`): `align.preset = more`, `maxColumn = 100`. Run `./mill --no-server fmt` before committing.
- Files use `package postino` / `postino.scodec`, indented Scala 3 style, no braces.
- Test vectors live in `interop/fixtures/postcard-1.1.3-vectors.tsv` and are loaded by name (e.g. `assertFixtureRoundTrip("i32_300", 300)`). When you add a new codec feature, add a Rust vector in `interop/rust-fixtures/src/main.rs`, regenerate with `interopTest`, commit the updated TSV file, and reference the vector name in a Scala test. Preserve the corpus format documented in `interop/fixtures/README.md`.
