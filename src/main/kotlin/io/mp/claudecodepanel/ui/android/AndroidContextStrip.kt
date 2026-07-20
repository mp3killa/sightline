package io.mp.claudecodepanel.ui.android

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.android.AndroidContext
import io.mp.claudecodepanel.android.AndroidContextFormatter
import io.mp.claudecodepanel.android.ContextChipKind
import io.mp.claudecodepanel.theme.ClaudeUiTokens
import io.mp.claudecodepanel.ui.A11yNames
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The one-line Android state above the composer:
 * `app · demoStagingDebug  |  Pixel 8 · API 35 · ready  |  RouteDetailsScreen.kt`
 *
 * Deliberately quiet — secondary colour, small font, no border. It is orientation, not a control panel,
 * and it sits in the space directly above the thing the user is typing into, which is the most expensive
 * real estate in the tool window. It **hides itself entirely** outside an Android project rather than
 * rendering an empty frame.
 *
 * Clicking opens the menu that makes it a control: toggle which facts are sent, or force a refresh.
 * The chips themselves live in the composer's chip row (they need to sit alongside file attachments);
 * this row is the summary and the way back to a chip you removed.
 */
class AndroidContextStrip(
    private val onToggleChip: (ContextChipKind, Boolean) -> Unit,
    private val isChipEnabled: (ContextChipKind) -> Boolean,
    private val onRefresh: () -> Unit,
) : JPanel(BorderLayout()) {

    private var context: AndroidContext = AndroidContext.NOT_ANDROID

    private val label = JBLabel("").apply {
        font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, JBUI.scaleFontSize(10.5f).toFloat())
        foreground = ClaudeUiTokens.textSecondary()
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 3, 2)
        add(label, BorderLayout.WEST)
        isVisible = false

        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val open = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = showMenu(this@AndroidContextStrip)
        }
        addMouseListener(open)
        label.addMouseListener(open)

        // Named for UI automation, kept out of the visible text (see ui/A11yNames).
        // `getAccessibleContext()`, not `accessibleContext`: in Kotlin the latter resolves to
        // JComponent's inherited protected *field*, which is null until the getter lazily creates it —
        // a documented trap in CLAUDE.md that NPEs on every panel open.
        getAccessibleContext().accessibleName = A11yNames.ANDROID_CONTEXT_STRIP
    }

    fun update(context: AndroidContext) {
        this.context = context
        // Visibility is decided from the *unbudgeted* strip: whether there is anything to say at all is
        // a property of the context, not of how wide the panel happens to be right now.
        val hasAnything = AndroidContextFormatter.strip(context).isNotEmpty()
        toolTipText = AndroidContextFormatter.stripTooltip(context)
        isVisible = hasAnything
        label.text = AndroidContextFormatter.strip(context, budgetChars())
        revalidate()
        repaint()
    }

    /**
     * Fits the text to the width the strip has actually been given.
     *
     * Driven from layout rather than from a `componentResized` listener on purpose: a resize event is
     * only delivered to a component in a realised hierarchy, so the listener version silently did
     * nothing in the headless preview — and, more importantly, on the first real layout pass, where the
     * width goes 0 → N without an intervening event. `doLayout` always runs.
     */
    override fun doLayout() {
        val fitted = AndroidContextFormatter.strip(context, budgetChars())
        // Only touch the label when the text actually changes; assigning it unconditionally inside a
        // layout pass invalidates the tree and can re-enter layout.
        if (fitted != label.text) label.text = fitted
        super.doLayout()
    }

    /**
     * How many characters fit, from the real font metrics rather than a guessed average. Uses the width
     * of `0` as the reference glyph — the font is proportional, so this is an estimate, but it errs
     * toward dropping a segment early rather than clipping one, which is the direction that fails safe.
     */
    private fun budgetChars(): Int {
        val usable = width - insets.left - insets.right
        if (usable <= 0) return Int.MAX_VALUE // not laid out yet — render in full, resize will correct it
        val glyph = getFontMetrics(label.font).charWidth('0').coerceAtLeast(1)
        return usable / glyph
    }

    private fun showMenu(anchor: JComponent) {
        val group = DefaultActionGroup()
        group.add(Separator.create("Send with each message"))
        for (kind in AndroidContextFormatter.availableChips(context)) {
            group.add(toggle(kind))
        }
        group.add(Separator.create())
        group.add(object : AnAction("Refresh Android context") {
            override fun actionPerformed(e: AnActionEvent) = onRefresh()
        })

        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "Android context", group, dataContext(anchor),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
            )
            .showUnderneathOf(anchor)
    }

    /**
     * A checkable item per fact. [Toggleable.setSelected] on the presentation is what draws the tick, so
     * the menu shows current state rather than just offering an action — the user needs to see which
     * facts are already going out before deciding what to remove.
     */
    private fun toggle(kind: ContextChipKind): AnAction {
        val value = AndroidContextFormatter.chipLabel(kind, context)
        return object : AnAction("${kind.label}: $value") {
            override fun update(e: AnActionEvent) {
                Toggleable.setSelected(e.presentation, isChipEnabled(kind))
            }

            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

            override fun actionPerformed(e: AnActionEvent) = onToggleChip(kind, !isChipEnabled(kind))
        }
    }

    private fun dataContext(anchor: JComponent) =
        com.intellij.openapi.actionSystem.impl.SimpleDataContext.getSimpleContext(
            com.intellij.openapi.actionSystem.PlatformDataKeys.CONTEXT_COMPONENT, anchor,
        )

    /** Unused today, kept so the strip can host an action button without re-plumbing. */
    @Suppress("unused")
    private fun actionManager() = ActionManager.getInstance()
}
