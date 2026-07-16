package io.zenwave360.asyncapi

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.AuthenticationValue
import io.zenwave360.jsonrefparser.model.ParsedDocument

class AsyncApiParser private constructor(
    val uri: String,
    private val options: AsyncApiParserOptions,
    private var delegate: RefParser,
) {
    constructor(
        uri: String,
        options: AsyncApiParserOptions = AsyncApiParserOptions(),
    ) : this(uri, options, RefParser(uri, options.refParserOptions))

    private var parsed = false
    private var sourceDocument: ParsedDocument? = null
    private var effectiveDocument: ParsedDocument? = null
    private var effectiveRoot: Any? = null
    private var version: AsyncApiVersion = AsyncApiVersion.UNKNOWN
    private val diagnostics = mutableListOf<AsyncApiDiagnostic>()

    fun withAuthentication(vararg auth: AuthenticationValue): AsyncApiParser = configured {
        delegate = delegate.withAuthentication(*auth)
    }

    fun withLoaders(vararg loaders: DocumentLoader): AsyncApiParser = configured {
        delegate = delegate.withLoaders(*loaders)
    }

    fun withLoaders(loaders: List<DocumentLoader>): AsyncApiParser = configured {
        delegate = delegate.withLoaders(loaders)
    }

    fun withDefaultLoaders(vararg loaders: DocumentLoader): AsyncApiParser = configured {
        delegate = delegate.withDefaultLoaders(*loaders)
    }

    fun withDefaultLoaders(loaders: List<DocumentLoader>): AsyncApiParser = configured {
        delegate = delegate.withDefaultLoaders(loaders)
    }

    suspend fun parse(): AsyncApiParser {
        diagnostics.clear()
        delegate.parse()
        val parsedDocument = delegate.getParsedDocument()
        sourceDocument = copyDocumentGraph(parsedDocument)
        effectiveDocument = copyDocumentGraph(parsedDocument)
        effectiveRoot = effectiveDocument!!.root
        version = parseVersion(sourceDocument!!)
        parsed = true
        return this
    }

    suspend fun dereference(): AsyncApiParser {
        if (!parsed) parse()
        delegate.dereference()
        refreshEffective(delegate.getParsedDocument())
        return this
    }

    suspend fun mergeAllOf(): AsyncApiParser {
        if (!parsed) parse()
        delegate.mergeAllOf()
        refreshEffective(delegate.getParsedDocument())
        return this
    }

    fun applyTraits(): AsyncApiParser {
        check(parsed) { "parse() or dereference() must be called before applyTraits()" }
        val root = effectiveRoot.asMutableStringMap() ?: return this
        if (!version.isSupported) return this

        diagnostics += TraitsProcessor().apply(
            root = root,
            rules = AsyncApiTraitPresets.forVersion(version.major),
            handling = options.invalidTraitHandling,
            sourceLocations = sourceDocument!!.locations,
        )
        return this
    }

    fun getDocument(): AsyncApiDocument {
        check(parsed) { "parse() or dereference() must be called before getDocument()" }
        val root = effectiveRoot
        return AsyncApiDocument(
            version = version,
            source = sourceDocument!!,
            effective = effectiveDocument!!,
            effectiveRoot = root,
            effectiveSchema = root.asStringMap() ?: emptyMap(),
            parserDiagnostics = diagnostics.toList(),
        )
    }

    private fun configured(block: () -> Unit): AsyncApiParser {
        check(!parsed) { "Parser configuration cannot change after parsing has started" }
        block()
        return this
    }

    private fun refreshEffective(parsedDocument: ParsedDocument) {
        val source = sourceDocument ?: copyDocumentGraph(parsedDocument)
        val effective = copyDocumentGraph(parsedDocument)
        sourceDocument = source.copy(
            documentLocations = parsedDocument.documentLocations,
            resolvedRefs = effective.resolvedRefs,
            originalAllOfs = effective.originalAllOfs,
            hasCircularRefs = parsedDocument.hasCircularRefs,
        )
        effectiveDocument = effective
        effectiveRoot = effective.root
    }

    private fun parseVersion(document: ParsedDocument): AsyncApiVersion {
        val root = document.root.asStringMap()
        if (root == null || !root.containsKey("asyncapi")) {
            diagnostics += versionDiagnostic(
                "ASYNCAPI_VERSION_MISSING",
                "The AsyncAPI document has no 'asyncapi' version",
                document,
            )
            return AsyncApiVersion.UNKNOWN
        }
        val rawValue = root["asyncapi"]
        if (rawValue !is String) {
            diagnostics += versionDiagnostic(
                "ASYNCAPI_VERSION_INVALID",
                "The AsyncAPI version must be a string",
                document,
            )
            return AsyncApiVersion(rawValue.toString(), -1, -1, -1)
        }

        val match = VERSION.matchEntire(rawValue)
        if (match == null) {
            diagnostics += versionDiagnostic(
                "ASYNCAPI_VERSION_INVALID",
                "Malformed AsyncAPI version: $rawValue",
                document,
            )
            return AsyncApiVersion(rawValue, -1, -1, -1)
        }

        val parsedVersion = AsyncApiVersion(
            raw = rawValue,
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
            suffix = match.groupValues[4].ifEmpty { null },
        )
        if (!parsedVersion.isSupported) {
            diagnostics += versionDiagnostic(
                "ASYNCAPI_VERSION_UNSUPPORTED",
                "Unsupported AsyncAPI major version: ${parsedVersion.major}",
                document,
            )
        }
        return parsedVersion
    }

    private fun versionDiagnostic(
        code: String,
        message: String,
        document: ParsedDocument,
    ) = AsyncApiDiagnostic(
        code = code,
        severity = AsyncApiDiagnosticSeverity.ERROR,
        message = message,
        pointer = "/asyncapi",
        sourceLocation = document.locations["/asyncapi"],
    )

    companion object {
        private val VERSION = Regex("""(\d+)\.(\d+)\.(\d+)((?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?)""")

        fun fromText(
            text: String,
            baseUri: String = "memory://anonymous",
            options: AsyncApiParserOptions = AsyncApiParserOptions(),
        ): AsyncApiParser = AsyncApiParser(
            uri = baseUri,
            options = options,
            delegate = RefParser.fromText(
                text = text,
                baseUri = baseUri,
                options = options.refParserOptions,
            ),
        )
    }
}

private fun copyDocumentGraph(document: ParsedDocument): ParsedDocument {
    val copier = JsonGraphCopier()
    val root = copier.copy(document.root)
    return document.copy(
        root = root,
        schema = root.asStringMap() ?: emptyMap(),
        locations = document.locations.toMap(),
        documentLocations = document.documentLocations.mapValues { it.value.toMap() },
        resolvedRefs = document.resolvedRefs.map { resolvedRef ->
            resolvedRef.copy(
                resolvedTo = copier.copy(resolvedRef.resolvedTo),
                replacedValue = copier.copy(resolvedRef.replacedValue),
            )
        },
        originalAllOfs = document.originalAllOfs.map { originalAllOf ->
            originalAllOf.copy(
                mergedMap = requireNotNull(copier.copy(originalAllOf.mergedMap).asStringMap()),
                allOfItems = requireNotNull(copier.copy(originalAllOf.allOfItems) as? List<Any?>),
            )
        },
    )
}
