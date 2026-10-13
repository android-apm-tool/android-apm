package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonTest {
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
