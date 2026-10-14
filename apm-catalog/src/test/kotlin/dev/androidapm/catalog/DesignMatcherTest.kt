package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DesignMatcherTest {
    private fun source(path: String) = record("path" to path, "line" to 1)
    private val ui = record("catalogFingerprint" to "current", "declarations" to listOf(
        record("id" to "a", "name" to "demo.WatcherRow", "source" to source("A.kt"), "bindingStatus" to "LINKED"),
        record("id" to "b", "name" to "demo.WatcherRow", "source" to source("B.kt"), "bindingStatus" to "UNLINKED")),
        "boundaries" to listOf(record("id" to "screen", "kind" to "CONTENT_CANDIDATE", "declarationIds" to listOf("a"),
            "hints" to record("path" to record("kind" to "CONSTANT", "value" to "watchers")), "textFeatures" to listOf("감시자 초대"))),
        "scenarios" to listOf(record("id" to "state", "boundaryId" to "screen")))
    private fun spec(review: Record = emptyMap(), name: String = "감시자") = record("schemaVersion" to 1,
        "cases" to listOf(record("id" to "frame", "name" to name, "review" to review)))
    private fun review(path: String = "A.kt") = record("origin" to "MANUAL", "expectedPath" to "watchers",
        "anchors" to listOf(record("name" to "demo.WatcherRow", "source" to source(path))))

    @Test fun `same name in a different file cannot satisfy the reviewed anchor`() {
        val row = DesignMatcher.match(ui, spec(review("B.kt"))).records("cases").single()
        val anchor = row["reviewed"].record().records("anchors").single()
        assertEquals("b", anchor["declarationId"])
        assertEquals("NOT_LINKED", anchor["expectedContext"])
    }
    @Test fun `located code does not confirm a design state or observed render`() {
        val result = DesignMatcher.match(ui, spec(review()))
        val reviewed = result.records("cases").single()["reviewed"].record()
        assertEquals("SOURCE_ANCHORS_VERIFIED", reviewed["status"])
        assertEquals("NOT_VERIFIED", reviewed["scenarioStatus"])
        assertEquals("NOT_OBSERVED", reviewed["renderStatus"])
        assertEquals(0, result["summary"].record()["automaticConfirmedMatches"])
        assertNull(result["evaluation"].record()["recall"])
    }
    @Test fun `review knowledge never leaks into automatic candidate retrieval`() {
        val with = DesignMatcher.match(ui, spec(review(), "unrelatedzz")).records("cases").single()["automatic"]
        val without = DesignMatcher.match(ui, spec(name = "unrelatedzz")).records("cases").single()["automatic"]
        assertEquals(without, with)
        assertEquals("NO_CANDIDATE", with.record()["status"])
    }
    @Test fun `stale review is invalidated even when symbol still exists`() {
        val reviewed = DesignMatcher.match(ui, spec(review() + record("catalogFingerprint" to "old"))).records("cases").single()["reviewed"].record()
        assertEquals("STALE_REVIEW", reviewed["status"])
        assertEquals("UNKNOWN", reviewed["implementationStatus"])
    }
    @Test fun `condition evidence from another boundary cannot validate scenario`() {
        val reviewed = DesignMatcher.match(ui, spec(review("B.kt") + record("conditionEvidenceIds" to listOf("state"))))
            .records("cases").single()["reviewed"].record()
        assertEquals("INVALID_CONDITION_REFERENCES", reviewed["scenarioStatus"])
    }
    @Test fun `one frame may reference multiple code units and one unit multiple frames`() {
        val design = record("schemaVersion" to 1, "cases" to listOf(
            record("id" to "one", "name" to "감시자", "review" to review()),
            record("id" to "two", "name" to "빈 감시자", "review" to review())))
        assertEquals(2, DesignMatcher.match(ui, design)["summary"].record()["reviewedSourceLocatedCases"])
    }
    @Test fun `unresolved boundary and global event hosts are not mislabeled missing content`() {
        val unknown = DesignMatcher.match(ui, spec(review() + record("expectedPath" to "unknown-path"))).records("cases").single()
        assertTrue("EXPECTED_BOUNDARY_UNRESOLVED" in unknown.strings("issues"))
        assertFalse("EXPECTED_CONTEXT_NOT_LINKED" in unknown.strings("issues"))
        val event = review() + record("anchors" to review().records("anchors").map { it + record("relationToRoute" to "EVENT_HOSTED_NOT_EVALUATED") })
        val row = DesignMatcher.match(ui, spec(event)).records("cases").single()
        assertTrue("EVENT_DISPLAY_CONNECTION_NOT_EVALUATED" in row.strings("issues"))
        assertFalse("EXPECTED_CONTEXT_NOT_LINKED" in row.strings("issues"))
    }
    @Test fun `duplicate design IDs fail before publishing`() {
        val case = record("id" to "duplicate")
        assertThrows(IllegalArgumentException::class.java) { DesignMatcher.match(ui, record("schemaVersion" to 1, "cases" to listOf(case, case))) }
    }
    @Test fun `preview overlays never enter production design retrieval`() {
        val previewUi = ui + record("boundaries" to listOf(record("id" to "preview-dialog", "kind" to "DIALOG", "origin" to "PREVIEW",
            "declarationIds" to listOf("a"), "textFeatures" to listOf("감시자"))))
        val row = DesignMatcher.match(previewUi, spec()).records("cases").single()
        assertEquals("NO_CANDIDATE", row["automatic"].record()["status"])
    }
    @Test fun `evaluation counts duplicate predictions and abstentions without manual credit`() {
        val truth = record("independent" to true, "version" to "fixture", "targets" to listOf(
            record("id" to "one", "inScope" to true, "contextKey" to "A"), record("id" to "two", "inScope" to true, "contextKey" to "B")))
        val match = record("truthId" to "one", "contextKey" to "A", "origin" to "AUTOMATIC", "status" to "CONFIRMED")
        val result = DiscoveryEvaluator.evaluate(listOf(match, match, record("truthId" to "two", "contextKey" to "B", "origin" to "MANUAL", "status" to "CONFIRMED")), truth)
        assertEquals(1, result["truePositive"])
        assertEquals(1, result["falsePositive"])
        assertEquals(1, result["falseNegative"])
        assertEquals(0.5, result["recall"])
    }
    @Test fun `evaluation rejects unspecified contexts and records predeclared exclusions separately`() {
        assertThrows(IllegalArgumentException::class.java) { DiscoveryEvaluator.evaluate(emptyList(),
            record("independent" to true, "targets" to listOf(record("id" to "one", "inScope" to true)))) }
        val result = DiscoveryEvaluator.evaluate(listOf(record("truthId" to "external", "status" to "CONFIRMED", "origin" to "AUTOMATIC")),
            record("independent" to true, "targets" to listOf(record("id" to "external", "contextKey" to "browser", "inScope" to false))))
        assertEquals(1, result["outOfScopePredictions"])
        assertEquals(0, result["falsePositive"])
        assertNull(result["recall"])
    }
}
