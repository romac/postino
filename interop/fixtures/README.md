# Postcard Compatibility Vectors

`postcard-1.1.3-vectors.tsv` is a language-neutral corpus of postcard 1.1.3 wire-format vectors. It is UTF-8 text with one vector per TSV row.

The metadata comments pin the corpus format, postcard tag, and upstream commit. The first non-comment line is the column header:

```text
name<TAB>flavor<TAB>schema<TAB>value<TAB>bytes<TAB>source
```

The columns have these meanings:

- `name`: stable unique vector identifier.
- `flavor`: `raw`, `cobs`, `crc32-iso-hdlc`, or `crc32-iscsi`.
- `schema`: the postcard schema in compact Serde-oriented notation. Primitive names match the Serde data model. Containers use `option<T>`, `seq<T>`, `map<K,V>`, `tuple(...)`, `newtype(...)`, `struct`, and `enum`.
- `value`: compact JSON. Maps use ordered `{"key":...,"value":...}` arrays because wire order is significant. Nested options use explicit `{"some":...}` objects when JSON `null` would be ambiguous.
- `bytes`: lowercase, space-separated hexadecimal bytes. The field is empty when the encoded value is empty.
- `source`: a repository-relative generator path or a permanent upstream source URL.

Tabs, carriage returns, and newlines are not permitted inside fields. Consumers must ignore initial lines beginning with `# `, require the exact header, reject duplicate names, and parse the `bytes` field as zero or more hexadecimal octets. Consumers must preserve JSON integers with arbitrary precision instead of coercing them to binary64 values.

Vector names are stable within one corpus-format version. Increment `postcard-test-vectors-version` when columns or their meanings change.

## Provenance

Rows whose names start with `upstream_` reproduce literal golden cases from postcard tag `postcard/v1.1.3` at commit `718aa6a6850456017c19eeff67303c633f875736`. The Rust generator asserts those literal bytes before it emits each row. Other rows cover Postino's documented compatibility boundary and are generated with the same pinned postcard crate.

This corpus is not an official postcard conformance certification. It is a reusable collection of positive wire vectors. Decoder rejection behavior and resource limits remain implementation-specific tests.

## Verification

From the repository root, run:

```text
./mill --no-server interopTest
```

The task runs `interop/rust-fixtures`, compares its complete output with the checked-in TSV file, and fails with paths to the expected and actual files if they differ. Normal Scala tests read the checked-in corpus and do not require Cargo.
