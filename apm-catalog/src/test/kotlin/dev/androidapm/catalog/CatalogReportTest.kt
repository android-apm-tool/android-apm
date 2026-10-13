package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogReportTest {
    private fun location(line: Int) = record("path" to "ui/Screen.kt", "line" to line, "column" to 5, "offset" to line * 10)
    private fun call(symbol: String, line: Int) = record("symbol" to symbol, "name" to "example.Screen",
        "callId" to "call-$line", "source" to location(line), "conditions" to emptyList<Any>())
    private fun declaration(symbol: String, id: String, line: Int) = record("id" to id, "symbol" to symbol,
        "name" to "example.Screen", "source" to location(line), "parameters" to listOf(record("ref" to "long-signature")))

    private fun fixture(): Record = record(
        "schemaVersion" to 1, "moduleId" to "app", "variant" to "debug",
        "collectionStatus" to "COMPLETE_FOR_DECLARED_INPUTS", "resolutionStatus" to "PARTIAL",
        "scope" to record("files" to listOf("ui/Screen.kt")),
        "composableDeclarations" to listOf(declaration("example.Screen()", "screen", 10), declaration("example.Screen(Int)", "overload", 20)),
        "functions" to emptyList<Any>(), "values" to listOf(record("ref" to "huge-internal-value")),
        "callRelations" to listOf(record("owner" to "render", "name" to "example.Screen", "composable" to true)),
        "screenCandidates" to listOf(record("id" to "route:render", "classification" to "UNCLASSIFIED", "resolution" to "PARTIAL",
            "source" to location(30), "arguments" to listOf(
                record("name" to "path", "value" to record("kind" to "CONSTANT", "value" to "home")),
                record("name" to "render", "composableType" to true, "value" to record("kind" to "FUNCTION", "ref" to "render"))),
            "composableCalls" to listOf(call("example.Screen()", 30), call("example.Screen()", 31), call("example.Screen(Int)", 32),
                call("external.Screen()", 33)))),
        "entryDeclarations" to listOf(record("id" to "entry")),
        "entryBindings" to listOf(record("id" to "entry-binding", "providerId" to null, "context" to "REGISTRATION",
            "classification" to "UNCLASSIFIED", "resolution" to "PARTIAL", "source" to location(40),
            "keyCandidates" to listOf(record("kind" to "TYPE", "type" to "K", "typeParameter" to "long#type:K")),
            "conditions" to listOf(record("kind" to "CALL", "name" to "example.enabled", "callId" to "raw-call-id", "source" to location(39))),
            "composableCalls" to listOf(call("example.Screen()", 41)))),
        "diagnostics" to listOf(record("code" to "ANALYSIS_LIMIT_REACHED", "source" to location(40)),
            record("code" to "ANALYSIS_LIMIT_REACHED", "source" to location(41))),
        "missingModules" to listOf(":missing"),
    )

    @Test fun `public output preserves UI identity and uncertainty without raw IR`() {
        val report = CatalogReport.create(fixture())
        assertEquals(2, report["schemaVersion"])
        assertEquals("PARTIAL", report["resolutionStatus"])
        assertEquals(listOf(":missing"), report["missingModules"])
        assertFalse(report.containsKey("callRelations"))
        assertFalse(report.containsKey("functions"))
        assertFalse(report.containsKey("values"))
        val candidate = report.records("screenCandidates").single()
        assertEquals(mapOf("path" to "home"), candidate["arguments"])
        assertEquals(listOf("example.Screen"), candidate["contentCalls"])
        assertEquals("UNCLASSIFIED", candidate["classification"])
        assertEquals("PARTIAL", candidate["resolution"])
        assertEquals(record("path" to "ui/Screen.kt", "line" to 30), candidate["source"])
        assertEquals(3, (candidate["composableIds"] as List<*>).size)
        val definitions = report.records("composables")
        assertEquals(3, definitions.size, "Repeated calls deduplicate, overloaded definitions remain distinct")
        val ids = definitions.map { it["id"] }.toSet()
        assertTrue((candidate["composableIds"] as List<*>).all { it in ids })
        assertEquals(true, definitions.single { it["id"] !in setOf("screen", "overload") }["external"])
        val binding = report.records("entryBindings").single()
        assertEquals("K", binding.records("keyCandidates").single()["typeParameter"])
        assertNull(binding["providerId"])
        assertEquals("example.enabled", binding.records("conditions").single()["name"])
        assertFalse(Json.encode(report).contains("raw-call-id"))
        assertEquals(2, report.records("diagnostics").single()["count"])
        assertEquals(2, report.records("diagnostics").single().records("sources").size)
    }

    @Test fun `empty modules and merged scope remain explicit and deterministic`() {
        val empty = CatalogLinker.link(listOf(record("schemaVersion" to 1, "moduleId" to "empty", "variant" to "debug",
            "collectionStatus" to "COMPLETE_FOR_DECLARED_INPUTS", "scope" to record("files" to emptyList<String>()))))
        val report = CatalogReport.create(empty)
        assertEquals(emptyList<Any>(), report["screenCandidates"])
        assertEquals(emptyList<Any>(), report["entryBindings"])
        assertEquals(emptyList<Any>(), report["composables"])
        val merged = fixture() + record("scope" to record("modules" to listOf(
            record("moduleId" to "z", "scope" to record("files" to listOf("z.kt"))),
            record("moduleId" to "a", "scope" to record("files" to listOf("a.kt", "b.kt"))))))
        val result = CatalogReport.create(merged)
        assertEquals(listOf("a", "z"), result["modules"])
        assertEquals(3, result["summary"].record()["sourceFiles"])
        assertEquals(Json.encode(result), Json.encode(CatalogReport.create(merged)))
    }

    @Test fun `multiple content slots do not mix their direct calls and named references keep their identity`() {
        val base = fixture()
        val arguments = listOf(
            record("name" to "header", "composableType" to true, "value" to record("kind" to "FUNCTION", "ref" to "header-lambda")),
            record("name" to "body", "composableType" to true, "value" to record("kind" to "FUNCTION", "ref" to "example.Screen()")),
        )
        val catalog = base + record(
            "callRelations" to listOf(
                record("owner" to "header-lambda", "name" to "example.Header", "composable" to true),
                record("owner" to "example.Screen()", "name" to "example.InnerText", "composable" to true)),
            "screenCandidates" to listOf("header", "body").map { slot ->
                base.records("screenCandidates").single() + record("id" to "container:$slot", "arguments" to arguments)
            },
        )
        val candidates = CatalogReport.create(catalog).records("screenCandidates").associateBy { it.string("id") }
        assertEquals(listOf("example.Header"), candidates.getValue("container:header")["contentCalls"])
        assertEquals(listOf("example.Screen"), candidates.getValue("container:body")["contentCalls"])
    }
}
