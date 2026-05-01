# Codec derivation review (postino vs borer)

## How postino derives codecs

**Products** (`Codec.derived` / `derives Codec`):
- `Codec.scala:31-36` matches on `Mirror.Of[A]`. Products go to `ProductCodecs.derivedProduct`; sums hit `compiletime.error(...)` pointing at `Postino.sum[A].variant(...).build`.
- `summonCodecs[Fields <: Tuple]` (`Codec.scala:301-305`) recursively summons a `Codec[field]` per `MirroredElemTypes` entry, returning a **`List[Codec[Any]]`** (everything cast to `Codec[Any]`).
- Runtime encode (`Codec.scala:307-317`) calls `product.productElement(index)` + `fieldCodecs(index).encode(...)` in a `while` loop with an early-return on `Left`.
- Runtime decode (`Codec.scala:319-330`) walks `var remaining = fieldCodecs` as a `List`, accumulates a `List[Any]`, then `mirror.fromProduct(Tuple.fromArray(values.toArray))`.

**Sums** (`SumCodecBuilder`):
- Manual fluent builder. Each `.variant(disc, Codec[B])` appends `(discriminant, codec, ClassTag[B])` to a `Vector`.
- Encode: linear `variants.find(_.matches(value))` using `classTag.runtimeClass.isInstance` (`SumCodecBuilder.scala:31-32`, `:57-58`).
- Decode: linear `variants.find(_.discriminant == discriminant.toLong)` (`SumCodecBuilder.scala:44`).
- Duplicate-discriminant check is at runtime in `.build` (throws `IllegalArgumentException`).

## How borer differs

Borer is fully macro-based (`scala.quoted.*`), and that gives it qualitatively different output:

1. **No runtime field walk.** Borer's `deriveForCaseClass` (`ArrayBasedCodecs.scala`) generates inlined code per case class — the encoder body becomes `w.writeArrayOpen(n); enc1.write(w, x.f1); enc2.write(w, x.f2); ... ; w.writeArrayClose()`. Decoders likewise generate `companion.apply(dec1.read(r), dec2.read(r), ...)`. There's no `productElement`, no `List[Codec[Any]]`, no boxing of each field.
2. **Sum types are derivable.** `deriveAllCodecs[T]` recursively walks the sealed hierarchy at macro expansion, generates a sub-codec per concrete subtype (skipping any already in implicit scope), and emits a dispatch on the type id. `@key("Foo")` / `@key(42)` annotations let users override the default tag (the class name).
3. **Two style flavors.** Borer ships both `ArrayBasedCodecs` (positional, like postino) and `MapBasedCodecs` (with field names, allows reordering, missing fields, defaults). The `derives Codec` clause picks whichever was imported.
4. **Compile-time errors are precise.** Missing field codec → "Could not find given Encoder[T] for field `name` of case class `C`" with full type path; postino's `summonInline` will surface a generic Scala 3 inline error instead.
5. **Recursion handling.** Borer wraps each summoned codec in `.recursive` so self-referential ADTs derive. Postino's mirror approach with `summonInline` is fine for linear cases but won't terminate for `case class Tree(children: List[Tree])` unless the user breaks the cycle with an explicit `given`.

## What postino could borrow (in order of cost/value)

**High value, low risk:**

- **Replace `List[Codec[Any]]` with `IArray[Codec[Any]]`.** `Codec.scala:307-317` does `fieldCodecs(index)` inside a while loop — that's `List#apply`, which is O(index). A 5-field record costs 1+2+3+4+5 traversals to encode, repeated for every value. `IArray` indexing is O(1).
- **Fixed-size `Array[Any]` for decode.** `decodeFields` builds a `List`, calls `.toArray`, then `Tuple.fromArray(...)` — three allocations per record. Allocate `new Array[Any](fieldCodecs.length)` once and write into it; `Tuple.fromArray` accepts it.
- **Compile-time check for missing field codecs.** The current `summonInline[Codec[field]]` error message is opaque. A small macro-summoner that wraps it with `compiletime.error(s"missing Codec for field of ${...}")` would help users.

**Worth considering:**

- **Mirror-aware sum builder for exhaustiveness.** Keep the explicit-discriminant policy (which is the right call given postcard semantics depend on Rust declaration order — see `CLAUDE.md`), but add a `Postino.sum[A].variants((0, summon[Codec[Ping]]), (1, summon[Codec[Pong]]), ...).build` form that uses `Mirror.SumOf[A]#MirroredElemTypes` to verify at compile time that every subtype was registered exactly once. This catches "forgot to add a variant" without inferring discriminants.
- **A `derives Codec` story for `enum`/sum that fails *with the variant list pre-filled*.** Right now the error is just text. A macro could emit a stub like `// Postino.sum[Message].variant(0, Codec[Ping]).variant(1, Codec[Pong]).build` so the user can copy-paste and just adjust discriminants.

**Probably not worth it for v0:**

- Full quote-macro derivation à la borer. The `inline` + `Mirror` approach is much smaller, has no macro-debugging overhead, and matches the "core stays dependency-light" stance in `CLAUDE.md`. The runtime overhead from `productElement` + boxing is real but small relative to varint encoding work, and only matters once postino is on a hot path.
- Map-based encoding. Postcard is positional by design — there's no map-style alternative on the Rust side to be compatible with.

## Summary

Postino's derivation is intentionally a thin shim over Scala 3 mirrors, and the explicit-sum policy is well-justified by the wire-format constraint. The two genuinely worthwhile borrowings from borer are tactical: switch the per-field collection from `List` to `IArray`, and write decoded fields directly into a pre-sized array. Everything else (full macros, auto-derived sums, map-based codecs) would either undermine the small-core goal or break wire compatibility.
