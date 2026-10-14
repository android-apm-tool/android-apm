"""Verify referential integrity and emit a compact, auditable result summary.

Usage: python scripts/inspect-ui-results.py consumer-root [summary-output.json]
Requires jsonschema only for schema validation; the analysis itself is Kotlin/Gradle.
"""
import collections
import json
from pathlib import Path
import sys
import jsonschema

sys.stdout.reconfigure(encoding="utf-8")
root = Path(sys.argv[1])
repository = Path(__file__).resolve().parents[1]
reports = root / "app/build/reports/android-apm/catalog/debug"
load = lambda path: json.loads(path.read_text(encoding="utf-8-sig"))
ui = load(reports / "ui-analysis.json")
matches = load(reports / "design-matches.json")
catalog = load(reports / "project-catalog.json")
evidence = load(root / "app/build/intermediates/android-apm/debug/analysis/ui-evidence.json")
for value, schema in [(ui, "ui-analysis-v1"), (matches, "design-matches-v1"), (catalog, "catalog-report-v2")]:
    jsonschema.validate(value, load(repository / f"schema/{schema}.schema.json"))
decls = {d["id"]: d for d in ui["declarations"]}
evidence_by_id = {e["id"]: e for e in evidence["evidence"]}
evidence_ids = set(evidence_by_id)
boundary_ids = {b["id"] for b in ui["boundaries"]}
assert len(decls) == len(ui["declarations"])
assert len(evidence_ids) == len(evidence["evidence"])
assert len(boundary_ids) == len(ui["boundaries"])
assert len({u["id"] for u in ui["usages"]}) == len(ui["usages"])
assert len({s["id"] for s in ui["scenarios"]}) == len(ui["scenarios"])
assert len({c["id"] for c in matches["cases"]}) == len(matches["cases"])
assert set(decls) == {d["id"] for d in catalog["composables"] if not d.get("external")}
unlinked = [d for d in decls.values() if d["bindingStatus"] == "UNLINKED"]
assert {d["id"] for d in unlinked} == set(catalog["unlinkedComposableIds"])
for group in [ui["declarations"], ui["usages"], ui["boundaries"], ui["scenarios"]]:
    for row in group:
        assert set(row.get("evidenceRefs", [])) <= evidence_ids
        assert set(row.get("boundaryIds", [])) <= boundary_ids
        assert set(row.get("declarationIds", [])) <= set(decls)
        assert "declarationId" not in row or row["declarationId"] in decls
        assert "boundaryId" not in row or row["boundaryId"] in boundary_ids
        assert "parentBoundaryId" not in row or row["parentBoundaryId"] in boundary_ids
        assert all(f["evidenceRef"] in evidence_ids for f in row.get("findings", []))
        if "conditionRef" in row:
            assert evidence_by_id[row["conditionRef"]]["kind"] == "CONDITION"
for case in matches["cases"]:
    assert all(c["boundaryId"] in boundary_ids for c in case["automatic"]["candidates"])
    for anchor in case["reviewed"]["anchors"]:
        assert anchor.get("declarationId") is None or anchor["declarationId"] in decls
        assert set(anchor["boundaryIds"]) <= boundary_ids
        assert set(anchor["expectedBoundaryIds"]) <= set(anchor["boundaryIds"])
assert evidence["catalogFingerprint"] == ui["catalogFingerprint"] == matches["catalogFingerprint"]
reason_groups = {}
for item in unlinked:
    reason_groups.setdefault(item["primaryReason"], []).append({"id": item["id"], "name": item["name"], "source": item["source"],
        "reasonCodes": item["reasonCodes"], "previewDeclaration": item["isPreviewDeclaration"]})
summary = {
    "catalog": catalog["summary"], "ui": ui["summary"],
    "unlinkedReasonCounts": dict(sorted(collections.Counter(d["primaryReason"] for d in unlinked).items())),
    "unlinkedPreviewDeclarations": sum(d["isPreviewDeclaration"] for d in unlinked),
    "unlinkedPreviewDescendants": sum(not d["isPreviewDeclaration"] and "PREVIEW_REACHABLE" in d["reasonCodes"] for d in unlinked),
    "unlinkedByReason": reason_groups,
    "roleUsageCounts": dict(sorted(collections.Counter(u["role"] for u in ui["usages"]).items())),
    "design": matches["summary"],
    "designSourceStatuses": dict(collections.Counter(c["reviewed"]["status"] for c in matches["cases"])),
    "designReviewDispositions": dict(collections.Counter(c["reviewed"]["disposition"] for c in matches["cases"])),
    "designContextGaps": [{"id": c["id"], "name": c["name"], "anchors": [a for a in c["reviewed"]["anchors"] if a["expectedContext"] == "NOT_LINKED"]}
                          for c in matches["cases"] if "EXPECTED_CONTEXT_NOT_LINKED" in c["issues"]],
    "eventDisplayLinksNotEvaluated": [{"id": c["id"], "name": c["name"]} for c in matches["cases"]
                                      if "EVENT_DISPLAY_CONNECTION_NOT_EVALUATED" in c["issues"]],
    "automaticNoCandidateCases": [{"id": c["id"], "name": c["name"]} for c in matches["cases"]
                                  if c["automatic"]["status"] == "NO_CANDIDATE"],
    "evaluation": matches["evaluation"],
}
if len(sys.argv) > 2:
    Path(sys.argv[2]).write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in summary.items() if k not in ("unlinkedByReason", "designContextGaps")}, ensure_ascii=False, indent=2))
print("All schemas, declaration IDs, evidence references and design case identities verified.")
