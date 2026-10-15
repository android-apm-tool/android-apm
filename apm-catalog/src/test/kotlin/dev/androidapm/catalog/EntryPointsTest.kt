package dev.androidapm.catalog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EntryPointsTest {
    private fun parse(body: String) = EntryPoints.collect("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="fixture"><application>$body</application></manifest>""".byteInputStream(), emptyMap())
    @Test fun `URI parser knowledge never creates a manifest web entry`() {
        val result = parse("""<activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter><intent-filter android:autoVerify="true"><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.BROWSABLE"/><category android:name="android.intent.category.DEFAULT"/><data android:scheme="https" android:host="example.invalid" android:pathPrefix="/inv/"/></intent-filter></activity>""")
        val entries = result.records("entries")
        assertEquals(3, entries.size)
        assertEquals(setOf("EXPLICIT_INTENT", "LAUNCHER", "BROWSABLE_INTENT"), entries.map { it["kind"] }.toSet())
        val web = entries.single { it["kind"] == "BROWSABLE_INTENT" }
        assertEquals("NOT_PROVEN", web["uriParserBinding"])
        assertEquals("NOT_OBSERVED", web["domainVerification"])
        assertEquals("/inv/", web["filter"].record().records("data").single()["pathPrefix"])
    }
    @Test fun `alias targets retain component and lifecycle identity`() {
        val result = parse("""<activity-alias android:name=".Launcher" android:targetActivity=".Main" android:exported="false" android:enabled="false"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity-alias>""")
        val launcher = result.records("entries").single { it["kind"] == "LAUNCHER" }
        assertEquals("fixture.Launcher", launcher["component"])
        assertEquals("fixture.Main", launcher["targetActivity"])
        assertEquals("false", launcher["enabled"])
    }
    @Test fun `XML entities cannot read files or issue network requests`() {
        val xml = """<!DOCTYPE manifest [<!ENTITY secret SYSTEM "file:///does-not-exist">]><manifest><application>&secret;</application></manifest>"""
        assertThrows(Exception::class.java) { EntryPoints.collect(xml.byteInputStream(), emptyMap()) }
    }
}
