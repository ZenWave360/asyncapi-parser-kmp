package io.zenwave360.asyncapi

import io.zenwave360.jsonrefparser.AuthenticationValue as JavaAuthenticationValue
import io.zenwave360.jsonrefparser.io.ClasspathLoader
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.AuthenticationType
import io.zenwave360.jsonrefparser.model.AuthenticationValue as CoreAuthenticationValue
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking

class JavaAsyncApiParser private constructor(
    private val parser: AsyncApiParser,
) {
    fun withAuthentication(vararg auth: JavaAuthenticationValue): JavaAsyncApiParser {
        val values = auth.map { value ->
            CoreAuthenticationValue(
                key = requireNotNull(value.key) { "key must be provided" },
                value = requireNotNull(value.value) { "value must be provided" },
                type = when (value.type) {
                    JavaAuthenticationValue.AuthenticationType.QUERY -> AuthenticationType.QUERY
                    JavaAuthenticationValue.AuthenticationType.HEADER -> AuthenticationType.HEADER
                },
                urlMatcher = { url -> value.matches(URI(url).toURL()) },
                urlPatterns = emptyList(),
            )
        }
        parser.withAuthentication(*values.toTypedArray())
        return this
    }

    fun withAuthenticationValues(vararg auth: CoreAuthenticationValue): JavaAsyncApiParser {
        parser.withAuthentication(*auth)
        return this
    }

    fun withLoaders(vararg loaders: DocumentLoader): JavaAsyncApiParser {
        parser.withLoaders(*loaders)
        return this
    }

    fun withDefaultLoaders(vararg loaders: DocumentLoader): JavaAsyncApiParser {
        parser.withDefaultLoaders(*loaders)
        return this
    }

    fun withResourceClassLoader(classLoader: ClassLoader?): JavaAsyncApiParser {
        parser.withDefaultLoaders(ClasspathLoader(classLoader))
        return this
    }

    fun parse(): JavaAsyncApiParser {
        runBlocking { parser.parse() }
        return this
    }

    fun dereference(): JavaAsyncApiParser {
        runBlocking { parser.dereference() }
        return this
    }

    fun mergeAllOf(): JavaAsyncApiParser {
        runBlocking { parser.mergeAllOf() }
        return this
    }

    fun applyTraits(): JavaAsyncApiParser {
        parser.applyTraits()
        return this
    }

    fun getDocument(): AsyncApiDocument = parser.getDocument()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun from(uri: String, options: AsyncApiParserOptions = AsyncApiParserOptions()): JavaAsyncApiParser =
            JavaAsyncApiParser(AsyncApiParser(uri, options))

        @JvmStatic
        @JvmOverloads
        fun from(uri: URI, options: AsyncApiParserOptions = AsyncApiParserOptions()): JavaAsyncApiParser =
            from(uri.toString(), options)

        @JvmStatic
        @JvmOverloads
        fun from(file: File, options: AsyncApiParserOptions = AsyncApiParserOptions()): JavaAsyncApiParser =
            from(file.toURI(), options)

        @JvmStatic
        @JvmOverloads
        fun fromText(
            text: String,
            baseUri: String = "memory://anonymous",
            options: AsyncApiParserOptions = AsyncApiParserOptions(),
        ): JavaAsyncApiParser =
            JavaAsyncApiParser(AsyncApiParser.fromText(text, baseUri, options))
    }
}
