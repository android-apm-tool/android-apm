package dev.androidapm.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Reports configuration to the console without producing files")
abstract class ApmDoctorTask : DefaultTask() {
    @get:Input abstract val apmVersion: Property<String>
    @get:Input abstract val expectedKotlinVersion: Property<String>
    @get:Input abstract val irProbeEnabled: Property<Boolean>

    @TaskAction
    fun report() {
        logger.lifecycle("Android APM ${apmVersion.get()}")
        logger.lifecycle("Compiler baseline: Kotlin ${expectedKotlinVersion.get()}; JVM 17")
        logger.lifecycle("IR probe: ${irProbeEnabled.get()} (debug compilation only)")
        logger.lifecycle("Bootstrap only: screen catalog, runtime agent and server are not implemented.")
    }
}
