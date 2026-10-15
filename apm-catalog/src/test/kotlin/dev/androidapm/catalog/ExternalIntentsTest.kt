package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExternalIntentsTest {
    private fun entry() = record("id" to "web", "kind" to "BROWSABLE_INTENT", "enabled" to "true", "applicationEnabled" to "true", "exported" to "true", "launchMode" to "singleTask", "permission" to "",
        "filter" to record("actions" to listOf("android.intent.action.VIEW"), "categories" to listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
            "data" to listOf(record("scheme" to "https", "host" to "example.test", "pathPrefix" to "/invite"))))
    @Test fun `only supported enabled unprotected warm activity filters generate concrete inputs`() {
        val entry = entry()
        assertEquals("https://example.test/invite/apm-example", ExternalIntents.candidates(entry).single()["data"].record()["value"])
        for (field in listOf("enabled", "applicationEnabled", "exported")) assertTrue(ExternalIntents.candidates(entry + record(field to "false")).isEmpty())
        for (mode in listOf("standard", "unknown")) assertTrue(ExternalIntents.candidates(entry + record("launchMode" to mode)).isEmpty())
        assertTrue(ExternalIntents.candidates(entry + record("permission" to "private.permission")).isEmpty())
        for (data in listOf(record("scheme" to "https", "host" to "*.test", "pathPrefix" to "/invite"), record("scheme" to "https", "host" to "example.test", "pathPattern" to "/.*")))
            assertTrue(ExternalIntents.candidates(entry + record("filter" to (entry["filter"].record() + record("data" to listOf(data))))).isEmpty())
        assertTrue(ExternalIntents.candidates(entry + record("filter" to (entry["filter"].record() + record("categories" to listOf("android.intent.category.BROWSABLE"))))).isEmpty())
    }
    @Test fun `URI and list summaries do not accept unrelated APIs or unproven receivers`() {
        val uri = ExternalIntents.uri("https://example.test/invite/token")!!
        val list = ExternalIntents.value("android.net.Uri.getPathSegments", mapOf("this" to uri))!!
        assertEquals("invite", ExternalIntents.value("kotlin.collections.firstOrNull", mapOf("this" to list))?.get("value"))
        assertEquals("token", ExternalIntents.value("kotlin.collections.getOrNull", mapOf("this" to list, "index" to record("kind" to "CONSTANT", "value" to 1)))?.get("value"))
        assertNull(ExternalIntents.value("example.Uri.getPathSegments", mapOf("this" to uri)))
        assertNull(ExternalIntents.value("android.net.Uri.getPathSegments", mapOf("this" to record("kind" to "UNKNOWN"))))
        assertNull(ExternalIntents.uri("https://example.test/invite/%2F"))
        assertNull(ExternalIntents.uri("https://example.test/invite/token?q=unknown"))
        assertNull(ExternalIntents.uri("https://user@example.test/invite/token"))
    }
}
