package dev.androidapm.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.util.Properties

class AndroidApmPlugin : KotlinCompilerPluginSupportPlugin {
    private val metadata = Properties().apply {
        checkNotNull(AndroidApmPlugin::class.java.getResourceAsStream("/android-apm/plugin.properties")) {
            "Android APM plugin metadata is missing"
        }.use(::load)
    }

    override fun apply(target: Project) {
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

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider {
            listOf(SubpluginOption("enabled", "true"))
        }
}
