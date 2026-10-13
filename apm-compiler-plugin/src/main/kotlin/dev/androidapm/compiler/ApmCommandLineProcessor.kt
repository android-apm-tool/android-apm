package dev.androidapm.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal val probeEnabled = CompilerConfigurationKey<Boolean>("Android APM IR probe enabled")

@OptIn(ExperimentalCompilerApi::class)
class ApmCommandLineProcessor : CommandLineProcessor {
    override val pluginId = "dev.androidapm.compiler"
    override val pluginOptions = listOf(
        CliOption("enabled", "true|false", "Enable the diagnostic IR probe", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "enabled" -> configuration.put(probeEnabled, value.toBooleanStrictOrNull()
                ?: throw CliOptionProcessingException("Android APM enabled must be true or false"))
            else -> throw CliOptionProcessingException("Unknown Android APM option: ${option.optionName}")
        }
    }
}
