package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording *is* the feature here — this is the only point at which someone learns an agent is about
 * to read their files and run commands, so the text is tested like behaviour.
 */
class FirstRunDisclosureTest {

    @Test
    fun `shown once, then never again`() {
        assertTrue(FirstRunDisclosure.shouldShow(alreadyAcknowledged = false))
        assertFalse(FirstRunDisclosure.shouldShow(alreadyAcknowledged = true))
    }

    @Test
    fun `it says plainly what the plugin can do`() {
        val body = FirstRunDisclosure.body("auto").joinToString(" ")
        assertTrue("must mention reading files: $body", body.contains("inspect files"))
        assertTrue("must mention running commands: $body", body.contains("run development commands"))
        assertTrue("must mention changing code: $body", body.contains("apply changes"))
    }

    @Test
    fun `it says where the data goes and what is not collected`() {
        val body = FirstRunDisclosure.body("auto").joinToString(" ")
        assertTrue(body.contains("your own Anthropic account"))
        assertTrue(body.contains("collects no analytics"))
        assertTrue(body.contains("never sees your credentials"))
    }

    /**
     * The modes make opposite promises. One sentence covering both would be actively misleading for
     * whichever it didn't describe.
     */
    @Test
    fun `each mode gets a sentence that is true of that mode`() {
        assertTrue(FirstRunDisclosure.modeSentence("default").contains("approve every tool call"))
        assertTrue(FirstRunDisclosure.modeSentence("acceptEdits").contains("apply without asking"))
        assertTrue(FirstRunDisclosure.modeSentence("plan").contains("propose a plan"))
        assertTrue(FirstRunDisclosure.modeSentence("auto").contains("Claude decides"))
    }

    /** If the disclosure is worth anything, it is here. No softening. */
    @Test
    fun `unrestricted mode is described bluntly`() {
        val sentence = FirstRunDisclosure.modeSentence("bypassPermissions")
        assertTrue(sentence.contains("no approval"))
        assertTrue(sentence.contains("afford to lose"))
        assertTrue(FirstRunDisclosure.isDangerousMode("bypassPermissions"))
    }

    @Test
    fun `ordinary modes are not flagged as dangerous`() {
        for (mode in listOf("default", "acceptEdits", "plan", "auto")) {
            assertFalse(mode, FirstRunDisclosure.isDangerousMode(mode))
        }
    }

    @Test
    fun `an unknown mode still gets a sentence rather than a blank`() {
        assertTrue(FirstRunDisclosure.modeSentence("something-new").isNotBlank())
        assertEquals(3, FirstRunDisclosure.body("something-new").size)
    }

    /** Short enough to be read. A notice people click past manufactures consent rather than obtaining it. */
    @Test
    fun `the notice stays short`() {
        val words = FirstRunDisclosure.body("auto").sumOf { it.split(" ").size }
        assertTrue("disclosure is $words words; keep it under 120", words < 120)
        assertEquals(3, FirstRunDisclosure.body("auto").size)
    }

    @Test
    fun `it closes by saying the choice is reversible`() {
        assertTrue(FirstRunDisclosure.FOOTER.contains("change the permission mode at any time"))
        assertTrue(FirstRunDisclosure.FOOTER.contains("Activity Map"))
    }

    @Test
    fun `button labels say what they do`() {
        assertEquals("Start using Sightline", FirstRunDisclosure.CONTINUE)
        assertEquals("Open settings", FirstRunDisclosure.SETTINGS)
    }
}
