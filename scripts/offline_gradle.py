"""Worker-side Gradle launcher. Run only through offline_runner.py, never as an isolation claim."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--version", default="9.1.0")
parser.add_argument("--project", default="samples/compose-app")
parser.add_argument("--heap", default="8g", choices=["2g", "4g", "8g", "12g"])
parser.add_argument("tasks", nargs="*", default=[":app:apmFindDebugAccessPaths"])
args = parser.parse_args()
bundle = Path("/bundle")
dist = list((bundle / "gradle-home/wrapper/dists" / ("gradle-" + args.version + "-bin")).glob("*/gradle-*/bin/gradle"))
if len(dist) != 1:
    raise SystemExit("DEPENDENCY_NOT_PREPARED:Gradle distribution")
bootstrap = bundle / "bootstrap-cache"
if bootstrap.exists():
    shutil.copytree(bootstrap, Path(os.environ["GRADLE_USER_HOME"]) / "caches", dirs_exist_ok=True,
        ignore=shutil.ignore_patterns("*.lock", "*.lck", "gc.properties", "modules-2", "build-cache-*"))
command = ["/bin/sh", str(dist[0]), "-p", args.project, *(args.tasks or [":app:apmFindDebugAccessPaths"]),
    "--offline", "--no-daemon", "--no-build-cache", "--console=plain", "--max-workers=2",
    "-Pkotlin.compiler.execution.strategy=in-process", "-Dorg.gradle.java.installations.auto-download=false"]
command += ["-Dorg.gradle.jvmargs=-Xmx" + args.heap + " -Dfile.encoding=UTF-8"]
command += ["-Papm.analysisHeap=" + args.heap]
result = subprocess.run(command, check=False)
if result.returncode == 0:
    reports = Path(args.project) / "app/build/reports/android-apm/catalog/debug"
    if reports.is_dir():
        shutil.copytree(reports, "/output/reports", dirs_exist_ok=True)
sys.exit(result.returncode)
