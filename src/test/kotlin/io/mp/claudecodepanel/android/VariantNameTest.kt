package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantNameTest {

    /** The real shape from sample-android-app: brand × environment dimensions plus a build type. */
    @Test
    fun `a two-dimension variant decomposes`() {
        val p = VariantName.decompose("demoStagingDebug", listOf("demo", "staging"))
        assertEquals(listOf("demo", "staging"), p.flavors)
        assertEquals("debug", p.buildType)
    }

    @Test
    fun `a single-flavour variant decomposes`() {
        val p = VariantName.decompose("stagingDebug", listOf("staging"))
        assertEquals(listOf("staging"), p.flavors)
        assertEquals("debug", p.buildType)
    }

    @Test
    fun `a variant with no flavours is just a build type`() {
        val p = VariantName.decompose("debug", emptyList())
        assertTrue(p.flavors.isEmpty())
        assertEquals("debug", p.buildType)
    }

    @Test
    fun `a custom build type survives decomposition`() {
        val p = VariantName.decompose("demoStagingQa", listOf("demo", "staging"))
        assertEquals(listOf("demo", "staging"), p.flavors)
        assertEquals("qa", p.buildType)
    }

    @Test
    fun `flavours that do not appear in the name are not forced on`() {
        val p = VariantName.decompose("demoStagingDebug", listOf("demo", "staging", "wear"))
        assertEquals(listOf("demo", "staging"), p.flavors)
        assertEquals("debug", p.buildType)
    }

    /**
     * The honest-failure case. If the declared flavours don't match the name we understand nothing about
     * it, and inventing a split would silently rename the build type — worse than admitting ignorance.
     */
    @Test
    fun `a name that matches no declared flavour reports no flavours`() {
        val p = VariantName.decompose("somethingElseRelease", listOf("demo", "staging"))
        assertTrue(p.flavors.isEmpty())
        assertEquals("release", p.buildType) // recovered by the conventional-suffix guess
    }

    @Test
    fun `flavour order matters and resolves the ambiguous case`() {
        // "stagingDebug" is both a valid flavour name and a valid flavour+buildType concatenation.
        // Declaration order is the only thing that settles it.
        val asFlavor = VariantName.decompose("stagingDebugRelease", listOf("stagingDebug"))
        assertEquals(listOf("stagingDebug"), asFlavor.flavors)
        assertEquals("release", asFlavor.buildType)

        val asSplit = VariantName.decompose("stagingDebug", listOf("staging"))
        assertEquals(listOf("staging"), asSplit.flavors)
        assertEquals("debug", asSplit.buildType)
    }

    @Test
    fun `a blank variant decomposes to nothing rather than crashing`() {
        val p = VariantName.decompose("", listOf("demo"))
        assertTrue(p.flavors.isEmpty())
        assertNull(p.buildType)
    }

    // ---- guessBuildType: allowed to say null, never allowed to be wrong ----

    @Test
    fun `a conventional suffix is recognised`() {
        assertEquals("debug", VariantName.guessBuildType("demoStagingDebug"))
        assertEquals("release", VariantName.guessBuildType("demoStagingRelease"))
        assertEquals("debug", VariantName.guessBuildType("debug"))
        assertEquals("release", VariantName.guessBuildType("Release"))
    }

    @Test
    fun `an unconventional build type is admitted as unknown, not guessed`() {
        assertNull(VariantName.guessBuildType("demoStagingQa"))
        assertNull(VariantName.guessBuildType("benchmark"))
        assertNull(VariantName.guessBuildType(""))
    }

    /** `debugging` ends with the letters of `debug` but is not that build type. */
    @Test
    fun `a substring match is not a suffix match`() {
        assertNull(VariantName.guessBuildType("demoDebugging"))
    }

    // ---- helpers ----

    @Test
    fun `capitalized builds the Gradle task fragment`() {
        assertEquals("DemoStagingDebug", VariantName.capitalized("demoStagingDebug"))
        assertEquals("Debug", VariantName.capitalized("debug"))
        assertEquals("", VariantName.capitalized(""))
    }

    @Test
    fun `describe reads as the parts we found`() {
        assertEquals(
            "demo · staging · debug",
            VariantName.describe(VariantName.decompose("demoStagingDebug", listOf("demo", "staging"))),
        )
        assertEquals("debug", VariantName.describe(VariantName.decompose("debug", emptyList())))
    }

    @Test
    fun `describe falls back to the raw name when nothing was understood`() {
        assertEquals("weirdVariant", VariantName.describe(VariantParts("weirdVariant", emptyList(), null)))
    }
}
