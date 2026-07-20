package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputMetadataParserTest {

    /** Captured verbatim from sample-android-app's build output. */
    private val real = """
        {
          "version": 3,
          "artifactType": { "type": "APK", "kind": "Directory" },
          "applicationId": "com.example.driver.staging",
          "variantName": "demoStagingDebug",
          "elements": [
            {
              "type": "SINGLE",
              "filters": [],
              "attributes": [],
              "versionCode": 1,
              "versionName": "1.0-staging",
              "outputFile": "wear-demo-staging-debug.apk"
            }
          ],
          "elementType": "File",
          "minSdkVersionForDexing": 30
        }
    """.trimIndent()

    @Test
    fun `real AGP output parses`() {
        val m = OutputMetadataParser.parse(real)!!
        assertEquals(3, m.metadataVersion)
        // The flavour suffix is already applied here, which is why this beats the declared defaultConfig.
        assertEquals("com.example.driver.staging", m.applicationId)
        assertEquals("demoStagingDebug", m.variantName)
        assertEquals(1, m.versionCode)
        assertEquals("1.0-staging", m.versionName)
        assertEquals(30, m.minSdkForDexing)
        assertEquals("wear-demo-staging-debug.apk", m.outputFile)
        assertFalse(m.unrecognisedVersion)
    }

    @Test
    fun `a newer metadata version is still read, but flagged`() {
        val m = OutputMetadataParser.parse(real.replace("\"version\": 3", "\"version\": 9"))!!
        assertEquals("demoStagingDebug", m.variantName)
        assertTrue(m.unrecognisedVersion)
    }

    /** The unfiltered SINGLE output is the one a plain install deploys. */
    @Test
    fun `the SINGLE element wins over split outputs`() {
        val split = """
            {
              "version": 3,
              "applicationId": "com.example",
              "variantName": "debug",
              "elements": [
                { "type": "ONE_OF_MANY", "versionCode": 2, "versionName": "arm", "outputFile": "arm.apk" },
                { "type": "SINGLE", "versionCode": 1, "versionName": "1.0", "outputFile": "app.apk" }
              ]
            }
        """.trimIndent()
        val m = OutputMetadataParser.parse(split)!!
        assertEquals("1.0", m.versionName)
        assertEquals("app.apk", m.outputFile)
    }

    @Test
    fun `with no SINGLE element the first is used`() {
        val split = """
            {
              "version": 3, "applicationId": "com.example", "variantName": "debug",
              "elements": [ { "type": "ONE_OF_MANY", "versionName": "arm", "outputFile": "arm.apk" } ]
            }
        """.trimIndent()
        assertEquals("arm", OutputMetadataParser.parse(split)!!.versionName)
    }

    @Test
    fun `missing fields cost that field, not the record`() {
        val partial = """{ "version": 3, "variantName": "debug" }"""
        val m = OutputMetadataParser.parse(partial)!!
        assertEquals("debug", m.variantName)
        assertNull(m.applicationId)
        assertNull(m.versionName)
    }

    /** The file is AGP's, not ours — a type change must not take the panel down. */
    @Test
    fun `wrong-typed values are treated as absent`() {
        val weird = """{ "version": 3, "variantName": "debug", "minSdkVersionForDexing": "thirty" }"""
        val m = OutputMetadataParser.parse(weird)!!
        assertEquals("debug", m.variantName)
        assertNull(m.minSdkForDexing)
    }

    @Test
    fun `unusable input yields null rather than an empty record`() {
        assertNull(OutputMetadataParser.parse(""))
        assertNull(OutputMetadataParser.parse("not json at all"))
        assertNull(OutputMetadataParser.parse("[1,2,3]"))
        assertNull(OutputMetadataParser.parse("""{"version": 3}""")) // nothing useful in it
    }

    // ---- variant from a build path: the directory names are themselves a fact ----

    @Test
    fun `a variant is recovered from a merged manifest path`() {
        assertEquals(
            "demoStagingDebug",
            OutputMetadataParser.variantFromPath(
                "app/build/intermediates/merged_manifest/demoStagingDebug/processDemoStagingDebugMainManifest/AndroidManifest.xml",
            ),
        )
        assertEquals(
            "demoStagingDebug",
            OutputMetadataParser.variantFromPath(
                "app/build/intermediates/merged_manifests/demoStagingDebug/x/AndroidManifest.xml",
            ),
        )
        assertEquals(
            "debug",
            OutputMetadataParser.variantFromPath("core/build/intermediates/packaged_manifests/debug/x/y.xml"),
        )
    }

    @Test
    fun `a path without the shape yields null rather than a guessed segment`() {
        assertNull(OutputMetadataParser.variantFromPath("app/src/main/AndroidManifest.xml"))
        assertNull(OutputMetadataParser.variantFromPath(""))
        assertNull(OutputMetadataParser.variantFromPath("app/build/intermediates/merged_manifest"))
    }

    @Test
    fun `windows separators are handled`() {
        assertEquals(
            "debug",
            OutputMetadataParser.variantFromPath("""app\build\intermediates\merged_manifest\debug\x\y.xml"""),
        )
    }
}
