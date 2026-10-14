"""Convert a frozen Figma frame snapshot plus OPTIONAL legacy review into matcher input.

Review anchors remain separately labeled; neither their routes nor names enter automatic retrieval.
Usage: python scripts/prepare-design-input.py frames.json output.json [legacy-audit.json]
"""
import hashlib
import json
from pathlib import Path
import sys

frames_path, output = map(Path, sys.argv[1:3])
frames = json.loads(frames_path.read_text(encoding="utf-8-sig"))
audit = json.loads(Path(sys.argv[3]).read_text(encoding="utf-8-sig")) if len(sys.argv) > 3 else {}
reviews = {row["figmaNodeId"]: row for row in audit.get("frames", [])}
cases = []
for frame in frames["frames"]:
    old = reviews.get(frame["id"], {})
    row = {"id": frame["id"], "frameId": frame["id"], "name": frame["name"],
           "texts": frame.get("texts", []), "kind": old.get("manualRole", "UNCLASSIFIED")}
    if old:
        row["review"] = {
            "origin": "LEGACY_REVIEW_REVALIDATED_AGAINST_CURRENT_SOURCE_IDENTITIES",
            "reviewedCodeCommit": audit.get("codeCommit"),
            "evidenceLevel": old.get("mappingEvidence"),
            "expectedPath": old.get("expectedRoute"),
            "disposition": old.get("sourceMatch"), "note": old.get("note", ""),
            "anchors": [{"name": check["function"], "source": {"path": check["source"]["path"]},
                         "relationToRoute": check.get("relationToRoute", "NOT_EVALUATED")}
                        for check in old.get("checks", [])],
        }
    cases.append(row)
result = {"schemaVersion": 1, "snapshot": {"fileKey": frames.get("fileKey"), "pageId": frames.get("pageId"),
    "retrievedOn": frames.get("retrievedOn"), "sha256": hashlib.sha256(frames_path.read_bytes()).hexdigest(),
    "legacyReviewLimitations": audit.get("limitations", [])}, "cases": cases}
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Prepared {len(cases)} cases; legacy review is separate from automatic matching: {output}")
