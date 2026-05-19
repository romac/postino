# Codec derivation review (postino vs borer)

## How postino derives codecs

**Products** (`Codec.derived` / `derives Codec`):
- `Codec.scala` matches on `Mirror.Of[A]`. Products go to `ProductCodecs.derivedProduct`.
- `summonCodecs[Fields <: Tuple]` recursively summons a `Codec[field]` per `MirroredElemTypes` entry, then materializes the result as an **`IArray[Codec[Any]]`** for indexed runtime access.
- Runtime encode calls `product.productElement(index)` + `fieldCodecs(index).encode(...)` in a `while` loop with an early-return on `Left`.
- Runtime decode writes each decoded value into a pre-sized `Array[Any]`, then calls `mirror.fromProduct(Tuple.fromArray(values))`.

**Derived sums** (`Codec.derived` / `derives Codec`):
- `Mirror.SumOf` children are walked in declaration order, and each child must have its own `Codec`.
- Runtime encode uses `mirror.ordinal(value)` as the `u32` discriminant, then delegates to the child codec at that ordinal.
- Runtime decode reads a `u32` discriminant and indexes into the same declaration-order child codec table.
- This path is only for schemas where Scala declaration order exactly matches Rust enum declaration order.

**Sums** (`SumCodecBuilder`):
- Manual fluent builder. Each `.variant(disc, Codec[B])` appends `(discriminant, codec, ClassTag[B])` to a `Vector`.
- Build: rejects duplicate discriminants, then precomputes a runtime-class lookup for encode and a discriminant lookup for decode.
- Encode: first checks exact `value.getClass` registrations. If none exist, it falls back to `classTag.runtimeClass.isInstance`; exactly one match is required. Overlapping fallback matches fail with `PostinoError.AmbiguousVariant`.
- Decode: looks up the decoded `u32` discriminant in the precomputed map.
- Duplicate-discriminant check is at runtime in `.build` (throws `IllegalArgumentException`).

## How borer differs

Borer is fully macro-based (`scala.quoted.*`), and that gives it qualitatively different output:

1. **No runtime field walk.** Borer's `deriveForCaseClass` (`ArrayBasedCodecs.scala`) generates inlined code per case class — the encoder body becomes `w.writeArrayOpen(n); enc1.write(w, x.f1); enc2.write(w, x.f2); ... ; w.writeArrayClose()`. Decoders likewise generate `companion.apply(dec1.read(r), dec2.read(r), ...)`. There's no `productElement`, no `List[Codec[Any]]`, no boxing of each field.
2. **Sum types are derivable.** `deriveAllCodecs[T]` recursively walks the sealed hierarchy at macro expansion, generates a sub-codec per concrete subtype (skipping any already in implicit scope), and emits a dispatch on the type id. `@key("Foo")` / `@key(42)` annotations let users override the default tag (the class name).
3. **Two style flavors.** Borer ships both `ArrayBasedCodecs` (positional, like postino) and `MapBasedCodecs` (with field names, allows reordering, missing fields, defaults). The `derives Codec` clause picks whichever was imported.
4. **Compile-time errors are precise.** Missing field codec → "Could not find given Encoder[T] for field `name` of case class `C`" with full type path. Postino uses a small macro summoner for a targeted missing-`Codec` error, but it does not include the field name.
5. **Recursion handling.** Borer wraps each summoned codec in `.recursive` so self-referential ADTs derive. Postino's mirror approach with `summonInline` is fine for linear cases but won't terminate for `case class Tree(children: List[Tree])` unless the user breaks the cycle with an explicit `given`.

## What postino could still borrow

**Already borrowed:**

- `IArray[Codec[Any]]` for O(1) runtime field-codec indexing.
- Fixed-size `Array[Any]` decode accumulation before `Tuple.fromArray`.
- Small macro summoners for clearer missing product-field and sum-variant codec errors.

**Worth considering:**

- **Mirror-aware explicit sum builder for exhaustiveness.** Keep the explicit-discriminant path for custom discriminants, but add a `Postino.sum[A].variants((0, summon[Codec[Ping]]), (1, summon[Codec[Pong]]), ...).build` form that uses `Mirror.SumOf[A]#MirroredElemTypes` to verify at compile time that every subtype was registered exactly once. This catches "forgot to add a variant" without inferring discriminants.

**Probably not worth it for v0:**

- Full quote-macro derivation à la borer. The `inline` + `Mirror` approach is much smaller, has no macro-debugging overhead, and matches the "core stays dependency-light" stance in `CLAUDE.md`. The runtime overhead from `productElement` + boxing is real but small relative to varint encoding work, and only matters once postino is on a hot path.
- Map-based encoding. Postcard is positional by design — there's no map-style alternative on the Rust side to be compatible with.

## Summary

Postino's derivation is intentionally a thin shim over Scala 3 mirrors. Product and declaration-order sum derivation share the same small-runtime-codec-table approach: child codecs are stored in an `IArray`, and runtime work is direct indexed dispatch. The remaining useful improvements are around diagnostics and explicit-sum ergonomics; full macros and map-based encoding would either undermine the small-core goal or break wire compatibility.
