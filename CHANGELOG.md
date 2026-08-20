# Changelog

All notable changes to Postino are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Postino uses [Early SemVer](https://scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html) while its API is below version 1.0.

## [Unreleased]

### Added

- Add postcard-compatible fixed arrays, tuples, Rust `Result` through Scala `Either`, and ordered sets through `SortedSet`.
- Derive declaration-order codecs directly for ordinary Scala 3 enums.

## [0.1.0-M2] - 2026-08-20

### Added

- Add postcard-compatible `Char`, signed `i128` through `BigInt`, `U128`, `Map`, and `SortedMap` codecs.
- Derive declaration-order sum codecs for sealed trait hierarchies.
- Add postcard COBS and trailing-CRC framing, including CRC-32/ISO-HDLC and CRC32C coverage.
- Add finite streaming encode and decode through `Sink`, `Source`, `OutputStream`, and `InputStream`.
- Add the `postino-circe` schema-driven JSON adapter.
- Add the `postino-fs2` COBS-framed stream adapter.
- Add configurable byte-length limits and bounded FS2 frame buffering.
- Add a language-neutral postcard 1.1.3 compatibility corpus with 70 raw and framed vectors, including 33 cases imported from the pinned upstream postcard source.

### Changed

- Make `Encoder` and `Decoder` invariant to prevent conflicting subtype codecs during given search. This can require changes in code that relied on the M1 variance declarations.
- Restrict `Writer.toByteArray` to Postino internals. Custom encoders should write through the supplied `Writer`; top-level callers should use `Postino.encode` or an `encodeTo` overload.
- Add `maxByteLength` to `DecodeOptions`. This changes the case class constructor and product arity.
- Change `PostinoError.CollectionElementLimitExceeded.requested` from `Int` to `Long` so map key/value accounting cannot overflow.
- Count map keys and values separately against the total collection-element budget.
- Define raw streaming decode as a finite-source operation that rejects trailing bytes.

## [0.1.0-M1] - 2026-05-19

### Added

- Add core postcard encode and decode APIs with structured errors and trailing-byte rejection.
- Add primitive, string, byte-array, option, sequence, product, explicit-sum, and unsigned integer codecs.
- Add configurable collection decode limits.
- Add Rust postcard 1.1.3 fixture compatibility tests.
- Add the `postino-scodec` adapter.

[Unreleased]: https://github.com/romac/postino/compare/0.1.0-M2...HEAD
[0.1.0-M2]: https://github.com/romac/postino/compare/0.1.0-M1...0.1.0-M2
[0.1.0-M1]: https://github.com/romac/postino/releases/tag/0.1.0-M1
