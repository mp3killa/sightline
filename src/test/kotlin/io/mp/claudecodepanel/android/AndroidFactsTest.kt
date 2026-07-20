package io.mp.claudecodepanel.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFactsTest {

    @Test
    fun `a known fact carries its value and tier`() {
        val f = Fact.known("demoStagingDebug", FactTier.IDE)
        assertTrue(f.isKnown)
        assertEquals("demoStagingDebug", f.value)
    }

    @Test
    fun `unknown facts have no value`() {
        val f = Fact.unknown<String>("no build output yet")
        assertFalse(f.isKnown)
        assertNull(f.value)
        assertEquals(FactTier.UNKNOWN, f.tier)
    }

    /** The invariant is the whole point of the type: a value must always know where it came from. */
    @Test(expected = IllegalArgumentException::class)
    fun `a value cannot be UNKNOWN-tier`() {
        Fact("something", FactTier.UNKNOWN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a null value cannot claim a real tier`() {
        Fact<String>(null, FactTier.IDE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `known() rejects the UNKNOWN tier`() {
        Fact.known("x", FactTier.UNKNOWN)
    }

    @Test
    fun `tier ordering runs strongest to weakest`() {
        assertEquals(FactTier.IDE, FactTier.IDE.strongerOf(FactTier.BUILD_OUTPUT))
        assertEquals(FactTier.IDE, FactTier.BUILD_OUTPUT.strongerOf(FactTier.IDE))
        assertEquals(FactTier.BUILD_OUTPUT, FactTier.BUILD_OUTPUT.strongerOf(FactTier.UNKNOWN))
        assertEquals(FactTier.STATIC_PARSE, FactTier.STATIC_PARSE.strongerOf(FactTier.DEVICE))
    }

    // ---- describe: the qualifier is what stops a stale fact reading as current ----

    @Test
    fun `an IDE fact needs no qualifier`() {
        assertEquals("demoStagingDebug", Fact.known("demoStagingDebug", FactTier.IDE).describe())
    }

    @Test
    fun `a build-output fact says so`() {
        assertEquals(
            "demoStagingDebug (last build)",
            Fact.known("demoStagingDebug", FactTier.BUILD_OUTPUT).describe(),
        )
    }

    @Test
    fun `an explicit note beats the tier label`() {
        assertEquals(
            "demoStagingDebug (built 20 minutes ago)",
            Fact.known("demoStagingDebug", FactTier.BUILD_OUTPUT, "built 20 minutes ago").describe(),
        )
    }

    @Test
    fun `an IDE fact with a note still shows the note`() {
        assertEquals("debug (sync in progress)", Fact.known("debug", FactTier.IDE, "sync in progress").describe())
    }

    @Test
    fun `an unknown fact explains itself when it can`() {
        assertEquals("unknown — nothing built yet", Fact.unknown<String>("nothing built yet").describe())
        assertEquals("unknown", Fact.unknown<String>().describe())
    }

    // ---- ladder ----

    @Test
    fun `the ladder takes the first known rung`() {
        val f = Fact.ladder(
            { Fact.unknown<String>("no IDE model") },
            { Fact.known("demoStagingDebug", FactTier.BUILD_OUTPUT) },
            { Fact.known("debug", FactTier.STATIC_PARSE) },
        )
        assertEquals("demoStagingDebug", f.value)
        assertEquals(FactTier.BUILD_OUTPUT, f.tier)
    }

    /** Later rungs can be expensive (a device probe); a hit higher up must not pay for them. */
    @Test
    fun `the ladder stops evaluating after a hit`() {
        var evaluated = 0
        Fact.ladder(
            { Fact.known("hit", FactTier.IDE) },
            { evaluated++; Fact.known("never", FactTier.DEVICE) },
        )
        assertEquals(0, evaluated)
    }

    @Test
    fun `an exhausted ladder is unknown and keeps the last explanation`() {
        val f = Fact.ladder<String>(
            { Fact.unknown("no IDE model") },
            { Fact.unknown("nothing built yet") },
        )
        assertFalse(f.isKnown)
        assertEquals("nothing built yet", f.note)
    }

    @Test
    fun `an empty ladder is unknown, not a crash`() {
        assertFalse(Fact.ladder<String>().isKnown)
    }
}
