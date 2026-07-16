# asyncapi-parser-kmp

Lightweight AsyncAPI Parser for Kotlin Multiplatform with trait composition and semantic navigation, built on top of [json-schema-ref-parser-kmp](https://github.com/ZenWave360/json-schema-ref-parser-kmp)'s dereferencing and JSON Merge Patch: AsyncAPI adds `traits`/`x-traits` composition on top of plain `$ref` dereferencing and `allOf` merging, so this module exists as a separate, focused layer rather than folding AsyncAPI-specific behavior into the generic core.

```kotlin
dependencies {
    implementation("io.zenwave360.jsonrefparser:asyncapi-parser-kmp:<version>")
}
```

```xml
<dependency>
  <groupId>io.zenwave360.jsonrefparser</groupId>
  <artifactId>asyncapi-parser-kmp-jvm</artifactId>
  <version>${asyncapi-parser-kmp-jvm.version}</version>
</dependency>
```

## Quick Start

```kotlin
import io.zenwave360.asyncapi.AsyncApiParser

val document = AsyncApiParser("asyncapi.yaml")
    .dereference()
    .applyTraits()
    .getDocument()

val operation = document.operations().first()
val messages = document.operationMessages(operation.id!!)
```

For inline text:

```kotlin
val document = AsyncApiParser.fromText(yaml, "memory://asyncapi.yaml")
    .dereference()
    .applyTraits()
    .getDocument()
```

Blocking JVM callers can use `JavaAsyncApiParser`:

```java
AsyncApiDocument document = JavaAsyncApiParser.from(new File("asyncapi.yaml"))
        .dereference()
        .applyTraits()
        .getDocument();
```

The JS target exports `parseAsyncApiText` and `asyncApiOperationMessagesText`. Returned documents, entries, and collections are plain JavaScript objects and arrays. No AsyncAPI npm runtime is used.

## How `applyTraits()` works

`applyTraits()` runs after `dereference()` (or directly after `parse()` for purely inline traits) and merges, via [RFC 7396 JSON Merge Patch](https://github.com/ZenWave360/json-schema-ref-parser-kmp#json-merge-patch), every AsyncAPI section the spec allows to declare `traits`: messages and operations on both v2 and v3, and channels on v3 only (provisional).

Alongside the spec's `traits` selector, it also merges `x-traits` when present. For v3 channels, `x-traits` is preferred and exclusive over `traits`; for messages and operations, `x-traits` is an additional selector composed together with `traits`. This keeps trait composition forward-compatible with constructs the current AsyncAPI spec doesn't yet sanction traits for.

See [Trait behavior](#trait-behavior) below for the full precedence rules and diagnostics, and [Source and effective views](#source-and-effective-views) for what `applyTraits()` does and does not mutate.

## Source and effective views

`AsyncApiDocument` keeps two explicit views:

- `source` and `sourceNavigator()` retain the parsed selectors and source/reference metadata.
- `effectiveRoot`, `effectiveSchema`, and the document query methods expose the separately allocated, trait-expanded graph.

Trait application never mutates the source tree, reusable traits, or resolved source targets. Consumed selectors are removed only from effective targets. Applying traits more than once is idempotent. Merged values do not receive fabricated source locations.

## Trait behavior

| Target             | AsyncAPI 2.x | AsyncAPI 3.x                            |
|--------------------|--------------|-----------------------------------------|
| Message traits     | Yes          | Yes                                     |
| Operation traits   | Yes          | Yes                                     |
| Channel traits     | No           | Yes, provisional                        |
| Channel `x-traits` | Diagnostic   | Preferred selector                      |
| Channel `traits`   | Diagnostic   | Fallback only when `x-traits` is absent |

Message and operation conflicts use this precedence:

```text
target object > first trait > second trait > later traits
```

Objects merge recursively through JSON Merge Patch. Arrays and scalars replace atomically, and a higher-precedence `null` deletes the member. Standard `x-*` extensions merge normally.

AsyncAPI v3 channel selection is exclusive and based on property presence:

```text
if x-traits is present: use only x-traits
else if traits is present: use traits
else: apply no channel traits
```

An empty `x-traits` suppresses `traits`. A null or malformed `x-traits` reports an error and never falls back. Channel traits reject the identity-bearing fields `address`, `messages`, `traits`, and `x-traits`; the channel-traits proposal is provisional and isolated from the generic merge implementation.

`AsyncApiParserOptions.invalidTraitHandling` defaults to `FAIL`. Use `COLLECT_AND_SKIP` to retain diagnostics and skip malformed selectors/items instead. Diagnostics cover version structure, malformed selectors, non-object items, unresolved trait references, v2 channel-trait attempts, and forbidden channel fields. These are parser/trait diagnostics only: this module does not perform AsyncAPI conformance validation.

## Semantic navigation

Navigation defaults to the effective view:

```kotlin
document.channels()
document.componentChannels()
document.operations()
document.componentOperations()
document.channelMessages()
document.componentMessages()

document.operationMessages(operationId)
document.channelMessages(channelId)
document.channelOperations(channelId)

document.atPointer("/channels/orders")
document.sourceNavigator().atPointer("/channels/orders")
```

`AsyncApiEntry` is map-backed and carries its declared ID when available, declaration pointer, source URI, value, and optional usage pointer. v2 operation entries also retain channel/direction context through `channelId` and `action`; v3 entries retain the operation action and resolved channel relationship.

Root and component declarations stay separate and preserve document order. Referenced usages keep declaration pointer/source identity plus their usage pointer. Relationship queries resolve by declaration/reference identity rather than value equality or unrestricted recursive key searches. The implementation uses internal v2/v3 layout strategies and exact JSON Pointers; it does not expose or depend on a general JSONPath evaluator.

Equivalent calls hide the version-specific structure:

```kotlin
// v2: follows channels.*.(publish|subscribe).message and message.oneOf
val v2Messages = v2Document.operationMessages("publishOrder")

// v3: follows operations.*.messages and channel/message references
val v3Messages = v3Document.operationMessages("publishOrder")
```

Entries without a specification ID or natural declaration map key keep a null `id`; this release does not invent traversal-based anonymous IDs.

## Design specs

Deferred and validation scopes are described in [Spec 03](docs/asyncapi-parser/03-deferred-capabilities.md) and [Spec 04](docs/asyncapi-parser/04-validation-reference-only.md).
