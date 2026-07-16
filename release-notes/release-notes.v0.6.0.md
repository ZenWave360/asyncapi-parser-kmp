# asyncapi-parser-kmp 0.6.0

First standalone release of `asyncapi-parser-kmp`, split out of `json-schema-ref-parser-kmp` into its own repository.

- AsyncAPI v2/v3 trait composition via `traits` and `x-traits`, covering messages and operations on both spec versions, plus provisional v3 channel traits
- Trait composition based on RFC 7396 JSON Merge Patch, with defined precedence rules for conflicting members
- Separate source and effective document views — trait application never mutates the parsed source tree
- Semantic navigation over channels, operations, messages, `componentX` variants, and `atPointer` lookups
- Structured trait diagnostics with configurable `InvalidTraitHandling` (`FAIL` or `COLLECT_AND_SKIP`)
- `AsyncApiParser` for Kotlin, `JavaAsyncApiParser` as a blocking JVM facade, and Node.js JS exports through Kotlin Multiplatform
