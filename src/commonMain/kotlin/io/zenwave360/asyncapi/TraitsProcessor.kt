package io.zenwave360.asyncapi

import io.zenwave360.jsonrefparser.merge.JsonMergePatch

/**
 * Generic, reusable AsyncAPI trait-composition processor.
 *
 * Given a parsed (dereferenced) document root and a set of [TraitRule]s, it composes each node's
 * `traits`/`x-traits` into the node **in place**, following RFC 7396 JSON Merge Patch semantics. It is
 * driven entirely by JSONPath expressions ([TraitRule.paths]) — it carries no AsyncAPI-version logic of
 * its own — so it can be reused by this library's [AsyncApiParser] (via [AsyncApiTraitPresets]) and by
 * external callers that already hold a parsed map (e.g. the ZenWave SDK), applied to their own model.
 *
 * The processor is a plain, non-suspend class with Java-friendly signatures so JVM consumers can call
 * it directly without going through [JavaAsyncApiParser].
 */
class TraitsProcessor {

    /** Overload for Java callers that don't need to pass [sourceLocations]. */
    fun apply(
        root: MutableMap<String, Any?>,
        rules: List<TraitRule>,
        handling: InvalidTraitHandling,
    ): List<AsyncApiDiagnostic> = apply(root, rules, handling, emptyMap())

    /** Overload for Java callers that want [InvalidTraitHandling.FAIL] and no source locations. */
    fun apply(
        root: MutableMap<String, Any?>,
        rules: List<TraitRule>,
    ): List<AsyncApiDiagnostic> = apply(root, rules, InvalidTraitHandling.FAIL, emptyMap())

    /**
     * Applies [rules] to [root] in place and returns any diagnostics collected. With
     * [InvalidTraitHandling.FAIL] the first invalid trait throws [AsyncApiTraitException]; with
     * [InvalidTraitHandling.COLLECT_AND_SKIP] invalid traits are recorded and skipped.
     */
    fun apply(
        root: MutableMap<String, Any?>,
        rules: List<TraitRule>,
        handling: InvalidTraitHandling,
        sourceLocations: Map<String, io.zenwave360.jsonrefparser.model.SourceLocation>,
    ): List<AsyncApiDiagnostic> {
        val diagnostics = mutableListOf<AsyncApiDiagnostic>()
        val engine = Engine(handling, sourceLocations, diagnostics)
        rules.forEach { rule -> engine.applyRule(root, rule) }
        return diagnostics.toList()
    }

    private class Engine(
        private val handling: InvalidTraitHandling,
        private val sourceLocations: Map<String, io.zenwave360.jsonrefparser.model.SourceLocation>,
        private val diagnostics: MutableList<AsyncApiDiagnostic>,
    ) {
        fun applyRule(root: MutableMap<String, Any?>, rule: TraitRule) {
            val targets = IdentitySet()
            rule.paths.forEach { path ->
                JsonPathSelector.selectMaps(root, path).forEach { target ->
                    if (targets.add(target)) applyToTarget(target, rule)
                }
            }
        }

        private fun applyToTarget(target: MutableMap<String, Any?>, rule: TraitRule) {
            val selector = rule.selectors.firstOrNull { target.containsKey(it) } ?: return
            if (rule.mode == TraitMode.REJECT) {
                problem(rule.rejectCode, rule.rejectMessage, "/${rule.kind}/$selector")
                return
            }
            composeTraits(target, selector, rule)
            if (rule.removeOtherSelectorsOnMatch) {
                rule.selectors.filter { it != selector }.forEach { target.remove(it) }
            }
        }

        private fun composeTraits(target: MutableMap<String, Any?>, selector: String, rule: TraitRule) {
            val selectorPointer = "/${rule.kind}/$selector"
            val items = target[selector] as? List<*>
            if (items == null) {
                problem(
                    "ASYNCAPI_TRAIT_SELECTOR_NOT_ARRAY",
                    "The ${rule.kind} trait selector '$selector' must be an array",
                    selectorPointer,
                )
                if (handling == InvalidTraitHandling.COLLECT_AND_SKIP) target.remove(selector)
                return
            }

            val validTraits = mutableListOf<Map<String, Any?>>()
            items.forEachIndexed { index, item ->
                val itemPointer = "$selectorPointer/$index"
                val trait = item.asStringMap()
                when {
                    trait == null -> problem(
                        "ASYNCAPI_TRAIT_ITEM_NOT_OBJECT",
                        "The ${rule.kind} trait at index $index must resolve to an object",
                        itemPointer,
                    )
                    trait.containsKey("\$ref") -> problem(
                        "ASYNCAPI_TRAIT_REF_UNRESOLVED",
                        "Trait reference at index $index is unresolved; call dereference() before applyTraits()",
                        itemPointer,
                    )
                    acceptTrait(trait, itemPointer, rule) -> validTraits += trait
                }
            }

            val owned = linkedMapOf<String, Any?>()
            owned.putAll(target)
            owned.remove(selector)
            val traitKeys = validTraits.flatMapTo(mutableSetOf()) { it.keys }

            var composed: Any? = linkedMapOf<String, Any?>()
            validTraits.asReversed().forEach { trait ->
                composed = JsonMergePatch.apply(composed, trait)
            }
            composed = JsonMergePatch.apply(composed, owned)

            @Suppress("UNCHECKED_CAST")
            val effective = composed as MutableMap<String, Any?>
            owned.forEach { (key, originalValue) ->
                if (originalValue != null && key !in traitKeys) {
                    effective[key] = originalValue
                }
            }
            target.clear()
            target.putAll(effective)
        }

        private fun acceptTrait(trait: Map<String, Any?>, pointer: String, rule: TraitRule): Boolean {
            if (rule.forbiddenFields.isEmpty()) return true
            val forbidden = rule.forbiddenFields.firstOrNull(trait::containsKey) ?: return true
            problem(
                "ASYNCAPI_CHANNEL_TRAIT_FORBIDDEN_FIELD",
                "Channel trait field '$forbidden' is identity-bearing and cannot be composed",
                pointer,
            )
            return false
        }

        private fun problem(code: String, message: String, pointer: String) {
            val diagnostic = AsyncApiDiagnostic(
                code = code,
                severity = AsyncApiDiagnosticSeverity.ERROR,
                message = message,
                pointer = pointer,
                sourceLocation = sourceLocations[pointer],
            )
            diagnostics += diagnostic
            if (handling == InvalidTraitHandling.FAIL) throw AsyncApiTraitException(diagnostic)
        }
    }
}
