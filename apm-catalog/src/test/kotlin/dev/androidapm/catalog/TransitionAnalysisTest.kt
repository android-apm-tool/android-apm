package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransitionAnalysisTest {
    @Test fun `filtered diagnostics cannot be passed off as complete access evidence`() {
        val facts = fixture()
        val full = TransitionAnalysis.create(facts).report
        val context = full.records("contexts").first().string("id")
        val diagnostic = TransitionAnalysis.create(facts, diagnosticContextRefs = setOf(context)).report
        assertEquals(true, diagnostic["diagnosticOnly"])
        assertThrows(IllegalArgumentException::class.java) { AccessPaths.find(diagnostic, entries(diagnostic)) }
        assertThrows(IllegalArgumentException::class.java) { TransitionAnalysis.create(facts, diagnosticContextRefs = setOf("missing")) }
    }
    @Test fun `predicate fingerprints may be reused but guard truth and state versions cannot`() {
        val checks = AccessPaths.GuardChecks()
        val test = record("kind" to "PREDICATE", "operation" to "input")
        fun guard(value: Boolean, version: Int) = record("test" to test, "expected" to value, "version" to version)
        assertTrue(checks.contradictory(listOf(guard(true, 0), guard(false, 0))))
        assertFalse(checks.contradictory(listOf(guard(true, 0), guard(false, 1))))
        assertFalse(checks.contradictory(listOf(guard(false, 0))))
        val copy = record("kind" to "PREDICATE", "operation" to "input")
        assertTrue(checks.contradictory(listOf(guard(true, 0), record("test" to copy, "expected" to false, "version" to 0))))
    }
    @Test fun `unproven field receivers cannot borrow the screen context as Channel ownership`() {
        val flow = FlowValues(record("callRelations" to listOf(call("channel", "kotlinx.coroutines.channels.Channel", emptyList()))))
        val expr = record("kind" to "CALL", "callId" to "channel")
        val env = mapOf("@context" to record("ref" to "screen"))
        assertEquals(true, flow.eval(expr, env)["ownerProven"])
        assertEquals(false, flow.eval(expr, env + ("@receiver" to record("kind" to "UNKNOWN")))["ownerProven"])
        fun owned(id: String) = flow.eval(expr, env + ("@receiver" to record("kind" to "INSTANCE", "identity" to id)))
        assertNotEquals(owned("one")["identity"], owned("two")["identity"])
    }
    @Test fun `LocalActivity field form needs proven Activity context and respects explicit providers`() {
        val getter = call("currentActivity", "androidx.compose.runtime.ProvidableCompositionLocal.<get-current>", listOf(argument("this", reference("androidx.activity.compose.LocalActivity"), "DispatchReceiver")))
        val flow = FlowValues(record("callRelations" to listOf(getter)))
        val expression = record("kind" to "CALL", "callId" to "currentActivity")
        val owner = record("kind" to "ACTIVITY_OWNER", "identity" to "activity:One")
        assertEquals(owner, flow.eval(expression, mapOf("@activityOwner" to owner)))
        assertEquals("UNKNOWN", flow.eval(expression)["kind"])
        assertEquals(constant(null), flow.eval(expression, mapOf("@activityOwner" to owner, "@local:androidx.activity.compose.LocalActivity" to constant(null))))
    }
    @Test fun `screen collectors resolve independently of declaration order without crossing screens or channels`() {
        for (sameChannel in listOf(true, false)) for (sameScreen in listOf(true, false)) {
            val base = fixture(action = listOf(statement("send")))
            fun stream(id: String) = record("kind" to "STREAM", "identity" to id, "ownerProven" to true, "streamKind" to "CHANNEL")
            val input = base + record("functions" to base.records("functions") + listOf("Subscribe", "Receive").map { record("symbol" to it, "parameters" to emptyList<Record>()) },
                "callRelations" to base.records("callRelations") + listOf(
                    call("effect", "androidx.compose.runtime.LaunchedEffect", listOf(argument("block", function("Subscribe")))),
                    call("collect", "kotlinx.coroutines.flow.Flow.collect", listOf(argument("this", stream("one"), "DispatchReceiver"), argument("collector", function("Receive")))),
                    call("send", "kotlinx.coroutines.channels.SendChannel.send", listOf(argument("this", stream(if (sameChannel) "one" else "two"), "DispatchReceiver"), argument("element", constant("event"))))),
                "functionBodies" to base.records("functionBodies").filter { it["owner"] !in setOf("Home", "Detail") } + listOf(
                    body("Home", listOf(statement("button")) + if (sameScreen) listOf(statement("effect")) else emptyList()),
                    body("Detail", if (sameScreen) emptyList() else listOf(statement("effect"))), body("Subscribe", listOf(statement("collect"))), body("Receive", listOf(statement("push")))))
            val result = TransitionAnalysis.create(input)
            assertEquals(if (sameChannel && sameScreen) 1 else 0, result.report.records("transitions").size)
            if (sameChannel && sameScreen) assertTrue(result.evidence.records("evidence").any { it["kind"] == "CHANNEL_TRANSFER" })
        }
    }
    @Test fun `explicit ViewModel owner and key preserve identity without accepting unknown owners`() {
        val constructor = record("symbol" to "Vm.init", "classOwner" to "Vm", "constructor" to true, "annotations" to listOf("javax.inject.Inject"), "parameters" to emptyList<Record>())
        fun evaluate(owner: Record, key: Any?, context: String): Record {
            val call = call("vm", "androidx.hilt.navigation.compose.hiltViewModel", listOf(argument("viewModelStoreOwner", owner), argument("key", constant(key))))
            val flow = FlowValues(record("functions" to listOf(constructor), "classes" to listOf(record("symbol" to "Vm", "annotations" to listOf(record("name" to "dagger.hilt.android.lifecycle.HiltViewModel")))), "callRelations" to listOf(call)))
            return flow.eval(record("kind" to "CALL", "callId" to "vm", "type" to "Vm"), mapOf("@context" to record("ref" to context)))
        }
        val owner = record("kind" to "ACTIVITY_OWNER", "identity" to "activity:One")
        val first = evaluate(owner, null, "first")
        assertEquals(first["identity"], evaluate(owner, null, "second")["identity"])
        assertNotEquals(first["identity"], evaluate(owner, "other-key", "first")["identity"])
        assertNotEquals(first["identity"], evaluate(owner + record("identity" to "activity:Two"), null, "first")["identity"])
        assertEquals("UNKNOWN", evaluate(reference("unprovenOwner"), null, "first")["kind"])
    }
    @Test fun `Activity results require a launch of the exact registered launcher`() {
        for (receiver in listOf(reference("launcher"), reference("unknownLauncher"))) {
            val base = fixture(action = listOf(statement("launch")))
            val input = base + record("functions" to base.records("functions") + record("symbol" to "Result", "parameters" to emptyList<Record>()),
                "callRelations" to base.records("callRelations") + listOf(
                    call("register", "androidx.activity.compose.rememberLauncherForActivityResult", listOf(argument("onResult", function("Result")))),
                    call("launch", "androidx.activity.compose.ManagedActivityResultLauncher.launch", listOf(argument("this", receiver, "DispatchReceiver")))),
                "functionBodies" to base.records("functionBodies").filter { it["owner"] != "Home" } + listOf(
                    body("Home", listOf(record("op" to "DECLARE", "ref" to "launcher", "value" to record("kind" to "CALL", "callId" to "register")), statement("button"))),
                    body("Result", listOf(statement("push")))))
            val result = TransitionAnalysis.create(input)
            assertEquals(if (receiver == reference("launcher")) 1 else 0, result.report.records("transitions").size)
            if (receiver == reference("launcher")) assertTrue(result.evidence.records("evidence").any { it["kind"] == "ACTIVITY_RESULT_TRANSFER" })
            val withoutClick = input + record("functionBodies" to input.records("functionBodies").map { if (it["owner"] == "Home") body("Home", listOf(statement("register"))) else it })
            assertTrue(TransitionAnalysis.create(withoutClick).report.records("transitions").isEmpty())
        }
    }
    @Test fun `material button variants are triggers but unrelated similarly named calls are not`() {
        for (name in listOf("androidx.compose.material3.TextButton", "androidx.compose.material3.FilledTonalButton", "example.TextButton")) {
            for (enabled in listOf(true, false)) {
                val base = fixture(enabled)
                val input = base + record("callRelations" to base.records("callRelations").map { if (it["id"] == "button") it + record("name" to name, "target" to name) else it })
                assertEquals(if (enabled && name.startsWith("androidx.")) 1 else 0, TransitionAnalysis.create(input).report.records("transitions").size)
            }
        }
    }
    @Test fun `source operator invoke is dispatched as a method not an arbitrary function argument`() {
        val base = fixture(action = listOf(statement("operator")))
        val input = base + record("functions" to base.records("functions") + record("symbol" to "UseCase.invoke", "parameters" to emptyList<Record>()),
            "callRelations" to base.records("callRelations") + call("operator", "UseCase.invoke", listOf(argument("this", record("kind" to "INSTANCE", "type" to "UseCase", "identity" to "usecase"), "DispatchReceiver"))),
            "functionBodies" to base.records("functionBodies") + body("UseCase.invoke", listOf(statement("push"))))
        assertEquals(1, TransitionAnalysis.create(input).report.records("transitions").size)
    }
    @Test fun `Result success and failure callbacks remain mutually exclusive and explicit throw skips success`() {
        for (throws in listOf(false, true)) {
            val base = fixture(action = listOf(statement("failure")))
            val input = base + record("functions" to base.records("functions") + listOf("Work", "Success", "Failure").map { record("symbol" to it, "parameters" to emptyList<Record>()) },
                "callRelations" to base.records("callRelations") + listOf(
                    call("catch", "kotlin.runCatching", listOf(argument("block", function("Work")))),
                    call("success", "kotlin.onSuccess", listOf(argument("this", record("kind" to "CALL", "callId" to "catch"), "ExtensionReceiver"), argument("action", function("Success")))),
                    call("failure", "kotlin.onFailure", listOf(argument("this", record("kind" to "CALL", "callId" to "success"), "ExtensionReceiver"), argument("action", function("Failure"))))),
                "functionBodies" to base.records("functionBodies") + listOf(body("Work", if (throws) listOf(record("op" to "THROW")) else emptyList()),
                    body("Success", listOf(statement("push"))), body("Failure", listOf(statement("clear")))))
            val report = TransitionAnalysis.create(input).report
            val effects = report.records("effects").associateBy { it.string("id") }
            val sequences = report.records("transitions").map { it.strings("effectRefs").map { ref -> effects.getValue(ref)["operation"] } }
            assertFalse(sequences.any { it.contains("PUSH") && it.contains("CLEAR") })
            assertEquals(!throws, sequences.any { it.contains("PUSH") })
        }
    }
    @Test fun `fair exploration reaches low fanout destinations without accepting false guards`() {
        val names = listOf("A", "B", "C", "Blocked")
        val contexts = names.map { record("id" to it, "hostRef" to "host", "keyPatterns" to listOf(constant(it))) }
        val effects = names.map { record("id" to it, "hostRef" to "host", "operation" to "PUSH", "payload" to constant(it)) }
        fun edge(id: String, from: String, to: String, guards: List<Record> = emptyList()) = record("id" to id, "originContextRefs" to listOf(from), "targetContextRefs" to listOf(to), "effectRefs" to listOf(to), "guards" to guards, "linkage" to "MAY", "completion" to "NORMAL_IN_MODELED_FLOW")
        val edges = (0..100).map { edge("loop$it", "A", "A", listOf(record("test" to reference("input$it"), "expected" to true))) } +
            listOf(edge("ab", "A", "B"), edge("bc", "B", "C"), edge("blocked", "C", "Blocked", listOf(record("test" to constant(false), "expected" to true))))
        val report = record("schemaVersion" to 1, "catalogFingerprint" to "fixture", "contexts" to contexts, "effects" to effects, "transitions" to edges,
            "initialStacks" to listOf(record("hostRef" to "host", "status" to "STRUCTURAL", "initialKeys" to listOf(constant("A")))))
        val result = AccessPaths.find(report, entries(report), record("maxStates" to 3))
        assertEquals(setOf("A", "B", "C"), result.records("paths").map { it.string("targetContextRef") }.toSet())
        assertEquals("TRUNCATED", result["coverage"])
    }
    @Test fun `a navigation prefix followed by an unsupported stack effect cannot establish an access path`() {
        val base = fixture(action = listOf(statement("push"), statement("indexed")))
        val push = base.records("callRelations").single { it["id"] == "push" }
        val indexed = push + record("id" to "indexed", "arguments" to listOf(push.records("arguments").first(), argument("index", constant(0)), push.records("arguments").last()))
        val report = TransitionAnalysis.create(base + record("callRelations" to base.records("callRelations") + indexed)).report
        assertEquals("UNSUPPORTED_EFFECT", report.records("transitions").single()["completion"])
        for (completion in listOf("UNSUPPORTED_EFFECT", "LIMIT", "THROW", "")) {
            val input = report + record("transitions" to report.records("transitions").map { it + record("completion" to completion) })
            val paths = AccessPaths.find(input, entries(input))
            assertTrue(paths.records("paths").all { it.strings("steps").isEmpty() })
            assertTrue(paths.records("frontier").any { it["code"] == "INCOMPLETE_TRANSITION" })
        }
    }
    @Test fun `different concrete event types cannot enter singleton branches unless equality is custom or unknown`() {
        val left = record("kind" to "INSTANCE", "identity" to "object:Load", "type" to "Load")
        val right = record("kind" to "INSTANCE", "identity" to "allocation:Open", "type" to "Open")
        val base = record("classes" to listOf(record("symbol" to "Load", "superTypes" to listOf("Event")), record("symbol" to "Event", "superTypes" to listOf("kotlin.Any"))),
            "callRelations" to listOf(call("equal", "kotlin.internal.ir.EQEQ", listOf(argument("left", left), argument("right", right)))))
        val expr = record("kind" to "CALL", "callId" to "equal")
        assertEquals(constant(false), FlowValues(base).eval(expr))
        assertEquals("PREDICATE", FlowValues(base + record("classes" to emptyList<Record>())).eval(expr)["kind"])
        for (owner in listOf("Load", "Event")) {
            val custom = base + record("functions" to listOf(record("symbol" to "$owner.equals", "classOwner" to owner, "simpleName" to "equals")))
            assertEquals("PREDICATE", FlowValues(custom).eval(expr)["kind"])
        }
    }
    @Test fun `repeated trigger evaluation reuses proof but distinct enabled values remain separate`() {
        val base = fixture()
        val calls = base.records("callRelations").map { if (it["id"] == "button") call("button", "androidx.compose.material3.Button", listOf(argument("onClick", function("Action")), argument("enabled", reference("enabled")))) else it }
        val statements = listOf(record("op" to "DECLARE", "ref" to "enabled", "value" to constant(false)), statement("button"),
            record("op" to "WRITE", "ref" to "enabled", "value" to constant(true)), statement("button"), statement("button"))
        val input = base + record("callRelations" to calls, "functionBodies" to base.records("functionBodies").filter { it["owner"] != "Home" } + body("Home", statements))
        val result = TransitionAnalysis.create(input).report
        assertEquals(2, result.records("triggers").size)
        assertEquals(1, result.records("transitions").size)
    }
    @Test fun `event stack is independent of render stack and exhausted handlers do not hide later buttons`() {
        fun nested(count: Int, exhausted: Boolean): Record {
            val base = fixture()
            val render = (0 until count).map { "Render$it" }
            val handlers = (0 until if (exhausted) 250 else count).map { "Handler$it" }
            val names = render + handlers
            val calls = names.map { call(it, it, emptyList()) }
            val bodies = render.mapIndexed { i, name -> body(name, listOf(statement(render.getOrNull(i+1) ?: "button"))) } +
                handlers.mapIndexed { i, name -> body(name, listOf(statement(handlers.getOrNull(i+1) ?: "push"))) }
            return base + record("callRelations" to base.records("callRelations") + calls + call("later", "androidx.compose.material3.Button", listOf(argument("onClick", function("Later")))),
                "functionBodies" to base.records("functionBodies").filter { it["owner"] !in setOf("Home", "Action") } + bodies +
                    body("Home", listOf(statement(render.first()), statement("later"))) + body("Action", listOf(statement(handlers.first()))) + body("Later", listOf(statement("push"))),
                "functions" to base.records("functions") + (names + "Later").map { record("symbol" to it, "parameters" to emptyList<Record>()) })
        }
        val separated = TransitionAnalysis.create(nested(20, false)).report
        assertEquals(2, separated.records("transitions").size)
        val limited = TransitionAnalysis.create(nested(20, true)).report
        assertEquals(1, limited.records("transitions").size)
        assertEquals("TRUNCATED", limited["coverage"])
        assertTrue(limited.records("diagnostics").any { it["code"] == "CONTROL_FLOW_DEPTH_LIMIT" })
    }
    @Test fun `inherited composition local current resolves only its provided identity`() {
        val local = record("kind" to "COMPOSITION_LOCAL", "identity" to "navigation")
        val stream = record("kind" to "STREAM", "identity" to "channel", "ownerProven" to true)
        for (owner in listOf("CompositionLocal", "ProvidableCompositionLocal")) {
            val getter = call("current", "androidx.compose.runtime.$owner.<get-current>", listOf(argument("this", local, "DispatchReceiver")))
            val flow = FlowValues(record("callRelations" to listOf(getter)))
            val expr = record("kind" to "CALL", "callId" to "current")
            assertEquals(stream, flow.eval(expr, mapOf("@local:navigation" to stream)))
            assertEquals("COMPOSITION_LOCAL_PROVIDER_UNRESOLVED", flow.eval(expr, mapOf("@local:different" to stream))["reason"])
            assertEquals("COMPOSITION_LOCAL_PROVIDER_UNRESOLVED", flow.eval(expr)["reason"])
        }
    }
    @Test fun `render and event limits are independent explicit and fail closed`() {
        val base = fixture()
        val input = base + record("callRelations" to base.records("callRelations") + call("decoration", "androidx.compose.foundation.layout.Box", emptyList()),
            "functionBodies" to base.records("functionBodies").filter { it["owner"] != "Home" } + body("Home", List(100) { statement("decoration") } + statement("button")))
        val lowRender = TransitionAnalysis.create(input, limits = TransitionLimits(renderVisits = 100)).report
        assertTrue(lowRender.records("transitions").isEmpty())
        assertTrue(lowRender.records("diagnostics").any { it["code"] == "CONTROL_FLOW_VISIT_LIMIT" && it["detail"] == "RENDER" })
        val enough = TransitionAnalysis.create(input, limits = TransitionLimits(renderVisits = 1_000)).report
        assertEquals(1, enough.records("transitions").size)
        assertEquals(1_000, enough["limits"].record()["renderVisits"])
        val lowEvent = TransitionAnalysis.create(input, limits = TransitionLimits(renderVisits = 1_000, eventVisits = 1)).report
        assertTrue(lowEvent.records("triggers").isNotEmpty())
        assertTrue(lowEvent.records("transitions").isEmpty())
        assertTrue(lowEvent.records("diagnostics").any { it["code"] == "CONTROL_FLOW_VISIT_LIMIT" && it.string("detail").startsWith("TRIGGER:") })
        assertThrows(IllegalArgumentException::class.java) { TransitionLimits(renderVisits = 0) }
    }
    @Test fun `independent rendering branch does not constrain the following button`() {
        val base = fixture()
        val decorative = record("op" to "BRANCH", "branches" to listOf(record("test" to reference("showDecoration"), "body" to statement("decoration"))))
        val input = base + record(
            "callRelations" to base.records("callRelations") + call("decoration", "androidx.compose.foundation.layout.Box", emptyList()),
            "functionBodies" to base.records("functionBodies").filter { it["owner"] != "Home" } + body("Home", listOf(decorative, statement("button"))))
        val edges = TransitionAnalysis.create(input).report.records("transitions")
        assertEquals(1, edges.size)
        assertTrue(edges.single().records("guards").isEmpty())
        val conditional = decorative + record("branches" to listOf(record("test" to reference("showDecoration"), "body" to statement("button"))))
        val both = input + record("functionBodies" to input.records("functionBodies").filter { it["owner"] != "Home" } + body("Home", listOf(conditional, statement("button"))))
        val bothEdges = TransitionAnalysis.create(both).report.records("transitions")
        assertEquals(2, bothEdges.size)
        assertEquals(1, bothEdges.count { it.records("guards").isEmpty() })
        assertEquals(true, bothEdges.single { it.records("guards").isNotEmpty() }.records("guards").single()["expected"])
        val returning = decorative + record("branches" to listOf(record("test" to reference("hidden"), "body" to record("op" to "RETURN", "value" to constant(null)))))
        val early = input + record("functionBodies" to input.records("functionBodies").filter { it["owner"] != "Home" } + body("Home", listOf(returning, statement("button"))))
        assertEquals(false, TransitionAnalysis.create(early).report.records("transitions").single().records("guards").single()["expected"])
    }
    @Test fun `discarded style choices join but assigned callback state remains correlated`() {
        val base = fixture()
        val choice = record("kind" to "CHOICE", "choiceGroupId" to "color", "values" to listOf(constant("red"), constant("blue")),
            "alternatives" to listOf(record("test" to reference("highlighted"), "excluded" to emptyList<Record>(), "value" to constant("red")),
                record("test" to constant(true), "excluded" to listOf(reference("highlighted")), "value" to constant("blue"))))
        val input = base + record("callRelations" to base.records("callRelations") + call("style", "androidx.compose.foundation.layout.Box", listOf(argument("color", choice))),
            "functionBodies" to base.records("functionBodies").filter { it["owner"] != "Home" } + body("Home", listOf(statement("style"), statement("button"))))
        assertTrue(TransitionAnalysis.create(input).report.records("transitions").single().records("guards").isEmpty())
        val assigned = input + record("functionBodies" to input.records("functionBodies").filter { it["owner"] != "Home" } + body("Home", listOf(
            record("op" to "DECLARE", "ref" to "color", "value" to choice), statement("button"))))
        assertEquals(2, TransitionAnalysis.create(assigned).report.records("transitions").size)
    }
    @Test fun `incremental argument binding cannot mutate an existing cache key`() {
        val flow = FlowValues(emptyMap(), evaluationLimit = 2)
        val expr = record("kind" to "REFERENCE", "ref" to "argument")
        val env = mutableMapOf("argument" to constant("one"))
        assertEquals(constant("one"), flow.eval(expr, env))
        env["argument"] = constant("two")
        assertEquals(constant("two"), flow.eval(expr, env))
        env["argument"] = constant("one")
        assertEquals(constant("two"), flow.eval(expr, mapOf("argument" to constant("two"))))
        assertFalse(flow.truncated)
    }
    @Test fun `memoized values preserve environments and one exhausted context cannot starve another`() {
        val flow = FlowValues(emptyMap(), evaluationLimit = 2)
        flow.beginContext("first")
        val expr = record("kind" to "REFERENCE", "ref" to "argument")
        repeat(100) { assertEquals(constant("one"), flow.eval(expr, mapOf("argument" to constant("one")))) }
        assertEquals(constant("two"), flow.eval(expr, mapOf("argument" to constant("two"))))
        assertEquals("UNKNOWN", flow.eval(expr, mapOf("argument" to constant("three")))["kind"])
        flow.beginContext("second")
        assertEquals(constant("three"), flow.eval(expr, mapOf("argument" to constant("three"))))
        assertTrue(flow.truncated)
        assertTrue(flow.diagnostics.values.any { it["originRef"] == "first" })
    }
    private fun constant(v: Any?) = record("kind" to "CONSTANT", "value" to v)
    private fun reference(v: String) = record("kind" to "REFERENCE", "ref" to v)
    private fun function(v: String) = record("kind" to "FUNCTION", "ref" to v)
    private fun statement(call: String) = record("op" to "CALL", "value" to record("kind" to "CALL", "callId" to call))
    private fun argument(name: String, value: Record, kind: String = "Regular") = record("name" to name, "parameter" to name, "parameterKind" to kind, "value" to value)
    private fun call(id: String, name: String, args: List<Record>) = record("id" to id, "name" to name, "target" to name, "arguments" to args)
    private fun body(owner: String, statements: List<Record>) = record("owner" to owner, "body" to record("op" to "BLOCK", "statements" to statements))
    private fun fixture(enabled: Boolean = true, list: Boolean = false, action: List<Record> = listOf(statement("push"))): Record {
        val stack = record("kind" to "STACK", "identity" to "stack", "initial" to listOf(constant("home")))
        val ordinary = record("kind" to "LIST_VALUE", "identity" to "ordinary", "items" to emptyList<Record>())
        val receiver = argument("this", if (list) ordinary else stack, "DispatchReceiver")
        return record("schemaVersion" to 1, "factsCapabilities" to listOf("CONTROL_FLOW_IR_V1", "PARAMETER_DEFAULTS_V1", "ASSIGNMENTS_V1", "EXPRESSION_CONTROL_FLOW_V1", "SUPER_CALL_TARGETS_V1"),
            "functions" to listOf("Home", "Detail", "Action").map { record("symbol" to it, "parameters" to emptyList<Record>()) },
            "functionBodies" to listOf(body("Home", listOf(statement("button"))), body("Detail", emptyList()), body("Action", action)),
            "callRelations" to listOf(
                call("button", "androidx.compose.material3.Button", listOf(argument("onClick", function("Action")), argument("enabled", constant(enabled)))),
                call("push", "kotlin.collections.MutableList.add", listOf(receiver, argument("element", constant("detail")))),
                call("clear", "kotlin.collections.MutableList.clear", listOf(receiver)),
                call("pop", "kotlin.collections.removeLastOrNull", listOf(receiver))),
            "navHosts" to listOf(record("id" to "host", "arguments" to listOf(argument("backStack", stack)))),
            "navHostBindings" to listOf(record("hostId" to "host", "providerIds" to listOf("provider"))),
            "entryBindings" to listOf("Home", "Detail").map { record("id" to it, "providerId" to "provider", "keyCandidates" to listOf(constant(it.lowercase())), "content" to function(it)) })
    }
    private fun entries(report: Record) = record("schemaVersion" to 1, "catalogFingerprint" to report["catalogFingerprint"], "entries" to listOf(record(
        "id" to "launcher", "kind" to "LAUNCHER", "enabled" to "true", "applicationEnabled" to "true", "exported" to "true", "hostRefs" to listOf("host"))))

    @Test fun `direct trigger carries ordered source evidence to destination and launcher path`() {
        val result = TransitionAnalysis.create(fixture())
        val edge = result.report.records("transitions").single()
        assertEquals("RESOLVED_IN_MODEL", edge["linkage"])
        val detail = result.report.records("contexts").single { it["bindingRef"] == "Detail" }.string("id")
        assertEquals(listOf(detail), edge["targetContextRefs"])
        val paths = AccessPaths.find(result.report, entries(result.report))
        assertTrue(paths.records("paths").any { it["targetContextRef"] == detail && it.strings("steps").size == 1 })
        val evidence = result.evidence.records("evidence").map { it["id"] }.toSet()
        assertTrue(edge.strings("evidenceRefs").all { it in evidence })
        assertEquals("NOT_OBSERVED", edge["observation"])
    }
    @Test fun `disabled callback and ordinary list never make navigation edges`() {
        assertTrue(TransitionAnalysis.create(fixture(enabled = false)).report.records("transitions").isEmpty())
        assertTrue(TransitionAnalysis.create(fixture(list = true)).report.records("transitions").isEmpty())
    }
    @Test fun `clear then push is one ordered effect sequence`() {
        val r = TransitionAnalysis.create(fixture(action = listOf(statement("clear"), statement("push")))).report
        val edge = r.records("transitions").single()
        val byId = r.records("effects").associateBy { it.string("id") }
        assertEquals(listOf("CLEAR", "PUSH"), edge.strings("effectRefs").map { byId[it]?.get("operation") })
        val path = AccessPaths.find(r, entries(r)).records("paths").single { it.strings("steps").isNotEmpty() }
        assertEquals(listOf(constant("detail")), path["stack"])
    }
    @Test fun `conditional early return retains negated guard on later effect`() {
        val branch = record("op" to "BRANCH", "branches" to listOf(record("test" to reference("blocked"), "body" to record("op" to "RETURN", "value" to constant(null)))))
        val r = TransitionAnalysis.create(fixture(action = listOf(branch, statement("push")))).report
        val edge = r.records("transitions").single()
        assertEquals(false, edge.records("guards").single()["expected"])
        assertEquals("MAY", edge["linkage"])
    }
    @Test fun `unknown branch alternatives never combine independent payloads or effect order`() {
        val branch = record("op" to "BRANCH", "branches" to listOf(
            record("test" to reference("replace"), "body" to record("op" to "BLOCK", "statements" to listOf(statement("clear"), statement("push")))),
            record("test" to constant(true), "body" to statement("pop"))))
        val r = TransitionAnalysis.create(fixture(action = listOf(branch))).report
        val byId = r.records("effects").associateBy { it.string("id") }
        val sequences = r.records("transitions").map { it.strings("effectRefs").map { ref -> byId[ref]?.get("operation") } }.toSet()
        assertEquals(setOf(listOf("CLEAR", "PUSH"), listOf("POP")), sequences)
    }
    @Test fun `same stack allocation shared by ambiguous hosts is not resolved`() {
        val f = fixture()
        val doubled = f + record("navHosts" to (f.records("navHosts") + (f.records("navHosts").single() + record("id" to "host2"))))
        val r = TransitionAnalysis.create(doubled).report
        assertTrue(r.records("transitions").isEmpty())
        assertTrue(r.records("diagnostics").any { it["code"] == "STACK_HOST_IDENTITY_UNRESOLVED" })
    }
    @Test fun `pop cannot invent a previous screen after reset`() {
        val r = TransitionAnalysis.create(fixture(action = listOf(statement("clear"), statement("push"), statement("pop")))).report
        val paths = AccessPaths.find(r, entries(r))
        assertTrue(paths.records("paths").all { it.strings("steps").isEmpty() })
        assertTrue(paths.records("frontier").any { it["code"] == "POP_EMPTY_OR_LAST_ENTRY" })
    }
    @Test fun `missing facts and cross-project evidence are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TransitionAnalysis.create(emptyMap()) }
        val r = TransitionAnalysis.create(fixture()).report
        assertThrows(IllegalArgumentException::class.java) { AccessPaths.find(r, entries(r) + record("catalogFingerprint" to "other")) }
    }
    @Test fun `structurally equal predicate copies conflict and different predicates remain independent`() {
        val first = record("test" to record("kind" to "SYMBOLIC", "ref" to "ready"), "expected" to true)
        val copy = record("test" to record("ref" to "ready", "kind" to "SYMBOLIC"), "expected" to false)
        assertTrue(AccessPaths.contradictory(listOf(first, copy)))
        assertFalse(AccessPaths.contradictory(listOf(first, copy + record("test" to reference("other")))))
        assertTrue(AccessPaths.contradictory(listOf(record("test" to constant(false), "expected" to true))))
    }
    @Test fun `negated predicates conflict within one state version but not across writes`() {
        val test = reference("ready")
        val positive = record("test" to test, "expected" to true, "version" to 0)
        val negative = record("test" to record("kind" to "PREDICATE", "operation" to "kotlin.Boolean.not", "arguments" to listOf(test)), "expected" to true, "version" to 0)
        assertTrue(AccessPaths.contradictory(listOf(positive, negative)))
        assertFalse(AccessPaths.contradictory(listOf(positive, negative + record("version" to 1))))
        assertFalse(AccessPaths.contradictory(listOf(positive, negative + record("expected" to false))))
    }
    @Test fun `state versions do not make pre-login and post-login guards contradictory`() {
        val guard = record("test" to reference("loggedIn"), "expected" to false, "version" to 0)
        assertFalse(AccessPaths.contradictory(listOf(guard, guard + record("expected" to true, "version" to 1))))
        assertTrue(AccessPaths.contradictory(listOf(guard, guard + record("expected" to true))))
    }
    @Test fun `indexed insertion is not incorrectly reported as a push`() {
        val base = fixture()
        val input = base + record("callRelations" to base.records("callRelations").map { call ->
            if (call["id"] == "push") call + record("arguments" to (call.records("arguments") + argument("index", constant(0)))) else call
        })
        val result = TransitionAnalysis.create(input).report
        assertTrue(result.records("transitions").isEmpty())
        assertTrue(result.records("diagnostics").any { it["code"] == "INDEXED_STACK_MUTATION_UNSUPPORTED" })
    }
    @Test fun `false root guard cannot seed a declared host as a launcher path`() {
        val base = fixture() + record("uiRoots" to listOf(record("id" to "root", "conditions" to listOf(record("test" to constant(false))),
            "arguments" to listOf(argument("content", function("Home"))))))
        val report = TransitionAnalysis.create(base).report
        assertTrue(AccessPaths.find(report, entries(report)).records("paths").isEmpty())
    }
    @Test fun `depth limits report truncated exploration without claiming unreachable`() {
        val base = fixture()
        val report = TransitionAnalysis.create(base).report
        val limited = AccessPaths.find(report, entries(report), record("maxStates" to 1))
        assertEquals("TRUNCATED", limited["coverage"])
        assertTrue(limited.records("targets").any { it["status"] == "NO_PATH_FOUND_IN_EXPLORED_MODEL" })
    }
    @Test fun `nested user wrappers still preserve trigger and stack identity`() {
        val base = fixture()
        val wrappers = (0..11).map { "Wrapper$it" }
        val input = base + record(
            "functions" to base.records("functions") + wrappers.map { record("symbol" to it, "parameters" to emptyList<Record>()) },
            "callRelations" to base.records("callRelations") + wrappers.map { call(it, it, emptyList()) },
            "functionBodies" to base.records("functionBodies").filter { it["owner"] != "Home" } + body("Home", listOf(statement(wrappers.first()))) +
                wrappers.mapIndexed { index, name -> body(name, listOf(statement(wrappers.getOrNull(index + 1) ?: "button"))) })
        assertTrue(TransitionAnalysis.create(input).report.records("transitions").any { it["linkage"] == "RESOLVED_IN_MODEL" })
    }
    @Test fun `known unrelated event types do not take each others is branches`() {
        val flow = FlowValues(record("classes" to listOf(
            record("symbol" to "Base", "superTypes" to listOf("kotlin.Any")),
            record("symbol" to "First", "superTypes" to listOf("Base")),
            record("symbol" to "Second", "superTypes" to listOf("Base")))))
        fun test(type: String) = flow.eval(record("kind" to "TYPE_TEST", "value" to record("kind" to "INSTANCE", "type" to "First"), "testedType" to type))
        assertEquals(constant(true), test("Base"))
        assertEquals(constant(false), test("Second"))
        val unknown = FlowValues(emptyMap()).eval(record("kind" to "TYPE_TEST", "value" to record("kind" to "INSTANCE", "type" to "ExternalType"), "testedType" to "Base"))
        assertEquals("PREDICATE", unknown["kind"])
    }
}
