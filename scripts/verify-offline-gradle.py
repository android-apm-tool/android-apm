"""Exercise real Gradle settings/buildSrc/child JVM under OS isolation using PUBLIC fixtures.

Preparation copies an already downloaded Gradle distribution; no downloads occur here.
This script accepts no customer-project argument and may run in hosted synthetic CI.
"""
import argparse
import json
from pathlib import Path
import shutil
import tempfile

from offline_runner import execute
from runner_bundle import seal

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--gradle-home", required=True, help="Prepared Gradle installation containing bin/gradle")
args = parser.parse_args()
gradle = Path(args.gradle_home)
assert (gradle / "bin/gradle").is_file(), "Prepare Gradle before invoking this verifier"
results = []
with tempfile.TemporaryDirectory(prefix="apm-gradle-security-") as directory:
    root = Path(directory)
    root.chmod(0o700)
    bundle = root / "bundle"
    bundle.mkdir(mode=0o700)
    shutil.copytree(gradle, bundle / "gradle")
    seal(bundle)
    probe = 'new java.net.Socket("192.0.2.1", 443).close()\n'
    fixtures = {
        "normal": ({"settings.gradle": "rootProject.name='public-fixture'\n", "build.gradle": "tasks.register('verify') {}\n"}, "COMPLETED", "verify"),
        "settings-egress": ({"settings.gradle": probe}, "EGRESS_ATTEMPT_BLOCKED", "help"),
        "buildsrc-egress": ({"settings.gradle": "rootProject.name='public-fixture'\n", "buildSrc/build.gradle": probe}, "EGRESS_ATTEMPT_BLOCKED", "help"),
        "child-jvm-egress": ({"settings.gradle": "rootProject.name='public-fixture'\n",
            "build.gradle": "tasks.register('verify', Exec) { commandLine(System.getenv('JAVA_HOME') + '/bin/java', 'Probe.java') }\n",
            "Probe.java": 'class Probe { public static void main(String[] args) throws Exception { new java.net.Socket("192.0.2.1",443); } }\n'}, "EGRESS_ATTEMPT_BLOCKED", "verify"),
        "dependency-missing": ({"settings.gradle": "rootProject.name='public-fixture'\n",
            "build.gradle": "configurations { missing }; repositories { mavenCentral() }; dependencies { missing 'invalid.apm.fixture:missing:0' }; tasks.register('verify') { doLast { configurations.missing.files } }\n"}, "WORKER_FAILED", "verify"),
    }
    for name, (files, expected, task) in fixtures.items():
        source = root / ("source-" + name)
        source.mkdir(mode=0o700)
        for relative, content in files.items():
            path = source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        policy, run = execute(root, source, ["/bin/sh", "/bundle/gradle/bin/gradle", task, "--offline", "--no-daemon",
            "--no-build-cache", "--console=plain", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx512m"],
            bundle=bundle, timeout=180, synthetic_ci=True)
        assert policy["status"] == expected, f"{name}: {policy['status']} (local audit: {run.name})"
        assert policy["blockedAttempts"] > 0 if expected == "EGRESS_ATTEMPT_BLOCKED" else policy["blockedAttempts"] == 0
        results.append({"fixture": name, "status": policy["status"], "blockedAttempts": policy["blockedAttempts"]})
        print(name + " passed", flush=True)
output = Path(__file__).resolve().parents[1] / "build/offline-verification"
output.mkdir(parents=True, exist_ok=True)
(output / "gradle-result.json").write_text(json.dumps({"inputClass": "PUBLIC_SYNTHETIC_ONLY", "results": results}, indent=2) + "\n")
