"""Compile the real AGP/Compose sample and verify opt-in IR plugin loading.

Requires JDK 17 and Android SDK 36. Does not install or launch the app.
Full logs are kept in build/bootstrap-verification/.
"""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LOGS = ROOT / "build" / "bootstrap-verification"
WRAPPER = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
MARKER = "APM IR probe:"


def compile_sample(name, task, enabled):
    LOGS.mkdir(parents=True, exist_ok=True)
    command = [
        str(WRAPPER), "-p", "samples/compose-app", task,
        f"-Papm.irProbe={str(enabled).lower()}",
        "--rerun-tasks", "--no-build-cache", "--no-watch-fs", "--console=plain", "--info",
    ]
    print(f"Compiling {name} ...", flush=True)
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (LOGS / f"{name}.log").write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{name} failed:\n" + "\n".join(result.stdout.splitlines()[-80:]))
    return result.stdout


debug = compile_sample("debug-enabled", ":app:assembleDebug", True)
probe_lines = [line for line in debug.splitlines() if MARKER in line]
for symbol in ("SampleApp", "HomeScreen", "DetailScreen"):
    if not any(f"dev.androidapm.sample.{symbol}" in line for line in probe_lines):
        raise RuntimeError(f"IR probe did not observe {symbol}; see {LOGS}")
if not (ROOT / "samples/compose-app/app/build/outputs/apk/debug/app-debug.apk").is_file():
    raise RuntimeError("Debug APK is missing")

disabled = compile_sample("debug-disabled", ":app:compileDebugKotlin", False)
release = compile_sample("release-enabled", ":app:compileReleaseKotlin", True)
if MARKER in disabled or MARKER in release:
    raise RuntimeError("IR probe ran in an excluded compilation")

print("PASS: debug APK built; IR sees the three sample Composables; disabled/debug and release stay inactive.")
print(f"Logs: {LOGS}")
print("This verifies plugin integration, not screen discovery coverage or device behavior.")
