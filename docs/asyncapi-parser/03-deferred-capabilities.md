# Spec 03: Deferred AsyncAPI Parser Capabilities

Status: **LATER, MAYBE — non-normative for current implementation**

Related specifications: [Spec 01 — Traits Processor](01-traits-processor-now.md), [Spec 02 — ZenWave Navigation](02-zenwave-navigation-now.md), and [Spec 04 — Validation Reference](04-validation-reference-only.md).

This document preserves designs that may become useful, but none of them belongs to the definition of done for Specs 01 or 02. Do not create placeholder public APIs merely to appear future-proof: unused extension points are themselves permanent maintenance surface.

Implement a section only after a concrete consumer, compatibility requirement, and maintenance owner exist.

## Decision policy

Before promoting any capability into normative work, record:

1. The current consumer and use case.
2. Why existing `ParsedDocument`, raw maps, or application code is insufficient.
3. Expected JVM/JS/platform support.
4. New dependencies and supply-chain impact.
5. Public compatibility obligations.
6. Required security limits and conformance fixtures.
7. Whether the feature belongs in the generic core or AsyncAPI module.

Promote each capability through a separate implementation spec and pull request. Do not implement this entire document as one feature batch.

## Capability A: Deterministic anonymous identifiers

### Motivation

Navigation from Spec 02 can use declared IDs and map keys. Some future generators or caches may require a non-null identifier for every anonymous channel, operation, message, or schema.

### Proposed model

```kotlin
enum class AsyncApiIdOrigin {
    DECLARED,
    MAP_KEY,
    GENERATED,
}

data class AsyncApiIdentity(
    val id: String,
    val declaredId: String?,
    val origin: AsyncApiIdOrigin,
    val sourceUri: String,
    val pointer: String,
)
```

Precedence:

1. Specification-defined declared identifier.
2. Stable declaration map key.
3. Deterministic generated identifier derived from canonical source URI, declaration kind, and escaped JSON Pointer.

Requirements:

- Deterministic across JVM and JS.
- Independent of traversal order.
- Collision-safe across external documents.
- Same declaration identity for all resolved usages.
- Optional usage pointer remains distinct from declaration identity.
- Never inject generated IDs into source or effective JSON.
- Never use counters such as `anonymous-message-3`.

### Promotion trigger

Implement only when a generator, cache, or public API cannot operate with nullable IDs/map keys and has tests demonstrating the requirement.

## Capability B: Multi-format schema inspection

### Existing behavior is usually sufficient

OpenAPI Schema Objects and Avro schemas represented as JSON/YAML are already structurally loadable by the generic parser. Specs 01/02 should preserve their raw content and `schemaFormat` without normalization. Avro named types are not JSON Reference `$ref` values and must not be reinterpreted.

RAML remains out of scope.

### Deferred content model

If a consumer needs format-aware inspection, introduce content that does not assume maps:

```kotlin
sealed interface AsyncApiSchemaContent {
    data class Json(val value: Any?) : AsyncApiSchemaContent
    data class Text(val value: String) : AsyncApiSchemaContent
    data class Binary(val value: ByteArray) : AsyncApiSchemaContent
}

data class AsyncApiSchemaEntry(
    val id: String?,
    val schemaFormat: String?,
    val content: AsyncApiSchemaContent,
    val pointer: String,
    val sourceUri: String,
)
```

Recognize version layout differences: AsyncAPI v2 places `schemaFormat` beside `payload`; v3 may wrap `schemaFormat` and `schema` inside a Multi Format Schema Object.

### Optional handler SPI

Only introduce an SPI with a real handler:

```kotlin
interface AsyncApiSchemaFormatHandler {
    fun supports(schemaFormat: String): Boolean

    suspend fun inspect(
        content: AsyncApiSchemaContent,
        baseUri: String,
        loader: ResourceLoader,
    ): AsyncApiSchemaInspection
}
```

Handlers are explicitly registered trusted application code. Preserve raw content and provenance even when inspection succeeds.

## Capability C: Protobuf integration

### Boundary

Protocol Buffer schemas are text, not JSON. Generic JSON `$ref` processing must never cast `.proto` text to a map or treat `import` as JSON Reference.

Without a handler the parser may preserve:

- raw `.proto` text;
- declared schema format;
- canonical/base URI;
- source location/reference metadata.

This preservation alone may satisfy future consumers and should be attempted before building a compiler integration.

### Optional handler responsibilities

A concrete Protobuf handler may:

- parse messages, enums, and services;
- resolve `import` statements through the authenticated loader chain;
- generate or consume descriptor sets;
- invoke an application-selected platform compiler.

Do not require `protoc`, a Protobuf JVM runtime, or npm Protobuf package in either base module. Protobuf import resolution remains inside the handler.

### Promotion trigger

Require a concrete ZenWave generator/use case, chosen parsing/compiler implementation, dependency review, import-security policy, and JVM/JS support decision.

## Capability D: Graph-safe serialization

### Motivation

Dereferenced documents may contain cycles and shared identity. Native JSON serialization cannot preserve that graph. A codec may be valuable for build, IDE, or remote caches, but creates a wire-format compatibility and untrusted-decoding obligation.

This capability belongs in `json-schema-ref-parser-kmp`, not the AsyncAPI module.

### Proposed API

```kotlin
interface ParsedDocumentCodec {
    fun encode(document: ParsedDocument): String
    fun decode(serialized: String): ParsedDocument
}
```

The versioned format must preserve:

- cycles and shared object identity;
- source/document locations;
- canonical source URIs;
- original refs and resolved-reference metadata;
- codec version and compatibility metadata.

Use explicit node IDs or documented pointer references. Do not copy undocumented parser-js sentinel strings.

### Decoder security

Reject malformed refs, duplicate node IDs, unknown versions, invalid type tags, and truncated data. Enforce configurable maximum encoded size, node count, collection size, string size, and graph depth. Fuzz or property-test decoding before accepting untrusted cache input.

Round-trip tests must assert reference identity and metadata, not only value equality.

### Promotion trigger

Require an actual cache integration, invalidation policy, wire-format owner, and compatibility/versioning plan.

## Capability E: Expanded per-node provenance

### Existing baseline

Specs 01/02 should expose existing core source locations and original-ref tracking. That is sufficient until a consumer needs merged-value attribution or per-node circular/shared queries.

### Proposed model

```kotlin
data class NodeProvenance(
    val sourceUri: String,
    val sourcePointer: String,
    val sourceLocation: SourceLocation?,
    val originalRef: String? = null,
    val referenceChain: List<String> = emptyList(),
    val circular: Boolean = false,
    val shared: Boolean = false,
)
```

AsyncAPI effective values may additionally record origin kind (`TARGET`, `TRAIT`, `GENERATED`) and contributing trait pointer. Never fabricate a location for a merged/generated value.

Keep generic reference/reference-chain/circular metadata in core. Keep trait-origin metadata in the AsyncAPI module.

### Promotion trigger

Require a diagnostics, IDE, generator, or cache use case that consumes the added metadata. Avoid retaining large reference chains by default without measuring memory impact.

## Estimated maintenance impact

These are planning ranges, not commitments:

| Capability | Production code | Tests | Ongoing burden |
| --- | ---: | ---: | --- |
| Anonymous IDs | 100–250 lines | 150–350 lines | Low, but public identity becomes permanent |
| Schema-format SPI | 200–500 | 300–700 | Medium |
| Concrete Protobuf support | 1,000–3,000 | 1,000–2,500 | High: imports, compiler/runtime, platform differences |
| Graph codec | 700–1,500 | 1,000–2,000 | Medium-high: wire compatibility and decoder security |
| Expanded provenance | 300–800 | 400–1,000 | Medium: memory and graph semantics |

## Documentation rule

Keep this document as a design reference. Do not add these capabilities to root README feature lists until implemented and tested. When promoted, create a normative spec, update README after API stabilization, and record compatibility/security decisions.

## Explicit exclusions

- Full AsyncAPI conformance validation belongs only to Spec 04.
- None of these capabilities blocks completion of Specs 01 or 02.
- Do not publish empty SPIs or placeholder types solely for hypothetical compatibility.
