package io.mp.sightline.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.ui.state.StatusKind
import java.awt.Color

/**
 * Single source of truth for the panel's theme-aware visual language: colours, spacing and corner
 * radii. Everything resolves lazily through IntelliJ theme APIs ([JBColor] / [UIUtil]) so it stays
 * correct in both light and dark themes and updates when the IDE theme changes. Spacing/radius
 * helpers run through [JBUI.scale] so they respect the IDE's presentation scale.
 *
 * Sightline's teal accent is intentionally reserved for a few "active/brand" signals (see [accent]);
 * everything else uses native surfaces/borders so the panel still reads as part of Android Studio.
 */
object ClaudeUiTokens {

    // ---- brand ----
    /**
     * Sightline's teal accent — the same colour as the sight line in the plugin icon. Use sparingly:
     * brand mark, focused composer, selected controls.
     *
     * Previously a warm orange, which was close enough to Anthropic's own palette to read as their
     * branding on a plugin that is explicitly not theirs. The teal matches the icon and belongs to
     * this product.
     */
    fun accent(): JBColor = JBColor(Color(0x0E, 0xA5, 0x99), Color(0x2C, 0xD4, 0xC6))

    // ---- surfaces ----
    fun surface(): Color = EditorColorsManager.getInstance().globalScheme.defaultBackground
    fun panel(): Color = UIUtil.getPanelBackground()
    /** A card/raised surface, a touch off the base background. */
    fun elevatedSurface(): Color = shift(surface(), dark = 14, light = -8)
    /** A barely-there fill for chips/inputs. */
    fun subtleSurface(): Color = shift(surface(), dark = 8, light = -5)
    /** A stronger raised surface for overlays/inspectors. */
    fun overlaySurface(): Color = shift(surface(), dark = 22, light = -14)

    fun border(): Color = JBColor.border()
    fun subtleBorder(): Color = JBColor(Color(0x00, 0x00, 0x00, 26), Color(0xFF, 0xFF, 0xFF, 26))

    // ---- text ----
    fun textPrimary(): Color = UIUtil.getLabelForeground()
    fun textSecondary(): Color = UIUtil.getContextHelpForeground()

    // ---- semantic (shared with the activity graph) ----
    fun success(): JBColor = JBColor(0x1F9D57, 0x54D98A)
    fun warning(): JBColor = JBColor(0xB08500, 0xE6C34D)
    fun error(): JBColor = JBColor(0xD1242F, 0xF66A63)
    fun info(): JBColor = JBColor(0x2F6FEB, 0x5B9BFF)

    // ---- spacing (scaled) ----
    fun xs() = JBUI.scale(4)
    fun sm() = JBUI.scale(6)
    fun md() = JBUI.scale(10)
    fun lg() = JBUI.scale(16)
    fun xl() = JBUI.scale(24)

    // ---- corner radii (scaled) ----
    fun radiusSm() = JBUI.scale(6)
    fun radiusMd() = JBUI.scale(10)
    fun radiusLg() = JBUI.scale(14)

    /** Shifts a colour lighter/darker depending on whether it is a dark or light base. */
    fun shift(base: Color, dark: Int, light: Int): Color {
        val amt = if (isDark(base)) dark else light
        return Color(clamp(base.red + amt), clamp(base.green + amt), clamp(base.blue + amt))
    }

    fun withAlpha(c: Color, alpha: Float): Color =
        Color(c.red, c.green, c.blue, (alpha.coerceIn(0f, 1f) * 255).toInt())

    fun isDark(c: Color = panel()): Boolean = (c.red * 0.299 + c.green * 0.587 + c.blue * 0.114) < 128

    /**
     * The colour a session state is drawn in — the header's state dot and the status strip's.
     *
     * Both used to carry their own identical copy of this mapping, which is one edit away from the two
     * disagreeing about what, say, PERMISSION looks like while both still compiling. Semantic
     * colour belongs to the token layer for the same reason the raw hexes do.
     */
    fun statusColor(kind: StatusKind): Color = when (kind) {
        StatusKind.READY -> textSecondary()
        StatusKind.WORKING, StatusKind.RUNNING -> accent()
        StatusKind.READING, StatusKind.SEARCHING, StatusKind.TESTING -> info()
        StatusKind.EDITING -> warning()
        StatusKind.SUCCESS, StatusKind.COMPLETED -> success()
        StatusKind.WARNING, StatusKind.PERMISSION -> warning()
        StatusKind.ERROR -> error()
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}
