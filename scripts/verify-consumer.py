"""Temporarily apply the local plugin to a consumer, run collection, restore build files.

Usage: python scripts/verify-consumer.py <consumer-root> [Gradle task ...]
Does not modify app source, manifests, or version catalogs. Never uploads/distributes.
"""
import pathlib
import subprocess
import sys

plugin = pathlib.Path(__file__).resolve().parents[1]
consumer = pathlib.Path(sys.argv[1]).resolve()
tasks = sys.argv[2:] or [":app:apmMergeDebugCatalog"]
settings = consumer / "settings.gradle.kts"
build = consumer / "build.gradle.kts"
originals = {p: p.read_bytes() for p in (settings, build)}
logs = plugin / "build/consumer-verification"
logs.mkdir(parents=True, exist_ok=True)
for p, content in originals.items():
    (logs / (p.name + ".before")).write_bytes(content)
try:
    path = plugin.as_posix()
    text = originals[settings].decode("utf-8")
    text = text.replace("pluginManagement {", f'pluginManagement {{\n    includeBuild("{path}")', 1)
    text += f'\nincludeBuild("{path}")\n'
    settings.write_text(text, encoding="utf-8")
    text = originals[build].decode("utf-8")
    text = text.replace("plugins {", 'plugins {\n    id("io.github.android-apm-tool.apm") apply false', 1)
    text += '''
subprojects {
    listOf("com.android.application", "com.android.library", "org.jetbrains.kotlin.jvm").forEach { id ->
        pluginManager.withPlugin(id) { apply(plugin = "io.github.android-apm-tool.apm") }
    }
}
'''
    build.write_text(text, encoding="utf-8")
    command = [str(consumer / "gradlew.bat"), *tasks, "--build-cache", "--console=plain", "--max-workers=2"]
    with (logs / "consumer.log").open("w", encoding="utf-8") as log:
        result = subprocess.run(command, cwd=consumer, stdout=log, stderr=subprocess.STDOUT)
    print("Consumer exit:", result.returncode, "log:", logs / "consumer.log", flush=True)
    if result.returncode:
        print("\n".join((logs / "consumer.log").read_text(encoding="utf-8", errors="replace").splitlines()[-65:]))
    sys.exit(result.returncode)
finally:
    for p, content in originals.items():
        p.write_bytes(content)
    print("Restored consumer settings and build script.", flush=True)
