"""Worker-side consumer integration. Only the runner's private copy is edited."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import re

work = Path('/work/project')
if Path.cwd() != work or Path('/input').exists():
    raise SystemExit('ISOLATED_PRIVATE_COPY_REQUIRED')
toolkit = Path('/work/toolkit')
shutil.copytree('/bundle/toolkit-stage/project', toolkit, ignore=shutil.ignore_patterns('.git', '.gradle', '.kotlin', 'build', '__pycache__', 'engine-dependencies.log'))
settings = work / 'settings.gradle.kts'
build = work / 'build.gradle.kts'
text = settings.read_text()
if 'pluginManagement {' not in text:
    raise SystemExit('UNSUPPORTED_CONSUMER_SETTINGS')
settings.write_text(text.replace('pluginManagement {', 'pluginManagement {\n    includeBuild("/work/toolkit")', 1) + '\nincludeBuild("/work/toolkit")\n')
text = build.read_text()
if 'plugins {' not in text:
    raise SystemExit('UNSUPPORTED_CONSUMER_BUILD')
build.write_text(text.replace('plugins {', 'plugins {\n    id("io.github.android-apm-tool.apm") apply false', 1) + '''
subprojects {
    listOf("com.android.application", "com.android.library", "org.jetbrains.kotlin.jvm").forEach { id ->
        pluginManager.withPlugin(id) { apply(plugin = "io.github.android-apm-tool.apm") }
    }
}
''')
# No credentials or app keys are supplied. Compilation fixtures use inert values only.
(work / 'local.properties').write_text('sdk.dir=/bundle/sdk\nBASE_URL=https://example.invalid/\n')
wrapper = (work / 'gradle/wrapper/gradle-wrapper.properties').read_text()
version = re.search(r'gradle-([0-9]+\.[0-9]+(?:\.[0-9]+)?)-bin\.zip', wrapper)
if version is None:
    raise SystemExit('UNSUPPORTED_GRADLE_DISTRIBUTION')
command = ['/usr/bin/python3', str(toolkit / 'scripts/offline_gradle.py'), '--version', version.group(1), '--project', '.', *sys.argv[1:]]
result = subprocess.run(command, check=False)
sys.exit(result.returncode)
