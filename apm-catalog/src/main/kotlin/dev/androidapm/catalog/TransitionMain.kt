package dev.androidapm.catalog

import java.nio.file.Files
import java.nio.file.Path

/** Reanalyse a collected snapshot without executing its build again. Still run inside the OS runner. */
object TransitionMain {
    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "--modules") {
            require(args.size == 5) { "Expected --modules inputs.json bytecode.json merged-manifest.xml NEW-output-directory" }
            val manifest = Json.decode(Files.readString(Path.of(args[1]))).record()
            val strings = HashMap<String, String>()
            val modules = manifest.strings("catalogs").map { Json.decode(Files.readString(Path.of(it)), strings).record() }
            val bytecode = Json.decode(Files.readString(Path.of(args[2])), strings).record()
            val enriched = modules.mapIndexed { index, module -> if (index != 0) module else module + record("analysisCallTargets" to bytecode["analysisCallTargets"],
                "collectionDiagnostics" to (module.records("collectionDiagnostics") + bytecode.records("collectionDiagnostics"))) }
            val facts = CatalogLinker.link(enriched) + record("bytecodeControlFlow" to bytecode["bytecodeControlFlow"], "analysis" to bytecode["analysis"])
            generate(facts, args[3], args[4], emptyMap())
            return
        }
        require(args.size in 3..4) { "Expected linked-facts.json merged-manifest.xml NEW-output-directory [query.json]" }
        val facts = Json.decode(Files.readString(Path.of(args[0])), HashMap()).record()
        val query = if (args.size == 4) Json.decode(Files.readString(Path.of(args[3]))).record() else emptyMap()
        generate(facts, args[1], args[2], query)
    }
    private fun generate(facts: Record, manifest: String, directory: String, query: Record) {
        val result = TransitionReports.compact(TransitionAnalysis.create(facts, onEvents = { stage, index, total -> println("Android APM transitions: $stage $index/$total") }) { index, total -> println("Android APM transitions: context $index/$total") })
        val entries = Files.newInputStream(Path.of(manifest)).use { EntryPoints.collect(it, facts) }
        val paths = AccessPaths.find(result.report, entries, query)
        val output = Path.of(directory)
        require(!Files.exists(output)) { "OUTPUT_GENERATION_ALREADY_EXISTS" }
        Files.createDirectories(output)
        Files.newBufferedWriter(output.resolve("linked-facts.json")).use { Json.write(facts, it) }
        mapOf("transitions.json" to result.report, "transition-evidence.json" to result.evidence,
            "entry-points.json" to entries, "access-paths.json" to paths).forEach { (name, value) ->
            Files.newBufferedWriter(output.resolve(name)).use { Json.write(value, it, pretty = true); it.append('\n') }
        }
        println(Json.encode(record("status" to "COMPLETED", "summary" to result.report["summary"], "coverage" to paths["coverage"])))
    }
}
