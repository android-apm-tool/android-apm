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
    @Test fun `opaque collection predicates distinguish inputs and objects but reject identical contradictory reads`() {
        val catalog = compile("""
            object Home
            object Detail
            object Other
            object Never
            class Probe(seed: String) {
                val values = mutableSetOf(seed)
                fun has(key: String): Boolean = values.contains(key)
            }
            @Composable fun App() {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> {
                        val one = Probe("first")
                        val two = Probe("second")
                        if (one.has("first")) {
                            if (!one.has("second")) androidx.compose.material3.Button(onClick = { stack.add(Detail) })
                            if (!one.has("first")) androidx.compose.material3.Button(onClick = { stack.add(Never) })
                            if (!two.has("first")) androidx.compose.material3.Button(onClick = { stack.add(Other) })
                        }
                    }
                    entry<Detail> {}
                    entry<Other> {}
                    entry<Never> {}
                })
            }
        """.trimIndent())
        val result = TransitionAnalysis.create(catalog).report
        val effects = result.records("effects").associateBy { it["id"] }
        assertEquals(setOf("fixture.Detail", "fixture.Other"), result.records("transitions").flatMap { it.strings("effectRefs") }.map { effects.getValue(it)["payload"].record().string("type") }.toSet())
    }
    @Test fun `dead render labels release branch state while a later callback retains its captured destination`() {
        val decorations = (0..11).joinToString("\n") { "val label$it = if (input > $it) \"a\" else \"b\"; androidx.compose.material3.Text(label$it)" }
        for (declaration in listOf("val action = { stack.add(destination); Unit }",
            "val handler = object { fun send() { stack.add(destination) } }; val action = handler::send",
            "fun send() { stack.add(destination) }; val action = ::send")) {
        val catalog = compile("""
            object Home
            object Detail
            object Other
            @Composable fun App(input: Int, choice: Boolean) {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> {
                        val destination = if (choice) Detail else Other
                        $declaration
                        $decorations
                        androidx.compose.material3.Button(onClick = action)
                    }
                    entry<Detail> {}
                    entry<Other> {}
                })
            }
        """.trimIndent(), mapOf("RenderText.kt" to "package androidx.compose.material3\n@androidx.compose.runtime.Composable fun Text(text: String) {}"))
        val result = TransitionAnalysis.create(catalog, limits = TransitionLimits(renderVisits = 5_000)).report
        val effects = result.records("effects").associateBy { it["id"] }
        assertEquals(setOf("fixture.Detail", "fixture.Other"), result.records("transitions").flatMap { it.strings("effectRefs") }.map { effects.getValue(it)["payload"].record().string("type") }.toSet(), Json.encode(result))
        assertFalse(result.records("diagnostics").any { it["code"] == "CONTROL_FLOW_VISIT_LIMIT" })
        }
    }
    @Test fun `render continuation sampling retains real guards and keeps distinct return outcomes`() {
        val cases = (0..11).joinToString("\n") { "$it -> \"shared\"" }
        val catalog = compile("""
            object Home
            object Detail
            object Other
            fun label(input: Int): String = when(input) { $cases
                else -> "different"
            }
            @Composable fun App(input: Int) {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> {
                        val text = label(input)
                        androidx.compose.material3.Button(onClick = { stack.add(if (text == "shared") Detail else Other) })
                    }
                    entry<Detail> {}
                    entry<Other> {}
                })
            }
        """.trimIndent())
        val full = TransitionAnalysis.create(catalog, limits = TransitionLimits(renderContinuationsPerOutcome = 32)).report
        val sampled = TransitionAnalysis.create(catalog, limits = TransitionLimits(renderContinuationsPerOutcome = 2)).report
        val originalGuards = full.records("transitions").map { Json.encode(it["guards"]) }.toSet()
        assertTrue(sampled.records("transitions").all { Json.encode(it["guards"]) in originalGuards })
        assertTrue(sampled.records("transitions").all { it.records("guards").isNotEmpty() })
        val effects = sampled.records("effects").associateBy { it["id"] }
        assertEquals(setOf("fixture.Detail", "fixture.Other"), sampled.records("transitions").flatMap { it.strings("effectRefs") }.map { effects.getValue(it)["payload"].record().string("type") }.toSet())
        assertTrue(sampled.records("transitions").size < full.records("transitions").size)
        assertEquals("TRUNCATED", sampled["coverage"])
        assertTrue(sampled.records("diagnostics").any { it["code"] == "RENDER_CONTINUATION_BUDGET_EXCEEDED" })
    }
    @Test fun `trigger quota retains complete witnesses and distinct captured destinations`() {
        val cases = (0..11).joinToString("\n") { "$it -> \"shared\"" }
        val catalog = compile("""
            object Home
            object Detail
            object Other
            fun label(input: Int): String = when(input) { $cases
                else -> "different"
            }
            @Composable fun App(input: Int) {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> {
                        val text = label(input)
                        androidx.compose.material3.Button(onClick = { stack.add(if (text == "shared") Detail else Other) })
                    }
                    entry<Detail> {}
                    entry<Other> {}
                })
            }
        """.trimIndent())
        val full = TransitionAnalysis.create(catalog, limits = TransitionLimits(renderContinuationsPerOutcome = 32, triggerVariantsPerAction = 32)).report
        val sampled = TransitionAnalysis.create(catalog, limits = TransitionLimits(renderContinuationsPerOutcome = 32, triggerVariantsPerAction = 2)).report
        val originalGuards = full.records("transitions").map { Json.encode(it["guards"]) }.toSet()
        assertTrue(sampled.records("transitions").all { Json.encode(it["guards"]) in originalGuards })
        assertTrue(sampled.records("transitions").all { it.records("guards").isNotEmpty() })
        val effects = sampled.records("effects").associateBy { it["id"] }
        assertEquals(setOf("fixture.Detail", "fixture.Other"), sampled.records("transitions").flatMap { it.strings("effectRefs") }.map { effects.getValue(it)["payload"].record().string("type") }.toSet())
        assertTrue(sampled.records("transitions").size < full.records("transitions").size)
        val originals = full.records("transitions").associateBy { it["id"] }
        assertTrue(sampled.records("transitions").all { it == originals[it["id"]] })
        assertEquals(2, sampled["limits"].record()["triggerVariantsPerAction"])
        assertThrows(IllegalArgumentException::class.java) { TransitionLimits(triggerVariantsPerAction = 0) }
        assertEquals("TRUNCATED", sampled["coverage"])
        assertTrue(sampled.records("diagnostics").any { it["code"] == "TRIGGER_VARIANT_BUDGET_EXCEEDED" })
    }
    @Test fun `repeated symbolic enum tests preserve compatible callbacks and reject conflicting branches`() {
        val catalog = compile("""
            object Home
            object Detail
            object Never
            enum class Mode { OPEN, CLOSED }
            @Composable fun Content(mode: Mode, click: () -> Unit, wrong: () -> Unit) {
                if (mode == Mode.OPEN) {
                    if (mode == Mode.CLOSED) androidx.compose.material3.Button(onClick = wrong)
                    else androidx.compose.material3.Button(onClick = click)
                }
            }
            @Composable fun App(mode: Mode) {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> { Content(mode, { stack.add(Detail) }, { stack.add(Never) }) }
                    entry<Detail> {}
                    entry<Never> {}
                })
            }
        """.trimIndent())
        val result = TransitionAnalysis.create(catalog).report
        val effects = result.records("effects").associateBy { it["id"] }
        assertEquals(setOf("fixture.Detail"), result.records("transitions").flatMap { it.strings("effectRefs") }.map { effects.getValue(it)["payload"].record().string("type") }.toSet(), Json.encode(result))
        assertTrue(result.records("transitions").all { it.records("guards").isNotEmpty() })
    }
    @Test fun `concrete enum branches cannot register a different mode callback`() {
        val catalog = compile("""
            object Home
            object Detail
            object Never
            enum class Mode { OPEN, CLOSED }
            enum class OtherMode { OPEN }
            @Composable fun Content(mode: Mode, click: () -> Unit, wrong: () -> Unit) {
                when (mode) {
                    Mode.OPEN -> androidx.compose.material3.Button(onClick = click)
                    Mode.CLOSED -> androidx.compose.material3.Button(onClick = wrong)
                }
                if ((mode as Any) == OtherMode.OPEN) androidx.compose.material3.Button(onClick = wrong)
            }
            @Composable fun App() {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> { Content(Mode.OPEN, { stack.add(Detail) }, { stack.add(Never) }) }
                    entry<Detail> {}
                    entry<Never> {}
                })
            }
        """.trimIndent())
        val result = TransitionAnalysis.create(catalog).report
        val edges = result.records("transitions")
        val effects = result.records("effects").associateBy { it["id"] }
        assertEquals(setOf("fixture.Detail"), edges.flatMap { it.strings("effectRefs") }.map { effects.getValue(it)["payload"].record().string("type") }.toSet(), Json.encode(result))
        assertTrue(edges.all { it.records("guards").isEmpty() })
    }
    @Test fun `scope adapters preserve returned destination receiver and nonlocal termination`() {
        val expressions = mapOf("Detail.let { Other }" to "fixture.Other", "Detail.run { Other }" to "fixture.Other",
            "with(Detail) { Other }" to "fixture.Other", "Detail.also { Other }" to "fixture.Detail", "Detail.apply { Other }" to "fixture.Detail",
            "Detail.let { return@Button }" to null, "Detail.run { throw IllegalStateException() }" to null)
        for ((expression, expected) in expressions) {
            val catalog = compile("""
                object Home
                object Detail
                object Other
                @Composable fun App() {
                    val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                    androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                        entry<Home> { androidx.compose.material3.Button(onClick = {
                            val destination = $expression
                            stack.add(destination)
                        }) }
                        entry<Detail> {}
                        entry<Other> {}
                    })
                }
            """.trimIndent())
            val result = TransitionAnalysis.create(catalog).report
            val effects = result.records("effects").associateBy { it["id"] }
            val destinations = result.records("transitions").flatMap { it.strings("effectRefs") }.map { effects.getValue(it)["payload"].record().string("type") }.toSet()
            assertEquals(if (expected == null) emptySet() else setOf(expected), destinations, expression)
        }
    }
    @Test fun `warm links require manifest matching parser acceptance and actual stack application`() {
        val catalog = compile("""
            object Home
            data class Detail(val token: String)
            @javax.inject.Singleton class Navigator @javax.inject.Inject constructor() {
                private val channel = kotlinx.coroutines.channels.Channel<Any>()
                val events = channel.receiveAsFlow()
                fun navigate(value: Any) { channel.trySend(value) }
            }
            @dagger.hilt.android.AndroidEntryPoint class Activity : android.app.Activity() {
                @javax.inject.Inject lateinit var navigator: Navigator
                override fun onCreate() { setContent { App() } }
                override fun onNewIntent(intent: android.content.Intent) {
                    val uri = intent.data ?: return
                    val segments = uri.pathSegments
                    if (segments.firstOrNull() != "invite") return
                    val token = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
                    val page = token.let { Detail(it) }.also { check(it.token.isNotBlank()) }
                    navigator.navigate(page)
                }
                @Composable fun App() {
                    val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                    androidx.compose.runtime.LaunchedEffect(Unit) { navigator.events.collect { stack.add(it) } }
                    androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                        entry<Home> {}
                        entry<Detail> {}
                    })
                }
            }
        """.trimIndent().let { "import kotlinx.coroutines.flow.*\nimport androidx.activity.compose.setContent\n" + it }, mapOf(
            "AndroidActivity.kt" to "package android.app\nopen class Activity { open fun onCreate() {}\nopen fun onNewIntent(intent: android.content.Intent) {} }",
            "AndroidIntent.kt" to "package android.content\nabstract class Intent { abstract val data: android.net.Uri? }",
            "AndroidUri.kt" to "package android.net\nabstract class Uri { abstract val pathSegments: List<String> }",
            "SetContent.kt" to "package androidx.activity.compose\nfun android.app.Activity.setContent(content: @androidx.compose.runtime.Composable () -> Unit) {}"
        ))
        fun entries(prefix: String, exported: Boolean = true) = EntryPoints.collect("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="fixture"><application>
              <activity android:name="fixture.Activity" android:exported="$exported" android:launchMode="singleTask">
                <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
                <intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/>
                  <data android:scheme="https" android:host="example.test" android:pathPrefix="$prefix"/>
                </intent-filter>
              </activity>
            </application></manifest>
        """.trimIndent().byteInputStream(), catalog)
        val input = entries("/invite")
        val result = TransitionReports.compact(TransitionAnalysis.create(catalog, input))
        val edges = result.report.records("transitions")
        assertTrue(edges.any { it.string("externalEntryRef").isNotEmpty() && it.strings("targetContextRefs").isNotEmpty() }, Json.encode(result.report))
        assertTrue(result.evidence.records("evidence").any { it["kind"] == "EXTERNAL_INTENT_DELIVERY" })
        assertTrue(result.evidence.records("evidence").any { it["kind"] == "CHANNEL_TRANSFER" })
        val paths = AccessPaths.find(result.report, input)
        val launcher = AccessPaths.find(result.report, input, record("includeExternalIntents" to false))
        assertEquals(2, paths.records("paths").map { it["targetContextRef"] }.toSet().size, Json.encode(paths))
        assertEquals(1, launcher.records("paths").map { it["targetContextRef"] }.toSet().size)
        assertTrue(paths.records("paths").any { it.strings("externalEntryRefs").isNotEmpty() })
        for (bad in listOf(entries("/wrong"), entries("/invite", false)))
            assertTrue(TransitionAnalysis.create(catalog, bad).report.records("transitions").isEmpty())
        val fakeLifecycle = catalog + record("functions" to catalog.records("functions").map { fn ->
            if (fn["simpleName"] == "onNewIntent") fn + record("overrideSymbols" to emptyList<String>()) else fn
        })
        assertTrue(TransitionAnalysis.create(fakeLifecycle, input + record("catalogFingerprint" to EntryPoints.unavailable(fakeLifecycle)["catalogFingerprint"])).report.records("transitions").isEmpty())
        val noDelivery = catalog + record("functionBodies" to catalog.records("functionBodies").map { body ->
            if (body.string("owner").contains("Navigator.navigate")) body + record("body" to record("op" to "BLOCK", "statements" to emptyList<Record>())) else body
        })
        val blockedEntries = input + record("catalogFingerprint" to EntryPoints.unavailable(noDelivery)["catalogFingerprint"])
        assertTrue(TransitionAnalysis.create(noDelivery, blockedEntries).report.records("transitions").isEmpty(), "Parser return without a delivered stack effect must not count")
    }
    @Test fun `conditional callback adapters preserve predicate truth and do not invent clicks`() {
        for (adapter in listOf("takeIf", "takeUnless")) for (flag in listOf("true", "false", "input")) {
            val catalog = compile("""
                object Home
                object Detail
                @Composable fun App(input: Boolean) {
                    val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                    androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                        entry<Home> {
                            val click = { stack.add(Detail); Unit }.$adapter { $flag }
                            click?.let { androidx.compose.material3.Button(onClick = it) }
                        }
                        entry<Detail> {}
                    })
                }
            """.trimIndent())
            val result = TransitionAnalysis.create(catalog).report
            val expected = flag == "input" || (flag == "true") == (adapter == "takeIf")
            assertEquals(expected, result.records("transitions").isNotEmpty(), Json.encode(result))
            if (flag == "input") assertEquals(setOf(adapter == "takeIf"), result.records("transitions").flatMap { it.records("guards") }.map { it["expected"] }.toSet())
        }
    }
    @Test fun `built menu callbacks retain item identity guards order and early returns`() {
        val catalog = compile("""
            object Home
            object Detail
            object Other
            object Never
            data class Menu(val click: () -> Unit)
            @Composable fun App(flag: Boolean) {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> {
                        val menu = buildList {
                            if (flag) { add(Menu { stack.add(Detail) }); return@buildList }
                            add(Menu { stack.add(Other) })
                            if (false) add(Menu { stack.add(Never) })
                        }
                        val unused = buildList { add(Menu { stack.add(Never) }) }
                        menu.forEach { item -> androidx.compose.material3.Button(onClick = item.click) }
                    }
                    entry<Detail> {}
                    entry<Other> {}
                    entry<Never> {}
                })
            }
        """.trimIndent())
        val report = TransitionAnalysis.create(catalog).report
        val effects = report.records("effects").associateBy { it.string("id") }
        val edges = report.records("transitions")
        assertEquals(setOf("fixture.Detail", "fixture.Other"), edges.flatMap { it.strings("effectRefs") }.map { effects.getValue(it)["payload"].record().string("type") }.toSet(), Json.encode(report))
        assertTrue(edges.all { it.strings("effectRefs").size == 1 })
        assertEquals(setOf(true, false), edges.flatMap { it.records("guards") }.map { it["expected"] }.toSet())
    }
    @Test fun `unsupported builder mutations cannot leave stale callbacks reachable`() {
        val catalog = compile("""
            object Home
            object Detail
            @Composable fun App() {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> {
                        val menu = buildList<() -> Unit> { add { stack.add(Detail) }; clear() }
                        menu.forEach { androidx.compose.material3.Button(onClick = it) }
                    }
                    entry<Detail> {}
                })
            }
        """.trimIndent())
        val report = TransitionAnalysis.create(catalog).report
        assertTrue(report.records("transitions").isEmpty())
        assertTrue(report.records("diagnostics").any { it["code"] == "LIST_BUILDER_OPERATION_UNSUPPORTED" })
    }
    @Test fun `explicit super calls bypass overrides while ordinary calls keep dynamic dispatch`() {
        val catalog = compile("""
            object Home
            object Detail
            object Other
            open class Parent { open fun destination(): Any = Detail }
            class Child : Parent() {
                override fun destination(): Any = Other
                fun parentDestination(): Any = super.destination()
            }
            fun dynamic(parent: Parent): Any = parent.destination()
            @Composable fun App() {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                val child = Child()
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> { androidx.compose.material3.Button(onClick = {
                        stack.add(child.parentDestination())
                        stack.add(dynamic(child))
                    }) }
                    entry<Detail> {}
                    entry<Other> {}
                })
            }
        """.trimIndent())
        assertEquals(1, catalog.records("callRelations").count { it["superQualified"] == true })
        val report = TransitionAnalysis.create(catalog).report
        val effects = report.records("effects").associateBy { it.string("id") }
        val sequence = report.records("transitions").single().strings("effectRefs").map { effects.getValue(it)["payload"].record()["type"] }
        assertEquals(listOf("fixture.Detail", "fixture.Other"), sequence)
        assertThrows(IllegalArgumentException::class.java) {
            TransitionAnalysis.create(catalog + record("factsCapabilities" to catalog.strings("factsCapabilities").filter { it != "SUPER_CALL_TARGETS_V1" }))
        }
    }
    @Test fun `repeated recursive inputs stop without inventing continuation but changing arguments can reach a base case`() {
        for (terminates in listOf(false, true)) {
            val catalog = compile("""
                object Home
                object Detail
                fun repeatAction(next: Boolean, action: () -> Unit) {
                    if (next) repeatAction(${if (terminates) "false" else "true"}, action) else action()
                }
                @Composable fun App() {
                    val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                    androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                        entry<Home> { androidx.compose.material3.Button(onClick = {
                            repeatAction(true) { stack.add(Detail) }
                            stack.add(Detail)
                        }) }
                        entry<Detail> {}
                    })
                }
            """.trimIndent())
            val report = TransitionAnalysis.create(catalog).report
            assertEquals(terminates, report.records("transitions").isNotEmpty(), Json.encode(report))
            assertEquals(!terminates, report.records("diagnostics").any { it["code"] == "RECURSIVE_CONTROL_FLOW" })
            assertFalse(report.records("diagnostics").any { it["code"] == "CONTROL_FLOW_DEPTH_LIMIT" })
        }
    }
    @Test fun `compiled Result callbacks preserve values receivers and exclusive failure branches`() {
        for (work in listOf("runCatching { Detail }", "Detail.runCatching { this }", "runCatching<Any> { throw IllegalStateException() }")) {
            val catalog = compile("""
                object Home
                object Detail
                @Composable fun App() {
                    val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                    androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                        entry<Home> { androidx.compose.material3.Button(onClick = {
                            $work.onSuccess { stack.add(it) }.onFailure { stack.clear() }
                        }) }
                        entry<Detail> {}
                    })
                }
            """.trimIndent())
            val report = TransitionAnalysis.create(catalog).report
            val effects = report.records("effects").associateBy { it.string("id") }
            val edges = report.records("transitions")
            val detail = report.records("contexts").single { it.records("keyPatterns").any { key -> key["type"] == "fixture.Detail" } }.string("id")
            assertEquals(!work.contains("throw"), edges.any { detail in it.strings("targetContextRefs") }, work)
            assertFalse(edges.any { edge -> edge.strings("effectRefs").map { effects[it]?.get("operation") }.toSet().containsAll(setOf("PUSH", "CLEAR")) }, work)
        }
    }
    @Test fun `anonymous receiver methods use their own captured callback`() {
        val catalog = compile("""
            object Home
            object Detail
            interface Relay { fun run() }
            fun relay(action: () -> Unit): Relay {
                val stored = action
                return object : Relay { override fun run() { stored() } }
            }
            @Composable fun App() {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                val active = relay { stack.add(Detail) }
                val unused = relay { stack.clear() }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> { androidx.compose.material3.Button(onClick = { active.run() }) }
                    entry<Detail> {}
                })
            }
        """.trimIndent())
        val report = TransitionAnalysis.create(catalog).report
        val effects = report.records("effects").associateBy { it.string("id") }
        assertTrue(report.records("transitions").any { it.strings("targetContextRefs").isNotEmpty() }, Json.encode(report))
        assertTrue(report.records("transitions").all { edge -> edge.strings("effectRefs").all { effects[it]?.get("operation") == "PUSH" } })
    }
    @Test fun `inherited generic methods and getters retain their declaring symbols and argument bindings`() {
        val catalog = compile("""
            object Home
            object Detail
            open class Parent<T>(val value: T) {
                fun relay(action: (T) -> Unit) { action(value) }
                open fun destination(): Any = Detail
            }
            class Child : Parent<Any>(Detail)
            @Composable fun App() {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                val child = Child()
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> { androidx.compose.material3.Button(onClick = { child.relay { stack.add(child.destination()) } }) }
                    entry<Detail> {}
                })
            }
        """.trimIndent())
        assertTrue(catalog.strings("factsCapabilities").contains("INHERITED_CALL_TARGETS_V1"))
        val functions = catalog.records("functions").associateBy { it.string("symbol") }
        val inherited = catalog.records("callRelations").filter { it.string("name") in setOf("fixture.Child.relay", "fixture.Child.destination") }
        assertEquals(2, inherited.size)
        inherited.forEach { call ->
            val declared = functions.getValue(call.string("target"))
            assertEquals("fixture.Parent", declared["classOwner"])
            assertTrue(call.records("arguments").all { arg -> declared.records("parameters").any { it["ref"] == arg["parameter"] } })
        }
        val report = TransitionAnalysis.create(catalog).report
        assertTrue(report.records("transitions").any { it.strings("targetContextRefs").isNotEmpty() }, Json.encode(report))
    }
    @Test fun `Elvis returns and throws inside arguments preserve expression control flow`() {
        for ((argument, throwing) in listOf("null" to false, "\"present\"" to false, "null" to true)) {
            val exit = if (throwing) "throw IllegalStateException()" else "return Detail"
            val catalog = compile("""
                object Home
                object Detail
                fun choose(input: String?): Any {
                    val value = input ?: $exit
                    return Home
                }
                @Composable fun App() {
                    val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                    androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                        entry<Home> { androidx.compose.material3.Button(onClick = { stack.add(choose($argument)) }) }
                        entry<Detail> {}
                    })
                }
            """.trimIndent())
            assertTrue(catalog.strings("factsCapabilities").contains("EXPRESSION_CONTROL_FLOW_V1"))
            val result = TransitionAnalysis.create(catalog).report
            val edges = result.records("transitions")
            if (throwing) assertTrue(edges.isEmpty(), Json.encode(result)) else {
                val target = result.records("contexts").single { it["id"] in edges.single().strings("targetContextRefs") }
                assertTrue(Json.encode(target["keyPatterns"]).contains(if (argument == "null") "fixture.Detail" else "fixture.Home"), Json.encode(result))
            }
        }
    }
    @Test fun `public property backing fields match external module references while private fields stay local`() {
        val library = directory.resolve("Library.kt").apply { writeText("package fixture\nval Shared = Any()\nprivate val Hidden = Any()\nfun hidden() = Hidden") }
        val consumer = directory.resolve("Consumer.kt").apply { writeText("package fixture\nfun use() = Shared") }
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()
        fun collect(input: Path, module: String, dependency: Path? = null): Record {
            val report = directory.resolve("$module.json")
            val args = mutableListOf(input.toString(), "-d", directory.resolve(module).toString(), "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                "-classpath", stdlib + (dependency?.let { java.io.File.pathSeparator + it } ?: ""), "-Xplugin=" + System.getProperty("apm.test.pluginJar"))
            mapOf("catalog" to "true", "catalogFile" to report.toString(), "moduleId" to module, "variant" to "debug", "projectRoot" to directory.toString()).forEach { (k,v) -> args += listOf("-P", "plugin:dev.androidapm.compiler:$k=$v") }
            val log = ByteArrayOutputStream()
            assertEquals(ExitCode.OK, PrintStream(log).use { K2JVMCompiler().exec(it, *args.toTypedArray()) }, log.toString(Charsets.UTF_8))
            return Json.decode(report.readText()).record()
        }
        val defining = collect(library, "library")
        val consuming = collect(consumer, "consumer", directory.resolve("library"))
        val shared = defining.records("values").single { it["ref"] == "fixture.Shared" }
        assertEquals("FIELD", shared["storage"])
        assertTrue(defining.records("values").any { it["storage"] == "FIELD" && it.string("ref").startsWith("library:") })
        val returned = consuming.records("values").single { it["returnOf"] == "fixture.use()" }["value"].record()
        assertEquals(shared["ref"], returned["ref"])
    }
    @Test fun `member accessors and their override references have stable qualified symbols`() {
        val catalog = compile("""
            interface Source { val current: String }
            class SourceImpl : Source { override val current: String = "value" }
            fun read(source: Source): String = source.current
        """.trimIndent())
        val declaration = catalog.records("functions").single { it["name"] == "fixture.Source.<get-current>" }
        val implementation = catalog.records("functions").single { it["name"] == "fixture.SourceImpl.<get-current>" }
        assertEquals("fixture.Source.<get-current>(fixture.Source)", declaration["symbol"])
        assertTrue(declaration["symbol"] in implementation.strings("overrideSymbols"))
        val call = catalog.records("callRelations").single { it["name"] == "fixture.Source.<get-current>" }
        assertEquals(declaration["symbol"], call["target"])
    }
    @TempDir lateinit var directory: Path
    private fun Record.strings(key: String): List<String> = (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()

    private fun compile(source: String, extraSources: Map<String, String> = emptyMap()): Record {
        val annotations = directory.resolve("Annotations.kt").apply { writeText("""
            package androidx.compose.runtime
            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
            annotation class Composable
            fun <T> remember(calculation: () -> T): T = calculation()
            fun <T> mutableStateListOf(vararg values: T): MutableList<T> = values.toMutableList()
            @Composable fun LaunchedEffect(key: Any?, block: suspend () -> Unit) {}
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
            @Composable fun <T> NavDisplay(backStack: List<Any>, onBack: () -> Unit = {}, entryProvider: (T) -> NavEntry<T>) {}
        """.trimIndent()) }
        val report = directory.resolve("catalog.json")
        val preview = directory.resolve("Preview.kt").apply { writeText("""
            package androidx.compose.ui.tooling.preview
            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
            annotation class Preview
        """.trimIndent()) }
        val channel = directory.resolve("Channel.kt").apply { writeText("""
            package kotlinx.coroutines.channels
            interface Channel<T> { fun trySend(element: T): Boolean }
            fun <T> Channel(capacity: Int = 0): Channel<T> = error("compiler fixture")
        """.trimIndent()) }
        val stream = directory.resolve("Flow.kt").apply { writeText("""
            package kotlinx.coroutines.flow
            class Flow<T>
            fun <T> kotlinx.coroutines.channels.Channel<T>.receiveAsFlow(): Flow<T> = Flow()
            suspend fun <T> Flow<T>.collect(action: suspend (T) -> Unit) {}
        """.trimIndent()) }
        val button = directory.resolve("Button.kt").apply { writeText("""
            package androidx.compose.material3
            @androidx.compose.runtime.Composable fun Button(onClick: () -> Unit, enabled: Boolean = true) {}
        """.trimIndent()) }
        val args = mutableListOf(annotations.toString(), nav.toString(), input.toString(), host.toString(), preview.toString(), channel.toString(), stream.toString(), button.toString(),
            "-d", directory.resolve("classes").toString(), "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-classpath", Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString(),
            "-Xplugin=" + System.getProperty("apm.test.pluginJar"))
        val diFixtures = mapOf(
            "Inject.kt" to """package javax.inject
                @Target(AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD) annotation class Inject
                @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION) annotation class Singleton
            """,
            "Dagger.kt" to """package dagger
                @Target(AnnotationTarget.FUNCTION) annotation class Binds
                @Target(AnnotationTarget.CLASS) annotation class Module
            """,
            "Install.kt" to """package dagger.hilt
                @Target(AnnotationTarget.CLASS) annotation class InstallIn(vararg val value: kotlin.reflect.KClass<*>)
            """,
            "Component.kt" to """package dagger.hilt.components
                class SingletonComponent
            """,
            "Hilt.kt" to """package dagger.hilt.android
                @Target(AnnotationTarget.CLASS) annotation class AndroidEntryPoint
            """,
            "HiltModel.kt" to """package dagger.hilt.android.lifecycle
                @Target(AnnotationTarget.CLASS) annotation class HiltViewModel
            """,
            "HiltCompose.kt" to """package androidx.hilt.navigation.compose
                @androidx.compose.runtime.Composable inline fun <reified T> hiltViewModel(): T = error("fixture")
            """,
        )
        args += diFixtures.map { (name, text) -> directory.resolve(name).apply { writeText(text.trimIndent()) }.toString() }
        args += extraSources.map { (name, text) -> directory.resolve(name).apply { writeText(text.trimIndent()) }.toString() }
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

    @Test fun `default arguments class annotations field writes type tests and branch order survive compiler IR`() {
        val catalog = compile("""
            annotation class Scope(val value: String)
            @Scope("fixture") class Route(val path: String, val login: Boolean = true)
            class Holder { var route: Route? = null
                fun update(value: Any, stop: Boolean) {
                    if (stop) return
                    if (value is Route) route = value
                }
            }
            fun create() = Route("home")
        """.trimIndent())
        val constructor = catalog.records("functions").single { it["classOwner"] == "fixture.Route" && it["constructor"] == true }
        val login = constructor.records("parameters").single { it["name"] == "login" }
        assertEquals(true, login["defaultValue"].record()["value"])
        assertEquals("Regular", login["kind"])
        val klass = catalog.records("classes").single { it["symbol"] == "fixture.Route" }
        assertEquals("fixture.Scope", klass.records("annotations").single()["name"])
        val update = catalog.records("functions").single { it["name"] == "fixture.Holder.update" }
        val body = catalog.records("functionBodies").single { it["owner"] == update["symbol"] }["body"].record()
        assertEquals("BRANCH", body.records("statements").first()["op"])
        val branch = body.records("statements")[1]
        assertEquals("TYPE_TEST", branch.records("branches").first()["test"].record()["kind"])
        assertTrue(catalog.records("writes").any { it["kind"] == "FIELD" || it["receiver"].record().isNotEmpty() })
        assertTrue((catalog["factsCapabilities"] as List<*>).contains("CONTROL_FLOW_IR_V1"))
    }

    @Test fun `finite route map needs selection and actual render invocation to define destinations`() {
        val catalog = compile("""
            class Key(val path: String)
            class Route(val path: String, val login: Boolean = true, val render: @Composable () -> Unit)
            @Composable fun Home() {}
            @Composable fun Detail() {}
            val routes = listOf(Route("home", render = { Detail() }), Route("home", render = { Home() }), Route("detail", render = { Detail() }))
            val byPath = routes.associateBy { it.path }
            @Composable fun App() {
                androidx.navigation3.ui.NavDisplay(entryProvider<Any> {
                    entry<Key> { key ->
                        val route = byPath[key.path]
                        if (route != null) route.render()
                    }
                })
            }
        """.trimIndent())
        val result = TransitionAnalysis.create(catalog).report
        val contexts = result.records("contexts")
        assertEquals(2, contexts.size, Json.encode(result))
        assertTrue(contexts.all { it["definitionStatus"] == "REGISTERED_LOOKUP_RENDER" }, Json.encode(result))
        assertEquals(setOf("home", "detail"), contexts.map { it.records("keyPatterns").single()["value"].record()["value"] }.toSet())
        val home = contexts.single { it.records("keyPatterns").single()["value"].record()["value"] == "home" }
        val renderer = home.strings("expectedRenderRefs").single()
        assertTrue(catalog.records("callRelations").any { it["owner"] == renderer && it["name"] == "fixture.Home" }, "associateBy must select the last duplicate key")
        val registered = contexts.map { it.string("storageCandidateRef") }.toSet()
        val unused = catalog.records("screenCandidates").single { it["id"] !in registered }
        assertEquals(listOf(unused["id"]), result.records("diagnostics").filter { it["code"] == "DESTINATION_BINDING_UNRESOLVED" }.map { it["subjectRef"] })
        val entry = catalog.records("entryBindings").single()["content"].record()["ref"]
        val unrendered = catalog + record("functionBodies" to catalog.records("functionBodies").map {
            if (it["owner"] == entry) it + record("body" to record("op" to "BLOCK", "statements" to emptyList<Record>())) else it
        })
        assertEquals(3, TransitionAnalysis.create(unrendered).report.records("diagnostics").count { it["code"] == "DESTINATION_BINDING_UNRESOLVED" })
    }

    @Test fun `real IR channel delivery requires the same captured channel and keeps delivery conditions`() {
        for (same in listOf(true, false)) {
            val catalog = compile("""
                object Home
                object Detail
                class Go(val page: Any)
                @Composable fun App() {
                    val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                    val sender = androidx.compose.runtime.remember { kotlinx.coroutines.channels.Channel<Go>() }
                    val other = androidx.compose.runtime.remember { kotlinx.coroutines.channels.Channel<Go>() }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        with(kotlinx.coroutines.flow) {}
                    }
                    androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                        entry<Home> { androidx.compose.material3.Button(onClick = { sender.trySend(Go(Detail)) }) }
                        entry<Detail> {}
                    })
                }
            """.trimIndent().replace("with(kotlinx.coroutines.flow) {}", "${if (same) "sender" else "other"}.receiveAsFlow().collect { signal -> stack.add(signal.page) }")
                .replace("object Home", "import kotlinx.coroutines.flow.receiveAsFlow\nimport kotlinx.coroutines.flow.collect\nobject Home"))
            val app = catalog.records("functions").single { it["name"] == "fixture.App" }.string("symbol")
            val input = catalog + record("uiRoots" to listOf(record("id" to "test-root", "arguments" to listOf(record("name" to "content", "value" to record("kind" to "FUNCTION", "ref" to app))))))
            val result = TransitionAnalysis.create(input).report
            if (same) {
                val edge = result.records("transitions").single { (it["targetContextRefs"] as List<*>).isNotEmpty() }
                assertEquals("MAY", edge["linkage"])
                assertTrue(edge.records("guards").any { it["test"].record()["operation"] == "CHANNEL_ACCEPTED_DELIVERED_AND_COLLECTOR_ACTIVE" }, Json.encode(result))
            } else assertTrue(result.records("transitions").none { (it["targetContextRefs"] as List<*>).isNotEmpty() }, Json.encode(result))
        }
    }

    @Test fun `Hilt singleton binding ties injected viewmodel sender to activity collector through exact overrides`() {
        val catalog = compile("""
            import kotlinx.coroutines.flow.receiveAsFlow
            import kotlinx.coroutines.flow.collect
            object Home
            object Detail
            class Go(val page: Any)
            interface Navigator { val events: kotlinx.coroutines.flow.Flow<Go>; fun go(page: Any) }
            class NavigatorImpl @javax.inject.Inject constructor() : Navigator {
                private val channel = kotlinx.coroutines.channels.Channel<Go>()
                override val events = channel.receiveAsFlow()
                override fun go(page: Any) { channel.trySend(Go(page)) }
            }
            @dagger.Module @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
            abstract class Bindings {
                @dagger.Binds @javax.inject.Singleton abstract fun bind(impl: NavigatorImpl): Navigator
            }
            @dagger.hilt.android.lifecycle.HiltViewModel
            class Model @javax.inject.Inject constructor(private val navigator: Navigator) {
                fun onIntent(intent: Go) { navigator.go(intent.page) }
            }
            @Composable fun Screen(model: Model = androidx.hilt.navigation.compose.hiltViewModel()) {
                val onIntent = model::onIntent
                androidx.compose.material3.Button(onClick = { onIntent(Go(Detail)) })
            }
            @dagger.hilt.android.AndroidEntryPoint class Activity {
                @javax.inject.Inject lateinit var navigator: Navigator
                @Composable fun App() {
                    val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                    androidx.compose.runtime.LaunchedEffect(Unit) { navigator.events.collect { signal -> stack.add(signal.page) } }
                    androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                        entry<Home> { Screen() }
                        entry<Detail> {}
                    })
                }
            }
        """.trimIndent())
        val app = catalog.records("functions").single { it["name"] == "fixture.Activity.App" }.string("symbol")
        val input = catalog + record("uiRoots" to listOf(record("id" to "test-root", "arguments" to listOf(record("name" to "content", "value" to record("kind" to "FUNCTION", "ref" to app))))))
        val result = TransitionAnalysis.create(input)
        assertTrue(result.report.records("transitions").any { (it["targetContextRefs"] as List<*>).isNotEmpty() }, Json.encode(result.report))
        assertTrue(result.evidence.records("evidence").any { it["kind"] == "CHANNEL_TRANSFER" })
        val unscoped = input + record("diBindings" to input.records("diBindings").map { it + record("annotations" to it.strings("annotations").filter { name -> name != "javax.inject.Singleton" }) })
        val rejected = TransitionAnalysis.create(unscoped).report
        assertTrue(rejected.records("transitions").none { it.strings("targetContextRefs").isNotEmpty() }, "Matching unscoped types must not prove channel identity")
        assertTrue(rejected.records("diagnostics").any { it["code"] == "DI_INSTANCE_SCOPE_UNPROVEN" })
    }

    @Test fun `choice argument keeps each destination correlated with its branch`() {
        val catalog = compile("""
            object Home
            object Detail
            object Other
            @Composable fun App(flag: Boolean) {
                val stack = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Any>(Home) }
                androidx.navigation3.ui.NavDisplay(backStack = stack, entryProvider = entryProvider<Any> {
                    entry<Home> { androidx.compose.material3.Button(onClick = { stack.add(if (flag) Detail else Other) }) }
                    entry<Detail> {}
                    entry<Other> {}
                })
            }
        """.trimIndent())
        val app = catalog.records("functions").single { it["name"] == "fixture.App" }.string("symbol")
        val input = catalog + record("uiRoots" to listOf(record("id" to "test-root", "arguments" to listOf(record("name" to "content", "value" to record("kind" to "FUNCTION", "ref" to app))))))
        val result = TransitionAnalysis.create(input).report
        val edges = result.records("transitions")
        assertEquals(2, edges.size, Json.encode(result))
        assertTrue(edges.all { it.strings("targetContextRefs").size == 1 && it["linkage"] == "MAY" }, Json.encode(result))
        assertEquals(2, edges.flatMap { it.strings("targetContextRefs") }.distinct().size)
        assertEquals(setOf(true, false), edges.flatMap { it.records("guards") }.map { it["expected"] }.toSet())
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
