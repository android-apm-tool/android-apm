package dev.androidapm.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class AndroidApmPluginTest {
    @TempDir lateinit var projectDir: Path

    private fun runner(vararg arguments: String) = GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withPluginClasspath()
        .withArguments(*arguments, "--configuration-cache", "--stacktrace")

    @Test
    fun `diagnostic task supports configuration cache without an Android SDK`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"plugin-consumer\"")
        projectDir.resolve("build.gradle.kts").writeText(
            "plugins { id(\"io.github.android-apm-tool.apm\") }",
        )
        assertTrue(runner("apmDoctor").build().output.contains("IR probe: false"))
        assertTrue(runner("apmDoctor").build().output.contains("Reusing configuration cache."))
    }

    @Test
    fun `report replays without recompiling and does not report after a failed compile`() {
        projectDir.resolve("settings.gradle").writeText(
            "rootProject.name = 'report-consumer'\nbuildCache { local { directory = file('local-cache') } }",
        )
        projectDir.resolve("Fixture.kt").writeText("fun fixture() = Unit")
        projectDir.resolve("build.gradle").writeText(
            """
            import dev.androidapm.gradle.ApmReportTask
            plugins { id 'io.github.android-apm-tool.apm' }

            @CacheableTask
            abstract class FakeCompile extends DefaultTask {
                @InputFile @PathSensitive(PathSensitivity.RELATIVE)
                abstract RegularFileProperty getSourceFile()
                @OutputFile abstract RegularFileProperty getReportFile()
                @TaskAction void compile() {
                    if (sourceFile.get().asFile.text == 'fail') throw new GradleException('Fixture compile failed')
                    def report = reportFile.get().asFile
                    report.parentFile.mkdirs()
                    report.text = 'APM IR probe: fixture'
                }
            }
            def compileProbe = tasks.register('compileProbe', FakeCompile) {
                sourceFile.set(layout.projectDirectory.file('Fixture.kt'))
                reportFile.set(layout.buildDirectory.file('reports/probe.txt'))
            }
            def reportProbe = tasks.register('reportProbe', ApmReportTask) {
                reports.from(compileProbe.flatMap { it.reportFile })
                kotlinSources.from(layout.projectDirectory.file('Fixture.kt'))
            }
            tasks.register('assembleDebug') { dependsOn(reportProbe) }
            """.trimIndent(),
        )
        fun run() = runner("assembleDebug", "--build-cache").build()

        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":compileProbe")?.outcome)
        assertTrue(first.output.contains("APM IR probe: fixture"), first.output)

        val unchanged = run()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":compileProbe")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, unchanged.task(":reportProbe")?.outcome)
        assertTrue(unchanged.output.contains("Reusing configuration cache."), unchanged.output)
        assertTrue(unchanged.output.contains("APM IR probe: fixture"), unchanged.output)

        Files.delete(projectDir.resolve("build/reports/probe.txt"))
        val restored = run()
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileProbe")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, restored.task(":reportProbe")?.outcome)
        assertTrue(restored.output.contains("APM IR probe: fixture"), restored.output)

        projectDir.resolve("Fixture.kt").writeText("fail")
        val failed = runner("assembleDebug", "--build-cache").buildAndFail()
        assertNull(failed.task(":reportProbe"))
        assertFalse(failed.output.contains("APM IR probe: fixture"), failed.output)
    }
}
