package io.mp.sightline.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color

/**
 * A colour handed to a Swing component must keep tracking the theme.
 *
 * Swing stores the `Color` *object* a component was given (`background = …`, a border's colour) and only
 * re-installs it from the LaF when it is a `UIResource` — which a colour computed from `UIUtil` or
 * `EditorColorsManager` is not. So a token that snapshots at construction pins the panel to whatever
 * theme was current when it opened. [ClaudeUiTokens] therefore hands out colours that re-resolve on
 * every read, and this test holds one instance across a theme change to prove it really does.
 */
class DynamicTokenTest : BasePlatformTestCase() {

    fun testTokensReResolveAfterThemeChanges() {
        val mgr = EditorColorsManager.getInstance()
        val light = mgr.allSchemes.firstOrNull {
            !it.name.contains("Darcula", true) && !it.name.contains("Dark", true) &&
                !it.name.contains("High contrast", true)
        }
        val dark = mgr.allSchemes.firstOrNull {
            it.name.contains("Darcula", true) || it.name.contains("Dark", true)
        }
        if (light == null || dark == null) return

        mgr.setGlobalScheme(light)
        // Captured once, exactly as a component captures it at construction time.
        val surface = ClaudeUiTokens.surface()
        val elevated = ClaudeUiTokens.elevatedSurface()
        val lightSurface = rgb(surface)
        val lightElevated = rgb(elevated)

        mgr.setGlobalScheme(dark)

        assertTrue(
            "the light scheme must actually be lighter than the dark one, or this proves nothing " +
                "(light=$lightSurface)",
            lum(lightSurface) > 128,
        )
        assertTrue(
            "surface() captured under the light scheme still reports ${rgb(surface)} after switching to " +
                "$dark — a component holding it would stay on the old theme",
            lum(rgb(surface)) < 128,
        )
        assertTrue(
            "elevatedSurface() is derived from surface() and must re-derive too, not re-wrap a snapshot",
            lum(rgb(elevated)) < 128,
        )
        assertTrue("the derived surface must differ from the base", rgb(elevated) != rgb(surface))
        assertTrue("and it must have moved off its light value", rgb(elevated) != lightElevated)

        mgr.setGlobalScheme(light)
        assertEquals("and back again — this is a live read, not a one-way latch", lightSurface, rgb(surface))
    }

    private fun rgb(c: Color): Int = c.rgb
    private fun lum(rgb: Int): Double {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        return r * 0.299 + g * 0.587 + b * 0.114
    }
}
