package io.mp.claudecodepanel.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRefDetectorTest {

    @Test fun detectsFilesWithKnownExtensions() {
        val refs = FileRefDetector.detect("See CLAUDE.md and docs/ARCHITECTURE.md for details.")
        assertEquals(listOf("CLAUDE.md", "docs/ARCHITECTURE.md"), refs.map { it.path })
        assertTrue(refs.all { it.line == null })
    }

    @Test fun detectsLineSuffixes() {
        assertEquals(420, FileRefDetector.detect("look at Foo.kt:420 here").single().line)
        assertEquals(18, FileRefDetector.detect("src/main/Bar.kt#L18").single().line)
    }

    @Test fun ignoresVersionsUrlsAndAbbreviations() {
        assertTrue(FileRefDetector.detect("version 0.6.0 was released").isEmpty())
        assertTrue(FileRefDetector.detect("e.g. this and i.e. that").isEmpty())
        // A file component embedded in a URL (preceded by '/') must not be picked up.
        assertTrue(FileRefDetector.detect("see https://site.com/README.md online").isEmpty())
    }

    @Test fun hrefRoundTrips() {
        assertEquals("file:Foo.kt:42", FileRefDetector.detect("Foo.kt:42").single().href())
        assertEquals("file:docs/X.md", FileRefDetector.detect("docs/X.md").single().href())
    }

    @Test fun linkifyOnlyWhenResolverConfirms() {
        val blocks = MarkdownDocParser.parse("Open CLAUDE.md and also Missing.kt now.")
        val linked = FileRefDetector.linkify(blocks) { it == "CLAUDE.md" }
        val links = (linked.single() as MdParagraph).inlines.filterIsInstance<MdLink>()
        assertEquals(1, links.size)
        assertEquals("file:CLAUDE.md", links.single().href)
        assertEquals("CLAUDE.md", links.single().inlines.plainText())
    }

    @Test fun linkifyLeavesInlineCodeAndPlainTextIntact() {
        val blocks = MarkdownDocParser.parse("Use `Foo.kt` in prose but not here.")
        val linked = FileRefDetector.linkify(blocks) { true }
        assertTrue((linked.single() as MdParagraph).inlines.none { it is MdLink })
    }

    @Test fun linkifyPreservesSurroundingText() {
        val blocks = MarkdownDocParser.parse("Before CLAUDE.md after.")
        val inlines = (FileRefDetector.linkify(blocks) { true }.single() as MdParagraph).inlines
        assertEquals("Before CLAUDE.md after.", inlines.plainText())
        assertTrue(inlines.any { it is MdLink })
    }
}
