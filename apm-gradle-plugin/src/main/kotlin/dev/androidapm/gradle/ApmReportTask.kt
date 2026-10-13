package dev.androidapm.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Displays an existing compiler output on every requested build")
abstract class ApmReportTask : DefaultTask() {
    // InputFiles permits an absent output when the compiler has not produced a report.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val reports: ConfigurableFileCollection

    // Do not display an old invocation after all Kotlin sources have been removed.
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinSources: ConfigurableFileCollection

    @TaskAction
    fun report() {
        val savedReports = reports.files.filter { it.isFile }
        if (savedReports.isEmpty()) {
            logger.lifecycle("Android APM: no saved IR diagnostic is available.")
            return
        }
        logger.lifecycle("Android APM: last IR invocation (incremental compilation may cover only changed sources).")
        savedReports.sortedBy { it.name }.forEach { logger.lifecycle(it.readText(Charsets.UTF_8).trimEnd()) }
    }
}
