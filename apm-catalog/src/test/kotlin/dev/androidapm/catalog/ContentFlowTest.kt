package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContentFlowTest {
    private fun fixture(name: String, argument: Record): Record = record("moduleId" to "test", "variant" to "debug",
        "functions" to listOf(record("symbol" to "root"), record("symbol" to "lambda"), record("symbol" to "Screen")),
        "composableDeclarations" to listOf(record("id" to "screen", "symbol" to "Screen", "name" to "Screen", "source" to record("path" to "Ui.kt", "line" to 1))),
        "uiRoots" to listOf(record("id" to "root", "arguments" to listOf(record("name" to "content", "value" to record("kind" to "FUNCTION", "ref" to "root"))))),
        "callRelations" to listOf(record("id" to "slot", "owner" to "root", "target" to name, "name" to name, "arguments" to listOf(argument)),
            record("id" to "screen-call", "owner" to "lambda", "target" to "Screen", "name" to "Screen", "composable" to true)))

    @Test fun `lazy builders and inline loops follow content but not event arguments`() {
        for ((name, argument) in listOf("androidx.compose.foundation.lazy.LazyColumn" to "content",
            "androidx.compose.foundation.lazy.LazyRow" to "content", "kotlin.repeat" to "action",
            "kotlin.collections.forEachIndexed" to "action")) {
            val facts = fixture(name, record("name" to argument, "value" to record("kind" to "FUNCTION", "ref" to "lambda")))
            val root = CatalogLinker.link(listOf(facts)).records("uiRootBindings").single()
            assertEquals(listOf("Screen"), root.records("composableCalls").map { it["name"] }, name)
        }
        val click = fixture("androidx.compose.foundation.clickable", record("name" to "onClick",
            "value" to record("kind" to "FUNCTION", "ref" to "lambda")))
        assertTrue(CatalogLinker.link(listOf(click)).records("uiRootBindings").single().records("composableCalls").isEmpty())
        assertFalse(ContentSemantics.follows("kotlin.repeat", record("name" to "times")))
    }

    @Test fun `unresolved external slot is partial and its unlinked declarations survive projection`() {
        val facts = fixture("vendor.Widget", record("name" to "content", "composableType" to true,
            "value" to record("kind" to "UNKNOWN")))
        val linked = CatalogLinker.link(listOf(facts))
        assertEquals("PARTIAL", linked.records("uiRootBindings").single()["resolution"])
        assertEquals("PARTIAL", linked["resolutionStatus"])
        assertTrue(linked.records("diagnostics").any { it["code"] == "UNRESOLVED_CONTENT_FLOW" })
        val report = CatalogReport.create(linked)
        assertEquals(listOf("screen"), report["unlinkedComposableIds"])
        assertEquals("Screen", report.records("composables").single()["name"])
        assertEquals(0, report["summary"].record()["referencedComposables"])
    }

    @Test fun `unknown ordinary builder containing UI warns without treating it as executed content`() {
        val facts = fixture("vendor.CustomBuilder", record("name" to "builder",
            "value" to record("kind" to "FUNCTION", "ref" to "lambda")))
        val linked = CatalogLinker.link(listOf(facts))
        assertTrue(linked.records("diagnostics").any { it["code"] == "UNMODELED_CALLBACK_CONTENT" })
        val root = linked.records("uiRootBindings").single()
        assertEquals("PARTIAL", root["resolution"])
        assertTrue(root.records("composableCalls").isEmpty())
        assertEquals(listOf("screen"), CatalogReport.create(linked)["unlinkedComposableIds"])
    }
}
