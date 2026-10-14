package dev.androidapm.catalog

import java.security.MessageDigest
import java.security.DigestOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

internal fun fingerprint(value: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    OutputStreamWriter(DigestOutputStream(OutputStream.nullOutputStream(), digest), Charsets.UTF_8).use { Json.write(value, it) }
    return digest.digest().take(16).joinToString("") { "%02x".format(it) }
}

data class UiAnalysisResult(val report: Record, val evidence: Record)

/** Structural evidence, not executable reachability, pixel visibility, or a dead-code oracle. */
object UiAnalysis {
    const val version = "usage-diagnostics-v1"
    fun create(catalog: Record): UiAnalysisResult = Analyzer(catalog).create()

    private class Analyzer(val catalog: Record) {
        val functions = catalog.records("functions").associateBy { it.string("symbol") }
        val declarations = catalog.records("composableDeclarations").associateBy { it.string("symbol") }
        val calls = catalog.records("callRelations").associateBy { it.string("id") }
        val values = catalog.records("values").associateBy { it.string("ref") }
        val evidence = linkedMapOf<String, Record>()
        val usages = linkedMapOf<String, Record>()
        val boundaries = mutableListOf<Record>()
        val scenarios = mutableListOf<Record>()
        val reached = mutableMapOf<String, MutableSet<String>>()
        val previewReached = mutableMapOf<String, MutableSet<String>>()
        val sourceRefs = mutableMapOf<String, MutableSet<String>>()
        val problems = mutableMapOf<String, MutableList<Record>>()
        val reverseReferences = mutableMapOf<String, MutableSet<String>>()
        val forwardReferences = mutableMapOf<String, MutableSet<String>>()
        val bindings = catalog.records("uiRootBindings").map { it + record("id" to it["rootId"], "bindingKind" to "ROOT_REGION") } +
            catalog.records("entryBindings").map { it + record("bindingKind" to if (it["providerId"] != null) "DESTINATION" else "UNBOUND_DESTINATION") } +
            catalog.records("screenCandidates").map { it + record("bindingKind" to "CONTENT_CANDIDATE") } +
            catalog.records("previewBindings").map { it + record("bindingKind" to "PREVIEW") }

        fun evidence(value: Record): String = fingerprint(value).also { evidence.putIfAbsent(it, value) }

        fun references(value: Any?, seen: Set<String> = emptySet()): Set<String> = when (value) {
            is Map<*, *> -> {
                val r = value.record()
                (if (r["kind"] in setOf("FUNCTION", "REFERENCE")) setOf(r.string("ref")) else emptySet()) +
                    (if (r["kind"] == "REFERENCE" && r.string("ref") !in seen && seen.size < 40)
                        references(values[r.string("ref")]?.get("value"), seen + r.string("ref")) else emptySet()) +
                    r.values.flatMap { references(it, seen) }
            }
            is List<*> -> value.flatMap { references(it, seen) }.toSet()
            else -> emptySet()
        }.filter { it in functions }.toSet()

        fun connect(from: String, to: String) {
            if (from !in functions || to !in functions) return
            reverseReferences.getOrPut(to) { sortedSetOf() }.add(from)
            forwardReferences.getOrPut(from) { sortedSetOf() }.add(to)
        }

        fun closure(start: Set<String>, graph: Map<String, Set<String>>): Set<String> {
            val result = start.toMutableSet()
            val pending = ArrayDeque(start)
            while (pending.isNotEmpty()) graph[pending.removeFirst()].orEmpty().forEach { if (result.add(it)) pending.add(it) }
            return result
        }

        fun create(): UiAnalysisResult {
            calls.values.forEach { call ->
                connect(call.string("owner"), call.string("target"))
                references(call["arguments"]).forEach { connect(call.string("owner"), it) }
            }
            functions.values.forEach { function ->
                // Lexical nesting is reference evidence only; it never creates an invocation edge.
                connect(function.string("enclosingFunction"), function.string("symbol"))
            }
            bindings.forEach(::binding)
            // Engine source gaps are associated only at a unique exact call location, never by file proximity.
            catalog.records("collectionDiagnostics").forEach { diagnostic ->
                val source = diagnostic["source"].record()
                val matches = calls.values.filter { call ->
                    val location = call["source"].record()
                    source.string("path").isNotEmpty() && listOf("path", "line", "column").all { source[it] != null && source[it] == location[it] }
                }
                matches.singleOrNull()?.let { call -> problems.getOrPut(call.string("owner")) { mutableListOf() }.add(
                    record("code" to diagnostic["code"], "association" to "UNIQUE_EXACT_CALL_SOURCE",
                        "evidenceRef" to evidence(diagnostic + record("callId" to call["id"])), "origin" to "BYTECODE_ANALYSIS")) }
            }
            val assessments = declarations.values.sortedBy { it.string("id") }.map(::assessment)
            val counts = assessments.groupingBy { it.string("primaryReason") }.eachCount().toSortedMap()
            val unlinked = assessments.filter { it["bindingStatus"] == "UNLINKED" }
            val report = record("schemaVersion" to 1, "rulesVersion" to version,
                "catalogFingerprint" to fingerprint(catalog.filterKeys { it in setOf("functions", "callRelations", "values",
                    "analysisCallTargets", "analysis", "uiRoots", "navHosts", "entryDeclarations", "providers", "contentContainers",
                    "scope", "variant", "missingModules", "collectionDiagnostics") }),
                "variant" to catalog["variant"], "inputScope" to catalog["scope"], "missingModules" to catalog["missingModules"],
                "coverageDiagnostics" to catalog.records("diagnostics").groupingBy { it.string("code") }.eachCount().toSortedMap(),
                "summary" to record("declarations" to assessments.size, "unlinkedDeclarations" to unlinked.size,
                    "previewReachableUnlinked" to unlinked.count { "PREVIEW_REACHABLE" in it.strings("reasonCodes") },
                    "primaryReasons" to counts, "boundaries" to boundaries.size, "usages" to usages.size,
                    "scenarios" to scenarios.size, "unresolvedBindings" to boundaries.count { it["resolution"] != "RESOLVED" }),
                "declarations" to assessments, "boundaries" to boundaries.sortedBy { it.string("id") },
                "usages" to usages.values.sortedBy { it.string("id") }, "scenarios" to scenarios.sortedBy { it.string("id") },
                "limitations" to listOf("STATIC_PATHS_NOT_RUNTIME_REACHABILITY", "NO_REFERENCE_IS_NOT_DEAD_CODE",
                    "PREVIEW_REACHABLE_IS_NOT_PREVIEW_ONLY", "STORAGE_IS_NOT_A_DESTINATION", "CONDITIONS_NOT_SOLVED",
                    "REFLECTION_HEAP_AND_UNMODELED_ROOTS_REMAIN", "ANNOTATION_METADATA_MAY_BE_INCOMPLETE",
                    "ROLE_UNKNOWN_IS_PRESERVED", "DESIGN_GROUND_TRUTH_NOT_INFERRED"))
            return UiAnalysisResult(report, record("schemaVersion" to 1, "rulesVersion" to version,
                "catalogFingerprint" to report["catalogFingerprint"], "evidence" to evidence.map { (id, value) -> record("id" to id) + value }))
        }

        fun binding(binding: Record) {
            val id = binding.string("id")
            val kind = binding.string("bindingKind")
            val preview = kind == "PREVIEW"
            val trace = binding.records("usageTrace")
            val edges = trace.filter { it["kind"] == "EDGE" }
            val traceCalls = trace.filter { it["kind"] == "CALL" }
            val callsByNode = traceCalls.groupBy { it.string("node") }
            val diagnostics = trace.filter { it["kind"] == "DIAGNOSTIC" }
            val nodeSymbols = edges.associate { it.string("to") to it.string("symbol") }
            val incoming = edges.groupBy { it.string("to") }
            val outgoing = edges.groupBy { it.string("from") }
            val seeds = edges.filter { it["relation"] == "SEED" }.map { it.string("to") }.toSet()
            val directContent = edges.filter { it.string("from") in seeds && functions[nodeSymbols[it.string("from")]]?.get("kind") == "LAMBDA" }
                .map { it.string("to") }.toSet()
            val reverseNodes = edges.filter { it.string("from").isNotEmpty() }.groupBy { it.string("to") }
                .mapValues { (_, items) -> items.map { it.string("from") }.toSet() }
            val visualNodes = closure(traceCalls.filter { it.string("name") in visualApis }.map { it.string("node") }.toSet(), reverseNodes)
            val mayNodes = closure(edges.filter { it["certainty"] == "MAY" }.map { it.string("to") }.toSet(),
                outgoing.mapValues { (_, items) -> items.map { it.string("to") }.toSet() })
            val boundaryId = "boundary:" + fingerprint(id)
            val source = binding["source"] ?: functions[binding.string("symbol")]?.get("source")
            val hints = binding.records("arguments").filter { it["composableType"] != true }.associate { it.string("name") to it["value"] }
            val featureCalls = traceCalls.mapNotNull { calls[it.string("callId")] }.filter { it["composable"] == true }.distinctBy { it["id"] }
            val strings = featureCalls.flatMap { c -> constants(c["arguments"]) }.filter { it.length in 2..160 }.distinct().sorted()
            val declarationIds = nodeSymbols.values.mapNotNull { declarations[it]?.get("id") as? String }.distinct().sorted()
            val diagnosticIds = diagnostics.map { evidence(it + record("bindingId" to id)) }
            boundaries += record("id" to boundaryId, "bindingId" to id, "kind" to kind, "source" to source,
                "origin" to if (preview) "PREVIEW" else "APPLICATION_BINDING",
                "resolution" to binding["resolution"], "evidenceRefs" to diagnosticIds,
                "declarationIds" to declarationIds, "hints" to hints, "textFeatures" to strings,
                "definitionStatus" to if (kind == "DESTINATION") "REGISTERED" else "CANDIDATE",
                "runtimeReachability" to "NOT_EVALUATED")

            // Boundary ownership propagates through explicit content-slot edges; events are not edges.
            val overlayCalls = traceCalls.filter { it.string("name") in overlayApis }
            val overlayOwners = mutableMapOf<String, MutableSet<String>>()
            overlayCalls.forEach { overlay ->
                val overlayId = "boundary:" + fingerprint(listOf(id, overlay["node"], overlay["callId"]))
                val seeds = edges.filter { it["from"] == overlay["node"] && it["callId"] == overlay["callId"] && it["relation"] == "SLOT" }.map { it.string("to") }
                val graph = outgoing.mapValues { (_, items) -> items.map { it.string("to") }.toSet() }
                val descendants = closure(seeds.toSet(), graph)
                descendants.forEach { overlayOwners.getOrPut(it) { sortedSetOf() }.add(overlayId) }
                boundaries += record("id" to overlayId, "parentBoundaryId" to boundaryId, "bindingId" to id,
                    "origin" to if (preview) "PREVIEW" else "APPLICATION_BINDING",
                    "kind" to overlayApis.getValue(overlay.string("name")), "definitionStatus" to "API_BOUNDARY",
                    "source" to overlay["source"], "resolution" to if (seeds.isEmpty() || binding["resolution"] != "RESOLVED") "PARTIAL" else "RESOLVED",
                    "conditions" to overlay["conditions"], "declarationIds" to descendants.mapNotNull { nodeSymbols[it]?.let { declarations[it]?.get("id") } }.distinct().sortedBy { it.toString() },
                    "evidenceRefs" to listOf(evidence(overlay + record("bindingId" to id))), "runtimeReachability" to "NOT_EVALUATED")
            }
            nodeSymbols.forEach { (node, symbol) ->
                val function = functions[symbol] ?: return@forEach
                val edgeRefs = incoming[node].orEmpty().map { evidence(it + record("bindingId" to id)) }
                (if (preview) previewReached else reached).getOrPut(symbol) { sortedSetOf() }.add(id)
                sourceRefs.getOrPut(symbol) { sortedSetOf() }.addAll(edgeRefs)
                val declaration = declarations[symbol] ?: return@forEach
                val nodeCalls = callsByNode[node].orEmpty()
                val role = role(function, nodeCalls, outgoing[node].orEmpty(), incoming[node].orEmpty(), kind, node in visualNodes, node in directContent)
                val usageId = "usage:" + fingerprint(listOf(id, node))
                usages[usageId] = record("id" to usageId, "declarationId" to declaration["id"], "bindingId" to id,
                    "boundaryIds" to (overlayOwners[node]?.toList() ?: listOf(boundaryId)),
                    "role" to role, "origin" to if (preview) "PREVIEW" else "APPLICATION_BINDING",
                    "certainty" to if (node in mayNodes) "MAY" else "STRUCTURAL", "roleStatus" to if (role == "UNKNOWN") "UNCLASSIFIED" else "RULE_INFERRED",
                    "conditionSemantics" to "ALTERNATIVE_INCOMING_EDGES", "evidenceRefs" to edgeRefs)
            }
            diagnostics.forEach { diagnostic ->
                val directlyRelated = diagnostic.strings("relatedSymbols").toSet()
                // This is a diagnostic frontier, not an execution proof for the callback.
                val related = closure(directlyRelated, forwardReferences)
                (related + diagnostic.string("symbol")).filter { it in functions }.forEach { symbol ->
                    problems.getOrPut(symbol) { mutableListOf() }.add(record("code" to diagnostic["code"],
                        "association" to if (symbol in related) "REFERENCED_BEHIND_FRONTIER" else "EXACT_OWNER",
                        "evidenceRef" to evidence(diagnostic + record("bindingId" to id)), "origin" to if (preview) "PREVIEW" else "APPLICATION_BINDING"))
                }
            }
            val conditions = traceCalls.flatMap { call -> call.records("conditions").map { condition ->
                record("bindingId" to id, "callId" to call["callId"], "owner" to call["owner"], "condition" to condition, "source" to call["source"])
            } }.distinctBy { fingerprint(it) }
            conditions.groupBy { fingerprint(it["condition"]) }.values.forEach { affected ->
                val conditionRef = evidence(record("kind" to "CONDITION", "expression" to affected.first()["condition"]))
                val refs = affected.map { evidence(it - "condition" + record("conditionRef" to conditionRef)) }
                scenarios += record("id" to "scenario:" + fingerprint(listOf(boundaryId, conditionRef)),
                    "boundaryId" to boundaryId, "kind" to "CONDITIONAL_CONTENT", "conditionRef" to conditionRef,
                    "feasibility" to "NOT_SOLVED", "evidenceRefs" to (listOf(conditionRef) + refs).distinct())
            }
        }

        fun role(function: Record, nodeCalls: List<Record>, children: List<Record>, parents: List<Record>, kind: String, visual: Boolean, directContent: Boolean): String {
            if (nodeCalls.any { it.string("name") == "androidx.navigation3.ui.NavDisplay" }) return "HOST"
            val contentParameters = function.records("parameters").filter { it["composableType"] == true }.map { it.string("ref") }.toSet()
            if (contentParameters.isNotEmpty() && nodeCalls.any { call ->
                    val raw = calls[call.string("callId")].orEmpty()
                    raw.records("arguments").any { argument -> argument["value"].record().string("ref") in contentParameters &&
                        (call.string("name").endsWith(".invoke") || ContentSemantics.follows(call.string("name"), argument)) }
                }) return "SHELL"
            if (parents.any { it["relation"] == "SEED" } && kind == "DESTINATION") return "SCREEN_CONTENT"
            if (directContent && visual && kind == "DESTINATION") return "SCREEN_CONTENT"
            if (directContent && visual && kind in setOf("ROOT_REGION", "CONTENT_CANDIDATE")) return "BOUNDARY_CONTENT_CANDIDATE"
            if (parents.any { it["relation"] == "SLOT" && calls[it.string("callId")]?.string("name") in overlayApis }) return "SCREEN_CONTENT"
            if (nodeCalls.isNotEmpty() && nodeCalls.all { it.string("name") in valueApis } && children.isEmpty()) return "NON_VISUAL_HELPER"
            if (visual && kind in setOf("DESTINATION", "ROOT_REGION", "CONTENT_CANDIDATE", "PREVIEW") && parents.any { it["relation"] != "SEED" }) return "COMPONENT_IN_CONTEXT"
            return "UNKNOWN"
        }

        fun assessment(declaration: Record): Record {
            val symbol = declaration.string("symbol")
            val linked = reached[symbol].orEmpty()
            val previews = previewReached[symbol].orEmpty()
            val ancestorSet = closure(setOf(symbol), reverseReferences)
            val linkedAncestor = ancestorSet.filter { reached[it].orEmpty().isNotEmpty() }
            val references = reverseReferences[symbol].orEmpty()
            val findings = problems[symbol].orEmpty().distinct()
            val reasonCodes = sortedSetOf<String>()
            if (linked.isNotEmpty()) reasonCodes += "LINKED_TO_BINDING"
            if (previews.isNotEmpty()) reasonCodes += "PREVIEW_REACHABLE"
            if (linked.isEmpty() && declaration.strings("overrides").any { it == "androidx.compose.ui.platform.AbstractComposeView.Content" }) reasonCodes += "ROOT_NOT_MODELED"
            if (declaration.string("sourceSet").contains("test", ignoreCase = true)) reasonCodes += "TEST_SOURCE"
            findings.forEach { problem -> reasonMap[problem.string("code")]?.let { reasonCodes += it } }
            if (linked.isEmpty() && previews.isEmpty()) {
                if (references.isEmpty()) reasonCodes += "NO_REFERENCE_IN_SCOPE"
                else if (linkedAncestor.isNotEmpty()) reasonCodes += "REFERENCE_PATH_NOT_RESOLVED"
                else reasonCodes += "UNROOTED_REFERENCE_GROUP"
            }
            if (reasonCodes.isEmpty()) reasonCodes += "UNKNOWN"
            val primary = priority.firstOrNull { it in reasonCodes } ?: reasonCodes.first()
            return record("id" to declaration["id"], "name" to (declaration["name"] ?: "<anonymous>"), "source" to declaration["source"],
                "sourceSet" to declaration["sourceSet"], "isPreviewDeclaration" to (declaration["preview"] == true),
                "bindingStatus" to if (linked.isEmpty()) "UNLINKED" else "LINKED",
                "bindingIds" to linked.sorted(), "previewBindingIds" to previews.sorted(),
                "primaryReason" to primary, "reasonCodes" to reasonCodes.toList(), "findings" to findings,
                "referenceOwners" to references.sorted(), "referenceGroupSize" to ancestorSet.size,
                "evidenceRefs" to sourceRefs[symbol].orEmpty().sorted(), "unusedStatus" to "NOT_PROVEN")
        }
    }

    internal fun constants(value: Any?): List<String> = when (value) {
        is Map<*, *> -> if (value["kind"] == "CONSTANT" && value["value"] is String) listOf(value["value"] as String) else value.values.flatMap(::constants)
        is List<*> -> value.flatMap(::constants)
        else -> emptyList()
    }
    private val overlayApis = mapOf("androidx.compose.material3.ModalBottomSheet" to "SHEET",
        "androidx.compose.material.ModalBottomSheetLayout" to "SHEET_HOST", "androidx.compose.ui.window.Dialog" to "DIALOG",
        "androidx.compose.material3.AlertDialog" to "DIALOG", "androidx.compose.material.AlertDialog" to "DIALOG",
        "androidx.compose.ui.window.Popup" to "POPUP")
    private val valueApis = setOf("androidx.compose.runtime.ProvidableCompositionLocal.<get-current>", "androidx.compose.runtime.CompositionLocal.<get-current>")
    private val visualApis = setOf("androidx.compose.foundation.text.BasicText", "androidx.compose.foundation.text.BasicTextField",
        "androidx.compose.foundation.Canvas", "androidx.compose.foundation.Image", "androidx.compose.ui.viewinterop.AndroidView",
        "androidx.compose.material3.Text", "androidx.compose.material.Text", "androidx.compose.material3.Icon", "androidx.compose.material.Icon")
    private val reasonMap = mapOf("ANALYSIS_LIMIT_REACHED" to "ANALYSIS_TRUNCATED", "UNMODELED_CALLBACK_CONTENT" to "CALLBACK_FLOW_UNRESOLVED",
        "UNRESOLVED_CONTENT_FLOW" to "CALLBACK_FLOW_UNRESOLVED", "UNRESOLVED_EXTERNAL_CONTENT_SLOT" to "LIBRARY_MODEL_MISSING",
        "UNRESOLVED_VIRTUAL_DISPATCH" to "VIRTUAL_DISPATCH_UNRESOLVED", "UNAVAILABLE_FUNCTION_BODY" to "FUNCTION_BODY_UNAVAILABLE",
        "BYTECODE_SOURCE_BRIDGE_GAPS" to "SOURCE_BRIDGE_AMBIGUOUS")
    private val priority = listOf("LINKED_TO_BINDING", "ROOT_NOT_MODELED", "ANALYSIS_TRUNCATED", "SOURCE_BRIDGE_AMBIGUOUS", "LIBRARY_MODEL_MISSING",
        "CALLBACK_FLOW_UNRESOLVED", "VIRTUAL_DISPATCH_UNRESOLVED", "FUNCTION_BODY_UNAVAILABLE", "PREVIEW_REACHABLE", "TEST_SOURCE",
        "REFERENCE_PATH_NOT_RESOLVED", "UNROOTED_REFERENCE_GROUP", "NO_REFERENCE_IN_SCOPE", "UNKNOWN")
}

internal fun Record.strings(key: String): List<String> = (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()
