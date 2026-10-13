package dev.androidapm.catalog

import java.security.MessageDigest

/** Links static facts. Candidate sets and unknown dynamic dispatch are never called resolved. */
object CatalogLinker {
    fun link(modules: List<Record>): Record = Linker(modules).link()

    private class Linker(val modules: List<Record>) {
        val kinds = listOf("functions", "composableDeclarations", "values", "callRelations", "entryDeclarations", "providers", "uiRoots", "navHosts", "contentContainers", "diBindings", "diagnostics")
        val data = kinds.associateWith { kind -> modules.flatMap { it.records(kind) }.distinctBy { it["id"] ?: it["ref"] ?: it["symbol"] ?: it } }
        val functions = data.getValue("functions").associateBy { it.string("symbol") }
        val values = data.getValue("values").associateBy { it.string("ref") }
        val returns = data.getValue("values").filter { it.containsKey("returnOf") }.groupBy { it.string("returnOf") }
        val calls = data.getValue("callRelations").groupBy { it.string("owner") }
        val callById = data.getValue("callRelations").associateBy { it.string("id") }
        val containerIds = data.getValue("contentContainers").map { it.string("id") }.toSet()
        val entries = data.getValue("entryDeclarations").associateBy { it.string("id") }
        val diagnostics = modules.flatMap { it.records("collectionDiagnostics") }.toMutableList()
        val bindings = mutableListOf<Record>()

        fun link(): Record {
            val attached = mutableSetOf<String>()
            val directProviders = mutableListOf<Record>()
            data.getValue("providers").forEach { provider ->
                val builder = provider.records("arguments").firstOrNull { it.string("name") == "builder" }?.get("value").record()
                traverse(builder, emptyMap()) { call, env ->
                    val entry = entries[call.string("id")] ?: return@traverse
                    attached += entry.string("id")
                    bind(entry, provider.string("id"), env)
                }
                val fallback = provider.records("arguments").firstOrNull { it.string("name") == "fallback" }?.get("value").record()
                traverse(fallback, emptyMap()) { call, env ->
                    val entry = entries[call.string("id")] ?: return@traverse
                    attached += entry.string("id")
                    bind(entry, provider.string("id"), env, "FALLBACK")
                }
            }
            val hostBindings = data.getValue("navHosts").map { host ->
                val expression = host.records("arguments").firstOrNull { it.string("name") == "entryProvider" }?.get("value").record()
                val providerIds = providerReferences(expression).toMutableSet()
                if (providerIds.isEmpty()) {
                    val directId = host.string("id") + ":direct-provider"
                    traverse(expression, emptyMap()) { call, env ->
                        entries[call.string("id")]?.takeIf { it["api"] == "NAV_ENTRY" }?.let { entry ->
                            attached += entry.string("id")
                            bind(entry, directId, env, "DIRECT_PROVIDER")
                            providerIds += directId
                        }
                    }
                    if (providerIds.isNotEmpty()) directProviders += record("id" to directId, "kind" to "DIRECT_PROVIDER", "source" to host["source"], "content" to expression)
                }
                if (providerIds.isEmpty()) diagnostics += record("code" to "UNRESOLVED_NAV_HOST_PROVIDER", "source" to host["source"])
                record("hostId" to host["id"], "providerIds" to providerIds.sorted(), "resolution" to if (providerIds.isEmpty()) "PARTIAL" else "RESOLVED")
            }
            entries.values.filter { it.string("id") !in attached }.forEach { bind(it, null, emptyMap()) }
            val rootBindings = data.getValue("uiRoots").map { root ->
                val expression = root.records("arguments").firstOrNull { it.string("name") == "content" }?.get("value").record()
                val (composables, status) = content(expression, emptyMap())
                record("rootId" to root["id"], "content" to expression, "composableCalls" to composables,
                    "navHostIds" to composables.filter { call -> data.getValue("navHosts").any { it["id"] == call["callId"] } }.map { it["callId"] },
                    "resolution" to status)
            }
            val candidates = data.getValue("contentContainers").flatMap { container ->
                container.records("contentArguments").map { argument ->
                    val result = content(argument["value"].record(), emptyMap())
                    record("id" to container.string("id") + ":" + argument.string("name"),
                        "kind" to "CONTENT_CONTAINER", "source" to container["source"],
                        "constructor" to container["name"], "arguments" to container["arguments"],
                        "composableCalls" to result.first, "resolution" to result.second,
                        "classification" to "UNCLASSIFIED", "reason" to "Content storage does not establish a screen boundary or provider binding")
                }
            }
            val base = if (modules.size == 1) modules.single() else record(
                "schemaVersion" to 1, "variant" to modules.firstOrNull()?.get("variant"),
                "moduleId" to "<project>", "collectionStatus" to "COMPLETE_FOR_DECLARED_INPUTS",
                "scope" to record("modules" to modules.map { record("moduleId" to it["moduleId"], "scope" to it["scope"]) }),
            )
            return base + data + record(
                "entryBindings" to bindings.distinctBy { Json.encode(it) }.sortedBy { it.string("id") },
                "providers" to (data.getValue("providers") + directProviders).sortedBy { it.string("id") },
                "navHostBindings" to hostBindings,
                "uiRootBindings" to rootBindings,
                "registrationFunctions" to entries.values.groupBy { it.string("owner") }.toSortedMap().map { (symbol, declarations) ->
                    record("symbol" to symbol, "declarationIds" to declarations.map { it.string("id") }.sorted())
                },
                "contentRelations" to data.getValue("callRelations").flatMap { call ->
                    call.records("arguments").filter { it["composableType"] == true }.map { argument ->
                        record("callId" to call["id"], "parameter" to argument["parameter"], "content" to argument["value"])
                    }
                },
                "screenCandidates" to candidates.sortedBy { it.string("id") },
                "collectionDiagnostics" to modules.flatMap { it.records("collectionDiagnostics") },
                "resolutionStatus" to if (bindings.all { it["resolution"] == "RESOLVED" } && diagnostics.isEmpty()) "RESOLVED" else "PARTIAL",
                "diagnostics" to diagnostics.distinctBy { Json.encode(it) }.sortedBy { Json.encode(it) },
            )
        }

        fun providerReferences(expression: Record, seen: Set<String> = emptySet()): Set<String> {
            if (seen.size > 40) return emptySet()
            val ref = expression.string("ref")
            val target = expression.string("target")
            return when (expression.string("kind")) {
                "CALL" -> if (expression["name"] == "androidx.navigation3.runtime.entryProvider") setOf(expression.string("callId"))
                    else if (target in seen) emptySet() else returns[target].orEmpty().flatMap { providerReferences(it["value"].record(), seen + target) }.toSet()
                "REFERENCE" -> if (ref in seen) emptySet() else providerReferences(values[ref]?.get("value").record(), seen + ref)
                "CHOICE" -> expression.records("values").flatMap { providerReferences(it, seen) }.toSet()
                else -> emptySet()
            }
        }

        fun bind(entry: Record, provider: String?, env: Map<String, Record>, context: String = "REGISTRATION") {
            val (composables, status) = content(entry["content"].record(), env)
            val keyCandidates = resolve(entry["key"].record(), env)
            val unresolvedKey = keyCandidates.isEmpty() || keyCandidates.any { it["typeParameter"] != null }
            val resolution = if (provider == null || status != "RESOLVED" || unresolvedKey) "PARTIAL" else "RESOLVED"
            val contextId = MessageDigest.getInstance("SHA-256").digest(Json.encode(env).toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
            bindings += record("id" to "${provider ?: "unbound"}:${entry.string("id")}:$context:$contextId",
                "providerId" to provider, "declarationId" to entry["id"], "context" to context,
                "classification" to if (provider != null) "NAV3_DESTINATION_CONTENT" else "UNCLASSIFIED",
                "key" to entry["key"], "content" to entry["content"], "composableCalls" to composables,
                "keyCandidates" to keyCandidates,
                "registrationArguments" to env,
                "resolution" to resolution, "conditions" to entry["conditions"], "source" to entry["source"])
            if (provider == null) diagnostics += record("code" to "UNRESOLVED_PROVIDER", "declarationId" to entry["id"], "source" to entry["source"])
            if (unresolvedKey) diagnostics += record("code" to "UNRESOLVED_KEY", "declarationId" to entry["id"], "source" to entry["source"])
        }

        fun content(expression: Record, env: Map<String, Record>): Pair<List<Record>, String> {
            val found = linkedMapOf<String, Record>()
            var partial = false
            val invoked = traverse(expression, env, { code, source ->
                partial = true
                diagnostics += record("code" to code, "source" to source)
            }) { call, _ ->
                if (call["composable"] == true) found[call.string("id")] = record(
                    "callId" to call["id"],
                    "symbol" to call["target"], "name" to call["name"], "source" to call["source"], "conditions" to call["conditions"],
                    "declarationAvailable" to functions.containsKey(call.string("target")))
            }
            if (!invoked) partial = true
            return found.values.sortedBy { it.string("symbol") + Json.encode(it["source"]) } to if (partial) "PARTIAL" else "RESOLVED"
        }

        fun resolve(expr: Record, env: Map<String, Record>, seen: Set<String> = emptySet()): List<Record> {
            if (seen.size > 40) return emptyList()
            return when (expr.string("kind")) {
                "TYPE" -> if (expr.string("typeParameter") in seen) listOf(expr)
                    else env[expr.string("typeParameter")]?.let { resolve(it, env, seen + expr.string("typeParameter")) } ?: listOf(expr)
                "FUNCTION", "OBJECT", "CONSTANT", "CONSTRUCTOR" -> listOf(expr)
                "LIST", "CHOICE" -> expr.records("values").flatMap { resolve(it, env, seen) }
                "REFERENCE" -> {
                    val ref = expr.string("ref")
                    if (ref in seen || values[ref]?.get("mutable") == true) emptyList() else {
                        val receiver = expr["receiver"].record()
                        if (receiver.isNotEmpty()) {
                            resolve(receiver, env, seen + ref).flatMap { value ->
                                arguments(value).filter { it.string("name") == expr.string("property") }
                                    .flatMap { resolve(it["value"].record(), env, seen + ref) }
                            }
                        } else (env[ref] ?: values[ref]?.get("value").record()).let { resolve(it, env, seen + ref) }
                    }
                }
                "CALL" -> {
                    val target = expr.string("target")
                    val name = expr.string("name")
                    val args = arguments(expr)
                    if (target in seen) emptyList()
                    else if (name in setOf("kotlin.collections.listOf", "kotlin.collections.setOf", "kotlin.collections.arrayListOf")) {
                        args.flatMap { resolve(it["value"].record(), env, seen + target) }
                    } else if (name in setOf("kotlin.collections.associateBy", "kotlin.collections.Map.get")) {
                        args.firstOrNull()?.get("value").record().let { resolve(it, env, seen + target) }
                    } else {
                        val bound = env + args.associate { it.string("parameter") to substitute(it["value"].record(), env) }
                        returns[target].orEmpty().flatMap { resolve(it["value"].record(), bound, seen + target) }
                    }
                }
                else -> emptyList()
            }
        }

        private fun arguments(expression: Record): List<Record> =
            expression.records("arguments").ifEmpty { callById[expression.string("callId")]?.records("arguments").orEmpty() }

        private fun substitute(expr: Record, env: Map<String, Record>): Record =
            if (expr.string("kind") == "REFERENCE") env[expr.string("ref")] ?: expr else expr

        fun traverse(
            expression: Record, initial: Map<String, Record>,
            problem: (String, Any?) -> Unit = { _, _ -> },
            visit: (Record, Map<String, Record>) -> Unit,
        ): Boolean {
            val seen = mutableSetOf<String>()
            var started = false
            var steps = 0
            fun run(ref: String, env: Map<String, Record>, depth: Int) {
                if (depth > 40 || ++steps > 5000) { problem("ANALYSIS_LIMIT_REACHED", functions[ref]?.get("source")); return }
                val signature = ref + Json.encode(env)
                if (!seen.add(signature)) return
                val function = functions[ref] ?: return
                started = true
                calls[ref].orEmpty().forEach { call ->
                    visit(call, env)
                    // Registration and storage do not invoke content. It has its own binding traversal.
                    if (call.string("id") in entries || call.string("name") == "androidx.navigation3.runtime.entryProvider" ||
                        call.string("id") in containerIds) return@forEach
                    val arguments = call.records("arguments")
                    val target = call.string("target")
                    val name = call.string("name")
                    val bound = env + arguments.associate { it.string("parameter") to substitute(it["value"].record(), env) } +
                        call.records("typeBindings").associate { it.string("parameter") to (resolve(it["value"].record(), env).singleOrNull() ?: it["value"].record()) }
                    if (name.endsWith(".invoke")) {
                        val receiver = arguments.firstOrNull()?.get("value").record()
                        val choices = resolve(receiver, env)
                        if (choices.isEmpty()) {
                            if (call["composable"] == true || receiver.string("type").contains("Composable")) problem("UNRESOLVED_CONTENT_FLOW", call["source"])
                        } else {
                            if (choices.size > 1) problem("DYNAMIC_CONTENT_CANDIDATES", call["source"])
                            choices.filter { it.string("kind") == "FUNCTION" }.forEach { run(it.string("ref"), bound, depth + 1) }
                        }
                    } else if (target in functions) {
                        run(target, bound, depth + 1)
                    } else {
                        arguments.filter { it["composableType"] == true || name in inlineScopeFunctions }.forEach { arg ->
                            resolve(arg["value"].record(), env).filter { it.string("kind") == "FUNCTION" }.forEach {
                                if (name !in inlineScopeFunctions && !name.startsWith("androidx.compose.")) problem("UNRESOLVED_EXTERNAL_CONTENT_SLOT", call["source"])
                                run(it.string("ref"), bound, depth + 1)
                            }
                        }
                    }
                }
            }
            val choices = resolve(expression, initial)
            if (choices.isEmpty() && expression.isNotEmpty()) problem("UNRESOLVED_CONTENT_FLOW", expression["source"])
            choices.filter { it.string("kind") == "FUNCTION" }.forEach { run(it.string("ref"), initial, 0) }
            return started
        }

        val inlineScopeFunctions = setOf("kotlin.let", "kotlin.run", "kotlin.with", "kotlin.also", "kotlin.apply", "kotlin.collections.forEach", "kotlin.collections.map")
    }
}
