package io.zenwave360.asyncapi

import io.zenwave360.jsonrefparser.JsonPointer
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.RefParserOptions
import io.zenwave360.jsonrefparser.model.SourceLocation

data class AsyncApiParserOptions(
    val refParserOptions: RefParserOptions = RefParserOptions(),
    val invalidTraitHandling: InvalidTraitHandling = InvalidTraitHandling.FAIL,
)

enum class InvalidTraitHandling {
    FAIL,
    COLLECT_AND_SKIP,
}

data class AsyncApiVersion(
    val raw: String,
    val major: Int,
    val minor: Int,
    val patch: Int,
    val suffix: String? = null,
) {
    val isSupported: Boolean get() = major == 2 || major == 3

    companion object {
        internal val UNKNOWN = AsyncApiVersion("", -1, -1, -1)
    }
}

enum class AsyncApiDiagnosticSeverity { ERROR, WARNING }

data class AsyncApiDiagnostic(
    val code: String,
    val severity: AsyncApiDiagnosticSeverity,
    val message: String,
    val pointer: String? = null,
    val sourceLocation: SourceLocation? = null,
)

class AsyncApiTraitException(
    val diagnostic: AsyncApiDiagnostic,
) : IllegalArgumentException(diagnostic.message)

enum class AsyncApiDeclarationKind {
    CHANNEL,
    OPERATION,
    MESSAGE,
}

data class AsyncApiEntry(
    val id: String?,
    val kind: AsyncApiDeclarationKind,
    val pointer: String,
    val sourceUri: String,
    val value: Map<String, Any?>,
    val usagePointer: String? = null,
    val channelId: String? = null,
    val action: String? = null,
)

data class AsyncApiDocument(
    val version: AsyncApiVersion,
    val source: ParsedDocument,
    val effective: ParsedDocument,
    val effectiveRoot: Any?,
    val effectiveSchema: Map<String, Any?>,
    val parserDiagnostics: List<AsyncApiDiagnostic> = emptyList(),
) {
    fun channels(): List<AsyncApiEntry> = navigator().channels()
    fun componentChannels(): List<AsyncApiEntry> = navigator().componentChannels()
    fun operations(): List<AsyncApiEntry> = navigator().operations()
    fun componentOperations(): List<AsyncApiEntry> = navigator().componentOperations()
    fun channelMessages(): List<AsyncApiEntry> = navigator().channelMessages()
    fun componentMessages(): List<AsyncApiEntry> = navigator().componentMessages()
    fun operationMessages(operationId: String): List<AsyncApiEntry> =
        navigator().operationMessages(operationId)
    fun channelMessages(channelId: String): List<AsyncApiEntry> =
        navigator().channelMessages(channelId)
    fun channelOperations(channelId: String): List<AsyncApiEntry> =
        navigator().channelOperations(channelId)
    fun atPointer(pointer: String): Any? = JsonPointer.parseFragment(pointer).resolve(effectiveRoot)

    fun sourceNavigator(): AsyncApiNavigator =
        AsyncApiNavigator(this, source.root, sourceView = true)

    fun navigator(): AsyncApiNavigator =
        AsyncApiNavigator(this, effectiveRoot, sourceView = false)
}
