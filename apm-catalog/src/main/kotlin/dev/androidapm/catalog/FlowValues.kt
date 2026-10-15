package dev.androidapm.catalog

/** Bounded, explicit value provenance. No target code is loaded or executed. */
internal class FlowValues(val catalog: Record, private val evaluationLimit: Int = 300_000) {
    val functions = catalog.records("functions").associateBy { it.string("symbol") }
    private val customEqualityTypes = functions.values.filter { it["simpleName"] == "equals" }.map { it.string("classOwner") }.toSet()
    val calls = catalog.records("callRelations").associateBy { it.string("id") }
    val values = catalog.records("values").associateBy { it.string("ref") }
    val returns = catalog.records("values").filter { "returnOf" in it }.groupBy { it.string("returnOf") }
    val bodies = catalog.records("functionBodies").associate { it.string("owner") to it["body"].record() }
    val classes = catalog.records("classes").associateBy { it.string("symbol") }
    val closures = mutableMapOf<String, Map<String, Record>>()
    private val captures = mutableMapOf<String, Set<String>>()
    val diagnostics = linkedMapOf<String, Record>()
    val injection = InjectionModel(this)
    private var evaluations = 0
    private var context = ""
    private data class EvalKey(val expression: Record, val environment: Map<String, Record>, val depth: Int, val seen: Set<String>)
    private fun newMemo() = object : LinkedHashMap<EvalKey, Record>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EvalKey, Record>?) = size > 20_000
    }
    private var memo = newMemo()
    fun <T> withFreshEvaluationBudget(block: () -> T): T {
        val savedEvaluations = evaluations
        val savedMemo = memo
        evaluations = 0
        memo = newMemo()
        return try { block() } finally { evaluations = savedEvaluations; memo = savedMemo }
    }
    private val environmentDigests = object : LinkedHashMap<Map<String, Record>, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Map<String, Record>, String>?) = size > 20_000
    }
    private val closureKeys = object : LinkedHashMap<Pair<String, Map<String, Record>>, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Map<String, Record>>, String>?) = size > 20_000
    }
    private fun environmentDigest(env: Map<String, Record>): String =
        environmentDigests.getOrPut(env.toMap()) { fingerprint(env) }
    fun beginContext(ref: String) {
        context = ref
        evaluations = 0
        memo.clear()
        environmentDigests.clear()
        closureKeys.clear()
    }
    var truncated = false
        private set

    fun unknown(reason: String, origin: String = ""): Record {
        val id = fingerprint(record("code" to reason, "origin" to origin))
        diagnostics.putIfAbsent(id, record("id" to id, "code" to reason, "originRef" to origin))
        return record("kind" to "UNKNOWN", "reason" to reason, "originRef" to origin)
    }
    fun constant(value: Any?) = record("kind" to "CONSTANT", "value" to value)
    private fun standardEquality(actual: String, seen: Set<String> = emptySet()): Boolean {
        val type = actual.removeSuffix("?").substringBefore('<')
        if (type == "kotlin.Any" || type == "java.lang.Object") return true
        if (type in seen) return false
        val declaration = classes[type] ?: return false
        if (type in customEqualityTypes) return false
        return declaration.strings("superTypes").all { standardEquality(it, seen + type) }
    }
    private fun subtype(actual: String, tested: String, seen: Set<String> = emptySet()): Boolean? {
        val type = actual.removeSuffix("?").substringBefore('<')
        if (type == tested || tested == "kotlin.Any") return true
        if (type in seen) return null
        if (type in setOf("kotlin.Any", "java.lang.Object")) return false
        val declaration = classes[type] ?: return null
        val parents = declaration.strings("superTypes").map { subtype(it, tested, seen + type) }
        return when { true in parents -> true; null in parents -> null; else -> false }
    }
    fun closure(ref: String, env: Map<String, Record>): Record {
        val used = captures.getOrPut(ref) { capturedReferences(ref) }
        val captured = env.filterKeys { it in used || it in setOf("@context", "@allocationContext", "@receiver") || it.startsWith("@local:") }
        val id = closureKeys.getOrPut(ref to captured) { fingerprint(record("ref" to ref, "environment" to captured)) }
        closures.putIfAbsent(id, captured)
        return record("kind" to "FUNCTION", "ref" to ref, "closureId" to id)
    }
    private fun capturedReferences(ref: String): Set<String> {
        val found = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun scan(value: Any?, depth: Int) {
            if (depth > 60) return
            when (value) {
                is Map<*, *> -> {
                    val item = value.record()
                    if (item["kind"] == "REFERENCE") found += item.string("ref")
                    if (item["kind"] in setOf("CALL", "CONSTRUCTOR")) {
                        val call = item.string("callId")
                        if (visited.add("call:" + call)) scan(calls[call]?.get("arguments"), depth + 1)
                    }
                    if (item["kind"] == "FUNCTION") {
                        val child = item.string("ref")
                        if (visited.add("function:" + child)) scan(bodies[child], depth + 1)
                    }
                    item.values.forEach { scan(it, depth + 1) }
                }
                is List<*> -> value.forEach { scan(it, depth + 1) }
            }
        }
        scan(bodies[ref], 0)
        return found
    }
    fun bind(call: Record, env: Map<String, Record>, depth: Int = 0): Map<String, Record> {
        val bound = env.toMutableMap()
        bound["@allocationContext"] = record("ref" to (env["@allocationContext"]?.string("ref").orEmpty() + ":" + call.string("id")))
        call.records("arguments").forEach { bound[it.string("parameter")] = eval(it["value"].record(), env, depth + 1) }
        functions[call.string("target")]?.records("parameters").orEmpty().forEach { parameter ->
            if (call.records("arguments").none { it["parameter"] == parameter["ref"] }) {
                bound[parameter.string("ref")] = parameter["defaultValue"].record().takeIf { it.isNotEmpty() }
                    ?.let { eval(it, bound, depth + 1) } ?: unknown("UNRESOLVED_DEFAULT_OR_ARGUMENT", parameter.string("ref"))
            }
        }
        return bound
    }
    fun arguments(call: Record, env: Map<String, Record>): Map<String, Record> =
        call.records("arguments").associate { it.string("name") to eval(it["value"].record(), env) }

    fun eval(expr: Record, env: Map<String, Record> = emptyMap(), depth: Int = 0, seen: Set<String> = emptySet()): Record {
        if (expr["kind"] == "CONSTANT") return constant(expr["value"])
        if (expr["kind"] in setOf("ENUM", "SYMBOLIC", "INSTANCE", "STACK", "STREAM", "LIST_VALUE", "MAP_VALUE", "UNKNOWN", "PREDICATE", "OPAQUE", "COMPOSITION_LOCAL", "PROVIDED_LOCAL", "PAIR", "TYPE")) return expr
        // Parameter binding fills a mutable map incrementally. A cache key must
        // retain the environment at evaluation time, not a later mutation.
        val key = EvalKey(expr, env.toMap(), depth, seen)
        memo[key]?.let { return it }
        if (depth > 36 || ++evaluations > evaluationLimit) { truncated = true; return unknown("VALUE_BUDGET_EXCEEDED", context) }
        return evaluate(expr, env, depth, seen).also { memo[key] = it }
    }
    private fun evaluate(expr: Record, env: Map<String, Record>, depth: Int, seen: Set<String>): Record {
        fun next(e: Record) = eval(e, env, depth + 1, seen)
        return when (expr.string("kind")) {
            "INJECTED_VALUE" -> if (expr["qualified"] == true) unknown("DI_QUALIFIER_NOT_MODELED", expr.string("type")) else injection.instance(expr.string("type"), expr.string("scope"))
            "CONSTANT" -> constant(expr["value"])
            "ENUM", "SYMBOLIC", "INSTANCE", "STACK", "STREAM", "LIST_VALUE", "MAP_VALUE", "UNKNOWN", "PREDICATE", "OPAQUE", "COMPOSITION_LOCAL", "PROVIDED_LOCAL", "PAIR" -> expr
            "FUNCTION" -> if (expr.containsKey("closureId")) expr else closure(expr.string("ref"), env + expr.records("boundArguments").associate { it.string("parameter") to next(it["value"].record()) }) +
                record("boundParameterRefs" to expr.records("boundArguments").map { it["parameter"] })
            "OBJECT" -> record("kind" to "INSTANCE", "identity" to "object:" + expr.string("ref"), "type" to expr["ref"], "fields" to emptyMap<String, Record>())
            "REFERENCE" -> {
                val ref = expr.string("ref")
                env[ref] ?: env["@receiver"]?.takeIf { ref.endsWith("#param:<this>") } ?: if (ref in seen) unknown("CYCLIC_VALUE", ref) else {
                    val receiver = expr["receiver"].record().takeIf { it.isNotEmpty() }?.let(::next)
                    val field = receiver?.get("fields").record()[expr.string("property")]?.record()
                    if (field != null && field.isNotEmpty()) next(field)
                    else values[ref]?.let { value ->
                        if (value["mutable"] == true) unknown("MUTABLE_VALUE_REQUIRES_STATE", ref)
                        else eval(value["value"].record(), env + if (receiver != null) mapOf("@receiver" to receiver) else emptyMap(), depth + 1, seen + ref)
                    } ?: injectedField(ref, env) ?: record("kind" to "SYMBOLIC", "ref" to ref, "type" to expr["type"])
                }
            }
            "TYPE" -> expr
            "TYPE_TEST" -> {
                val value = next(expr["value"].record())
                if (value["kind"] == "INSTANCE") {
                    val type = value.string("type")
                    val tested = expr.string("testedType").removeSuffix("?").substringBefore('<')
                    val known = subtype(type, tested)
                    if (known != null) constant(known != (expr["negated"] == true)) else record("kind" to "PREDICATE", "operation" to "TYPE_TEST", "value" to value, "testedType" to tested, "negated" to expr["negated"])
                } else record("kind" to "PREDICATE", "operation" to "TYPE_TEST", "value" to value, "testedType" to expr["testedType"], "negated" to expr["negated"])
            }
            "BLOCK_EXPRESSION" -> {
                env["@expression:" + expr.string("expressionId")]?.let { return it }
                val bound = env.toMutableMap()
                for (stmt in expr.records("statements")) {
                    if (stmt["op"] !in setOf("DECLARE", "WRITE")) return unknown("EXPRESSION_CONTROL_REQUIRES_INTERPRETER", expr.string("expressionId"))
                    bound[stmt.string("ref")] = eval(stmt["value"].record(), bound, depth + 1, seen)
                }
                eval(expr["value"].record(), bound, depth + 1, seen)
            }
            "CHOICE" -> {
                env["@choice:" + expr.string("choiceGroupId")]?.let { return it }
                val alternatives = expr.records("alternatives")
                val selected = alternatives.firstOrNull { next(it["test"].record()) == constant(true) && it.records("excluded").all { e -> next(e) == constant(false) } }
                if (selected != null) next(selected["value"].record())
                else {
                    val choices = expr.records("values").map(::next).distinct()
                    if (choices.size == 1) choices.single() else record("kind" to "CHOICE_VALUE", "choiceGroupId" to expr["choiceGroupId"], "values" to choices)
                }
            }
            "STRING_CONCAT" -> {
                val items = expr.records("values").map(::next)
                if (items.all { it["kind"] == "CONSTANT" }) constant(items.joinToString("") { it["value"].toString() })
                else record("kind" to "SYMBOLIC", "operation" to "STRING_CONCAT", "values" to items)
            }
            "LIST" -> {
                val items = expr.records("values").flatMap { item ->
                    if (item["kind"] != "SPREAD") listOf(next(item)) else {
                        val spread = next(item["value"].record())
                        if (spread["kind"] == "LIST_VALUE") spread.records("items") else listOf(unknown("SPREAD_CONTENTS_UNRESOLVED"))
                    }
                }
                record("kind" to "LIST_VALUE", "items" to items)
            }
            "CALL", "CONSTRUCTOR" -> {
                env["@evaluated:" + expr.string("callId")]?.let { return it }
                val call = calls[expr.string("callId")] ?: return unknown("CALL_FACT_UNAVAILABLE", expr.string("callId"))
                val key = call.string("id") + ":" + environmentDigest(env)
                if (key in seen) return unknown("RECURSIVE_VALUE", call.string("id"))
                val args = call.records("arguments").associate { it.string("name") to eval(it["value"].record(), env, depth + 1, seen + key) }
                val name = call.string("name")
                when {
                    name in setOf("androidx.compose.runtime.compositionLocalOf", "androidx.compose.runtime.staticCompositionLocalOf") -> record("kind" to "COMPOSITION_LOCAL", "identity" to call["id"])
                    name in setOf("androidx.compose.runtime.ProvidableCompositionLocal.provides", "androidx.compose.runtime.ProvidableCompositionLocal.providesDefault") ->
                        record("kind" to "PROVIDED_LOCAL", "local" to args.values.firstOrNull(), "value" to args.values.lastOrNull())
                    name in setOf("androidx.compose.runtime.CompositionLocal.<get-current>", "androidx.compose.runtime.ProvidableCompositionLocal.<get-current>") -> {
                        val local = args.values.firstOrNull()
                        env["@local:" + local?.get("identity")] ?: unknown("COMPOSITION_LOCAL_PROVIDER_UNRESOLVED", call.string("id"))
                    }
                    name in setOf("androidx.hilt.navigation.compose.hiltViewModel", "androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel") ->
                        if (args.containsKey("viewModelStoreOwner") || args.containsKey("key")) unknown("CUSTOM_VIEWMODEL_OWNER_OR_KEY_NOT_MODELED", call.string("id"))
                        else injection.instance(expr.string("type"), env["@context"]?.string("ref") ?: call.string("id"), viewModel = true)
                    call["constructor"] == true || expr["kind"] == "CONSTRUCTOR" -> {
                        val bound = bind(call, env, depth + 1)
                        val parameters = functions[call.string("target")]?.records("parameters").orEmpty()
                        val fields = if (parameters.isEmpty()) args else parameters.filter { it["kind"] == "Regular" || it["kind"] == "REGULAR" }.associate { it.string("name") to bound.getValue(it.string("ref")) }
                        record("kind" to "INSTANCE", "identity" to "allocation:" + key, "allocationRef" to call["id"],
                            "type" to (call["classOwner"] ?: expr["type"]), "fields" to fields, "multiplicity" to "MAY_REPEAT")
                    }
                    name.startsWith("androidx.compose.runtime.") && name.substringAfterLast('.') in setOf("remember", "rememberSaveable") -> {
                        val fn = args.values.lastOrNull { it["kind"] == "FUNCTION" }
                        if (fn == null) unknown("REMEMBER_CALCULATION_UNRESOLVED", call.string("id")) else returned(fn, emptyList(), depth + 1)
                    }
                    name == "androidx.compose.runtime.mutableStateListOf" || name == "androidx.navigation3.runtime.rememberNavBackStack" ->
                        record("kind" to "STACK", "identity" to "stack:" + call.string("id") + ":" + env["@allocationContext"]?.string("ref").orEmpty(), "initial" to args.values.flatMap { if (it["kind"] == "LIST_VALUE") it.records("items") else listOf(it) })
                    name in setOf("kotlin.collections.listOf", "kotlin.collections.mutableListOf", "kotlin.collections.arrayListOf", "kotlin.collections.setOf", "kotlin.arrayOf") ->
                        record("kind" to "LIST_VALUE", "identity" to "list:" + key, "items" to args.values.flatMap { if (it["kind"] == "LIST_VALUE") it.records("items") else listOf(it) })
                    name == "kotlin.collections.emptyList" -> record("kind" to "LIST_VALUE", "items" to emptyList<Record>())
                    name == "kotlin.collections.toTypedArray" -> args.values.firstOrNull()?.takeIf { it["kind"] == "LIST_VALUE" } ?: unknown("ARRAY_CONTENTS_UNRESOLVED", call.string("id"))
                    name == "kotlin.to" -> record("kind" to "PAIR", "first" to args.values.firstOrNull(), "second" to args.values.lastOrNull())
                    name in setOf("kotlin.collections.mapOf", "kotlin.collections.mutableMapOf", "kotlin.collections.emptyMap") -> {
                        val pairs = args.values.flatMap { if (it["kind"] == "LIST_VALUE") it.records("items") else listOf(it) }
                        if (pairs.all { it["kind"] == "PAIR" }) record("kind" to "MAP_VALUE", "entries" to pairs.map { record("key" to it["first"], "value" to it["second"]) })
                        else unknown("MAP_ENTRIES_UNRESOLVED", call.string("id"))
                    }
                    name == "kotlin.collections.associateBy" -> {
                        val list = args.values.firstOrNull()
                        val fn = args["keySelector"]
                        if (list?.get("kind") == "LIST_VALUE" && fn?.get("kind") == "FUNCTION") {
                            val entries = list.records("items").map { item -> record("key" to returned(fn, listOf(item), depth + 1), "value" to item) }
                            if (entries.all { it["key"].record()["kind"] == "CONSTANT" }) record("kind" to "MAP_VALUE", "entries" to entries.associateBy { it["key"] }.values.toList())
                            else unknown("MAP_KEYS_UNRESOLVED", call.string("id"))
                        } else unknown("MAP_SOURCE_UNRESOLVED", call.string("id"))
                    }
                    name in setOf("kotlin.collections.Map.get", "kotlin.collections.MutableMap.get") -> {
                        val map = args.values.firstOrNull()
                        val index = args.values.lastOrNull()
                        if (map?.get("kind") == "MAP_VALUE" && index?.get("kind") == "CONSTANT") map.records("entries").lastOrNull { it["key"] == index }?.get("value").record().ifEmpty { constant(null) }
                        else unknown("MAP_LOOKUP_UNRESOLVED", call.string("id"))
                    }
                    name == "kotlinx.coroutines.channels.Channel" -> record("kind" to "STREAM", "identity" to "channel:" + call.string("id") + ":" + (env["@receiver"]?.get("identity") ?: env["@allocationContext"]?.get("ref") ?: env["@context"]?.get("ref") ?: "OWNER_UNRESOLVED"),
                        "streamKind" to "CHANNEL", "ownerProven" to (env["@receiver"]?.get("identity") != null || env.containsKey("@context")), "allocationRef" to call["id"])
                    name == "kotlinx.coroutines.flow.receiveAsFlow" -> args.values.firstOrNull()?.takeIf { it["kind"] == "STREAM" }
                        ?.plus(record("adapter" to "receiveAsFlow")) ?: unknown("STREAM_IDENTITY_UNRESOLVED", call.string("id"))
                    name in setOf("kotlin.internal.ir.EQEQ", "kotlin.internal.ir.EQEQEQ", "kotlin.Boolean.not") -> {
                        val list = args.values.toList()
                        if (list.all { it["kind"] == "CONSTANT" }) constant(if (name.endsWith(".not")) list.first()["value"] != true else list.getOrNull(0)?.get("value") == list.getOrNull(1)?.get("value"))
                        else if (!name.endsWith(".not") && list.size == 2 && list.any { it == constant(null) } && list.any { it["kind"] in setOf("INSTANCE", "FUNCTION", "STACK", "STREAM", "MAP_VALUE", "LIST_VALUE") }) constant(false)
                        else if (!name.endsWith(".not") && list.size == 2 && list.all { it["kind"] == "INSTANCE" && it.string("identity").startsWith("object:") } &&
                            (name.endsWith(".EQEQEQ") || standardEquality(list[0].string("type")))) constant(list[0]["identity"] == list[1]["identity"])
                        // Source hierarchies without custom equals use identity or
                        // Kotlin-generated equality, both rejecting another concrete type.
                        else if (!name.endsWith(".not") && list.size == 2 && list.all { it["kind"] == "INSTANCE" } && list[0]["type"] != list[1]["type"] &&
                            (name.endsWith(".EQEQEQ") || standardEquality(list[0].string("type")))) constant(false)
                        else record("kind" to "PREDICATE", "operation" to name, "arguments" to list)
                    }
                    name.endsWith(".invoke") -> args.values.firstOrNull { it["kind"] == "FUNCTION" }?.let { returned(it, args.values.drop(1), depth + 1) }
                        ?: unknown("CALLBACK_UNRESOLVED", call.string("id"))
                    call.string("target") in functions -> {
                        val declared = functions.getValue(call.string("target"))
                        val receiver = args.values.firstOrNull()
                        val implementations = functions.values.filter { it["classOwner"] == receiver?.get("type") && (it["symbol"] == declared["symbol"] || declared["symbol"] in it.strings("overrideSymbols")) && it.string("symbol") in bodies }
                        val fn = if (declared["overridable"] == true && receiver?.get("kind") == "INSTANCE") implementations.singleOrNull() ?: declared else declared
                        if (fn["overridable"] == true && (receiver?.get("kind") != "INSTANCE" || fn["classOwner"] != receiver["type"])) unknown("VIRTUAL_RETURN_UNRESOLVED", call.string("id"))
                        else {
                            val candidates = returns[fn.string("symbol")].orEmpty()
                            val bound = bind(call, env, depth + 1) + fn.records("parameters").mapIndexedNotNull { index, p ->
                                call.records("arguments").firstOrNull { (it["index"] as? Number)?.toInt() == index }?.let { p.string("ref") to eval(it["value"].record(), env, depth + 1, seen + key) }
                            }.toMap()
                            val resolved = candidates.map { eval(it["value"].record(), bound, depth + 1, seen + key) }.distinct()
                            if (resolved.size == 1 && candidates.all { it.records("conditions").isEmpty() }) resolved.single() else unknown("RETURN_ALTERNATIVES_UNRESOLVED", call.string("id"))
                        }
                    }
                    else -> record("kind" to "OPAQUE", "callId" to call["id"], "name" to name, "type" to expr["type"])
                }
            }
            else -> unknown(expr.string("expressionType").ifEmpty { "EXPRESSION_UNMODELED" })
        }
    }

    fun returned(fn: Record, arguments: List<Record>, depth: Int = 0): Record {
        val function = functions[fn.string("ref")] ?: return unknown("FUNCTION_BODY_UNAVAILABLE", fn.string("ref"))
        val env = closures[fn.string("closureId")].orEmpty() + function.records("parameters").filter { it.string("ref") !in fn.strings("boundParameterRefs") }
            .mapIndexedNotNull { i, p -> arguments.getOrNull(i)?.let { p.string("ref") to it } }.toMap()
        val candidates = returns[fn.string("ref")].orEmpty()
        if (candidates.size != 1 || candidates.single().records("conditions").isNotEmpty()) return unknown("RETURN_CONDITIONS_UNRESOLVED", fn.string("ref"))
        return eval(candidates.single()["value"].record(), env, depth + 1)
    }
    private fun injectedField(ref: String, env: Map<String, Record>): Record? {
        val klass = classes.values.singleOrNull { c -> c.records("fields").any { it["ref"] == ref } } ?: return null
        if (klass.records("annotations").none { it["name"] == "dagger.hilt.android.AndroidEntryPoint" }) return null
        val field = klass.records("fields").single { it["ref"] == ref }
        if (field.records("annotations").none { it["name"] == "javax.inject.Inject" }) return null
        return injection.instance(field.string("type"), env["@context"]?.string("ref") ?: "ACTIVITY_OWNER_UNRESOLVED")
    }
}
