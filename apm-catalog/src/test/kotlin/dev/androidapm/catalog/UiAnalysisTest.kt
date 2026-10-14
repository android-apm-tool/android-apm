package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UiAnalysisTest {
    private fun function(symbol: String, preview: Boolean = false, extra: Record = emptyMap()) = record(
        "symbol" to symbol, "id" to symbol, "name" to symbol, "simpleName" to symbol, "composable" to true,
        "hasBody" to true, "preview" to preview, "sourceSet" to "main", "source" to record("path" to "$symbol.kt", "line" to 1)) + extra
    private fun call(owner: String, target: String, args: List<Record> = emptyList(), extra: Record = emptyMap()) = record(
        "id" to "$owner->$target", "owner" to owner, "target" to target, "name" to target,
        "composable" to true, "arguments" to args, "source" to record("path" to "$owner.kt", "line" to 2)) + extra
    private fun content(ref: String) = record("kind" to "FUNCTION", "ref" to ref)
    private fun argument(name: String, value: Record, composable: Boolean = false) = record("name" to name, "parameter" to name, "value" to value, "composableType" to composable)
    private fun linked(functions: List<Record>, calls: List<Record>, extra: Record = emptyMap()): Record = CatalogLinker.link(listOf(record(
        "moduleId" to "fixture", "variant" to "debug", "scope" to record("files" to listOf("Ui.kt")),
        "functions" to functions, "composableDeclarations" to functions, "callRelations" to calls,
        "uiRoots" to listOf(record("id" to "root", "arguments" to listOf(argument("content", content("Root")))))) + extra))
    private fun Record.declaration(id: String) = records("declarations").single { it["id"] == id }

    @Test fun `preview metadata and production usage coexist without exclusion`() {
        val ui = UiAnalysis.create(linked(listOf(function("Root"), function("Shared", true), function("Only", true), function("Child")),
            listOf(call("Root", "Shared"), call("Only", "Child")))).report
        assertEquals("LINKED", ui.declaration("Shared")["bindingStatus"])
        assertTrue("PREVIEW_REACHABLE" in ui.declaration("Shared").strings("reasonCodes"))
        assertEquals("PREVIEW_REACHABLE", ui.declaration("Child")["primaryReason"])
        assertEquals("NOT_PROVEN", ui.declaration("Child")["unusedStatus"])
        assertEquals("UNLINKED", ui.declaration("Only")["bindingStatus"])
    }

    @Test fun `preview suffix without annotation is not preview evidence`() {
        val ui = UiAnalysis.create(linked(listOf(function("Root"), function("LooksLikePreview")), emptyList())).report
        assertEquals("NO_REFERENCE_IN_SCOPE", ui.declaration("LooksLikePreview")["primaryReason"])
    }

    @Test fun `event reference is preserved but never becomes a composition edge`() {
        val facts = linked(listOf(function("Root"), function("Event")), listOf(call("Root", "androidx.compose.foundation.clickable",
            listOf(argument("onClick", content("Event"))))))
        val ui = UiAnalysis.create(facts).report
        assertEquals("UNLINKED", ui.declaration("Event")["bindingStatus"])
        assertFalse(ui.records("usages").any { it["declarationId"] == "Event" })
        assertTrue("CALLBACK_FLOW_UNRESOLVED" in ui.declaration("Event").strings("reasonCodes"))
    }

    @Test fun `recursive disconnected group is not called live or dead`() {
        val ui = UiAnalysis.create(linked(listOf(function("Root"), function("A"), function("B")), listOf(call("A", "B"), call("B", "A")))).report
        for (id in listOf("A", "B")) {
            assertEquals("UNROOTED_REFERENCE_GROUP", ui.declaration(id)["primaryReason"])
            assertEquals("NOT_PROVEN", ui.declaration(id)["unusedStatus"])
        }
    }

    @Test fun `framework override has explicit unsupported root evidence`() {
        val ui = UiAnalysis.create(linked(listOf(function("Root"), function("ViewContent", extra = record(
            "overrides" to listOf("androidx.compose.ui.platform.AbstractComposeView.Content")))), emptyList())).report
        assertEquals("ROOT_NOT_MODELED", ui.declaration("ViewContent")["primaryReason"])
    }

    @Test fun `unknown builder frontier diagnoses its callback only`() {
        val ui = UiAnalysis.create(linked(listOf(function("Root"), function("Callback"), function("Nested"), function("Unrelated")),
            listOf(call("Root", "vendor.Builder", listOf(argument("builder", content("Callback")))), call("Callback", "Nested")))).report
        assertTrue("CALLBACK_FLOW_UNRESOLVED" in ui.declaration("Nested").strings("reasonCodes"))
        assertEquals("NO_REFERENCE_IN_SCOPE", ui.declaration("Unrelated")["primaryReason"])
        assertTrue(ui.declaration("Nested").records("findings").all { it["association"] == "REFERENCED_BEHIND_FRONTIER" })
    }

    @Test fun `same function retains sheet and page usage identities`() {
        val dialog = "androidx.compose.ui.window.Dialog"
        val ui = UiAnalysis.create(linked(listOf(function("Root"), function("Shared")), listOf(
            call("Root", "Shared"), call("Root", dialog, listOf(argument("content", content("Shared"), true))),
            call("Shared", "androidx.compose.material3.Text")))).report
        assertTrue(ui.records("boundaries").any { it["kind"] == "DIALOG" })
        val shared = ui.records("usages").filter { it["declarationId"] == "Shared" }
        assertEquals(2, shared.size)
        assertEquals(2, shared.flatMap { it.strings("boundaryIds") }.distinct().size)
        assertTrue(shared.flatMap { it.strings("evidenceRefs") }.isNotEmpty())
        assertEquals("LINKED", ui.declaration("Shared")["bindingStatus"])
    }

    @Test fun `content storage is not promoted to destination by its path name`() {
        val container = record("id" to "storage", "name" to "vendor.AppRoute", "arguments" to listOf(argument("path", record("kind" to "CONSTANT", "value" to "home"))),
            "contentArguments" to listOf(argument("render", content("Stored"), true)))
        val ui = UiAnalysis.create(linked(listOf(function("Root"), function("Stored")), emptyList(), record("contentContainers" to listOf(container)))).report
        val boundary = ui.records("boundaries").single { it["bindingId"] == "storage:render" }
        assertEquals("CONTENT_CANDIDATE", boundary["kind"])
        assertEquals("CANDIDATE", boundary["definitionStatus"])
        assertEquals("NOT_EVALUATED", boundary["runtimeReachability"])
    }

    @Test fun `conditions stay separate from roles and never claim feasible runtime state`() {
        val ui = UiAnalysis.create(linked(listOf(function("Root"), function("Child")), listOf(call("Root", "Child", extra = record(
            "conditions" to listOf(record("kind" to "REFERENCE", "ref" to "loading"))))))).report
        assertEquals("NOT_SOLVED", ui.records("scenarios").single()["feasibility"])
        assertTrue(ui.records("usages").none { it["role"] == "STATE" })
    }

    @Test fun `destination content does not need a named Screen function`() {
        val lambda = function("EntryLambda", extra = record("kind" to "LAMBDA"))
        val entry = record("id" to "entry", "owner" to "Provider", "content" to content("EntryLambda"), "key" to record("kind" to "CONSTANT", "value" to "home"))
        val provider = record("id" to "provider", "arguments" to listOf(argument("builder", content("Provider"))))
        val ui = UiAnalysis.create(linked(listOf(function("Root"), function("Provider"), lambda, function("Header"), function("Feed")),
            listOf(call("Provider", "register", extra = record("id" to "entry")), call("EntryLambda", "Header"), call("EntryLambda", "Feed"),
                call("Header", "androidx.compose.material3.Text"), call("Feed", "androidx.compose.material3.Text")),
            record("entryDeclarations" to listOf(entry), "providers" to listOf(provider)))).report
        assertEquals(1, ui.records("boundaries").count { it["kind"] == "DESTINATION" })
        val contentUsages = ui.records("usages").filter { it["declarationId"] in setOf("Header", "Feed") }
        assertEquals(2, contentUsages.size)
        assertTrue(contentUsages.all { it["role"] == "SCREEN_CONTENT" })
    }

    @Test fun `immutable temporary path resolves but mutable path is not guessed`() {
        val ref = record("kind" to "REFERENCE", "ref" to "temporary")
        val container = record("id" to "storage", "name" to "vendor.AppRoute", "arguments" to listOf(argument("path", ref)),
            "contentArguments" to listOf(argument("render", content("Stored"), true)))
        for (mutable in listOf(false, true)) {
            val facts = linked(listOf(function("Root"), function("Stored")), emptyList(), record("contentContainers" to listOf(container),
                "values" to listOf(record("ref" to "temporary", "mutable" to mutable, "value" to record("kind" to "CONSTANT", "value" to "home")))))
            val path = facts.records("screenCandidates").single().records("arguments").single()["value"].record()
            assertEquals(if (mutable) "REFERENCE" else "CONSTANT", path["kind"])
        }
    }

    @Test fun `all usages and findings point to existing evidence and output is deterministic`() {
        val facts = linked(listOf(function("Root"), function("Child")), listOf(call("Root", "Child")))
        val a = UiAnalysis.create(facts)
        val b = UiAnalysis.create(facts)
        assertEquals(Json.encode(a.report), Json.encode(b.report))
        val ids = a.evidence.records("evidence").map { it.string("id") }.toSet()
        assertTrue(a.report.records("usages").flatMap { it.strings("evidenceRefs") }.all { it in ids })
    }
    @Test fun `catalog identity changes when stored values or engine target evidence changes`() {
        val facts = linked(listOf(function("Root")), emptyList())
        val original = UiAnalysis.create(facts).report["catalogFingerprint"]
        assertNotEquals(original, UiAnalysis.create(facts + record("values" to listOf(record("ref" to "route", "value" to "home")))).report["catalogFingerprint"])
        assertNotEquals(original, UiAnalysis.create(facts + record("analysisCallTargets" to listOf(record("callId" to "dispatch", "targets" to listOf("Other"))))).report["catalogFingerprint"])
    }
    @Test fun `shared branch predicates are stored once and their affected calls remain inspectable`() {
        val guard = record("conditions" to listOf(record("kind" to "REFERENCE", "ref" to "loading")))
        val result = UiAnalysis.create(linked(listOf(function("Root"), function("A"), function("B")),
            listOf(call("Root", "A", extra = guard), call("Root", "B", extra = guard))))
        val scenario = result.report.records("scenarios").single()
        assertFalse(scenario.containsKey("condition"))
        val condition = result.evidence.records("evidence").single { it["id"] == scenario["conditionRef"] }
        assertEquals("loading", condition["expression"].record()["ref"])
        assertEquals(3, scenario.strings("evidenceRefs").size)
    }
}
