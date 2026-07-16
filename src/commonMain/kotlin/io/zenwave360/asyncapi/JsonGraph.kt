package io.zenwave360.asyncapi

internal class JsonGraphCopier {
    private val copies = mutableListOf<Pair<Any, Any>>()

    fun copy(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value
        is Map<*, *> -> copyMap(value)
        is List<*> -> copyList(value)
        else -> throw IllegalArgumentException(
            "Unsupported JSON runtime type: ${value::class.simpleName ?: value::class.toString()}",
        )
    }

    private fun copyMap(source: Map<*, *>): MutableMap<String, Any?> {
        existing(source)?.let { @Suppress("UNCHECKED_CAST") return it as MutableMap<String, Any?> }
        val output = linkedMapOf<String, Any?>()
        copies += source to output
        source.forEach { (key, value) ->
            require(key is String) { "JSON object keys must be strings" }
            output[key] = copy(value)
        }
        return output
    }

    private fun copyList(source: List<*>): MutableList<Any?> {
        existing(source)?.let { @Suppress("UNCHECKED_CAST") return it as MutableList<Any?> }
        val output = mutableListOf<Any?>()
        copies += source to output
        source.forEach { output += copy(it) }
        return output
    }

    private fun existing(source: Any): Any? =
        copies.firstOrNull { it.first === source }?.second
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asStringMap(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMutableStringMap(): MutableMap<String, Any?>? =
    this as? MutableMap<String, Any?>

internal fun escapePointerToken(value: String): String =
    value.replace("~", "~0").replace("/", "~1")

internal fun pointerChild(pointer: String, token: String): String =
    "$pointer/${escapePointerToken(token)}"

internal fun rootSourceUri(document: AsyncApiDocument): String =
    document.source.locations[""]?.file
        ?: document.source.documentLocations.keys.firstOrNull()
        ?: "memory://anonymous"

internal class IdentitySet {
    private val values = mutableListOf<Any>()
    fun add(value: Any): Boolean {
        if (values.any { it === value }) return false
        values += value
        return true
    }
}
