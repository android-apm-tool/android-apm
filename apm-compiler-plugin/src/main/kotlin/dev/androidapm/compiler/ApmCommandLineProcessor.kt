package dev.androidapm.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal val probeEnabled = CompilerConfigurationKey<Boolean>("Android APM IR probe enabled")
internal val probeReportFile = CompilerConfigurationKey<String>("Android APM IR probe report file")

@OptIn(ExperimentalCompilerApi::class)
class ApmCommandLineProcessor : CommandLineProcessor {
    override val pluginId = "dev.androidapm.compiler"
    override val pluginOptions = listOf(
        CliOption("enabled", "true|false", "Enable the diagnostic IR probe", required = false),
        CliOption("reportFile", "path", "Save the last IR invocation for Gradle to report", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "enabled" -> configuration.put(probeEnabled, value.toBooleanStrictOrNull()
                ?: throw CliOptionProcessingException("Android APM enabled must be true or false"))
            "reportFile" -> {
                if (value.isBlank()) throw CliOptionProcessingException("Android APM reportFile must not be blank")
                configuration.put(probeReportFile, value)
            }
            else -> throw CliOptionProcessingException("Unknown Android APM option: " + option.optionName)
        }
    }
}
