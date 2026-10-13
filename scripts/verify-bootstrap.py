"""Verify the real AGP/Compose sample and replay of cached IR diagnostics.

Requires JDK 17 and Android SDK 36. Does not install or launch the app.
Full logs are kept in build/bootstrap-verification/.
"""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LOGS = ROOT / "build" / "bootstrap-verification"
WRAPPER = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
REPORT = ROOT / "samples/compose-app/app/build/reports/android-apm/debug/ir-probe.txt"
MARKER = "APM IR probe:"


def build_sample(name, tasks, enabled):
    LOGS.mkdir(parents=True, exist_ok=True)
    command = [
        str(WRAPPER), "-p", "samples/compose-app", *tasks,
        f"-Papm.irProbe={str(enabled).lower()}",
        "--build-cache", "--configuration-cache", "--console=plain", "--no-daemon",
        # Keep the verifier independent from Android Studio's Gradle distribution.
        "--project-cache-dir", str(ROOT / "build" / "bootstrap-verification-cache"),
    ]
    print(f"Building {name} ...", flush=True)
    result = subprocess.run(command, cwd=ROOT, text=True, encoding="utf-8", errors="replace",
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (LOGS / f"{name}.log").write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{name} failed:\n" + "\n".join(result.stdout.splitlines()[-80:]))
    return result.stdout


def assert_report(output):
    probe_lines = [line for line in output.splitlines() if line.startswith(MARKER)]
    if len(probe_lines) != 1:
        raise RuntimeError(f"Expected one lifecycle diagnostic, found {len(probe_lines)}; see {LOGS}")
    for symbol in ("SampleApp", "HomeScreen", "DetailScreen"):
        if f"dev.androidapm.sample.{symbol}" not in probe_lines[0]:
            raise RuntimeError(f"IR probe did not observe {symbol}; see {LOGS}")
    if REPORT.read_text(encoding="utf-8").strip() != probe_lines[0]:
        raise RuntimeError("Build log does not match the saved compiler diagnostic")


debug = build_sample("debug-enabled", [":app:clean", ":app:assembleDebug"], True)
assert_report(debug)
baseline_report = REPORT.read_bytes()
if not (ROOT / "samples/compose-app/app/build/outputs/apk/debug/app-debug.apk").is_file():
    raise RuntimeError("Debug APK is missing")

unchanged = build_sample("debug-up-to-date", [":app:assembleDebug"], True)
assert_report(unchanged)
if ":app:compileDebugKotlin UP-TO-DATE" not in unchanged:
    raise RuntimeError("Unchanged build recompiled Kotlin")

reused = build_sample("debug-configuration-cache", [":app:assembleDebug"], True)
assert_report(reused)
if "Reusing configuration cache." not in reused or ":app:compileDebugKotlin UP-TO-DATE" not in reused:
    raise RuntimeError("Configuration cache was not reused without recompiling Kotlin")

restored = build_sample("debug-from-cache", [":app:clean", ":app:assembleDebug"], True)
assert_report(restored)
if ":app:compileDebugKotlin FROM-CACHE" not in restored:
    raise RuntimeError("Compiler diagnostic was not restored with the build cache")
if REPORT.read_bytes() != baseline_report:
    raise RuntimeError("The restored diagnostic differs from the cached output")

disabled = build_sample("debug-disabled", [":app:assembleDebug"], False)
release = build_sample("release-enabled", [":app:compileReleaseKotlin"], True)
if MARKER in disabled or MARKER in release or ":app:apmReportDebug" in disabled:
    raise RuntimeError("IR probe ran or replayed stale data in an excluded compilation")

print("PASS: debug APK, saved IR diagnostic, UP-TO-DATE, FROM-CACHE and configuration cache.")
print("PASS: disabled debug and release stay inactive.")
print(f"Logs: {LOGS}")
print("This verifies plugin integration and last-invocation replay, not complete screen discovery.")
