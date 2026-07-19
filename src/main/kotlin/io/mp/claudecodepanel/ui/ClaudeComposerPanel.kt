package io.mp.claudecodepanel.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.theme.ClaudeIcons
import io.mp.claudecodepanel.theme.ClaudeUiTokens
import io.mp.claudecodepanel.ui.components.ContextChip
import io.mp.claudecodepanel.ui.components.IconActionButton
import io.mp.claudecodepanel.ui.state.ComposerModel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The bottom composer: a growing 2-row input (Enter sends, Shift+Enter newlines), removable
 * attached-context chips, a left action group (attach / slash actions), and a right group with a
 * concise permission-mode chip and one coordinated send/stop button. No decorative/unavailable
 * controls.
 */
class ClaudeComposerPanel(
    private val model: ComposerModel,
    private val onSend: (String) -> Unit,
    private val onStop: () -> Unit,
    private val onAttach: () -> Unit,
    private val onSlash: (Component) -> Unit,
    private val onModeMenu: (Component) -> Unit,
) : JPanel(BorderLayout()) {

    private val box = ComposerBox()
    private val chipsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2)))
    private val input = JBTextArea(2, 20)
    private val inputScroll = JBScrollPane(input, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
    private val modeChip = JButton()
    private val sendButton = SendButton()

    private val minRows = 2
    private val maxRows = 8

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 10, 10, 10)
        box.layout = BorderLayout()
        box.border = JBUI.Borders.empty(6, 10, 6, 8)

        chipsRow.isOpaque = false
        chipsRow.isVisible = false
        box.add(chipsRow, BorderLayout.NORTH)

        input.isOpaque = false
        input.lineWrap = true
        input.wrapStyleWord = true
        input.border = JBUI.Borders.empty(2, 2)
        input.font = UIUtil.getLabelFont()
        input.emptyText.text = "Ask Claude about this project…"
        input.toolTipText = "Enter to send · Shift+Enter for a new line"
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) { e.consume(); trySend() }
            }
        })
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent) = onTextChanged()
        })
        input.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { box.focused = true; box.repaint() }
            override fun focusLost(e: FocusEvent) { box.focused = false; box.repaint() }
        })
        inputScroll.isOpaque = false
        inputScroll.viewport.isOpaque = false
        inputScroll.border = JBUI.Borders.empty()
        box.add(inputScroll, BorderLayout.CENTER)

        box.add(buildActionRow(), BorderLayout.SOUTH)
        add(box, BorderLayout.CENTER)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = adjustHeight()
        })
        SwingUtilities.invokeLater { adjustHeight(); updateSendEnabled() }
    }

    private fun buildActionRow(): JComponent {
        val row = JPanel(BorderLayout())
        row.isOpaque = false
        row.border = JBUI.Borders.emptyTop(6)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0))
        left.isOpaque = false
        left.add(IconActionButton(ClaudeIcons.attach, "Attach a file as context") { onAttach() })
        var slashRef: IconActionButton? = null
        val slashButton = IconActionButton(ClaudeIcons.slash, "Add context / actions") { slashRef?.let { onSlash(it) } }
        slashRef = slashButton
        left.add(slashButton)
        row.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        right.isOpaque = false
        styleModeChip()
        right.add(modeChip)
        right.add(sendButton)
        row.add(right, BorderLayout.EAST)
        return row
    }

    private fun styleModeChip() {
        modeChip.isContentAreaFilled = false
        modeChip.isFocusPainted = false
        modeChip.isOpaque = false
        modeChip.icon = ClaudeIcons.chevronDown.withSize(12)
        modeChip.horizontalTextPosition = JButton.LEFT
        modeChip.iconTextGap = JBUI.scale(3)
        modeChip.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(12f).toFloat())
        modeChip.border = JBUI.Borders.empty(3, 9)
        modeChip.toolTipText = "Permission mode"
        modeChip.addActionListener { onModeMenu(modeChip) }
    }

    // ---- public API ----

    fun inputComponent(): JComponent = input
    fun requestInputFocus() { input.requestFocusInWindow() }
    fun currentText(): String = input.text
    fun clearInput() { input.text = "" }

    fun insertContextText(text: String) {
        input.insert(text, input.caretPosition.coerceIn(0, input.document.length))
        input.requestFocusInWindow()
    }

    fun setRunning(running: Boolean) {
        model.running = running
        sendButton.running = running
        sendButton.toolTipText = if (running) "Stop Claude" else "Send"
        sendButton.getAccessibleContext().accessibleName = if (running) "Stop Claude" else "Send message"
        updateSendEnabled()
        sendButton.repaint()
    }

    fun setMode(shortName: String, dangerous: Boolean) {
        modeChip.text = shortName
        modeChip.icon = ClaudeIcons.chevronDown.withSize(12).withColor {
            if (dangerous) ClaudeUiTokens.warning() else ClaudeUiTokens.textSecondary()
        }
        modeChip.foreground = if (dangerous) ClaudeUiTokens.warning() else ClaudeUiTokens.textPrimary()
        modeChip.border = JBUI.Borders.empty(3, 9)
        modeChip.repaint()
    }

    fun refreshChips() {
        chipsRow.removeAll()
        for (path in model.attachments) {
            chipsRow.add(ContextChip(path, basename(path)) { removed ->
                model.removeAttachment(removed)
                refreshChips()
            })
        }
        chipsRow.isVisible = model.hasAttachments
        chipsRow.revalidate(); chipsRow.repaint()
        revalidate(); repaint()
    }

    // ---- internals ----

    private fun trySend() {
        val text = input.text
        if (model.sendEnabled(text)) onSend(text)
    }

    private fun onTextChanged() {
        updateSendEnabled()
        adjustHeight()
    }

    private fun updateSendEnabled() {
        sendButton.isEnabled = model.running || model.sendEnabled(input.text)
    }

    private fun adjustHeight() {
        val fm = input.getFontMetrics(input.font)
        val rowH = fm.height
        val content = input.preferredSize.height
        val minH = rowH * minRows
        val maxH = rowH * maxRows
        val target = content.coerceIn(minH, maxH)
        inputScroll.preferredSize = Dimension(JBUI.scale(10), target + JBUI.scale(6))
        inputScroll.verticalScrollBarPolicy =
            if (content > maxH) ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS else ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        inputScroll.revalidate()
        revalidate()
    }

    private fun basename(path: String): String {
        val p = path.replace('\\', '/').trimEnd('/')
        val slash = p.lastIndexOf('/')
        return if (slash >= 0) p.substring(slash + 1) else p
    }

    /** Rounded composer surface that highlights its border while the input is focused. */
    private inner class ComposerBox : JPanel() {
        var focused = false
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = ClaudeUiTokens.radiusLg()
            g2.color = ClaudeUiTokens.subtleSurface()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = if (focused) ClaudeUiTokens.accent() else ClaudeUiTokens.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    /** One coordinated send/stop control: send (disabled when empty) or stop while running. */
    private inner class SendButton : JButton() {
        var running = false
        private val sendIcon: Icon = ClaudeIcons.send.withSize(16).withColor { Color.WHITE }
        private val stopIcon: Icon = ClaudeIcons.stop.withSize(15).withColor { Color.WHITE }

        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            val d = Dimension(JBUI.scale(30), JBUI.scale(30))
            preferredSize = d; minimumSize = d; maximumSize = d
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = "Send"
            getAccessibleContext().accessibleName = "Send message"
            addActionListener { if (running) onStop() else trySend() }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = when {
                running -> ClaudeUiTokens.error()
                !isEnabled -> ClaudeUiTokens.withAlpha(ClaudeUiTokens.accent(), 0.35f)
                else -> ClaudeUiTokens.accent()
            }
            g2.color = bg
            val arc = ClaudeUiTokens.radiusMd()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            val icon = if (running) stopIcon else sendIcon
            icon.paintIcon(this, g, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
        }
    }
}
