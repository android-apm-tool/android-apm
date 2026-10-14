package dev.androidapm.compiler

import dev.androidapm.catalog.*
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.*

class CatalogCollectorTest {
    @TempDir lateinit var directory: Path

    private fun compile(source: String): Record {
        val annotations = directory.resolve("Annotations.kt").apply { writeText("""
            package androidx.compose.runtime
            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
            annotation class Composable
        """.trimIndent()) }
        val nav = directory.resolve("Navigation.kt").apply { writeText("""
            package androidx.navigation3.runtime
            import androidx.compose.runtime.Composable
            class NavEntry<T>(val key: T, val content: @Composable (T) -> Unit)
            class EntryProviderScope<T> {
                inline fun <reified K : T> entry(noinline content: @Composable (K) -> Unit) {}
                fun entry(key: T, content: @Composable (T) -> Unit) {}
            }
            fun <T> entryProvider(fallback: (T) -> NavEntry<T> = { error("unknown") }, builder: EntryProviderScope<T>.() -> Unit): (T) -> NavEntry<T> = fallback
        """.trimIndent()) }
        val input = directory.resolve("화면.kt").apply { writeText("""
            package fixture
            import androidx.compose.runtime.Composable
            import androidx.navigation3.runtime.*
            $source
        """.trimIndent()) }
        val host = directory.resolve("NavDisplay.kt").apply { writeText("""
            package androidx.navigation3.ui
            import androidx.compose.runtime.Composable
            import androidx.navigation3.runtime.NavEntry
            @Composable fun <T> NavDisplay(entryProvider: (T) -> NavEntry<T>) {}
        """.trimIndent()) }
        val report = directory.resolve("catalog.json")
        val preview = directory.resolve("Preview.kt").apply { writeText("""
            package androidx.compose.ui.tooling.preview
            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
            annotation class Preview
        """.trimIndent()) }
        val args = mutableListOf(annotations.toString(), nav.toString(), input.toString(), host.toString(), preview.toString(),
            "-d", directory.resolve("classes").toString(), "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-classpath", Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString(),
            "-Xplugin=" + System.getProperty("apm.test.pluginJar"))
        mapOf("catalog" to "true", "catalogFile" to report.toString(), "moduleId" to "fixture:app",
            "variant" to "debug", "projectRoot" to directory.toString()).forEach { (key, value) ->
            args += listOf("-P", "plugin:dev.androidapm.compiler:$key=$value")
        }
        val output = ByteArrayOutputStream()
        val exit = PrintStream(output).use { K2JVMCompiler().exec(it, *args.toTypedArray()) }
        assertEquals(ExitCode.OK, exit, output.toString(Charsets.UTF_8))
        return Json.decode(report.readText()).record()
    }

    @Test fun `custom preview annotations and else exclusions survive real compiler collection`() {
        val catalog = compile("""
            @androidx.compose.ui.tooling.preview.Preview annotation class CustomPreview
            @CustomPreview @Composable fun Sample() { State(true) }
            @Composable fun State(flag: Boolean) { if (flag) First() else Second() }
            @Composable fun First() {}
            @Composable fun Second() {}
        """.trimIndent())
        assertEquals(true, catalog.records("functions").single { it["name"] == "fixture.Sample" }["preview"])
        val second = catalog.records("callRelations").single { it["name"] == "fixture.Second" }
        assertTrue(second.records("conditions").single().records("excluded").isNotEmpty())
        val analysis = UiAnalysis.create(catalog).report
        assertTrue((analysis.records("declarations").single { it["name"] == "fixture.Second" }["reasonCodes"] as List<*>).contains("PREVIEW_REACHABLE"))
    }

    @Test fun `whole content includes wrapper and both branches but not same named fake DSL`() {
        val catalog = compile("""
            object Home
            @Composable fun First() {}
            @Composable fun Second() {}
            @Composable fun Frame(content: @Composable () -> Unit) { content() }
            fun entry(block: () -> Unit) {}
            fun provider(flag: Boolean) = entryProvider<Any> {
                entry<Home> { Frame { if (flag) First() else Second() } }
            }
            fun unrelated() { entry { println("not navigation") } }
        """.trimIndent())
        assertEquals(1, catalog.records("entryDeclarations").size)
        val binding = catalog.records("entryBindings").single()
        assertEquals("RESOLVED", binding["resolution"])
        assertEquals(setOf("fixture.Frame", "fixture.First", "fixture.Second"), binding.records("composableCalls").map { it.string("name") }.toSet())
        assertTrue(binding.records("composableCalls").filter { it.string("name") != "fixture.Frame" }.all { it.records("conditions").isNotEmpty() })
        assertEquals("화면.kt", binding["source"].record()["path"])
    }

    @Test fun `shared registration helper retains two provider bindings`() {
        val catalog = compile("""
            object Home
            @Composable fun Screen() {}
            fun EntryProviderScope<Any>.register() { entry<Home> { Screen() } }
            fun one() = entryProvider<Any> { register() }
            fun two() = entryProvider<Any> { register() }
        """.trimIndent())
        assertEquals(1, catalog.records("entryDeclarations").size)
        assertEquals(2, catalog.records("entryBindings").size)
        assertEquals(2, catalog.records("entryBindings").map { it["providerId"] }.distinct().size)
    }

    @Test fun `generic helper substitutes the key type and constant arguments at each call site`() {
        val catalog = compile("""
            object Home
            object Detail
            @Composable fun Screen() {}
            inline fun <reified T : Any> EntryProviderScope<Any>.register() { entry<T> { Screen() } }
            fun provider() = entryProvider<Any> { register<Home>(); register<Detail>() }
        """.trimIndent())
        val bindings = catalog.records("entryBindings")
        assertEquals(2, bindings.size)
        assertEquals(2, bindings.map { it.string("id") }.distinct().size)
        assertEquals(setOf("fixture.Home", "fixture.Detail"), bindings.flatMap { it.records("keyCandidates") }.map { it.string("type") }.toSet())
        assertTrue(bindings.all { it["resolution"] == "RESOLVED" })
    }

    @Test fun `direct provider retains whole content and class initializers are safe`() {
        val catalog = compile("""
            class Initialized(val value: Int) { init { println(this.value) } }
            @Composable fun Screen() {}
            @Composable fun Host() {
                androidx.navigation3.ui.NavDisplay<String> { key -> NavEntry(key) { Screen() } }
            }
        """.trimIndent())
        val binding = catalog.records("entryBindings").single()
        assertEquals("DIRECT_PROVIDER", binding["context"])
        assertEquals("fixture.Screen", binding.records("composableCalls").single()["name"])
        assertEquals("RESOLVED", catalog.records("navHostBindings").single()["resolution"])
        assertEquals("PARTIAL", binding["resolution"], "Runtime key must not be presented as a known key")
    }

    @Test fun `dynamic content is partial and data class render lambdas remain candidates`() {
        val catalog = compile("""
            object Home
            @Composable fun Screen() {}
            data class Route(val path: String, val render: @Composable () -> Unit)
            val routes = listOf(Route("home", { Screen() }))
            fun provider(dynamic: @Composable () -> Unit) = entryProvider<Any> { entry<Home> { dynamic() } }
        """.trimIndent())
        assertEquals("PARTIAL", catalog.records("entryBindings").single()["resolution"])
        assertTrue(catalog.records("diagnostics").any { it["code"] == "UNRESOLVED_CONTENT_FLOW" })
        val candidate = catalog.records("screenCandidates").single()
        assertEquals("UNCLASSIFIED", candidate["classification"])
        assertEquals("fixture.Screen", candidate.records("composableCalls").single()["name"])
    }

    @Test fun `mutable content cannot be resolved solely from its initializer`() {
        val catalog = compile("""
            object Home
            @Composable fun First() {}
            @Composable fun Second() {}
            var chosen: @Composable () -> Unit = { First() }
            fun change() { chosen = { Second() } }
            fun provider() = entryProvider<Any> { entry<Home> { chosen() } }
        """.trimIndent())
        val binding = catalog.records("entryBindings").single()
        assertEquals("PARTIAL", binding["resolution"])
        assertTrue(catalog.records("composableDeclarations").any { it["name"] == "fixture.Second" })
        assertTrue(binding.records("composableCalls").none { it["name"] == "fixture.First" })
    }

    @Test fun `fallback and explicit key are preserved and repeated output is deterministic`() {
        val source = """
            @Composable fun Screen() {}
            fun provider() = entryProvider<String>(fallback = { key -> NavEntry(key) { Screen() } }) {
                entry("home") { Screen() }
            }
        """.trimIndent()
        val first = compile(source)
        assertEquals(2, first.records("entryDeclarations").size)
        assertEquals(setOf("FALLBACK", "REGISTRATION"), first.records("entryBindings").map { it.string("context") }.toSet())
        assertEquals(Json.encode(first), Json.encode(compile(source)))
        val afterDelete = compile("@Composable fun Remaining() {}")
        assertTrue(afterDelete.records("entryDeclarations").isEmpty())
        assertTrue(afterDelete.records("entryBindings").isEmpty())
    }
}
