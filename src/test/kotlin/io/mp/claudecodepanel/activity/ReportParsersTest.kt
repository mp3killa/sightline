package io.mp.claudecodepanel.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportParsersTest {

    @Test fun parsesJUnitXmlSummaryAndFailures() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="5" failures="1" errors="0" skipped="1">
              <testcase name="loads" classname="com.example.FooTest"/>
              <testcase name="saves" classname="com.example.FooTest">
                <failure message="expected true">assertion failed</failure>
              </testcase>
            </testsuite>
        """.trimIndent()
        val s = ReportParsers.parseJUnitXml(xml)!!
        assertEquals(3, s.passed) // 5 - 1 failed - 1 skipped
        assertEquals(1, s.failed)
        assertTrue(s.failedNames.any { it.contains("com.example.FooTest") && it.contains("saves") })
    }

    @Test fun sumsMultipleTestsuites() {
        val xml = """
            <testsuites>
              <testsuite tests="2" failures="0" errors="0" skipped="0"/>
              <testsuite tests="3" failures="1" errors="1" skipped="0"/>
            </testsuites>
        """.trimIndent()
        val s = ReportParsers.parseJUnitXml(xml)!!
        assertEquals(2, s.failed) // 1 failure + 1 error
        assertEquals(3, s.passed) // 5 total - 2 failed
    }

    @Test fun parsesDetektCheckstyleXml() {
        val xml = """
            <checkstyle version="4.3">
              <file name="app/src/main/Foo.kt">
                <error line="10" column="5" severity="warning" message="Magic number" source="detekt.MagicNumber"/>
                <error line="20" column="1" severity="error" message="Too long" source="detekt.MaxLineLength"/>
              </file>
            </checkstyle>
        """.trimIndent()
        val d = ReportParsers.parseCheckstyleXml(xml)
        assertEquals(2, d.size)
        assertEquals("app/src/main/Foo.kt", d[0].path)
        assertEquals(10, d[0].line)
        assertFalse(d[0].isError)
        assertTrue(d[1].isError)
    }

    @Test fun parsesAndroidLintXml() {
        val xml = """
            <issues format="6">
              <issue id="AllowBackup" severity="Warning" message="Missing attribute">
                <location file="src/main/AndroidManifest.xml" line="9" column="5"/>
              </issue>
              <issue id="HardcodedText" severity="Error" message="Hardcoded string">
                <location file="src/main/res/layout/a.xml" line="12"/>
              </issue>
            </issues>
        """.trimIndent()
        val d = ReportParsers.parseAndroidLintXml(xml)
        assertEquals(2, d.size)
        assertEquals("src/main/AndroidManifest.xml", d[0].path)
        assertEquals(9, d[0].line)
        assertFalse(d[0].isError)
        assertTrue(d[1].isError)
    }

    @Test fun parsesSarif() {
        val json = """
            {"version":"2.1.0","runs":[{"results":[
              {"ruleId":"MagicNumber","level":"warning","message":{"text":"Magic number 42"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"app/Foo.kt"},"region":{"startLine":10}}}]}
            ]}]}
        """.trimIndent()
        val d = ReportParsers.parseSarif(json)
        assertEquals(1, d.size)
        assertEquals("app/Foo.kt", d[0].path)
        assertEquals(10, d[0].line)
        assertFalse(d[0].isError)
    }

    @Test fun dispatchesByFileTypeAndContent() {
        assertTrue(ReportParsers.parseReportFile("TEST-Foo.xml", "<testsuite tests=\"1\" failures=\"0\"/>").tests != null)
        assertTrue(ReportParsers.parseReportFile("detekt.sarif", """{"runs":[{"results":[]}]}""").diagnostics.isEmpty())
        assertTrue(ReportParsers.parseReportFile("x.xml", "<issues><issue severity=\"Warning\" message=\"m\"><location file=\"A.kt\" line=\"1\"/></issue></issues>").diagnostics.size == 1)
    }

    @Test fun malformedInputIsSafe() {
        assertNull(ReportParsers.parseJUnitXml("not xml at all"))
        assertTrue(ReportParsers.parseCheckstyleXml("<broken").isEmpty())
        assertTrue(ReportParsers.parseSarif("{not json").isEmpty())
        assertTrue(ReportParsers.parseReportFile("empty.txt", "nothing structured here").isEmpty)
    }

    @Test fun rejectsDoctypeForXxeSafety() {
        // A DOCTYPE with an external/general entity must not be processed — the parser is configured to
        // disallow DOCTYPE, so this yields no data rather than resolving the entity.
        val evil = """
            <?xml version="1.0"?>
            <!DOCTYPE testsuite [ <!ENTITY x "boom"> ]>
            <testsuite tests="1" failures="0" errors="0" skipped="0"><name>&x;</name></testsuite>
        """.trimIndent()
        assertNull(ReportParsers.parseJUnitXml(evil))
    }
}
