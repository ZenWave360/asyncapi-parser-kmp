@file:OptIn(kotlin.js.ExperimentalJsExport::class)
@file:JsExport

package io.zenwave360.asyncapi

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise

@OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)
@JsExport
fun parseAsyncApiText(
    input: String,
    baseUri: String = "memory://anonymous",
    applyTraits: Boolean = true,
): Promise<Any?> = GlobalScope.promise {
    val parser = AsyncApiParser.fromText(input, baseUri).dereference()
    if (applyTraits) parser.applyTraits()
    exportDocument(parser.getDocument())
}
@OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)
@JsExport
fun asyncApiOperationMessagesText(
    input: String,
    operationId: String,
    baseUri: String = "memory://anonymous",
): Promise<Any?> = GlobalScope.promise {
    val document = AsyncApiParser.fromText(input, baseUri)
        .dereference()
        .applyTraits()
        .getDocument()
    convertToPlain(document.operationMessages(operationId).map(::entryToMap))
}

private fun exportDocument(document: AsyncApiDocument): Any? {
    val output = js("{}")
    output.version = convertToPlain(
        mapOf(
            "raw" to document.version.raw,
            "major" to document.version.major,
            "minor" to document.version.minor,
            "patch" to document.version.patch,
            "suffix" to document.version.suffix,
        ),
    )
    output.source = convertToPlain(document.source.root)
    output.effectiveRoot = convertToPlain(document.effectiveRoot)
    output.diagnostics = convertToPlain(document.parserDiagnostics.map { diagnostic ->
        mapOf(
            "code" to diagnostic.code,
            "severity" to diagnostic.severity.name,
            "message" to diagnostic.message,
            "pointer" to diagnostic.pointer,
        )
    })
    output.channels = convertToPlain(document.channels().map(::entryToMap))
    output.componentChannels = convertToPlain(document.componentChannels().map(::entryToMap))
    output.operations = convertToPlain(document.operations().map(::entryToMap))
    output.componentOperations = convertToPlain(document.componentOperations().map(::entryToMap))
    output.messages = convertToPlain(document.channelMessages().map(::entryToMap))
    output.componentMessages = convertToPlain(document.componentMessages().map(::entryToMap))
    return output
}

private fun entryToMap(entry: AsyncApiEntry): Map<String, Any?> = mapOf(
    "id" to entry.id,
    "kind" to entry.kind.name,
    "pointer" to entry.pointer,
    "sourceUri" to entry.sourceUri,
    "value" to entry.value,
    "usagePointer" to entry.usagePointer,
    "channelId" to entry.channelId,
    "action" to entry.action,
)

private fun convertToPlain(value: Any?): Any? = when (value) {
    null -> null
    is Map<*, *> -> {
        val output = js("{}")
        value.forEach { (key, child) -> output[key.toString()] = convertToPlain(child) }
        output
    }
    is Collection<*> -> {
        val output = js("[]")
        value.forEach { output.push(convertToPlain(it)) }
        output
    }
    else -> value
}
