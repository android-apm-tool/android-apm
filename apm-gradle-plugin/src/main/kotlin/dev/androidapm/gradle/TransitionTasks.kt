package dev.androidapm.gradle

import dev.androidapm.catalog.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@CacheableTask
abstract class LinkTransitionsTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val factsFile: RegularFileProperty
    @get:OutputFile abstract val transitionsFile: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty
    @TaskAction fun link() {
        val result = TransitionAnalysis.create(read(factsFile), onEvents = { stage, index, total -> logger.lifecycle("Android APM transitions: $stage $index/$total") }) { index, total -> logger.lifecycle("Android APM transitions: context $index/$total") }
        publish(evidenceFile, result.evidence)
        publish(transitionsFile, result.report)
        logger.lifecycle("Android APM transitions: ${transitionsFile.get().asFile.absolutePath}")
    }
}

@CacheableTask
abstract class CollectEntryPointsTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifestFiles: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val factsFile: RegularFileProperty
    @get:OutputFile abstract val entryPointsFile: RegularFileProperty
    @TaskAction fun collect() {
        val files = manifestFiles.files.filter { it.isFile && it.name == "AndroidManifest.xml" }
        check(files.size <= 1) { "AMBIGUOUS_MERGED_MANIFEST" }
        val facts = read(factsFile)
        val result = files.singleOrNull()?.inputStream()?.use { EntryPoints.collect(it, facts) }
            ?: EntryPoints.unavailable(facts)
        publish(entryPointsFile, result)
    }
}

@CacheableTask
abstract class FindAccessPathsTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val transitionsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val entryPointsFile: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val queryFile: RegularFileProperty
    @get:OutputFile abstract val pathsFile: RegularFileProperty
    @TaskAction fun explore() {
        val query = if (queryFile.isPresent) read(queryFile) else emptyMap()
        publish(pathsFile, AccessPaths.find(read(transitionsFile), read(entryPointsFile), query))
        logger.lifecycle("Android APM access paths: ${pathsFile.get().asFile.absolutePath}")
    }
}

private fun read(property: RegularFileProperty) = Json.decode(property.get().asFile.readText(Charsets.UTF_8)).record()
private fun publish(property: RegularFileProperty, value: Record) {
    val output = property.get().asFile.toPath()
    Files.createDirectories(output.parent)
    val temporary = Files.createTempFile(output.parent, "apm-", ".json")
    try {
        Files.newBufferedWriter(temporary, Charsets.UTF_8).use { Json.write(value, it, pretty = true); it.append('\n') }
        try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temporary) }
}
