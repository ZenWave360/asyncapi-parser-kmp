package io.zenwave360.asyncapi

/**
 * Minimal, Kotlin-Multiplatform-safe JSONPath selector used to locate trait/message target nodes in
 * the parsed map/list graph. Supports the subset of JSONPath needed to address AsyncAPI locations:
 * property access (`$.a.b`), bracketed keys (`$['a']`), array/object wildcard (`[*]`), array index
 * (`[0]`) and recursive descent (`..`). It intentionally does NOT support filter predicates
 * (`[?(...)]`) — trait/message locations never need them.
 *
 * Matches are returned as live references into the source graph, so callers can mutate the selected
 * maps in place. Adapted from the shared implementation in the ZenWave DSL tooling.
 */
internal object JsonPathSelector {

    /** Returns every node matched by [path], each a live reference into [root]. */
    fun select(root: Any?, path: String): List<Any?> {
        val result = get(root, path) ?: return emptyList()
        return when (result) {
            is List<*> -> result
            else -> listOf(result)
        }
    }

    /** Returns every mutable-map node matched by [path], deduplicated by identity. */
    fun selectMaps(root: Any?, path: String): List<MutableMap<String, Any?>> {
        val seen = IdentitySet()
        val output = mutableListOf<MutableMap<String, Any?>>()
        select(root, path).forEach { match ->
            match.asMutableStringMap()?.let { if (seen.add(it)) output += it }
        }
        return output
    }

    private fun get(source: Any?, path: String): Any? {
        if (source == null) return null

        val normalizedPath = when {
            path.startsWith("$..") -> path.substring(1)
            path.startsWith("$.") -> path.substring(2)
            path.startsWith("$") -> path.substring(1)
            else -> path
        }

        val segments = parsePath(normalizedPath)
        return try {
            evaluatePath(source, segments)
        } catch (e: Exception) {
            null
        }
    }

    private fun parsePath(path: String): List<String> {
        if (path.isEmpty()) return emptyList()

        val segments = mutableListOf<String>()
        var current = ""
        var i = 0

        while (i < path.length) {
            when {
                i < path.length - 1 && path[i] == '.' && path[i + 1] == '.' -> {
                    if (current.isNotEmpty()) {
                        segments.add(current)
                        current = ""
                    }
                    segments.add("")
                    i += 2
                    continue
                }
                path[i] == '.' -> {
                    if (current.isNotEmpty()) {
                        segments.add(current)
                        current = ""
                    }
                }
                path[i] == '[' -> {
                    if (current.isNotEmpty()) {
                        segments.add(current)
                        current = ""
                    }
                    val closingBracket = path.indexOf(']', i)
                    if (closingBracket != -1) {
                        val bracketContent = path.substring(i + 1, closingBracket)
                        val segment = if (bracketContent.startsWith("'") && bracketContent.endsWith("'")) {
                            bracketContent.substring(1, bracketContent.length - 1)
                        } else {
                            "[$bracketContent]"
                        }
                        segments.add(segment)
                        i = closingBracket
                    } else {
                        current += path[i]
                    }
                }
                else -> current += path[i]
            }
            i++
        }

        if (current.isNotEmpty()) {
            segments.add(current)
        }

        return segments
    }

    private fun evaluatePath(current: Any?, segments: List<String>): Any? {
        if (current == null || segments.isEmpty()) return current

        val segment = segments.first()
        val remaining = segments.drop(1)

        return when {
            segment == "" -> {
                val results = mutableListOf<Any?>()
                if (remaining.isNotEmpty()) {
                    collectRecursive(current, remaining, results)
                } else {
                    results.add(current)
                }
                results.flatMap { result ->
                    when (result) {
                        is List<*> -> result
                        else -> listOf(result)
                    }
                }
            }
            segment == "[*]" -> when (current) {
                is List<*> -> if (remaining.isEmpty()) {
                    current
                } else {
                    current.mapNotNull { evaluatePath(it, remaining) }.flatMap { flatten(it) }
                }
                is Map<*, *> -> if (remaining.isEmpty()) {
                    current.values.toList()
                } else {
                    current.values.mapNotNull { evaluatePath(it, remaining) }.flatMap { flatten(it) }
                }
                else -> null
            }
            segment.startsWith("[") && segment.endsWith("]") -> {
                val index = segment.removeSurrounding("[", "]").toIntOrNull()
                if (index != null && current is List<*>) {
                    evaluatePath(current.getOrNull(index), remaining)
                } else {
                    null
                }
            }
            else -> when (current) {
                is Map<*, *> -> evaluatePath(current[segment], remaining)
                else -> null
            }
        }
    }

    private fun flatten(result: Any?): List<Any?> = when (result) {
        is List<*> -> result
        else -> listOf(result)
    }

    private fun collectRecursive(current: Any?, segments: List<String>, results: MutableList<Any?>) {
        if (current == null) return

        val directResult = evaluatePath(current, segments)
        if (directResult != null) {
            when (directResult) {
                is List<*> -> results.addAll(directResult)
                else -> results.add(directResult)
            }
        }

        when (current) {
            is Map<*, *> -> current.values.forEach { collectRecursive(it, segments, results) }
            is List<*> -> current.forEach { collectRecursive(it, segments, results) }
        }
    }
}
