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
internal val catalogEnabled = CompilerConfigurationKey<Boolean>("Android APM full catalog")
internal val catalogOutput = CompilerConfigurationKey<String>("Android APM catalog staging file")
internal val catalogModule = CompilerConfigurationKey<String>("Android APM module identity")
internal val catalogVariant = CompilerConfigurationKey<String>("Android APM variant")
internal val catalogRoot = CompilerConfigurationKey<String>("Android APM project root")

@OptIn(ExperimentalCompilerApi::class)
class ApmCommandLineProcessor : CommandLineProcessor {
    override val pluginId = "dev.androidapm.compiler"
    override val pluginOptions = listOf(
        CliOption("enabled", "true|false", "Enable the diagnostic IR probe", required = false),
        CliOption("reportFile", "path", "Save the last IR invocation for Gradle to report", required = false),
        CliOption("catalog", "true|false", "Collect a full module catalog", required = false),
        CliOption("catalogFile", "path", "Catalog staging file", required = false),
        CliOption("moduleId", "id", "Module identity", required = false),
        CliOption("variant", "name", "Variant", required = false),
        CliOption("projectRoot", "path", "Base for relative source paths", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "catalog" -> configuration.put(catalogEnabled, value.toBooleanStrictOrNull()
                ?: throw CliOptionProcessingException("Android APM catalog must be true or false"))
            "catalogFile", "moduleId", "variant", "projectRoot" -> {
                if (value.isBlank()) throw CliOptionProcessingException("Android APM ${option.optionName} must not be blank")
                val key = when (option.optionName) {
                    "catalogFile" -> catalogOutput
                    "moduleId" -> catalogModule
                    "variant" -> catalogVariant
                    else -> catalogRoot
                }
                configuration.put(key, value)
            }
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
