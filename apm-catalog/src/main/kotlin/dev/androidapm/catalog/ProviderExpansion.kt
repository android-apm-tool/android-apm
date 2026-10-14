package dev.androidapm.catalog

/** Finite map selection derived from key-field -> Map.get -> stored content invocation. */
internal object ProviderExpansion {
    fun expand(binding: Record, host: String, flow: FlowValues): List<Record> {
        val fn = binding["content"].record()
        if (fn["kind"] != "FUNCTION") return emptyList()
        val declaration = flow.functions[fn.string("ref")] ?: return emptyList()
        val parameters = declaration.records("parameters")
        if (parameters.size != 1) return emptyList()
        val keyType = binding.records("keyCandidates").singleOrNull()?.takeIf { it["kind"] == "TYPE" }?.string("type") ?: return emptyList()
        val lookups = flow.calls.values.filter { it["owner"] == fn["ref"] && it["name"] in setOf("kotlin.collections.Map.get", "kotlin.collections.MutableMap.get") }
        return lookups.flatMap { lookup ->
            val args = lookup.records("arguments")
            if (args.size != 2) return@flatMap emptyList()
            val selector = args.last()["value"].record()
            if (selector["kind"] != "REFERENCE" || selector["receiver"].record()["ref"] != parameters.single()["ref"] || selector.string("property").isEmpty()) return@flatMap emptyList()
            val map = flow.eval(args.first()["value"].record())
            if (map["kind"] != "MAP_VALUE") return@flatMap emptyList()
            map.records("entries").mapNotNull { entry ->
                val key = entry["key"].record()
                val value = entry["value"].record()
                if (key["kind"] != "CONSTANT" || value["kind"] != "INSTANCE") return@mapNotNull null
                val constructor = flow.functions.values.singleOrNull { it["constructor"] == true && it["classOwner"] == value["type"] && it.records("parameters").any { p -> p["composableType"] == true } }
                    ?: return@mapNotNull null
                val contentFields = constructor.records("parameters").filter { it["composableType"] == true }.map { it.string("name") }
                val renderRefs = contentFields.mapNotNull { value["fields"].record()[it]?.record()?.string("ref") }.filter { it.isNotEmpty() }
                if (renderRefs.isEmpty()) return@mapNotNull null
                val fields = flow.classes[keyType]?.records("fields").orEmpty().associate { p ->
                    p.string("name") to record("kind" to "SYMBOLIC", "ref" to "key:" + key["value"] + ":" + p["name"], "type" to p["type"])
                } + (selector.string("property") to key)
                val contextId = "context:" + fingerprint(record("binding" to binding["id"], "host" to host, "selector" to selector["property"], "key" to key))
                record("id" to contextId, "bindingRef" to binding["id"], "hostRef" to host,
                    "keyPatterns" to listOf(record("kind" to "FIELD_KEY", "type" to keyType, "field" to selector["property"], "value" to key)),
                    "content" to fn, "entryArguments" to listOf(record("kind" to "INSTANCE", "type" to keyType, "identity" to "entry:" + contextId, "fields" to fields)),
                    "expectedRenderRefs" to renderRefs, "providerLookupRef" to lookup["id"], "definitionStatus" to "LOOKUP_REQUIRES_RENDER_PROOF",
                    "source" to constructor["source"])
            }
        }.distinctBy { it["id"] }
    }
}
