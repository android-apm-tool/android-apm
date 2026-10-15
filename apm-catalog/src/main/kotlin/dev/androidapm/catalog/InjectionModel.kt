package dev.androidapm.catalog

/** Restricted Hilt contracts: one unqualified @Binds binding in SingletonComponent. */
internal class InjectionModel(private val flow: FlowValues) {
    fun instance(requested: String, scope: String, viewModel: Boolean = false): Record {
        val type = requested.removeSuffix("?")
        val bindings = flow.catalog.records("diBindings").filter { it["returnType"] == type }
        val binding = bindings.singleOrNull()
        if (bindings.size > 1) return flow.unknown("DI_BINDING_AMBIGUOUS", type)
        val implementation = if (binding != null) {
            if (binding.strings("annotations").any { it !in setOf("dagger.Binds", "javax.inject.Singleton") }) return flow.unknown("DI_QUALIFIER_OR_SCOPE_NOT_MODELED", type)
            if ("dagger.Binds" !in binding.strings("annotations")) return flow.unknown("DI_PROVIDER_NOT_MODELED", type)
            val params = binding.records("parameters").filter { it.string("kind") == "Regular" }
            if (params.size != 1 || params.single().records("annotations").isNotEmpty()) return flow.unknown("DI_QUALIFIER_NOT_MODELED", type)
            val declaration = flow.functions[binding.string("symbol")].orEmpty()
            val module = flow.classes[declaration.string("classOwner")].orEmpty()
            val install = module.records("annotations").singleOrNull { it["name"] == "dagger.hilt.InstallIn" }
            if (install == null || !Json.encode(install).contains("dagger.hilt.components.SingletonComponent")) return flow.unknown("DI_COMPONENT_NOT_MODELED", type)
            params.single().string("type")
        } else type
        val klass = flow.classes[implementation] ?: return flow.unknown("DI_CLASS_UNAVAILABLE", type)
        val annotationNames = klass.records("annotations").map { it["name"] }
        if (viewModel && "dagger.hilt.android.lifecycle.HiltViewModel" !in annotationNames) return flow.unknown("VIEWMODEL_FACTORY_NOT_PROVEN", type)
        val singleton = "javax.inject.Singleton" in annotationNames || binding?.strings("annotations")?.contains("javax.inject.Singleton") == true
        // An unscoped constructor can create a new object at every injection site.
        // A matching type/owner is not evidence that a sender shares its collector.
        if (!singleton && !viewModel) return flow.unknown("DI_INSTANCE_SCOPE_UNPROVEN", type)
        val constructor = flow.functions.values.filter { it["classOwner"] == implementation && it["constructor"] == true && "javax.inject.Inject" in it.strings("annotations") }.singleOrNull()
            ?: return flow.unknown("INJECT_CONSTRUCTOR_UNRESOLVED", implementation)
        val fields = constructor.records("parameters").filter { it.string("kind") == "Regular" }.associate { p ->
            p.string("name") to record("kind" to "INJECTED_VALUE", "type" to p["type"], "scope" to scope,
                "qualified" to p.records("annotations").isNotEmpty())
        }
        return record("kind" to "INSTANCE", "type" to implementation,
            "identity" to if (singleton) "di:SingletonComponent:$implementation" else "di:$scope:$implementation",
            "fields" to fields, "provenance" to record("kind" to if (viewModel) "HILT_VIEWMODEL_OWNER" else "HILT_INJECTION",
                "bindingRef" to binding?.get("symbol"), "constructorRef" to constructor["symbol"], "scope" to if (singleton) "SingletonComponent" else scope),
            "multiplicity" to if (singleton) "ONE_PER_COMPONENT" else "OWNER_SCOPED")
    }
}
