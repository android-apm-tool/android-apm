@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package dev.androidapm.compiler

import dev.androidapm.catalog.*
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import java.io.File
import java.security.MessageDigest

/** Read-only extraction before Compose lowering. Never evaluates user code. */
internal class CatalogCollector(
    private val module: String, private val variant: String,
    private val root: File, private val output: File,
) : IrGenerationExtension {
    private val functions = linkedMapOf<String, Record>()
    private val values = linkedMapOf<String, Record>()
    private val calls = linkedMapOf<String, Record>()
    private val entries = linkedMapOf<String, Record>()
    private val providers = linkedMapOf<String, Record>()
    private val roots = linkedMapOf<String, Record>()
    private val hosts = linkedMapOf<String, Record>()
    private val containers = linkedMapOf<String, Record>()
    private val diagnostics = linkedMapOf<String, Record>()
    private val diBindings = linkedMapOf<String, Record>()
    private val files = sortedSetOf<String>()
    private lateinit var file: IrFile
    private var owner = ""
    private val conditions = mutableListOf<Record>()

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.files.sortedBy { it.fileEntry.name }.forEach { source ->
            file = source
            files += relative(source.fileEntry.name)
            owner = "file:" + relative(source.fileEntry.name)
            source.acceptChildrenVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
                override fun visitFunction(declaration: IrFunction) {
                    if (declaration is IrSimpleFunction && declaration.isFakeOverride) return
                    if (declaration.origin == IrDeclarationOrigin.GENERATED_DATA_CLASS_MEMBER) return
                    val previous = owner
                    owner = symbol(declaration)
                    functions[owner] = record(
                        "id" to id("function", declaration), "symbol" to owner,
                        "name" to declaration.fqNameWhenAvailable?.asString(),
                        "composable" to declaration.hasAnnotation(FqName("androidx.compose.runtime.Composable")),
                        "kind" to if (declaration.name.isSpecial) "LAMBDA" else "FUNCTION",
                        "simpleName" to declaration.name.asString(),
                        "packageName" to file.packageFqName.asString(),
                        "hasBody" to (declaration.body != null),
                        "overridable" to (declaration is IrSimpleFunction && declaration.modality != org.jetbrains.kotlin.descriptors.Modality.FINAL),
                        "parameters" to declaration.parameters.map { p -> record("ref" to symbol(p), "name" to p.name.asString(), "type" to p.type.render()) },
                        "source" to location(declaration),
                    )
                    val annotations = declaration.annotations.mapNotNull { it.symbol.owner.parentAsClass.fqNameWhenAvailable?.asString() }
                    if (annotations.any { it in setOf("dagger.Provides", "dagger.Binds", "dagger.multibindings.Multibinds") }) {
                        val navigation = declaration.returnType.render().let { "EntryProviderScope" in it || "NavEntry" in it }
                        diBindings[owner] = record("symbol" to owner, "annotations" to annotations,
                            "returnType" to declaration.returnType.render(), "parameters" to functions.getValue(owner)["parameters"],
                            "source" to location(declaration), "resolution" to if (navigation) "UNRESOLVED" else "NOT_NAVIGATION_SPECIFIC")
                        if (navigation) diagnostic("UNRESOLVED_DI_BINDING", declaration, "Contribution recorded; runtime injection context is not inferred")
                    }
                    super.visitFunction(declaration)
                    owner = previous
                }
                override fun visitVariable(declaration: IrVariable) {
                    declaration.initializer?.let { values[symbol(declaration)] = record("ref" to symbol(declaration), "value" to expression(it), "mutable" to declaration.isVar) }
                    super.visitVariable(declaration)
                }
                override fun visitField(declaration: IrField) {
                    declaration.initializer?.expression?.let {
                        values[symbol(declaration)] = record("ref" to symbol(declaration), "value" to expression(it), "mutable" to !declaration.isFinal)
                    }
                    super.visitField(declaration)
                }
                override fun visitReturn(expression: IrReturn) {
                    val target = expression.returnTargetSymbol.owner as? IrFunction
                    if (target != null) {
                        val key = symbol(target) + "#return@" + expression.startOffset
                        values[key] = record("ref" to key, "returnOf" to symbol(target), "value" to expression(expression.value))
                    }
                    super.visitReturn(expression)
                }
                override fun visitBranch(branch: IrBranch) {
                    conditions += expression(branch.condition)
                    super.visitBranch(branch)
                    conditions.removeAt(conditions.lastIndex)
                }
                override fun visitCall(expression: IrCall) {
                    collectCall(expression)
                    super.visitCall(expression)
                }
                override fun visitConstructorCall(expression: IrConstructorCall) {
                    collectCall(expression)
                    super.visitConstructorCall(expression)
                }
            })
        }
        val catalog = record(
            "schemaVersion" to 1, "moduleId" to module, "variant" to variant,
            "collectionStatus" to "COMPLETE_FOR_DECLARED_INPUTS",
            "scope" to record("files" to files, "mode" to "FULL_COMPILATION", "adapter" to "nav3-runtime-1.0.1-1.1.2-v1"),
            "functions" to functions.values.sortedBy { it.string("symbol") },
            "composableDeclarations" to functions.values.filter { it["composable"] == true }.sortedBy { it.string("symbol") },
            "values" to values.values.sortedBy { it.string("ref") },
            "callRelations" to calls.values.sortedBy { it.string("id") },
            "entryDeclarations" to entries.values.sortedBy { it.string("id") },
            "providers" to providers.values.sortedBy { it.string("id") },
            "uiRoots" to roots.values.sortedBy { it.string("id") },
            "navHosts" to hosts.values.sortedBy { it.string("id") },
            "contentContainers" to containers.values.sortedBy { it.string("id") },
            "diBindings" to diBindings.values.sortedBy { it.string("symbol") },
            "diagnostics" to diagnostics.values.sortedBy { it.string("id") },
            "collectionDiagnostics" to diagnostics.values.sortedBy { it.string("id") },
        )
        output.parentFile.mkdirs()
        output.bufferedWriter(Charsets.UTF_8).use { writer -> Json.write(CatalogLinker.link(listOf(catalog)), writer); writer.append('\n') }
    }

    private fun collectCall(call: IrFunctionAccessExpression) {
        val target = call.symbol.owner
        val fq = target.fqNameWhenAvailable?.asString().orEmpty()
        val args = arguments(call)
        val callId = id("call:$fq", call)
        val data = record("id" to callId, "owner" to owner, "target" to symbol(target), "name" to fq,
            "composable" to target.hasAnnotation(FqName("androidx.compose.runtime.Composable")),
            "typeBindings" to target.typeParameters.mapIndexedNotNull { index, parameter ->
                call.typeArguments.getOrNull(index)?.let { record("parameter" to typeParameterSymbol(parameter), "value" to typeFact(it)) }
            },
            "arguments" to args, "conditions" to conditions.toList(), "source" to location(call))
        calls[callId] = data
        val content = args.firstOrNull { it.string("name") == "content" }?.get("value")
        val isEntry = fq in setOf("androidx.navigation3.runtime.EntryProviderScope.entry", "androidx.navigation3.runtime.EntryProviderScope.addEntryProvider")
        val isNavEntry = target is IrConstructor && target.parentAsClass.fqNameWhenAvailable?.asString() == "androidx.navigation3.runtime.NavEntry"
        if ((isEntry || isNavEntry) && content != null) {
            val key = args.firstOrNull { it.string("name") in setOf("key", "clazz", "navEntry") }
            entries[callId] = data + record("api" to if (isNavEntry) "NAV_ENTRY" else "ENTRY_DSL",
                "key" to (key?.get("value") ?: typeFact(call.typeArguments.firstOrNull())),
                "content" to content, "typeArguments" to call.typeArguments.map { it?.render() })
        } else if (isEntry || isNavEntry) {
            diagnostic("UNSUPPORTED_NAV3_SIGNATURE", call, "No content parameter in $fq")
        }
        if (fq == "androidx.navigation3.runtime.entryProvider") providers[callId] = data
        if (fq == "androidx.navigation3.ui.NavDisplay") hosts[callId] = data
        if (fq in setOf("androidx.activity.compose.setContent", "androidx.compose.ui.platform.ComposeView.setContent")) roots[callId] = data
        if (target is IrConstructor && !isNavEntry) {
            val contentArgs = args.filter { it["composableType"] == true }
            if (contentArgs.isNotEmpty()) containers[callId] = data + record("contentArguments" to contentArgs)
        }
    }

    private fun arguments(call: IrFunctionAccessExpression): List<Record> = call.symbol.owner.parameters.mapIndexedNotNull { index, param ->
        val value = call.arguments.getOrNull(index) ?: return@mapIndexedNotNull null
        record("parameter" to symbol(param), "name" to param.name.asString(), "value" to expression(value),
            "composableType" to composableType(param.type))
    }

    private fun expression(value: IrExpression, depth: Int = 0): Record {
        if (depth > 24) { diagnostic("ANALYSIS_LIMIT_REACHED", value, "Expression depth exceeds 24"); return record("kind" to "UNKNOWN", "source" to location(value)) }
        fun next(e: IrExpression) = expression(e, depth + 1)
        val base = record("type" to value.type.render(), "source" to location(value))
        return base + when (value) {
            is IrConst -> record("kind" to "CONSTANT", "value" to when (val constant = value.value) {
                is Char -> constant.toString()
                is Float -> if (constant.isFinite()) constant else constant.toString()
                is Double -> if (constant.isFinite()) constant else constant.toString()
                else -> constant
            })
            is IrFunctionExpression -> record("kind" to "FUNCTION", "ref" to symbol(value.function))
            is IrFunctionReference -> record("kind" to "FUNCTION", "ref" to symbol(value.symbol.owner))
            is IrGetValue -> record("kind" to "REFERENCE", "ref" to symbol(value.symbol.owner))
            is IrGetObjectValue -> record("kind" to "OBJECT", "ref" to value.symbol.owner.fqNameWhenAvailable?.asString())
            is IrGetField -> record("kind" to "REFERENCE", "ref" to symbol(value.symbol.owner),
                "property" to value.symbol.owner.name.asString(), "receiver" to value.receiver?.let(::next))
            is IrTypeOperatorCall -> next(value.argument)
            is IrFunctionAccessExpression -> {
                val target = value.symbol.owner
                val property = (target as? IrSimpleFunction)?.correspondingPropertySymbol?.owner
                if (property?.backingField != null && target == property.getter) {
                    record("kind" to "REFERENCE", "ref" to symbol(property.backingField!!),
                        "property" to property.name.asString(), "receiver" to value.dispatchReceiver?.let(::next))
                } else record("kind" to if (target is IrConstructor) "CONSTRUCTOR" else "CALL",
                    "callId" to id("call:${target.fqNameWhenAvailable?.asString().orEmpty()}", value),
                    "target" to symbol(target), "name" to target.fqNameWhenAvailable?.asString())
            }
            is IrWhen -> record("kind" to "CHOICE", "values" to value.branches.map { next(it.result) })
            is IrContainerExpression -> (value.statements.lastOrNull() as? IrExpression)?.let(::next) ?: record("kind" to "UNKNOWN")
            is IrVararg -> record("kind" to "LIST", "values" to value.elements.mapNotNull { (it as? IrExpression)?.let(::next) })
            is IrClassReference -> record("kind" to "TYPE", "type" to value.classType.render())
            else -> record("kind" to "UNKNOWN", "expressionType" to value.javaClass.simpleName)
        }
    }

    private fun composableType(type: IrType): Boolean = type.annotations.any {
        it.symbol.owner.parentAsClass.fqNameWhenAvailable?.asString() == "androidx.compose.runtime.Composable"
    }

    private fun typeParameterSymbol(parameter: IrTypeParameter): String =
        "${(parameter.parent as? IrDeclaration)?.let(::symbol) ?: module}#type:${parameter.name}"

    private fun typeFact(type: IrType?): Record = record("kind" to "TYPE", "type" to type?.render(),
        "typeParameter" to ((type as? IrSimpleType)?.classifier?.owner as? IrTypeParameter)?.let(::typeParameterSymbol))

    private fun symbol(declaration: IrDeclaration): String = when (declaration) {
        is IrValueParameter -> ((declaration.parent as? IrDeclaration)?.let(::symbol) ?: owner) + "#param:" + declaration.name.asString()
        is IrClass -> declaration.fqNameWhenAvailable?.asString() ?: "$module:${relative(file.fileEntry.name)}@${declaration.startOffset}:class"
        is IrFunction -> {
            val name = declaration.fqNameWhenAvailable?.asString()
            val prefix = if (declaration.name.isSpecial && declaration !is IrConstructor || name == null ||
                declaration.parent is IrFunction || declaration.parent is IrFile && declaration.visibility == DescriptorVisibilities.PRIVATE) {
                module + ":" + relative(declaration.fileOrNull?.fileEntry?.name ?: file.fileEntry.name) + "@" + declaration.startOffset + ":" + declaration.name
            } else name
            prefix + "(" + declaration.parameters.joinToString(",") { it.type.render() } + ")"
        }
        is IrField -> if (declaration.parent is IrFile && declaration.visibility == DescriptorVisibilities.PRIVATE)
            "$module:${relative(declaration.fileOrNull?.fileEntry?.name ?: file.fileEntry.name)}@${declaration.startOffset}:field"
            else declaration.fqNameWhenAvailable?.asString() ?: "$module:${relative(file.fileEntry.name)}@${declaration.startOffset}:field"
        is IrVariable -> "${(declaration.parent as? IrFunction)?.let(::symbol) ?: module}#local:${declaration.name}@${declaration.startOffset}"
        else -> "$module:${relative(file.fileEntry.name)}@${declaration.startOffset}"
    }

    private fun relative(path: String): String = File(path).absoluteFile.toPath().normalize()
        .let { root.absoluteFile.toPath().normalize().relativize(it).toString().replace('\\', '/') }
    private fun location(element: IrElement): Record {
        val offset = element.startOffset
        return record("path" to relative(file.fileEntry.name), "offset" to offset,
            "line" to if (offset >= 0) file.fileEntry.getLineNumber(offset) + 1 else null,
            "endLine" to if (element.endOffset >= 0) file.fileEntry.getLineNumber(element.endOffset) + 1 else null,
            "column" to if (offset >= 0) file.fileEntry.getColumnNumber(offset) + 1 else null)
    }
    private fun id(kind: String, element: IrElement): String = MessageDigest.getInstance("SHA-256")
        .digest("$module|$variant|${relative(file.fileEntry.name)}|${element.startOffset}|${element.endOffset}|$kind".toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }
    private fun diagnostic(code: String, element: IrElement, detail: String) {
        val key = id(code, element)
        diagnostics[key] = record("id" to key, "code" to code, "source" to location(element), "detail" to detail)
    }
}
