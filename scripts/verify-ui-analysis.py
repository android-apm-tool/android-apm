"""End-to-end real Compose fixture, evidence integrity, matching isolation and failure preservation."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "samples/compose-app"
OUTPUT = SAMPLE / "app/build/reports/android-apm/catalog/debug"
LOGS = ROOT / "build/ui-verification"
LOGS.mkdir(parents=True, exist_ok=True)
SPEC = LOGS / "design.json"
SPEC.write_bytes((SAMPLE / "design-cases.json").read_bytes())
WRAPPER = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")


def run(name, success=True):
    result = subprocess.run([str(WRAPPER), "-p", str(SAMPLE), ":app:apmMatchDebugDesign",
        f"-Papm.designSpec={SPEC}", "--no-daemon", "--build-cache", "--configuration-cache",
        "--max-workers=2", "--console=plain", "--project-cache-dir", str(LOGS / "gradle-cache")],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", cwd=ROOT)
    (LOGS / f"{name}.log").write_text(result.stdout, encoding="utf-8")
    if (result.returncode == 0) != success:
        raise AssertionError(result.stdout[-10000:])
    print(name, "passed", flush=True)
    return result.stdout


run("initial")
ui = json.loads((OUTPUT / "ui-analysis.json").read_text(encoding="utf-8"))
evidence = json.loads((SAMPLE / "app/build/intermediates/android-apm/debug/analysis/ui-evidence.json").read_text(encoding="utf-8"))
matches = json.loads((OUTPUT / "design-matches.json").read_text(encoding="utf-8"))
definitions = {d["name"]: d for d in ui["declarations"]}
assert definitions["dev.androidapm.sample.PreviewOnly"]["isPreviewDeclaration"] is True
assert definitions["dev.androidapm.sample.PreviewOnlyItem"]["primaryReason"] == "PREVIEW_REACHABLE"
assert definitions["dev.androidapm.sample.StatePanel"]["bindingStatus"] == "LINKED"
assert "PREVIEW_REACHABLE" in definitions["dev.androidapm.sample.StatePanel"]["reasonCodes"]
assert definitions["dev.androidapm.sample.UnlinkedItem"]["primaryReason"] == "NO_REFERENCE_IN_SCOPE"
assert any(b["kind"] == "DIALOG" for b in ui["boundaries"])
assert ui["scenarios"] and all(s["feasibility"] == "NOT_SOLVED" for s in ui["scenarios"])
ids = {e["id"] for e in evidence["evidence"]}
assert all(ref in ids for group in (ui["usages"], ui["scenarios"], ui["boundaries"], ui["declarations"])
           for row in group for ref in row.get("evidenceRefs", []))
assert matches["summary"]["reviewedSourceLocatedCases"] == 2
assert matches["summary"]["automaticConfirmedMatches"] == 0
assert matches["cases"][2]["automatic"]["status"] == "NO_CANDIDATE"
before = {name: (OUTPUT / name).read_bytes() for name in ("ui-analysis.json", "design-matches.json")}
again = run("unchanged")
assert ":app:apmMatchDebugDesign UP-TO-DATE" in again and "Reusing configuration cache" in again
assert all((OUTPUT / name).read_bytes() == value for name, value in before.items())
spec = json.loads(SPEC.read_text(encoding="utf-8"))
spec["cases"].append({"id": "added-design-only", "name": "Another unmatched design"})
SPEC.write_text(json.dumps(spec), encoding="utf-8")
changed = run("design-only-change")
assert ":app:apmMergeDebugCatalog UP-TO-DATE" in changed
assert ":app:apmCompileDebugCatalog UP-TO-DATE" in changed
assert (OUTPUT / "ui-analysis.json").read_bytes() == before["ui-analysis.json"]
last_success = (OUTPUT / "design-matches.json").read_bytes()
spec["cases"].append(spec["cases"][0])
SPEC.write_text(json.dumps(spec), encoding="utf-8")
run("invalid-design", success=False)
assert (OUTPUT / "design-matches.json").read_bytes() == last_success
SPEC.write_bytes((SAMPLE / "design-cases.json").read_bytes())
run("restored")
assert (OUTPUT / "design-matches.json").read_bytes() == before["design-matches.json"]
print("Real sample UI evidence, preview, matching, cache and failure checks passed.")
