package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCatalogParserTest {

    private val catalog = """
        [versions]
        agp = "8.7.2"
        kotlin = "2.1.0"
        composeBom = "2024.12.01"
        coreKtx = "1.15.0"
        # a comment
        junit = "4.13.2"

        [libraries]
        androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }

        [plugins]
        android-application = { id = "com.android.application", version.ref = "agp" }
    """.trimIndent()

    @Test
    fun `the versions table parses`() {
        val v = VersionCatalogParser.parseVersions(catalog)
        assertEquals("8.7.2", v["agp"])
        assertEquals("2.1.0", v["kotlin"])
        assertEquals("2024.12.01", v["composeBom"])
        assertEquals("4.13.2", v["junit"])
    }

    /** Only `[versions]`. A library coordinate is a dependency, not a version fact. */
    @Test
    fun `other tables are ignored`() {
        val v = VersionCatalogParser.parseVersions(catalog)
        assertFalse(v.containsKey("androidx-core-ktx"))
        assertFalse(v.containsKey("android-application"))
        assertEquals(5, v.size)
    }

    @Test
    fun `declaration order is preserved`() {
        assertEquals(
            listOf("agp", "kotlin", "composeBom", "coreKtx", "junit"),
            VersionCatalogParser.parseVersions(catalog).keys.toList(),
        )
    }

    /** A rich version is a constraint; rendering it as a version would misreport what resolves. */
    @Test
    fun `rich versions are skipped rather than mangled`() {
        val toml = """
            [versions]
            plain = "1.0.0"
            rich = { strictly = "[1.0, 2.0[", prefer = "1.5" }
        """.trimIndent()
        val v = VersionCatalogParser.parseVersions(toml)
        assertEquals("1.0.0", v["plain"])
        assertFalse(v.containsKey("rich"))
    }

    @Test
    fun `trailing comments and quoted keys are handled`() {
        val v = VersionCatalogParser.parseVersions(
            """
            [versions]
            agp = "8.7.2" # keep in step with Studio
            "quoted-key" = "1.0"
            """.trimIndent(),
        )
        assertEquals("8.7.2", v["agp"])
        assertEquals("1.0", v["quoted-key"])
    }

    @Test
    fun `an empty or absent catalog is empty, not an error`() {
        assertTrue(VersionCatalogParser.parseVersions("").isEmpty())
        assertTrue(VersionCatalogParser.parseVersions("[libraries]\nfoo = \"bar\"").isEmpty())
        assertTrue(VersionCatalogParser.parseVersions("no tables at all").isEmpty())
    }

    // ---- highlights: a prompt gets the handful that matter, not eighty entries ----

    @Test
    fun `highlights pick the versions worth prompting with`() {
        val h = VersionCatalogParser.highlights(VersionCatalogParser.parseVersions(catalog))
        assertEquals("8.7.2", h["agp"])
        assertEquals("2.1.0", h["kotlin"])
        assertEquals("2024.12.01", h["composeBom"])
        assertFalse("junit is not a headline version", h.containsKey("junit"))
    }

    @Test
    fun `highlight matching ignores separators and case`() {
        val h = VersionCatalogParser.highlights(mapOf("compose-bom" to "2024.12.01", "KSP" to "2.1.0-1.0.29"))
        assertEquals("2024.12.01", h["compose-bom"])
        assertEquals("2.1.0-1.0.29", h["KSP"])
    }

    @Test
    fun `highlights of nothing are nothing`() {
        assertTrue(VersionCatalogParser.highlights(emptyMap()).isEmpty())
        assertTrue(VersionCatalogParser.highlights(mapOf("obscure" to "1.0")).isEmpty())
    }
}
