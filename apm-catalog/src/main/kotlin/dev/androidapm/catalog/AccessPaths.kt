package dev.androidapm.catalog

/** Finite, path-sensitive stack exploration. A missing path never proves unreachability. */
object AccessPaths {
    fun find(transitions: Record, entries: Record, query: Record = emptyMap()): Record {
        require(transitions["schemaVersion"] == entries["schemaVersion"]) { "INCOMPATIBLE_ACCESS_INPUTS" }
        require(transitions["catalogFingerprint"] == entries["catalogFingerprint"]) { "ACCESS_INPUT_PROVENANCE_MISMATCH" }
        val contexts = transitions.records("contexts")
        val effects = transitions.records("effects").associateBy { it.string("id") }
        val maxDepth = limit(query, "maxDepth", 20, 100)
        val maxStates = limit(query, "maxStates", 10_000, 100_000)
        val maxPaths = limit(query, "maxPathsPerTarget", 5, 50)
        val suffixLimit = limit(query, "stackSuffix", 8, 64)
        val targets = query.strings("targetContextRefs").ifEmpty { contexts.map { it.string("id") } }.toSet()
        require(targets.all { id -> contexts.any { it["id"] == id } }) { "UNKNOWN_TARGET_CONTEXT" }
        val queue = ArrayDeque<State>()
        val paths = mutableListOf<Record>()
        val frontier = linkedMapOf<String, Record>()
        fun unresolved(code: String, value: String) { frontier.putIfAbsent("$code:$value", record("code" to code, "subjectRef" to value)) }
        entries.records("entries").filter { it["kind"] == "LAUNCHER" && it["enabled"] == "true" && it["applicationEnabled"] == "true" && it["exported"] == "true" }.forEach { entry ->
            entry.strings("hostRefs").forEach { host ->
                val initial = transitions.records("initialStacks").singleOrNull { it["hostRef"] == host }
                if (initial?.get("status") == "STRUCTURAL") {
                    val keys = initial.records("initialKeys")
                    if (keys.isNotEmpty() && !contradictory(initial.records("guards"))) queue.add(State(entry.string("id"), host, keys.takeLast(suffixLimit), keys.size > suffixLimit, initial.records("guards")))
                    else unresolved("INITIAL_STACK_EMPTY_OR_UNKNOWN", host)
                } else unresolved("INITIAL_STACK_UNRESOLVED", host)
            }
        }
        if (queue.isEmpty()) unresolved("NO_PROVEN_LAUNCHER_TO_HOST_BINDING", "")
        val visited = mutableMapOf<String, Int>()
        val counts = mutableMapOf<String, Int>()
        var explored = 0
        var truncated = false
        while (queue.isNotEmpty()) {
            if (explored++ >= maxStates) { truncated = true; unresolved("STATE_BUDGET_EXCEEDED", ""); break }
            val state = queue.removeFirst()
            val key = fingerprint(record("entry" to state.entry, "host" to state.host, "stack" to state.stack, "prefix" to state.unknownPrefix,
                "constraints" to state.guards, "version" to state.version))
            val seenCount = visited[key] ?: 0
            if (seenCount >= maxPaths) continue
            visited[key] = seenCount + 1
            val visible = contexts.filter { it["definitionStatus"] != "UNRESOLVED_RENDER" && it["hostRef"] == state.host && it.records("keyPatterns").any { pattern -> TransitionAnalysis.keyMatches(pattern, state.stack.lastOrNull().orEmpty()) } }
            if (visible.isEmpty()) { unresolved("VISIBLE_DESTINATION_UNRESOLVED", key); continue }
            visible.filter { it.string("id") in targets }.forEach { target ->
                val id = target.string("id")
                if ((counts[id] ?: 0) < maxPaths) {
                    counts[id] = (counts[id] ?: 0) + 1
                    paths += record("id" to "path:" + fingerprint(record("entry" to state.entry, "target" to id, "steps" to state.steps)),
                        "entryRef" to state.entry, "targetContextRef" to id, "steps" to state.steps, "initialOrRestored" to state.steps.isEmpty(),
                        "stack" to state.stack, "unknownPrefix" to state.unknownPrefix, "guards" to state.guards,
                        "feasibility" to if (state.guards.isEmpty()) "NO_KNOWN_CONTRADICTION" else "UNKNOWN",
                        "observation" to "NOT_OBSERVED", "stateVersion" to state.version)
                }
            }
            val next = transitions.records("transitions").filter { edge -> edge.strings("originContextRefs").any { id -> visible.any { it["id"] == id } } }
            if (state.steps.size >= maxDepth) { if (next.isNotEmpty()) { truncated = true; unresolved("DEPTH_BUDGET_EXCEEDED", key) }; continue }
            for (edge in next) {
                if (edge["linkage"] == "UNRESOLVED") { unresolved("UNRESOLVED_TRANSITION", edge.string("id")); continue }
                // Mutable-state predicates are versioned after state effects; input predicates remain correlated.
                val guards = state.guards + edge.records("guards").map { guard ->
                    if (guard["mutableState"] == true) guard + record("version" to state.version) else guard
                }
                if (contradictory(guards)) continue
                var stack = state.stack
                var unknownPrefix = state.unknownPrefix
                var valid = true
                var version = state.version
                for (ref in edge.strings("effectRefs")) {
                    val effect = effects[ref] ?: error("BROKEN_EFFECT_REFERENCE")
                    if (effect["hostRef"] != state.host) { valid = false; unresolved("CROSS_HOST_EFFECT_UNRESOLVED", ref); break }
                    when (effect["operation"]) {
                        "PUSH" -> stack = stack + effect["payload"].record()
                        "CLEAR" -> { stack = emptyList(); unknownPrefix = false }
                        "APPEND_ALL" -> {
                            val payload = effect["payload"].record()
                            if (payload["kind"] == "LIST_VALUE") stack = stack + payload.records("items")
                            else { valid = false; unresolved("STACK_SEQUENCE_UNRESOLVED", ref) }
                        }
                        "POP" -> if (stack.size > 1) stack = stack.dropLast(1) else {
                            valid = false
                            unresolved(if (unknownPrefix) "POP_UNKNOWN_STACK_PREFIX" else "POP_EMPTY_OR_LAST_ENTRY", ref)
                        }
                        "STATE_WRITE" -> version++
                        else -> { valid = false; unresolved("UNSUPPORTED_STACK_EFFECT", ref) }
                    }
                    if (stack.size > suffixLimit) { stack = stack.takeLast(suffixLimit); unknownPrefix = true }
                }
                if (valid) queue.add(State(state.entry, state.host, stack, unknownPrefix, guards.distinct(), state.steps + edge.string("id"), version))
            }
        }
        return record("schemaVersion" to 1, "rulesVersion" to "access-v1", "aiMode" to "DISABLED", "catalogFingerprint" to transitions["catalogFingerprint"],
            "transitionFingerprint" to fingerprint(transitions), "query" to query, "paths" to paths.sortedWith(compareBy({ it.string("targetContextRef") }, { it.strings("steps").size }, { it.string("id") })),
            "targets" to targets.sorted().map { record("contextRef" to it, "status" to if ((counts[it] ?: 0) > 0) "PATH_FOUND_IN_STATIC_MODEL" else "NO_PATH_FOUND_IN_EXPLORED_MODEL") },
            "frontier" to frontier.values.toList(), "inputCoverage" to transitions["coverage"],
            "coverage" to if (truncated || transitions["coverage"] == "TRUNCATED") "TRUNCATED" else if (transitions["coverage"] == "INCOMPLETE_MODEL") "INCOMPLETE_MODEL" else "BOUNDED_STATIC_MODEL",
            "limits" to record("maxDepth" to maxDepth, "maxStates" to maxStates, "maxPathsPerTarget" to maxPaths, "stackSuffix" to suffixLimit),
            "exploredStates" to explored, "limitations" to listOf("STATIC_PATH_IS_NOT_RUNTIME_ACCESS", "EXTERNAL_PRECONDITIONS_UNVERIFIED", "RESTORATION_AND_ASYNC_STATE_REQUIRE_EXPLICIT_INPUTS"))
    }
    private data class State(val entry: String, val host: String, val stack: List<Record>, val unknownPrefix: Boolean,
        val guards: List<Record> = emptyList(), val steps: List<String> = emptyList(), val version: Int = 0)
    private fun limit(query: Record, key: String, default: Int, maximum: Int): Int = ((query[key] as? Number)?.toInt() ?: default).also { require(it in 1..maximum) { "INVALID_ACCESS_LIMIT:$key" } }
    internal fun contradictory(guards: List<Record>): Boolean = guards.any { guard ->
        val test = guard["test"].record()
        test["kind"] == "CONSTANT" && test["value"] is Boolean && test["value"] != guard["expected"]
    } || guards.groupBy { fingerprint(record("test" to it["test"], "version" to it["version"])) }.values.any { group -> group.map { it["expected"] }.distinct().size > 1 }
}
