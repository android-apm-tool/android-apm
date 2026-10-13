package dev.androidapm.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.io.File

@OptIn(ExperimentalCompilerApi::class)
class ApmCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId = "dev.androidapm.compiler"
    override val supportsK2 = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (configuration.get(catalogEnabled, false)) {
            fun required(key: org.jetbrains.kotlin.config.CompilerConfigurationKey<String>) =
                requireNotNull(configuration.get(key)) { "Missing APM catalog option: $key" }
            IrGenerationExtension.registerExtension(CatalogCollector(
                required(catalogModule), required(catalogVariant), File(required(catalogRoot)), File(required(catalogOutput)),
            ))
        }
        if (!configuration.get(probeEnabled, false)) return
        IrGenerationExtension.registerExtension(
            ApmIrProbe(
                configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE),
                configuration.get(probeReportFile)?.let(::File),
            ),
        )
    }
}
