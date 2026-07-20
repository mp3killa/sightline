package io.mp.sightline.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathDisplayTest {

    private val base = "/Users/devuser/AndroidStudioProjects/MyApplication"
    private val home = "/Users/devuser"

    @Test
    fun `in-project path loses the prefix every other row repeats`() {
        assertEquals(
            "app/src/main/java/com/example/myapplication/MainActivity.kt",
            PathDisplay.relativize("$base/app/src/main/java/com/example/myapplication/MainActivity.kt", base),
        )
    }

    @Test
    fun `a path outside the project keeps its own shape`() {
        assertEquals("/etc/hosts", PathDisplay.relativize("/etc/hosts", base))
    }

    @Test
    fun `a sibling directory sharing the prefix is not treated as inside`() {
        val sibling = "${base}Other/build.gradle.kts"
        assertEquals(sibling, PathDisplay.relativize(sibling, base))
    }

    @Test
    fun `a trailing separator on the base does not survive into the result`() {
        assertEquals("build.gradle.kts", PathDisplay.relativize("$base/build.gradle.kts", "$base/"))
    }

    @Test
    fun `no project means no relativising`() {
        assertEquals("/a/b.kt", PathDisplay.relativize("/a/b.kt", null))
        assertEquals("/a/b.kt", PathDisplay.relativize("/a/b.kt", ""))
    }

    @Test
    fun `home outside the project collapses to tilde`() {
        assertEquals("~/notes/todo.md", PathDisplay.homeRelative("/Users/devuser/notes/todo.md", home))
        assertEquals("/opt/tool", PathDisplay.homeRelative("/opt/tool", home))
    }

    @Test
    fun `a path within budget is left exactly alone`() {
        val short = "app/src/Main.kt"
        assertEquals(short, PathDisplay.elide(short, 72))
    }

    @Test
    fun `elision cuts at a separator, never mid-segment`() {
        val long = "/Users/devuser/AndroidStudioProjects/MyApplication/gradle/libs.versions.toml"
        val out = PathDisplay.elide(long, 40)

        assertTrue(out.length <= 40)
        assertTrue("kept the filename whole: $out", out.endsWith("libs.versions.toml"))
        // The regression this exists for: a plain character cut yields "…s/devuser/…", which reads as
        // damage rather than abbreviation. Whatever follows the ellipsis must be a whole segment.
        assertTrue("cut at a separator: $out", out.startsWith("…/"))
        assertTrue("every kept segment is whole: $out", long.endsWith(out.removePrefix("…/")))
    }

    @Test
    fun `the filename survives even when the budget is tight`() {
        val out = PathDisplay.elide("/a/very/deeply/nested/tree/Thing.kt", 14)
        assertEquals("…/Thing.kt", out)
    }

    @Test
    fun `a filename that cannot fit is elided inside itself, keeping its extension`() {
        val name = "AbsurdlyLongGeneratedFileNameThatGoesOnForever.kt"
        val out = PathDisplay.elide("/some/dir/$name", 20)

        assertTrue(out.length <= 20)
        assertTrue("kept the extension: $out", out.endsWith(".kt"))
        assertTrue("kept the head: $out", out.startsWith("Absurdly"))
        assertFalse("no separator prefix when only the name survives: $out", out.startsWith("…/"))
    }

    @Test
    fun `elision never returns something longer than it was given`() {
        val paths = listOf(
            "/Users/devuser/AndroidStudioProjects/MyApplication/app/build.gradle.kts",
            "/a/b",
            "/aaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "relative/path/File.kt",
            "NoSeparatorsAtAllInThisOne.kt",
        )
        for (p in paths) {
            for (max in 4..80) {
                val out = PathDisplay.elide(p, max)
                assertTrue("$p @ $max produced ${out.length} chars: $out", out.length <= maxOf(max, p.length))
                assertTrue("$p @ $max exceeded the budget: $out", out.length <= max || p.length <= max)
            }
        }
    }

    @Test
    fun `display prefers the project prefix over the home prefix`() {
        // Both prefixes match a file inside a project under home; the project-relative form is the one
        // that tells the reader something they don't already know.
        val out = PathDisplay.display("$base/app/build.gradle.kts", base, home)
        assertEquals("app/build.gradle.kts", out)
    }

    @Test
    fun `display falls back to tilde for a file outside the project`() {
        assertEquals("~/Downloads/x.txt", PathDisplay.display("/Users/devuser/Downloads/x.txt", base, home))
    }

    @Test
    fun `display is empty for no path`() {
        assertEquals("", PathDisplay.display(null, base, home))
        assertEquals("", PathDisplay.display("", base, home))
    }

    @Test
    fun `the screenshot case reads as a path instead of as damage`() {
        // Straight from a live session: three rows whose absolute forms differed only in the tail.
        val touched = listOf(
            "$base/app/src/main/java/com/example/myapplication/MainActivity.kt",
            "$base/app/build.gradle.kts",
            "$base/gradle/libs.versions.toml",
        )
        val shown = touched.map { PathDisplay.display(it, base, home) }

        assertEquals(
            listOf(
                "app/src/main/java/com/example/myapplication/MainActivity.kt",
                "app/build.gradle.kts",
                "gradle/libs.versions.toml",
            ),
            shown,
        )
        assertTrue("all fit without eliding at all", shown.none { it.contains("…") })
    }
}
