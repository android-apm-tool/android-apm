package dev.androidapm.catalog

import kotlin.math.ln

/** Retrieval is automatic; reviewed anchors and state proofs are reported on separate axes. */
object DesignMatcher {
    const val version = "design-correspondence-v1"

    fun match(ui: Record, design: Record): Record {
        require((design["schemaVersion"] as? Number)?.toInt() == 1) { "Unsupported design specification" }
        val cases = design.records("cases")
        require(cases.map { it.string("id") }.distinct().size == cases.size && cases.all { it.string("id").isNotBlank() }) { "Duplicate or empty design case IDs" }
        val declarations = ui.records("declarations")
        val byId = declarations.associateBy { it.string("id") }
        val boundaries = ui.records("boundaries").filter { it["kind"] != "PREVIEW" && it["origin"] != "PREVIEW" }
        val features = boundaries.associate { boundary -> boundary.string("id") to tokens(
            boundary.strings("textFeatures").joinToString(" ") + " " + UiAnalysis.constants(boundary["hints"]).joinToString(" ") + " " +
                boundary.strings("declarationIds").mapNotNull { byId[it]?.string("name") }.joinToString(" ")) }
        val frequency = features.values.flatMap { it }.groupingBy { it }.eachCount()
        fun weight(token: String) = ln(1.0 + (features.size + 1.0) / (frequency[token] ?: 1))
        val rows = cases.map { case ->
            // Review fields, route labels and code anchors never feed automatic retrieval.
            val query = tokens(case.string("name") + " " + case.strings("texts").joinToString(" "))
            val candidates = boundaries.mapNotNull { boundary ->
                val common = query.intersect(features.getValue(boundary.string("id")))
                if (common.isEmpty()) null else record("boundaryId" to boundary["id"],
                    "rankScore" to common.sumOf(::weight), "sharedTokens" to common.sorted(), "resolution" to boundary["resolution"])
            }.sortedWith(compareByDescending<Record> { (it["rankScore"] as Number).toDouble() }.thenBy { it.string("boundaryId") }).take(5)
            val review = case["review"].record()
            val reviewedFingerprint = review.string("catalogFingerprint")
            val stale = reviewedFingerprint.isNotEmpty() && reviewedFingerprint != ui.string("catalogFingerprint")
            val anchors = review.records("anchors").map { anchor ->
                val matches = declarations.filter { declaration ->
                    (anchor.string("id").isEmpty() || declaration["id"] == anchor["id"]) &&
                        declaration["name"] == anchor["name"] &&
                        declaration["source"].record()["path"] == anchor["source"].record()["path"] &&
                        (anchor["source"].record()["line"] == null || declaration["source"].record()["line"] == anchor["source"].record()["line"])
                }
                val declaration = matches.singleOrNull()
                val linkedBoundaries = declaration?.let { d -> boundaries.filter { d.string("id") in it.strings("declarationIds") } }.orEmpty()
                val expectedPath = review.string("expectedPath")
                val knownExpected = if (expectedPath.isEmpty()) emptyList() else boundaries.filter {
                    expectedPath in UiAnalysis.constants(it["hints"].record()["path"])
                }
                val expected = if (expectedPath.isEmpty()) emptyList() else linkedBoundaries.filter {
                    expectedPath in UiAnalysis.constants(it["hints"].record()["path"])
                }
                record("requested" to anchor, "status" to when { stale -> "STALE_REVIEW"; matches.isEmpty() -> "NOT_FOUND"; matches.size > 1 -> "AMBIGUOUS"; else -> "SOURCE_VERIFIED" },
                    "declarationId" to declaration?.get("id"), "bindingStatus" to declaration?.get("bindingStatus"),
                    "boundaryIds" to linkedBoundaries.map { it["id"] }, "expectedBoundaryIds" to expected.map { it["id"] },
                    "expectedContext" to when {
                        anchor["relationToRoute"] == "EVENT_HOSTED_NOT_EVALUATED" -> "EVENT_FLOW_NOT_EVALUATED"
                        expectedPath.isEmpty() -> "UNSPECIFIED"
                        knownExpected.isEmpty() -> "BOUNDARY_IDENTITY_UNRESOLVED"
                        expected.isEmpty() -> "NOT_LINKED"
                        else -> "LINKED_CANDIDATE"
                    })
            }
            val allAnchors = anchors.isNotEmpty() && anchors.all { it["status"] == "SOURCE_VERIFIED" }
            val conditionIds = review.strings("conditionEvidenceIds")
            val relatedBoundaries = anchors.flatMap { it.strings("boundaryIds") }.toSet()
            val relatedConditions = ui.records("scenarios").filter { it.string("boundaryId") in relatedBoundaries }.map { it.string("id") }.toSet()
            val stateStatus = when {
                stale -> "STALE_REVIEW"
                conditionIds.isEmpty() -> "NOT_VERIFIED"
                !allAnchors -> "NOT_VERIFIED"
                conditionIds.all { it in relatedConditions } -> "STRUCTURAL_CONDITIONS_REFERENCED"
                else -> "INVALID_CONDITION_REFERENCES"
            }
            val disposition = review.string("disposition").ifEmpty { "UNREVIEWED" }
            record("id" to case["id"], "frameId" to (case["frameId"] ?: case["id"]), "name" to case["name"],
                "designKind" to case["kind"], "scenario" to case["scenario"],
                "automatic" to record("status" to if (candidates.isEmpty()) "NO_CANDIDATE" else "CANDIDATES_ONLY", "candidates" to candidates),
                "reviewed" to record("status" to when { stale -> "STALE_REVIEW"; review.isEmpty() -> "NOT_REVIEWED"; allAnchors -> "SOURCE_ANCHORS_VERIFIED"; anchors.isEmpty() -> "NO_CODE_ANCHORS"; else -> "ANCHORS_UNRESOLVED" },
                    "origin" to review["origin"], "evidenceLevel" to review["evidenceLevel"], "disposition" to disposition,
                    "note" to review["note"], "anchors" to anchors, "scenarioStatus" to stateStatus,
                    "implementationStatus" to if (allAnchors) "RELATED_CODE_LOCATED" else "UNKNOWN", "renderStatus" to "NOT_OBSERVED"),
                "issues" to buildList {
                    if (candidates.isEmpty()) add("AUTOMATIC_MATCH_NOT_FOUND")
                    if (anchors.any { it["status"] in setOf("NOT_FOUND", "AMBIGUOUS", "STALE_REVIEW") }) add("SOURCE_IDENTITY_UNRESOLVED")
                    if (anchors.any { it["expectedContext"] == "NOT_LINKED" }) add("EXPECTED_CONTEXT_NOT_LINKED")
                    if (anchors.any { it["expectedContext"] == "BOUNDARY_IDENTITY_UNRESOLVED" }) add("EXPECTED_BOUNDARY_UNRESOLVED")
                    if (anchors.any { it["expectedContext"] == "EVENT_FLOW_NOT_EVALUATED" }) add("EVENT_DISPLAY_CONNECTION_NOT_EVALUATED")
                    if (stateStatus != "STRUCTURAL_CONDITIONS_REFERENCED") add("DESIGN_SCENARIO_NOT_VERIFIED")
                    if (disposition == "CHANGED_PRESENTATION") add("REVIEW_REPORTS_IMPLEMENTATION_DIFFERENCE")
                })
        }
        return record("schemaVersion" to 1, "matcherVersion" to version, "catalogFingerprint" to ui["catalogFingerprint"],
            "designFingerprint" to fingerprint(design), "designSnapshot" to design["snapshot"],
            "summary" to record("cases" to rows.size, "automaticCandidateCases" to rows.count { it["automatic"].record()["status"] == "CANDIDATES_ONLY" },
                "automaticConfirmedMatches" to 0, "reviewedSourceLocatedCases" to rows.count { it["reviewed"].record()["status"] == "SOURCE_ANCHORS_VERIFIED" },
                "scenarioVerifiedCases" to 0, "expectedContextGapCases" to rows.count { "EXPECTED_CONTEXT_NOT_LINKED" in it.strings("issues") }),
            "cases" to rows, "evaluation" to record("status" to "GROUND_TRUTH_REQUIRED", "recall" to null, "precision" to null),
            "limitations" to listOf("RANK_SCORES_ARE_NOT_PROBABILITIES", "REVIEW_HINTS_EXCLUDED_FROM_AUTOMATIC_RETRIEVAL",
                "SOURCE_EXISTENCE_IS_NOT_SCENARIO_IMPLEMENTATION", "STATIC_CONDITIONS_ARE_NOT_STATE_REACHABILITY", "NO_RENDER_OR_RUNTIME_OBSERVATIONS"))
    }

    internal fun tokens(text: String): Set<String> = text.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.length >= 2 && !it.all(Char::isDigit) && it !in stopWords }.toSet()
    private val stopWords = setOf("com", "ruleup", "presentation", "screen", "content", "android", "androidx", "compose", "the", "and")
}

/** Evaluates a frozen, independent one-to-one screen truth set. Abstentions remain false negatives. */
object DiscoveryEvaluator {
    fun evaluate(predictions: List<Record>, truth: Record): Record {
        require(truth["independent"] == true) { "Evaluation requires an independent truth set" }
        val targets = truth.records("targets")
        require(targets.map { it.string("id") }.distinct().size == targets.size) { "Duplicate truth targets" }
        require(targets.all { it.string("id").isNotBlank() && it.string("contextKey").isNotBlank() && it["inScope"] is Boolean }) { "Truth requires explicit identity, context and scope" }
        val eligible = targets.filter { it["inScope"] == true }
        val excluded = targets.filter { it["inScope"] == false }.map { it.string("id") }.toSet()
        val valid = eligible.associateBy { it.string("id") }
        val seen = mutableSetOf<String>()
        var tp = 0
        var fp = 0
        var outside = 0
        predictions.filter { it["origin"] == "AUTOMATIC" && it["status"] == "CONFIRMED" }.forEach { prediction ->
            if (prediction.string("truthId") in excluded) { outside++; return@forEach }
            val target = valid[prediction.string("truthId")]
            if (target != null && prediction["contextKey"] == target["contextKey"] && seen.add(target.string("id"))) tp++ else fp++
        }
        val fn = eligible.size - tp
        return record("truePositive" to tp, "falsePositive" to fp, "falseNegative" to fn, "outOfScopePredictions" to outside,
            "recall" to if (eligible.isEmpty()) null else tp.toDouble() / eligible.size,
            "precision" to if (tp + fp == 0) null else tp.toDouble() / (tp + fp),
            "truthVersion" to truth["version"], "missingTruthIds" to (valid.keys - seen).sorted())
    }
}
