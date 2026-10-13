package dev.androidapm.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName
import java.io.File

internal class ApmIrProbe(
    private val messages: MessageCollector,
    private val reportFile: File? = null,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val composables = sortedSetOf<String>()
        moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (declaration.hasAnnotation(FqName("androidx.compose.runtime.Composable"))) {
                    composables += declaration.fqNameWhenAvailable?.asString() ?: declaration.name.asString()
                }
                super.visitSimpleFunction(declaration)
            }
        })
        // An invocation snapshot, not a merged catalog: incremental compilations can see a subset of sources.
        val summary = "APM IR probe: module=" + moduleFragment.name +
            "; sourceFiles=" + moduleFragment.files.size + "; composables=" + composables.joinToString(",")
        if (reportFile == null) {
            messages.report(CompilerMessageSeverity.INFO, summary)
        } else {
            reportFile.absoluteFile.parentFile.mkdirs()
            reportFile.writeText(summary + "\n", Charsets.UTF_8)
        }
    }
}
