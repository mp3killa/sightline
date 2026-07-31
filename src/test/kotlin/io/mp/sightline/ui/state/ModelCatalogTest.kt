package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private fun current(entries: List<ModelCatalog.Entry>) = entries.filter { it.current }

    @Test fun defaultIsCurrentWhenNothingIsSelected() {
        val e = ModelCatalog.entries(selected = "")
        assertEquals(1, current(e).size)
        assertNull("the default row carries no id — the CLI picks", current(e).single().id)
    }

    @Test fun everyDocumentedAliasIsOffered() {
        val ids = ModelCatalog.entries(selected = "").mapNotNull { it.id }
        assertTrue(ids.containsAll(listOf("opus", "sonnet", "haiku", "fable")))
    }

    @Test fun exactlyOneRowIsEverMarkedCurrent() {
        for (sel in listOf("", "opus", "sonnet", "claude-sonnet-5", "something-custom")) {
            val e = ModelCatalog.entries(selected = sel, customs = listOf("claude-sonnet-5"))
            assertEquals("selected='$sel' must mark exactly one row", 1, current(e).size)
        }
    }

    /**
     * The current row is chosen by what was *selected*, never by matching the reported id against an
     * alias: `sonnet` and `claude-sonnet-5` are the same model, and a string comparison marks neither.
     */
    @Test fun aliasStaysCurrentEvenWhenTheCliReportsADatedId() {
        val e = ModelCatalog.entries(selected = "sonnet", reported = "claude-sonnet-5")
        assertEquals("sonnet", current(e).single().id)
    }

    @Test fun pinnedCustomIdsAreOffered() {
        val e = ModelCatalog.entries(selected = "", customs = listOf("claude-sonnet-5", "claude-opus-5"))
        val customs = e.filter { it.custom }.mapNotNull { it.id }
        assertEquals(listOf("claude-sonnet-5", "claude-opus-5"), customs)
    }

    /** A model set in Settings that was never pinned still has to appear, or nothing shows as current. */
    @Test fun aSelectedIdThatIsNeitherAliasNorPinnedStillAppears() {
        val e = ModelCatalog.entries(selected = "claude-fable-5")
        val row = current(e).single()
        assertEquals("claude-fable-5", row.id)
        assertTrue(row.custom)
    }

    @Test fun aSelectedIdIsNotListedTwiceWhenAlsoPinned() {
        val e = ModelCatalog.entries(selected = "claude-sonnet-5", customs = listOf("claude-sonnet-5"))
        assertEquals(1, e.count { it.id == "claude-sonnet-5" })
    }

    @Test fun blankAndDuplicateCustomsAreDropped() {
        val e = ModelCatalog.entries(selected = "", customs = listOf(" ", "x", "x", "opus"))
        val customs = e.filter { it.custom }.mapNotNull { it.id }
        assertEquals("an alias is never re-listed as a custom", listOf("x"), customs)
    }

    /**
     * A row renders as "Label — detail", so a detail that merely repeats the label reads as "Fable —
     * Fable" (spotted in a screen recording of the real menu). A tier with no documented positioning
     * gets no description rather than an invented one.
     */
    @Test fun noRowDescribesItselfWithItsOwnName() {
        for (e in ModelCatalog.entries(selected = "", customs = listOf("claude-sonnet-5"))) {
            assertTrue(
                "row '${e.label}' has the tautological detail '${e.detail}'",
                !e.label.equals(e.detail, ignoreCase = true),
            )
        }
    }

    @Test fun labelsTitleCaseAliasesAndLeaveIdsAlone() {
        assertEquals("Sonnet", ModelCatalog.label("sonnet"))
        assertEquals("claude-sonnet-5", ModelCatalog.label("claude-sonnet-5"))
        assertEquals("Default", ModelCatalog.label(null))
        assertEquals("Default", ModelCatalog.label(""))
    }

    /** The note relays what the CLI said; with nothing reported it says nothing rather than guessing. */
    @Test fun resolvedNoteOnlyExistsOnceTheCliHasReported() {
        assertNull(ModelCatalog.resolvedNote(null))
        assertNull(ModelCatalog.resolvedNote("  "))
        assertTrue(ModelCatalog.resolvedNote("claude-sonnet-5")!!.contains("claude-sonnet-5"))
    }

    @Test fun rememberPutsTheNewestFirstAndDeduplicates() {
        var c = ModelCatalog.remember(emptyList(), "a")
        c = ModelCatalog.remember(c, "b")
        c = ModelCatalog.remember(c, "a")
        assertEquals(listOf("a", "b"), c)
    }

    @Test fun rememberIgnoresAliasesAndBlanks() {
        assertEquals(emptyList<String>(), ModelCatalog.remember(emptyList(), "opus"))
        assertEquals(emptyList<String>(), ModelCatalog.remember(emptyList(), "   "))
    }

    @Test fun rememberIsBounded() {
        var c = emptyList<String>()
        repeat(ModelCatalog.MAX_CUSTOM + 5) { c = ModelCatalog.remember(c, "model-$it") }
        assertEquals(ModelCatalog.MAX_CUSTOM, c.size)
        assertFalse("the oldest is dropped, not the newest", c.contains("model-0"))
    }
}
