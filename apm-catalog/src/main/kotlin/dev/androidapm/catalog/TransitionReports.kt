package dev.androidapm.catalog

/** Lossless shared conditions and ordered proof traces; never weakens a path condition. */
object TransitionReports {
    fun compact(input: TransitionResult): TransitionResult {
        if (input.report["evidenceFormat"] == "SHARED_CONDITIONS_TRACES_V1") return input
        val definitions = linkedMapOf<String, Record>()
        val predicates = java.util.IdentityHashMap<Record, Record>()
        val proofs = input.evidence.records("evidence").associateBy { it.string("id") }
        val prefixes = HashMap<Pair<String, String>, String>()
        fun condition(guard: Record): Record {
            var test = guard["test"].record()
            var expected = guard["expected"]
            var depth = 0
            while (test["kind"] == "PREDICATE" && test["operation"] == "kotlin.Boolean.not" && test.records("arguments").size == 1 && depth++ < 64) {
                test = test.records("arguments").single()
                expected = expected != true
            }
            if (test["kind"] == "CONSTANT") return guard + record("test" to test, "expected" to expected)
            val reference = predicates.getOrPut(test) {
                val id = "condition:" + fingerprint(test)
                definitions.putIfAbsent(id, record("id" to id, "kind" to "PREDICATE_DEFINITION", "expression" to test))
                record("kind" to "PREDICATE_REF", "ref" to id) + (AccessPaths.enumConstraint(test)?.let { record("enumConstraint" to it) } ?: emptyMap())
            }
            return guard + record("test" to reference, "expected" to expected)
        }
        fun compact(row: Record): Record {
            var result = if (row.containsKey("guards")) row + record("guards" to row.records("guards").map(::condition)) else row
            if (!row.containsKey("evidenceRefs")) return result
            val trace = row.strings("evidenceRefs")
            var previous = ""
            for (ref in trace) {
                require(ref in proofs) { "BROKEN_EVIDENCE_REFERENCE" }
                previous = prefixes.getOrPut(previous to ref) {
                    val node = record("kind" to "TRACE_NODE", "previousRef" to previous.takeIf { it.isNotEmpty() }, "evidenceRef" to ref)
                    val id = "trace:" + fingerprint(node)
                    definitions[id] = record("id" to id) + node
                    id
                }
            }
            // The full trace (including repeats and order) is recoverable via traceRef.
            // Keep direct and async transfer proofs convenient for simple consumers.
            val direct = trace.filter { proofs.getValue(it)["kind"] != "CALL" || proofs.getValue(it)["callRef"] == row["callRef"] }.distinct()
            result = result + record("evidenceRefs" to direct, "traceRef" to previous.takeIf { it.isNotEmpty() })
            return result
        }
        val usedEffects = input.report.records("transitions").flatMap { it.strings("effectRefs") }.toSet()
        val fields = listOf("initialStacks", "triggers", "effects", "streamBindings", "transitions")
        var report = input.report
        fields.forEach { field ->
            val rows = input.report.records(field).filter { field != "effects" || it.string("id") in usedEffects }
            report = report + record(field to rows.map(::compact))
        }
        report = report + record("evidenceFormat" to "SHARED_CONDITIONS_TRACES_V1",
            "summary" to (report["summary"].record() + record("effects" to report.records("effects").size)))
        return TransitionResult(report, input.evidence + record("evidenceFormat" to "SHARED_CONDITIONS_TRACES_V1",
            "evidence" to (proofs.values + definitions.values).sortedBy { it.string("id") }))
    }

    /** Reject corrupt references instead of silently omitting a proof. */
    fun trace(row: Record, evidence: Map<String, Record>): List<String> {
        if (!row.containsKey("traceRef")) return row.strings("evidenceRefs").also { refs -> require(refs.all { it in evidence }) { "BROKEN_EVIDENCE_REFERENCE" } }
        var ref = row.string("traceRef")
        val seen = HashSet<String>()
        val reversed = mutableListOf<String>()
        while (ref.isNotEmpty()) {
            require(seen.add(ref)) { "CYCLIC_TRACE_REFERENCE" }
            val node = evidence[ref] ?: error("BROKEN_TRACE_REFERENCE")
            require(node["kind"] == "TRACE_NODE" && node.string("evidenceRef") in evidence) { "BROKEN_EVIDENCE_REFERENCE" }
            reversed += node.string("evidenceRef")
            ref = node.string("previousRef")
        }
        return reversed.asReversed()
    }
}
