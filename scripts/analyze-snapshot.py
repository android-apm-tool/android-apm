"""Worker-only replay of collected IR/bytecode; invoke through offline_runner.py.

Expected private snapshot: analysis-inputs.json (catalogs/classes/libraries),
AndroidManifest.xml. A prepared bundle/analyzer-runtime contains pinned tool jars.
No Gradle, target classes or target build scripts are executed during replay.
"""
import os
from pathlib import Path
import subprocess

assert Path.cwd() == Path("/work/project") and not Path("/input").exists(), "ISOLATED_PRIVATE_COPY_REQUIRED"
runtime = sorted(Path("/bundle/analyzer-runtime").glob("*.jar"))
assert runtime, "DEPENDENCY_NOT_PREPARED"
java = [str(Path(os.environ["JAVA_HOME"]) / "bin/java"), "-Xmx8g", "-Dorg.slf4j.simpleLogger.defaultLogLevel=error", "-cp", ":".join(map(str, runtime))]
subprocess.run([*java, "dev.androidapm.analysis.AnalysisMain", "analysis-inputs.json", "/work/bytecode.json"], check=True)
subprocess.run([*java, "dev.androidapm.catalog.TransitionMain", "--modules", "analysis-inputs.json", "/work/bytecode.json", "AndroidManifest.xml", "/output/reports"], check=True)
