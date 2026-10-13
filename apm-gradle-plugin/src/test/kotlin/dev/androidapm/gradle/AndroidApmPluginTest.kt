package dev.androidapm.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class AndroidApmPluginTest {
    @TempDir lateinit var projectDir: Path

    @Test
    fun `diagnostic task supports configuration cache without an Android SDK`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"plugin-consumer\"")
        projectDir.resolve("build.gradle.kts").writeText(
            "plugins { id(\"io.github.android-apm-tool.apm\") }",
        )
        fun run() = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("apmDoctor", "--configuration-cache", "--stacktrace")
            .build()

        assertTrue(run().output.contains("IR probe: false"))
        assertTrue(run().output.contains("Reusing configuration cache."))
    }
}
