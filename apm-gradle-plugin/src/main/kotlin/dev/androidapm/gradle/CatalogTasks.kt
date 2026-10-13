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
            "scope" to record("files" to emptyList<String>(), "mode" to "FULL_COMPILATION"),
        ))) else {
            check(input != null) { "APM collector did not produce a complete module catalog" }
            Json.decode(input.readText(Charsets.UTF_8)).record()
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
    @get:OutputFile abstract val catalogFile: RegularFileProperty

    @TaskAction fun merge() {
        val strings = HashMap<String, String>()
        val modules = catalogs.files.map { Json.decode(it.readText(Charsets.UTF_8), strings).record() }.sortedBy { it.string("moduleId") }
        check(modules.all { (it["schemaVersion"] as? Number)?.toInt() == 1 }) { "Unsupported APM catalog schema" }
        check(modules.map { it.string("variant") }.distinct().size <= 1) { "Cannot mix APM variants" }
        check(modules.map { it.string("moduleId") }.distinct().size == modules.size) { "Duplicate APM module IDs" }
        val result = CatalogLinker.link(modules).toMutableMap()
        result["missingModules"] = missingModules.get()
        if (missingModules.get().isNotEmpty()) result["resolutionStatus"] = "PARTIAL"
        writeCatalog(catalogFile.get().asFile, CatalogReport.create(result), pretty = true)
        logger.lifecycle("Android APM merged ${modules.size} module(s): ${catalogFile.get().asFile.absolutePath}")
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
        val merge = project.tasks.register("apmMergeDebugCatalog", MergeCatalogTask::class.java) {
            it.group = "android apm"
            it.description = "Links catalogs of this module and its debug project dependencies."
            it.catalogs.from(publish.flatMap { task -> task.linkingFile })
            it.missingModules.convention(emptyList())
            it.catalogFile.set(project.layout.buildDirectory.file("reports/android-apm/catalog/debug/project-catalog.json"))
        }
        project.gradle.projectsEvaluated {
            merge.configure { task ->
                val dependencies = project.configurations.findByName("debugCompileClasspath") ?: project.configurations.findByName("compileClasspath")
                val paths = dependencies?.incoming?.resolutionResult?.allComponents.orEmpty().mapNotNull { component ->
                    (component.id as? ProjectComponentIdentifier)?.takeIf { it.build.buildPath == ":" }?.projectPath
                }.filter { it != project.path }.distinct().sorted()
                val producers = paths.mapNotNull { path -> project.rootProject.findProject(path)?.tasks?.findByName("apmCollectDebugCatalog") as? PublishCatalogTask }
                task.catalogs.from(producers.map { it.linkingFile })
                task.dependsOn(producers)
                task.missingModules.set(paths.filter { path -> producers.none { it.project.path == path } })
            }
        }
    }
}
