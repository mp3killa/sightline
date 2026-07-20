package io.mp.sightline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpsysParserTest {

    /** Shape from a modern `dumpsys activity activities`. */
    private val activities = """
        Display #0 (activities from top to bottom):
          * Task{a1b2c3 #42 type=standard A=com.example.driver U=0 visible=true}
            * Hist  #1: ActivityRecord{d4e5f6 u0 com.example.driver/.MainActivity t42}
            * Hist  #0: ActivityRecord{7a8b9c u0 com.example.driver/.SplashActivity t42}
          ResumedActivity: ActivityRecord{d4e5f6 u0 com.example.driver/.MainActivity t42}
    """.trimIndent()

    @Test
    fun `the resumed activity and package are read`() {
        val s = DumpsysParser.parseActivities(activities)
        assertEquals("com.example.driver/.MainActivity", s.resumedActivity)
        assertEquals("com.example.driver", s.resumedPackage)
    }

    /** dumpsys lists a task oldest-first; a back stack reads front-first. */
    @Test
    fun `the back stack reads front first`() {
        val s = DumpsysParser.parseActivities(activities)
        assertEquals(
            listOf("com.example.driver/.MainActivity", "com.example.driver/.SplashActivity"),
            s.backStack,
        )
    }

    @Test
    fun `the older field names still parse`() {
        val old = "  mResumedActivity: ActivityRecord{aaa u0 com.example.driver/.MainActivity t1}"
        assertEquals("com.example.driver/.MainActivity", DumpsysParser.parseActivities(old).resumedActivity)

        val newer = "  topResumedActivity=ActivityRecord{bbb u0 com.example/.Home t2}"
        assertEquals("com.example/.Home", DumpsysParser.parseActivities(newer).resumedActivity)
    }

    /** A ScreenState with nulls is a useful answer; one with wrong values is worse than nothing. */
    @Test
    fun `unrecognised output yields nulls rather than guesses`() {
        val s = DumpsysParser.parseActivities("some entirely unexpected output")
        assertNull(s.resumedActivity)
        assertTrue(s.backStack.isEmpty())
        assertTrue(s.isEmpty)
    }

    // ---- window ----

    @Test
    fun `display size, density and rotation parse`() {
        val window = """
            Display: mDisplayId=0
              init=1080x2400 420dpi cur=1080x2400 app=1080x2274
              mCurrentRotation=ROTATION_0
              mBaseDisplayDensity=420
        """.trimIndent()
        val s = DumpsysParser.parseWindow(window)
        assertEquals(1080, s.screenWidthPx)
        assertEquals(2400, s.screenHeightPx)
        assertEquals(420, s.densityDpi)
        assertEquals(ScreenState.Orientation.PORTRAIT, s.orientation)
    }

    @Test
    fun `landscape rotations map to landscape`() {
        for (rotation in listOf(1, 3)) {
            val s = DumpsysParser.parseWindow("init=2400x1080 mCurrentRotation=ROTATION_$rotation")
            assertEquals(ScreenState.Orientation.LANDSCAPE, s.orientation)
        }
    }

    @Test
    fun `an absent rotation is null, not assumed portrait`() {
        assertNull(DumpsysParser.parseWindow("init=1080x2400 420dpi").orientation)
    }

    // ---- configuration ----

    @Test
    fun `night mode, font scale and locale parse`() {
        val config = "mLocales=[en_ZA] fontScale=1.15 uiMode=0x21 night-yes"
        val s = DumpsysParser.parseConfiguration(config)
        assertEquals(true, s.nightMode)
        assertEquals(1.15f, s.fontScale!!, 0.001f)
        assertEquals("en_ZA", s.locale)
    }

    @Test
    fun `the uimode command form parses`() {
        assertEquals(true, DumpsysParser.parseConfiguration("Night mode: yes").nightMode)
        assertEquals(false, DumpsysParser.parseConfiguration("Night mode: no").nightMode)
        assertNull(DumpsysParser.parseConfiguration("Night mode: auto").nightMode)
    }

    // ---- merge ----

    @Test
    fun `merging combines dumps and keeps the first non-null`() {
        val merged = DumpsysParser.merge(
            DumpsysParser.parseActivities(activities),
            DumpsysParser.parseWindow("init=1080x2400 420dpi mCurrentRotation=ROTATION_0"),
            DumpsysParser.parseConfiguration("Night mode: yes"),
        )
        assertEquals("com.example.driver/.MainActivity", merged.resumedActivity)
        assertEquals(1080, merged.screenWidthPx)
        assertEquals(true, merged.nightMode)
        assertEquals(2, merged.backStack.size)
    }

    @Test
    fun `merging nothing yields an empty state rather than throwing`() {
        assertTrue(DumpsysParser.merge().isEmpty)
        assertTrue(DumpsysParser.merge(ScreenState(), ScreenState()).isEmpty)
    }

    // ---- summary ----

    @Test
    fun `the summary names what is on screen`() {
        val merged = DumpsysParser.merge(
            DumpsysParser.parseActivities(activities),
            DumpsysParser.parseWindow("init=1080x2400 mCurrentRotation=ROTATION_0"),
            DumpsysParser.parseConfiguration("Night mode: yes fontScale=1.5"),
        )
        val summary = merged.summary()
        assertTrue(summary.contains("MainActivity"))
        assertTrue(summary.contains("1080×2400"))
        assertTrue(summary.contains("portrait"))
        assertTrue(summary.contains("dark"))
        assertTrue(summary.contains("1.5"))
    }

    /** A default font scale is not worth a segment; only a changed one is. */
    @Test
    fun `a normal font scale is omitted from the summary`() {
        val s = ScreenState(resumedActivity = "com.x/.Main", fontScale = 1.0f)
        assertEquals("Main", s.summary())
    }
}
