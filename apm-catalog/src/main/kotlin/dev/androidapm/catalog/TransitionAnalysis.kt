package dev.androidapm.catalog

data class TransitionResult(val report: Record, val evidence: Record)

data class TransitionLimits(val renderVisits: Int = 5_000_000, val eventVisits: Int = 100_000, val renderContinuationsPerOutcome: Int = 8) {
    init { require(renderVisits > 0 && eventVisits > 0 && renderContinuationsPerOutcome > 0) { "TRANSITION_LIMITS_MUST_BE_POSITIVE" } }
}

/** Bounded source interpreter; runtime reachability and unsupported dispatch stay explicit. */
object TransitionAnalysis {
    const val version = "transitions-v2"
    fun create(catalog: Record, entries: Record = emptyMap(), diagnosticContextRefs: Set<String> = emptySet(), limits: TransitionLimits = TransitionLimits(), onEvents: (String, Int, Int) -> Unit = { _, _, _ -> }, onContext: (Int, Int) -> Unit = { _, _ -> }): TransitionResult {
        require(catalog.strings("factsCapabilities").containsAll(setOf("CONTROL_FLOW_IR_V1", "PARAMETER_DEFAULTS_V1", "ASSIGNMENTS_V1", "EXPRESSION_CONTROL_FLOW_V1", "SUPER_CALL_TARGETS_V1"))) { "TRANSITION_FACTS_RECOLLECTION_REQUIRED" }
        if (entries.isNotEmpty()) require(entries["catalogFingerprint"] == fingerprint(catalog)) { "ENTRY_INPUT_PROVENANCE_MISMATCH" }
        return Analyzer(catalog, entries, diagnosticContextRefs, limits, onContext, onEvents).run()
    }
    private data class Frame(val env: Map<String, Record> = emptyMap(), val guards: List<Record> = emptyList(),
        val effects: List<String> = emptyList(), val trace: List<String> = emptyList(), val uncertain: Boolean = false,
        val stop: String = "", val value: Record = emptyMap())
    // Recursion detection is in-memory equality, not a persisted evidence ID.
    // Avoid serializing a large captured environment at every invocation.
    private data class Invocation(val function: String, val environment: Any, val event: String?)
    private data class PendingTrigger(val id: String, val callback: Record, val frame: Frame, val context: Record)
    private data class RenderOutcome(val environment: Any, val value: Record, val effects: List<String>, val stop: String, val uncertain: Boolean)
    private class Analyzer(val catalog: Record, val entries: Record, val diagnosticContextRefs: Set<String>, val limits: TransitionLimits, val onContext: (Int, Int) -> Unit, val onEvents: (String, Int, Int) -> Unit) {
        val flow = FlowValues(catalog)
        val liveness = RenderLiveness(flow)
        val guardChecks = AccessPaths.GuardChecks()
        val evidence = linkedMapOf<String, Record>()
        val contexts = mutableListOf<Record>()
        val triggers = linkedMapOf<String, Record>()
        val effects = linkedMapOf<String, Record>()
        val transitions = linkedMapOf<String, Record>()
        val unresolved = linkedMapOf<String, Record>()
        val hostStacks = mutableMapOf<String, Record>()
        val hostEnvironments = mutableMapOf<String, Map<String, Record>>()
        val hostGuards = mutableMapOf<String, List<Record>>()
        val collectors = linkedMapOf<String, Record>()
        val pendingTriggers = mutableListOf<PendingTrigger>()
        var collectingEvents = false
        val reachedRenders = mutableMapOf<String, MutableSet<String>>()
        var visits = 0
        var truncated = false
        var cutoffs = 0
        var analysisContext = ""
        fun problem(code: String, ref: String, context: String = "", detail: String = "") {
            if (collectingEvents) return
            val v = record("code" to code, "subjectRef" to ref, "contextRef" to context, "detail" to detail)
            unresolved.putIfAbsent(fingerprint(v), v)
        }
        fun proof(v: Record): String = fingerprint(v).also { evidence.putIfAbsent(it, v) }
        fun run(): TransitionResult {
            val providerHosts = mutableMapOf<String, MutableSet<String>>()
            catalog.records("navHostBindings").forEach { b -> b.strings("providerIds").forEach {
                providerHosts.getOrPut(it) { sortedSetOf() }.add(b.string("hostId"))
            } }
            catalog.records("uiRoots").forEach { root ->
                visits = 0
                flow.beginContext("root:" + root.string("id"))
                val c = record("id" to "root:" + root.string("id"), "hostRef" to "", "root" to true)
                analysisContext = c.string("id")
                val content = root.records("arguments").firstOrNull { it["name"] == "content" }?.get("value").record()
                val owner = flow.functions[root.string("owner")].orEmpty()
                val activity = if (root["name"] == "androidx.activity.compose.setContent" && owner["simpleName"] == "onCreate") owner.string("classOwner") else ""
                val rootEnv = mapOf("@context" to record("ref" to c["id"])) + if (activity.isNotEmpty())
                    mapOf("@activityOwner" to record("kind" to "ACTIVITY_OWNER", "identity" to "activity:" + activity)) else emptyMap()
                val fn = flow.eval(content, rootEnv)
                val guards = root.records("conditions").flatMap { branch -> listOf(record("test" to flow.eval(branch["test"].record()), "expected" to true)) + branch.records("excluded").map { record("test" to flow.eval(it), "expected" to false) } }
                if (fn["kind"] == "FUNCTION" && !guardChecks.contradictory(guards)) invoke(fn, emptyList(), Frame(guards = guards), c, null, emptySet(), 0)
            }
            catalog.records("navHosts").forEach { h ->
                // Declaration-only fixtures can describe a host. With real roots, only
                // a host reached by the root interpreter may seed launcher access.
                if (catalog.records("uiRoots").isEmpty()) hostStacks.putIfAbsent(h.string("id"), flow.eval(h.records("arguments").firstOrNull { it["name"] == "backStack" }?.get("value").record()))
            }
            catalog.records("entryBindings").forEach { b ->
                flow.beginContext("provider:" + b.string("id"))
                val hosts = providerHosts[b.string("providerId")].orEmpty()
                if (hosts.isEmpty()) problem("DESTINATION_HOST_UNRESOLVED", b.string("id"))
                hosts.forEach { h ->
                    val expanded = ProviderExpansion.expand(b, h, flow)
                    if (expanded.isNotEmpty()) contexts += expanded else contexts += record("id" to "context:" + fingerprint(record("binding" to b["id"], "host" to h)),
                    "bindingRef" to b["id"], "hostRef" to h, "keyPatterns" to b["keyCandidates"],
                    "content" to b["content"], "definitionStatus" to "REGISTERED", "source" to b["source"]) }
            }
            require(diagnosticContextRefs.all { ref -> contexts.any { it["id"] == ref } }) { "UNKNOWN_DIAGNOSTIC_CONTEXT" }
            contexts.forEachIndexed { index, c ->
                if (diagnosticContextRefs.isNotEmpty() && c.string("id") !in diagnosticContextRefs) return@forEachIndexed
                onContext(index + 1, contexts.size)
                visits = 0
                flow.beginContext(c.string("id"))
                analysisContext = c.string("id")
                val environment = hostEnvironments[c.string("hostRef")].orEmpty() + mapOf("@context" to record("ref" to c["id"]))
                val fn = flow.eval(c["content"].record(), environment)
                if (fn["kind"] == "FUNCTION") invoke(fn, c.records("entryArguments"), Frame(), c, null, emptySet(), 0)
                else problem("DESTINATION_CONTENT_UNRESOLVED", c.string("bindingRef"), c.string("id"))
                val h = catalog.records("navHosts").single { it["id"] == c["hostRef"] }
                val back = h.records("arguments").firstOrNull { it["name"] == "onBack" }?.get("value").record()
                if (back.isNotEmpty()) trigger(h, "BACK", flow.eval(back, environment), flow.constant(true), Frame(), c, emptySet(), 0)
            }
            registerExternalIntents()
            // Register subscriptions before interpreting emissions. Source order
            // of a Button and its LaunchedEffect must not change the graph.
            collectingEvents = true
            val subscriptions = pendingTriggers.filter { triggers[it.id]?.get("kind") == "COMPOSITION_EFFECT" }
            subscriptions.forEachIndexed { index, pending ->
                if (index % 100 == 0 || index == subscriptions.lastIndex) onEvents("subscriptions", index + 1, subscriptions.size)
                executeTrigger(pending)
            }
            collectingEvents = false
            pendingTriggers.forEachIndexed { index, pending ->
                if (index % 100 == 0 || index == pendingTriggers.lastIndex) onEvents("triggers", index + 1, pendingTriggers.size)
                executeTrigger(pending)
            }
            contexts.indices.forEach { index ->
                val c = contexts[index]
                if (c["definitionStatus"] == "LOOKUP_REQUIRES_RENDER_PROOF") {
                    val proved = c.strings("expectedRenderRefs").any { it in reachedRenders[c.string("id")].orEmpty() }
                    contexts[index] = c + record("definitionStatus" to if (proved) "REGISTERED_LOOKUP_RENDER" else "UNRESOLVED_RENDER",
                        "reachedRenderRefs" to reachedRenders[c.string("id")].orEmpty().sorted())
                    if (!proved) problem("PROVIDER_RENDER_NOT_PROVEN", c.string("providerLookupRef"), c.string("id"))
                }
            }
            val registeredStorage = contexts.filter { it["definitionStatus"] == "REGISTERED_LOOKUP_RENDER" }
                .map { it.string("storageCandidateRef") }.filter { it.isNotEmpty() }.toSet()
            catalog.records("screenCandidates").filter { it.string("id") !in registeredStorage }.forEach {
                problem("DESTINATION_BINDING_UNRESOLVED", it.string("id"), detail = "Content storage requires lookup/render proof")
            }
            val proven = contexts.filter { it["definitionStatus"] != "UNRESOLVED_RENDER" }.map { it["id"] }.toSet()
            transitions.entries.removeIf { (_, edge) -> edge.strings("originContextRefs").none { it in proven } }
            transitions.replaceAll { _, edge ->
                val targets = edge.strings("targetContextRefs").filter { it in proven }
                edge + record("targetContextRefs" to targets, "linkage" to if (targets.isEmpty() && edge["dynamicTarget"] == null) "UNRESOLVED" else edge["linkage"])
            }
            val initial = hostStacks.entries.map { (h, s) -> record("hostRef" to h, "initialKeys" to s["initial"],
                "identity" to s["identity"], "guards" to hostGuards[h].orEmpty(), "status" to if (s["kind"] == "STACK") "STRUCTURAL" else "UNRESOLVED") }
            val report = record("schemaVersion" to 1, "rulesVersion" to version, "aiMode" to "DISABLED", "catalogFingerprint" to fingerprint(catalog),
                "diagnosticOnly" to diagnosticContextRefs.isNotEmpty(), "diagnosticContextRefs" to diagnosticContextRefs.sorted(),
                "limits" to record("renderVisits" to limits.renderVisits, "eventVisits" to limits.eventVisits, "renderContinuationsPerOutcome" to limits.renderContinuationsPerOutcome, "depth" to 384, "branchStates" to 256),
                "variant" to catalog["variant"], "contexts" to contexts.sortedBy { it.string("id") }, "initialStacks" to initial.sortedBy { it.string("hostRef") },
                "triggers" to triggers.values.sortedBy { it.string("id") }, "effects" to effects.values.sortedBy { it.string("id") },
                "streamBindings" to collectors.values.map { it.filterKeys { key -> key != "callback" } }.sortedBy { it.string("id") },
                "transitions" to transitions.values.sortedBy { it.string("id") },
                "diagnostics" to (unresolved.map { (id, v) -> record("id" to id) + v } + flow.diagnostics.values).sortedBy { it.string("id") },
                "coverage" to if (truncated || flow.truncated) "TRUNCATED" else if (unresolved.isNotEmpty() || flow.diagnostics.isNotEmpty()) "INCOMPLETE_MODEL" else "WITHIN_DECLARED_BOUNDS",
                "limitations" to listOf("STATIC_MODEL_NOT_RUNTIME_REACHABILITY", "ALLOCATION_SITE_IS_NOT_RUNTIME_INSTANCE",
                    "CHANNEL_DI_AND_DYNAMIC_PROVIDER_REQUIRE_PROVEN_BINDINGS", "EXCEPTION_AND_LOOP_PATHS_NOT_COMPLETE"),
                "summary" to record("contexts" to contexts.size, "triggers" to triggers.size, "effects" to effects.size, "transitions" to transitions.size, "unresolved" to unresolved.size))
            return TransitionResult(report, record("schemaVersion" to 1, "catalogFingerprint" to report["catalogFingerprint"],
                "evidence" to evidence.map { (id, v) -> record("id" to id) + v }))
        }
        fun budget(depth: Int, subject: Any? = analysisContext, event: String? = null): Boolean = if (++visits > (if (event == null) limits.renderVisits else limits.eventVisits) || depth > 384) {
            truncated = true; cutoffs++
            if (visits == (if (event == null) limits.renderVisits else limits.eventVisits) + 1 || depth > 384) problem(if (depth > 384) "CONTROL_FLOW_DEPTH_LIMIT" else "CONTROL_FLOW_VISIT_LIMIT", subject as? String ?: fingerprint(subject), analysisContext,
                if (event == null) "RENDER" else "TRIGGER:$event")
            false
        } else true
        fun registerExternalIntents() {
            entries.records("entries").forEach { entry ->
                val inputs = ExternalIntents.candidates(entry)
                if (inputs.isEmpty()) return@forEach
                val handler = flow.functions.values.singleOrNull { fn -> fn["classOwner"] == entry["targetActivity"] && fn["simpleName"] == "onNewIntent" &&
                    fn.strings("overrideSymbols").any { symbol -> listOf("android.app.Activity.onNewIntent(", "androidx.activity.ComponentActivity.onNewIntent(").any(symbol::startsWith) } &&
                    fn.records("parameters").count { it.string("kind").uppercase() == "REGULAR" && it.string("type").removeSuffix("?") == "android.content.Intent" } == 1 } ?: return@forEach
                problem("EXTERNAL_INPUT_DOMAIN_BOUNDED", entry.string("id"), detail = "Concrete manifest-derived warm inputs only; cold starts, arbitrary URI arguments and Android delivery are not proven")
                contexts.filter { it.string("hostRef") in entry.strings("hostRefs") && (diagnosticContextRefs.isEmpty() || it.string("id") in diagnosticContextRefs) }.forEach { c ->
                    inputs.forEach { input ->
                        val id = "external:" + fingerprint(record("entry" to entry["id"], "input" to input, "context" to c["id"]))
                        val activity = record("kind" to "INSTANCE", "identity" to "activity:" + entry.string("targetActivity"), "type" to entry["targetActivity"])
                        val env = hostEnvironments[c.string("hostRef")].orEmpty() + mapOf("@context" to record("ref" to c["id"]), "@activityOwner" to record("kind" to "ACTIVITY_OWNER", "identity" to activity["identity"])) +
                            handler.records("parameters").associate { p -> p.string("ref") to when {
                                p.string("kind").uppercase().contains("DISPATCH") -> activity
                                p.string("type").removeSuffix("?") == "android.content.Intent" -> input
                                else -> flow.unknown("EXTERNAL_CALLBACK_ARGUMENT_UNRESOLVED", p.string("ref"))
                            } }
                        val delivery = record("kind" to "EXTERNAL_INTENT_DELIVERY", "entryRef" to entry["id"], "callbackRef" to handler["symbol"], "hostRef" to c["hostRef"], "inputIntent" to input,
                            "lifecycle" to "WARM_EXISTING_ACTIVITY", "delivery" to "CONDITIONAL_NOT_OBSERVED")
                        val guard = record("test" to record("kind" to "PREDICATE", "operation" to "ANDROID_WARM_INTENT_DELIVERED", "entryRef" to entry["id"], "inputIntent" to input), "expected" to true, "mutableState" to true)
                        val call = record("id" to id, "externalEntryRef" to entry["id"], "externalInput" to input)
                        trigger(call, "EXTERNAL_INTENT", flow.closure(handler.string("symbol"), env), flow.constant(true), Frame(env = env, guards = listOf(guard), trace = listOf(proof(delivery))), c, emptySet(), 0)
                    }
                }
            }
        }
        fun invoke(fn: Record, args: List<Record>, parent: Frame, c: Record, event: String?, seen: Set<Invocation>, depth: Int): List<Frame> {
            if (!budget(depth, fn.string("ref"), event)) return listOf(parent.copy(uncertain = true, stop = "LIMIT"))
            val ref = fn.string("ref")
            if (event == null && ref in c.strings("expectedRenderRefs")) reachedRenders.getOrPut(c.string("id")) { sortedSetOf() }.add(ref)
            val declaration = flow.functions[ref]
            val body = flow.bodies[ref]
            if (declaration == null || body == null) { problem("FUNCTION_BODY_UNAVAILABLE", ref, c.string("id")); return listOf(parent.copy(uncertain = true)) }
            if (event == null) liveness.prepare(ref)
            val env = (flow.closures[fn.string("closureId")].orEmpty() + parent.env.filterKeys { it.startsWith("@list:") }).toMutableMap()
            declaration.records("parameters").filter { it.string("ref") !in fn.strings("boundParameterRefs") }.forEachIndexed { i, p ->
                if (i < args.size) env[p.string("ref")] = args[i]
                else env.putIfAbsent(p.string("ref"), p["defaultValue"].record().takeIf { it.isNotEmpty() }?.let { flow.eval(it, env) }
                    ?: record("kind" to "SYMBOLIC", "ref" to p["ref"], "type" to p["type"]))
            }
            // Allocation call history is bookkeeping, not an input to this
            // invocation. Keep actual receiver/argument identities and captured
            // values, so recursion with different inputs can still terminate.
            val key = Invocation(ref, flow.environmentKey(env - "@allocationContext"), event)
            if (key in seen) {
                problem("RECURSIVE_CONTROL_FLOW", ref, c.string("id"))
                return listOf(parent.copy(uncertain = true, stop = "RECURSION"))
            }
            val before = cutoffs
            val returned = walk(body, parent.copy(env = env, value = emptyMap()), c, event, seen + key, depth + 1).map {
                it.copy(env = parent.env + it.env.filterKeys { key -> key.startsWith("@list:") }, stop = if (it.stop == "RETURN" || it.stop == "RETURN:" + ref) "" else it.stop)
            }
            // Rendering registers its child triggers immediately. Once every
            // branch returns the same value without effects, its local guards
            // must not constrain later, independent caller content.
            return if (event == null && cutoffs == before && returned.isNotEmpty() && returned.all { it.stop.isEmpty() && it.effects == parent.effects } &&
                returned.map { it.value }.distinct().size == 1 && returned.map { it.env }.distinct().size == 1 && returned.all { it.guards.containsAll(parent.guards) })
                listOf(parent.copy(env = returned.first().env, value = returned.first().value, uncertain = parent.uncertain || returned.any { it.uncertain }))
                else if (event == null) renderContinuations(returned, ref, c) else returned
        }
        /** Keep actual witnesses, never weaken their guards to join partial paths.
         * Child triggers have already been registered with their complete guards.
         * Different returned values, heap state, effects and exits never share a quota. */
        fun renderContinuations(frames: List<Frame>, ref: Any?, context: Record): List<Frame> {
            if (frames.size <= limits.renderContinuationsPerOutcome) return frames
            val counts = mutableMapOf<RenderOutcome, Int>()
            return frames.filter { frame ->
                val key = RenderOutcome(flow.environmentKey(frame.env), frame.value, frame.effects, frame.stop, frame.uncertain)
                val count = counts[key] ?: 0
                counts[key] = count + 1
                if (count >= limits.renderContinuationsPerOutcome) {
                    truncated = true; cutoffs++
                    problem("RENDER_CONTINUATION_BUDGET_EXCEEDED", ref as? String ?: fingerprint(ref), context.string("id"))
                    false
                } else true
            }
        }
        fun walk(stmt: Record, f: Frame, c: Record, event: String?, seen: Set<Invocation>, depth: Int): List<Frame> {
            if (!budget(depth, stmt["source"], event)) return listOf(f.copy(uncertain = true, stop = "LIMIT"))
            return when (stmt.string("op")) {
                "BLOCK" -> stmt.records("statements").fold(listOf(f)) { states, child ->
                    val advanced = states.flatMap { state ->
                        if (state.stop.isNotEmpty()) listOf(state) else {
                            fun clean(env: Map<String, Record>) = env.filterKeys { !it.startsWith("@evaluated:") && !it.startsWith("@choice:") && !it.startsWith("@expression:") }
                            val before = cutoffs
                            val input = state.copy(env = clean(state.env))
                            val paths = walk(child, input, c, event, seen, depth + 1).map { path ->
                                // A statement's discarded call result and expression-local
                                // alternatives cannot constrain the next statement.
                                if (path.stop.isEmpty()) path.copy(env = clean(path.env) - (if (event == null) liveness.deadAfter(child) else emptySet()), value = input.value) else path
                            }
                            if (event == null && before == cutoffs && paths.isNotEmpty() && paths.all { it.stop.isEmpty() && it.effects == input.effects } &&
                                paths.map { it.env }.distinct().size == 1)
                                listOf(input.copy(env = paths.first().env, uncertain = input.uncertain || paths.any { it.uncertain })) else paths
                        }
                    }
                    bounded(if (event == null) renderContinuations(advanced, child["source"], c) else advanced)
                }
                "DECLARE", "WRITE" -> expression(stmt["value"].record(), f, c, event, seen, depth + 1).map {
                    if (it.stop.isNotEmpty()) it else it.copy(env = it.env + (stmt.string("ref") to flow.eval(stmt["value"].record(), it.env)))
                }
                "RETURN", "CALL", "EVALUATE" -> expression(stmt["value"].record(), f, c, event, seen, depth + 1).map {
                    if (it.stop.isEmpty() && stmt["op"] == "RETURN") it.copy(stop = if (stmt.string("target").isEmpty()) "RETURN" else "RETURN:" + stmt.string("target"), value = flow.eval(stmt["value"].record(), it.env)) else it
                }
                "BRANCH" -> {
                    val before = cutoffs
                    var remaining: Frame? = f
                    val paths = mutableListOf<Frame>()
                    for (branch in stmt.records("branches")) {
                        val current = remaining ?: break
                        val test = flow.eval(branch["test"].record(), current.env)
                        assume(current, test, true)?.let { paths += walk(branch["body"].record(), it, c, event, seen, depth + 1) }
                        remaining = assume(current, test, false)
                    }
                    remaining?.let { paths += it }
                    if (event == null && cutoffs == before && paths.isNotEmpty() && paths.all { it.stop.isEmpty() && it.env == f.env && it.effects == f.effects && it.value == f.value })
                        listOf(f.copy(uncertain = f.uncertain || paths.any { it.uncertain })) else bounded(paths)
                }
                "THROW" -> listOf(f.copy(stop = "THROW", uncertain = true))
                "WRITE_FIELD", "LOOP", "TRY", "UNKNOWN" -> {
                    problem("UNSUPPORTED_CONTROL_FLOW", fingerprint(stmt["source"]), c.string("id"), stmt.string("op"))
                    listOf(f.copy(uncertain = true))
                }
                else -> listOf(f)
            }
        }
        fun bounded(frames: List<Frame>): List<Frame> {
            if (frames.size > 256) { truncated = true; cutoffs++; problem("BRANCH_BUDGET_EXCEEDED", "") }
            return frames.take(256)
        }
        fun assume(f: Frame, test: Record, expected: Boolean): Frame? {
            if (test == flow.constant(expected)) return f
            if (test == flow.constant(!expected)) return null
            val guards = (f.guards + record("test" to test, "expected" to expected)).distinct()
            return if (guardChecks.contradictory(guards)) null else f.copy(guards = guards)
        }
        fun expression(expr: Record, f: Frame, c: Record, event: String?, seen: Set<Invocation>, depth: Int): List<Frame> {
            if (f.stop.isNotEmpty()) return listOf(f)
            if (expr["kind"] in setOf("RETURN_EXPRESSION", "THROW_EXPRESSION")) {
                return expression(expr["value"].record(), f, c, event, seen, depth + 1).map {
                    if (it.stop.isNotEmpty()) it else it.copy(stop = if (expr["kind"] == "THROW_EXPRESSION") "THROW" else "RETURN:" + expr.string("target"),
                        value = flow.eval(expr["value"].record(), it.env))
                }
            }
            if (expr["kind"] == "BLOCK_EXPRESSION") {
                val prefix = walk(record("op" to "BLOCK", "statements" to expr.records("statements")), f, c, event, seen, depth + 1)
                return bounded(prefix.flatMap { expression(expr["value"].record(), it, c, event, seen, depth + 1) }.map {
                    if (it.stop.isNotEmpty()) it else it.copy(env = it.env + ("@expression:" + expr.string("expressionId") to flow.eval(expr["value"].record(), it.env)))
                })
            }
            if (expr["kind"] == "CHOICE" && expr.string("choiceGroupId").isNotEmpty()) {
                return bounded(expr.records("alternatives").flatMap { alternative ->
                    var branch: Frame? = assume(f, flow.eval(alternative["test"].record(), f.env), true)
                    alternative.records("excluded").forEach { excluded -> branch = branch?.let { assume(it, flow.eval(excluded, it.env), false) } }
                    branch?.let { frame -> expression(alternative["value"].record(), frame, c, event, seen, depth + 1).map {
                        if (it.stop.isNotEmpty()) it else it.copy(env = it.env + ("@choice:" + expr.string("choiceGroupId") to flow.eval(alternative["value"].record(), it.env)))
                    } }.orEmpty()
                })
            }
            if (expr["kind"] == "LIST") return expr.records("values").fold(listOf(f)) { states, item ->
                bounded(states.flatMap { expression(if (item["kind"] == "SPREAD") item["value"].record() else item, it, c, event, seen, depth + 1) })
            }
            if (expr["kind"] !in setOf("CALL", "CONSTRUCTOR")) return listOf(f)
            if (!budget(depth, expr.string("callId"), event)) return listOf(f.copy(uncertain = true, stop = "LIMIT"))
            val call = flow.calls[expr.string("callId")] ?: return listOf(f.copy(uncertain = true))
            val states = call.records("arguments").fold(listOf(f)) { states, arg ->
                bounded(states.flatMap { expression(arg["value"].record(), it, c, event, seen, depth + 1) })
            }
            return bounded(states.flatMap { if (it.stop.isNotEmpty()) listOf(it) else dispatch(call, it, c, event, seen, depth + 1) })
        }
        fun dispatch(call: Record, f: Frame, c: Record, event: String?, seen: Set<Invocation>, depth: Int): List<Frame> {
            val name = call.string("name")
            val args = flow.arguments(call, f.env)
            val next = f.copy(trace = f.trace + proof(record("kind" to "CALL", "callRef" to call["id"], "source" to call["source"], "target" to call["target"])))
            ExternalIntents.value(name, args)?.let { value -> return listOf(next.copy(value = value, env = next.env + ("@evaluated:" + call.string("id") to value))) }
            if (name == "androidx.navigation3.ui.NavDisplay") {
                val stack = args["backStack"].orEmpty()
                val old = hostStacks[call.string("id")]
                if (old != null && old != stack) {
                    problem("MULTIPLE_HOST_INSTANCES", call.string("id"), c.string("id"))
                    hostStacks[call.string("id")] = flow.unknown("HOST_INSTANCE_AMBIGUOUS", call.string("id"))
                } else {
                    hostStacks[call.string("id")] = stack
                    hostEnvironments[call.string("id")] = f.env
                    hostGuards.putIfAbsent(call.string("id"), f.guards)
                }
                return listOf(next)
            }
            if (name == "androidx.compose.runtime.CompositionLocalProvider") {
                val locals = args.values.flatMap { if (it["kind"] == "LIST_VALUE") it.records("items") else listOf(it) }.filter { it["kind"] == "PROVIDED_LOCAL" }
                    .associate { "@local:" + it["local"].record()["identity"] to it["value"].record() }
                val fn = args["content"].orEmpty()
                if (fn["kind"] == "FUNCTION") return invoke(flow.closure(fn.string("ref"), flow.closures[fn.string("closureId")].orEmpty() + locals), emptyList(), next, c, event, seen, depth + 1)
                problem("COMPOSITION_LOCAL_CONTENT_UNRESOLVED", call.string("id"), c.string("id"))
                return listOf(next.copy(uncertain = true))
            }
            if (name == "androidx.compose.runtime.LaunchedEffect") {
                if (event != null) {
                    problem("NESTED_TRIGGER_REGISTRATION", call.string("id"), c.string("id"))
                    return listOf(next.copy(uncertain = true))
                }
                val callback = args["block"].orEmpty()
                val active = next.copy(guards = next.guards + record("test" to record("kind" to "PREDICATE", "operation" to "EFFECT_LIFECYCLE_ACTIVE", "callRef" to call["id"]), "expected" to true, "mutableState" to true))
                if (callback["kind"] != "FUNCTION") return listOf(next.copy(uncertain = true))
                if (c["root"] == true) return invoke(callback, emptyList(), active, c, null, seen, depth + 1)
                trigger(call, "COMPOSITION_EFFECT", callback, flow.constant(true), active, c, seen, depth + 1)
                return listOf(next)
            }
            val slot = when (name) {
                "androidx.compose.foundation.clickable", "androidx.compose.foundation.combinedClickable",
                "androidx.compose.material3.Button", "androidx.compose.material3.TextButton", "androidx.compose.material3.OutlinedButton",
                "androidx.compose.material3.FilledTonalButton", "androidx.compose.material3.ElevatedButton", "androidx.compose.material3.IconButton",
                "androidx.compose.material.Button", "androidx.compose.material.TextButton", "androidx.compose.material.OutlinedButton", "androidx.compose.material.IconButton" -> "onClick"
                "androidx.activity.compose.BackHandler" -> "onBack"
                else -> null
            }
            if (slot != null) {
                if (event == null) trigger(call, if (slot == "onBack") "BACK" else "CLICK", args[slot].orEmpty(), args["enabled"] ?: flow.constant(true), next, c, seen, depth)
                else problem("NESTED_TRIGGER_REGISTRATION", call.string("id"), c.string("id"))
                return listOf(next)
            }
            val receiver = call.records("arguments").firstOrNull { it.string("parameterKind").uppercase() in setOf("DISPATCHRECEIVER", "EXTENSIONRECEIVER", "DISPATCH_RECEIVER", "EXTENSION_RECEIVER") }
                ?: call.records("arguments").firstOrNull { "this" in it.string("name") }
            val instance = receiver?.get("value")?.record()?.let { flow.eval(it, f.env) }
            if (name in setOf("kotlin.takeIf", "kotlin.takeUnless")) {
                val callback = args["predicate"].orEmpty()
                if (callback["kind"] != "FUNCTION" || instance == null) {
                    problem("CONDITIONAL_VALUE_UNRESOLVED", call.string("id"), c.string("id"))
                    return listOf(next.copy(uncertain = true))
                }
                return bounded(invoke(callback, listOf(instance), next, c, event, seen, depth + 1).flatMap { path ->
                    if (path.stop.isNotEmpty()) listOf(path) else {
                        val test = path.value.ifEmpty { flow.returned(callback, listOf(instance)) }
                        val keep = name == "kotlin.takeIf"
                        listOfNotNull(assume(path, test, keep)?.let { it.copy(value = instance, env = it.env + ("@evaluated:" + call.string("id") to instance)) },
                            assume(path, test, !keep)?.let { it.copy(value = flow.constant(null), env = it.env + ("@evaluated:" + call.string("id") to flow.constant(null))) })
                    }
                })
            }
            if (name == "kotlin.collections.buildList") {
                val callback = args["builderAction"].orEmpty()
                if (callback["kind"] != "FUNCTION") return listOf(next.copy(uncertain = true))
                val id = "@list:" + call.string("id") + ":" + flow.environmentDigest(f.env)
                val builder = record("kind" to "LIST_BUILDER", "identity" to id)
                val initial = next.copy(env = next.env + (id to record("kind" to "LIST_VALUE", "items" to emptyList<Record>())))
                return invoke(callback, listOf(builder), initial, c, event, seen, depth + 1).map { path ->
                    val result = path.env[id] ?: flow.unknown("LIST_BUILDER_STATE_UNRESOLVED", call.string("id"))
                    path.copy(value = result, env = (path.env - id) + ("@evaluated:" + call.string("id") to result))
                }
            }
            if (instance?.get("kind") == "LIST_BUILDER" && name in setOf("kotlin.collections.MutableList.add", "kotlin.collections.MutableCollection.add")) {
                val regular = call.records("arguments").filter { it != receiver }
                val key = instance.string("identity")
                val list = f.env[key]
                if (regular.size != 1 || list?.get("kind") != "LIST_VALUE") {
                    problem("LIST_BUILDER_MUTATION_UNSUPPORTED", call.string("id"), c.string("id"))
                    return listOf(next.copy(uncertain = true, stop = "UNSUPPORTED_EFFECT"))
                }
                val value = flow.eval(regular.single()["value"].record(), f.env)
                val updated = list + record("items" to list.records("items") + value)
                return listOf(next.copy(value = flow.constant(true), env = next.env + (key to updated) + ("@evaluated:" + call.string("id") to flow.constant(true))))
            }
            if (instance?.get("kind") == "LIST_BUILDER" && name.startsWith("kotlin.collections.")) {
                problem("LIST_BUILDER_OPERATION_UNSUPPORTED", call.string("id"), c.string("id"))
                return listOf(next.copy(uncertain = true, stop = "UNSUPPORTED_EFFECT"))
            }
            if (name in setOf("kotlin.collections.forEach", "kotlin.collections.forEachIndexed") && instance?.get("kind") == "LIST_VALUE") {
                val callback = args["action"].orEmpty()
                if (callback["kind"] != "FUNCTION") return listOf(next.copy(uncertain = true))
                return instance.records("items").withIndex().fold(listOf(next)) { paths, (index, item) ->
                    bounded(paths.flatMap { path -> if (path.stop.isNotEmpty()) listOf(path) else invoke(callback,
                        if (name.endsWith("Indexed")) listOf(flow.constant(index), item) else listOf(item), path, c, event, seen, depth + 1) })
                }
            }
            if (event != null && name in setOf("androidx.activity.compose.ManagedActivityResultLauncher.launch", "androidx.activity.result.ActivityResultLauncher.launch")) {
                if (instance?.get("kind") != "ACTIVITY_LAUNCHER") {
                    problem("ACTIVITY_RESULT_LAUNCHER_UNRESOLVED", call.string("id"), c.string("id"))
                    return listOf(next.copy(uncertain = true))
                }
                val delivery = record("kind" to "ACTIVITY_RESULT_TRANSFER", "registrationRef" to instance["registrationRef"], "launchCallRef" to call["id"], "launcherIdentity" to instance["identity"])
                val predicate = record("kind" to "PREDICATE", "operation" to "ACTIVITY_RESULT_DELIVERED", "launcherIdentity" to instance["identity"], "launchCallRef" to call["id"])
                val value = record("kind" to "SYMBOLIC", "ref" to "activity-result:" + fingerprint(delivery), "contract" to instance["contract"])
                return invoke(instance["callback"].record(), listOf(value), next.copy(uncertain = true, trace = next.trace + proof(delivery),
                    guards = next.guards + record("test" to predicate, "expected" to true, "mutableState" to true)), c, event, seen, depth + 1)
            }
            if (name == "kotlin.runCatching" && event != null) {
                val callback = args["block"].orEmpty()
                if (callback["kind"] != "FUNCTION") return listOf(next.copy(uncertain = true))
                val result = flow.eval(record("kind" to "CALL", "callId" to call["id"]), f.env)
                return invoke(callback, emptyList(), next, c, event, seen, depth + 1).map { path ->
                    val value = result + record("value" to path.value.ifEmpty { result["value"].record() }, "outcome" to if (path.stop == "THROW") "FAILURE" else "UNKNOWN")
                    path.copy(stop = if (path.stop == "THROW") "" else path.stop, value = value, env = path.env + ("@evaluated:" + call.string("id") to value))
                }
            }
            if (name in setOf("kotlin.onSuccess", "kotlin.onFailure") && event != null) {
                val result = instance ?: args.values.firstOrNull().orEmpty()
                val callback = args["action"].orEmpty()
                if (callback["kind"] != "FUNCTION") return listOf(next.copy(uncertain = true))
                val success = name == "kotlin.onSuccess"
                val predicate = record("kind" to "PREDICATE", "operation" to "RESULT_IS_SUCCESS", "result" to result)
                val active = if (result["outcome"] == "FAILURE" && success) null else assume(next, predicate, success)
                val value = if (success) result["value"].record().ifEmpty { record("kind" to "SYMBOLIC", "ref" to "result:" + fingerprint(result)) }
                    else record("kind" to "SYMBOLIC", "ref" to "failure:" + fingerprint(result), "type" to "kotlin.Throwable")
                val paths = active?.let { invoke(callback, listOf(value), it, c, event, seen, depth + 1) }.orEmpty() + listOfNotNull(assume(next, predicate, !success))
                return paths.map { it.copy(value = result, env = it.env + ("@evaluated:" + call.string("id") to result)) }
            }
            if (name in setOf("kotlinx.coroutines.flow.collect", "kotlinx.coroutines.flow.Flow.collect")) {
                val stream = instance ?: args.values.firstOrNull()
                val callback = args.values.lastOrNull { it["kind"] == "FUNCTION" }
                if (stream?.get("kind") == "STREAM" && stream["ownerProven"] == true && callback != null && (event == null || triggers[event]?.get("kind") == "COMPOSITION_EFFECT")) {
                    val id = "collector:" + fingerprint(record("call" to call["id"], "stream" to stream["identity"], "callback" to callback))
                    val stackIds = flow.closures[callback.string("closureId")].orEmpty().values.filter { it["kind"] == "STACK" }.map { it.string("identity") }.distinct()
                    collectors[id] = record("id" to id, "callRef" to call["id"], "streamIdentity" to stream["identity"], "streamKind" to stream["streamKind"],
                        "callback" to callback, "stackIdentities" to stackIds, "contextRef" to if (c["root"] == true) null else c["id"], "hostRef" to c["hostRef"],
                        "guards" to f.guards, "evidenceRefs" to next.trace, "delivery" to "CONDITIONAL_NOT_OBSERVED")
                } else problem("STREAM_COLLECTOR_UNRESOLVED", call.string("id"), c.string("id"),
                    "stream=" + stream?.get("kind") + "; reason=" + stream?.get("reason") + "; callback=" + callback?.get("kind"))
                return listOf(next.copy(uncertain = true))
            }
            if (event != null && name.startsWith("kotlinx.coroutines.channels.") && name.substringAfterLast('.') in setOf("trySend", "send")) {
                val stream = instance ?: args.values.firstOrNull()
                val receivers = collectors.values.filter { stream?.get("ownerProven") == true && it["streamIdentity"] == stream["identity"] && (it["contextRef"] == null || it["contextRef"] == c["id"]) }
                if (receivers.isEmpty()) { problem("STREAM_RECEIVER_UNRESOLVED", call.string("id"), c.string("id")); return listOf(next.copy(uncertain = true)) }
                val payload = call.records("arguments").lastOrNull { it != receiver }?.let { flow.eval(it["value"].record(), f.env) }.orEmpty()
                return receivers.flatMap { collector ->
                    val hosts = if (collector["contextRef"] == c["id"] && collector.string("hostRef").isNotEmpty()) setOf(collector.string("hostRef"))
                        else hostStacks.filterValues { it.string("identity") in collector.strings("stackIdentities") }.keys
                    if (hosts.size != 1 || c.string("hostRef") !in hosts) {
                        problem("ASYNC_HOST_CONTEXT_UNRESOLVED", collector.string("id"), c.string("id")); listOf(next.copy(uncertain = true))
                    } else {
                        val delivery = record("kind" to "CHANNEL_TRANSFER", "sendCallRef" to call["id"], "collectorRef" to collector["id"],
                            "streamIdentity" to stream?.get("identity"), "competingCollectors" to receivers.size, "payload" to payload,
                            "stages" to listOf("REQUEST", "ACCEPTANCE_REQUIRED", "RECEIVE_REQUIRED", "APPLICATION_NOT_OBSERVED"))
                        val guard = record("test" to record("kind" to "PREDICATE", "operation" to "CHANNEL_ACCEPTED_DELIVERED_AND_COLLECTOR_ACTIVE", "sendCallRef" to call["id"], "collectorRef" to collector["id"]), "expected" to true, "mutableState" to true)
                        invoke(collector["callback"].record(), listOf(payload), next.copy(uncertain = true,
                            guards = next.guards + collector.records("guards") + guard, trace = next.trace + collector.strings("evidenceRefs") + proof(delivery)), c, event, seen, depth + 1)
                    }
                }
            }
            if (event != null && name.substringAfterLast('.') in setOf("add", "removeLastOrNull", "removeLast", "clear", "addAll")) {
                if (instance?.get("kind") == "STACK") {
                    val regular = call.records("arguments").filter { it != receiver }
                    if (name.substringAfterLast('.') in setOf("add", "addAll") && regular.size != 1) {
                        problem("INDEXED_STACK_MUTATION_UNSUPPORTED", call.string("id"), c.string("id"))
                        return listOf(next.copy(uncertain = true, stop = "UNSUPPORTED_EFFECT"))
                    }
                    val hosts = hostStacks.filterValues { it["identity"] == instance["identity"] }.keys
                    if (hosts.size != 1 || c.string("hostRef") !in hosts) {
                        problem("STACK_HOST_IDENTITY_UNRESOLVED", call.string("id"), c.string("id")); return listOf(next.copy(uncertain = true))
                    }
                    val op = when (name.substringAfterLast('.')) { "add" -> "PUSH"; "clear" -> "CLEAR"; "addAll" -> "APPEND_ALL"; else -> "POP" }
                    val payload = call.records("arguments").filter { it != receiver }.lastOrNull()?.let { flow.eval(it["value"].record(), f.env) }
                    val id = "effect:" + fingerprint(record("call" to call["id"], "trigger" to event, "payload" to payload, "guards" to f.guards, "index" to f.effects.size))
                    effects[id] = record("id" to id, "hostRef" to c["hostRef"], "operation" to op, "payload" to payload,
                        "callRef" to call["id"], "guards" to f.guards, "evidenceRefs" to next.trace)
                    return listOf(next.copy(effects = next.effects + id))
                }
                if (instance?.get("kind") !in setOf("LIST_VALUE", "CONSTANT")) {
                    problem("POSSIBLE_STACK_MUTATION_UNRESOLVED", call.string("id"), c.string("id")); return listOf(next.copy(uncertain = true))
                }
            }
            if (name.endsWith(".invoke") && (instance?.get("kind") == "FUNCTION" || name.startsWith("kotlin.Function") || name.startsWith("kotlin.coroutines.SuspendFunction"))) {
                val fn = instance ?: args.values.firstOrNull()
                if (fn?.get("kind") == "FUNCTION") return invoke(fn, args.values.drop(1), next, c, event, seen, depth + 1)
                if (event != null) problem("CALLBACK_UNRESOLVED", call.string("id"), c.string("id"))
                return listOf(next.copy(uncertain = event != null || next.uncertain))
            }
            // Value summaries are authoritative for these framework factories/adapters.
            // Executing an available stub body would overwrite their identity with its placeholder return.
            if (name in setOf("androidx.compose.runtime.remember", "androidx.compose.runtime.rememberSaveable",
                    "androidx.compose.runtime.mutableStateListOf", "androidx.navigation3.runtime.rememberNavBackStack",
                    "kotlinx.coroutines.channels.Channel", "kotlinx.coroutines.flow.receiveAsFlow", "androidx.activity.compose.rememberLauncherForActivityResult")) return listOf(next)
            if (call.string("target") in flow.functions && call["constructor"] != true) {
                var target = flow.functions.getValue(call.string("target"))
                if (target["overridable"] == true && call["superQualified"] != true) {
                    val implementation = flow.implementation(target, instance)
                    if (implementation != null) target = implementation
                    else { problem("VIRTUAL_DISPATCH_REQUIRES_OBJECT_PROOF", call.string("id"), c.string("id")); return listOf(next.copy(uncertain = true)) }
                }
                val bound = flow.bind(call, f.env + flow.closures[instance?.string("environmentId")].orEmpty())
                val parameters = target.records("parameters")
                val declaredParameters = flow.functions[call.string("target")]?.records("parameters").orEmpty()
                val values = declaredParameters.map { bound[it.string("ref")] ?: flow.unknown("DISPATCH_ARGUMENT_UNRESOLVED", it.string("ref")) }
                val env = if (target["symbol"] == call["target"]) bound else bound + parameters.mapIndexedNotNull { i, p -> values.getOrNull(i)?.let { p.string("ref") to it } }.toMap()
                return invoke(flow.closure(target.string("symbol"), env), emptyList(), next, c, event, seen, depth + 1).map { returned ->
                    if (returned.value.isNotEmpty()) returned.copy(env = returned.env + ("@evaluated:" + call.string("id") to returned.value)) else returned
                }
            }
            if (event != null && name.startsWith("kotlinx.coroutines.")) {
                if (name == "kotlinx.coroutines.launch" && args["block"]?.get("kind") == "FUNCTION") {
                    val guard = record("test" to record("kind" to "PREDICATE", "operation" to "COROUTINE_RUNS_WITHOUT_CANCELLATION", "callRef" to call["id"]), "expected" to true, "mutableState" to true)
                    return invoke(args.getValue("block"), emptyList(), next.copy(uncertain = true, guards = next.guards + guard), c, event, seen, depth + 1)
                }
                problem("ASYNC_TRANSFER_UNRESOLVED", call.string("id"), c.string("id")); return listOf(next.copy(uncertain = true))
            }
            // Framework content slots execute in their own render/collection context; callbacks such as onClick do not.
            val slots = call.records("arguments").filter { ContentSemantics.follows(name, it) }
            if (slots.isNotEmpty() && ContentSemantics.known(name)) {
                return slots.fold(listOf(next)) { states, slot -> states.flatMap { state ->
                    val fn = flow.eval(slot["value"].record(), state.env)
                    if (fn["kind"] != "FUNCTION") listOf(state.copy(uncertain = true)) else {
                        val params = flow.functions[fn.string("ref")]?.records("parameters").orEmpty()
                        val values = params.map { p ->
                            if (name in setOf("kotlin.let", "kotlin.run", "kotlin.also", "kotlin.apply", "kotlin.with") && params.size == 1)
                                instance ?: args["receiver"] ?: flow.unknown("SCOPE_RECEIVER_UNRESOLVED", call.string("id"))
                            else record("kind" to "SYMBOLIC", "ref" to p["ref"], "type" to p["type"], "originCallRef" to call["id"])
                        }
                        val collection = name.contains(".lazy.") && slot["name"] in setOf("itemContent", "content") && name.substringAfterLast('.') !in setOf("LazyColumn", "LazyRow")
                        val active = if (collection) state.copy(guards = state.guards + record("test" to record("kind" to "PREDICATE", "operation" to "ITEM_IS_COMPOSED", "callRef" to call["id"]), "expected" to true)) else state
                        invoke(fn, values, active, c, event, seen, depth + 1).map { path ->
                            if (path.stop.isNotEmpty() || name !in setOf("kotlin.let", "kotlin.run", "kotlin.with", "kotlin.also", "kotlin.apply")) path else {
                                val value = if (name in setOf("kotlin.also", "kotlin.apply")) instance ?: args["receiver"] ?: flow.unknown("SCOPE_RECEIVER_UNRESOLVED", call.string("id"))
                                    else path.value.ifEmpty { flow.returned(fn, values) }
                                path.copy(value = value, env = path.env + ("@evaluated:" + call.string("id") to value))
                            }
                        }
                    }
                } }
            }
            return listOf(next)
        }
        fun trigger(call: Record, kind: String, callback: Record, enabled: Record, f: Frame, c: Record, seen: Set<Invocation>, depth: Int) {
            if (c["root"] == true) return
            if (callback["kind"] != "FUNCTION") { problem("TRIGGER_CALLBACK_UNRESOLVED", call.string("id"), c.string("id")); return }
            val id = "trigger:" + fingerprint(record("context" to c["id"], "call" to call["id"], "callback" to callback, "enabled" to enabled, "guards" to f.guards))
            if (id in triggers) return
            triggers[id] = record("id" to id, "originContextRef" to c["id"], "kind" to kind, "callRef" to call["id"],
                "callbackRef" to callback["ref"], "enabled" to enabled, "guards" to f.guards, "evidenceRefs" to f.trace) + call.filterKeys { it in setOf("externalEntryRef", "externalInput") }
            val active = assume(f.copy(effects = emptyList()), enabled, true) ?: return
            pendingTriggers += PendingTrigger(id, callback, active, c)
        }
        fun executeTrigger(pending: PendingTrigger) {
            val (id, callback, active, c) = pending
            analysisContext = c.string("id")
            // Event execution starts after rendering. Its call stack and budget
            // cannot consume the budget for discovering later UI controls.
            val savedVisits = visits
            val savedCutoffs = cutoffs
            val plans = try {
                visits = 0
                flow.withFreshEvaluationBudget { invoke(callback, emptyList(), active, c, id, emptySet(), 0) }
            } finally { visits = savedVisits; cutoffs = savedCutoffs }
            if (collectingEvents) return
            plans.filter { it.effects.isNotEmpty() }.forEach { plan ->
                val last = effects[plan.effects.last()].orEmpty()
                val key = when (last["operation"]) { "PUSH" -> last["payload"].record(); "APPEND_ALL" -> last["payload"].record().records("items").lastOrNull().orEmpty(); else -> emptyMap() }
                val targets = contexts.filter { it["hostRef"] == c["hostRef"] && it.records("keyPatterns").any { p -> keyMatches(p, key) } }
                val dynamic = last["operation"] == "POP"
                if (targets.isEmpty() && !dynamic) problem("DESTINATION_KEY_UNRESOLVED", id, c.string("id"))
                val edgeId = "transition:" + fingerprint(record("trigger" to id, "effects" to plan.effects, "guards" to plan.guards))
                transitions[edgeId] = record("id" to edgeId, "originContextRefs" to listOf(c["id"]), "triggerRef" to id, "effectRefs" to plan.effects,
                    "targetContextRefs" to targets.map { it["id"] }, "dynamicTarget" to if (dynamic) "PREVIOUS_STACK_ENTRY" else null,
                    "linkage" to if (targets.isEmpty() && !dynamic) "UNRESOLVED" else if (plan.uncertain || plan.guards.isNotEmpty() || dynamic || targets.size != 1) "MAY" else "RESOLVED_IN_MODEL",
                    "conditions" to if (plan.guards.isEmpty()) "NO_KNOWN_CONTRADICTION" else "UNKNOWN", "guards" to plan.guards,
                    "completion" to plan.stop.ifEmpty { "NORMAL_IN_MODELED_FLOW" }, "observation" to "NOT_OBSERVED", "evidenceRefs" to plan.trace) + triggers.getValue(id).filterKeys { it in setOf("externalEntryRef", "externalInput") }
            }
        }
    }
    internal fun keyMatches(pattern: Record, value: Record): Boolean = when (pattern.string("kind")) {
        "FIELD_KEY" -> pattern["type"] == value["type"] && value["fields"].record()[pattern.string("field")] == pattern["value"]
        "TYPE" -> pattern.string("type").removeSuffix("?") == value.string("type").removeSuffix("?")
        "CONSTANT" -> value["kind"] == "CONSTANT" && pattern["value"] == value["value"]
        "OBJECT" -> pattern["ref"] == value["type"]
        else -> false
    }
}

