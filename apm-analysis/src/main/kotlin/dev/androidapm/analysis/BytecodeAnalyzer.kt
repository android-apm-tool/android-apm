package dev.androidapm.analysis

import dev.androidapm.catalog.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm
import sootup.callgraph.scope.CallResolver
import sootup.callgraph.scope.VirtualCallResolver
import sootup.callgraph.scope.ExplorationVerdict.*
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.SootMethod
import sootup.core.model.SourceType
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation
import sootup.java.core.views.JavaView
import java.io.File

/** SootUp owns bytecode/CHA/CFG; Heros owns callback fixed points. This adapter owns source identity. */
object BytecodeAnalyzer {
    private data class SourceMethod(val owner: String, val name: String, val file: String?, val lines: Set<Int>)

    fun analyze(modules: List<Record>, classDirectories: List<File>, libraries: List<File>): Record {
        val diagnostics = mutableListOf<Record>()
        val bytecodeSources = mutableMapOf<Pair<String, String>, MutableList<SourceMethod>>()
        val classNames = sortedSetOf<String>()
        classDirectories.filter { it.isDirectory }.distinct().forEach { directory ->
            directory.walkTopDown().filter { it.extension == "class" }.sortedBy { it.relativeTo(directory).path }.forEach { file ->
                val node = ClassNode(Opcodes.ASM9)
                ClassReader(file.readBytes()).accept(node, ClassReader.SKIP_FRAMES)
                val owner = node.name.replace('/', '.')
                check(classNames.add(owner)) { "Duplicate application bytecode class: $owner" }
                node.methods.forEach { method ->
                    bytecodeSources.getOrPut(owner to method.name) { mutableListOf() }.add(SourceMethod(owner, method.name,
                        node.sourceFile, method.instructions.filterIsInstance<LineNumberNode>().map { it.line }.toSet()))
                }
            }
        }
        val inputs = classDirectories.filter { it.isDirectory }.distinct().map {
            PathBasedAnalysisInputLocation.create(it.toPath(), SourceType.Application)
        } + libraries.filter { it.exists() && (it.isDirectory || it.extension == "jar") }.distinct().map {
            PathBasedAnalysisInputLocation.create(it.toPath(), SourceType.Library)
        }
        val view = JavaView(inputs)
        val methods = classNames.flatMap { name ->
            view.getClass(view.identifierFactory.getClassType(name)).orElseThrow().methods
        }.filter { it.hasBody() }.sortedBy { it.signature.toString() }
        // Force conversion here: failure aborts the task rather than publishing a smaller graph as success.
        methods.forEach { it.body }
        val functions = modules.flatMap { it.records("functions") }
        val bySource = functions.groupBy { it.string("packageName") to it["source"].record().string("path").substringAfterLast('/') }
        fun bridge(method: SootMethod): Record? {
            val owner = method.declaringClassType.fullyQualifiedName
            val sources = bytecodeSources[owner to method.name].orEmpty()
            val sourceFile = sources.mapNotNull { it.file }.distinct().singleOrNull() ?: return null
            val pkg = owner.substringBeforeLast('.', "")
            val candidates = bySource[pkg to sourceFile].orEmpty()
            val name = method.name.substringBefore('-').substringBefore("\$default")
            val named = candidates.filter { it["kind"] != "LAMBDA" && it.string("simpleName") == name }
            val lines = method.body.stmts.map { it.positionInfo.stmtPosition.firstLine }.filter { it > 0 }.toSet()
            fun within(f: Record): Boolean {
                val source = f["source"].record()
                val first = (source["line"] as? Number)?.toInt() ?: return false
                val last = (source["endLine"] as? Number)?.toInt() ?: first
                return lines.any { it in first..last }
            }
            // Never choose an overload, lambda, or same-basename file arbitrarily.
            if (named.size == 1) return named.single().takeIf(::within)
            val matching = named.filter(::within)
            if (matching.size == 1) return matching.single()
            if (method.name.contains("\$lambda") || method.name == "invoke") {
                return candidates.filter { it["kind"] == "LAMBDA" && within(it) }.singleOrNull()
            }
            return null
        }
        val bridged = methods.mapNotNull { method -> bridge(method)?.let { method.signature to it } }.toMap()
        val allowed = methods.map { it.signature }.toSet()
        val graph = ClassHierarchyAnalysisAlgorithm(view,
            CallResolver { caller, statement ->
                val invoke = statement.invokeExpr.orElse(null)
                if (caller.signature !in allowed || invoke == null || invoke is JDynamicInvokeExpr) STOP
                // Framework/library bodies use explicit semantic summaries, never recursive expansion.
                else if (invoke.methodSignature.declClassType.fullyQualifiedName !in classNames) STOP
                else EXPLORE_METHOD
            },
            VirtualCallResolver { _, callee, _ -> if (callee in allowed) EXPLORE_METHOD else STOP },
        ).initialize(methods.map { it.signature })
        val irCalls = modules.flatMap { it.records("callRelations") }.groupBy { it.string("owner") }
        val targets = linkedMapOf<String, MutableSet<String>>()
        val evidence = linkedMapOf<String, MutableSet<String>>()
        val unmatchedSites = linkedMapOf<String, Record>()
        val unbridgedStatements = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stmt, Boolean>())
        fun add(owner: SootMethod, statement: Stmt, target: sootup.core.signatures.MethodSignature, kind: String) {
            val caller = bridged[owner.signature] ?: return
            val invokedName = statement.asInvokableStmt().invokeExpr.orElseThrow().methodSignature.name.substringBefore('-')
            val line = statement.positionInfo.stmtPosition.firstLine
            val sites = irCalls[caller.string("symbol")].orEmpty().filter {
                it.string("name").substringAfterLast('.') == invokedName && (it["source"].record()["line"] as? Number)?.toInt() == line
            }
            val call = sites.singleOrNull() ?: run { unbridgedStatements.add(statement); return }
            val callee = bridged[target] ?: run { unmatchedSites[call.string("id")] = call; return }
            if (callee.string("symbol") == call.string("target")) return
            targets.getOrPut(call.string("id")) { sortedSetOf() }.add(callee.string("symbol"))
            evidence.getOrPut(call.string("id")) { sortedSetOf() }.add(kind)
        }
        methods.forEach { method ->
            graph.callsFrom(method.signature).forEach { edge ->
                val invoke = edge.invokableStmt().invokeExpr.orElse(null)
                if (invoke is AbstractInstanceInvokeExpr && invoke !is JSpecialInvokeExpr)
                    add(method, edge.invokableStmt(), edge.targetMethodSignature(), "SOOTUP_CHA")
            }
        }
        val callbackHits = CallbackFlow.solve(view, methods, graph)
        callbackHits.forEach { add(it.owner(), it.statement(), it.target(), "HEROS_IFDS") }
        unmatchedSites.values.forEach { diagnostics += record("code" to "BYTECODE_SOURCE_BRIDGE_GAPS", "source" to it["source"]) }
        val indy = methods.sumOf { method -> method.body.stmts.count { stmt ->
            stmt.isInvokableStmt && stmt.asInvokableStmt().invokeExpr.orElse(null) is JDynamicInvokeExpr
        } }
        return record(
            "analysis" to record("engine" to "SOOTUP_HEROS", "sootUpVersion" to "3.0.1", "herosVersion" to "1.2.3",
                "contentModels" to ContentSemantics.version, "scope" to "APPLICATION_BYTECODE_WITH_LIBRARY_SUMMARIES",
                "applicationClasses" to classNames.size, "applicationMethods" to methods.size,
                "mappedMethods" to bridged.size, "classpathEntries" to libraries.count { it.exists() },
                "callGraphEdges" to graph.methodSignatures.sumOf { graph.callsFrom(it).size },
                "callbackInvocationFacts" to callbackHits.size, "linkedCallSites" to targets.size,
                "unmappedCandidateSites" to unmatchedSites.size, "unbridgedBytecodeStatements" to unbridgedStatements.size,
                "dynamicInvokeSites" to indy,
                "limitations" to listOf("MAY_TARGETS_NOT_RUNTIME_OBSERVATIONS", "HEAP_AND_CAPTURED_FIELDS_NOT_SOLVED",
                    "REFLECTION_AND_DYNAMIC_LOADING_NOT_SOLVED", "LIBRARY_BODIES_USE_SUMMARIES",
                    "EXTERNAL_DECLARED_DISPATCH_NOT_EXPANDED", "OPAQUE_RETURNS_NOT_SOLVED", "AMBIGUOUS_SOURCE_IDENTITIES_NOT_LINKED")),
            "analysisCallTargets" to targets.toSortedMap().map { (id, symbols) ->
                record("callId" to id, "targets" to symbols.toList(), "evidence" to evidence.getValue(id).toList(), "resolution" to "MAY")
            },
            "collectionDiagnostics" to diagnostics.sortedBy(Json::encode),
        )
    }
}

/** Separate JVM: SootUp/ASM/Guava never enter the Kotlin compiler or Gradle daemon classloader. */
object AnalysisMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val manifest = Json.decode(File(args[0]).readText(Charsets.UTF_8)).record()
        fun files(key: String) = (manifest[key] as List<*>).map { File(it as String) }
        val strings = HashMap<String, String>()
        val modules = files("catalogs").map { Json.decode(it.readText(Charsets.UTF_8), strings).record() }
        val result = BytecodeAnalyzer.analyze(modules, files("classes"), files("libraries"))
        File(args[1]).bufferedWriter(Charsets.UTF_8).use { Json.write(result, it, true) }
        println("Android APM SootUp/Heros: " + Json.encode(result["analysis"]))
    }
}
