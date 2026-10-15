package dev.androidapm.catalog

/** Concrete, bounded input witnesses. Manifest declarations alone never link a destination. */
object ExternalIntents {
    fun candidates(entry: Record): List<Record> {
        if (entry["kind"] != "BROWSABLE_INTENT" || listOf("enabled", "applicationEnabled", "exported").any { entry[it] != "true" } ||
            entry["launchMode"] !in setOf("singleTask", "singleTop") || entry.string("permission").isNotEmpty()) return emptyList()
        val filter = entry["filter"].record()
        if ("android.intent.action.VIEW" !in filter.strings("actions") || !filter.strings("categories").containsAll(listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"))) return emptyList()
        val data = filter.records("data")
        if (data.any { row -> row.keys.any { it !in setOf("scheme", "host", "port", "path", "pathPrefix") } }) return emptyList()
        if (data.any { it.containsKey("port") && it.string("host").isEmpty() }) return emptyList()
        val schemes = data.map { it.string("scheme") }.filter { it.matches(Regex("[a-z][a-z0-9+.-]*")) }.distinct()
        val hosts = data.filter { it.string("host").matches(Regex("[a-zA-Z0-9.-]+")) }.map { it.string("host") to it.string("port") }.distinct()
        val paths = data.flatMap { row ->
            listOfNotNull(row.string("path").takeIf { it.startsWith('/') }, row.string("pathPrefix").takeIf { it.startsWith('/') }?.let { it.trimEnd('/') + "/apm-example" })
        }.distinct().ifEmpty { if (data.none { it.containsKey("path") || it.containsKey("pathPrefix") }) listOf("/") else emptyList() }
        return schemes.flatMap { scheme -> hosts.flatMap { (host, port) -> paths.mapNotNull { path ->
            val url = "$scheme://$host" + if (port.isEmpty()) path else ":$port$path"
            uri(url)?.let { record("kind" to "INTENT", "action" to "android.intent.action.VIEW", "data" to it, "entryRef" to entry["id"]) }
        } } }.distinct().take(64)
    }
    fun uri(value: String): Record? = runCatching {
        val parsed = java.net.URI(value)
        require(!parsed.isOpaque && parsed.scheme != null && parsed.host != null && parsed.rawUserInfo == null && parsed.rawFragment == null)
        // Generated witnesses contain only simple ASCII paths. Percent decoding,
        // MIME and advanced patterns remain unsupported instead of being guessed.
        require('%' !in value && parsed.rawQuery == null)
        record("kind" to "URI", "value" to value, "scheme" to parsed.scheme, "host" to parsed.host, "path" to parsed.path,
            "segments" to parsed.path.split('/').filter { it.isNotEmpty() })
    }.getOrNull()

    internal fun value(name: String, args: Map<String, Record>): Record? {
        val receiver = args.values.firstOrNull().orEmpty()
        fun constant(v: Any?) = record("kind" to "CONSTANT", "value" to v)
        if (receiver["kind"] == "INTENT") return when (name) {
            "android.content.Intent.getData", "android.content.Intent.<get-data>" -> receiver["data"].record()
            "android.content.Intent.getAction", "android.content.Intent.<get-action>" -> constant(receiver["action"])
            else -> null
        }
        if (receiver["kind"] == "URI") return when (name) {
            "android.net.Uri.getScheme", "android.net.Uri.<get-scheme>" -> constant(receiver["scheme"])
            "android.net.Uri.getHost", "android.net.Uri.<get-host>" -> constant(receiver["host"])
            "android.net.Uri.getPath", "android.net.Uri.<get-path>" -> constant(receiver["path"])
            "android.net.Uri.toString" -> constant(receiver["value"])
            "android.net.Uri.getPathSegments", "android.net.Uri.<get-pathSegments>" -> record("kind" to "LIST_VALUE", "items" to receiver.strings("segments").map(::constant))
            "android.net.Uri.getQueryParameter" -> constant(null) // witnesses have no query
            else -> null
        }
        if (receiver["kind"] == "LIST_VALUE") {
            val items = receiver.records("items")
            return when (name) {
                "kotlin.collections.firstOrNull" -> if (args.size == 1) items.firstOrNull() ?: constant(null) else null
                "kotlin.collections.getOrNull" -> (args["index"]?.get("value") as? Number)?.toInt()?.let { items.getOrNull(it) ?: constant(null) }
                "kotlin.collections.isNotEmpty" -> constant(items.isNotEmpty())
                "kotlin.collections.Collection.isEmpty", "kotlin.collections.List.isEmpty" -> constant(items.isEmpty())
                else -> null
            }
        }
        if (receiver["kind"] == "CONSTANT" && receiver["value"] is String) {
            val text = receiver["value"] as String
            return when (name) {
                "kotlin.text.isNotBlank" -> constant(text.isNotBlank())
                "kotlin.text.isNotEmpty" -> constant(text.isNotEmpty())
                "kotlin.text.startsWith" -> (args["prefix"]?.get("value") as? String)?.takeIf { args["startIndex"] == null }?.let { prefix ->
                    val ignore = args["ignoreCase"] ?: constant(false)
                    if (ignore["kind"] == "CONSTANT" && ignore["value"] is Boolean) constant(text.startsWith(prefix, ignore["value"] as Boolean)) else null
                }
                "kotlin.text.equals" -> {
                    val other = args["other"]
                    val ignore = args["ignoreCase"] ?: constant(false)
                    if (other?.get("kind") == "CONSTANT" && (other["value"] == null || other["value"] is String) && ignore["kind"] == "CONSTANT" && ignore["value"] is Boolean)
                        constant(text.equals(other["value"] as String?, ignore["value"] as Boolean)) else null
                }
                else -> null
            }
        }
        return null
    }
}
