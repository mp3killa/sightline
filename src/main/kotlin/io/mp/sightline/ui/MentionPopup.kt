package io.mp.sightline.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.theme.ClaudeUiTokens
import io.mp.sightline.ui.state.PathDisplay
import java.awt.Component
import java.awt.event.KeyEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * The file list that appears while you type an `@` reference in the composer.
 *
 * Deliberately **not** focus-stealing: the popup is shown without requesting focus and the composer
 * keeps the caret, so typing continues to narrow the list rather than being swallowed by it. That is
 * why [handleKey] exists — the composer forwards the navigation keys here while the popup is up,
 * instead of the popup taking the keyboard. A completion UI that moves the caret out of the text you
 * are writing is the thing people complain about in every editor that gets this wrong.
 *
 * Ranking and query parsing live in the platform-free `MentionQuery`; this is only the Swing half.
 */
class MentionPopup(private val onChoose: (String) -> Unit) {

    private val model = DefaultListModel<String>()
    private val list = JBList(model)
    private var popup: JBPopup? = null

    init {
        list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = VISIBLE_ROWS
        list.font = UIUtil.getLabelFont()
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                l: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(l, value, index, selected, focused)
                // Project-relative and shortened at segment boundaries — the same rule the tool rows
                // use, so a deep path stays recognisable by its filename rather than its prefix.
                text = PathDisplay.elide(value as? String ?: "", MAX_PATH_CHARS)
                border = JBUI.Borders.empty(2, 6)
                if (!selected) foreground = ClaudeUiTokens.textPrimary()
                return c
            }
        }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount >= 1) chooseSelected()
            }
        })
    }

    /** Shows or updates the popup under [anchor]'s caret. Never takes focus away from the composer. */
    fun show(anchor: JTextArea, paths: List<String>) {
        model.clear()
        paths.forEach { model.addElement(it) }
        list.selectedIndex = 0

        val existing = popup
        if (existing != null && existing.isVisible) { list.revalidate(); list.repaint(); return }

        val built = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(list, null)
            .setRequestFocus(false)          // the caret stays in the composer; typing keeps narrowing
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
        popup = built
        val where = runCatching { anchor.modelToView2D(anchor.caretPosition) }.getOrNull()
        val point = if (where != null) {
            java.awt.Point(where.x.toInt(), (where.y + where.height).toInt() + JBUI.scale(4))
        } else {
            java.awt.Point(0, anchor.height)
        }
        built.show(com.intellij.ui.awt.RelativePoint(anchor, point))
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }

    private fun isShowing(): Boolean = popup?.isVisible == true

    /**
     * Handles a key on the composer's behalf while the popup is up. Returns true when the key was
     * consumed, which is the composer's signal not to treat it as ordinary input — Enter in particular,
     * which would otherwise send the message instead of accepting the highlighted file.
     */
    fun handleKey(e: KeyEvent): Boolean {
        if (!isShowing() || model.isEmpty) return false
        when (e.keyCode) {
            KeyEvent.VK_DOWN -> move(1)
            KeyEvent.VK_UP -> move(-1)
            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> chooseSelected()
            KeyEvent.VK_ESCAPE -> hide()
            else -> return false
        }
        e.consume()
        return true
    }

    private fun move(delta: Int) {
        val next = (list.selectedIndex + delta).coerceIn(0, model.size() - 1)
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    private fun chooseSelected() {
        val path = list.selectedValue ?: return
        // Off the current event so the popup is gone before the document changes underneath it.
        SwingUtilities.invokeLater { onChoose(path) }
    }

    private companion object {
        const val VISIBLE_ROWS = 8
        const val MAX_PATH_CHARS = 64
    }
}
