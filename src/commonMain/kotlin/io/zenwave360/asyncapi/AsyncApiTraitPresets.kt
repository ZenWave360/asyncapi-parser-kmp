package io.zenwave360.asyncapi

import kotlin.jvm.JvmStatic

/**
 * A single trait-composition rule: the set of JSONPaths whose nodes should have their traits composed,
 * plus the version-specific semantics for those nodes.
 *
 * @param paths JSONPaths selecting the target nodes (resolved against the parsed map graph).
 * @param kind node kind label used in diagnostics: `"message"`, `"operation"` or `"channel"`.
 * @param selectors trait-selector property names in precedence order; the first one present on a node
 *   is the one composed (e.g. `["x-traits", "traits"]` makes `x-traits` win over `traits`).
 * @param forbiddenFields fields a trait may not carry (identity-bearing fields that cannot be composed).
 * @param removeOtherSelectorsOnMatch when the winning selector is not the last one, drop the remaining
 *   selector keys from the node (e.g. AsyncAPI v3 channels drop `traits` when `x-traits` is used).
 * @param mode [TraitMode.COMPOSE] to merge traits, or [TraitMode.REJECT] to report the mere presence of
 *   a selector as a diagnostic (e.g. AsyncAPI v2 channels do not support traits at all).
 */
data class TraitRule(
    val paths: List<String>,
    val kind: String,
    val selectors: List<String> = listOf("traits"),
    val forbiddenFields: Set<String> = emptySet(),
    val removeOtherSelectorsOnMatch: Boolean = false,
    val mode: TraitMode = TraitMode.COMPOSE,
    val rejectCode: String = "",
    val rejectMessage: String = "",
)

enum class TraitMode { COMPOSE, REJECT }

/**
 * Standard AsyncAPI v2/v3 trait-composition rule sets, shared by this library's own
 * [AsyncApiParser.applyTraits] and by external callers (e.g. the ZenWave SDK) so the "where do traits
 * live" knowledge is defined once. Rules are ordered message → operation → channel.
 */
object AsyncApiTraitPresets {

    @JvmStatic
    fun forVersion(major: Int): List<TraitRule> = when (major) {
        2 -> V2
        3 -> V3
        else -> emptyList()
    }

    private val V2: List<TraitRule> = listOf(
        TraitRule(
            kind = "message",
            paths = listOf(
                "$.components.messages[*]",
                "$.channels[*].publish.message",
                "$.channels[*].subscribe.message",
                "$.channels[*].publish.message.oneOf[*]",
                "$.channels[*].subscribe.message.oneOf[*]",
            ),
        ),
        TraitRule(
            kind = "operation",
            paths = listOf(
                "$.channels[*].publish",
                "$.channels[*].subscribe",
            ),
        ),
        TraitRule(
            kind = "channel",
            paths = listOf("$.channels[*]"),
            selectors = listOf("x-traits", "traits"),
            mode = TraitMode.REJECT,
            rejectCode = "ASYNCAPI_V2_CHANNEL_TRAITS",
            rejectMessage = "Channel traits are only supported for AsyncAPI v3",
        ),
    )

    private val V3: List<TraitRule> = listOf(
        TraitRule(
            kind = "message",
            paths = listOf(
                "$.components.messages[*]",
                "$.channels[*].messages[*]",
                "$.components.channels[*].messages[*]",
                "$.operations[*].messages[*]",
                "$.components.operations[*].messages[*]",
            ),
        ),
        TraitRule(
            kind = "operation",
            paths = listOf(
                "$.operations[*]",
                "$.components.operations[*]",
            ),
        ),
        TraitRule(
            kind = "channel",
            paths = listOf(
                "$.channels[*]",
                "$.components.channels[*]",
            ),
            selectors = listOf("x-traits", "traits"),
            forbiddenFields = setOf("address", "messages", "traits", "x-traits"),
            removeOtherSelectorsOnMatch = true,
        ),
    )
}
