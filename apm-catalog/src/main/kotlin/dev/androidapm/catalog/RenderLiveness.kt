package dev.androidapm.catalog

/** Conservative lexical liveness for render continuations. Only this function's
 * local bindings can be removed, after their last possible read. Captures,
 * aliases, local functions and local-class methods count as reads. */
internal class RenderLiveness(private val flow: FlowValues) {
    private val localsByOwner = flow.values.values.filter { it["storage"] == "LOCAL" }.groupBy { it.string("owner") }
    private val methodsByClass = flow.functions.values.groupBy { it.string("classOwner") }
    private val fieldsByClass = flow.values.values.filter { it["storage"] == "FIELD" }.groupBy { it.string("classOwner") }
    private val prepared = mutableSetOf<String>()
    private val dead = java.util.IdentityHashMap<Record, Set<String>>()
    fun deadAfter(statement: Record): Set<String> = dead[statement].orEmpty()

    fun prepare(owner: String) {
        if (!prepared.add(owner)) return
        val locals = localsByOwner[owner].orEmpty().map { it.string("ref") }.toSet()
        if (locals.isEmpty()) return
        val cache = java.util.IdentityHashMap<Record, Set<String>>()
        fun localFunction(ref: String): Boolean {
            var current = flow.functions[ref]?.string("enclosingFunction").orEmpty()
            val seen = mutableSetOf<String>()
            while (current.isNotEmpty() && seen.add(current)) {
                if (current == owner) return true
                current = flow.functions[current]?.string("enclosingFunction").orEmpty()
            }
            return false
        }
        fun references(input: Record): Set<String> = cache.getOrPut(input) {
            val result = mutableSetOf<String>()
            val seen = mutableSetOf<String>()
            var incomplete = false
            fun scan(value: Any?, depth: Int) {
                if (depth > 96) { incomplete = true; return }
                when (value) {
                    is Map<*, *> -> {
                        val item = value.record()
                        if (item["kind"] == "REFERENCE") {
                            val ref = item.string("ref")
                            if (ref in locals) result += ref
                            val alias = flow.values[ref]
                            // A local binding already contains its evaluated value
                            // (including a closure's captured environment). Its
                            // initializer is read at DECLARE, not at every later use.
                            val follow = alias != null && (alias["storage"] == "LOCAL" && ref !in locals || alias["storage"] == "FIELD" && (alias["owner"] == owner || localFunction(alias.string("owner"))))
                            if (follow && seen.add("value:$ref")) scan(alias?.get("value"), depth + 1)
                        }
                        if (item["kind"] == "FUNCTION") {
                            val ref = item.string("ref")
                            if (seen.add("function:$ref")) {
                                val body = flow.bodies[ref]
                                if (body == null) incomplete = true else scan(body, depth + 1)
                            }
                        }
                        if (item["kind"] in setOf("CALL", "CONSTRUCTOR")) {
                            val ref = item.string("callId")
                            if (seen.add("call:$ref")) {
                                val call = flow.calls[ref]
                                if (call == null) incomplete = true else {
                                    scan(call["arguments"], depth + 1)
                                    val target = call.string("target")
                                    if (localFunction(target) && seen.add("function:$target")) {
                                        if (call["constructor"] != true) scan(flow.bodies[target], depth + 1)
                                        scan(flow.functions[target]?.get("parameters"), depth + 1)
                                    }
                                    if (item["kind"] == "CONSTRUCTOR" && localFunction(target)) {
                                        methodsByClass[call.string("classOwner")].orEmpty().filter { it["constructor"] != true }.forEach {
                                            if (seen.add("function:" + it.string("symbol"))) scan(flow.bodies[it.string("symbol")], depth + 1)
                                        }
                                        fieldsByClass[call.string("classOwner")].orEmpty().forEach { scan(it["value"], depth + 1) }
                                    }
                                }
                            }
                        }
                        if (item["op"] in setOf("UNKNOWN", "LOOP", "TRY", "WRITE_FIELD")) incomplete = true
                        item.filterKeys { it != "source" }.values.forEach { scan(it, depth + 1) }
                    }
                    is List<*> -> value.forEach { scan(it, depth + 1) }
                }
            }
            scan(input, 0)
            if (incomplete) locals else result
        }
        fun visit(statement: Record, after: Set<String>): Set<String> {
            dead[statement] = locals - after
            return when (statement["op"]) {
                "BLOCK" -> statement.records("statements").asReversed().fold(after) { live, child -> visit(child, live) }
                "BRANCH" -> after + statement.records("branches").flatMap { branch -> references(branch["test"].record()) + visit(branch["body"].record(), after) }
                "DECLARE", "WRITE" -> (after - statement.string("ref")) + references(statement["value"].record())
                else -> after + references(statement)
            }
        }
        visit(flow.bodies[owner].orEmpty(), emptySet())
    }
}
