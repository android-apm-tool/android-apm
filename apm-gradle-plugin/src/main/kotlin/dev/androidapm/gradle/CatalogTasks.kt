package dev.androidapm.gradle

import dev.androidapm.catalog.*
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Inputs include both bytecode and its classpath, so analysis cannot survive a changed dependency. */
@CacheableTask
abstract class AnalyzeCatalogTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogs: ConfigurableFileCollection
    @get:Classpath abstract val classes: ConfigurableFileCollection
    @get:Classpath abstract val libraries: ConfigurableFileCollection
    @get:Classpath abstract val analyzerClasspath: ConfigurableFileCollection
    @get:OutputFile abstract val analysisFile: RegularFileProperty
    @get:Input abstract val analyzerHeap: Property<String>

    @TaskAction fun analyze() {
        val manifest = temporaryDir.resolve("inputs.json")
        val result = temporaryDir.resolve("result.json")
        result.delete()
        writeCatalog(manifest, record("catalogs" to catalogs.files.map { it.absolutePath }.sorted(),
            "classes" to classes.files.filter { it.isDirectory }.map { it.absolutePath }.sorted(),
            // Classpath order is semantic: the consumer's selected dependencies take precedence.
            "libraries" to libraries.files.filter { it.exists() }.map { it.absolutePath }))
        exec.javaexec {
            it.classpath(analyzerClasspath)
            it.mainClass.set("dev.androidapm.analysis.AnalysisMain")
            it.maxHeapSize = analyzerHeap.get()
            it.systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
            it.args(manifest.absolutePath, result.absolutePath)
        }.assertNormalExitValue()
        check(result.isFile) { "APM bytecode analyzer did not produce output" }
        writeCatalog(analysisFile.get().asFile, Json.decode(result.readText(Charsets.UTF_8)).record(), pretty = true)
    }
}

/** Only a successful, complete collector compilation can publish the staging file. */
@CacheableTask
abstract class PublishCatalogTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stagingFiles: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:Input abstract val moduleId: Property<String>
    @get:Input abstract val variant: Property<String>
    @get:OutputFile abstract val linkingFile: RegularFileProperty
    @get:OutputFile abstract val catalogFile: RegularFileProperty

    @TaskAction fun publish() {
        val input = stagingFiles.files.singleOrNull { it.isFile }
        val catalog = if (sourceFiles.isEmpty) CatalogLinker.link(listOf(record(
            "schemaVersion" to 1, "moduleId" to moduleId.get(), "variant" to variant.get(),
            "collectionStatus" to "COMPLETE_FOR_DECLARED_INPUTS",
            "factsCapabilities" to listOf("PARAMETER_DEFAULTS_V1", "CLASS_METADATA_V1", "ASSIGNMENTS_V1", "EXPLICIT_ARGUMENT_POSITIONS_V1", "CONTROL_FLOW_IR_V1"),
            "scope" to record("files" to emptyList<String>(), "mode" to "FULL_COMPILATION"),
        ))) else {
            check(input != null) { "APM collector did not produce a complete module catalog" }
            Json.decode(input.readText(Charsets.UTF_8), HashMap()).record()
        }
        writeCatalog(linkingFile.get().asFile, catalog)
        writeCatalog(catalogFile.get().asFile, CatalogReport.create(catalog), pretty = true)
        logger.lifecycle("Android APM catalog: ${catalogFile.get().asFile.absolutePath}")
    }
}

@CacheableTask
abstract class MergeCatalogTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogs: ConfigurableFileCollection
    @get:Input abstract val missingModules: ListProperty<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val analysisFile: RegularFileProperty
    @get:OutputFile abstract val catalogFile: RegularFileProperty
    @get:OutputFile abstract val uiAnalysisFile: RegularFileProperty
    @get:OutputFile abstract val uiEvidenceFile: RegularFileProperty
    @get:OutputFile abstract val linkingFile: RegularFileProperty

    @TaskAction fun merge() {
        val strings = HashMap<String, String>()
        val modules = catalogs.files.map { Json.decode(it.readText(Charsets.UTF_8), strings).record() }.sortedBy { it.string("moduleId") }
        check(modules.all { (it["schemaVersion"] as? Number)?.toInt() == 1 }) { "Unsupported APM catalog schema" }
        check(modules.map { it.string("variant") }.distinct().size <= 1) { "Cannot mix APM variants" }
        check(modules.map { it.string("moduleId") }.distinct().size == modules.size) { "Duplicate APM module IDs" }
        val analysis = Json.decode(analysisFile.get().asFile.readText(Charsets.UTF_8)).record()
        val enriched = modules.mapIndexed { index, module -> if (index != 0) module else module + record(
            "analysisCallTargets" to analysis["analysisCallTargets"],
            "collectionDiagnostics" to (module.records("collectionDiagnostics") + analysis.records("collectionDiagnostics"))) }
        val result = CatalogLinker.link(enriched).toMutableMap()
        result["analysis"] = analysis["analysis"]
        result["bytecodeControlFlow"] = analysis["bytecodeControlFlow"]
        result["missingModules"] = missingModules.get()
        if (missingModules.get().isNotEmpty()) result["resolutionStatus"] = "PARTIAL"
        val ui = UiAnalysis.create(result)
        writeCatalog(linkingFile.get().asFile, result)
        writeCatalog(uiEvidenceFile.get().asFile, ui.evidence, pretty = true)
        writeCatalog(uiAnalysisFile.get().asFile, ui.report, pretty = true)
        writeCatalog(catalogFile.get().asFile, CatalogReport.create(result), pretty = true)
        logger.lifecycle("Android APM merged ${modules.size} module(s): ${catalogFile.get().asFile.absolutePath}")
    }
}

/** Design changes invalidate matching without recompiling the app or rerunning bytecode analysis. */
@CacheableTask
abstract class MatchDesignTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val uiAnalysisFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val designFile: RegularFileProperty
    @get:OutputFile abstract val matchesFile: RegularFileProperty
    @TaskAction fun match() {
        val ui = Json.decode(uiAnalysisFile.get().asFile.readText(Charsets.UTF_8)).record()
        val design = Json.decode(designFile.get().asFile.readText(Charsets.UTF_8)).record()
        writeCatalog(matchesFile.get().asFile, DesignMatcher.match(ui, design), pretty = true)
        logger.lifecycle("Android APM design matches: ${matchesFile.get().asFile.absolutePath}")
    }
}

private fun writeCatalog(output: java.io.File, catalog: Record, pretty: Boolean = false) {
        // Atomic replacement: consumers never read a half-written successful result.
        output.parentFile.mkdirs()
        val temporary = Files.createTempFile(output.parentFile.toPath(), "catalog-", ".json")
        try {
            Files.newBufferedWriter(temporary, Charsets.UTF_8).use { writer -> Json.write(catalog, writer, pretty); writer.append('\n') }
            try { Files.move(temporary, output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, output.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
}

internal fun registerCatalogTasks(project: Project, artifact: SubpluginArtifact) {
    project.afterEvaluate {
        val original = (project.tasks.findByName("compileDebugKotlin") ?: project.tasks.findByName("compileKotlin")) as? KotlinJvmCompile ?: return@afterEvaluate
        val factory = project.plugins.apply(KotlinApiPlugin::class.java)
        val compiler = project.configurations.create("apmCatalogCompiler") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
        }
        project.dependencies.add(compiler.name, "${artifact.groupId}:${artifact.artifactId}:${artifact.version}")
        val analyzer = project.configurations.create("apmCatalogAnalyzer") {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
        }
        project.dependencies.add(analyzer.name, "${artifact.groupId}:apm-analysis:${artifact.version}")
        val staging = project.layout.buildDirectory.dir("intermediates/android-apm/debug/catalog")
        val collector = factory.registerKotlinJvmCompileTask("apmCompileDebugCatalog", original.compilerOptions)
        collector.configure { task ->
            task.description = "Compiles all debug sources into an isolated APM catalog; preserves the app compile cache."
            // Sources and their producing tasks (including KSP) are inherited from the actual variant.
            task.source(original.sources)
            (original as? KotlinCompile)?.let { task.source(it.javaSources) }
            task.libraries.from(original.libraries)
            task.friendPaths.from(original.friendPaths)
            task.pluginClasspath.from(original.pluginClasspath, compiler)
            task.destinationDirectory.set(project.layout.buildDirectory.dir("intermediates/android-apm/debug/classes"))
            task.sourceSetName.set("apmDebugCatalog")
            task.multiPlatformEnabled.set(original.multiPlatformEnabled)
            task.useModuleDetection.set(original.useModuleDetection)
            task.jvmTargetValidationMode.set(original.jvmTargetValidationMode)
            task.outputs.dir(staging).withPropertyName("apmCatalogStaging")
            (task as KotlinCompile).incremental = false
            task.compilerOptions.freeCompilerArgs.set(original.compilerOptions.freeCompilerArgs.map { args ->
                args + "-Xcompiler-plugin-order=dev.androidapm.compiler>androidx.compose.compiler.plugins.kotlin"
            })
            task.pluginOptions.set(original.pluginOptions.map { configs ->
                configs.map { config ->
                    CompilerPluginConfig().apply {
                        config.allOptions().filterKeys { it != "dev.androidapm.compiler" }.forEach { (id, options) ->
                            options.forEach { addPluginArgument(id, it) }
                        }
                    }
                }
            })
            task.pluginOptions.add(CompilerPluginConfig().apply {
                fun option(name: String, value: String) = addPluginArgument("dev.androidapm.compiler", SubpluginOption(name, value))
                option("catalog", "true")
                option("moduleId", project.rootProject.name + project.path)
                option("variant", "debug")
                addPluginArgument("dev.androidapm.compiler", FilesSubpluginOption("catalogFile", listOf(staging.get().file("module.json").asFile)))
                addPluginArgument("dev.androidapm.compiler", FilesSubpluginOption("projectRoot", listOf(project.rootProject.projectDir)))
            })
        }
        val publish = project.tasks.register("apmCollectDebugCatalog", PublishCatalogTask::class.java) {
            it.group = "android apm"
            it.description = "Collects a complete debug module catalog as JSON."
            it.dependsOn(collector)
            it.stagingFiles.from(staging.map { dir -> dir.file("module.json") })
            it.sourceFiles.from(original.sources)
            it.moduleId.set(project.rootProject.name + project.path)
            it.variant.set("debug")
            it.linkingFile.set(project.layout.buildDirectory.file("intermediates/android-apm/debug/published/catalog.json"))
            it.catalogFile.set(project.layout.buildDirectory.file("reports/android-apm/catalog/debug/catalog.json"))
        }
        val analyze = project.tasks.register("apmAnalyzeDebugCatalog", AnalyzeCatalogTask::class.java) {
            it.group = "android apm"
            it.description = "Runs SootUp/Heros over isolated debug bytecode and library signatures."
            it.catalogs.from(publish.flatMap { task -> task.linkingFile })
            it.classes.from(collector.flatMap { task -> task.destinationDirectory })
            it.libraries.from(original.libraries)
            it.analyzerClasspath.from(analyzer)
            it.analyzerHeap.convention(project.providers.gradleProperty("apm.analysisHeap").orElse("3g"))
            it.analysisFile.set(project.layout.buildDirectory.file("intermediates/android-apm/debug/analysis/bytecode.json"))
        }
        val merge = project.tasks.register("apmMergeDebugCatalog", MergeCatalogTask::class.java) {
            it.group = "android apm"
            it.description = "Links catalogs of this module and its debug project dependencies."
            it.catalogs.from(publish.flatMap { task -> task.linkingFile })
            it.missingModules.convention(emptyList())
            it.analysisFile.set(analyze.flatMap { task -> task.analysisFile })
            it.catalogFile.set(project.layout.buildDirectory.file("reports/android-apm/catalog/debug/project-catalog.json"))
            it.uiAnalysisFile.set(project.layout.buildDirectory.file("reports/android-apm/catalog/debug/ui-analysis.json"))
            it.uiEvidenceFile.set(project.layout.buildDirectory.file("intermediates/android-apm/debug/analysis/ui-evidence.json"))
            it.linkingFile.set(project.layout.buildDirectory.file("intermediates/android-apm/debug/analysis/linked-facts.json"))
        }
        val linkTransitions = project.tasks.register("apmLinkDebugTransitions", LinkTransitionsTask::class.java) {
            it.group = "android apm"
            it.description = "Links triggers and navigation effects with explicit unresolved frontiers."
            it.factsFile.set(merge.flatMap { task -> task.linkingFile })
            it.transitionsFile.set(project.layout.buildDirectory.file("reports/android-apm/catalog/debug/transitions.json"))
            it.evidenceFile.set(project.layout.buildDirectory.file("intermediates/android-apm/debug/analysis/transition-evidence.json"))
        }
        val entries = project.tasks.register("apmCollectDebugEntryPoints", CollectEntryPointsTask::class.java) {
            it.group = "android apm"
            it.factsFile.set(merge.flatMap { task -> task.linkingFile })
            it.entryPointsFile.set(project.layout.buildDirectory.file("reports/android-apm/catalog/debug/entry-points.json"))
        }
        linkTransitions.configure { it.entryPointsFile.set(entries.flatMap { task -> task.entryPointsFile }) }
        project.tasks.register("apmFindDebugAccessPaths", FindAccessPathsTask::class.java) {
            it.group = "android apm"
            it.description = "Explores bounded access paths; optionally set -Papm.accessQuery=<file>."
            it.transitionsFile.set(linkTransitions.flatMap { task -> task.transitionsFile })
            it.entryPointsFile.set(entries.flatMap { task -> task.entryPointsFile })
            it.queryFile.set(project.layout.file(project.providers.gradleProperty("apm.accessQuery").map { path -> project.rootProject.file(path) }))
            it.pathsFile.set(project.layout.buildDirectory.file("reports/android-apm/catalog/debug/access-paths.json"))
            it.launcherPathsFile.set(project.layout.buildDirectory.file("reports/android-apm/catalog/debug/launcher-access-paths.json"))
        }
        project.tasks.register("apmMatchDebugDesign", MatchDesignTask::class.java) {
            it.group = "android apm"
            it.description = "Matches a versioned design specification; set -Papm.designSpec=<file>."
            it.uiAnalysisFile.set(merge.flatMap { task -> task.uiAnalysisFile })
            it.designFile.set(project.layout.file(project.providers.gradleProperty("apm.designSpec").map { path -> project.rootProject.file(path) }))
            it.matchesFile.set(project.layout.buildDirectory.file("reports/android-apm/catalog/debug/design-matches.json"))
        }
        project.gradle.projectsEvaluated {
            val manifest = project.tasks.findByName("processDebugMainManifest")
            entries.configure { task ->
                if (manifest != null) {
                    task.dependsOn(manifest)
                    task.manifestFiles.from(manifest.outputs.files.asFileTree.matching { it.include("**/AndroidManifest.xml") })
                }
            }
            fun dependencyPaths(): List<String> {
                val dependencies = project.configurations.findByName("debugCompileClasspath") ?: project.configurations.findByName("compileClasspath")
                return dependencies?.incoming?.resolutionResult?.allComponents.orEmpty().mapNotNull { component ->
                    (component.id as? ProjectComponentIdentifier)?.takeIf { it.build.buildPath == ":" }?.projectPath
                }.filter { it != project.path }.distinct().sorted()
            }
            fun producers(paths: List<String>) = paths.mapNotNull { path -> project.rootProject.findProject(path)?.tasks?.findByName("apmCollectDebugCatalog") as? PublishCatalogTask }
            merge.configure { task ->
                val paths = dependencyPaths()
                val producers = producers(paths)
                task.catalogs.from(producers.map { it.linkingFile })
                task.dependsOn(producers)
                task.missingModules.set(paths.filter { path -> producers.none { it.project.path == path } })
            }
            analyze.configure { analysis ->
                val producers = producers(dependencyPaths())
                analysis.catalogs.from(producers.map { it.linkingFile })
                analysis.dependsOn(producers)
                producers.forEach { producer ->
                    val compile = producer.project.tasks.named("apmCompileDebugCatalog", KotlinJvmCompile::class.java)
                    analysis.classes.from(compile.flatMap { it.destinationDirectory })
                    analysis.libraries.from(compile.map { it.libraries })
                }
            }
        }
    }
}
