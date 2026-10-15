"""Real public sample: path evidence, query-only invalidation, failed-query preservation."""
from pathlib import Path
import json
import os
import subprocess

root = Path(__file__).resolve().parents[1]
logs = root / "build/transition-verification"
logs.mkdir(parents=True, exist_ok=True)
reports = root / "samples/compose-app/app/build/reports/android-apm/catalog/debug"
query = logs / "query.json"
wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")

def run(name, valid=True):
    result = subprocess.run([str(wrapper), "-p", "samples/compose-app", ":app:apmFindDebugAccessPaths",
        "-Papm.accessQuery=" + str(query), "--no-daemon", "--console=plain", "--configuration-cache", "--build-cache"],
        cwd=root, capture_output=True, text=True, encoding="utf-8", errors="replace")
    (logs / (name + ".log")).write_text(result.stdout + result.stderr, encoding="utf-8")
    assert (result.returncode == 0) == valid, name + ": " + result.stdout[-4000:]
    print(name + " passed", flush=True)
    return result.stdout

query.write_text('{"maxDepth":3}')
run("collect")
transitions = json.loads((reports / "transitions.json").read_text())
evidence = json.loads((root / "samples/compose-app/app/build/intermediates/android-apm/debug/analysis/transition-evidence.json").read_text())
paths = json.loads((reports / "access-paths.json").read_text())
detail = next(c["id"] for c in transitions["contexts"] if any(p.get("type", "").endswith(".Detail") for p in c["keyPatterns"]))
assert any(p["targetContextRef"] == detail and len(p["steps"]) == 1 for p in paths["paths"])
known = {e["id"] for e in evidence["evidence"]}
assert all(set(e["evidenceRefs"]) <= known for e in transitions["transitions"])
assert transitions["evidenceFormat"] == "SHARED_CONDITIONS_TRACES_V1"
proofs = {e["id"]: e for e in evidence["evidence"]}
for edge in transitions["transitions"]:
    ref = edge["traceRef"]
    seen, trace = set(), []
    while ref:
        assert ref not in seen
        seen.add(ref)
        node = proofs[ref]
        assert node["kind"] == "TRACE_NODE" and node["evidenceRef"] in proofs
        trace.append(proofs[node["evidenceRef"]])
        ref = node["previousRef"]
    assert any(p["kind"] == "CALL" for p in trace)
launcher = json.loads((reports / "launcher-access-paths.json").read_text())
assert launcher["query"]["includeExternalIntents"] is False
assert all(not p["externalEntryRefs"] for p in launcher["paths"])
assert transitions["aiMode"] == "DISABLED"
unchanged = run("up-to-date")
assert ":app:apmFindDebugAccessPaths UP-TO-DATE" in unchanged
before = (reports / "transitions.json").read_bytes()
query.write_text('{"maxDepth":2}')
changed = run("query-only")
assert ":app:apmCompileDebugCatalog UP-TO-DATE" in changed
assert ":app:apmLinkDebugTransitions UP-TO-DATE" in changed
assert ":app:apmFindDebugAccessPaths UP-TO-DATE" not in changed
assert (reports / "transitions.json").read_bytes() == before
saved = (reports / "access-paths.json").read_bytes()
query.write_text('{"maxDepth":0}')
run("invalid-query", valid=False)
assert (reports / "access-paths.json").read_bytes() == saved
query.write_text('{"maxDepth":2}')
run("recover")
