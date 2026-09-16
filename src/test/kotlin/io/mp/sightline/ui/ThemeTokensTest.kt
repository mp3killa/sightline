package io.mp.sightline.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import io.mp.sightline.theme.ClaudeUiTokens

/**
 * Theme-derived colours must **re-resolve on every read**, not hold the value they had when a
 * component was built.
 *
 * This is the standing rule behind `ClaudeUiTokens` handing out `JBColor.lazy` wrappers: Swing keeps
 * the `Color` *object* a component was given and only re-installs it from the LaF when it is a
 * `UIResource`, which a value computed from `UIUtil`/`EditorColorsManager` is not. A snapshot
 * therefore pins the panel to whatever theme was current when it opened.
 *
 * It replaces the theme half of the old map-chrome test, which asserted the same property through
 * the activity map's canvas. The map is gone; the rule is not.
 */
class ThemeTokensTest : BasePlatformTestCase() {

    fun testDerivedColoursFollowAThemeSwitchWithoutBeingRebuilt() {
        val wasDark = JBColor.isBright().not()
        try {
            JBColor.setDark(false)
            // Held exactly as a component holds it: one instance, read again after the switch.
            val surface = ClaudeUiTokens.surface()
            val border = ClaudeUiTokens.border()
            val light = surface.rgb to border.rgb

            JBColor.setDark(true)
            val dark = surface.rgb to border.rgb

            assertFalse(
                "a colour handed out once must re-resolve on read — these are the same instances, " +
                    "read again after the theme changed",
                light == dark,
            )
        } finally {
            JBColor.setDark(wasDark)
        }
    }
}
