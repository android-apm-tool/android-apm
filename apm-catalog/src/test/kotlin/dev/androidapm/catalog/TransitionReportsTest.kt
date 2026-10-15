package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransitionReportsTest {
    @Test fun `enum domain exclusions survive compaction without conflating types operands or versions`() {
        fun guard(entry: String, type: String = "fixture.Mode", operand: String = "mode", reversed: Boolean = false) : Record {
            val args = listOf(record("kind" to "SYMBOLIC", "ref" to operand, "type" to type), record("kind" to "ENUM", "ref" to "fixture.Mode.$entry"))
            return record("test" to record("kind" to "PREDICATE", "operation" to "kotlin.internal.ir.EQEQ", "arguments" to if (reversed) args.reversed() else args), "expected" to true)
        }
        fun compact(guards: List<Record>): List<Record> = TransitionReports.compact(TransitionResult(record("transitions" to listOf(record("guards" to guards))), record("evidence" to emptyList<Record>())))
            .report.records("transitions").single().records("guards")
        val conflict = listOf(guard("OPEN"), guard("CLOSED", reversed = true))
        assertTrue(AccessPaths.contradictory(conflict))
        assertTrue(AccessPaths.contradictory(compact(conflict)))
        assertFalse(AccessPaths.contradictory(compact(conflict.mapIndexed { index, g -> g + record("version" to index) })))
        assertFalse(AccessPaths.contradictory(compact(listOf(guard("OPEN"), guard("CLOSED", operand = "different")))))
        assertFalse(AccessPaths.contradictory(compact(listOf(guard("OPEN", type = "kotlin.Any"), guard("CLOSED", type = "kotlin.Any")))))
        assertTrue(AccessPaths.contradictory(compact(listOf(guard("OPEN"), guard("OPEN", reversed = true) + record("expected" to false)))))
        assertFalse(AccessPaths.contradictory(compact(listOf(guard("OPEN"), guard("CLOSED") + record("expected" to false)))))
    }
    private fun constant(value: Any?) = record("kind" to "CONSTANT", "value" to value)
    private fun predicate() = record("kind" to "PREDICATE", "operation" to "input", "description" to "반복되는 조건".repeat(1000))
    @Test fun `shared conditions keep contradictions state versions and reachable targets unchanged`() {
        val test = predicate()
        val negated = record("kind" to "PREDICATE", "operation" to "kotlin.Boolean.not", "arguments" to listOf(test))
        fun guard(value: Record, expected: Boolean) = record("test" to value, "expected" to expected)
        val contexts = listOf("Home", "Detail", "Blocked", "False").map { record("id" to it, "hostRef" to "host", "keyPatterns" to listOf(constant(it))) }
        val effects = listOf("Detail", "Blocked", "False", "unused").map { record("id" to it, "hostRef" to "host", "operation" to "PUSH", "payload" to constant(it)) }
        fun edge(to: String, guards: List<Record>) = record("id" to to, "originContextRefs" to listOf("Home"), "targetContextRefs" to listOf(to), "effectRefs" to listOf(to), "guards" to guards, "completion" to "NORMAL_IN_MODELED_FLOW", "linkage" to "MAY")
        val report = record("schemaVersion" to 1, "catalogFingerprint" to "fixture", "contexts" to contexts, "effects" to effects,
            "initialStacks" to listOf(record("hostRef" to "host", "status" to "STRUCTURAL", "initialKeys" to listOf(constant("Home")))),
            "transitions" to listOf(edge("Detail", listOf(guard(test, true))), edge("Blocked", listOf(guard(test, true), guard(negated, true))), edge("False", listOf(guard(constant(false), true)))))
        val compact = TransitionReports.compact(TransitionResult(report, record("evidence" to emptyList<Record>())))
        val entries = record("schemaVersion" to 1, "catalogFingerprint" to "fixture", "entries" to listOf(record("id" to "launcher", "kind" to "LAUNCHER", "enabled" to "true", "applicationEnabled" to "true", "exported" to "true", "hostRefs" to listOf("host"))))
        val before = AccessPaths.find(report, entries)
        val after = AccessPaths.find(compact.report, entries)
        assertEquals(before["targets"], after["targets"])
        assertEquals(before.records("paths").map { it["steps"] }, after.records("paths").map { it["steps"] })
        assertEquals(setOf("Home", "Detail"), after.records("paths").map { it["targetContextRef"] }.toSet())
        assertEquals(1, compact.evidence.records("evidence").count { it["kind"] == "PREDICATE_DEFINITION" })
        assertFalse(compact.report.records("effects").any { it["id"] == "unused" })
        val opposite = compact.report.records("transitions")[1].records("guards")
        assertTrue(AccessPaths.contradictory(opposite))
        assertFalse(AccessPaths.contradictory(opposite.mapIndexed { i, g -> g + record("version" to i) }))
        assertEquals(compact, TransitionReports.compact(compact))
        assertTrue(Json.encode(compact.report).length < Json.encode(report).length / 3)
    }
    @Test fun `trace DAG preserves repeats order and transfer proofs and rejects corruption`() {
        val trace = listOf("a", "b", "a", "transfer", "b")
        val edge = record("id" to "edge", "evidenceRefs" to trace)
        val original = TransitionResult(record("transitions" to listOf(edge), "effects" to emptyList<Record>()), record("evidence" to listOf(
            record("id" to "a", "kind" to "CALL", "callRef" to "call-a"), record("id" to "b", "kind" to "CALL", "callRef" to "call-b"), record("id" to "transfer", "kind" to "CHANNEL_TRANSFER"))))
        val compact = TransitionReports.compact(original)
        val proofs = compact.evidence.records("evidence").associateBy { it.string("id") }
        val row = compact.report.records("transitions").single()
        assertEquals(trace, TransitionReports.trace(row, proofs))
        assertEquals(listOf("transfer"), row.strings("evidenceRefs"))
        assertThrows(IllegalStateException::class.java) { TransitionReports.trace(row, proofs - row.string("traceRef")) }
        assertThrows(IllegalArgumentException::class.java) { TransitionReports.trace(row, proofs - "a") }
        val cycle = proofs.getValue(row.string("traceRef")) + record("previousRef" to row["traceRef"])
        assertThrows(IllegalArgumentException::class.java) { TransitionReports.trace(row, proofs + (row.string("traceRef") to cycle)) }
    }
}
