package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This feature is a deliberate exception to the "nothing is persisted but settings" standing decision,
 * so its *wording* is tested like behaviour: the consent dialog is the whole basis on which a user
 * agrees to have an identifier stored, and a sentence quietly dropped from it later would turn an
 * informed choice into an assumed one.
 */
class SessionPersistenceTest {

    /** The dialog hard-wraps; what is being asserted is the wording, not where the lines break. */
    private val consent = SessionPersistence.CONSENT.replace(Regex("\\s+"), " ")

    @Test fun `the consent states what is stored and what is not`() {
        assertTrue(consent, consent.contains("a session id and the date"))
        assertTrue(consent, consent.contains("No messages"))
        assertTrue(consent, consent.contains("no file contents"))
    }

    @Test fun `the consent says where the real transcript already lives`() {
        // Without this the reader cannot judge the risk: the CLI stores the whole conversation either
        // way, so the id is a pointer, not a new disclosure.
        assertTrue(consent, consent.contains("~/.claude/projects/"))
    }

    @Test fun `the consent never promises to restore the transcript`() {
        assertTrue(consent, consent.contains("The panel starts empty"))
        assertTrue(consent, consent.contains("cannot redraw it"))
        // And the notice shown at resume time repeats it, because that is the moment the user forms
        // the expectation.
        val resumed = SessionPersistence.RESUMED_NOTICE.replace(Regex("\\s+"), " ")
        assertTrue(resumed, resumed.contains("this panel does not"))
    }

    @Test fun `the consent says it is reversible`() {
        assertTrue(consent, consent.contains("turn this off at any time"))
        assertTrue(consent, consent.contains("forgets the saved id"))
    }

    @Test fun `the decline option is unambiguous`() {
        assertEquals("No, don't save anything", SessionPersistence.DECLINE)
    }

    @Test fun `descriptions never claim something is saved when nothing is`() {
        assertEquals("Nothing saved for this project.", SessionPersistence.describe(null, "2026-09-16"))
        assertEquals("Nothing saved for this project.", SessionPersistence.describe("  ", null))
        assertTrue(SessionPersistence.describe("abc-123", null).contains("A session id is saved"))
        assertTrue(SessionPersistence.describe("abc-123", "2026-09-16").contains("from 2026-09-16"))
    }

    @Test fun `the resume label does not promise a transcript`() {
        val label = SessionPersistence.resumeLabel("2026-09-16")
        assertTrue(label, label.contains("Resume conversation from 2026-09-16"))
        assertFalse(label.lowercase().contains("restore"))
    }

    /** Only something shaped like the CLI's own id is ever handed back to `--resume`. */
    @Test fun `ids are validated before being used`() {
        assertTrue(SessionPersistence.isValidId("2f1c4e6a-1111-2222-3333-444455556666"))
        assertFalse(SessionPersistence.isValidId(null))
        assertFalse(SessionPersistence.isValidId(""))
        assertFalse(SessionPersistence.isValidId("../../etc/passwd"))
        assertFalse(SessionPersistence.isValidId("not an id"))
    }
}
