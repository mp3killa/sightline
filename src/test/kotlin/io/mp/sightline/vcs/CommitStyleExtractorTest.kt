package io.mp.sightline.vcs

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitStyleExtractorTest {

    @Test fun pullsTheCommitSectionOutOfContributing() {
        val md = """
            # Contributing

            ## Setup
            Run the thing.

            ## Commit Messages
            Use imperative mood. Reference the issue number.

            ## Pull requests
            Open one.
        """.trimIndent()
        val s = CommitStyleExtractor.extract("CONTRIBUTING.md", md)!!
        assertTrue(s.contains("Commit Messages"))
        assertTrue(s.contains("imperative mood"))
        assertTrue("stops at the next section", !s.contains("Pull requests"))
        assertTrue("does not bleed the earlier section", !s.contains("Run the thing"))
    }

    @Test fun ignoresMarkdownWithNoCommitSection() {
        assertNull(CommitStyleExtractor.extract("README.md", "# Readme\n\nJust a project."))
    }

    @Test fun bareCommitHeadingWithNoBodyIsNotAMatch() {
        assertNull(CommitStyleExtractor.extract("CONTRIBUTING.md", "## Commit Messages\n"))
    }

    @Test fun gitmessageTemplateStripsCommentLines() {
        val tmpl = "# Subject line below\nfeat: \n# body guidance\n\nExplain why."
        val s = CommitStyleExtractor.extract(".gitmessage", tmpl)!!
        assertTrue(s.contains("feat:"))
        assertTrue(s.contains("Explain why."))
        assertTrue("comment lines are dropped", !s.contains("Subject line below"))
    }

    @Test fun commentOnlyGitmessageIsNull() {
        assertNull(CommitStyleExtractor.extract(".gitmessage", "# only\n# comments"))
    }

    @Test fun commitlintConfigImpliesConventionalCommits() {
        val s = CommitStyleExtractor.extract("commitlint.config.js", "module.exports = {}")!!
        assertTrue(s.contains("Conventional Commits"))
    }

    @Test fun longSectionsAreCapped() {
        val big = "## Commit messages\n" + "word ".repeat(2000)
        val s = CommitStyleExtractor.extract("CONTRIBUTING.md", big)!!
        assertTrue(s.length <= CommitStyleExtractor.MAX_SNIPPET)
    }
}
