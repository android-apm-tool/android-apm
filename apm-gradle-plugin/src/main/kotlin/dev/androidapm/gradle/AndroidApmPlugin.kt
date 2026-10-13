package dev.androidapm.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool
import java.util.Properties

class AndroidApmPlugin : KotlinCompilerPluginSupportPlugin {
    private val metadata = Properties().apply {
        checkNotNull(AndroidApmPlugin::class.java.getResourceAsStream("/android-apm/plugin.properties")) {
            "Android APM plugin metadata is missing"
        }.use(::load)
    }

    override fun apply(target: Project) {
        registerCatalogTasks(target, getPluginArtifact())
        target.tasks.register("apmDoctor", ApmDoctorTask::class.java) {
            it.group = "android apm"
            it.description = "Reports the bootstrap plugin configuration; does not compile or run the app."
            it.apmVersion.set(metadata.getProperty("version"))
            it.expectedKotlinVersion.set(metadata.getProperty("kotlinVersion"))
            it.irProbeEnabled.set(target.providers.gradleProperty("apm.irProbe").map {
                value -> value.toBooleanStrict()
            }.orElse(false))
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.name == "debug" &&
            kotlinCompilation.target.project.providers.gradleProperty("apm.irProbe")
                .map(String::toBooleanStrict).orElse(false).get()

    override fun getCompilerPluginId(): String = "dev.androidapm.compiler"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = metadata.getProperty("group"),
        artifactId = "apm-compiler-plugin",
        version = metadata.getProperty("version"),
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val compileTask = kotlinCompilation.compileTaskProvider
        val reportDirectory = project.layout.buildDirectory.dir("reports/android-apm/debug")
        val reportFile = reportDirectory.map { it.file("ir-probe.txt") }
        // Kotlin's incremental compiler initializes additional outputs as directories.
        compileTask.configure { it.outputs.dir(reportDirectory).withPropertyName("apmIrProbeReports") }

        val reportTask = project.tasks.register("apmReportDebug", ApmReportTask::class.java) {
            it.group = "android apm"
            it.description = "Displays the saved debug IR diagnostic, including on up-to-date and cached builds."
            it.dependsOn(compileTask)
            it.reports.from(reportFile)
            it.kotlinSources.from(compileTask.map { task -> (task as KotlinCompileTool).sources })
        }
        // A normal dependency does not print a stale diagnostic if compilation fails.
        val lifecycleNames = setOf("assembleDebug", "bundleDebug", kotlinCompilation.compileAllTaskName)
        project.tasks.matching { it.name in lifecycleNames }.configureEach { it.dependsOn(reportTask) }

        return reportFile.map { file ->
            listOf(
                SubpluginOption("enabled", "true"),
                // The absolute location is internal; the file itself is a declared compile output above.
                FilesSubpluginOption("reportFile", listOf(file.asFile)),
            )
        }
    }
}
