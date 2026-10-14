package dev.androidapm.catalog

import java.security.MessageDigest

/** Human-readable output. The lossless v1 facts remain private inputs to the linker. */
object CatalogReport {
    fun create(catalog: Record): Record = Report(catalog).create()

    private class Report(val catalog: Record) {
        val declarations = catalog.records("composableDeclarations").associateBy { it.string("symbol") }
        val functions = catalog.records("functions").associateBy { it.string("symbol") }
        val calls = catalog.records("callRelations").filter { it["composable"] == true }.groupBy { it.string("owner") }
        val composables = linkedMapOf<String, Record>()

        fun create(): Record {
            val roots = catalog.records("uiRoots").associateBy { it.string("id") }
            val hosts = catalog.records("navHosts").associateBy { it.string("id") }
            val modules = catalog["scope"].record().records("modules").ifEmpty {
                listOf(record("moduleId" to catalog["moduleId"], "scope" to catalog["scope"]))
            }.sortedBy { it.string("moduleId") }
            val rootBindings = catalog.records("uiRootBindings").map { binding ->
                record("id" to binding["rootId"], "source" to source(roots[binding["rootId"]]?.get("source")),
                    "resolution" to binding["resolution"], "navHostIds" to binding["navHostIds"],
                    "contentCalls" to contentCalls(binding["content"].record()),
                    "composableIds" to references(binding))
            }
            val hostBindings = catalog.records("navHostBindings").map { binding ->
                record("id" to binding["hostId"], "source" to source(hosts[binding["hostId"]]?.get("source")),
                    "resolution" to binding["resolution"], "providerIds" to binding["providerIds"])
            }
            val entries = catalog.records("entryBindings").map { binding ->
                record("id" to binding["id"], "source" to source(binding["source"]),
                    "providerId" to binding["providerId"], "context" to binding["context"],
                    "classification" to binding["classification"], "resolution" to binding["resolution"],
                    "keyCandidates" to binding.records("keyCandidates").ifEmpty { listOf(binding["key"].record()) }.map(::expression),
                    "contentCalls" to contentCalls(binding["content"].record()),
                    "composableIds" to references(binding)) + conditions(binding)
            }
            val candidates = catalog.records("screenCandidates").sortedWith(
                compareBy({ it["source"].record().string("path") }, { (it["source"].record()["line"] as? Number)?.toInt() ?: 0 }, { it.string("id") }),
            ).map { candidate ->
                record("id" to candidate["id"], "source" to source(candidate["source"]),
                    "classification" to candidate["classification"], "resolution" to candidate["resolution"],
                    "arguments" to candidate.records("arguments").filter { it["composableType"] != true }
                        .associate { it.string("name") to argument(it["value"].record()) },
                    "contentCalls" to candidate.records("arguments").filter {
                        it["composableType"] == true && it.string("name") == candidate.string("id").substringAfterLast(':')
                    }
                        .flatMap { contentCalls(it["value"].record()) }.distinct(),
                    "composableIds" to references(candidate))
            }
            val diagnostics = catalog.records("diagnostics")
            val referencedCount = composables.size
            val unlinked = declarations.values.filter { it.string("id") !in composables }.map { it.string("id") }.sorted()
            declarations.values.forEach { declaration ->
                composables.putIfAbsent(declaration.string("id"), record("id" to declaration["id"],
                    "name" to (declaration["name"] ?: "<anonymous>"), "source" to source(declaration["source"])))
            }
            return record(
                "schemaVersion" to 2,
                "moduleId" to catalog["moduleId"], "variant" to catalog["variant"],
                "collectionStatus" to catalog["collectionStatus"], "resolutionStatus" to catalog["resolutionStatus"],
                "summary" to record("modules" to modules.size,
                    "sourceFiles" to modules.sumOf { (it["scope"].record()["files"] as? List<*>)?.size ?: 0 },
                    "collectedComposables" to declarations.size, "referencedComposables" to referencedCount,
                    "unlinkedComposables" to unlinked.size,
                    "entryDeclarations" to catalog.records("entryDeclarations").size,
                    "entryBindings" to entries.size, "screenCandidates" to candidates.size,
                    "diagnostics" to diagnostics.sumOf { (it["count"] as? Number)?.toInt() ?: 1 }),
                "modules" to modules.map { it.string("moduleId") },
                "missingModules" to ((catalog["missingModules"] as? List<*>) ?: emptyList<String>()),
                "screenCandidates" to candidates,
                "entryBindings" to entries,
                "uiRoots" to rootBindings,
                "navHosts" to hostBindings,
                "providers" to catalog.records("providers").map { record("id" to it["id"], "source" to source(it["source"])) },
                "composables" to composables.values.sortedWith(compareBy({ it.string("name") }, { it.string("id") })),
                "unlinkedComposableIds" to unlinked,
                "analysis" to (catalog["analysis"] ?: record("engine" to "IR_ONLY", "contentModels" to ContentSemantics.version)),
                "diagnostics" to diagnostics.groupBy { it.string("code") }.toSortedMap().map { (code, items) ->
                    record("code" to code, "count" to items.sumOf { (it["count"] as? Number)?.toInt() ?: 1 },
                        "sources" to items.mapNotNull { source(it["source"]) }.distinct().sortedBy(Json::encode))
                },
            )
        }

        /** Preserve overload identities, but store each declaration only once across every binding. */
        fun references(binding: Record): List<String> = binding.records("composableCalls").map { call ->
            val symbol = call.string("symbol")
            val declaration = declarations[symbol]
            val id = declaration?.string("id") ?: MessageDigest.getInstance("SHA-256")
                .digest(symbol.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
            composables.getOrPut(id) {
                record("id" to id, "name" to call["name"]) +
                    if (declaration == null) record("external" to true)
                    else record("source" to source(declaration["source"]))
            }
            id
        }.distinct().sortedBy { composables.getValue(it).string("name") + it }

        fun conditions(binding: Record): Record = binding.records("conditions").takeIf { it.isNotEmpty() }
            ?.let { record("conditions" to it.map(::expression)) } ?: emptyMap()

        fun argument(value: Record): Any? = if (value["kind"] == "CONSTANT") value["value"] else expression(value)

        /** These are direct calls in the content function, not a claim that the first one is a screen. */
        fun contentCalls(value: Record): List<String> = when (value["kind"]) {
            "FUNCTION" -> declarations[value.string("ref")]?.string("name")?.takeIf { it.isNotEmpty() }?.let { listOf(it) }
                ?: calls[value.string("ref")].orEmpty().map { it.string("name") }.distinct().sorted()
            "CHOICE", "LIST" -> value.records("values").flatMap(::contentCalls).distinct().sorted()
            else -> emptyList()
        }

        /** A description, not an evaluated expression. Never expose orphaned raw call/ref IDs. */
        fun expression(value: Record): Record {
            val kind = value.string("kind").ifEmpty { "UNKNOWN" }
            val result = linkedMapOf<String, Any?>("kind" to kind)
            when (kind) {
                "CONSTANT" -> { result["value"] = value["value"]; value["type"]?.let { result["type"] = it } }
                "TYPE" -> {
                    result["type"] = value["type"]
                    value["typeParameter"]?.let { result["typeParameter"] = it.toString().substringAfterLast("#type:") }
                }
                "OBJECT" -> result["name"] = value["ref"]
                "LIST", "CHOICE" -> result["values"] = value.records("values").map(::expression)
                "BRANCH" -> { result["test"] = expression(value["test"].record()); result["excluded"] = value.records("excluded").map(::expression) }
                "FUNCTION" -> functions[value.string("ref")]?.get("name")?.let { result["name"] = it }
                "CALL", "CONSTRUCTOR" -> result["name"] = value["name"]
                "REFERENCE" -> {
                    result["name"] = value["property"] ?: value.string("ref").substringAfterLast('#').substringAfterLast(':').substringBefore('@')
                    value["type"]?.let { result["type"] = it }
                }
            }
            source(value["source"])?.let { result["source"] = it }
            return result
        }

        fun source(value: Any?): Record? = value.record().takeIf { it.string("path").isNotEmpty() }?.let {
            record("path" to it["path"], "line" to it["line"])
        }
    }
}
