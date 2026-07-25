package io.mp.sightline.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitMessagePromptTest {

    @Test fun buildEmbedsTheDiffAndTheRules() {
        val p = CommitMessagePrompt.build("diff --git a/x b/x\n+hello")
        assertTrue(p.contains("+hello"))
        assertTrue(p.contains("imperative"))
        assertTrue(p.contains("Output ONLY the commit message"))
    }

    @Test fun extraInstructionsAreIncludedOnlyWhenPresent() {
        assertFalse(CommitMessagePrompt.build("d").contains("style guidance"))
        assertTrue(CommitMessagePrompt.build("d", "Use Conventional Commits").contains("Use Conventional Commits"))
    }

    @Test fun longDiffsAreTruncatedWithANote() {
        val big = "x".repeat(CommitMessagePrompt.MAX_DIFF_CHARS + 500)
        val (trimmed, truncated) = CommitMessagePrompt.truncate(big)
        assertTrue(truncated)
        assertEquals(CommitMessagePrompt.MAX_DIFF_CHARS, trimmed.length)
        assertTrue(CommitMessagePrompt.build(big).contains("truncated"))
    }

    @Test fun shortDiffsAreNotTruncated() {
        val (_, truncated) = CommitMessagePrompt.truncate("small")
        assertFalse(truncated)
        assertFalse(CommitMessagePrompt.build("small").contains("truncated"))
    }

    @Test fun cleanStripsCodeFences() {
        assertEquals("Fix the parser", CommitMessagePrompt.clean("```\nFix the parser\n```"))
        assertEquals("Fix the parser", CommitMessagePrompt.clean("```text\nFix the parser\n```"))
    }

    @Test fun cleanStripsSurroundingQuotes() {
        assertEquals("Add mermaid rendering", CommitMessagePrompt.clean("\"Add mermaid rendering\""))
    }

    @Test fun cleanKeepsAMultilineBody() {
        val msg = "Add commit-message generation\n\nDraft a message from the staged diff via a fast model."
        assertEquals(msg, CommitMessagePrompt.clean(msg))
    }

    @Test fun cleanTrimsBlankEdges() {
        assertEquals("Tidy up", CommitMessagePrompt.clean("\n\n  Tidy up  \n\n"))
    }
}
