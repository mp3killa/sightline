package io.mp.sightline.ui.state

/**
 * How large the conversation's text is, as a percentage of the IDE's own font.
 *
 * The panel followed the IDE's label font exactly, which sounds right and is wrong for the one thing
 * this window is for: a chat column is read like prose, not scanned like a tree view, and the size
 * that suits a project pane at arm's length is not the size that suits a paragraph. It is also the
 * single most-requested missing control in the equivalent VS Code panel after context usage — and the
 * complaint is invariably about *reading*, not about the UI chrome.
 *
 * So this scales the **conversation** only: message text, code fences, tool output. The header, the
 * composer chrome and the status strip stay on the IDE's own metrics, because those are chrome and
 * should match the rest of the IDE.
 *
 * The bounds are not arbitrary. Below [MIN] the text is smaller than the IDE's own minimum readable
 * size and the panel starts lying about its reading width; above [MAX] a code fence wraps so hard on a
 * docked panel that a diff becomes unreadable — the feature would be defeating the layout work it sits
 * on top of.
 */
object TextScale {

    const val MIN = 80
    const val MAX = 200
    const val DEFAULT = 100

    /** The offered steps. A free-text percentage invites 137% and the support question that follows. */
    val STEPS = listOf(80, 90, 100, 110, 125, 150, 175, 200)

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)

    /** Applies the scale to a font size, never returning something Swing will render as nothing. */
    fun size(baseSize: Float, percent: Int): Float = (baseSize * clamp(percent) / 100f).coerceAtLeast(6f)

    /** `100` → "100% (IDE default)", so the neutral value is recognisable as neutral. */
    fun label(percent: Int): String =
        if (clamp(percent) == DEFAULT) "$DEFAULT% (IDE default)" else "${clamp(percent)}%"

    /** Whether a scale is worth mentioning anywhere — i.e. anything but the neutral default. */
    fun isCustom(percent: Int): Boolean = clamp(percent) != DEFAULT
}
