package dev.androidapm.catalog

/** Small dependency-free JSON codec so the compiler plugin remains a standalone JAR. */
object Json {
    fun encode(value: Any?): String = buildString { write(value, this) }

    /** Streams large catalogs without allocating recursively concatenated JSON copies. */
    fun write(value: Any?, output: Appendable, pretty: Boolean = false) = write(value, output, pretty, 0)

    private fun write(value: Any?, output: Appendable, pretty: Boolean, depth: Int) {
        fun newline(level: Int) {
            if (pretty) { output.append('\n'); repeat(level) { output.append("  ") } }
        }
        when (value) {
        null -> output.append("null")
        is String -> {
            output.append('"')
            var start = 0
            value.forEachIndexed { index, c ->
                val escaped = when (c) {
                    '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
                    else -> if (c.code < 32) "\\u%04x".format(c.code) else null
                }
                if (escaped != null) {
                    if (start < index) output.append(value, start, index)
                    output.append(escaped)
                    start = index + 1
                }
            }
            if (start < value.length) output.append(value, start, value.length)
            output.append('"')
        }
        is Boolean -> output.append(value.toString())
        is Number -> {
            require(value !is Double || value.isFinite()) { "Non-finite JSON number" }
            require(value !is Float || value.isFinite()) { "Non-finite JSON number" }
            output.append(value.toString())
        }
        is Map<*, *> -> {
            output.append('{')
            val entries = if (pretty) value.entries.toList() else value.entries.sortedBy { it.key.toString() }
            entries.forEachIndexed { index, entry ->
                if (index != 0) output.append(',')
                newline(depth + 1)
                write(entry.key.toString(), output, pretty, depth + 1)
                output.append(if (pretty) ": " else ":")
                write(entry.value, output, pretty, depth + 1)
            }
            if (entries.isNotEmpty()) newline(depth)
            output.append('}')
        }
        is Iterable<*> -> {
            output.append('[')
            var nonEmpty = false
            value.forEachIndexed { index, element ->
                if (index != 0) output.append(',')
                newline(depth + 1)
                write(element, output, pretty, depth + 1)
                nonEmpty = true
            }
            if (nonEmpty) newline(depth)
            output.append(']')
        }
        else -> error("Unsupported JSON value: ${value.javaClass.name}")
        }
    }

    fun decode(text: String, strings: MutableMap<String, String> = HashMap()): Any? = Reader(text, strings).run {
        val value = read()
        whitespace()
        require(index == text.length) { "Trailing JSON data" }
        value
    }

    private val numberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private class Reader(val text: String, val strings: MutableMap<String, String>) {
        var index = 0
        fun whitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
        private fun take(): Char { require(index < text.length) { "Unexpected end of JSON" }; return text[index++] }
        private fun expect(c: Char) { require(take() == c) { "Expected $c at $index" } }
        fun read(): Any? {
            whitespace()
            return when (val c = take()) {
                '"' -> string()
                '{' -> linkedMapOf<String, Any?>().apply {
                    whitespace()
                    if (text.getOrNull(index) == '}') { index++; return@apply }
                    while (true) {
                        whitespace(); expect('"'); val key = string(); whitespace(); expect(':')
                        require(!containsKey(key)) { "Duplicate JSON key: $key" }; put(key, read()); whitespace()
                        val separator = take(); if (separator == '}') break; require(separator == ',')
                    }
                }
                '[' -> mutableListOf<Any?>().apply {
                    whitespace()
                    if (text.getOrNull(index) == ']') { index++; return@apply }
                    while (true) { add(read()); whitespace(); val separator = take(); if (separator == ']') break; require(separator == ',') }
                }
                't' -> { literal("rue"); true }
                'f' -> { literal("alse"); false }
                'n' -> { literal("ull"); null }
                else -> {
                    require(c == '-' || c.isDigit()) { "Invalid JSON token at $index" }
                    val start = index - 1
                    while (text.getOrNull(index)?.let { it.isDigit() || it in ".eE+-" } == true) index++
                    val number = text.substring(start, index)
                    require(number.matches(numberPattern)) { "Invalid JSON number" }
                    number.toLongOrNull() ?: number.toDouble()
                }
            }
        }
        private fun literal(rest: String) { rest.forEach(::expect) }
        private fun string(): String = buildString {
            while (true) {
                val c = take()
                if (c == '"') break
                if (c != '\\') { require(c.code >= 32); append(c); continue }
                append(when (val escaped = take()) {
                    '"', '\\', '/' -> escaped
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000C'
                    'u' -> { val hex = (1..4).map { take() }.joinToString(""); hex.toInt(16).toChar() }
                    else -> error("Invalid JSON escape: $escaped")
                })
            }
        }.let { strings.getOrPut(it) { it } }
    }
}

typealias Record = Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Any?.record(): Record = this as? Record ?: emptyMap()
fun Record.records(key: String): List<Record> = (this[key] as? List<*>)?.map { it.record() } ?: emptyList()
fun Record.string(key: String): String = this[key] as? String ?: ""
fun record(vararg fields: Pair<String, Any?>): Record = linkedMapOf(*fields)
