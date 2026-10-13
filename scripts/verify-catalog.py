"""Real Compose/Nav3 sample regression: cache, whole snapshots, deletion, failure isolation."""
from pathlib import Path
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "samples/compose-app"
REPORT = SAMPLE / "app/build/reports/android-apm/catalog/debug/catalog.json"
PROJECT_REPORT = REPORT.with_name("project-catalog.json")
LINKING = SAMPLE / "app/build/intermediates/android-apm/debug/published/catalog.json"
SOURCE = SAMPLE / "app/src/main/kotlin/dev/androidapm/sample/MainActivity.kt"
EXTRA = SOURCE.with_name("CatalogVerification.kt")
LOGS = ROOT / "build/catalog-verification"
LOGS.mkdir(parents=True, exist_ok=True)
WRAPPER = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")


def run(name, *tasks, success=True):
    command = [str(WRAPPER), "-p", str(SAMPLE), *tasks, "--build-cache", "--configuration-cache",
               "--project-cache-dir", str(ROOT / "build/catalog-verification-cache"), "--console=plain", "--no-daemon"]
    result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace")
    (LOGS / (name + ".log")).write_text(result.stdout, encoding="utf-8")
    if (result.returncode == 0) != success:
        raise RuntimeError(name + "\n" + "\n".join(result.stdout.splitlines()[-65:]))
    print(name, "passed", flush=True)
    return result.stdout


def catalog():
    return json.loads(REPORT.read_text(encoding="utf-8"))


assert not EXTRA.exists(), "Refusing to overwrite an existing source"
original = SOURCE.read_bytes()
try:
    run("normal-before", ":app:assembleDebug")
    run("collect", ":app:apmMergeDebugCatalog")
    baseline = REPORT.read_bytes()
    value = catalog()
    assert value["schemaVersion"] == 2
    assert baseline.startswith(b'{\n  "schemaVersion": 2,\n')
    assert value["summary"]["entryDeclarations"] == 2
    assert not {"functions", "values", "callRelations", "contentRelations"}.intersection(value)
    assert json.loads(LINKING.read_text(encoding="utf-8"))["schemaVersion"] == 1
    project = json.loads(PROJECT_REPORT.read_text(encoding="utf-8"))
    assert project["schemaVersion"] == 2
    assert project["analysis"]["engine"] == "SOOTUP_HEROS"
    assert project["analysis"]["linkedCallSites"] >= 1
    definitions = {c["id"]: c["name"] for c in project["composables"]}
    detail = next(b for b in project["entryBindings"] if b["keyCandidates"][0]["type"].endswith(".Detail"))
    assert {"dev.androidapm.sample." + name for name in ["LazyNestedItem", "RepeatedItem", "IndexedItem", "DispatchedItem"]} <= {definitions[i] for i in detail["composableIds"]}
    assert "dev.androidapm.sample.UnlinkedItem" in {definitions[i] for i in project["unlinkedComposableIds"]}
    assert {b["keyCandidates"][0]["type"] for b in value["entryBindings"]} == {
        "dev.androidapm.sample.Home", "dev.androidapm.sample.Detail"}
    assert len(value["uiRoots"]) == 1
    assert len(value["navHosts"]) == 1
    assert value["navHosts"][0]["resolution"] == "RESOLVED"
    known = {c["id"] for c in value["composables"]}
    for binding in value["entryBindings"] + value["uiRoots"] + value["screenCandidates"]:
        assert set(binding["composableIds"]) <= known
    unchanged = run("up-to-date", ":app:apmMergeDebugCatalog")
    assert ":app:apmCompileDebugCatalog UP-TO-DATE" in unchanged
    assert ":app:apmAnalyzeDebugCatalog UP-TO-DATE" in unchanged
    assert json.loads(PROJECT_REPORT.read_text(encoding="utf-8")) == project
    assert "Reusing configuration cache" in unchanged
    assert REPORT.read_bytes() == baseline
    normal = run("normal-after", ":app:assembleDebug")
    assert ":app:compileDebugKotlin UP-TO-DATE" in normal
    cached = run("from-cache", ":app:clean", ":app:apmMergeDebugCatalog")
    assert ":app:apmCompileDebugCatalog FROM-CACHE" in cached
    assert REPORT.read_bytes() == baseline
    EXTRA.write_text('''package dev.androidapm.sample
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
private object Added
@Composable fun AddedScreen() {}
fun addedProvider() = entryProvider<Any> { entry<Added> { AddedScreen() } }
''', encoding="utf-8")
    run("added-source", ":app:apmCollectDebugCatalog")
    assert catalog()["summary"]["entryDeclarations"] == 3
    EXTRA.unlink()
    run("deleted-source", ":app:apmCollectDebugCatalog")
    assert REPORT.read_bytes() == baseline
    EXTRA.write_text("this is not valid Kotlin", encoding="utf-8")
    run("failed-compile", ":app:apmCollectDebugCatalog", success=False)
    assert REPORT.read_bytes() == baseline, "Failed compile replaced the last successful report"
    EXTRA.unlink()
    SOURCE.unlink()
    run("no-sources", ":app:apmMergeDebugCatalog")
    assert catalog()["summary"]["entryDeclarations"] == 0
    assert catalog()["entryBindings"] == []
    assert json.loads(PROJECT_REPORT.read_text(encoding="utf-8"))["analysis"]["applicationClasses"] == 0
finally:
    if EXTRA.exists():
        EXTRA.unlink()
    SOURCE.write_bytes(original)
run("restored", ":app:apmMergeDebugCatalog", ":app:assembleDebug")
assert REPORT.read_bytes() == baseline
print("All sample catalog and cache checks passed.")
