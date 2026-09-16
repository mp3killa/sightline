package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every interesting case here is a **rejection**. A completion popup that opens on the `@` in an email
 * address, or mid-word, is the kind of thing that makes people stop typing in a box.
 */
class MentionQueryTest {

    private fun at(text: String) = MentionQuery.at(text, text.length)

    @Test fun `an at sign starting a word opens a query`() {
        assertEquals(MentionQuery.Query(0, ""), at("@"))
        assertEquals(MentionQuery.Query(5, "Claude"), at("look @Claude"))
        assertEquals(MentionQuery.Query(0, "src/Foo.kt"), at("@src/Foo.kt"))
    }

    @Test fun `an at sign mid-word is not a reference`() {
        assertNull("an email address must not open a file popup", at("mail me@example.com"))
        assertNull(at("list@2x.png"))
        assertNull(at("v1.2@beta"))
    }

    @Test fun `the query ends at whitespace, so a finished reference is not still being typed`() {
        assertNull(at("@src/Foo.kt and then what"))
        assertNull(at("no mention here"))
        assertNull(at(""))
    }

    @Test fun `an at sign after a bracket still counts`() {
        assertEquals(MentionQuery.Query(1, "Foo"), at("(@Foo"))
        assertEquals(MentionQuery.Query(1, "Foo"), at("[@Foo"))
    }

    @Test fun `an implausibly long prefix stops being a filename`() {
        val long = "@" + "x".repeat(MentionQuery.MAX_PREFIX + 1)
        assertNull(at(long))
    }

    @Test fun `a caret inside the text queries only up to the caret`() {
        val text = "see @Cla and more"
        assertEquals(MentionQuery.Query(4, "Cla"), MentionQuery.at(text, 8))
    }

    /**
     * Ranking is the difference between a useful popup and a list. Typing `Claude` should offer
     * `ClaudePanel.kt`, not the forty paths that merely contain the word.
     */
    @Test fun `a filename match outranks a path match`() {
        val candidates = listOf(
            "docs/claude-notes.md",
            "src/main/kotlin/io/mp/sightline/ui/ClaudePanel.kt",
            "src/claude/other/Unrelated.kt",
        )
        val ranked = MentionQuery.rank(candidates, "ClaudeP")
        assertEquals("src/main/kotlin/io/mp/sightline/ui/ClaudePanel.kt", ranked.first())
    }

    @Test fun `a prefix match outranks a contained one, and shorter paths win ties`() {
        val candidates = listOf("app/src/DeepFoo.kt", "Foo.kt", "lib/Foo.kt")
        val ranked = MentionQuery.rank(candidates, "Foo")
        assertEquals(listOf("Foo.kt", "lib/Foo.kt", "app/src/DeepFoo.kt"), ranked)
    }

    @Test fun `non-matches are dropped and the limit is honoured`() {
        val candidates = (1..50).map { "src/File$it.kt" } + listOf("README.md")
        val ranked = MentionQuery.rank(candidates, "File", limit = 5)
        assertEquals(5, ranked.size)
        assertTrue(ranked.none { it == "README.md" })
    }

    @Test fun `completion ends with a space so the next word does not join the reference`() {
        assertEquals("@src/Foo.kt ", MentionQuery.completion("src/Foo.kt"))
    }
}
