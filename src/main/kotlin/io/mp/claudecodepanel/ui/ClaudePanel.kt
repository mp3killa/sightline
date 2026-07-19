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
import com.intellij.openapi.diagnostic.thisLogger
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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.activity.ActivityInterpreter
import io.mp.claudecodepanel.activity.AgentActivityEvent
import io.mp.claudecodepanel.activity.BuildReportScanner
import io.mp.claudecodepanel.activity.ErrorObserved
import io.mp.claudecodepanel.activity.OutputParsers
import io.mp.claudecodepanel.process.ClaudeSession
import io.mp.claudecodepanel.settings.ClaudeSettings
import io.mp.claudecodepanel.settings.ClaudeSettingsConfigurable
import io.mp.claudecodepanel.theme.ClaudeIcons
import io.mp.claudecodepanel.theme.ClaudeUiTokens
import io.mp.claudecodepanel.ui.components.EmptyStatePanel
import io.mp.claudecodepanel.ui.state.ComposerModel
import io.mp.claudecodepanel.ui.state.LayoutProfile
import io.mp.claudecodepanel.ui.state.PermissionModes
import io.mp.claudecodepanel.ui.state.ResponsiveLayout
import io.mp.claudecodepanel.ui.state.StatusKind
import io.mp.claudecodepanel.ui.state.StatusModel
import io.mp.claudecodepanel.ui.state.StatusView
import io.mp.claudecodepanel.ui.state.TranscriptPresenter
import io.mp.claudecodepanel.ui.state.WorkspaceMode
import io.mp.claudecodepanel.ui.state.WorkspaceModes
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

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
 * Native Swing chat panel for Claude Code. Four regions: a compact [ClaudeToolHeader], the primary
 * workspace (transcript / activity map / split), a coordinated [ClaudeStatusStrip], and the
 * [ClaudeComposerPanel]. Stream parsing feeds both the transcript blocks and a normalised activity
 * stream shared by the activity map and the status model.
 */
class ClaudePanel(private val project: Project, parent: Disposable) : Disposable {

    val component: JComponent
    private val session: ClaudeSession = ClaudeSession(project) { line -> onLine(line) }
    private val interpreter = ActivityInterpreter()
    private val activityMap = ActivityMapPanel(project, parent)

    private val statusModel = StatusModel()
    private val composerModel = ComposerModel()
    private val transcriptPresenter = TranscriptPresenter()

    private val mapSplitter = JBSplitter(false, 0.58f)
    private val centerHost = JPanel(BorderLayout())
    private val chatHost = JPanel(BorderLayout())

    private lateinit var header: ClaudeToolHeader
    private lateinit var composer: ClaudeComposerPanel
    private lateinit var statusStrip: ClaudeStatusStrip
    private lateinit var emptyState: EmptyStatePanel

    private enum class ViewMode { CHAT, SPLIT, MAP }
    private var viewMode = ViewMode.SPLIT
    private var lastProfile: LayoutProfile? = null

    private val transcript = object : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = ClaudeUiTokens.surface()
            border = JBUI.Borders.empty(12, 14)
        }
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(20)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(120)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
    private val scroll = JBScrollPane(transcript)

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
    private var malformedEventCount = 0

    // After a build/test/analysis command, its structured report files are read off-EDT for richer
    // results than the console gives. tool_use_id -> (command, start millis).
    private val reportScanner = BuildReportScanner()
    private val pendingReportScans = HashMap<String, Pair<String, Long>>()
    private val toolCardsById = HashMap<String, ToolCard>()
    private val renderedTools = HashSet<String>()
    private var pendingScroll = false
    private var showDetails = ClaudeSettings.getInstance().state.showDetails
    private var completionMeta: String? = null
    private val turns = ArrayList<AssistantTurn>()

    private val modes = PermissionModes.all

    init {
        Disposer.register(parent, this)
        initStyles()
        viewMode = initialViewMode()
        component = build()
        applyConfigToUi()
        installEmptyState()
        installResponsive()
        refreshStatus()
    }

    // ---------- UI ----------

    private fun build(): JComponent {
        val root = JPanel(BorderLayout())

        header = ClaudeToolHeader(
            initialMode = toWorkspace(viewMode),
            onWorkspace = { mode -> setWorkspace(if (mode == WorkspaceMode.CHAT) ViewMode.CHAT else ViewMode.MAP) },
            onToggleSplit = { toggleSplit() },
            onNew = { session.newConversation() },
            onMore = { anchor -> showMoreMenu(anchor) },
        )
        root.add(header, BorderLayout.NORTH)

        scroll.border = BorderFactory.createEmptyBorder()
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scroll.viewport.background = ClaudeUiTokens.surface()
        chatHost.add(scroll, BorderLayout.CENTER)
        mapSplitter.setHonorComponentsMinimumSize(false)
        installCenter()
        root.add(centerHost, BorderLayout.CENTER)

        composer = ClaudeComposerPanel(
            model = composerModel,
            onSend = { text -> doSend(text) },
            onStop = { stopRequest() },
            onAttach = { attachFile() },
            onSlash = { anchor -> showSlashMenu(anchor) },
            onModeMenu = { anchor -> showModesPopup(anchor) },
        )
        statusStrip = ClaudeStatusStrip(this)

        val south = JPanel(BorderLayout())
        south.add(statusStrip, BorderLayout.NORTH)
        south.add(composer, BorderLayout.CENTER)
        root.add(south, BorderLayout.SOUTH)

        updateModeChip()
        installShortcuts(root)
        return root
    }

    private fun installEmptyState() {
        emptyState = EmptyStatePanel(
            icon = ClaudeIcons.brand,
            heading = "What are we working on?",
            description = "Ask about this project, or start from one of these.",
            actions = listOf(
                "Explain the current file" to { doSend("Explain what the currently open file does and how it fits into this project.") },
                "Fix an issue" to { starter("Fix this bug: ") },
                "Plan a feature" to { starter("Plan a feature: ") },
                "Catch up on this project" to { primeProject() },
            ),
        )
        showEmptyState(transcriptPresenter.showEmptyState)
    }

    private fun starter(prefix: String) {
        composer.insertContextText(prefix)
        composer.requestInputFocus()
    }

    private fun installShortcuts(root: JComponent) {
        // Escape stops an in-flight request; does not hijack editor shortcuts.
        root.registerKeyboardAction(
            { if (running) stopRequest() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
        )
    }

    private fun installResponsive() {
        component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyProfile()
        })
        SwingUtilities.invokeLater { applyProfile() }
    }

    private fun applyProfile() {
        // Ignore pre-layout (0/near-0 width) passes so we don't transiently downgrade the layout.
        if (component.width < JBUI.scale(80)) return
        val scale = JBUI.scale(1000) / 1000f
        val profile = ResponsiveLayout.profile(component.width, scale)
        header.applyProfile(profile)
        activityMap.applyProfile(profile)
        // Narrow panels can't split — fall back to the activity view.
        if (profile == LayoutProfile.NARROW && viewMode == ViewMode.SPLIT) setWorkspace(ViewMode.MAP)
        // Constrain the transcript to a comfortable reading width once wide.
        val maxW = ResponsiveLayout.maxReadableWidth(profile)
        val hpad = if (maxW != Int.MAX_VALUE) {
            ((scroll.viewport.width - JBUI.scale(maxW)) / 2).coerceIn(JBUI.scale(14), JBUI.scale(400))
        } else JBUI.scale(14)
        transcript.border = JBUI.Borders.empty(12, hpad)
        if (profile != lastProfile) { lastProfile = profile; transcript.revalidate(); transcript.repaint() }
    }

    private fun initStyles() {
        val base = UIUtil.getLabelFont()
        val mono = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        val fg = ClaudeUiTokens.textPrimary()
        val muted = ClaudeUiTokens.textSecondary()
        StyleConstants.setFontFamily(sNormal, base.family); StyleConstants.setFontSize(sNormal, base.size); StyleConstants.setForeground(sNormal, fg)
        clone(sMuted, sNormal); StyleConstants.setForeground(sMuted, muted)
        clone(sBold, sNormal); StyleConstants.setBold(sBold, true)
        clone(sError, sNormal); StyleConstants.setForeground(sError, ClaudeUiTokens.error())
        StyleConstants.setFontFamily(sCode, mono.family); StyleConstants.setFontSize(sCode, base.size); StyleConstants.setForeground(sCode, fg)
        clone(sDiffAdd, sCode); StyleConstants.setBackground(sDiffAdd, JBColor(Color(0xDD, 0xF4, 0xE4), Color(0x1E, 0x3A, 0x28)))
        clone(sDiffDel, sCode); StyleConstants.setBackground(sDiffDel, JBColor(Color(0xFB, 0xE4, 0xE1), Color(0x3A, 0x22, 0x22)))
    }

    private fun clone(dst: SimpleAttributeSet, src: SimpleAttributeSet) { dst.addAttributes(src) }
    private fun cardBg(): Color = ClaudeUiTokens.elevatedSurface()
    private fun mutedFg(): Color = ClaudeUiTokens.textSecondary()

    // ---------- transcript rows ----------

    private fun addRow(c: JComponent) {
        transcript.add(c)
        transcript.add(Box.createVerticalStrut(JBUI.scale(10)))
        scrollToBottomSoon()
    }

    private fun addInfo(text: String, err: Boolean) {
        val ta = plainArea(text)
        ta.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC)
        ta.foreground = if (err) ClaudeUiTokens.error() else mutedFg()
        val r = fullWidth(JPanel(BorderLayout())); r.border = JBUI.Borders.empty(2, 4); r.add(ta, BorderLayout.CENTER)
        addRow(r)
    }

    private fun addUserBubble(text: String, attachments: List<String>) {
        val bubble = Bubble()
        bubble.border = JBUI.Borders.empty(9, 12)
        val col = JPanel(); col.layout = BoxLayout(col, BoxLayout.Y_AXIS); col.isOpaque = false
        col.add(fullWidth(plainArea(text)))
        if (attachments.isNotEmpty()) {
            val ctx = plainArea("Context: " + attachments.joinToString(", ") { basename(it) })
            ctx.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, JBUI.scaleFontSize(11f).toFloat())
            ctx.foreground = mutedFg()
            col.add(fullWidth(ctx))
        }
        bubble.add(col, BorderLayout.CENTER)
        addRow(bubble)
    }

    private fun plainArea(text: String): JTextArea {
        val ta = JTextArea(text)
        ta.isEditable = false; ta.lineWrap = true; ta.wrapStyleWord = true; ta.isOpaque = false
        ta.border = JBUI.Borders.empty()
        ta.font = UIUtil.getLabelFont()
        ta.foreground = ClaudeUiTokens.textPrimary()
        return ta
    }

    private fun styledPane(): JTextPane {
        val p = JTextPane()
        p.isEditable = false; p.isOpaque = false
        p.border = JBUI.Borders.empty()
        p.font = UIUtil.getLabelFont()
        return p
    }

    private fun <T : JComponent> fullWidth(c: T): T { c.alignmentX = Component.LEFT_ALIGNMENT; c.isOpaque = false; return c }
    private fun click(run: () -> Unit) = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { run() } }

    // ---------- compose actions ----------

    private fun doSend(rawText: String) {
        if (running) return
        val text = rawText.trim()
        if (text.isEmpty()) return
        val attachments = composerModel.attachments
        val message = composerModel.buildMessage(rawText)
        finalizeCurrent(); inAssistant = false; curTurn = null
        addUserBubble(text, attachments)
        transcriptPresenter.onUserMessage(); showEmptyState(false)
        feed(interpreter.taskStarted(text))
        statusModel.taskStarted(); refreshStatus()
        session.sendUserMessage(message)
        composer.clearInput(); composerModel.clearAttachments(); composer.refreshChips()
        setRunning(true)
    }

    private fun attachFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val base = project.basePath
        val rel = if (base != null && file.path.startsWith(base)) file.path.substring(base.length).trimStart('/') else file.path
        composerModel.addAttachment(rel)
        composer.refreshChips()
    }

    private fun primeProject() {
        if (running) return
        finalizeCurrent(); inAssistant = false; curTurn = null
        addUserBubble("Catch up on this project — read the docs, generate any missing recommended ones (CLAUDE.md, ARCHITECTURE, conventions), and summarize.", emptyList())
        transcriptPresenter.onUserMessage(); showEmptyState(false)
        feed(interpreter.taskStarted("Catch up on this project and complete its docs"))
        statusModel.taskStarted(); refreshStatus()
        session.sendUserMessage(PRIME_PROMPT)
        setRunning(true)
    }

    private fun stopRequest() {
        if (!running) return
        session.stop()
        header.setSessionState(StatusKind.WORKING, "Stopping")
    }

    // ---------- menus ----------

    private fun showSlashMenu(anchor: Component) {
        val group = DefaultActionGroup()
        group.add(Separator.create("Context"))
        group.add(action("Catch up on project") { primeProject() })
        group.add(action("Attach file…") { attachFile() })
        group.add(Separator.create("Conversation"))
        group.add(action("Clear conversation") { session.newConversation() })
        popup("Actions", group, anchor)
    }

    private fun showMoreMenu(anchor: Component) {
        val group = DefaultActionGroup()
        group.add(action("Catch up on project") { primeProject() })
        group.add(action(if (showDetails) "Hide technical details" else "Show technical details") { setShowDetails(!showDetails) })
        group.add(Separator.getInstance())
        val modelGroup = DefaultActionGroup("Model", true)
        listOf("" to "Default", "opus" to "Opus", "sonnet" to "Sonnet", "haiku" to "Haiku").forEach { (v, label) ->
            modelGroup.add(action(label + if ((ClaudeSettings.getInstance().state.model ?: "") == v) "   ✓" else "") { setModel(v) })
        }
        group.add(modelGroup)
        group.add(Separator.getInstance())
        group.add(action("Clear conversation") { session.newConversation() })
        group.add(action("Activity map preferences…") { openSettings() })
        group.add(action("Settings…") { openSettings() })
        popup("More", group, anchor)
    }

    private fun setModel(value: String) {
        ClaudeSettings.getInstance().state.model = value
    }

    private fun popup(title: String, group: DefaultActionGroup, anchor: Component) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(title, group, SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showUnderneathOf(anchor)
    }

    private fun action(text: String, run: () -> Unit): AnAction = object : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) { run() }
    }

    private fun showModesPopup(anchor: Component) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(modes)
            .setTitle("Permission mode")
            .setRenderer(modeRenderer())
            .setItemChosenCallback { m -> setMode(m.value) }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun modeRenderer(): javax.swing.ListCellRenderer<PermissionModes.Info> = javax.swing.ListCellRenderer { _, value, _, selected, _ ->
        val panel = JPanel(BorderLayout(10, 0))
        panel.isOpaque = true
        panel.background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
        panel.border = JBUI.Borders.empty(5, 9)
        val fg = if (selected) UIUtil.getListSelectionForeground(true) else ClaudeUiTokens.textPrimary()
        val col = JPanel(); col.layout = BoxLayout(col, BoxLayout.Y_AXIS); col.isOpaque = false
        val cur = ClaudeSettings.getInstance().state.permissionMode ?: "auto"
        val title = JBLabel(value.shortName + if (value.value == cur) "   ✓" else "")
        title.foreground = if (value.dangerous && !selected) ClaudeUiTokens.warning() else fg
        title.font = title.font.deriveFont(Font.BOLD)
        val desc = JBLabel(value.description)
        desc.foreground = if (selected) fg else ClaudeUiTokens.textSecondary()
        desc.font = desc.font.deriveFont(JBUI.scaleFontSize(11f).toFloat())
        col.add(title); col.add(desc)
        panel.add(col, BorderLayout.CENTER)
        panel
    }

    private fun setMode(value: String) { ClaudeSettings.getInstance().state.permissionMode = value; updateModeChip() }
    private fun updateModeChip() {
        val m = PermissionModes.byValue(ClaudeSettings.getInstance().state.permissionMode)
        composer.setMode(m.shortName, m.dangerous)
    }

    // ---------- workspace ----------

    private fun toWorkspace(v: ViewMode): WorkspaceMode = when (v) {
        ViewMode.CHAT -> WorkspaceMode.CHAT
        ViewMode.SPLIT -> WorkspaceMode.SPLIT
        ViewMode.MAP -> WorkspaceMode.ACTIVITY
    }

    private fun initialViewMode(): ViewMode {
        val s = ClaudeSettings.getInstance().state
        return when (WorkspaceModes.fromSettings(s.showActivityMap, s.activityViewMode)) {
            WorkspaceMode.CHAT -> ViewMode.CHAT
            WorkspaceMode.SPLIT -> ViewMode.SPLIT
            WorkspaceMode.ACTIVITY -> ViewMode.MAP
        }
    }

    private fun setWorkspace(mode: ViewMode) {
        viewMode = mode
        val s = ClaudeSettings.getInstance().state
        val ws = toWorkspace(mode)
        s.showActivityMap = WorkspaceModes.toShowActivityMap(ws)
        s.activityViewMode = WorkspaceModes.toViewMode(ws)
        header.setWorkspace(ws)
        installCenter()
    }

    private fun toggleSplit() {
        setWorkspace(if (viewMode == ViewMode.SPLIT) ViewMode.MAP else ViewMode.SPLIT)
    }

    private fun installCenter() {
        centerHost.removeAll()
        when (viewMode) {
            ViewMode.CHAT -> centerHost.add(chatHost, BorderLayout.CENTER)
            ViewMode.MAP -> centerHost.add(activityMap.component, BorderLayout.CENTER)
            ViewMode.SPLIT -> {
                mapSplitter.firstComponent = chatHost
                mapSplitter.secondComponent = activityMap.component
                centerHost.add(mapSplitter, BorderLayout.CENTER)
            }
        }
        SwingUtilities.invokeLater { centerHost.revalidate(); centerHost.repaint() }
    }

    private fun showEmptyState(show: Boolean) {
        chatHost.removeAll()
        chatHost.add(if (show) emptyState else scroll, BorderLayout.CENTER)
        chatHost.revalidate(); chatHost.repaint()
    }

    // ---------- event intake ----------

    private fun onLine(line: String) {
        ApplicationManager.getApplication().invokeLater({ handleEvent(line) }, ModalityState.any())
    }

    private fun handleEvent(line: String) {
        val o = try {
            JsonParser.parseString(line).asJsonObject
        } catch (e: Exception) {
            noteMalformedEvent("<unparseable>", null, null, line.length, e, critical = false)
            return
        }
        val type = o.str("type")
        try {
            when (type) {
                "__panel" -> onPanel(o)
                "system" -> onSystem(o)
                "stream_event" -> o.objOrNull("event")?.let { onStream(it) }
                "assistant" -> onAssistant(o)
                "user" -> onUser(o)
                "result" -> onResult(o)
                "control_request" -> onControlRequest(o)
            }
        } catch (e: Exception) {
            // A malformed approval/result event can leave the UI inconsistent — surface those; ignore
            // best-effort stream deltas. Never log prompt/source/token content, only shape metadata.
            val critical = type == "control_request" || type == "result"
            noteMalformedEvent(type ?: "<none>", o.str("subtype"), o, line.length, e, critical)
        }
    }

    private fun noteMalformedEvent(
        type: String, subtype: String?, o: JsonObject?, bytes: Int, e: Exception, critical: Boolean,
    ) {
        malformedEventCount++
        val keys = o?.keySet()?.joinToString(",").orEmpty()
        thisLogger().warn(
            "Claude event not processed [#$malformedEventCount] type=$type " +
                "subtype=${subtype ?: "-"} keys=[$keys] bytes=$bytes", e,
        )
        if (critical) {
            addInfo("Claude response could not be processed. See the plugin log (Help → Show Log) for details.", true)
        }
    }

    private fun onPanel(o: JsonObject) {
        when (o.str("subtype")) {
            "started" -> setRunning(true)
            "cleared" -> clearAll()
            "config" -> applyConfigToUi()
            "error" -> { addInfo(o.str("text") ?: "Error", true); noteError(o.str("text") ?: "Error"); setRunning(false) }
            "exited" -> { finalizeCurrent(); inAssistant = false; setRunning(false); val c = o.intOrNull("code"); if (c != null && c != 0) noteError("Claude exited (code $c)") }
            "stderr" -> {}
        }
    }

    private fun onSystem(o: JsonObject) {
        when (o.str("subtype")) {
            "init" -> setRunning(true)
            "status" -> if (o.str("status") == "requesting") feed(interpreter.status("Thinking"))
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
                id?.let { tid -> pendingReportScans.remove(tid)?.let { (cmd, started) -> scanReportsAsync(cmd, started) } }
            }
        }
        scrollToBottomSoon()
    }

    private fun onResult(o: JsonObject) {
        finalizeCurrent(); inAssistant = false; setRunning(false)
        val isErr = o.has("is_error") && o.get("is_error").let { it.isJsonPrimitive && it.asBoolean }
        val parts = ArrayList<String>()
        o.dblOrNull("total_cost_usd")?.let { parts.add("$" + String.format("%.4f", it)) }
        o.dblOrNull("duration_ms")?.let { parts.add(String.format("%.1fs", it / 1000)) }
        o.intOrNull("num_turns")?.let { parts.add("$it turn" + if (it > 1) "s" else "") }
        completionMeta = parts.joinToString("   ·   ").ifBlank { null }
        feed(interpreter.taskDone(o.str("result") ?: "", isErr))
        if (isErr) o.str("result")?.let { addInfo(it, true) }
    }

    // ---------- tool body rendering ----------

    private fun renderToolBody(name: String, id: String?, input: JsonObject?, card: ToolCard) {
        target = card.bodyDoc
        card.setDetails(name, input ?: JsonObject(), renderToolContent(name, input ?: JsonObject()))
        target = null
        if (name == "Edit" || name == "Write" || name == "MultiEdit") card.expand()
        if (id != null) { renderedTools.add(id); toolCardsById[id] = card }
        feed(interpreter.toolUse(id, name, input))
        noteBuildCommand(id, name, input)
    }

    /** Remembers a build/test/analysis Bash command so its report files can be read when it finishes. */
    private fun noteBuildCommand(id: String?, name: String, input: JsonObject?) {
        if (id == null || name != "Bash") return
        val cmd = input?.str("command") ?: return
        if (OutputParsers.looksLikeGradle(cmd) || OutputParsers.isTestCommand(cmd) || OutputParsers.analysisTool(cmd) != null) {
            pendingReportScans[id] = cmd to System.currentTimeMillis()
        }
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
                    val mark = when (to.str("status")) { "completed" -> "[x] "; "in_progress" -> "[~] "; else -> "[ ] " }
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
        val toolUseId = req.str("tool_use_id")
        val title = req.str("title") ?: "Allow $toolName?"
        val suggestions = if (req.has("permission_suggestions") && req.get("permission_suggestions").isJsonArray)
            req.getAsJsonArray("permission_suggestions").toString() else null
        val block = ApprovalBlock(
            title, toolName, input,
            onAllow = { session.respondAllow(reqId, inputJson, null); resolvePermission() },
            onAllowAlways = if (suggestions != null) ({ session.respondAllow(reqId, inputJson, suggestions); resolvePermission() }) else null,
            onDeny = { session.respondDeny(reqId, "Denied by user"); resolvePermission(); onToolDenied(toolUseId, toolName, input) },
        )
        turn.addBlock(block)
        statusModel.permissionRequested(); refreshStatus()
        scrollToBottomSoon()
    }

    /** The user denied a tool: record a blocked node/status (never an execution) and mark its card. */
    private fun onToolDenied(toolUseId: String?, toolName: String, input: JsonObject) {
        feed(interpreter.toolDenied(toolUseId, toolName, input))
        val reason = "Denied by user"
        toolUseId?.let { toolCardsById[it] }?.markBlocked(reason)
    }

    private fun resolvePermission() { statusModel.permissionResolved(); refreshStatus() }

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

    // ---------- status / running ----------

    private fun setRunning(v: Boolean) {
        running = v
        SwingUtilities.invokeLater {
            composer.setRunning(v)
            refreshStatus()
        }
    }

    /** Push normalised activity events into both the map and the status model. */
    private fun feed(events: List<AgentActivityEvent>) {
        if (events.isEmpty()) return
        activityMap.apply(events)
        for (e in events) statusModel.apply(e)
        refreshStatus()
    }

    private fun noteError(text: String) {
        statusModel.apply(ErrorObserved(null, text, Instant.now()))
        refreshStatus()
    }

    /** Reads the report files a build/test/analysis command wrote (off the EDT), then feeds the results. */
    private fun scanReportsAsync(command: String, startedMillis: Long) {
        val base = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val events = try {
                reportScanner.scan(java.io.File(base), command, startedMillis)
            } catch (e: Exception) {
                thisLogger().warn("Build report scan failed", e); emptyList()
            }
            if (events.isNotEmpty()) {
                ApplicationManager.getApplication().invokeLater({ feed(events) }, ModalityState.any())
            }
        }
    }

    private fun refreshStatus() {
        val view = statusModel.view
        val meta = if (running) modelLabel() else completionMeta
        statusStrip.update(view, meta)
        header.setSessionState(coarseKind(view), coarseLabel(view))
    }

    private fun modelLabel(): String {
        val m = ClaudeSettings.getInstance().state.model ?: ""
        return if (m.isBlank()) "" else m.replaceFirstChar { it.uppercase() }
    }

    private fun coarseKind(v: StatusView): StatusKind = when (v.kind) {
        StatusKind.READY -> StatusKind.READY
        StatusKind.ERROR -> StatusKind.ERROR
        StatusKind.PERMISSION -> StatusKind.PERMISSION
        StatusKind.WARNING -> StatusKind.WARNING
        StatusKind.SUCCESS, StatusKind.COMPLETED -> StatusKind.SUCCESS
        else -> StatusKind.WORKING
    }

    private fun coarseLabel(v: StatusView): String = when (v.kind) {
        StatusKind.READY -> "Ready"
        StatusKind.ERROR -> "Error"
        StatusKind.PERMISSION -> "Waiting for approval"
        StatusKind.WARNING -> "Stopped"
        StatusKind.SUCCESS, StatusKind.COMPLETED -> "Ready"
        else -> "Working"
    }

    // ---------- lifecycle ----------

    private fun clearAll() {
        transcript.removeAll()
        toolCardsById.clear(); renderedTools.clear(); pendingReportScans.clear(); turns.clear(); inAssistant = false; curTurn = null; resetBlock()
        completionMeta = null
        interpreter.reset(); activityMap.clearSession()
        statusModel.reset(); transcriptPresenter.reset()
        transcript.revalidate(); transcript.repaint()
        showEmptyState(true)
        refreshStatus()
    }

    private fun applyConfigToUi() {
        SwingUtilities.invokeLater {
            updateModeChip()
            val desired = initialViewMode()
            if (desired != viewMode) setWorkspace(desired) else header.setWorkspace(toWorkspace(viewMode))
            statusStrip.setReduceMotion(ClaudeSettings.getInstance().state.activityReduceMotion)
        }
    }

    private fun openSettings() { ShowSettingsUtil.getInstance().showSettingsDialog(project, ClaudeSettingsConfigurable::class.java) }

    private fun setShowDetails(v: Boolean) {
        showDetails = v
        ClaudeSettings.getInstance().state.showDetails = v
        turns.forEach { it.applyDetails(v) }
        relayout()
    }

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

    private fun basename(p: String): String { val q = p.replace('\\', '/').trimEnd('/'); val i = q.lastIndexOf('/'); return if (i >= 0) q.substring(i + 1) else q }
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

    // ---------- tool presentation ----------

    private fun toolAction(name: String): String = when (name) {
        "Read" -> "Read"; "Edit", "MultiEdit", "NotebookEdit" -> "Edited"; "Write" -> "Created"
        "Bash" -> "Ran"; "Grep", "Glob", "WebSearch" -> "Searched"; "WebFetch" -> "Fetched"
        "TodoWrite" -> "Planned"; else -> humanizeTool(name)
    }

    private fun toolIcon(name: String): Icon = when (name) {
        "Read" -> ClaudeIcons.read
        "Edit", "MultiEdit", "Write", "NotebookEdit" -> ClaudeIcons.edit
        "Bash" -> ClaudeIcons.command
        "Grep", "Glob", "WebSearch" -> ClaudeIcons.search
        "WebFetch" -> ClaudeIcons.web
        "TodoWrite" -> ClaudeIcons.diamond
        else -> ClaudeIcons.diamond
    }

    private fun humanizeTool(name: String): String {
        val short = name.substringAfterLast("__")
        return short.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** The component keyboard focus should land on when the tool window activates. */
    fun preferredFocusComponent(): JComponent = composer.inputComponent()

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
            border = JBUI.Borders.empty(2, 2, 6, 2)
            val role = JBLabel("Claude")
            role.foreground = mutedFg(); role.font = role.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(11f).toFloat()); role.border = JBUI.Borders.emptyBottom(3)
            add(role, BorderLayout.NORTH)
            body.layout = BoxLayout(body, BoxLayout.Y_AXIS); body.isOpaque = false; body.alignmentX = Component.LEFT_ALIGNMENT
            add(body, BorderLayout.CENTER)
            refreshVisibility()
        }
        fun addBlock(c: JComponent) {
            val strut = Box.createVerticalStrut(JBUI.scale(5))
            body.add(fullWidth(c)); body.add(strut)
            if (c is TextBlock || c is ApprovalBlock) textCount++
            if (c is ThinkingBlock || c is ToolCard) {
                c.isVisible = showDetails; strut.isVisible = showDetails
                detailKids.add(c); detailKids.add(strut)
            }
            refreshVisibility(); relayout()
        }
        fun applyDetails(show: Boolean) { detailKids.forEach { it.isVisible = show }; refreshVisibility() }
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

    /** Extended thinking, rendered subtly under "Processing details" (only when details are on). */
    private inner class ThinkingBlock : Block() {
        private val header = JBLabel("Processing details")
        private val area = plainArea("")
        private var open = false
        private val chevron = JBLabel(ClaudeIcons.chevronRight.withSize(12))
        init {
            layout = BorderLayout()
            val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)); head.isOpaque = false; head.cursor = hand
            header.foreground = mutedFg(); header.font = header.font.deriveFont(Font.ITALIC)
            head.add(chevron); head.add(header)
            head.addMouseListener(click { toggle() })
            area.foreground = mutedFg(); area.font = area.font.deriveFont(Font.ITALIC); area.isVisible = false
            area.border = JBUI.Borders.empty(2, 16, 2, 0)
            add(head, BorderLayout.NORTH); add(area, BorderLayout.CENTER)
        }
        fun append(t: String) { area.text = area.text + t; scrollToBottomSoon() }
        private fun toggle() { open = !open; area.isVisible = open; chevron.icon = (if (open) ClaudeIcons.chevronDown else ClaudeIcons.chevronRight).withSize(12); relayout() }
    }

    /** A compact activity row: semantic icon + human action + target, with a result-state icon. */
    private inner class ToolCard(name: String) : Block() {
        private val bodyPane = styledPane()
        val bodyDoc: StyledDocument get() = bodyPane.styledDocument
        private val actionLabel = JBLabel(name)
        private val targetLabel = JBLabel("")
        private val iconLabel = JBLabel(toolIcon(name).let { (it as? io.mp.claudecodepanel.theme.VectorIcon)?.withColor { ClaudeUiTokens.textSecondary() } ?: it })
        private val stateLabel = JBLabel("")
        private val chevron = JBLabel(ClaudeIcons.chevronRight.withSize(12))
        private val bodyWrap = JPanel(BorderLayout())
        private var open = false
        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(1, 0)
            val head = JPanel(BorderLayout(JBUI.scale(6), 0)); head.isOpaque = false; head.border = JBUI.Borders.empty(6, 8); head.cursor = hand
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)); left.isOpaque = false
            actionLabel.font = actionLabel.font.deriveFont(Font.BOLD)
            actionLabel.foreground = ClaudeUiTokens.textPrimary()
            targetLabel.foreground = mutedFg()
            left.add(iconLabel); left.add(actionLabel); left.add(targetLabel)
            head.add(left, BorderLayout.WEST)
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)); right.isOpaque = false
            right.add(stateLabel); right.add(chevron)
            head.add(right, BorderLayout.EAST)
            head.addMouseListener(click { toggle() })
            add(head, BorderLayout.NORTH)
            bodyWrap.isOpaque = false; bodyWrap.border = JBUI.Borders.empty(0, 12, 6, 8); bodyWrap.add(bodyPane, BorderLayout.CENTER)
            bodyWrap.isVisible = false
            add(bodyWrap, BorderLayout.CENTER)
        }
        fun setDetails(name: String, input: JsonObject, summary: String) {
            actionLabel.text = toolAction(name)
            targetLabel.text = summary
            relayout()
        }
        fun addResult(text: String, isErr: Boolean) {
            target = bodyDoc
            if (bodyDoc.length > 0) insert("\n", sMuted)
            insert(if (isErr) "! " else "» ", if (isErr) sError else sMuted)
            insert(truncate(text).ifBlank { if (isErr) "(error)" else "(no output)" } + "\n", sMuted)
            target = null
            stateLabel.icon = if (isErr) ClaudeIcons.errorCircle.withSize(13) else ClaudeIcons.check.withSize(13)
            if (isErr) expand()
            relayout()
        }
        fun expand() { if (!open) toggle() }
        /** Marks this tool as denied/cancelled: blocked icon + a muted "not run" note; never looks done. */
        fun markBlocked(reason: String) {
            stateLabel.icon = ClaudeIcons.blocked.withSize(13)
            targetLabel.foreground = mutedFg()
            target = bodyDoc
            if (bodyDoc.length > 0) insert("\n", sMuted)
            insert("⦸ $reason\n", sMuted)
            target = null
            relayout()
        }
        private fun toggle() { open = !open; bodyWrap.isVisible = open; chevron.icon = (if (open) ClaudeIcons.chevronDown else ClaudeIcons.chevronRight).withSize(12); relayout() }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = ClaudeUiTokens.radiusMd()
            g2.color = cardBg()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = ClaudeUiTokens.subtleBorder()
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
        private val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        private val decided = JBLabel("")
        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(9, 11)
            val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)); head.isOpaque = false; head.border = JBUI.Borders.emptyBottom(5)
            head.add(JBLabel(ClaudeIcons.warningTriangle.withSize(14)))
            val titleLabel = JBLabel(title)
            titleLabel.foreground = ClaudeUiTokens.accent(); titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
            head.add(titleLabel)
            add(head, BorderLayout.NORTH)

            val pane = styledPane()
            target = pane.styledDocument; renderToolContent(toolName, input); target = null
            val mid = JPanel(BorderLayout()); mid.isOpaque = false; mid.border = JBUI.Borders.emptyBottom(6); mid.add(pane, BorderLayout.CENTER)
            add(mid, BorderLayout.CENTER)

            buttons.isOpaque = false
            val allow = JButton("Allow"); allow.addActionListener { onAllow(); resolve("Allowed") }; buttons.add(allow)
            if (onAllowAlways != null) {
                val aa = JButton("Allow always"); aa.addActionListener { onAllowAlways(); resolve("Always allowed") }; buttons.add(aa)
            }
            val deny = JButton("Deny"); deny.addActionListener { onDeny(); resolve("Denied") }; buttons.add(deny)
            decided.foreground = mutedFg()
            val south = JPanel(BorderLayout()); south.isOpaque = false
            south.add(buttons, BorderLayout.WEST); south.add(decided, BorderLayout.EAST)
            add(south, BorderLayout.SOUTH)
        }
        private fun resolve(text: String) { buttons.isVisible = false; decided.text = text; relayout() }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = ClaudeUiTokens.radiusMd()
            g2.color = ClaudeUiTokens.overlaySurface()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = ClaudeUiTokens.accent()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private inner class Bubble : JPanel(BorderLayout()) {
        init { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT; border = JBUI.Borders.empty(9, 12) }
        override fun getMaximumSize(): Dimension = Dimension(Integer.MAX_VALUE, preferredSize.height)
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = ClaudeUiTokens.radiusMd()
            g2.color = cardBg()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = ClaudeUiTokens.subtleBorder()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
