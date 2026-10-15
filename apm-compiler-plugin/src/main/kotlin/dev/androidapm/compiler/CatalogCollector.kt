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
import org.jetbrains.kotlin.ir.visitors.acceptVoid
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
    private val classes = linkedMapOf<String, Record>()
    private val writes = linkedMapOf<String, Record>()
    private val bodies = linkedMapOf<String, Record>()
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
                override fun visitClass(declaration: IrClass) {
                    val name = symbol(declaration)
                    classes[name] = record("symbol" to name, "kind" to declaration.kind.name,
                        "superTypes" to declaration.superTypes.map { it.render() },
                        "annotations" to annotationFacts(declaration),
                        "fields" to declaration.declarations.filterIsInstance<IrProperty>().mapNotNull { it.backingField }.map {
                            record("ref" to symbol(it), "name" to it.name.asString(), "type" to it.type.render(), "mutable" to !it.isFinal, "annotations" to annotationFacts(it))
                        }, "source" to location(declaration))
                    super.visitClass(declaration)
                }
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
                        "enclosingFunction" to previous,
                        "returnType" to declaration.returnType.render(),
                        "classOwner" to (declaration.parent as? IrClass)?.let(::symbol),
                        "constructor" to (declaration is IrConstructor),
                        "annotationFacts" to annotationFacts(declaration),
                        "overrides" to ((declaration as? IrSimpleFunction)?.overriddenSymbols?.mapNotNull { it.owner.fqNameWhenAvailable?.asString() } ?: emptyList<String>()),
                        "overrideSymbols" to ((declaration as? IrSimpleFunction)?.overriddenSymbols?.map { symbol(it.owner) } ?: emptyList<String>()),
                        "annotations" to declaration.annotations.mapNotNull { it.symbol.owner.parentAsClass.fqNameWhenAvailable?.asString() },
                        "preview" to declaration.annotations.any { isPreview(it) },
                        "sourceSet" to relative(file.fileEntry.name).substringAfter("/src/", "unknown").substringBefore('/'),
                        "parameters" to declaration.parameters.mapIndexed { index, p -> record("ref" to symbol(p), "name" to p.name.asString(), "type" to p.type.render(), "composableType" to composableType(p.type),
                            "index" to index, "kind" to p.kind.name, "defaultValue" to p.defaultValue?.expression?.let { expression(it) }, "annotations" to annotationFacts(p)) },
                        "source" to location(declaration),
                    )
                    declaration.body?.let { body -> bodies[owner] = record("owner" to owner, "body" to flowStatement(body)) }
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
                    declaration.initializer?.let { values[symbol(declaration)] = record("ref" to symbol(declaration), "value" to expression(it), "mutable" to declaration.isVar,
                        "owner" to owner, "source" to location(declaration), "storage" to "LOCAL") }
                    super.visitVariable(declaration)
                }
                override fun visitField(declaration: IrField) {
                    declaration.initializer?.expression?.let {
                        values[symbol(declaration)] = record("ref" to symbol(declaration), "value" to expression(it), "mutable" to !declaration.isFinal,
                            "owner" to owner, "classOwner" to (declaration.parent as? IrClass)?.let(::symbol), "source" to location(declaration), "storage" to "FIELD")
                    }
                    super.visitField(declaration)
                }
                override fun visitReturn(expression: IrReturn) {
                    val target = expression.returnTargetSymbol.owner as? IrFunction
                    if (target != null) {
                        val key = symbol(target) + "#return@" + expression.startOffset
                        values[key] = record("ref" to key, "returnOf" to symbol(target), "value" to expression(expression.value),
                            "conditions" to conditions.toList(), "source" to location(expression))
                    }
                    super.visitReturn(expression)
                }
                override fun visitSetValue(expression: IrSetValue) {
                    val key = id("write-local", expression)
                    writes[key] = record("id" to key, "owner" to owner, "kind" to "LOCAL", "ref" to symbol(expression.symbol.owner),
                        "value" to expression(expression.value), "conditions" to conditions.toList(), "source" to location(expression))
                    super.visitSetValue(expression)
                }
                override fun visitSetField(expression: IrSetField) {
                    val key = id("write-field", expression)
                    writes[key] = record("id" to key, "owner" to owner, "kind" to "FIELD", "ref" to symbol(expression.symbol.owner),
                        "receiver" to expression.receiver?.let { expression(it) }, "value" to expression(expression.value),
                        "conditions" to conditions.toList(), "source" to location(expression))
                    super.visitSetField(expression)
                }
                override fun visitWhen(expression: IrWhen) {
                    val preceding = mutableListOf<Record>()
                    expression.branches.forEach { branch ->
                        branch.condition.acceptVoid(this)
                        val test = expression(branch.condition)
                        conditions += record("kind" to "BRANCH", "test" to test, "excluded" to preceding.toList())
                        branch.result.acceptVoid(this)
                        conditions.removeAt(conditions.lastIndex)
                        preceding += test
                    }
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
            "factsCapabilities" to listOf("PARAMETER_DEFAULTS_V1", "CLASS_METADATA_V1", "ASSIGNMENTS_V1", "EXPLICIT_ARGUMENT_POSITIONS_V1", "CONTROL_FLOW_IR_V1", "EXPRESSION_CONTROL_FLOW_V1", "INHERITED_CALL_TARGETS_V1"),
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
            "classes" to classes.values.sortedBy { it.string("symbol") },
            "writes" to writes.values.sortedBy { it.string("id") },
            "functionBodies" to bodies.values.sortedBy { it.string("owner") },
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
            "returnType" to target.returnType.render(), "constructor" to (target is IrConstructor),
            "classOwner" to (target.parent as? IrClass)?.let(::symbol),
            "parameterSchema" to target.parameters.mapIndexed { index, p -> record("ref" to symbol(p), "name" to p.name.asString(), "type" to p.type.render(), "index" to index, "kind" to p.kind.name, "hasDefault" to (p.defaultValue != null)) },
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

    private fun arguments(call: IrMemberAccessExpression<*>): List<Record> = (call.symbol.owner as IrFunction).parameters.mapIndexedNotNull { index, param ->
        val value = call.arguments.getOrNull(index) ?: return@mapIndexedNotNull null
        record("parameter" to symbol(param), "name" to param.name.asString(), "value" to expression(value),
            "index" to index, "parameterKind" to param.kind.name, "type" to param.type.render(), "origin" to "EXPLICIT",
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
            is IrFunctionReference -> record("kind" to "FUNCTION", "ref" to symbol(value.symbol.owner), "boundArguments" to arguments(value))
            is IrGetValue -> record("kind" to "REFERENCE", "ref" to symbol(value.symbol.owner))
            is IrGetObjectValue -> record("kind" to "OBJECT", "ref" to value.symbol.owner.fqNameWhenAvailable?.asString())
            is IrGetEnumValue -> record("kind" to "ENUM", "ref" to value.symbol.owner.fqNameWhenAvailable?.asString())
            is IrGetField -> record("kind" to "REFERENCE", "ref" to symbol(value.symbol.owner),
                "property" to value.symbol.owner.name.asString(), "receiver" to value.receiver?.let(::next))
            is IrTypeOperatorCall -> if (value.operator in setOf(IrTypeOperator.INSTANCEOF, IrTypeOperator.NOT_INSTANCEOF))
                record("kind" to "TYPE_TEST", "value" to next(value.argument), "testedType" to value.typeOperand.render(), "negated" to (value.operator == IrTypeOperator.NOT_INSTANCEOF))
                else next(value.argument)
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
            is IrWhen -> record("kind" to "CHOICE", "values" to value.branches.map { next(it.result) },
                "choiceGroupId" to id("choice", value), "alternatives" to value.branches.mapIndexed { index, branch -> record(
                    "value" to next(branch.result), "test" to next(branch.condition), "excluded" to value.branches.take(index).map { next(it.condition) }) })
            is IrStringConcatenation -> record("kind" to "STRING_CONCAT", "values" to value.arguments.map(::next))
            is IrReturn -> record("kind" to "RETURN_EXPRESSION", "target" to symbol(value.returnTargetSymbol.owner as IrFunction), "value" to next(value.value))
            is IrThrow -> record("kind" to "THROW_EXPRESSION", "value" to next(value.value))
            is IrContainerExpression -> {
                val result = (value.statements.lastOrNull() as? IrExpression)?.let(::next) ?: record("kind" to "UNKNOWN")
                if (value.statements.size <= 1) result else record("kind" to "BLOCK_EXPRESSION", "expressionId" to id("expression-block", value),
                    "statements" to value.statements.dropLast(1).map { flowStatement(it, depth + 1) }, "value" to result)
            }
            is IrVararg -> record("kind" to "LIST", "values" to value.elements.map { element -> when (element) {
                is IrSpreadElement -> record("kind" to "SPREAD", "value" to next(element.expression))
                is IrExpression -> next(element)
                else -> record("kind" to "UNKNOWN", "reason" to "VARARG_ELEMENT_UNMODELED")
            } })
            is IrClassReference -> record("kind" to "TYPE", "type" to value.classType.render())
            else -> record("kind" to "UNKNOWN", "expressionType" to value.javaClass.simpleName)
        }
    }

    private fun composableType(type: IrType): Boolean = type.annotations.any {
        it.symbol.owner.parentAsClass.fqNameWhenAvailable?.asString() == "androidx.compose.runtime.Composable"
    }

    private fun annotationFacts(declaration: IrAnnotationContainer): List<Record> = declaration.annotations.map { annotation ->
        record("name" to annotation.symbol.owner.parentAsClass.fqNameWhenAvailable?.asString(),
            "arguments" to annotation.symbol.owner.parameters.mapIndexedNotNull { index, p ->
                annotation.arguments.getOrNull(index)?.let { record("name" to p.name.asString(), "value" to expression(it)) }
            })
    }

    /** Ordered source operations, not a claim that exceptional/coroutine control flow is complete. */
    private fun flowStatement(element: IrElement, depth: Int = 0): Record {
        if (depth > 48) return record("op" to "UNKNOWN", "reason" to "IR_BODY_DEPTH_LIMIT", "source" to location(element))
        fun next(e: IrElement) = flowStatement(e, depth + 1)
        val base = record("source" to location(element))
        return base + when (element) {
            is IrBlockBody -> record("op" to "BLOCK", "statements" to element.statements.map(::next))
            is IrExpressionBody -> next(element.expression)
            is IrContainerExpression -> record("op" to "BLOCK", "statements" to element.statements.map(::next))
            is IrVariable -> record("op" to "DECLARE", "ref" to symbol(element), "mutable" to element.isVar, "value" to element.initializer?.let { expression(it) })
            is IrSetValue -> record("op" to "WRITE", "ref" to symbol(element.symbol.owner), "value" to expression(element.value))
            is IrSetField -> record("op" to "WRITE_FIELD", "ref" to symbol(element.symbol.owner), "receiver" to element.receiver?.let { expression(it) }, "value" to expression(element.value))
            is IrReturn -> record("op" to "RETURN", "target" to symbol(element.returnTargetSymbol.owner as IrFunction), "value" to expression(element.value))
            is IrWhen -> record("op" to "BRANCH", "branches" to element.branches.map { record("test" to expression(it.condition), "body" to next(it.result)) })
            is IrThrow -> record("op" to "THROW", "value" to expression(element.value))
            is IrLoop -> record("op" to "LOOP", "condition" to expression(element.condition), "body" to element.body?.let(::next))
            is IrTry -> record("op" to "TRY", "body" to next(element.tryResult), "catches" to element.catches.map { next(it.result) }, "finally" to element.finallyExpression?.let(::next))
            is IrFunctionAccessExpression -> record("op" to "CALL", "value" to expression(element))
            is IrExpression -> record("op" to "EVALUATE", "value" to expression(element))
            else -> record("op" to "DECLARATION", "elementType" to element.javaClass.simpleName)
        }
    }

    private fun isPreview(annotation: IrConstructorCall, seen: Set<String> = emptySet()): Boolean {
        val klass = annotation.symbol.owner.parentAsClass
        val name = klass.fqNameWhenAvailable?.asString() ?: return false
        if (name == "androidx.compose.ui.tooling.preview.Preview") return true
        if (name in seen || seen.size >= 16) return false
        return klass.annotations.any { isPreview(it, seen + name) }
    }

    private fun typeParameterSymbol(parameter: IrTypeParameter): String =
        "${(parameter.parent as? IrDeclaration)?.let(::symbol) ?: module}#type:${parameter.name}"

    private fun typeFact(type: IrType?): Record = record("kind" to "TYPE", "type" to type?.render(),
        "typeParameter" to ((type as? IrSimpleType)?.classifier?.owner as? IrTypeParameter)?.let(::typeParameterSymbol))

    /** Kotlin fake overrides have no body. Only a unique IR-declared ancestor
     * may supply the symbol; overloads and ambiguous diamonds stay unresolved. */
    private fun inheritedDeclaration(function: IrFunction, seen: Set<IrFunction> = emptySet()): IrFunction {
        if (function !is IrSimpleFunction || !function.isFakeOverride || function in seen) return function
        val targets = function.overriddenSymbols.map { inheritedDeclaration(it.owner, seen + function) }.distinct()
        return targets.singleOrNull() ?: function
    }
    private fun symbol(declaration: IrDeclaration): String { return when (declaration) {
        is IrValueParameter -> {
            val parent = declaration.parent as? IrFunction
            val target = parent?.let(::inheritedDeclaration)
            val parameter = if (target != null && target !== parent) target.parameters.getOrNull(parent.parameters.indexOf(declaration)) ?: declaration else declaration
            ((parameter.parent as? IrDeclaration)?.let(::symbol) ?: owner) + "#param:" + parameter.name.asString()
        }
        is IrClass -> declaration.fqNameWhenAvailable?.asString() ?: "$module:${relative(file.fileEntry.name)}@${declaration.startOffset}:class"
        is IrFunction -> {
            val inherited = inheritedDeclaration(declaration)
            if (inherited !== declaration) return symbol(inherited)
            val name = declaration.fqNameWhenAvailable?.asString()
            // Accessors have special names too, but public/member accessors are
            // declarations shared across modules, not source-local lambdas.
            val accessor = declaration is IrSimpleFunction && declaration.correspondingPropertySymbol != null
            val prefix = if (declaration.name.isSpecial && declaration !is IrConstructor && !accessor || name == null ||
                declaration.parent is IrFunction || declaration.parent is IrFile && declaration.visibility == DescriptorVisibilities.PRIVATE) {
                module + ":" + relative(declaration.fileOrNull?.fileEntry?.name ?: file.fileEntry.name) + "@" + declaration.startOffset + ":" + declaration.name
            } else name
            prefix + "(" + declaration.parameters.joinToString(",") { it.type.render() } + ")"
        }
        // A public top-level property's backing field is private in its defining
        // module, but external IR references use the property's qualified name.
        is IrField -> if (declaration.parent is IrFile &&
            (declaration.correspondingPropertySymbol?.owner?.visibility ?: declaration.visibility) == DescriptorVisibilities.PRIVATE)
            "$module:${relative(declaration.fileOrNull?.fileEntry?.name ?: file.fileEntry.name)}@${declaration.startOffset}:field"
            else declaration.fqNameWhenAvailable?.asString() ?: "$module:${relative(file.fileEntry.name)}@${declaration.startOffset}:field"
        is IrVariable -> "${(declaration.parent as? IrFunction)?.let(::symbol) ?: module}#local:${declaration.name}@${declaration.startOffset}"
        else -> "$module:${relative(file.fileEntry.name)}@${declaration.startOffset}"
    } }

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
