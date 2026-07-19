package io.mp.claudecodepanel.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.activity.ActivityInterpreter
import io.mp.claudecodepanel.activity.AgentActivityEvent
import io.mp.claudecodepanel.process.ClaudeSession
import io.mp.claudecodepanel.settings.ClaudeSettings
import io.mp.claudecodepanel.settings.ClaudeSettingsConfigurable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.ListCellRenderer
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

private val ACCENT = JBColor(Color(0xC2, 0x55, 0x2E), Color(0xE8, 0x8A, 0x6B))
private val STOP_RED = JBColor(Color(0xC0, 0x39, 0x2B), Color(0xE0, 0x6C, 0x5F))

private const val PRIME_PROMPT =
    "Get up to speed on this project AND make sure its Claude/internal docs are complete.\n\n" +
        "1. Explore the codebase with the tools available (inspect real files — don't guess): the module/folder " +
        "structure, build system, tech stack, entry points, and the actual conventions (naming, testing, DI, " +
        "styling, error handling, etc.).\n" +
        "2. Read any existing docs: CLAUDE.md (including nested ones), README.md, ARCHITECTURE.md, CONTRIBUTING.md, " +
        "CONVENTIONS.md, AGENTS.md, and anything under docs/.\n" +
        "3. CREATE any of these recommended docs that are MISSING, written accurately from what you actually found " +
        "(concise and high-signal — no placeholder boilerplate):\n" +
        "   - CLAUDE.md (repo root): what the project is, the module/folder map, the build/run/test commands, and the " +
        "key conventions to follow. It is auto-loaded into every Claude Code session, so keep it tight.\n" +
        "   - docs/ARCHITECTURE.md: the architecture — modules/layers, data flow, and the key components and how they fit.\n" +
        "   - docs/CONVENTIONS.md: the coding conventions exactly as they appear in the code.\n" +
        "   - CONTRIBUTING.md (repo root): how to set up, build, run, and test the project, plus the commit / PR / " +
        "branch conventions actually used.\n" +
        "   - AGENTS.md (repo root): concise tool-agnostic instructions for AI agents working in this repo. To avoid " +
        "duplication, if CLAUDE.md already covers the overview/commands, keep AGENTS.md short and point to CLAUDE.md " +
        "and docs/ for the detail.\n" +
        "   Only create a file if it does not already exist — never overwrite or edit an existing doc, and leave " +
        "README.md to the humans. If they all already exist, create nothing.\n" +
        "4. Give me a concise brief (what it does, structure/stack, key conventions) and list which docs you created " +
        "vs. already found."

/**
 * Native Swing chat panel for Claude Code. Each assistant turn is a vertical stack of block
 * components: streaming text (light markdown), collapsible extended-thinking, and collapsible
 * tool cards (icon + summary; expand to see command/diff and result). User turns are rounded
 * bubbles. The composer mirrors Claude Code's (attach / actions / mode chip / circular send).
 */
class ClaudePanel(private val project: Project, parent: Disposable) : Disposable {

    val component: JComponent
    private val session: ClaudeSession = ClaudeSession(project) { line -> onLine(line) }
    private val interpreter = ActivityInterpreter()
    // Parented to the tool window's Disposable (not `this`): this is a field initializer that runs
    // before ClaudePanel registers itself, so registering under `this` would leak an unregistered parent.
    private val activityMap = ActivityMapPanel(project, parent)
    private val mapSplitter = JBSplitter(true, 0.60f)
    private val mapButton = JButton()
    private var mapVisible = ClaudeSettings.getInstance().state.showActivityMap

    private val transcript = object : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = editorBg()
            border = JBUI.Borders.empty(10, 10)
        }
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(20)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(120)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
    private val scroll = JBScrollPane(transcript)
    private val statusLabel = JBLabel(" ")
    private val input = JBTextArea(2, 10)
    private val primeButton = JButton("Catch up")
    private val detailsButton = JButton()
    private val newButton = JButton("New")
    private val settingsButton = JButton("⚙")
    private val modelCombo = JComboBox(arrayOf("", "opus", "sonnet", "haiku"))
    private val modeChip = JButton()
    private val sendCircle = RoundButton("↑")
    private val spinner = AsyncProcessIcon("claude-running")
    private val composerBox = RoundedPanel()

    private val hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

    // styles (used inside per-block styled panes)
    private val sNormal = SimpleAttributeSet()
    private val sMuted = SimpleAttributeSet()
    private val sBold = SimpleAttributeSet()
    private val sCode = SimpleAttributeSet()
    private val sDiffAdd = SimpleAttributeSet()
    private val sDiffDel = SimpleAttributeSet()
    private val sError = SimpleAttributeSet()

    // render state
    private var running = false
    private var inAssistant = false
    private var sawStream = false
    private var curTurn: AssistantTurn? = null
    private var curType: String? = null
    private var curText: TextBlock? = null
    private var curThinking: ThinkingBlock? = null
    private var curTool: ToolCard? = null
    private var curToolName: String? = null
    private var curToolId: String? = null
    private val curToolJson = StringBuilder()
    private var target: StyledDocument? = null
    private val toolCardsById = HashMap<String, ToolCard>()
    private val renderedTools = HashSet<String>()
    private var pendingScroll = false
    private var showDetails = ClaudeSettings.getInstance().state.showDetails
    private val turns = ArrayList<AssistantTurn>()

    private data class Mode(val value: String, val title: String, val desc: String, val glyph: String)
    private val modes = listOf(
        Mode("default", "Manual", "Claude will ask for approval before making each edit", "✋"),
        Mode("acceptEdits", "Edit automatically", "Claude will edit your selected text or the whole file", "✎"),
        Mode("plan", "Plan", "Claude will explore the code and present a plan before editing", "▤"),
        Mode("auto", "Auto", "Claude approves actions that pass a safety check and pauses for anything risky (needs Sonnet/Opus)", "⚡"),
        Mode("bypassPermissions", "Bypass permissions", "Claude won't ask before running potentially dangerous commands", "⚠"),
    )

    init {
        Disposer.register(parent, this)
        Disposer.register(this, spinner)
        initStyles()
        component = build()
        applyConfigToUi()
        addInfo("Ask Claude to build a feature, explain code, or fix a bug in this project.", false)
    }

    // ---------- UI ----------

    private fun build(): JComponent {
        val root = JPanel(BorderLayout())

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        val brand = JBLabel("✱ Claude Code")
        brand.foreground = ACCENT
        brand.border = JBUI.Borders.emptyRight(8)
        bar.add(brand)
        modelCombo.renderer = labelRenderer { if (it.isNullOrEmpty()) "Default model" else cap(it) }
        modelCombo.addActionListener { ClaudeSettings.getInstance().state.model = (modelCombo.selectedItem as? String) ?: "" }
        primeButton.toolTipText = "Have Claude read this project's docs (CLAUDE.md, README, docs/…) and summarize — handy at the start of a session"
        primeButton.addActionListener { primeProject() }
        detailsButton.toolTipText = "Show/hide thinking and tool-call details in the transcript"
        detailsButton.addActionListener { setShowDetails(!showDetails) }
        updateDetailsButton()
        newButton.addActionListener { session.newConversation() }
        settingsButton.toolTipText = "Claude Code settings"
        settingsButton.addActionListener { openSettings() }
        mapButton.toolTipText = "Show/hide the live Agent Activity Map (graph of observable tool activity)"
        mapButton.addActionListener { setMapVisible(!mapVisible) }
        bar.add(modelCombo); bar.add(primeButton); bar.add(detailsButton); bar.add(mapButton); bar.add(newButton); bar.add(settingsButton)
        root.add(bar, BorderLayout.NORTH)

        scroll.border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border())
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scroll.viewport.background = editorBg()
        mapSplitter.firstComponent = scroll
        mapSplitter.setHonorComponentsMinimumSize(false)
        updateMapButton()
        installCenter()
        root.add(mapSplitter, BorderLayout.CENTER)

        val south = JPanel(BorderLayout())
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.border = JBUI.Borders.empty(2, 12)
        south.add(statusLabel, BorderLayout.NORTH)
        south.add(buildComposer(), BorderLayout.CENTER)
        root.add(south, BorderLayout.SOUTH)
        return root
    }

    private fun buildComposer(): JComponent {
        composerBox.layout = BorderLayout()
        composerBox.border = JBUI.Borders.empty(8, 12, 6, 8)

        input.isOpaque = false
        input.lineWrap = true
        input.wrapStyleWord = true
        input.border = JBUI.Borders.empty()
        input.emptyText.text = "Message Claude…   (⏎ send · ⇧⏎ newline)"
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) { e.consume(); doSend() }
            }
        })
        input.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { composerBox.focused = true; composerBox.repaint() }
            override fun focusLost(e: FocusEvent) { composerBox.focused = false; composerBox.repaint() }
        })
        val inputScroll = JBScrollPane(input)
        inputScroll.isOpaque = false
        inputScroll.viewport.isOpaque = false
        inputScroll.border = JBUI.Borders.empty()
        inputScroll.preferredSize = Dimension(10, JBUI.scale(46))
        val inputRow = JPanel(BorderLayout())
        inputRow.isOpaque = false
        inputRow.add(inputScroll, BorderLayout.CENTER)
        val mic = JBLabel("🎤")
        mic.foreground = UIUtil.getContextHelpForeground()
        mic.toolTipText = "Voice input isn't available in this panel"
        mic.border = JBUI.Borders.empty(1, 6, 0, 2)
        val micWrap = JPanel(BorderLayout()); micWrap.isOpaque = false; micWrap.add(mic, BorderLayout.NORTH)
        inputRow.add(micWrap, BorderLayout.EAST)
        composerBox.add(inputRow, BorderLayout.CENTER)

        val actions = JPanel(BorderLayout())
        actions.isOpaque = false
        actions.border = JBUI.Borders.emptyTop(6)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        left.isOpaque = false
        left.add(flatGlyph("+", "Attach a file as an @mention") { attachFile() })
        left.add(boxedGlyph("/", "Actions") { b -> showSlashMenu(b) })
        spinner.isVisible = false
        spinner.suspend()
        left.add(spinner)
        actions.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        right.isOpaque = false
        styleChip(modeChip)
        modeChip.addActionListener { showModesPopup(modeChip) }
        right.add(modeChip)
        sendCircle.toolTipText = "Send"
        sendCircle.addActionListener { if (running) { session.stop(); setStatus("Stopping…") } else doSend() }
        right.add(sendCircle)
        actions.add(right, BorderLayout.EAST)

        composerBox.add(actions, BorderLayout.SOUTH)

        val wrap = JPanel(BorderLayout())
        wrap.border = JBUI.Borders.empty(6, 10, 10, 10)
        wrap.add(composerBox, BorderLayout.CENTER)
        return wrap
    }

    private fun initStyles() {
        val base = UIUtil.getLabelFont()
        val mono = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        val fg = UIUtil.getLabelForeground()
        val muted = UIUtil.getContextHelpForeground()

        StyleConstants.setFontFamily(sNormal, base.family); StyleConstants.setFontSize(sNormal, base.size); StyleConstants.setForeground(sNormal, fg)
        clone(sMuted, sNormal); StyleConstants.setForeground(sMuted, muted)
        clone(sBold, sNormal); StyleConstants.setBold(sBold, true)
        clone(sError, sNormal); StyleConstants.setForeground(sError, STOP_RED)
        StyleConstants.setFontFamily(sCode, mono.family); StyleConstants.setFontSize(sCode, base.size); StyleConstants.setForeground(sCode, fg)
        clone(sDiffAdd, sCode); StyleConstants.setBackground(sDiffAdd, JBColor(Color(0xDD, 0xF4, 0xE4), Color(0x1E, 0x3A, 0x28)))
        clone(sDiffDel, sCode); StyleConstants.setBackground(sDiffDel, JBColor(Color(0xFB, 0xE4, 0xE1), Color(0x3A, 0x22, 0x22)))
    }

    private fun clone(dst: SimpleAttributeSet, src: SimpleAttributeSet) { dst.addAttributes(src) }
    private fun editorBg(): Color = EditorColorsManager.getInstance().globalScheme.defaultBackground
    private fun cardBg(): Color = shift(editorBg(), 10, -6)
    private fun shift(b: Color, dark: Int, light: Int): Color {
        val d = if ((b.red * 0.299 + b.green * 0.587 + b.blue * 0.114) < 128) dark else light
        return Color(clampc(b.red + d), clampc(b.green + d), clampc(b.blue + d))
    }
    private fun clampc(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
    private fun mutedFg(): Color = UIUtil.getContextHelpForeground()

    // ---------- transcript rows ----------

    private fun addRow(c: JComponent) {
        transcript.add(c)
        transcript.add(Box.createVerticalStrut(JBUI.scale(8)))
        scrollToBottomSoon()
    }

    private fun addInfo(text: String, err: Boolean) {
        val ta = plainArea(text)
        ta.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC)
        ta.foreground = if (err) STOP_RED else mutedFg()
        val r = fullWidth(JPanel(BorderLayout())); r.border = JBUI.Borders.empty(2, 4); r.add(ta, BorderLayout.CENTER)
        addRow(r)
    }

    private fun addUserBubble(text: String) {
        val bubble = Bubble()
        bubble.border = JBUI.Borders.empty(9, 12)
        val role = JBLabel("You")
        role.foreground = ACCENT
        role.font = role.font.deriveFont(Font.BOLD, JBUI.scale(11f))
        role.border = JBUI.Borders.emptyBottom(3)
        bubble.add(role, BorderLayout.NORTH)
        bubble.add(plainArea(text), BorderLayout.CENTER)
        addRow(bubble)
    }

    private fun plainArea(text: String): JTextArea {
        val ta = JTextArea(text)
        ta.isEditable = false; ta.lineWrap = true; ta.wrapStyleWord = true; ta.isOpaque = false
        ta.border = JBUI.Borders.empty()
        ta.font = UIUtil.getLabelFont()
        ta.foreground = UIUtil.getLabelForeground()
        return ta
    }

    private fun styledPane(): JTextPane {
        val p = JTextPane()
        p.isEditable = false; p.isOpaque = false
        p.border = JBUI.Borders.empty()
        p.font = UIUtil.getLabelFont()
        return p
    }

    /** Makes a component fill the transcript width in the vertical BoxLayout. */
    private fun <T : JComponent> fullWidth(c: T): T { c.alignmentX = Component.LEFT_ALIGNMENT; c.isOpaque = false; return c }

    private fun click(run: () -> Unit) = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { run() } }

    // ---------- compose actions ----------

    private fun doSend() {
        if (running) return
        val text = input.text.trim()
        if (text.isEmpty()) return
        finalizeCurrent(); inAssistant = false; curTurn = null
        addUserBubble(text)
        feed(interpreter.taskStarted(text))
        session.sendUserMessage(text)
        input.text = ""
        setRunning(true); setStatus("Sending…")
    }

    private fun insertText(s: String) {
        input.insert(s, input.caretPosition.coerceIn(0, input.document.length))
        input.requestFocusInWindow()
    }

    private fun attachFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val base = project.basePath
        val rel = if (base != null && file.path.startsWith(base)) file.path.substring(base.length).trimStart('/') else file.path
        insertText("@$rel ")
    }

    private fun primeProject() {
        if (running) return
        finalizeCurrent(); inAssistant = false; curTurn = null
        addUserBubble("📖 Catch up on this project — read the docs, generate any missing recommended ones (CLAUDE.md, ARCHITECTURE, conventions), and summarize.")
        feed(interpreter.taskStarted("Catch up on this project and complete its docs"))
        session.sendUserMessage(PRIME_PROMPT)
        setRunning(true); setStatus("Reading project docs…")
    }

    // ---------- "/" actions + modes ----------

    private fun showSlashMenu(anchor: Component) {
        val group = DefaultActionGroup()
        group.add(Separator.create("Context"))
        group.add(action("Catch up on project") { primeProject() })
        group.add(action("Attach file…") { attachFile() })
        group.add(action("Clear conversation") { session.newConversation() })
        group.add(Separator.create("Model"))
        group.add(action("Switch model…") { showModelMenu(anchor) })
        group.add(action("Settings…") { openSettings() })
        JBPopupFactory.getInstance()
            .createActionGroupPopup("Actions", group, SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showUnderneathOf(anchor)
    }

    private fun action(text: String, run: () -> Unit): AnAction = object : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) { run() }
    }

    private fun showModelMenu(anchor: Component) {
        val group = DefaultActionGroup()
        listOf("" to "Default", "opus" to "Opus", "sonnet" to "Sonnet", "haiku" to "Haiku").forEach { (v, label) ->
            group.add(action(label) {
                ClaudeSettings.getInstance().state.model = v
                SwingUtilities.invokeLater { modelCombo.selectedItem = v }
            })
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup("Model", group, SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showUnderneathOf(anchor)
    }

    private fun showModesPopup(anchor: Component) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(modes)
            .setTitle("Modes")
            .setRenderer(modeRenderer())
            .setItemChosenCallback { m -> setMode(m.value) }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun modeRenderer(): ListCellRenderer<Mode> = ListCellRenderer { _, value, _, selected, _ ->
        val panel = JPanel(BorderLayout(10, 0))
        panel.isOpaque = true
        panel.background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
        panel.border = JBUI.Borders.empty(5, 9)
        val fg = if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getLabelForeground()
        val glyph = JBLabel(value.glyph); glyph.foreground = if (selected) fg else ACCENT
        panel.add(glyph, BorderLayout.WEST)
        val col = JPanel(); col.layout = BoxLayout(col, BoxLayout.Y_AXIS); col.isOpaque = false
        val cur = ClaudeSettings.getInstance().state.permissionMode ?: "acceptEdits"
        val title = JBLabel(value.title + if (value.value == cur) "   ✓" else "")
        title.foreground = fg; title.font = title.font.deriveFont(Font.BOLD)
        val desc = JBLabel(value.desc)
        desc.foreground = if (selected) fg else UIUtil.getContextHelpForeground()
        desc.font = desc.font.deriveFont(JBUI.scale(11f))
        col.add(title); col.add(desc)
        panel.add(col, BorderLayout.CENTER)
        panel
    }

    private fun setMode(value: String) { ClaudeSettings.getInstance().state.permissionMode = value; updateModeChip() }
    private fun updateModeChip() {
        val cur = ClaudeSettings.getInstance().state.permissionMode ?: "acceptEdits"
        val m = modes.firstOrNull { it.value == cur } ?: modes[1]
        modeChip.text = m.glyph + "  " + m.title + "  ▾"
    }

    // ---------- event intake ----------

    private fun onLine(line: String) {
        ApplicationManager.getApplication().invokeLater({ handleEvent(line) }, ModalityState.any())
    }

    private fun handleEvent(line: String) {
        val o = try { JsonParser.parseString(line).asJsonObject } catch (e: Exception) { return }
        try {
            when (o.str("type")) {
                "__panel" -> onPanel(o)
                "system" -> onSystem(o)
                "stream_event" -> o.objOrNull("event")?.let { onStream(it) }
                "assistant" -> onAssistant(o)
                "user" -> onUser(o)
                "result" -> onResult(o)
                "control_request" -> onControlRequest(o)
            }
        } catch (e: Exception) {
        }
    }

    private fun onPanel(o: JsonObject) {
        when (o.str("subtype")) {
            "started" -> { setRunning(true); setStatus("Starting Claude…") }
            "cleared" -> clearAll()
            "config" -> applyConfigToUi()
            "error" -> { addInfo(o.str("text") ?: "Error", true); setRunning(false) }
            "exited" -> { finalizeCurrent(); inAssistant = false; setRunning(false); val c = o.intOrNull("code"); if (c != null && c != 0) setStatus("Claude exited (code $c)") }
            "stderr" -> {}
        }
    }

    private fun onSystem(o: JsonObject) {
        when (o.str("subtype")) {
            "init" -> setRunning(true)
            "status" -> if (o.str("status") == "requesting") { setStatus("Thinking…"); feed(interpreter.status("Thinking")) }
            "thinking_tokens" -> setStatus("Thinking… (" + (o.intOrNull("estimated_tokens") ?: 0) + " tokens)")
        }
    }

    private fun onStream(ev: JsonObject) {
        when (ev.str("type")) {
            "message_start" -> beginAssistant()
            "content_block_start" -> blockStart(ev.objOrNull("content_block"))
            "content_block_delta" -> blockDelta(ev.objOrNull("delta"))
            "content_block_stop" -> blockStop()
        }
    }

    private fun beginAssistant() {
        finalizeCurrent()
        setStatus("")
        val turn = AssistantTurn()
        curTurn = turn
        turns.add(turn)
        addRow(turn)
        inAssistant = true; sawStream = false
        resetBlock()
    }
    private fun ensureAssistant(): AssistantTurn { if (!inAssistant || curTurn == null) beginAssistant(); return curTurn!! }
    private fun resetBlock() { curType = null; curText = null; curThinking = null; curTool = null; curToolName = null; curToolId = null; curToolJson.setLength(0) }

    private fun blockStart(cb: JsonObject?) {
        val turn = ensureAssistant()
        finalizeCurrent()
        when (val t = cb?.str("type") ?: "text") {
            "text" -> { val tb = TextBlock(); turn.addBlock(tb); curText = tb; curType = "text" }
            "thinking" -> { val th = ThinkingBlock(); turn.addBlock(th); curThinking = th; curType = "thinking" }
            "tool_use" -> {
                val card = ToolCard(cb?.str("name") ?: "tool"); turn.addBlock(card)
                curTool = card; curToolName = cb?.str("name") ?: "tool"; curToolId = cb?.str("id"); curToolJson.setLength(0)
                curType = "tool"
            }
            else -> curType = t
        }
    }

    private fun blockDelta(delta: JsonObject?) {
        val d = delta ?: return
        sawStream = true
        when (d.str("type")) {
            "text_delta" -> curText?.append(d.str("text") ?: "")
            "thinking_delta" -> curThinking?.append(d.str("thinking") ?: "")
            "input_json_delta" -> curToolJson.append(d.str("partial_json") ?: "")
        }
        scrollToBottomSoon()
    }

    private fun blockStop() {
        when (curType) {
            "text" -> curText?.finalizeMarkdown()
            "tool" -> curTool?.let { renderToolBody(curToolName ?: "tool", curToolId, parseObj(curToolJson.toString()), it) }
        }
        curType = null
    }

    private fun finalizeCurrent() { if (curType == "text") curText?.finalizeMarkdown(); curType = null }

    private fun onAssistant(o: JsonObject) {
        val content = o.objOrNull("message")?.get("content") ?: return
        if (!content.isJsonArray) return
        val turn = ensureAssistant()
        for (el in content.asJsonArray) {
            val b = el.asJsonObject
            when (b.str("type")) {
                "tool_use" -> {
                    val id = b.str("id")
                    if (!sawStream && (id == null || !renderedTools.contains(id))) {
                        val card = ToolCard(b.str("name") ?: "tool"); turn.addBlock(card)
                        renderToolBody(b.str("name") ?: "tool", id, b.objOrNull("input"), card)
                    }
                }
                "text" -> if (!sawStream) { val tb = TextBlock(); turn.addBlock(tb); tb.setMarkdown(b.str("text") ?: "") }
                "thinking" -> if (!sawStream) { val th = ThinkingBlock(); turn.addBlock(th); th.append(b.str("thinking") ?: "") }
            }
        }
        scrollToBottomSoon()
    }

    private fun onUser(o: JsonObject) {
        val content = o.objOrNull("message")?.get("content") ?: return
        if (!content.isJsonArray) return
        for (el in content.asJsonArray) {
            val b = el.asJsonObject
            if (b.str("type") == "tool_result") {
                val id = b.str("tool_use_id")
                val isErr = b.has("is_error") && b.get("is_error").let { it.isJsonPrimitive && it.asBoolean }
                val text = extractText(b.get("content"))
                feed(interpreter.toolResult(id, text, isErr))
                id?.let { toolCardsById[it] }?.addResult(text, isErr)
            }
        }
        scrollToBottomSoon()
    }

    private fun onResult(o: JsonObject) {
        finalizeCurrent(); inAssistant = false; setRunning(false)
        val isErr = o.has("is_error") && o.get("is_error").let { it.isJsonPrimitive && it.asBoolean }
        feed(interpreter.taskDone(o.str("result") ?: "", isErr))
        val parts = ArrayList<String>()
        o.dblOrNull("total_cost_usd")?.let { parts.add("$" + String.format("%.4f", it)) }
        o.dblOrNull("duration_ms")?.let { parts.add(String.format("%.1fs", it / 1000)) }
        o.intOrNull("num_turns")?.let { parts.add("$it turn" + if (it > 1) "s" else "") }
        setStatus(parts.joinToString("   ·   "))
        if (o.has("is_error") && o.get("is_error").let { it.isJsonPrimitive && it.asBoolean }) {
            o.str("result")?.let { addInfo(it, true) }
        }
    }

    // ---------- tool body rendering (into a card's styled pane) ----------

    private fun renderToolBody(name: String, id: String?, input: JsonObject?, card: ToolCard) {
        target = card.bodyDoc
        card.setSummary(renderToolContent(name, input ?: JsonObject()))
        target = null
        if (name == "Edit" || name == "Write" || name == "MultiEdit") card.expand()
        if (id != null) { renderedTools.add(id); toolCardsById[id] = card }
        feed(interpreter.toolUse(id, name, input))
    }

    /** Renders a tool's command/diff/inputs into the current [target] doc; returns a header summary. */
    private fun renderToolContent(name: String, inp: JsonObject): String {
        var summary = ""
        when (name) {
            "Bash" -> {
                inp.str("description")?.let { insert("$it\n", sMuted) }
                val cmd = inp.str("command") ?: ""
                insert("$ " + cmd + "\n", sCode); summary = oneLine(cmd, 72)
            }
            "Edit" -> { summary = shortPath(inp.str("file_path")); renderDiff(inp.str("old_string") ?: "", inp.str("new_string") ?: "") }
            "MultiEdit" -> {
                summary = shortPath(inp.str("file_path"))
                val edits = if (inp.has("edits") && inp.get("edits").isJsonArray) inp.getAsJsonArray("edits") else null
                edits?.forEach { e -> val eo = e.asJsonObject; renderDiff(eo.str("old_string") ?: "", eo.str("new_string") ?: "") }
            }
            "Write" -> { summary = shortPath(inp.str("file_path")); renderDiff("", inp.str("content") ?: "") }
            "Read" -> { summary = shortPath(inp.str("file_path")); insert(shortPath(inp.str("file_path")) + "\n", sMuted) }
            "Grep" -> { summary = inp.str("pattern") ?: ""; insert("pattern: " + (inp.str("pattern") ?: "") + "\n", sMuted); inp.str("path")?.let { insert("path: $it\n", sMuted) } }
            "Glob" -> { summary = inp.str("pattern") ?: ""; insert("pattern: " + (inp.str("pattern") ?: "") + "\n", sMuted) }
            "WebFetch" -> { summary = inp.str("url") ?: ""; insert((inp.str("url") ?: "") + "\n", sMuted) }
            "WebSearch" -> { summary = inp.str("query") ?: ""; insert((inp.str("query") ?: "") + "\n", sMuted) }
            "TodoWrite" -> {
                val todos = if (inp.has("todos") && inp.get("todos").isJsonArray) inp.getAsJsonArray("todos") else null
                summary = (todos?.size() ?: 0).toString() + " items"
                todos?.forEach { t ->
                    val to = t.asJsonObject
                    val mark = when (to.str("status")) { "completed" -> "✓ "; "in_progress" -> "▸ "; else -> "• " }
                    insert(mark + (to.str("content") ?: "") + "\n", sMuted)
                }
            }
            else -> if (inp.size() > 0) insert(inp.toString() + "\n", sCode)
        }
        return summary
    }

    // ---------- tool-permission approval (control protocol) ----------

    private fun onControlRequest(o: JsonObject) {
        val reqId = o.str("request_id") ?: return
        val req = o.objOrNull("request") ?: return
        when (req.str("subtype")) {
            "can_use_tool" -> showApproval(reqId, req)
            else -> session.respondControlError(reqId, "unsupported control request")
        }
    }

    private fun showApproval(reqId: String, req: JsonObject) {
        val turn = ensureAssistant()
        val toolName = req.str("tool_name") ?: "tool"
        val input = req.objOrNull("input") ?: JsonObject()
        val inputJson = input.toString()
        val title = req.str("title") ?: "Allow $toolName?"
        val suggestions = if (req.has("permission_suggestions") && req.get("permission_suggestions").isJsonArray)
            req.getAsJsonArray("permission_suggestions").toString() else null
        val block = ApprovalBlock(
            title, toolName, input,
            onAllow = { session.respondAllow(reqId, inputJson, null) },
            onAllowAlways = if (suggestions != null) ({ session.respondAllow(reqId, inputJson, suggestions) }) else null,
            onDeny = { session.respondDeny(reqId, "Denied by user") },
        )
        turn.addBlock(block)
        scrollToBottomSoon()
    }

    private fun renderDiff(oldStr: String, newStr: String) {
        for (r in lineDiff(oldStr, newStr)) {
            val style = when (r.first) { "add" -> sDiffAdd; "del" -> sDiffDel; else -> sCode }
            val sign = when (r.first) { "add" -> "+ "; "del" -> "- "; else -> "  " }
            insert(sign + r.second + "\n", style)
        }
    }

    private fun insertMarkdown(src: String) {
        val fence = Regex("```[\\w+#.\\-]*\\n?([\\s\\S]*?)```")
        var idx = 0
        for (m in fence.findAll(src)) {
            insertInline(src.substring(idx, m.range.first))
            insert(m.groupValues[1].trimEnd('\n') + "\n", sCode)
            idx = m.range.last + 1
        }
        if (idx <= src.length) insertInline(src.substring(idx))
    }

    private fun insertInline(s: String) {
        var i = 0
        val n = s.length
        while (i < n) {
            val tick = s.indexOf('`', i)
            val bold = s.indexOf("**", i)
            val next = listOf(tick, bold).filter { it >= 0 }.minOrNull() ?: -1
            if (next < 0) { insert(s.substring(i), sNormal); break }
            if (next > i) insert(s.substring(i, next), sNormal)
            if (next == tick && (bold < 0 || tick <= bold)) {
                val end = s.indexOf('`', tick + 1)
                if (end < 0) { insert(s.substring(tick), sNormal); break }
                insert(s.substring(tick + 1, end), sCode); i = end + 1
            } else {
                val end = s.indexOf("**", bold + 2)
                if (end < 0) { insert(s.substring(bold), sNormal); break }
                insert(s.substring(bold + 2, end), sBold); i = end + 2
            }
        }
    }

    private fun insert(text: String, style: AttributeSet) {
        val d = target ?: return
        try { d.insertString(d.length, text, style) } catch (e: BadLocationException) {}
    }

    // ---------- helpers ----------

    private fun setRunning(v: Boolean) {
        running = v
        SwingUtilities.invokeLater {
            spinner.isVisible = v
            if (v) spinner.resume() else spinner.suspend()
            if (v) { sendCircle.text = "■"; sendCircle.setBg(STOP_RED); sendCircle.toolTipText = "Stop" }
            else { sendCircle.text = "↑"; sendCircle.setBg(ACCENT); sendCircle.toolTipText = "Send" }
        }
    }
    private fun setStatus(text: String) { SwingUtilities.invokeLater { statusLabel.text = if (text.isEmpty()) " " else text } }

    private fun clearAll() {
        transcript.removeAll()
        toolCardsById.clear(); renderedTools.clear(); turns.clear(); inAssistant = false; curTurn = null; resetBlock(); setStatus("")
        interpreter.reset(); activityMap.clearSession()
        transcript.revalidate(); transcript.repaint()
        addInfo("New conversation.", false)
    }

    private fun applyConfigToUi() {
        val s = ClaudeSettings.getInstance().state
        SwingUtilities.invokeLater {
            modelCombo.selectedItem = s.model ?: ""; updateModeChip()
            if (s.showActivityMap != mapVisible) setMapVisible(s.showActivityMap)
        }
    }

    private fun openSettings() { ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java) }

    private fun setShowDetails(v: Boolean) {
        showDetails = v
        ClaudeSettings.getInstance().state.showDetails = v
        turns.forEach { it.applyDetails(v) }
        updateDetailsButton()
        relayout()
    }
    private fun updateDetailsButton() {
        detailsButton.text = if (showDetails) "Hide details" else "Details"
    }

    private fun setMapVisible(v: Boolean) {
        mapVisible = v
        ClaudeSettings.getInstance().state.showActivityMap = v
        updateMapButton()
        installCenter()
    }
    private fun updateMapButton() { mapButton.text = if (mapVisible) "Hide map" else "Activity map" }
    private fun installCenter() {
        mapSplitter.secondComponent = if (mapVisible) activityMap.component else null
        SwingUtilities.invokeLater { mapSplitter.revalidate(); mapSplitter.repaint() }
    }

    /** Push normalised activity events into the map (no-op when the batch is empty). */
    private fun feed(events: List<AgentActivityEvent>) { if (events.isNotEmpty()) activityMap.apply(events) }

    private fun scrollToBottomSoon() {
        if (pendingScroll) return
        pendingScroll = true
        SwingUtilities.invokeLater {
            pendingScroll = false
            transcript.revalidate(); transcript.repaint()
            val bar = scroll.verticalScrollBar; bar.value = bar.maximum
        }
    }
    private fun relayout() { SwingUtilities.invokeLater { transcript.revalidate(); transcript.repaint() } }

    private fun labelRenderer(mapper: (String?) -> String) = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean): Component =
            super.getListCellRendererComponent(list, mapper(value as? String), index, sel, focus)
    }
    private fun cap(s: String) = if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)
    private fun shortPath(p: String?): String { if (p.isNullOrEmpty()) return ""; return if (p.length > 72) "…" + p.substring(p.length - 71) else p }
    private fun oneLine(s: String, max: Int): String { val one = s.replace("\n", " ").trim(); return if (one.length > max) one.substring(0, max) + "…" else one }
    private fun truncate(t: String): String {
        val lines = t.split("\n")
        if (lines.size > 40) return lines.take(40).joinToString("\n") + "\n…(" + (lines.size - 40) + " more lines)"
        return if (t.length > 4000) t.substring(0, 4000) + "…" else t
    }

    private fun extractText(e: JsonElement?): String {
        if (e == null || e.isJsonNull) return ""
        if (e.isJsonPrimitive) return e.asString
        if (e.isJsonArray) return e.asJsonArray.joinToString("\n") { el ->
            if (el.isJsonPrimitive) el.asString
            else if (el.isJsonObject && el.asJsonObject.has("text")) el.asJsonObject.str("text") ?: "" else ""
        }
        return e.toString()
    }

    private fun lineDiff(aStr: String, bStr: String): List<Pair<String, String>> {
        val a = aStr.split("\n"); val b = bStr.split("\n")
        val n = a.size; val m = b.size
        if (n.toLong() * m > 400_000L) return a.map { "del" to it } + b.map { "add" to it }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = ArrayList<Pair<String, String>>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) { out.add("ctx" to a[i]); i++; j++ }
            else if (dp[i + 1][j] >= dp[i][j + 1]) { out.add("del" to a[i]); i++ }
            else { out.add("add" to b[j]); j++ }
        }
        while (i < n) out.add("del" to a[i++])
        while (j < m) out.add("add" to b[j++])
        return out
    }

    private fun JsonObject.str(k: String): String? = if (has(k) && get(k).isJsonPrimitive) get(k).asString else null
    private fun JsonObject.intOrNull(k: String): Int? = if (has(k) && get(k).isJsonPrimitive) get(k).asInt else null
    private fun JsonObject.dblOrNull(k: String): Double? = if (has(k) && get(k).isJsonPrimitive) get(k).asDouble else null
    private fun JsonObject.objOrNull(k: String): JsonObject? = if (has(k) && get(k).isJsonObject) getAsJsonObject(k) else null
    private fun parseObj(s: String): JsonObject? = try { if (s.isBlank()) null else JsonParser.parseString(s).asJsonObject } catch (e: Exception) { null }

    private fun styleChip(b: JButton) {
        b.isContentAreaFilled = false; b.isFocusPainted = false; b.isOpaque = false
        b.border = BorderFactory.createCompoundBorder(RoundedLineBorder(JBColor.border(), JBUI.scale(12)), JBUI.Borders.empty(3, 9))
        b.foreground = UIUtil.getLabelForeground()
        b.font = b.font.deriveFont(JBUI.scale(12f))
        b.cursor = hand
    }

    private fun flatGlyph(glyph: String, tip: String, onClick: (JButton) -> Unit): JButton {
        val b = JButton(glyph)
        b.toolTipText = tip
        b.isContentAreaFilled = false; b.isBorderPainted = false; b.isFocusPainted = false; b.isOpaque = false
        b.foreground = UIUtil.getContextHelpForeground()
        b.font = b.font.deriveFont(JBUI.scale(16f))
        b.border = JBUI.Borders.empty(2, 5)
        b.cursor = hand
        b.addActionListener { onClick(b) }
        return b
    }

    private fun boxedGlyph(glyph: String, tip: String, onClick: (JButton) -> Unit): JButton {
        val b = JButton(glyph)
        b.toolTipText = tip
        b.isContentAreaFilled = false; b.isFocusPainted = false; b.isOpaque = false; b.isBorderPainted = true
        b.foreground = UIUtil.getContextHelpForeground()
        b.font = b.font.deriveFont(JBUI.scale(13f))
        b.border = BorderFactory.createCompoundBorder(RoundedLineBorder(JBColor.border(), JBUI.scale(8)), JBUI.Borders.empty(2, 7))
        b.cursor = hand
        b.addActionListener { onClick(b) }
        return b
    }

    private fun iconFor(name: String): String = when (name) {
        "Bash" -> "$"; "Edit", "MultiEdit", "Write" -> "✎"; "Read" -> "▤"
        "Grep", "Glob", "WebSearch" -> "⌕"; "WebFetch" -> "↗"; "TodoWrite" -> "☑"; else -> "▶"
    }

    override fun dispose() { session.dispose() }

    // ---------- block components ----------

    private abstract inner class Block : JPanel() {
        init { alignmentX = Component.LEFT_ALIGNMENT; isOpaque = false }
        override fun getMaximumSize(): Dimension = Dimension(Integer.MAX_VALUE, preferredSize.height)
    }

    private inner class AssistantTurn : Block() {
        private val body = JPanel()
        private var textCount = 0
        private val detailKids = ArrayList<Component>()
        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(2, 4, 4, 4)
            val role = JBLabel("Claude")
            role.foreground = mutedFg(); role.font = role.font.deriveFont(Font.BOLD, JBUI.scale(11f)); role.border = JBUI.Borders.emptyBottom(2)
            add(role, BorderLayout.NORTH)
            body.layout = BoxLayout(body, BoxLayout.Y_AXIS); body.isOpaque = false; body.alignmentX = Component.LEFT_ALIGNMENT
            add(body, BorderLayout.CENTER)
            refreshVisibility()
        }
        fun addBlock(c: JComponent) {
            val strut = Box.createVerticalStrut(JBUI.scale(4))
            body.add(fullWidth(c)); body.add(strut)
            // Text and approval prompts are always shown; thinking/tool cards are "details".
            if (c is TextBlock || c is ApprovalBlock) textCount++
            if (c is ThinkingBlock || c is ToolCard) {
                c.isVisible = showDetails; strut.isVisible = showDetails
                detailKids.add(c); detailKids.add(strut)
            }
            refreshVisibility(); relayout()
        }
        fun applyDetails(show: Boolean) { detailKids.forEach { it.isVisible = show }; refreshVisibility() }
        // In compact mode a turn that produced only thinking/tools collapses away entirely.
        private fun refreshVisibility() { isVisible = showDetails || textCount > 0 }
    }

    private inner class TextBlock : Block() {
        private val pane = styledPane()
        val doc: StyledDocument get() = pane.styledDocument
        private val sb = StringBuilder()
        init { layout = BorderLayout(); add(pane, BorderLayout.CENTER) }
        fun append(t: String) { sb.append(t); ins(doc, t, sNormal); scrollToBottomSoon() }
        fun finalizeMarkdown() {
            val raw = sb.toString()
            try { doc.remove(0, doc.length) } catch (e: BadLocationException) {}
            target = doc; insertMarkdown(raw); target = null
            relayout()
        }
        fun setMarkdown(t: String) { sb.setLength(0); sb.append(t); try { doc.remove(0, doc.length) } catch (e: Exception) {}; target = doc; insertMarkdown(t); target = null; relayout() }
        private fun ins(d: StyledDocument, t: String, s: AttributeSet) { try { d.insertString(d.length, t, s) } catch (e: BadLocationException) {} }
    }

    private inner class ThinkingBlock : Block() {
        private val header = JBLabel("▸ ✱ Thinking")
        private val area = plainArea("")
        private var open = false
        init {
            layout = BorderLayout()
            header.foreground = mutedFg(); header.cursor = hand; header.border = JBUI.Borders.empty(1, 0)
            header.font = header.font.deriveFont(Font.ITALIC)
            header.addMouseListener(click { toggle() })
            area.foreground = mutedFg(); area.font = area.font.deriveFont(Font.ITALIC); area.isVisible = false
            area.border = JBUI.Borders.empty(1, 12, 2, 0)
            add(header, BorderLayout.NORTH); add(area, BorderLayout.CENTER)
        }
        fun append(t: String) { area.text = area.text + t; scrollToBottomSoon() }
        private fun toggle() { open = !open; area.isVisible = open; header.text = (if (open) "▾" else "▸") + " ✱ Thinking"; relayout() }
    }

    private inner class ToolCard(name: String) : Block() {
        private val bodyPane = styledPane()
        val bodyDoc: StyledDocument get() = bodyPane.styledDocument
        private val summary = JBLabel("")
        private val chevron = JBLabel("▸")
        private val bodyWrap = JPanel(BorderLayout())
        private var open = false
        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(1, 0)
            val head = JPanel(BorderLayout(6, 0)); head.isOpaque = false; head.border = JBUI.Borders.empty(5, 8); head.cursor = hand
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)); left.isOpaque = false
            val icon = JBLabel(iconFor(name)); icon.foreground = ACCENT; icon.font = icon.font.deriveFont(Font.BOLD)
            val title = JBLabel(name); title.font = title.font.deriveFont(Font.BOLD)
            left.add(icon); left.add(title)
            head.add(left, BorderLayout.WEST)
            summary.foreground = mutedFg(); head.add(summary, BorderLayout.CENTER)
            chevron.foreground = mutedFg(); head.add(chevron, BorderLayout.EAST)
            head.addMouseListener(click { toggle() })
            add(head, BorderLayout.NORTH)
            bodyWrap.isOpaque = false; bodyWrap.border = JBUI.Borders.empty(0, 10, 6, 8); bodyWrap.add(bodyPane, BorderLayout.CENTER)
            bodyWrap.isVisible = false
            add(bodyWrap, BorderLayout.CENTER)
        }
        fun setSummary(s: String) { summary.text = s; relayout() }
        fun addResult(text: String, isErr: Boolean) {
            target = bodyDoc
            if (bodyDoc.length > 0) insert("\n", sMuted)
            insert(if (isErr) "⚠ " else "↳ ", if (isErr) sError else sMuted)
            insert(truncate(text).ifBlank { if (isErr) "(error)" else "(no output)" } + "\n", sMuted)
            target = null
            if (isErr) expand()
            relayout()
        }
        fun expand() { if (!open) toggle() }
        private fun toggle() { open = !open; bodyWrap.isVisible = open; chevron.text = if (open) "▾" else "▸"; relayout() }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(12)
            g2.color = cardBg()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    /** Always-visible approval prompt for a can_use_tool control request. */
    private inner class ApprovalBlock(
        title: String, toolName: String, input: JsonObject,
        onAllow: () -> Unit, onAllowAlways: (() -> Unit)?, onDeny: () -> Unit,
    ) : Block() {
        private val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        private val decided = JBLabel("")
        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8, 10)
            val head = JBLabel("🔒 $title")
            head.foreground = ACCENT; head.font = head.font.deriveFont(Font.BOLD); head.border = JBUI.Borders.emptyBottom(5)
            add(head, BorderLayout.NORTH)

            val pane = styledPane()
            target = pane.styledDocument; renderToolContent(toolName, input); target = null
            val mid = JPanel(BorderLayout()); mid.isOpaque = false; mid.border = JBUI.Borders.emptyBottom(6); mid.add(pane, BorderLayout.CENTER)
            add(mid, BorderLayout.CENTER)

            buttons.isOpaque = false
            val allow = JButton("Allow"); allow.addActionListener { onAllow(); resolve("✓ Allowed") }; buttons.add(allow)
            if (onAllowAlways != null) {
                val aa = JButton("Allow always"); aa.addActionListener { onAllowAlways(); resolve("✓ Always allowed") }; buttons.add(aa)
            }
            val deny = JButton("Deny"); deny.addActionListener { onDeny(); resolve("✗ Denied") }; buttons.add(deny)
            decided.foreground = mutedFg()
            val south = JPanel(BorderLayout()); south.isOpaque = false
            south.add(buttons, BorderLayout.WEST); south.add(decided, BorderLayout.EAST)
            add(south, BorderLayout.SOUTH)
        }
        private fun resolve(text: String) { buttons.isVisible = false; decided.text = text; relayout() }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(12)
            g2.color = shift(editorBg(), 24, -16)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = ACCENT
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    // ---------- custom-painted composer widgets ----------

    private inner class RoundButton(glyph: String) : JButton(glyph) {
        private var bg: Color = ACCENT
        init {
            isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false; isOpaque = false
            foreground = Color.WHITE
            val d = Dimension(JBUI.scale(30), JBUI.scale(30)); preferredSize = d; minimumSize = d; maximumSize = d
            font = font.deriveFont(Font.BOLD, JBUI.scale(14f)); cursor = hand
        }
        fun setBg(c: Color) { bg = c; repaint() }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = bg
            val s = minOf(width, height) - 1
            g2.fillOval((width - s) / 2, (height - s) / 2, s, s)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private inner class RoundedPanel : JPanel() {
        var focused = false
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(16)
            g2.color = shift(editorBg(), 16, -10)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = if (focused) ACCENT else JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private inner class Bubble : JPanel(BorderLayout()) {
        init { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        override fun getMaximumSize(): Dimension = Dimension(Integer.MAX_VALUE, preferredSize.height)
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(14)
            g2.color = cardBg()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
