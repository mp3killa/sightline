package io.mp.sightline.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.sightline.android.AndroidContext
import io.mp.sightline.android.AndroidContextFormatter
import io.mp.sightline.android.ContextChipKind
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import io.mp.sightline.theme.ClaudeIcons
import io.mp.sightline.theme.ClaudeUiTokens
import io.mp.sightline.ui.android.AndroidContextStrip
import io.mp.sightline.ui.components.ContextChip
import io.mp.sightline.ui.components.IconActionButton
import io.mp.sightline.ui.components.WrapLayout
import io.mp.sightline.ui.state.ComposerModel
import io.mp.sightline.ui.state.ContextUsage
import io.mp.sightline.ui.state.ImageAttachmentPolicy
import io.mp.sightline.ui.state.MentionQuery
import io.mp.sightline.ui.state.PasteRouting
import io.mp.sightline.ui.state.PendingImage
import io.mp.sightline.ui.state.PendingText
import io.mp.sightline.ui.state.TextAttachmentPolicy
import io.mp.sightline.ui.state.SlashCommands
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import com.intellij.openapi.util.SystemInfo
import java.awt.Image
import java.awt.RenderingHints
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The bottom composer: a growing 2-row input (Enter sends, Shift+Enter newlines), removable
 * attached-context chips (including pasted images, with thumbnails), a left action group (attach /
 * slash actions), and a right group with a concise permission-mode chip and one coordinated
 * send/stop button. No decorative/unavailable controls.
 *
 * Pasting an image (⌘V after a screenshot, "Copy Image" in a browser) attaches it to the next
 * message rather than pasting mush into the text — routing decided by [PasteRouting], encoding by
 * [ImageAttachmentEncoder] off the EDT.
 */
class ClaudeComposerPanel(
    private val model: ComposerModel,
    private val onSend: (String) -> Unit,
    private val onStop: () -> Unit,
    private val onAttach: () -> Unit,
    private val onSlash: (Component) -> Unit,
    private val onModeMenu: (Component) -> Unit,
    /** Re-resolve the Android context on demand. No-op by default so tests and previews stay simple. */
    private val onRefreshAndroidContext: () -> Unit = {},
    /** Files pasted or dropped onto the input — the host attaches them as path chips. */
    private val onFilesPasted: (List<File>) -> Unit = {},
    /** A one-line notice about an attachment (a refused or unreadable paste) — the host surfaces it. */
    private val onAttachmentNotice: (String) -> Unit = {},
    /** Ask the CLI for its own `/context` breakdown — what the footer chip's click does. */
    private val onContextBreakdown: () -> Unit = {},
    /**
     * Project files matching a typed `@` prefix, ranked, for the mention popup. Returns an empty list
     * by default so previews and unit tests never touch the project index.
     */
    private val onMentionSearch: (String) -> List<String> = { emptyList() },
) : JPanel(BorderLayout()) {

    private val box = ComposerBox()
    // WrapLayout, not FlowLayout: FlowLayout reports a one-row preferred height however many rows it
    // actually lays out, so on a narrow panel the last chip was silently clipped away.
    private val chipsRow = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2)))

    /** Current Android state, for chip labels. Empty until the host pushes one in. */
    private var androidContext: AndroidContext = AndroidContext.NOT_ANDROID

    /** The one-line Android summary; hides itself outside an Android project. */
    private val contextStrip = AndroidContextStrip(
        onToggleChip = { kind, enabled -> model.setChipEnabled(kind, enabled); refreshChips() },
        isChipEnabled = { model.isChipEnabled(it) },
        onRefresh = { onRefreshAndroidContext() },
    )

    /**
     * Parked messages, one editable card each — visible while a turn is in flight. A count label was
     * "too easy to miss" (per the review) and offered no way back: each card shows the message text and
     * an **Edit** (pull it back into the composer) and **Cancel** (drop it) affordance.
     */
    private val queuedPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 3, 0)
        isVisible = false
    }
    private val input = JBTextArea(2, 20)

    /** The handler wrapped by [installPasteInterceptor] — the ordinary text paste, kept reachable. */
    private var originalTransferHandler: TransferHandler? = null
    private val inputScroll = JBScrollPane(input, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
    private val modeChip = JButton()

    /**
     * Context-window occupancy, muted, beside the mode chip — the footer is where a reader looks for
     * "state of this conversation", and it is the placement the equivalent VS Code panel settled on.
     * Hidden until the CLI has reported usage: an empty conversation has no context to report.
     */
    private val contextChip = JButton()
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
        val north = JPanel(BorderLayout()); north.isOpaque = false
        north.add(contextStrip, BorderLayout.NORTH)
        north.add(chipsRow, BorderLayout.CENTER); north.add(queuedPanel, BorderLayout.SOUTH)
        box.add(north, BorderLayout.NORTH)

        input.isOpaque = false
        input.lineWrap = true
        input.wrapStyleWord = true
        input.border = JBUI.Borders.empty(2, 2)
        input.font = UIUtil.getLabelFont()
        input.emptyText.text = "Ask Claude about this project…"
        input.toolTipText = "Enter to send · Shift+Enter for a new line"
        // Shift+Cmd/Ctrl+V forces the image off the clipboard. Registered on the input rather than as
        // an IDE action so it exists only where it means something, and cannot collide with a keymap.
        //
        // The modifier is chosen from SystemInfo, *not* Toolkit.getMenuShortcutKeyMaskEx(): that call
        // throws HeadlessException, and this runs while the composer is being constructed — so every
        // headless test that builds a panel died on it. It passed locally because macOS is not headless
        // and failed the entire CI suite, which is the only place that difference shows up.
        val menuMask = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        input.registerKeyboardAction(
            { attachImageFromClipboard() },
            KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask or InputEvent.SHIFT_DOWN_MASK),
            JComponent.WHEN_FOCUSED,
        )
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // While the mention popup is up it owns the navigation keys — otherwise Enter would
                // send the message instead of accepting the completion the user is looking at.
                if (mentionPopup?.handleKey(e) == true) return
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) { e.consume(); trySend() }
            }
        })
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent) = onTextChanged()
        })
        input.addCaretListener { refreshMentionPopup() }
        input.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { box.focused = true; box.repaint(); adjustHeight() }
            override fun focusLost(e: FocusEvent) { box.focused = false; box.repaint(); adjustHeight() }
        })
        installPasteInterceptor()
        installIdePasteAction()
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
        styleContextChip()
        right.add(contextChip)
        styleModeChip()
        right.add(modeChip)
        right.add(sendButton)
        row.add(right, BorderLayout.EAST)
        return row
    }

    /**
     * Styled as a flat chip rather than a label because it *is* clickable: it runs `/context`, the
     * CLI's own breakdown by category. That costs nothing — verified, the command is executed locally
     * and comes back as a synthetic turn with `num_turns: 0` and zero tokens.
     */
    private fun styleContextChip() {
        contextChip.isContentAreaFilled = false
        contextChip.isFocusPainted = false
        contextChip.isOpaque = false
        contextChip.isVisible = false
        contextChip.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        contextChip.border = JBUI.Borders.empty(3, 6)
        contextChip.addActionListener { onContextBreakdown() }
    }

    /** Shows the occupancy, or hides the chip when there is nothing evidenced to show. */
    fun setContextUsage(view: ContextUsage.View?) {
        if (view == null) { contextChip.isVisible = false; return }
        contextChip.isVisible = true
        contextChip.text = view.text
        contextChip.toolTipText = view.detail + "  Click for the full breakdown."
        contextChip.foreground = when (view.level) {
            ContextUsage.Level.CRITICAL -> ClaudeUiTokens.error()
            ContextUsage.Level.HIGH -> ClaudeUiTokens.warning()
            ContextUsage.Level.NORMAL -> ClaudeUiTokens.textSecondary()
        }
        contextChip.repaint()
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

    /**
     * Puts [text] in the input and focuses it, replacing whatever draft was there only when there
     * wasn't one — a half-typed message is the user's, and a menu pick must not eat it.
     */
    /**
     * Puts [text] in the input, ready to edit and send.
     *
     * A **slash command goes to the front**, not the end. It only executes as a command when it is the
     * first thing in the message, so appending one after text the user had already typed produced a
     * line that looked like a command and was billed as a prompt. Anything already typed stays, after
     * it, where the CLI reads it as the command's arguments and where the user can see and edit it.
     */
    fun putInInput(text: String) {
        val existing = input.text.orEmpty().trim()
        input.text = when {
            existing.isBlank() -> text
            SlashCommands.isCommand(text) -> text.trimEnd() + " " + existing
            else -> existing + " " + text
        }
        input.caretPosition = input.document.length
        input.requestFocusInWindow()
    }

    // ---- @-mention completion ----

    private var mentionPopup: MentionPopup? = null

    /**
     * Opens, updates or closes the mention popup for whatever is under the caret.
     *
     * Called from the document listener, so it runs on every keystroke: everything expensive is behind
     * [MentionQuery.at] returning null, which is the common case (no `@` being typed).
     */
    private fun refreshMentionPopup() {
        val query = MentionQuery.at(input.text ?: "", input.caretPosition)
        if (query == null) { closeMentionPopup(); return }
        val matches = runCatching { onMentionSearch(query.prefix) }.getOrDefault(emptyList())
        if (matches.isEmpty()) { closeMentionPopup(); return }
        val popup = mentionPopup ?: MentionPopup { path -> acceptMention(query.start, path) }.also { mentionPopup = it }
        popup.show(input, matches)
    }

    private fun closeMentionPopup() {
        mentionPopup?.hide()
        mentionPopup = null
    }

    /** Replaces the typed `@prefix` with the chosen reference. */
    private fun acceptMention(start: Int, path: String) {
        val caret = input.caretPosition.coerceIn(0, input.document.length)
        if (start > caret) { closeMentionPopup(); return }
        input.document.remove(start, caret - start)
        input.document.insertString(start, MentionQuery.completion(path), null)
        closeMentionPopup()
        input.requestFocusInWindow()
    }

    fun insertContextText(text: String) {
        input.insert(text, input.caretPosition.coerceIn(0, input.document.length))
        input.requestFocusInWindow()
    }

    fun setRunning(running: Boolean) {
        model.running = running
        sendButton.running = running
        sendButton.toolTipText = if (running) "Stop Claude" else "Send"
        sendButton.getAccessibleContext().accessibleName = if (running) "Stop Claude" else "Send message"
        // The placeholder states what Enter will do right now: send, or queue behind the current turn.
        input.emptyText.text = model.placeholder()
        updateSendEnabled()
        refreshQueueLabel()
        adjustHeight() // collapse to one row when a run starts (or restore when it ends)
        sendButton.repaint()
    }

    /** Rebuilds the queued-message cards; the panel hides itself when nothing is waiting. */
    fun refreshQueueLabel() {
        queuedPanel.removeAll()
        val msgs = model.queued
        queuedPanel.isVisible = msgs.isNotEmpty()
        for ((i, msg) in msgs.withIndex()) {
            queuedPanel.add(buildQueuedCard(i, msg))
            queuedPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)))
        }
        queuedPanel.revalidate()
        revalidate(); repaint()
    }

    private fun buildQueuedCard(index: Int, msg: ComposerModel.QueuedMessage): JComponent {
        val row = QueuedRow()
        row.layout = BorderLayout(JBUI.scale(8), 0)
        row.border = JBUI.Borders.empty(3, 9)
        row.alignmentX = Component.LEFT_ALIGNMENT

        val label = JBLabel(queuedText(msg))
        label.foreground = ClaudeUiTokens.textSecondary()
        label.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(10.5f).toFloat())
        label.toolTipText = msg.text.trim().ifBlank { null }
        row.add(label, BorderLayout.CENTER)

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)); actions.isOpaque = false
        actions.add(linkAction("Edit") { editQueued(index) })
        actions.add(linkAction("Cancel") { cancelQueued(index) })
        row.add(actions, BorderLayout.EAST)
        return row
    }

    /** "Queued: <text>", or a description of an image-only message; truncated to stay one line. */
    private fun queuedText(msg: ComposerModel.QueuedMessage): String {
        val t = msg.text.trim().replace("\n", " ")
        val body = when {
            t.isNotEmpty() -> if (t.length > 60) t.take(59) + "…" else t
            msg.images.isNotEmpty() -> "${msg.images.size} image" + if (msg.images.size == 1) "" else "s"
            msg.texts.isNotEmpty() -> "${msg.texts.size} pasted text block" + if (msg.texts.size == 1) "" else "s"
            else -> "(empty)"
        }
        // The card names what rides along, not only the prose: a parked message whose whole content is a
        // 400-line paste would otherwise read as a bare sentence with nothing attached to it.
        val carried = listOfNotNull(
            msg.images.size.takeIf { it > 0 && t.isNotEmpty() }?.let { "$it image" + if (it == 1) "" else "s" },
            msg.texts.size.takeIf { it > 0 && t.isNotEmpty() }?.let { "$it pasted" },
        )
        val suffix = if (carried.isEmpty()) "" else " (+ ${carried.joinToString(", ")})"
        return "Queued: $body$suffix"
    }

    /** Pulls a queued message back into the composer for revision (text + its captured images). */
    private fun editQueued(index: Int) {
        val msg = model.removeQueuedAt(index) ?: return
        val existing = input.text
        input.text = if (existing.isBlank()) msg.text else existing.trimEnd() + "\n" + msg.text
        model.restoreImages(msg.images)
        // Back as chips, never re-inlined: Edit exists to revise the prose, and dumping the paste into
        // the textarea would undo the one thing attaching it achieved.
        model.restoreTexts(msg.texts)
        refreshChips(); refreshQueueLabel(); updateSendEnabled(); adjustHeight()
        input.requestFocusInWindow()
    }

    private fun cancelQueued(index: Int) {
        model.removeQueuedAt(index) ?: return
        refreshQueueLabel()
    }

    /** A small clickable text action ("Edit" / "Cancel") that accents on hover. */
    private fun linkAction(text: String, action: () -> Unit): JBLabel {
        val l = JBLabel(text)
        l.foreground = ClaudeUiTokens.textSecondary()
        l.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(10.5f).toFloat())
        l.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        l.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = action()
            override fun mouseEntered(e: java.awt.event.MouseEvent) { l.foreground = ClaudeUiTokens.accent() }
            override fun mouseExited(e: java.awt.event.MouseEvent) { l.foreground = ClaudeUiTokens.textSecondary() }
        })
        return l
    }

    /** A subtle rounded surface behind one queued-message card. */
    private inner class QueuedRow : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = ClaudeUiTokens.radiusMd()
            g2.color = ClaudeUiTokens.subtleSurface()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = ClaudeUiTokens.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
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

    /** Push a freshly resolved Android context in — updates the strip and the context chips. */
    fun setAndroidContext(context: AndroidContext) {
        androidContext = context
        contextStrip.update(context)
        refreshChips()
    }

    /**
     * Rebuilds the chip row from two sources: the Android context facts, then file attachments.
     *
     * Context leads because it describes the framing of the message; attachments are its content.
     * Removing a context chip disables that fact for subsequent messages — it is not merely hiding a
     * label — and the strip's menu is the way back, which is why removing one leaves the strip visible.
     */
    fun refreshChips() {
        chipsRow.removeAll()

        val contextChips = AndroidContextFormatter.availableChips(androidContext)
            .filter { model.isChipEnabled(it) }
        for (kind in contextChips) {
            val chip = ContextChip(kind.id, AndroidContextFormatter.chipLabel(kind, androidContext)) { id ->
                ContextChipKind.byId(id)?.let { model.removeContextChip(it) }
                refreshChips()
            }
            chip.toolTipText = AndroidContextFormatter.chipTooltip(kind, androidContext)
            chipsRow.add(chip)
        }

        for (path in model.attachments) {
            chipsRow.add(ContextChip(path, basename(path)) { removed ->
                model.removeAttachment(removed)
                refreshChips()
                updateSendEnabled()
            })
        }

        for (img in model.images) {
            val chip = ContextChip(
                img.id,
                ImageAttachmentPolicy.chipLabel(img),
                icon = thumbnailIcon(img),
                detail = ImageAttachmentPolicy.chipDetail(img),
            ) { removed ->
                model.removeImage(removed)
                refreshChips()
                updateSendEnabled()
            }
            chip.toolTipText = ImageAttachmentPolicy.tooltip(img)
            chip.getAccessibleContext().accessibleName = A11yNames.composerImage(img.ordinal)
            chipsRow.add(chip)
        }

        for (txt in model.texts) {
            val chip = ContextChip(
                txt.id,
                TextAttachmentPolicy.chipLabel(txt),
                icon = ClaudeIcons.read.withSize(13).withColor { ClaudeUiTokens.textSecondary() },
                detail = TextAttachmentPolicy.chipDetail(txt),
            ) { removed ->
                model.removeText(removed)
                refreshChips()
                updateSendEnabled()
            }
            chip.toolTipText = TextAttachmentPolicy.tooltip(txt)
            chip.getAccessibleContext().accessibleName = A11yNames.composerPastedText(txt.ordinal)
            chipsRow.add(chip)
        }

        chipsRow.isVisible = model.hasAttachments || model.hasImages || model.hasTexts || contextChips.isNotEmpty()
        chipsRow.revalidate(); chipsRow.repaint()
        revalidate(); repaint()
    }

    // ---- clipboard images ----

    /**
     * Routes what lands in the input: files → path chips, an image → an image attachment, text →
     * the ordinary paste. The precedence (and why Excel cells must paste as text while a screenshot
     * attaches) lives in [PasteRouting]; this wrapper only maps clipboard flavors to booleans — and
     * forwards the export half (copy/cut, drag-out) untouched to the original handler, so
     * intercepting paste never costs copy.
     */
    private fun installPasteInterceptor() {
        val original = input.transferHandler
        originalTransferHandler = original
        input.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                    support.isDataFlavorSupported(DataFlavor.imageFlavor) ||
                    (original?.canImport(support) ?: false)

            override fun importData(support: TransferSupport): Boolean =
                routePaste(support.transferable) || (original?.importData(support) ?: false)

            // Legacy entry point some paste paths still use — funnel into the TransferSupport one.
            override fun importData(comp: JComponent, t: Transferable): Boolean =
                importData(TransferSupport(comp, t))

            override fun getSourceActions(c: JComponent): Int = original?.getSourceActions(c) ?: NONE
            override fun exportToClipboard(comp: JComponent, clip: Clipboard, action: Int) {
                original?.exportToClipboard(comp, clip, action)
            }
            override fun exportAsDrag(comp: JComponent, e: InputEvent, action: Int) {
                original?.exportAsDrag(comp, e, action)
            }
        }
    }

    /**
     * Decides what a [Transferable] *is* and acts on it. Returns false when the paste is ordinary text
     * that belongs in the input box, which is the caller's cue to run the normal text paste.
     *
     * One routine rather than two because there are two ways in: the Swing [TransferHandler] (drag and
     * drop, and any paste path that reaches it) and the IDE paste action installed by
     * [installIdePasteAction]. Two copies of a precedence rule is one edit away from the gestures
     * disagreeing about what a clipboard holding both text and an image means.
     */
    private fun routePaste(t: Transferable): Boolean {
        val hasFiles = t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        val hasText = t.isDataFlavorSupported(DataFlavor.stringFlavor)
        val hasImage = t.isDataFlavorSupported(DataFlavor.imageFlavor)
        val pasted = if (hasText) readString(t) else null
        val routed = PasteRouting.route(
            hasFiles = hasFiles,
            hasText = hasText,
            textIsBlank = hasText && (pasted?.isBlank() ?: true),
            hasImage = hasImage,
        )
        return when (routed) {
            PasteRouting.Route.FILES -> {
                val files = readFiles(t)
                if (files.isEmpty()) false else { onFilesPasted(files); true }
            }
            PasteRouting.Route.IMAGE -> {
                val image = readImage(t)
                if (image == null) false else { attachClipboardImage(image); true }
            }
            PasteRouting.Route.TEXT, PasteRouting.Route.DELEGATE -> {
                // Say when a paste has just stepped over an image — the browser case, where
                // "Copy image" carries the picture and its URL and the text wins. Said before the size
                // check, not after: a large paste that becomes a chip stepped over the image just as
                // surely as one that went into the input box.
                if (PasteRouting.imageWasPassedOver(routed, hasImage)) {
                    onAttachmentNotice(PasteRouting.IMAGE_PASSED_OVER)
                }
                // A paste too big for the input box becomes a chip instead of burying the sentence
                // being written. Below the threshold nothing changes: ordinary text pastes as
                // characters, which is what a text box is for.
                if (pasted != null && TextAttachmentPolicy.shouldAttach(pasted)) {
                    attachPastedText(pasted)
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Claims the IDE's own **Paste** shortcut on the input, because in an IntelliJ-platform IDE a plain
     * Swing `TransferHandler` never sees Cmd/Ctrl+V at all.
     *
     * `com.intellij.openapi.editor.actions.PasteAction` extends `TextComponentEditorAction`, which is
     * the platform's mechanism for making *editor* actions work inside ordinary Swing text components:
     * a focused `JTextArea` gets wrapped in a `TextComponentEditor` and the keystroke is handled by the
     * editor paste path — which deals only in `stringFlavor`. So with an image on the clipboard the IDE
     * action ran, found no text, did nothing, and consumed the event; `importData` was never reached and
     * the paste looked broken with no error anywhere. (This is also why the Shift+Cmd/Ctrl+V override
     * worked: a component-level `registerKeyboardAction` is not shadowed the same way.)
     *
     * The shortcut is taken from the **`${'$'}Paste` action's own shortcut set**, not hardcoded, so a user
     * who has rebound paste keeps their binding. Registering it on [input] scopes it to this component
     * and gives it priority there over the platform action. When the routing declines — ordinary text —
     * the original handler runs, so normal typing-and-pasting is untouched.
     */
    private fun installIdePasteAction() {
        val platformPaste = ActionManager.getInstance().getAction("${'$'}Paste") ?: return
        object : AnAction(), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) {
                val contents = runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                }.getOrNull() ?: return
                if (routePaste(contents)) return
                // Not ours: the ordinary text paste, run against the handler we wrapped so the routing
                // is not re-entered (and its notices not said twice).
                originalTransferHandler?.importData(TransferHandler.TransferSupport(input, contents))
            }
        }.registerCustomShortcutSet(platformPaste.shortcutSet, input)
    }

    /**
     * Attaches whatever image the system clipboard is holding, whatever else it also holds.
     *
     * The explicit gesture behind Shift+Ctrl/Cmd+V and the Actions menu item. Ordinary paste keeps its
     * precedence (files, then text, then image) so a spreadsheet still pastes as text; this is the way
     * to reach the image when something else won.
     */
    fun attachImageFromClipboard() {
        val clipboard = runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()
        val image = clipboard?.let { c ->
            runCatching {
                if (c.isDataFlavorAvailable(DataFlavor.imageFlavor)) c.getData(DataFlavor.imageFlavor) as? Image else null
            }.getOrNull()
        }
        if (image == null) { onAttachmentNotice(PasteRouting.NO_IMAGE); return }
        attachClipboardImage(image)
    }

    /**
     * Turns a large paste into a chip, or says why it could not. Synchronous — unlike an image there is
     * nothing to encode, and hopping off the EDT for a string copy would only make the chip appear a
     * frame late.
     *
     * The first thing this must never be is quiet: to the user the gesture was a paste, and an input box
     * that does not change after one looks exactly like a paste that failed. So the *outcome* is stated
     * either way — accepted as a named chip, or refused with the reason and what to do instead.
     */
    private fun attachPastedText(text: String) {
        when (model.addText(text)) {
            TextAttachmentPolicy.AddTextResult.ADDED -> {
                model.lastText()?.let { onAttachmentNotice(TextAttachmentPolicy.attachedNotice(it)) }
                refreshChips()
                updateSendEnabled()
            }
            TextAttachmentPolicy.AddTextResult.REJECTED_LIMIT ->
                onAttachmentNotice(TextAttachmentPolicy.limitMessage())
            TextAttachmentPolicy.AddTextResult.REJECTED_TOO_LARGE ->
                onAttachmentNotice(TextAttachmentPolicy.tooLargeMessage(text.length))
        }
    }

    private fun readString(t: Transferable): String? = try {
        t.getTransferData(DataFlavor.stringFlavor) as? String
    } catch (_: Exception) {
        null
    }

    private fun readFiles(t: Transferable): List<File> = try {
        (t.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
    } catch (_: Exception) {
        emptyList()
    }

    private fun readImage(t: Transferable): Image? = try {
        t.getTransferData(DataFlavor.imageFlavor) as? Image
    } catch (_: Exception) {
        null
    }

    /**
     * Encodes off the EDT — scaling and compressing a retina screenshot takes real milliseconds —
     * then hops back to add the chip. A refused or unreadable paste is *said*, via
     * [onAttachmentNotice], never silently swallowed.
     */
    private fun attachClipboardImage(image: Image) {
        Thread({
            val encoded = ImageAttachmentEncoder.encode(image)
            SwingUtilities.invokeLater {
                if (encoded == null) {
                    onAttachmentNotice("Image not attached: the clipboard image could not be read.")
                    return@invokeLater
                }
                when (model.addImage(encoded)) {
                    ImageAttachmentPolicy.AddImageResult.ADDED -> {
                        refreshChips()
                        updateSendEnabled()
                    }
                    ImageAttachmentPolicy.AddImageResult.REJECTED_LIMIT ->
                        onAttachmentNotice(ImageAttachmentPolicy.limitMessage())
                    ImageAttachmentPolicy.AddImageResult.REJECTED_TOO_LARGE ->
                        onAttachmentNotice(ImageAttachmentPolicy.tooLargeMessage(encoded.bytes.size))
                }
            }
        }, "sightline-image-encode").apply { isDaemon = true }.start()
    }

    /** A 16px-tall rendition of the encoder's thumbnail, so the chip shows *which* image it is. */
    private fun thumbnailIcon(img: PendingImage): Icon? {
        val thumb = img.image.thumbnail ?: return null
        val h = JBUI.scale(16)
        val w = ((thumb.width * h.toDouble()) / thumb.height).toInt().coerceAtLeast(1)
        return ImageIcon(thumb.getScaledInstance(w, h, Image.SCALE_SMOOTH))
    }

    // ---- internals ----

    /**
     * Enter/Send: hands the text to the host when idle **or mid-turn** (the host folds an interjection
     * into the running turn), and parks it only when nothing is listening. Either way the input is
     * cleared and the user gets feedback — the one thing the old code never did.
     */
    private fun trySend() {
        val text = input.text
        when (model.submit(text)) {
            ComposerModel.Submit.IGNORED_BLANK -> return
            ComposerModel.Submit.QUEUED -> {
                input.text = ""
                // The queued entry captured the pending images — their chips leave with it.
                refreshChips()
                refreshQueueLabel()
                onTextChanged()
            }
            // Same host call for both: the host knows whether a turn is running and delivers
            // accordingly. Splitting it here would put that decision in two places.
            ComposerModel.Submit.SENT, ComposerModel.Submit.INTERJECTED -> onSend(text)
        }
    }

    /** Re-reads the placeholder — the host calls this when interject-ability changes (e.g. a Stop). */
    fun refreshPlaceholder() { input.emptyText.text = model.placeholder() }

    /** Test-only: submit a message directly, as if the user had pressed Enter mid-turn. */
    @org.jetbrains.annotations.TestOnly
    internal fun queueForTest(message: String) {
        model.submit(message)
        refreshQueueLabel()
    }

    /** Drains one queued message (text + its captured images) — called by the host when a turn finishes. */
    fun takeQueuedMessage(): ComposerModel.QueuedMessage? = model.takeQueued()?.also { refreshQueueLabel() }

    private fun onTextChanged() {
        updateSendEnabled()
        adjustHeight()
    }

    private fun updateSendEnabled() {
        sendButton.isEnabled = model.running || model.sendEnabled(input.text)
    }

    /**
     * While the agent is working and the field is empty and unfocused, the composer collapses to a
     * single row to give the transcript/graph the space — expanding again the moment it is focused or
     * gains text. A light touch on purpose: the field stays present (queuing a follow-up must remain
     * one click away), it just stops reserving two idle rows mid-run.
     */
    private fun effectiveMinRows(): Int =
        if (model.running && !box.focused && input.text.isBlank()) 1 else minRows

    private fun adjustHeight() {
        val fm = input.getFontMetrics(input.font)
        val rowH = fm.height
        val content = input.preferredSize.height
        val minH = rowH * effectiveMinRows()
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
