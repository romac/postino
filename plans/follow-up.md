# Postino Roadmap — Phased Plan

Each phase ends with: Rust fixtures in `interop/rust-fixtures/src/main.rs`, regenerated `interop/fixtures/postcard-1.1.3.hex`, matching Scala tests, an updated `docs/compatibility.md`, and a README limitations-list edit.

---

## Phase 1 — Scalar wire coverage

**Goal:** close the obvious "missing primitive" gaps without touching the core architecture.

1. **`char`** — Rust encodes as a `u32` varint of the Unicode scalar value. Add a `Codec[Char]` in `PrimitiveCodecs`, reject surrogates on decode (`PostinoError.InvalidChar`), reuse the existing `u32` varint path.
2. **`u128` / `i128`** — extend `Varint.scala` with 19-byte capped LEB128; add `U128` value class in `Unsigned.scala` mirroring `U64` (`fromBigInt`, `unsafeFromBigInt`, `MaxValue`); zigzag for `i128` in `PrimitiveCodecs`. Decide Scala carrier: `BigInt` for both (no native 128-bit type).
3. **Rust maps** — length-prefixed `(K, V)` sequences. Provide `Codec[Map[K, V]]` and `Codec[SortedMap[K, V]]`. Document that postcard preserves iteration order of the encoder side; `BTreeMap` on the Rust side is sorted, `HashMap` is not — surface this in `docs/compatibility.md`.

**Exit:** every Rust scalar/collection Postino *could* sensibly support is covered; only the framing flavors and ergonomic items remain.

---

## Phase 2 — Sum derivation ergonomics

**Goal:** make the common case (Scala declaration order matches Rust declaration order) automatic, without giving up the explicit override.

1. Replace the current `Codec.derived` compile error for `Mirror.SumOf` with an opt-in derivation that:
   - walks `Mirror.SumOf` children in declaration order,
   - assigns discriminants `0..n-1`,
   - requires each child to have its own `Codec` (derived or given).
2. Keep `Postino.sum[A].variant(...).build` as the explicit path for `#[repr(u8)]`, serde tags, or non-contiguous discriminants. Document when to use which.
3. Add a fixture pair: a Rust enum with three variants on the Rust side, decoded by a Scala sealed trait using `derives Codec`.

**Exit:** `derives Codec` works for sealed traits / enums in the simple case; `AGENTS.md` updated to reflect the new default.

---

## Phase 3 — Framing flavors (COBS, then CRC)

**Goal:** support postcard's framing add-ons on top of the existing `Writer`/`Reader`.

1. **COBS** — implement `postcard_cobs` encode/decode as a wrapper around the current top-level `encode`/`decode`. New entry points (`Postino.encodeCobs`, `Postino.decodeCobs`) rather than a flag, to keep the `Either` shape clean. New errors: `PostinoError.CobsFraming`, `PostinoError.CobsZeroInPayload`.
2. **CRC** — postcard's CRC flavor wraps an inner encoded payload with a trailing CRC. Layer over the COBS code: `Postino.encodeCrc(crc, value)` and matching decode. Pluggable CRC algorithm (start with `crc32fast`-equivalent; allow user-supplied `Crc` typeclass).
3. Add interop fixtures that round-trip a struct through `postcard::to_stdvec_cobs` and the CRC flavor.

**Exit:** Postino can talk to a real serial/embedded link that uses postcard COBS/CRC framing.

---

## Phase 4 — Ecosystem: Circe

**Goal:** the JSON analogue of `postinoScodec`. Independent of all other phases — can land in parallel with anything.

1. New Mill submodule `postinoCirce` (sibling to `postinoScodec`), depending on core + `io.circe::circe-core`.
2. Provide `PostinoCirce.toCirce[A: Codec]: io.circe.Codec[A]` — but be honest in the README that postcard's positional/non-self-describing format does *not* map naturally to JSON, so the adapter is schema-driven: it walks the same product/sum structure used by `Codec.derived` and emits a JSON object with field names from the Scala mirror.
3. Decide and document the sum encoding (tagged object `{ "tag": "...", "value": ... }` is the safe default).

**Exit:** a Postino-defined schema can be serialized as JSON for debugging/logging without writing a second codec.

---

## Phase 5 — Streaming I/O

**Goal:** decouple encode/decode from `Array[Byte]` so postcard streams (e.g. over a socket or `InputStream`) work without buffering the whole payload.

1. Introduce `Sink` / `Source` traits behind `Writer`/`Reader`. Existing array-backed implementations become one concrete pair; add `OutputStream`/`InputStream` adapters.
2. Audit every codec for "peek length, then read" patterns that assume a finite buffer — collection decoders, `String`, `Array[Byte]`, sums. These need either bounded reads or to stay array-only.
3. Re-evaluate the `Either[PostinoError, A]` return shape for streaming decode: probably stays as-is, but I/O errors from the underlying stream need a new `PostinoError.Io` case.
4. This is the only phase that touches `docs/compatibility.md` architectural assumptions — review the spec before starting.

**Exit:** a postcard message can be decoded from an `InputStream` without first reading the full payload into memory.

---

## Phase 6 — Ecosystem: FS2

**Goal:** provide idiomatic effectful stream integration without pulling Cats Effect / FS2 into core.

1. New Mill submodule `postinoFs2` (sibling to `postinoScodec` and `postinoCirce`), depending on core + `co.fs2::fs2-core`.
2. Keep raw postcard stream APIs secondary: unframed postcard messages are not self-delimiting, so the useful surface should prioritize framed byte streams.
3. Provide COBS-oriented pipes first, e.g. `Stream[F, A] => Stream[F, Byte]` and `Stream[F, Byte] => Stream[F, A]`, then layer CRC variants if the core framing APIs expose the right hooks.
4. Use Cats Effect error/resource conventions in the adapter; do not change the core `Either[PostinoError, A]` model to fit FS2.

**Exit:** Postino schemas can be encoded/decoded over FS2 byte streams in a framed, resource-safe way.

---

## Phase 7 — Serde attributes (opportunistic)

**Goal:** *not* a sweep — pick attributes off the list only when a real schema needs them. Likely candidates, in rough order of payback:

1. `#[serde(skip)]` — drop a field on encode, supply a default on decode. Pure Scala-side concern.
2. `#[serde(rename)]` — irrelevant on the wire for postcard (no field names), but matters for Phase 4's Circe adapter.
3. `#[serde(with = "...")]` — express in Scala by providing a different `Codec[A]` at the call site; no library work needed once we document the pattern.
4. Tag styles (`#[serde(tag = "...")]`, `untagged`, etc.) — likely Circe-adapter concerns, not postcard wire concerns.

**Exit:** no fixed exit. This phase stays open and absorbs feature requests rather than being shipped as a block.

---

## Dropped / reworded

- **Schema evolution** — postcard itself doesn't support it. Reword in the README from "not supported" to "out of scope (postcard is not self-describing)" and remove from the roadmap.

---

## Suggested calendar shape

- **Now:** Phase 1 (1–2 weeks of focused work, mostly fixtures).
- **Next:** Phase 2 — small but high-ergonomics payoff.
- **Then pick one of:** Phase 3 (if users want serial framing) or Phase 4 (if users want debuggability). Both are self-contained.
- **Defer:** Phase 5 until a concrete streaming user exists.
- **After streaming:** Phase 6 if users want FS2 byte-stream integration.
- **Open-ended:** Phase 7.
