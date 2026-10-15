package dev.androidapm.catalog

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Manifest declarations are kept separate from parser support and Android delivery. */
object EntryPoints {
    private const val android = "http://schemas.android.com/apk/res/android"
    fun unavailable(catalog: Record) = record("schemaVersion" to 1, "catalogFingerprint" to fingerprint(catalog),
        "status" to "MERGED_MANIFEST_UNAVAILABLE", "entries" to emptyList<Record>())

    fun collect(input: InputStream, catalog: Record): Record {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(input).documentElement
        val pkg = document.getAttribute("package")
        fun qualified(name: String) = when { name.startsWith('.') -> pkg + name; '.' !in name -> "$pkg.$name"; else -> name }
        val app = document.children("application").singleOrNull() ?: return unavailable(catalog)
        val entries = (app.children("activity") + app.children("activity-alias")).flatMap { activity ->
            val name = qualified(activity.attr("name"))
            val target = activity.attr("targetActivity").takeIf { it.isNotEmpty() }?.let(::qualified) ?: name
            val owners = catalog.records("functions").filter { it["classOwner"] == target && it["simpleName"] == "onCreate" }.map { it["symbol"] }.toSet()
            val roots = catalog.records("uiRoots").filter { it["owner"] in owners }.map { it["id"] }.toSet()
            val hosts = catalog.records("uiRootBindings").filter { it["rootId"] in roots }.flatMap { it.strings("navHostIds") }.distinct().sorted()
            val enabled = activity.attr("enabled").ifEmpty { "true" }
            val appEnabled = app.attr("enabled").ifEmpty { "true" }
            val exported = activity.attr("exported").ifEmpty { "UNRESOLVED" }
            val common = record("component" to name, "targetActivity" to target, "enabled" to enabled, "applicationEnabled" to appEnabled,
                "exported" to exported, "permission" to activity.attr("permission").ifEmpty { app.attr("permission") },
                "launchMode" to activity.attr("launchMode").ifEmpty { "standard" }, "hostRefs" to hosts,
                "rootRefs" to roots.sortedBy { it.toString() }, "observation" to "NOT_OBSERVED")
            val explicit = common + record("id" to "entry:" + fingerprint(record("component" to name, "mode" to "EXPLICIT")),
                "kind" to "EXPLICIT_INTENT", "delivery" to "REQUIRES_CALLER_AND_ANDROID_STATE", "coldWarm" to "SEPARATE_LIFECYCLE_PATHS_UNRESOLVED")
            listOf(explicit) + activity.children("intent-filter").map { filter ->
                val actions = filter.children("action").map { it.attr("name") }.sorted()
                val categories = filter.children("category").map { it.attr("name") }.sorted()
                val data = filter.children("data").map { node -> listOf("scheme", "host", "port", "path", "pathPrefix", "pathPattern", "pathAdvancedPattern", "mimeType").associateWith { node.attr(it) }.filterValues { it.isNotEmpty() } }
                val kind = when {
                    "android.intent.action.MAIN" in actions && "android.intent.category.LAUNCHER" in categories -> "LAUNCHER"
                    "android.intent.action.VIEW" in actions && "android.intent.category.BROWSABLE" in categories -> "BROWSABLE_INTENT"
                    else -> "INTENT_FILTER"
                }
                val specification = record("actions" to actions, "categories" to categories, "data" to data,
                    "autoVerify" to filter.attr("autoVerify").ifEmpty { "false" })
                common + record("id" to "entry:" + fingerprint(common + specification), "kind" to kind, "filter" to specification,
                    "delivery" to "MANIFEST_DECLARATION_ONLY", "uriParserBinding" to "NOT_PROVEN", "domainVerification" to "NOT_OBSERVED")
            }
        }
        return record("schemaVersion" to 1, "catalogFingerprint" to fingerprint(catalog), "status" to "COLLECTED",
            "entries" to entries.sortedBy { it.string("id") }, "limitations" to listOf("ANDROID_DELIVERY_NOT_OBSERVED", "URI_PARSER_NOT_IMPLICITLY_EXTERNAL_ENTRY"))
    }
    private fun Element.attr(name: String) = getAttributeNS(android, name)
    private fun Element.children(name: String): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == name }
}
