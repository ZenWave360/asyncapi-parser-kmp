package io.zenwave360.asyncapi

internal data class LayoutTarget(
    val pointer: String,
    val value: MutableMap<String, Any?>,
)

internal interface AsyncApiLayout {
    fun channels(root: Map<String, Any?>): List<LayoutTarget>
    fun componentChannels(root: Map<String, Any?>): List<LayoutTarget>
    fun operations(root: Map<String, Any?>): List<LayoutTarget>
    fun componentOperations(root: Map<String, Any?>): List<LayoutTarget>
    fun messages(root: Map<String, Any?>): List<LayoutTarget>
    fun componentMessages(root: Map<String, Any?>): List<LayoutTarget>
}

internal fun layoutFor(version: AsyncApiVersion): AsyncApiLayout? = when (version.major) {
    2 -> AsyncApiV2Layout
    3 -> AsyncApiV3Layout
    else -> null
}

private object AsyncApiV2Layout : AsyncApiLayout {
    override fun channels(root: Map<String, Any?>): List<LayoutTarget> =
        mapTargets(root["channels"], "/channels")

    override fun componentChannels(root: Map<String, Any?>): List<LayoutTarget> = emptyList()

    override fun operations(root: Map<String, Any?>): List<LayoutTarget> = buildList {
        channels(root).forEach { channel ->
            listOf("publish", "subscribe").forEach { action ->
                channel.value[action].asMutableStringMap()?.let {
                    add(LayoutTarget(pointerChild(channel.pointer, action), it))
                }
            }
        }
    }

    override fun componentOperations(root: Map<String, Any?>): List<LayoutTarget> = emptyList()

    override fun messages(root: Map<String, Any?>): List<LayoutTarget> =
        distinctTargets(buildList {
            addAll(componentMessages(root))
            operations(root).forEach { operation ->
                val messagePointer = pointerChild(operation.pointer, "message")
                val message = operation.value["message"].asMutableStringMap() ?: return@forEach
                add(LayoutTarget(messagePointer, message))
                val alternatives = message["oneOf"] as? List<*>
                alternatives?.forEachIndexed { index, alternative ->
                    alternative.asMutableStringMap()?.let {
                        add(LayoutTarget("$messagePointer/oneOf/$index", it))
                    }
                }
            }
        })

    override fun componentMessages(root: Map<String, Any?>): List<LayoutTarget> =
        mapTargets(root["components"].asStringMap()?.get("messages"), "/components/messages")
}

private object AsyncApiV3Layout : AsyncApiLayout {
    override fun channels(root: Map<String, Any?>): List<LayoutTarget> =
        mapTargets(root["channels"], "/channels")

    override fun componentChannels(root: Map<String, Any?>): List<LayoutTarget> =
        mapTargets(root["components"].asStringMap()?.get("channels"), "/components/channels")

    override fun operations(root: Map<String, Any?>): List<LayoutTarget> =
        mapTargets(root["operations"], "/operations")

    override fun componentOperations(root: Map<String, Any?>): List<LayoutTarget> =
        mapTargets(root["components"].asStringMap()?.get("operations"), "/components/operations")

    override fun messages(root: Map<String, Any?>): List<LayoutTarget> =
        distinctTargets(buildList {
            addAll(componentMessages(root))
            (channels(root) + componentChannels(root)).forEach { channel ->
                addAll(mapTargets(channel.value["messages"], pointerChild(channel.pointer, "messages")))
            }
            (operations(root) + componentOperations(root)).forEach { operation ->
                val messages = operation.value["messages"] as? List<*> ?: return@forEach
                messages.forEachIndexed { index, item ->
                    item.asMutableStringMap()?.let {
                        add(LayoutTarget("${operation.pointer}/messages/$index", it))
                    }
                }
            }
        })

    override fun componentMessages(root: Map<String, Any?>): List<LayoutTarget> =
        mapTargets(root["components"].asStringMap()?.get("messages"), "/components/messages")
}

internal fun mapTargets(value: Any?, basePointer: String): List<LayoutTarget> {
    val map = value.asStringMap() ?: return emptyList()
    return map.mapNotNull { (key, child) ->
        child.asMutableStringMap()?.let { LayoutTarget(pointerChild(basePointer, key), it) }
    }
}

internal fun distinctTargets(targets: List<LayoutTarget>): List<LayoutTarget> {
    val seen = IdentitySet()
    return targets.filter { seen.add(it.value) }
}
