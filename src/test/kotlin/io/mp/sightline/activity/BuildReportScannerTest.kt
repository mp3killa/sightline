package io.mp.sightline.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class BuildReportScannerTest {

    private lateinit var root: File
    private val scanner = BuildReportScanner()
    private val now = Instant.parse("2026-07-19T12:00:00Z")

    @Before fun setUp() {
        root = Files.createTempDirectory("brs").toFile()
    }

    private fun write(rel: String, content: String, lastModified: Long = System.currentTimeMillis()): File {
        val f = File(root, rel)
        f.parentFile.mkdirs()
        f.writeText(content)
        f.setLastModified(lastModified)
        return f
    }

    @Test fun readsFreshTestAndAnalysisReports() {
        write(
            "app/build/test-results/testDebugUnitTest/TEST-FooTest.xml",
            """<testsuite tests="2" failures="1" errors="0" skipped="0">
                 <testcase name="a" classname="FooTest"/>
                 <testcase name="b" classname="FooTest"><failure message="x"/></testcase>
               </testsuite>""",
        )
        write(
            "app/build/reports/detekt/detekt.xml",
            """<checkstyle><file name="app/src/main/Foo.kt">
                 <error line="10" severity="warning" message="Magic number"/></file></checkstyle>""",
        )

        val events = scanner.scan(root, "./gradlew testDebugUnitTest detekt", System.currentTimeMillis() - 5000, now)

        val test = events.filterIsInstance<TestReported>().single()
        assertEquals(1, test.passed)
        assertEquals(1, test.failed)
        // Paths are normalised to absolute so a finding attaches to the same node an edit would create.
        val expected = File(root, "app/src/main/Foo.kt").canonicalPath
        assertTrue(events.filterIsInstance<WarningObserved>().any { it.path == expected })
    }

    @Test fun ignoresStaleReportsFromEarlierRuns() {
        write(
            "app/build/test-results/test/TEST-Old.xml",
            """<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase name="x" classname="Old"><failure/></testcase></testsuite>""",
            lastModified = System.currentTimeMillis() - 3_600_000, // an hour ago
        )
        val events = scanner.scan(root, "./gradlew test", System.currentTimeMillis() - 5000, now)
        assertTrue(events.isEmpty())
    }

    @Test fun doesNotScanForNonBuildCommands() {
        write("app/build/test-results/test/TEST-Foo.xml", """<testsuite tests="1" failures="0" errors="0" skipped="0"/>""")
        assertTrue(scanner.scan(root, "ls -la", System.currentTimeMillis() - 5000, now).isEmpty())
        assertTrue(scanner.scan(root, "git status", System.currentTimeMillis() - 5000, now).isEmpty())
    }

    @Test fun prefersXmlOverSiblingSarifAndNormalisesPaths() {
        // Android lint writes the same run as lint-results-debug.{xml,sarif}; the XML uses a
        // project-relative path, the SARIF an absolute one and a different line — reading both would
        // double-report. Verified against real lint output: the SARIF sibling is dropped, the
        // relative path is resolved to absolute so the finding attaches to one file node.
        write(
            "app/build/reports/lint-results-debug.xml",
            """<issues><issue severity="Warning" message="Redundant label">
                 <location file="app/src/main/AndroidManifest.xml" line="5"/></issue></issues>""",
        )
        write(
            "app/build/reports/lint-results-debug.sarif",
            """{"runs":[{"results":[{"level":"warning","message":{"text":"Redundant label"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"/abs/AndroidManifest.xml"},"region":{"startLine":9}}}]}]}]}""",
        )
        val events = scanner.scan(root, "./gradlew lintDebug", System.currentTimeMillis() - 5000, now)
        val warns = events.filterIsInstance<WarningObserved>()
        assertEquals(1, warns.size)
        assertEquals(File(root, "app/src/main/AndroidManifest.xml").canonicalPath, warns[0].path)
    }

    @Test fun ignoresNonReportFilesAndPrunedDirs() {
        // A source file and a build/intermediates file must never be treated as reports.
        write("app/src/main/Foo.xml", "<testsuite tests=\"9\" failures=\"9\" errors=\"0\" skipped=\"0\"/>")
        write("app/build/intermediates/junk/TEST-x.xml", "<testsuite tests=\"9\" failures=\"9\" errors=\"0\" skipped=\"0\"/>")
        val events = scanner.scan(root, "./gradlew test", System.currentTimeMillis() - 5000, now)
        assertTrue(events.isEmpty())
    }
}
