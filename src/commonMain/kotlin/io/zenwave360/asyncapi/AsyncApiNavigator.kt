package io.zenwave360.asyncapi

import io.zenwave360.jsonrefparser.JsonPointer

class AsyncApiNavigator internal constructor(
    private val document: AsyncApiDocument,
    root: Any?,
    private val sourceView: Boolean,
) {
    private val root: MutableMap<String, Any?>? = root.asMutableStringMap()
    private val layout: AsyncApiLayout? = layoutFor(document.version)
    private val sourceUri: String = rootSourceUri(document)

    fun channels(): List<AsyncApiEntry> =
        entries(root?.let { layout?.channels(it) }.orEmpty(), AsyncApiDeclarationKind.CHANNEL)

    fun componentChannels(): List<AsyncApiEntry> =
        entries(root?.let { layout?.componentChannels(it) }.orEmpty(), AsyncApiDeclarationKind.CHANNEL)

    fun operations(): List<AsyncApiEntry> =
        operationEntries(root?.let { layout?.operations(it) }.orEmpty())

    fun componentOperations(): List<AsyncApiEntry> =
        operationEntries(root?.let { layout?.componentOperations(it) }.orEmpty())

    fun channelMessages(): List<AsyncApiEntry> {
        val schema = root ?: return emptyList()
        return if (document.version.major == 2) {
            distinctEntries(operations().flatMap { messagesFromOperation(it) })
        } else {
            distinctEntries(layout!!.channels(schema).flatMap { channelMessageEntries(it) })
        }
    }

    fun componentMessages(): List<AsyncApiEntry> =
        entries(root?.let { layout?.componentMessages(it) }.orEmpty(), AsyncApiDeclarationKind.MESSAGE)

    fun operationMessages(operationId: String): List<AsyncApiEntry> =
        distinctEntries(
            (operations() + componentOperations())
                .filter { it.id == operationId }
                .flatMap { messagesFromOperation(it) },
        )

    fun channelMessages(channelId: String): List<AsyncApiEntry> {
        val channel = (channels() + componentChannels()).firstOrNull { it.id == channelId }
            ?: return emptyList()
        return if (document.version.major == 2) {
            distinctEntries(channelOperations(channelId).flatMap { messagesFromOperation(it) })
        } else {
            val target = channel.value.asMutableStringMap() ?: return emptyList()
            channelMessageEntries(LayoutTarget(channel.usagePointer ?: channel.pointer, target))
        }
    }

    fun channelOperations(channelId: String): List<AsyncApiEntry> {
        val selected = (channels() + componentChannels()).firstOrNull { it.id == channelId }
            ?: return emptyList()
        if (document.version.major == 2) {
            return operations().filter { it.channelId == channelId }
        }
        return (operations() + componentOperations()).filter { operation ->
            operation.value["channel"] === selected.value
        }
    }

    fun atPointer(pointer: String): Any? =
        JsonPointer.parseFragment(pointer).resolve(root)

    private fun entries(
        targets: List<LayoutTarget>,
        kind: AsyncApiDeclarationKind,
    ): List<AsyncApiEntry> = targets.map { target ->
        val naturalId = when (kind) {
            AsyncApiDeclarationKind.CHANNEL -> pointerKey(target.pointer)
            AsyncApiDeclarationKind.MESSAGE ->
                target.value["name"] as? String ?: pointerKey(target.pointer)
            AsyncApiDeclarationKind.OPERATION ->
                target.value["operationId"] as? String ?: pointerKey(target.pointer)
        }
        entry(target.value, kind, target.pointer, naturalId)
    }

    private fun operationEntries(targets: List<LayoutTarget>): List<AsyncApiEntry> =
        targets.map { target ->
            if (document.version.major == 2) {
                val action = pointerKey(target.pointer)
                val channelPointer = target.pointer.substringBeforeLast("/")
                val channelId = pointerKey(channelPointer)
                entry(
                    value = target.value,
                    kind = AsyncApiDeclarationKind.OPERATION,
                    usagePointer = target.pointer,
                    naturalId = target.value["operationId"] as? String,
                    channelId = channelId,
                    action = action,
                )
            } else {
                val channel = target.value["channel"]
                val channelId = (channels() + componentChannels())
                    .firstOrNull { it.value === channel }
                    ?.id
                entry(
                    value = target.value,
                    kind = AsyncApiDeclarationKind.OPERATION,
                    usagePointer = target.pointer,
                    naturalId = target.value["operationId"] as? String ?: pointerKey(target.pointer),
                    channelId = channelId,
                    action = target.value["action"] as? String,
                )
            }
        }

    private fun messagesFromOperation(operation: AsyncApiEntry): List<AsyncApiEntry> {
        val operationPointer = operation.usagePointer ?: operation.pointer
        return if (document.version.major == 2) {
            val messagePointer = pointerChild(operationPointer, "message")
            val message = operation.value["message"].asMutableStringMap() ?: return emptyList()
            val oneOf = message["oneOf"] as? List<*>
            if (oneOf == null) {
                listOf(messageUsageEntry(message, messagePointer))
            } else {
                oneOf.mapIndexedNotNull { index, item ->
                    item.asMutableStringMap()?.let { messageUsageEntry(it, "$messagePointer/oneOf/$index") }
                }
            }
        } else {
            val messages = operation.value["messages"] as? List<*>
            if (messages != null) {
                messages.mapIndexedNotNull { index, item ->
                    item.asMutableStringMap()?.let {
                        messageUsageEntry(it, "$operationPointer/messages/$index")
                    }
                }
            } else {
                val channel = operation.value["channel"].asMutableStringMap() ?: return emptyList()
                val declaration = (channels() + componentChannels()).firstOrNull { it.value === channel }
                    ?: return emptyList()
                channelMessageEntries(LayoutTarget(declaration.usagePointer ?: declaration.pointer, channel))
            }
        }
    }

    private fun channelMessageEntries(channel: LayoutTarget): List<AsyncApiEntry> =
        mapTargets(channel.value["messages"], pointerChild(channel.pointer, "messages")).map { target ->
            entry(
                value = target.value,
                kind = AsyncApiDeclarationKind.MESSAGE,
                usagePointer = target.pointer,
                naturalId = target.value["name"] as? String ?: pointerKey(target.pointer),
            )
        }

    private fun messageUsageEntry(
        value: MutableMap<String, Any?>,
        usagePointer: String,
    ): AsyncApiEntry {
        val declaration = declaredMessages().firstOrNull { it.value === value }
        return if (declaration != null) {
            declaration.copy(usagePointer = usagePointer)
        } else {
            entry(
                value = value,
                kind = AsyncApiDeclarationKind.MESSAGE,
                usagePointer = usagePointer,
                naturalId = value["name"] as? String,
            )
        }
    }

    private fun declaredMessages(): List<AsyncApiEntry> {
        val schema = root ?: return emptyList()
        val declared = mutableListOf<AsyncApiEntry>()
        declared += componentMessages()
        if (document.version.major == 3) {
            layout!!.channels(schema).forEach { declared += channelMessageEntries(it) }
            layout.componentChannels(schema).forEach { declared += channelMessageEntries(it) }
        }
        return declared
    }

    private fun entry(
        value: MutableMap<String, Any?>,
        kind: AsyncApiDeclarationKind,
        usagePointer: String,
        naturalId: String?,
        channelId: String? = null,
        action: String? = null,
    ): AsyncApiEntry {
        val origin = origin(usagePointer)
        return AsyncApiEntry(
            id = naturalId ?: origin.declarationId,
            kind = kind,
            pointer = origin.pointer,
            sourceUri = origin.sourceUri,
            value = value,
            usagePointer = origin.usagePointer,
            channelId = channelId,
            action = action,
        )
    }

    private fun origin(usagePointer: String): Origin {
        if (sourceView) return Origin(usagePointer, sourceUri, null, null)
        val ref = document.source.resolvedRefs.lastOrNull { it.sourcePointer == usagePointer }
            ?: return Origin(usagePointer, sourceUri, null, null)
        val fragment = ref.refString.substringAfter("#", "")
        val declarationPointer = when {
            fragment.isEmpty() -> ""
            fragment.startsWith("/") -> fragment
            else -> "/$fragment"
        }
        return Origin(
            pointer = declarationPointer,
            sourceUri = ref.targetUri ?: ref.sourceUri ?: sourceUri,
            usagePointer = usagePointer,
            declarationId = declarationPointer.takeIf { it.isNotEmpty() }?.let(::pointerKey),
        )
    }

    private fun distinctEntries(entries: List<AsyncApiEntry>): List<AsyncApiEntry> {
        val seenDeclarations = mutableSetOf<String>()
        val seenValues = IdentitySet()
        return entries.filter { entry ->
            val declarationKey = "${entry.sourceUri}#${entry.pointer}"
            if (entry.pointer.isNotEmpty()) seenDeclarations.add(declarationKey)
            else seenValues.add(entry.value)
        }
    }

    private fun pointerKey(pointer: String): String =
        pointer.substringAfterLast("/")
            .replace("~1", "/")
            .replace("~0", "~")

    private data class Origin(
        val pointer: String,
        val sourceUri: String,
        val usagePointer: String?,
        val declarationId: String?,
    )
}
