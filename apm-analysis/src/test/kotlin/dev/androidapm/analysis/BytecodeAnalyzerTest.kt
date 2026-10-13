package dev.androidapm.analysis

import dev.androidapm.catalog.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.*

class BytecodeAnalyzerTest {
    @TempDir lateinit var directory: Path

    @Test fun `real bytecode dispatch and IFDS argument return flow enrich source bindings`() {
        val code = """
            package fixture;
            public class Demo {
              interface Renderer { void Content(); }
              static class First implements Renderer {
                public void Content() { FirstScreen(); }
              }
              static class Second implements Renderer {
                public void Content() { SecondScreen(); }
              }
              static void FirstScreen() { System.out.println("first"); }
              static void SecondScreen() { System.out.println("second"); }
              static void dispatch(Renderer renderer) { renderer.Content(); }
              static Runnable identity(Runnable value) { return value; }
              static void invoke(Runnable callback) { callback.run(); }
              static void entry(boolean flag) {
                Runnable value = flag ? Demo::FirstScreen : Demo::SecondScreen;
                Runnable copied = identity(value);
                invoke(copied);
              }
            }
        """.trimIndent()
        val input = directory.resolve("Demo.java").apply { writeText(code) }
        val classes = directory.resolve("classes").createDirectories()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "17", "-g", "-d", classes.toString(), input.toString()))
        fun source(line: Int) = record("path" to "Demo.java", "line" to line, "endLine" to line)
        fun function(symbol: String, name: String, line: Int, composable: Boolean = false, body: Boolean = true) =
            record("id" to symbol, "symbol" to symbol, "name" to symbol, "simpleName" to name,
                "packageName" to "fixture", "kind" to "FUNCTION", "hasBody" to body,
                "composable" to composable, "source" to source(line))
        val functions = listOf(function("First.Content", "Content", 5), function("Second.Content", "Content", 8),
            function("FirstScreen", "FirstScreen", 10, true), function("SecondScreen", "SecondScreen", 11, true),
            function("dispatch", "dispatch", 12), function("identity", "identity", 13), function("invoke", "invoke", 14),
            function("entry", "entry", 16), function("Renderer.Content", "Content", 3, body = false))
        fun call(id: String, owner: String, target: String, name: String, line: Int, ui: Boolean = false) =
            record("id" to id, "owner" to owner, "target" to target, "name" to name,
                "source" to source(line), "composable" to ui)
        val module = record("schemaVersion" to 1, "moduleId" to "fixture", "variant" to "debug",
            "functions" to functions, "composableDeclarations" to functions.filter { it["composable"] == true },
            "callRelations" to listOf(call("virtual", "dispatch", "Renderer.Content", "Renderer.Content", 12),
                call("callback", "invoke", "Runnable.run", "Runnable.run", 14),
                call("first", "First.Content", "FirstScreen", "FirstScreen", 5, true),
                call("second", "Second.Content", "SecondScreen", "SecondScreen", 8, true)),
            "uiRoots" to listOf(record("id" to "root", "arguments" to listOf(record("name" to "content",
                "value" to record("kind" to "FUNCTION", "ref" to "dispatch"))))))
        val analysis = BytecodeAnalyzer.analyze(listOf(module), listOf(classes.toFile()), emptyList())
        val targets = analysis.records("analysisCallTargets").associateBy { it.string("callId") }
        assertEquals(setOf("First.Content", "Second.Content"), (targets.getValue("virtual")["targets"] as List<*>).toSet())
        assertEquals(setOf("FirstScreen", "SecondScreen"), (targets.getValue("callback")["targets"] as List<*>).toSet())
        assertTrue((targets.getValue("callback")["evidence"] as List<*>).contains("HEROS_IFDS"))
        val linked = CatalogLinker.link(listOf(module + analysis))
        val root = linked.records("uiRootBindings").single()
        assertEquals(setOf("FirstScreen", "SecondScreen"), root.records("composableCalls").map { it.string("name") }.toSet())
        assertEquals("PARTIAL", root["resolution"], "CHA alternatives are may-targets, not a concrete runtime receiver")
        val ambiguous = module + record("functions" to (functions + function("ambiguous", "FirstScreen", 10, true)))
        val rejected = BytecodeAnalyzer.analyze(listOf(ambiguous), listOf(classes.toFile()), emptyList())
        val callback = rejected.records("analysisCallTargets").single { it["callId"] == "callback" }
        assertEquals(listOf("SecondScreen"), callback["targets"], "Overlapping source identities must not be guessed")
        assertTrue(rejected.records("collectionDiagnostics").any { it["code"] == "BYTECODE_SOURCE_BRIDGE_GAPS" })
    }

    @Test fun `missing source identity never invents a binding`() {
        val source = directory.resolve("Unknown.java").apply { writeText("public class Unknown { void x() {} }") }
        val classes = directory.resolve("classes").createDirectories()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g:none", "-d", classes.toString(), source.toString()))
        val result = BytecodeAnalyzer.analyze(emptyList(), listOf(classes.toFile()), emptyList())
        assertEquals(0, result["analysis"].record()["mappedMethods"])
        assertTrue(result.records("analysisCallTargets").isEmpty())
    }
}
