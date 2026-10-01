package io.github.szymonbonkowski.myndhamr.reconstruction

/** Small dependency-free JSON encoder for the versioned reconstruction handoff files. */
internal fun reconstructionJson(value: Any?): String = buildString { appendJson(value) }

private fun StringBuilder.appendJson(value: Any?) {
    when (value) {
        null -> append("null")
        is String -> appendQuoted(value)
        is Boolean -> append(if (value) "true" else "false")
        is Byte, is Short, is Int, is Long -> append(value.toString())
        is Float -> { require(value.isFinite()) { "JSON numbers must be finite" }; append(value.toString()) }
        is Double -> { require(value.isFinite()) { "JSON numbers must be finite" }; append(value.toString()) }
        is Enum<*> -> appendQuoted(value.name)
        is Map<*, *> -> {
            append('{')
            value.entries.forEachIndexed { index, entry ->
                require(entry.key is String) { "JSON object keys must be strings" }
                if (index != 0) append(',')
                appendQuoted(entry.key as String); append(':'); appendJson(entry.value)
            }
            append('}')
        }
        is Iterable<*> -> {
            append('[')
            value.forEachIndexed { index, item -> if (index != 0) append(','); appendJson(item) }
            append(']')
        }
        is Array<*> -> appendJson(value.asList())
        is IntArray -> appendJson(value.asList())
        is LongArray -> appendJson(value.asList())
        is DoubleArray -> appendJson(value.asList())
        else -> error("Unsupported JSON value ${value.javaClass.name}")
    }
}

private fun StringBuilder.appendQuoted(value: String) {
    append('"')
    for (character in value) when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
    }
    append('"')
}
