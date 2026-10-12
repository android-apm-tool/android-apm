package dev.androidapm.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.writeText

class ApmCompilerPluginTest {
    @TempDir lateinit var workDir: Path

    private fun compile(enabled: String?): Pair<ExitCode, String> {
        val source = workDir.resolve("Fixture.kt")
        source.writeText("package fixture\nclass Fixture")
        val output = ByteArrayOutputStream()
        val args = mutableListOf(
            source.toString(), "-d", workDir.resolve("classes").toString(),
            "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-classpath", Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString(),
            "-Xplugin=${System.getProperty("apm.test.pluginJar")}",
        )
        if (enabled != null) args += listOf("-P", "plugin:dev.androidapm.compiler:enabled=$enabled")
        val exit = PrintStream(output).use { K2JVMCompiler().exec(it, *args.toTypedArray()) }
        return exit to output.toString(Charsets.UTF_8)
    }

    @Test
    fun `packaged service providers run the IR extension in a real compiler`() {
        val (exit, output) = compile("true")
        assertEquals(ExitCode.OK, exit, output)
        assertTrue(output.contains("APM IR probe:"), output)
        assertTrue(output.contains("sourceFiles=1"), output)
    }

    @Test
    fun `compiler plugin is inactive without explicit opt in`() {
        val (exit, output) = compile(null)
        assertEquals(ExitCode.OK, exit, output)
        assertFalse(output.contains("APM IR probe:"), output)
    }

    @Test
    fun `invalid options fail compilation instead of silently disabling analysis`() {
        val (exit, output) = compile("yes")
        assertEquals(ExitCode.COMPILATION_ERROR, exit, output)
        assertTrue(output.contains("enabled must be true or false"), output)
    }
}
