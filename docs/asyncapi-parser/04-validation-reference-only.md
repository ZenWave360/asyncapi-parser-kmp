# Spec 04: AsyncAPI Validation Reference

Status: **NEVER by default — reference design only**

Related specifications: [Spec 01 — Traits Processor](01-traits-processor-now.md), [Spec 02 — ZenWave Navigation](02-zenwave-navigation-now.md), and [Spec 03 — Deferred Capabilities](03-deferred-capabilities.md).

This document preserves a secure design in case full AsyncAPI validation is ever reconsidered. It is not an implementation request, roadmap commitment, extension point requirement, or definition-of-done item for Specs 01–03.

The project currently needs reference resolution, trait processing, and targeted navigation—not ownership of complete AsyncAPI conformance. Avoiding AsyncAPI npm dependencies does not require reimplementing the entire JavaScript parser or validation ecosystem.

## Why validation is excluded

Full validation creates a substantially larger and continuously changing maintenance surface:

- every supported AsyncAPI version and schema correction;
- rules that cannot be expressed in JSON Schema;
- conformance fixture ownership;
- schema/rules supply-chain review;
- diagnostics compatibility;
- external-reference and multi-format interactions;
- security limits for untrusted input;
- pressure to emulate Spectral/custom-rule behavior.

Validation would likely add more production and test code than the traits processor and navigation combined. Do not implement it without a funded consumer and named long-term owner.

## Reconsideration gate

Before converting this reference into a normative spec, require all of:

1. A concrete application that needs validation from this library rather than a build-time/CLI service.
2. A documented reason structural trait diagnostics are insufficient.
3. An owner for AsyncAPI version and rule updates.
4. A supported-version matrix and deprecation policy.
5. A schema provenance/update/security process.
6. A conformance suite and release budget.
7. Agreement that parser and validator remain separate pipelines.

Without all seven, leave this document unimplemented.

## Non-negotiable pipeline separation

Parsing and validation must be independently callable and configurable:

```kotlin
val document = AsyncApiParser.fromText(text)
    .dereference()
    .applyTraits()
    .getDocument()

val report = AsyncApiValidator(options).validate(document)
```

Rules:

- `AsyncApiParser` performs syntax reading, resource loading, references, optional `allOf`, traits, and indexes.
- `AsyncApiValidator` never changes parser state or mutates source/effective views.
- `parse()` never invokes validation.
- `validate()` never applies traits implicitly.
- A validation failure never destroys an already parsed document.
- Parser diagnostics and validation diagnostics share a base shape but use distinct categories/codes.
- Source validation is the default; effective-view validation is explicit.

## Reference API

```kotlin
enum class AsyncApiValidationMode {
    REPORT,
    STRICT,
}

enum class AsyncApiValidationView {
    SOURCE,
    EFFECTIVE,
}

data class AsyncApiValidationOptions(
    val mode: AsyncApiValidationMode = AsyncApiValidationMode.REPORT,
    val view: AsyncApiValidationView = AsyncApiValidationView.SOURCE,
    val profile: AsyncApiValidationProfile = AsyncApiValidationProfile.CORE,
)

data class AsyncApiValidationReport(
    val diagnostics: List<AsyncApiDiagnostic>,
    val valid: Boolean,
)

class AsyncApiValidator(
    options: AsyncApiValidationOptions = AsyncApiValidationOptions(),
) {
    suspend fun validate(document: AsyncApiDocument): AsyncApiValidationReport
    suspend fun validateText(
        text: String,
        baseUri: String = "memory://anonymous",
    ): AsyncApiValidationReport
    suspend fun validate(uri: String): AsyncApiValidationReport
}
```

`REPORT` is the default and returns every diagnostic that can be collected safely. `STRICT` may throw only after building the complete report and must retain that report on its exception.

Raw text/URI validation may reuse the core YAML/JSON reader and loader but must not call the transformation-oriented parser pipeline.

## Diagnostic contract

Each validation diagnostic should include:

- stable code;
- error/warning/info severity;
- message;
- source URI;
- JSON Pointer;
- line/column range when available;
- AsyncAPI version/profile;
- optional related locations;
- category such as structural, semantic, recommended, schema-format, or provisional.

Collect independent failures instead of failing on the first rule. Never expose raw casts or validator-library exceptions as the public format.

## Validation layers

If implemented, keep layers explicit:

### 1. Syntax and resource safety

Syntax/resource failures remain parser diagnostics. The validator consumes structurally parsed input and must not duplicate loader/reference behavior.

### 2. Structural rules

Small auditable Kotlin rules check shapes needed for safe interpretation, such as required root types, expected list/map shapes, and version-aware object placement.

### 3. Cross-field semantic rules

Examples include:

- duplicate operation/message identifiers;
- channel/server variable declarations matching expressions;
- server/security references resolving to declared components;
- security array rules for relevant scheme types;
- operation/channel/message relationship integrity;
- channel server declarations;
- duplicate tags across relevant objects/traits;
- version-specific restrictions not expressible in bundled JSON Schema.

### 4. Recommended practices

Best-practice rules produce warnings, not conformance errors. Keep them separately selectable from core validity.

### 5. Provisional rules

Unreleased behavior such as channel traits must be separately identified and must never be represented as released-spec conformance.

## Rule implementation

Prefer typed Kotlin rule implementations over a generic rule language:

```kotlin
interface AsyncApiValidationRule {
    val code: String
    val supportedVersions: AsyncApiVersionRange

    fun validate(
        document: AsyncApiDocument,
        context: AsyncApiValidationContext,
    ): List<AsyncApiDiagnostic>
}
```

Rules are registered explicitly. Avoid cloning Spectral, supporting arbitrary JSONPath rule expressions, or executing JavaScript functions. Custom rule plugins are trusted application code and should be opt-in.

## Schema supply-chain policy

Never depend on `@asyncapi/specs`, another AsyncAPI npm package, or runtime/build downloads of schemas/rules. Never execute code shipped with a schema bundle.

If JSON Schema validation is added, use repository-vendored, manually reviewed snapshots only. Every snapshot requires:

- exact AsyncAPI version;
- upstream source URL and commit/tag;
- retrieval date;
- license notice;
- checked-in SHA-256 manifest;
- explicit human-reviewed update commit;
- tests proving runtime/builds work with network disabled.

Builds must never auto-refresh schemas. Treat a schema update like source-code modification, not dependency resolution.

Prefer storing only versions the project explicitly supports. Do not bundle every historical/pre-release schema by default.

## Schema validator boundary

Any JSON Schema engine is a separately reviewed dependency. Document:

- supported drafts;
- `$id`/base-URI behavior;
- external-ref policy;
- format assertion policy;
- cycle behavior;
- maximum depth/size/error count;
- JVM/JS parity;
- known mismatches with AsyncAPI schemas.

JSON Schema validity alone must never be described as complete AsyncAPI validity because semantic rules remain outside it.

## Multi-format validation

Payload schema validation is separate from AsyncAPI document validation. Do not automatically pull Protobuf, Avro, OpenAPI, or RAML validators into the base validator.

If Spec 03 schema handlers exist, a validation profile may explicitly delegate syntax checks to a registered trusted handler. Missing handlers should produce an unsupported-format diagnostic according to configured policy, not trigger dependency downloads.

RAML remains out of scope unless separately specified.

## Resource and denial-of-service limits

Validation accepts untrusted documents. Define limits for:

- input bytes;
- external resources and total fetched bytes;
- reference depth/count;
- graph nodes/depth;
- collection/string sizes;
- diagnostics count;
- rule execution time where enforceable;
- schema recursion.

Respect core loader authentication and network policies. Validation must not expand network authority.

## Conformance requirements if promoted

At minimum test:

- every supported AsyncAPI version/profile;
- valid and invalid official examples where license permits;
- semantic rules listed above;
- source/effective-view selection;
- complete `REPORT` aggregation;
- `STRICT` retaining its report;
- no parser mutation or implicit traits;
- network-disabled execution;
- schema checksum verification;
- malformed/cyclic/external documents;
- resource limits;
- JVM/JS parity for common rules;
- stable diagnostic codes and locations.

Publish an explicit coverage matrix. Documentation must state missing rules. Never claim full conformance based solely on schema validation.

## Maintenance estimate

An initial useful validator is likely to require approximately 1,000–2,500 production lines plus 2,000–4,000 test lines before schema bundles/fixtures. The primary cost is not line count: it is continuous spec/version/rule/security ownership.

Every new supported AsyncAPI release requires review of:

- structural layout changes;
- schema snapshot provenance and diffs;
- semantic rules;
- diagnostics and fixtures;
- compatibility promises;
- downstream SDK behavior.

## Documentation rule

Keep this file as reference-only. Specs 01–03 and the root README must not advertise validation as available.

If validation is ever promoted:

1. Create a new normative implementation spec referencing this design.
2. Record the approved scope/version matrix and owner.
3. Implement and test before editing README.
4. Clearly distinguish parser diagnostics from validation reports.
5. Document incomplete coverage and schema provenance.

## Explicit non-goals

- No validation implementation under current Specs 01 or 02.
- No Spectral clone or arbitrary JSONPath rule DSL.
- No JavaScript rule execution.
- No automatic schema downloads.
- No AsyncAPI npm dependencies.
- No claim of full conformance without published coverage evidence.
