package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonTest {
    @Test fun `pretty output uses two spaces without changing canonical hashing or JSON values`() {
        val value = record("z" to listOf(record("화면" to "홈\n\"상세\""), emptyList<Any>()), "a" to emptyMap<String, Any>())
        val canonical = Json.encode(value)
        val pretty = buildString { Json.write(value, this, pretty = true) }
        assertTrue(pretty.startsWith("{\n  \"z\": [\n    {\n      \"화면\": "))
        assertTrue(pretty.contains("    []\n"))
        assertTrue(pretty.contains("  \"a\": {}\n}"))
        assertEquals(value, Json.decode(pretty))
        assertEquals(canonical, Json.encode(Json.decode(pretty)))
    }

    @Test fun `unicode escapes and nested values round trip deterministically`() {
        val value = record("z" to listOf(true, null, 12L, 1.5), "a" to "화면\n\t\"\\\u0001")
        val encoded = Json.encode(value)
        assertTrue(encoded.startsWith("{\"a\":"))
        assertEquals(value, Json.decode(encoded))
    }
    @Test fun `invalid and duplicate records are rejected`() {
        listOf("{\"a\":1,\"a\":2}", "01", "1.", "[1,]", "{} false").forEach { input ->
            assertThrows(RuntimeException::class.java) { Json.decode(input) }
        }
        assertThrows(IllegalArgumentException::class.java) { Json.encode(Double.NaN) }
    }
}
