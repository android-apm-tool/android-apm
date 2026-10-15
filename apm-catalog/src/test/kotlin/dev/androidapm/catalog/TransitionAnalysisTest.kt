package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransitionAnalysisTest {
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
        return record("schemaVersion" to 1, "factsCapabilities" to listOf("CONTROL_FLOW_IR_V1", "PARAMETER_DEFAULTS_V1", "ASSIGNMENTS_V1"),
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
