package io.mp.sightline.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.util.IJSwingUtilities
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.ide.CopyPasteManager
import io.mp.sightline.ui.state.DiffLayout
import io.mp.sightline.ui.state.DiffPresentation
import java.awt.GridLayout
import io.mp.sightline.ui.state.MentionQuery
import io.mp.sightline.ui.state.ModelCatalog
import io.mp.sightline.ui.state.PathDisplay
import io.mp.sightline.ui.state.ProcessingSummary
import javax.swing.Timer
import io.mp.sightline.ui.state.TranscriptRetention
import com.intellij.ide.projectView.ProjectView
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.TestOnly
import io.mp.sightline.activity.ActivityInterpreter
import io.mp.sightline.activity.AgentActivityEvent
import io.mp.sightline.activity.BuildReportScanner
import io.mp.sightline.activity.ErrorObserved
import io.mp.sightline.activity.FileEdited
import io.mp.sightline.activity.FileRead
import io.mp.sightline.activity.OutputParsers
import io.mp.sightline.health.HealthGatherer
import io.mp.sightline.mcp.McpSyncCoordinator
import io.mp.sightline.ide.McpConfigWatcher
import io.mp.sightline.ide.ApprovalCoordinator
import io.mp.sightline.ide.ApprovalDecision
import io.mp.sightline.ide.PendingApproval
import io.mp.sightline.ide.PendingQuestion
import io.mp.sightline.ide.QuestionCoordinator
import io.mp.sightline.ide.QuestionResolution
import io.mp.sightline.interaction.AskUserQuestionParser
import io.mp.sightline.interaction.AskUserQuestionResponseBuilder
import io.mp.sightline.interaction.ParseResult
import io.mp.sightline.interaction.QuestionFormState
import io.mp.sightline.interaction.UserQuestionOption
import io.mp.sightline.interaction.UserQuestionRequest
import io.mp.sightline.process.ClaudePathResolver
import io.mp.sightline.process.ClaudeSession
import io.mp.sightline.process.SessionControlJson
import io.mp.sightline.process.UserMessageJson
import io.mp.sightline.android.AndroidContextFormatter
import io.mp.sightline.android.StackTraceResolver
import io.mp.sightline.ide.android.AndroidContextResolver
import io.mp.sightline.settings.ClaudeSettings
import io.mp.sightline.settings.ClaudeSettingsConfigurable
import io.mp.sightline.theme.ClaudeIcons
import io.mp.sightline.theme.ClaudeUiTokens
import io.mp.sightline.ui.components.EmptyStatePanel
import io.mp.sightline.ui.components.IconActionButton
import io.mp.sightline.ui.markdown.BlockRenderer
import io.mp.sightline.ui.markdown.FileRefDetector
import io.mp.sightline.ui.markdown.MarkdownDocParser
import io.mp.sightline.ui.markdown.MdBlock
import io.mp.sightline.ui.markdown.StreamingMarkdown
import io.mp.sightline.ui.components.WrapLayout
import io.mp.sightline.ui.state.CheckpointPolicy
import io.mp.sightline.ui.state.CompletionCard
import io.mp.sightline.ui.state.ComposerModel
import io.mp.sightline.ui.state.ContextUsage
import io.mp.sightline.ui.state.ImageAttachmentPolicy
import io.mp.sightline.ui.state.LayoutProfile
import io.mp.sightline.ui.state.PendingImage
import io.mp.sightline.ui.state.LineDiff
import io.mp.sightline.ui.state.PermissionModes
import io.mp.sightline.ui.state.PlanReview
import io.mp.sightline.ui.state.ResponsiveLayout
import io.mp.sightline.ui.state.ScrollFollow
import io.mp.sightline.ui.state.SessionFailure
import io.mp.sightline.ui.state.SessionNotices
import io.mp.sightline.ui.state.SessionPersistence
import io.mp.sightline.ui.state.SlashCommands
import io.mp.sightline.ui.state.StallPolicy
import io.mp.sightline.ui.state.StatusKind
import io.mp.sightline.ui.state.StatusModel
import io.mp.sightline.ui.state.StatusView
import io.mp.sightline.ui.state.StopPolicy
import io.mp.sightline.ui.state.SubagentPresentation
import io.mp.sightline.ui.state.ToolEventPresentation
import io.mp.sightline.ui.state.ToolOutcome
import io.mp.sightline.ui.state.ToolWeight
import io.mp.sightline.ui.state.TranscriptPresenter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.util.Base64
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButton
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
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

/** request_id prefix for sandbox-test-bridge-simulated control requests (no real CLI behind them). */
private const val SIM_REQ_PREFIX = "sim-question-"

/**
 * Caption under a user bubble that went out mid-run. It states **when the message was sent**, which is
 * the part this panel actually knows: the CLI folds a streamed-in user message into the work in progress
 * at the agent's next step, and if the turn happens to end in that same instant it is answered as the
 * next turn. "Claude will read this now" would be a claim about the other side of the pipe.
 */
private const val INTERJECTED_NOTE = "Sent while Claude was working"

/**
 * Bottom gutter the transcript reserves for the "Jump to latest" overlay (unscaled; [JBUI] scales it
 * alongside the button's own scaled font). The button floats over the transcript so that showing and
 * hiding it never shifts the text being read — but without room to scroll into, that same overlay sits
 * on top of the last line once the reader reaches the bottom. Reserved unconditionally: a gutter that
 * appeared with the button would reintroduce exactly the shifting the overlay exists to avoid.
 */
private const val JUMP_TO_LATEST_GUTTER = 46

/**
 * Native Swing chat panel for Claude Code. Four regions: a compact [ClaudeToolHeader], the primary
 * transcript, a coordinated [ClaudeStatusStrip], and the
 * [ClaudeComposerPanel]. Stream parsing feeds both the transcript blocks and a normalised activity
 * stream that drives the status model.
 */
class ClaudePanel(private val project: Project, parent: Disposable) : Disposable {

    private companion object {
        /**
         * How often the MCP config stamp is checked while the panel is showing. Two `lastModified()`
         * calls per tick, so this is cheap; the interval only decides how soon after `claude mcp add`
         * the tools appear, and a couple of seconds is well inside the time it takes to type the next
         * message.
         */
        const val MCP_POLL_MS = 2500

        /**
         * How long to wait for a control reply before reporting that we cannot say. Comfortably past
         * the CLI's own 30s per-server connect timeout, so a slow-but-working server is not called a
         * failure — this is for a reply that is never coming.
         */
        const val MCP_REPLY_TIMEOUT_MS = 45_000

        /**
         * How many lines of a failure's raw text the card shows before it says it clipped the rest.
         * Enough for the multi-line messages the CLI actually prints, short enough that a stack trace
         * can't push every recovery button off the bottom of the panel.
         */
        const val DETAIL_LINES = 12

        /** Bound on `claude auth status`, which talks to the network. Off-EDT by construction. */
        const val AUTH_PROBE_TIMEOUT_MS = 12_000

        /** How often the quiet check runs while a turn is in flight. Cheap: two comparisons. */
        const val QUIET_POLL_MS = 15_000

        /**
         * Caps on `@`-mention completion, so a keystroke in a large monorepo stays a keystroke. The
         * name cap bounds the index walk; the path cap bounds resolving names to files, which is the
         * expensive half (one common filename can map to dozens of modules).
         */
        const val MENTION_NAME_CAP = 400
        const val MENTION_PATH_CAP = 200
    }

    val component: JComponent
    private val session: ClaudeSession = ClaudeSession(project) { line -> onLine(line) }
    private val interpreter = ActivityInterpreter()

    private val statusModel = StatusModel()
    private val composerModel = ComposerModel()
    private val transcriptPresenter = TranscriptPresenter()

    /**
     * Live MCP sync: keeps this conversation's MCP servers in step with the ones you have declared,
     * so `claude mcp add` does not cost the conversation. See [McpSyncCoordinator].
     *
     * The writes go straight to the running process and are never waited on — `mcp_set_servers` can
     * take 30s to answer when a server hangs, which is why none of this is on the send path.
     */
    private val mcpSync = McpSyncCoordinator(
        send = { line -> session.sendControlRequest(line) },
        notice = { addInfo(it, false) },
        newRequestId = { "sightline-mcp-${java.util.UUID.randomUUID()}" },
    )

    /**
     * Polls the config stamp while the panel is showing. Two `File.lastModified()` calls per tick, off
     * the EDT, and a parse only when the stamp actually moves — see [McpConfigWatcher].
     */
    private val mcpPollTimer = Timer(MCP_POLL_MS) { pollMcpConfig() }

    /** Frees an exchange whose reply never came, so one wedged request can't disable sync for the session. */
    private val mcpTimeoutTimer = Timer(MCP_REPLY_TIMEOUT_MS) { mcpSync.timedOut() }
        .apply { isRepeats = false }

    /**
     * Watches for a turn that has gone quiet. Runs only while a turn is in flight (started and stopped
     * by [setRunning]), so an idle panel ticks nothing.
     */
    private val quietTimer = Timer(QUIET_POLL_MS) { checkForQuiet() }

    /** True while no turn is in flight — a sync waits for one to finish rather than interrupting it. */
    private var mcpPollInFlight = false

    private val chatHost = JPanel(BorderLayout())
    /** Status strip + composer. Padded in [applyProfile] so it lines up with the transcript column. */
    private val southHost = JPanel(BorderLayout())

    private lateinit var header: ClaudeToolHeader
    private lateinit var composer: ClaudeComposerPanel
    private lateinit var statusStrip: ClaudeStatusStrip
    private lateinit var emptyState: EmptyStatePanel

    /**
     * How many normalised activity events this session has observed. The health report states it, as
     * a check that the event pipeline behind the status strip is actually receiving anything — the
     * activity map kept this count until it was removed.
     */
    private var observedEvents = 0

    /**
     * Context-window occupancy, as last reported by the CLI. The window half is sticky for the session
     * once stated (only `result.modelUsage` carries it, so a live turn would otherwise lose the
     * denominator it had a moment ago and the chip would flick between "35.4k / 200k · 18%" and a bare
     * "35.4k" on every message).
     */
    /** Wall-clock of the last event seen on the stream, and of the last quiet notice — see [StallPolicy]. */
    private var lastEventAt = 0L
    private var lastQuietNoticeAt = 0L

    /** The one door to a remembered session id — see [io.mp.sightline.ui.state.SessionPersistence]. */
    private val sessionMemory = SessionMemory(project)

    private var contextTokens: Long? = null
    private var contextWindow: Long? = null

    private var lastProfile: LayoutProfile? = null
    private var lastDiffWidth = -1
    private var evictedTurns = 0

    /** Sits above the transcript once old turns have been released; hidden until then. */
    private val retentionNotice = JBLabel("").apply {
        foreground = ClaudeUiTokens.textSecondary()
        font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, JBUI.scaleFontSize(11f).toFloat())
        border = JBUI.Borders.empty(4, 2, 8, 2)
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
    }

    private val transcript = object : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = ClaudeUiTokens.surface()
            border = JBUI.Borders.empty(12, 14, JUMP_TO_LATEST_GUTTER, 14)
        }
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(20)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(120)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
    // Auto-follow stays on only while the user is at/near the bottom; scrolling up to read pauses it.
    private var following = true

    /** Shown only while follow is paused, so the reader can get back to the live end in one click. */
    private val jumpToLatestButton = JButton("Jump to latest ↓").apply {
        font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        margin = Insets(2, 10, 2, 10)
        isVisible = false
        isFocusable = true
        toolTipText = "Resume following new output"
        accessibleContext.accessibleName = A11yNames.TRANSCRIPT_JUMP_TO_LATEST
        addActionListener { jumpToLatest() }
    }

    /** True while *we* are moving the scrollbar, so our own scroll isn't read as a user gesture. */
    private var programmaticScroll = false

    private var lastScrollMaximum = 0
    private var lastScrollValue = 0

    private val scroll = JBScrollPane(transcript).apply {
        verticalScrollBar.addAdjustmentListener {
            val bar = verticalScrollBar
            val grew = bar.maximum != lastScrollMaximum
            val moved = bar.value != lastScrollValue
            lastScrollMaximum = bar.maximum
            lastScrollValue = bar.value
            if (programmaticScroll) return@addAdjustmentListener
            // A real gesture moves the content out from under the pointer, leaving hover actions
            // revealed on a row that is no longer hovered. Pure growth must *not* close them, or they
            // would flicker away mid-stream just as the user reaches for one.
            if (moved) hideRevealedHoverActions?.invoke()
            // Content arriving is not a user gesture. While following, pure growth (the maximum moved
            // but the value didn't) must **re-pin** to the new bottom; reading it as "no longer near
            // the end" is what silently killed follow mid-stream and popped "Jump to latest" without
            // anyone touching anything.
            //
            // `!moved` is the important half: if the value changed too, the user is scrolling — even
            // if content happens to be arriving at the same time — and re-pinning would drag them back
            // to the bottom, which is the exact behaviour this feature exists to prevent.
            if (following && grew && !moved) {
                scrollToBottomNow()
                return@addAdjustmentListener
            }
            following = ScrollFollow.isNearBottom(bar.value, bar.visibleAmount, bar.maximum, JBUI.scale(48))
            updateJumpToLatest()
        }
    }

    /**
     * Overlays [jumpToLatestButton] on the transcript without stealing layout space, so the affordance can
     * appear and disappear during a scroll without shifting the text the user is reading.
     */
    private val transcriptLayer = object : JLayeredPane() {
        override fun doLayout() {
            scroll.setBounds(0, 0, width, height)
            val d = jumpToLatestButton.preferredSize
            jumpToLatestButton.setBounds((width - d.width) / 2, height - d.height - JBUI.scale(12), d.width, d.height)
        }
        override fun getPreferredSize(): Dimension = scroll.preferredSize
    }.apply {
        setLayer(scroll, JLayeredPane.DEFAULT_LAYER)
        setLayer(jumpToLatestButton, JLayeredPane.PALETTE_LAYER)
        add(scroll)
        add(jumpToLatestButton)
    }

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

    /**
     * A Stop has been asked for but the exit hasn't been observed yet. During that window the CLI is
     * being torn down, so a mid-turn message must queue rather than be written into a dying stdin —
     * see [canInterject].
     */
    private var stopping = false

    /**
     * Stop state, per [StopPolicy]. `interruptPending` makes a second Stop press the escalation signal;
     * `interruptSupported` latches false the first time a CLI answers `interrupt` with "unsupported", so
     * later presses skip the polite path instead of asking again and waiting.
     */
    private var interruptPending = false
    private var interruptSupported = true
    private var interruptUnsupportedNoted = false

    /**
     * `tool_use_id`s of in-flight tools whose work can outlive a Stop — see [StopPolicy.LINGERING_TOOLS].
     * Neither an interrupt nor a kill stops a shell the agent already started (docs/PROTOCOL.md §6), so
     * the Stop notice needs to know whether there is one to be honest about.
     */
    private val inFlightCommands = mutableSetOf<String>()

    /** Mode being switched into, so the `set_permission_mode` reply can name it. */
    private var pendingModeLabel: String? = null

    /**
     * The CLI's own commands, as it reported them — never a built-in list, since the set depends on
     * this project's commands, plugins and skills. See [SlashCommands].
     */
    private var slashCommands: List<SlashCommands.Command> = emptyList()

    /** Speaks only when the rate-limit *status* changes; see [SessionNotices.RateLimits]. */
    private val rateLimits = SessionNotices.RateLimits()

    /**
     * Bubbles sent but not yet matched to a replayed user message, oldest first. The replay is what
     * carries the checkpoint `uuid`; until it arrives a message has no restore point and the action is
     * simply not offered. Only populated when file checkpointing is on — see [CheckpointPolicy].
     */
    private val awaitingCheckpoint = ArrayDeque<Pair<String, Bubble>>()

    /**
     * Ceiling on that queue. It is normally 0 or 1 deep — a replay follows its send almost immediately —
     * so reaching this means replays have stopped arriving, and the oldest entries are the ones least
     * likely to ever match. Bounded so a broken replay stream cannot pin the whole transcript's Bubbles
     * in memory for the life of the panel.
     */
    private val MAX_AWAITING_CHECKPOINTS = 32

    /** Message being reverted to, so the `rewind_files` reply can be reported against it. */
    private var rewindInFlight = false
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
    private var permissionModeFallbackNoted = false

    /**
     * The model the CLI last reported in `system/init` — the resolved id, not the alias that was asked
     * for. Null until it has said, and the picker shows nothing rather than a guess in that window.
     */
    private var reportedModel: String? = null

    // After a build/test/analysis command, its structured report files are read off-EDT for richer
    // results than the console gives. tool_use_id -> (command, start millis).
    private val reportScanner = BuildReportScanner()
    private val pendingReportScans = HashMap<String, Pair<String, Long>>()

    // Approvals are resolved through a shared coordinator so the sandbox test bridge drives the same
    // logic as the human's Allow/Deny buttons — never a separate bypass path.
    private val approvalCoordinator by lazy { project.getService(ApprovalCoordinator::class.java) }
    private val questionCoordinator by lazy { project.getService(QuestionCoordinator::class.java) }
    private val uiState by lazy { project.getService(SightlineUiState::class.java) }

    // Phase 2a: enriches files Claude touches with real project structure (imports/test targets/package),
    // off the EDT. Only files already touched are enriched; results feed back as background relations.
    private val toolCardsById = HashMap<String, ToolCard>()

    /** What a tool call was, so its later result (which carries only an id) can be attributed. */
    private inner class ToolMeta(val name: String, val path: String?, val turn: AssistantTurn?, val card: ToolCard)
    private val toolMetaById = HashMap<String, ToolMeta>()
    private val renderedTools = HashSet<String>()
    private var pendingScroll = false
    private var showDetails = ClaudeSettings.getInstance().state.showDetails
    private val markdownRenderer = BlockRenderer(
        project,
        onLink = { openMarkdownLink(it) },
        onReveal = { href -> revealMarkdownLink(href) },
        renderMermaid = ClaudeSettings.getInstance().state.renderMermaid,
    )
    private val turns = ArrayList<AssistantTurn>()

    private val modes = PermissionModes.all

    init {
        Disposer.register(parent, this)
        initStyles()
        component = build()
        component.getAccessibleContext()?.accessibleName = A11yNames.TOOL_WINDOW_ROOT
        uiState.rootComponent = component
        uiState.toolWindowVisible = true
        uiState.askQuestionSimulator = { input -> simulateAskUserQuestion(input) } // reachable only via the gated test bridge
        // How the editor-side actions reach this panel. Blank text means "just focus me", which is what
        // "Open Sightline" wants and what activating an already-open window would otherwise not do.
        uiState.insertIntoComposer = { text ->
            runOnEdt {
                if (text.isNotBlank()) {
                    showEmptyState(false)
                    composer.insertContextText(if (text.endsWith(" ")) text else "$text ")
                }
                composer.requestInputFocus()
            }
        }
        applyConfigToUi()
        installEmptyState()
        installResponsive()
        refreshStatus()
    }

    // ---------- UI ----------

    private fun build(): JComponent {
        val root = JPanel(BorderLayout())

        header = ClaudeToolHeader(
            onNew = { session.newConversation() },
            onMore = { anchor -> showMoreMenu(anchor) },
        )
        root.add(header, BorderLayout.NORTH)

        scroll.border = BorderFactory.createEmptyBorder()
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scroll.viewport.background = ClaudeUiTokens.surface()
        transcript.add(retentionNotice)
        chatHost.add(transcriptLayer, BorderLayout.CENTER)
        root.add(chatHost, BorderLayout.CENTER)

        // Whether Enter mid-turn folds into the running turn or parks for the next one. The model owns
        // the decision but can't see a process, so the host supplies the answer.
        composerModel.canInterject = { canInterject() }

        composer = ClaudeComposerPanel(
            model = composerModel,
            onSend = { text -> doSend(text) },
            onStop = { stopRequest() },
            onAttach = { attachFile() },
            onSlash = { anchor -> showSlashMenu(anchor) },
            onModeMenu = { anchor -> showModesPopup(anchor) },
            onRefreshAndroidContext = { refreshAndroidContext(force = true) },
            onFilesPasted = { files -> attachPastedFiles(files) },
            // A refused paste must be said somewhere the user will see it, not swallowed.
            onAttachmentNotice = { notice -> showEmptyState(false); addInfo(notice, err = false); scrollToBottomSoon() },
            onContextBreakdown = { askForContextBreakdown() },
            onMentionSearch = { prefix -> searchProjectFiles(prefix) },
        )
        installAndroidContext()
        statusStrip = ClaudeStatusStrip(this)

        southHost.add(statusStrip, BorderLayout.NORTH)
        southHost.add(composer, BorderLayout.CENTER)
        root.add(southHost, BorderLayout.SOUTH)

        updateModeChip()
        installShortcuts(root)
        installThemeListener(root)
        installMcpConfigPolling(root)
        return root
    }

    /**
     * Runs the MCP config poll only while the panel is actually on screen.
     *
     * A docked tool window spends most of its life hidden, and a background tick that costs nothing
     * individually still has no business running for a panel nobody is looking at. Coming back into view polls immediately, so a server added
     * while the window was collapsed is picked up on sight rather than after another interval.
     */
    private fun installMcpConfigPolling(root: JComponent) {
        root.addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() == 0L) return@addHierarchyListener
            if (root.isShowing) {
                mcpPollTimer.start()
                pollMcpConfig()
            } else {
                mcpPollTimer.stop()
            }
        }
    }

    /**
     * Repaints the panel when the theme changes.
     *
     * [ClaudeUiTokens] hands out colours that re-resolve on every read, so nothing needs re-applying —
     * but Swing only shows the new values once something asks it to paint. A LaF switch repaints the IDE
     * for us; an **editor colour scheme** change does not necessarily reach a docked tool window, and
     * half this panel's surfaces are derived from the editor background, so that one is subscribed too.
     *
     * Both topics are application-level, so they are taken on the application bus — a project connection
     * would compile, register, and simply never fire. Scoped to this panel's lifetime.
     */
    private fun installThemeListener(root: JComponent) {
        val repaint = {
            SwingUtilities.invokeLater {
                IJSwingUtilities.updateComponentTreeUI(root)
                root.revalidate(); root.repaint()
            }
        }
        val bus = ApplicationManager.getApplication().messageBus.connect(this)
        bus.subscribe(LafManagerListener.TOPIC, LafManagerListener { repaint() })
        bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { repaint() })
    }

    // ---- Android context (docs/ANDROID.md M1) ----

    /**
     * Wires the composer to the Android fact ladder.
     *
     * The block is supplied through a **lambda evaluated at send time**, not a stored string. That is
     * what makes a queued message honest: one typed before an emulator booted is sent after it did, and
     * must describe the device that exists then rather than the absence that existed while it waited.
     * `ComposerModel.buildMessage` is re-entered on queue drain, so this happens for free.
     */
    private fun installAndroidContext() {
        // Lets a logcat crash attach to the file that threw. Reuses resolveProjectFile, which resolves
        // only a *unique* project match — so an ambiguous name yields null rather than a wrong file.
        interpreter.resolveSourceFile = { fileName -> resolveProjectFile(fileName)?.path }

        composerModel.androidContextBlock = { enabled ->
            if (!ClaudeSettings.getInstance().state.androidFeatures) {
                ""
            } else {
                // Cached-only: this runs on the EDT at send time, and the resolver shells out to adb.
                // A miss costs the first message its context and is repaired by the refresh below —
                // far better than blocking the UI thread on a device that may not answer.
                val cached = AndroidContextResolver.getInstance(project).cachedOrNull()
                cached?.let { AndroidContextFormatter.promptBlock(it, enabled) }.orEmpty()
            }
        }
        // Keep the strip honest as the user navigates. The resolver re-reads only the editor fact on a
        // cache hit, so this is a ReadAction per file switch, not a build-tree walk.
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    refreshAndroidContext(force = false)
                }
            },
        )
        refreshAndroidContext(force = false)
    }

    /**
     * Set by the preview seam so an in-flight background refresh can't overwrite an injected context.
     * Without it the preview races the resolver's startup pass, which — in a fixture project with no
     * build files — resolves to NOT_ANDROID and silently hides the very strip under test.
     */
    private var androidContextPinned = false

    /** Resolves off-EDT and pushes the result into the composer. Safe to call often; the resolver caches. */
    private fun refreshAndroidContext(force: Boolean) {
        if (androidContextPinned) return
        if (!ClaudeSettings.getInstance().state.androidFeatures) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val context = try {
                AndroidContextResolver.getInstance(project).resolve(force = force)
            } catch (e: Exception) {
                thisLogger().info("android: context refresh failed (${e.javaClass.simpleName})")
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater({
                // Re-check the pin here, not only on entry: a refresh scheduled before the pin was set
                // is still in flight, and delivering its result would clobber the pinned context.
                if (project.isDisposed || androidContextPinned) return@invokeLater
                composer.setAndroidContext(context)
                // A crash's blame frame needs to know which packages are "ours". Both halves matter: a
                // flavour's applicationIdSuffix makes the installed id differ from the namespace the
                // code actually lives in, and matching on only one finds no frames at all.
                val module = context.activeModule
                interpreter.appPackagePrefixes = StackTraceResolver.appPrefixes(
                    module?.applicationId?.value,
                    module?.namespace?.value,
                )
            }, ModalityState.any())
        }
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
        // The chat column can change width without the panel doing so — the splitter divider moving,
        // or simply the viewport being laid out for the first time after the panel already has its
        // size. Without this, first open computed padding against a not-yet-sized viewport and never
        // revisited it, so the conversation stayed squeezed until the window was resized.
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyProfile()
        })
        SwingUtilities.invokeLater { applyProfile() }
    }

    private fun applyProfile() {
        // Ignore pre-layout (0/near-0 width) passes so we don't transiently downgrade the layout.
        if (component.width < JBUI.scale(80)) return
        val scale = JBUI.scale(1000) / 1000f
        val profile = ResponsiveLayout.profile(component.width, scale)
        val profileChanged = profile != lastProfile
        lastProfile = profile
        header.applyProfile(profile)
        //
        // Never substitute the panel width when the viewport hasn't been laid out yet. On first open
        // the viewport is still 0 while the panel is already full width, so that fallback produced
        // padding sized for a column twice as wide as the real one — and since the panel width then
        // never changes, nothing recomputed it and the text stayed crushed into a sliver until the
        // window was resized. The viewport listener below re-runs this once the real width exists.
        val textWidth = scroll.viewport.width
        val hpad = if (textWidth > 0) {
            JBUI.scale(ResponsiveLayout.readablePadding((textWidth / scale).toInt()))
        } else {
            JBUI.scale(ResponsiveLayout.BASE_PADDING)
        }
        transcript.border = JBUI.Borders.empty(12, hpad)
        // Keep the composer on the same column as the conversation it belongs to.
        val composerPad = (hpad - JBUI.scale(14)).coerceAtLeast(0)
        southHost.border = JBUI.Borders.empty(0, composerPad)
        // Diffs pick unified vs side-by-side from the width the *transcript column* actually gets.
        val diffWidth = (textWidth - hpad * 2).coerceAtLeast(0)
        if (diffWidth != lastDiffWidth) {
            lastDiffWidth = diffWidth
            turns.forEach { it.applyDiffWidth(diffWidth) }
        }
        if (profileChanged) { transcript.revalidate(); transcript.repaint() }
        southHost.revalidate()
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
        // Prefer the IDE's own diff colours so a custom theme is tracked; the fixed pairs are only a
        // fallback for schemes that don't define them (the scheme is already the source for fonts and
        // fence highlighting, so this keeps one source of truth).
        val scheme = EditorColorsManager.getInstance().globalScheme
        val addBg = scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor
            ?: JBColor(Color(0xDD, 0xF4, 0xE4), Color(0x1E, 0x3A, 0x28))
        val delBg = scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor
            ?: JBColor(Color(0xFB, 0xE4, 0xE1), Color(0x3A, 0x22, 0x22))
        clone(sDiffAdd, sCode); StyleConstants.setBackground(sDiffAdd, addBg)
        clone(sDiffDel, sCode); StyleConstants.setBackground(sDiffDel, delBg)
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

    /**
     * Drops the oldest turns once past [TranscriptRetention.MAX_TURNS] so a marathon session doesn't
     * grow an unbounded component tree that every layout pass has to walk.
     *
     * The components are genuinely released, not hidden — that is the point — so the notice says the
     * turns are gone rather than offering a "load earlier" that has nothing to load from (there is no
     * session persistence). Their tool-card bookkeeping goes with them, or the id maps would leak
     * exactly what the eviction was meant to free.
     */
    private fun evictOldTurns() {
        val drop = TranscriptRetention.evictCount(turns.size)
        if (drop <= 0) return
        val dropped = turns.subList(0, drop).toList()
        repeat(drop) { turns.removeAt(0) }
        for (t in dropped) {
            // The strut that follows each turn goes with it.
            val i = transcript.components.indexOf(t)
            if (i >= 0) {
                if (i + 1 < transcript.componentCount) transcript.remove(i + 1)
                transcript.remove(i)
            }
        }
        val goneIds = toolMetaById.filterValues { it.turn in dropped }.keys.toList()
        goneIds.forEach { toolMetaById.remove(it); toolCardsById.remove(it); renderedTools.remove(it) }
        evictedTurns += drop
        updateRetentionNotice()
        transcript.revalidate(); transcript.repaint()
    }

    private fun updateRetentionNotice() {
        val text = TranscriptRetention.noticeText(evictedTurns)
        retentionNotice.text = text
        retentionNotice.isVisible = text.isNotEmpty()
    }

    private fun addInfo(text: String, err: Boolean) {
        val ta = plainArea(text)
        ta.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC)
        ta.foreground = if (err) ClaudeUiTokens.error() else mutedFg()
        val r = fullWidth(JPanel(BorderLayout())); r.border = JBUI.Borders.empty(2, 4); r.add(ta, BorderLayout.CENTER)
        addRow(r)
    }

    /**
     * A user turn. [interjected] captions the bubble as having gone out mid-run — worded as *when it was
     * sent*, not as a promise about what the agent does with it: the CLI folds it in at its next step,
     * and a turn that happens to end in that instant answers it as the following turn instead.
     */
    private fun addUserBubble(
        text: String,
        attachments: List<String>,
        images: List<PendingImage> = emptyList(),
        interjected: Boolean = false,
    ): Bubble {
        val bubble = Bubble()
        bubble.border = JBUI.Borders.empty(9, 12)
        val col = JPanel(); col.layout = BoxLayout(col, BoxLayout.Y_AXIS); col.isOpaque = false
        if (text.isNotEmpty()) col.add(fullWidth(plainArea(text)))
        if (images.isNotEmpty()) {
            // Thumbnails only: the full bytes went to the CLI and are released with the pending set;
            // what the transcript keeps is the small pre-scaled render.
            val row = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)))
            row.isOpaque = false
            for (img in images) {
                val thumb = img.image.thumbnail
                val label = if (thumb != null) JBLabel(ImageIcon(thumb)) else JBLabel("Image ${img.ordinal}")
                label.border = BorderFactory.createLineBorder(ClaudeUiTokens.border())
                label.toolTipText = ImageAttachmentPolicy.tooltip(img)
                row.add(label)
            }
            col.add(fullWidth(row))
        }
        if (attachments.isNotEmpty()) {
            val ctx = plainArea("Context: " + attachments.joinToString(", ") { basename(it) })
            ctx.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, JBUI.scaleFontSize(11f).toFloat())
            ctx.foreground = mutedFg()
            col.add(fullWidth(ctx))
        }
        if (interjected) {
            val note = plainArea(INTERJECTED_NOTE)
            note.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, JBUI.scaleFontSize(11f).toFloat())
            note.foreground = mutedFg()
            col.add(fullWidth(note))
        }
        bubble.add(col, BorderLayout.CENTER)
        addRow(bubble)
        return bubble
    }

    /**
     * Renders a failure as an actionable card instead of a red line.
     *
     * [raw] must be the **fullest** text available, not the one-line summary built from it: the card
     * shows the CLI's own wording and "Copy details" hands over all of it. [exitCode] is set only
     * where the failure was the process exiting.
     */
    private fun addFailure(raw: String, exitCode: Int? = null) {
        val advice = SessionFailure.classify(raw, exitCode, canRetry = retryableMessage() != null)
        showEmptyState(false)
        addRow(FailureBlock(advice) { action, card -> runFailureAction(action, advice, card) })
    }

    /**
     * The message a Retry would send again, or null when there isn't one that can be re-sent honestly.
     *
     * Three things disqualify it, and each of them is a way a one-click retry could do harm rather
     * than nothing: a turn still running (the retry would interject into it), a turn that already ran
     * tools before it failed (re-sending replays edits and commands that already happened — the
     * duplicate `git commit` case), and a message that carried images, whose bytes were released with
     * the send. A button labelled "Retry that message" must send *that* message or not be offered.
     */
    private fun retryableMessage(): String? {
        if (running || toolsRanThisTurn) return null
        return lastSentText?.takeIf { it.isNotBlank() }
    }

    /** Carries out a recovery action and says so in the card that offered it. */
    private fun runFailureAction(
        action: SessionFailure.Action, advice: SessionFailure.Advice, card: FailureBlock,
    ) {
        when (action) {
            SessionFailure.Action.SIGN_IN -> {
                CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(SessionFailure.SIGN_IN_COMMAND))
                card.note("Copied — run it in a terminal, then \"Check sign-in\".")
            }
            SessionFailure.Action.CHECK_SIGN_IN -> {
                card.note("Checking…")
                ApplicationManager.getApplication().executeOnPooledThread {
                    val answer = probeAuthStatus()
                    runOnEdt { card.note(answer) }
                }
            }
            SessionFailure.Action.RETRY -> {
                val text = retryableMessage()
                if (text == null) {
                    // The turn moved on between the card being drawn and the click — say so rather
                    // than sending something the button no longer stands for.
                    card.note("There is nothing to send again now.")
                    return
                }
                card.markRetried()
                doSend(text)
            }
            SessionFailure.Action.HEALTH -> showHealth()
            SessionFailure.Action.SETTINGS -> openSettings()
            SessionFailure.Action.COPY -> {
                CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(advice.detail))
                card.note("Copied.")
            }
        }
    }

    /**
     * Asks the CLI whether it is signed in, and relays **its** answer.
     *
     * Nothing here judges the output: the plugin holds no credentials and has no way to verify a
     * login, so the honest thing to show is the CLI's own line. A CLI too old for the `auth`
     * subcommand says so itself, which is also an answer. Off-EDT — the probe talks to the network.
     */
    private fun probeAuthStatus(): String {
        val configured = ClaudeSettings.getInstance().state.claudeCommand ?: "claude"
        ClaudePathResolver.invalidate() // the user may have just installed or re-logged-in
        val path = runCatching { ClaudePathResolver.resolve(configured) }.getOrNull()
        if (path.isNullOrBlank()) return "The claude command could not be found — try the health check."
        return try {
            val out = ExecUtil.execAndGetOutput(GeneralCommandLine(path, "auth", "status"), AUTH_PROBE_TIMEOUT_MS)
            val line = (out.stdout.lineSequence() + out.stderr.lineSequence()).firstOrNull { it.isNotBlank() }
            line?.trim()?.take(120) ?: "The CLI answered nothing."
        } catch (e: Exception) {
            thisLogger().info("auth status probe failed", e)
            "Could not run `claude auth status`."
        }
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
        // Selectable text should look selectable — the default arrow reads as inert chrome.
        p.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        return p
    }

    /** A monospaced, non-wrapping pane for diff text; the caller supplies the styles. */
    private fun diffPane(): JTextPane {
        val p = object : JTextPane() {
            // No wrapping: a wrapped diff line stops lining up with its counterpart.
            override fun getScrollableTracksViewportWidth(): Boolean = false
        }
        p.isEditable = false; p.isOpaque = false
        p.border = JBUI.Borders.empty()
        return p
    }

    /**
     * A quiet inline action.
     *
     * These were `JButton`s carrying `JButton.buttonType = "square"`. On the real macOS IDE LaF that
     * property forces a small fixed-size square that leaves no room for the label, so the actions
     * rendered as **two empty boxes** — while looking fine in the headless preview, whose LaF ignores
     * the property. `ActionLink` is the platform's own inline-action component: it always renders its
     * text, carries no button chrome in a transcript, and keeps focus traversal and accessibility.
     */
    private fun smallLink(text: String, run: () -> Unit): ActionLink {
        val link = ActionLink(text) { run() }
        link.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        return link
    }

    private fun copyToClipboard(text: String) {
        runCatching { CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(text)) }
    }

    /** Closes whichever hover-action row is currently revealed; at most one ever is. */
    private var hideRevealedHoverActions: (() -> Unit)? = null

    /**
     * Builds a row of secondary actions that stays hidden until the pointer is over [host] (or one of
     * the buttons takes keyboard focus), so the default view stays clean without the actions being
     * unreachable. Focus is included deliberately: hover-only actions are invisible to keyboard users.
     *
     * [enabled] is consulted each time the row is revealed, not once at construction, so an action can
     * depend on content that has not streamed in yet.
     */
    private fun hoverActions(
        host: JComponent,
        vararg actions: Pair<String, () -> Unit>,
        enabled: (String) -> Boolean = { true },
    ): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        row.isOpaque = false
        val buttons = actions.map { (label, run) -> smallLink(label, run) }
        buttons.forEach { row.add(it) }

        // Reserve the row's full size up front and toggle only the links inside it. Hiding the row
        // itself made it collapse to nothing, so revealing it on hover *pushed the conversation down* —
        // the text moved out from under the pointer and nothing useful appeared in its place.
        val reserved = row.preferredSize
        row.preferredSize = reserved
        row.minimumSize = reserved
        buttons.forEach { it.isVisible = false }

        // Tracked here rather than read back off the buttons: `enabled` can leave some of them hidden
        // while the row is revealed, so no single button's visibility answers "is this row showing?".
        var shown = false
        fun show(v: Boolean) {
            // Never hide while a button still holds focus, or tabbing into it makes it vanish.
            if (!v && buttons.any { it.hasFocus() }) return
            if (shown == v) return
            // Only one row is ever revealed, and whoever reveals one closes the last. Swing delivers no
            // mouseExited when the content moves out from under a *stationary* pointer — which is what
            // scrolling and a growing stream both do — so without this a row scrolls away still showing
            // its actions, and two rows appear to offer them at once.
            if (v) hideRevealedHoverActions?.invoke()
            shown = v
            // An action with nothing to act on is not offered at all: "Copy" on a turn that streamed no
            // text would put an empty string on the clipboard and look like it had worked.
            buttons.forEach { it.isVisible = v && enabled(it.text) }
            row.repaint()
            hideRevealedHoverActions = if (v) ({ show(false) }) else null
        }

        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = show(true)
            override fun mouseExited(e: MouseEvent) {
                // Moving onto a child still counts as being over the host.
                val p = SwingUtilities.convertPoint(e.component, e.point, host)
                if (!host.contains(p)) show(false)
            }
        }
        fun install(c: Component) {
            c.addMouseListener(hover)
            if (c is Container) c.components.forEach { install(it) }
        }
        install(host)
        val focus = object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = show(true)
            override fun focusLost(e: FocusEvent) = show(false)
        }
        buttons.forEach { it.addFocusListener(focus) }
        return row
    }

    private fun <T : JComponent> fullWidth(c: T): T { c.alignmentX = Component.LEFT_ALIGNMENT; c.isOpaque = false; return c }
    private fun click(run: () -> Unit) = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { run() } }

    /** Opens a Markdown hyperlink: real URLs in the browser, `file:` refs at the resolved project file. */
    private fun openMarkdownLink(href: String) {
        when {
            href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:") ->
                runCatching { BrowserUtil.browse(href) }
            href.startsWith("file:") -> openFileRef(href.removePrefix("file:"))
        }
    }

    /** "Reveal in Project" for a `file:` Markdown reference — selects it in the Project view. */
    private fun revealMarkdownLink(href: String) {
        val spec = href.removePrefix("file:")
        val vf = resolveProjectFile(spec) ?: return
        runCatching { ProjectView.getInstance(project).select(null, vf, true) }
    }

    /**
     * Opens an absolute path the CLI named — the plan file, which it writes under `~/.claude/plans/`
     * and so lives outside the project. [openFileRef] resolves project files only and would silently
     * do nothing here. Read-only, at the user's click, on a path the CLI itself reported.
     */
    private fun openPathInEditor(path: String) {
        val vf = runCatching { com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(path) }
            .getOrNull()
        if (vf == null) {
            addInfo("The plan file is no longer at $path.", false)
            scrollToBottomSoon()
            return
        }
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf), true)
    }

    private fun openFileRef(spec: String) {
        val line = Regex(":(\\d+)$|#L(\\d+)$").find(spec)?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull() }
        val vf = resolveProjectFile(spec) ?: return
        val desc = if (line != null) OpenFileDescriptor(project, vf, (line - 1).coerceAtLeast(0), 0) else OpenFileDescriptor(project, vf)
        FileEditorManager.getInstance(project).openTextEditor(desc, true)
    }

    /**
     * The unique in-project file a Markdown reference points at ("CLAUDE.md", "docs/Foo.kt", "Bar.kt:12"),
     * or null when absent, ambiguous, or the index is unavailable. Never guesses — a link is only offered
     * when exactly one project file matches. Runs in a read action; skipped during indexing.
     */
    private fun resolveProjectFile(ref: String): VirtualFile? {
        if (DumbService.isDumb(project)) return null
        val path = ref.substringBefore(':').substringBefore("#")
        val name = path.substringAfterLast('/').ifBlank { return null }
        return try {
            ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
                val idx = ProjectFileIndex.getInstance(project)
                val matches = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
                    .filter { idx.isInContent(it) }
                when {
                    path.contains('/') -> matches.singleOrNull { it.path.replace('\\', '/').endsWith(path) }
                    else -> matches.singleOrNull()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- compose actions ----------

    /**
     * Sends a message: the composer's live text + pending images, or — on the queue-drain path — a
     * [queuedImages] set captured when the user pressed Enter mid-turn (a paste made *while* that
     * entry waited belongs to the next message, so the live pending set is left alone there).
     *
     * When a turn is already running this is an **interjection**: the message goes down the same stdin
     * and the CLI folds it into the work in progress at the agent's next step, rather than waiting for
     * the turn to end. The differences from a fresh turn are all about not overwriting a run that is
     * still going — no new activity task, no status/health reset, and no new turn container for output
     * that is still landing in the current one.
     */
    private fun doSend(rawText: String, queuedImages: List<PendingImage>? = null) {
        // Mid-turn only reaches here as an interjection: ComposerModel.submit parks the message instead
        // whenever canInterject() says nothing is listening (a Stop in flight, an unobserved exit).
        val interjecting = running
        if (interjecting && !canInterject()) return
        val text = rawText.trim()
        // Pending images make a blank body sendable: "look at this" needs no prose.
        val images = queuedImages ?: composerModel.images
        if (text.isEmpty() && images.isEmpty()) return
        // Before the first message ever leaves, not as a banner beside it: the only useful moment to
        // say "this can read your files and run commands" is before it does. Declining cancels the
        // send — dismissing a disclosure is not consent, and the images stay pending.
        if (!FirstRunDialog.ensureAcknowledged(project)) return
        val attachments = composerModel.attachments
        val message = composerModel.buildMessage(rawText)
        val wire = images.map { it.toWireBlock() }
        // The interjection write comes first, before anything is consumed. A process can die at any
        // moment, including between canInterject() above and this line, and the write refuses to start a
        // replacement — so on failure the text is still in the composer and the user can be told, rather
        // than watching their message become a bubble that never reached anyone.
        if (interjecting && !writeInterjection(message, wire)) {
            addInfo("Claude stopped before that message was sent — it's still in the composer.", false)
            return
        }
        if (queuedImages == null) composerModel.clearImages() // `images` holds the snapshot leaving now
        if (interjecting) {
            // Deliberately *not* finalizeCurrent(): a block still streaming belongs to the turn above
            // this bubble — that text really was written before the interjection, and cutting it off
            // would drop deltas still arriving. Clearing `inAssistant` alone is enough to make the
            // agent's *next* output open a fresh turn below the bubble, in the order things happened.
            inAssistant = false
        } else {
            finalizeCurrent(); inAssistant = false; curTurn = null
            toolsRanThisTurn = false
        }
        // Remembered for a failure card's Retry. Images disqualify it: their bytes go out with this
        // send and are released, so "that message" could not be reproduced.
        lastSentText = text.takeIf { it.isNotEmpty() && images.isEmpty() }
        following = true // sending a message re-follows so the user always sees their own turn + the reply
        val bubble = addUserBubble(text, attachments, images, interjected = interjecting)
        // The queue must mirror **exactly what was written to stdin**, in order — including
        // interjections. An interjection goes out as an identical `{"type":"user",…}` line
        // (ClaudeSession.interjectUserMessage), so the CLI replays it like any other message; leaving it
        // out of the queue meant its replay arrived with nothing to match and popped somebody else's
        // entry instead, silently costing an unrelated message its revert action.
        //
        // Queue `message`, not `text`: what comes back is what went on the wire, and buildMessage
        // prepends the Android context block and any `@path` refs. Queuing the composer text meant the
        // first message sent with a context chip never matched — and, matched head-first, jammed every
        // checkpoint after it too.
        if (ClaudeSettings.getInstance().state.fileCheckpointing && message.isNotEmpty()) {
            if (awaitingCheckpoint.size >= MAX_AWAITING_CHECKPOINTS) awaitingCheckpoint.removeFirst()
            awaitingCheckpoint.addLast(message to bubble)
        }
        transcriptPresenter.onUserMessage(); showEmptyState(false)
        if (interjecting) {
            // A focus-card verb, not taskStarted(): the running task continues, and restarting it would
            // reset the activity task and clear the health tally the completion card still has to report.
            feed(interpreter.status("Following up"))
        } else {
            feed(interpreter.taskStarted(text.ifEmpty { imagesTaskLabel(images.size) }))
            statusModel.taskStarted(); refreshStatus()
        }
        // The interjection was already written above; a fresh turn is what starts the run.
        if (!interjecting) session.sendUserMessage(message, wire)
        composer.clearInput(); composerModel.clearAttachments(); composer.refreshChips()
        if (!interjecting) setRunning(true)
    }

    /**
     * Stands in for a live CLI process in the headless previews, which have none behind [session] and
     * would otherwise only ever exercise the queue fallback. False in production, where both answers
     * below come from the real process.
     */
    private var assumeLiveSessionForTest = false

    /**
     * The last message text that actually went to the CLI, kept only so a failure card can offer to
     * send it again — it is not a transcript history, and it is null whenever a retry would not be the
     * same message (an image-carrying send, whose bytes are released with it).
     */
    private var lastSentText: String? = null

    /** Whether the current turn ran any tool. A turn that did is not safe to replay with one click. */
    private var toolsRanThisTurn = false

    /**
     * Whether a mid-turn message can be folded into the running turn right now. It needs a live process
     * that has not been asked to stop: after a Stop the CLI is being torn down, and after an exit the
     * next write would land in a pipe nobody reads, so those messages queue instead.
     */
    private fun canInterject(): Boolean = (assumeLiveSessionForTest || session.isRunning) && !stopping

    /** The interjection write itself; false means no live process took it. */
    private fun writeInterjection(message: String, wire: List<UserMessageJson.ImageBlock>): Boolean =
        assumeLiveSessionForTest || session.interjectUserMessage(message, wire)

    /** Activity-map task label for an image-only message, which has no prose to label it with. */
    private fun imagesTaskLabel(count: Int) = if (count == 1) "Sent an image" else "Sent $count images"

    /** Base64 for the wire — the standard encoder, which emits no line breaks. */
    private fun PendingImage.toWireBlock() = UserMessageJson.ImageBlock(
        mediaType = image.mediaType,
        base64Data = Base64.getEncoder().encodeToString(image.bytes),
    )

    private fun attachFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        addPathAttachment(file.path)
    }

    /** Files pasted or dropped onto the composer input — attached exactly like the picker's choice. */
    private fun attachPastedFiles(files: List<java.io.File>) {
        for (f in files) addPathAttachment(f.absolutePath.replace('\\', '/'))
    }

    private fun addPathAttachment(path: String) {
        val base = project.basePath
        val rel = if (base != null && path.startsWith(base)) path.substring(base.length).trimStart('/') else path
        composerModel.addAttachment(rel)
        composer.refreshChips()
    }

    private fun primeProject() {
        if (running) return
        // A second send path, and it bypasses doSend — so it needs the disclosure gate of its own.
        // "Catch up on this project" starts a full agent session that reads and writes docs; showing
        // the notice only on the composer path would let the empty state's most inviting button be the
        // one way to start without ever seeing it.
        if (!FirstRunDialog.ensureAcknowledged(project)) return
        finalizeCurrent(); inAssistant = false; curTurn = null
        addUserBubble("Catch up on this project — read the docs, generate any missing recommended ones (CLAUDE.md, ARCHITECTURE, conventions), and summarize.", emptyList())
        transcriptPresenter.onUserMessage(); showEmptyState(false)
        feed(interpreter.taskStarted("Catch up on this project and complete its docs"))
        statusModel.taskStarted(); refreshStatus()
        session.sendUserMessage(PRIME_PROMPT)
        setRunning(true)
    }

    /**
     * Stop, via `interrupt` where the CLI supports it and a process kill where it does not — see
     * [StopPolicy] for why the two are not interchangeable and why a second press escalates.
     *
     * Both paths set [stopping]: even though `interrupt` leaves stdin readable, the user has just asked
     * for this turn to end, so a message typed now belongs to the *next* one and queues rather than
     * folding into a turn being torn down.
     */
    private fun stopRequest(): StopPolicy.StopAction {
        val decided = StopPolicy.decide(running, interruptPending, interruptSupported)
        if (decided == StopPolicy.StopAction.NONE) return decided
        // Fall through to the kill if the interrupt cannot even be written — a Stop that does nothing
        // because the process died in the gap would leave the panel claiming a turn is still running.
        val action = when {
            decided == StopPolicy.StopAction.INTERRUPT && session.interrupt() -> {
                interruptPending = true
                decided
            }
            else -> {
                session.stop()
                StopPolicy.StopAction.FORCE
            }
        }
        stopping = true
        StopPolicy.notice(action, inFlightCommands.isNotEmpty())?.let { addInfo(it, false) }
        // The composer's placeholder promises what Enter does; while stopping that changes from folding
        // into the run to queuing for the next turn, so it has to be re-read here.
        composer.refreshPlaceholder()
        header.setSessionState(StatusKind.WORKING, StopPolicy.statusLabel(action))
        return action
    }

    // ---------- menus ----------

    /** Folds a newly reported catalogue into what we already have; the richer source wins per name. */
    private fun noteSlashCommands(reported: List<SlashCommands.Command>) {
        if (reported.isEmpty()) return
        slashCommands = SlashCommands.merge(reported, slashCommands)
    }

    /**
     * The CLI's own commands as a submenu. Choosing one **fills the composer**; it never sends. A
     * command that takes arguments is incomplete until the user types them, and a menu where some
     * entries send and some don't is one you have to read twice.
     */
    private fun commandsMenu(): DefaultActionGroup {
        val offerable = SlashCommands.offerable(slashCommands)
        val group = DefaultActionGroup("Commands", true)
        if (offerable.isEmpty()) {
            // Said, not hidden: an empty submenu with no explanation reads as a broken menu.
            group.add(action("Not reported yet — start a conversation") {}.also { it.templatePresentation.isEnabled = false })
            return group
        }
        for (cmd in offerable) {
            val desc = SlashCommands.shortDescription(cmd)
            group.add(
                action(SlashCommands.label(cmd)) {
                    composer.putInInput(SlashCommands.insertion(cmd))
                }.also { if (desc.isNotBlank()) it.templatePresentation.description = desc },
            )
        }
        return group
    }

    private fun showSlashMenu(anchor: Component) {
        val group = DefaultActionGroup()
        group.add(Separator.create("Context"))
        group.add(action("Catch up on project") { primeProject() })
        group.add(action("Attach file…") { attachFile() })
        // The reachable form of "paste the image": ordinary paste gives text precedence, so a browser's
        // "Copy image" — which carries the picture and its URL — needs a gesture of its own.
        group.add(action("Attach image from clipboard") { composer.attachImageFromClipboard() })
        androidContextAction()?.let { group.add(it) }
        group.add(Separator.create("Claude Code"))
        group.add(commandsMenu())
        group.add(Separator.create("Model"))
        group.add(modelMenu())
        group.add(Separator.create("Conversation"))
        group.add(action("Clear conversation") { session.newConversation() })
        popup("Actions", group, anchor)
    }

    /**
     * The model picker. Its rows come from [ModelCatalog] — the CLI's documented aliases, whatever full
     * ids the user has pinned, and a free-text entry — because nothing can *enumerate* models: the CLI
     * has no list command and this plugin never holds an API key. What it can do honestly is show the
     * model the CLI **reports** it is running, which is the trailing note.
     */
    private fun modelMenu(): ActionGroup {
        val settings = ClaudeSettings.getInstance().state
        val entries = ModelCatalog.entries(settings.model, reportedModel, settings.customModels)
        val current = entries.firstOrNull { it.current }

        val group = DefaultActionGroup("Model: ${current?.label ?: "Default"}", true)
        for (e in entries) {
            val mark = if (e.current) "✓ " else "   "
            val detail = e.detail?.let { " — $it" } ?: ""
            group.add(action("$mark${e.label}$detail") { chooseModel(e.id) })
        }
        group.add(Separator.create())
        group.add(action("Custom model…") { promptForCustomModel() })
        // What the CLI says it is actually running — an alias resolves to a dated id and only the CLI
        // knows that mapping, so it is relayed, never derived here.
        ModelCatalog.resolvedNote(reportedModel)?.let {
            group.add(Separator.create())
            group.add(action(it) { }.apply { templatePresentation.isEnabled = false })
        }
        return group
    }

    /**
     * Applies a model choice. A running session switches **in place** over the control protocol — the
     * CLI acknowledges `set_model` and re-announces `system/init` with the resolved model, so neither the
     * process nor the conversation is lost. Anything that can't be switched that way is persisted and
     * takes effect on the next launch, and the transcript says which of the two happened rather than
     * leaving the user to guess whether the click did anything.
     */
    private fun chooseModel(id: String?) {
        val settings = ClaudeSettings.getInstance().state
        settings.model = id ?: ""
        id?.let { settings.customModels = ModelCatalog.remember(settings.customModels, it).toMutableList() }

        val label = ModelCatalog.label(id)
        val switched = id != null && session.setModel(id)
        showEmptyState(false)
        addInfo(
            if (switched) "Model switched to $label for this conversation."
            else "Model set to $label — it applies to the next conversation.",
            err = false,
        )
        scrollToBottomSoon()
    }

    private fun promptForCustomModel() {
        val entered = Messages.showInputDialog(
            project,
            "Model id (for example claude-sonnet-5). The CLI cannot list models, so this is passed through as typed.",
            "Custom Model",
            null,
            ClaudeSettings.getInstance().state.model.orEmpty(),
            null,
        )?.trim().orEmpty()
        if (entered.isNotEmpty()) chooseModel(entered)
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
        resumeAction()?.let { group.add(it) }
        rememberAction()?.let { group.add(it) }
        group.add(action("Health check…") { showHealth() })
        group.add(action("Settings…") { openSettings() })
        popup("More", group, anchor)
    }

    /** Opens the preflight panel, sourcing live session/diagnostics facts each time it (re)checks. */
    private fun showHealth() {
        HealthDialog(project) {
            HealthGatherer.SessionFacts(
                running = session.isRunning,
                sawSession = session.sawSession,
                activityEventCount = observedEvents,
                diagnosticsAvailable = diagnosticsAvailability()?.first,
                diagnosticsReason = diagnosticsAvailability()?.second,
            )
        }.show()
    }

    /** Best-effort read of whether scoped diagnostics can be collected right now. Null = couldn't tell. */
    private fun diagnosticsAvailability(): Pair<Boolean, String?>? = when {
        DumbService.getInstance(project).isDumb -> false to "IDE indexing is in progress"
        else -> true to null
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

    /**
     * The `/android-context` action, or null outside an Android project.
     *
     * Returning null rather than a disabled item is the deliberate half: a menu of things that don't
     * apply is worse than a shorter menu. Where an action *could* apply but can't right now — no device,
     * nothing built — it stays visible and says why, which is the pattern the later milestones' commands
     * inherit (see docs/ANDROID.md, "Availability gating is mandatory").
     */
    private fun androidContextAction(): AnAction? {
        if (!ClaudeSettings.getInstance().state.androidFeatures) return null
        val cached = AndroidContextResolver.getInstance(project).cachedOrNull()
        // Only ever consults the cache: this builds a menu on the EDT, and the resolver shells out.
        if (cached == null || cached.notAndroid) return null
        return action("Refresh Android context") { refreshAndroidContext(force = true) }
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

    /**
     * Applies a permission-mode choice. Mirrors [chooseModel]: a running session switches **in place**
     * over the control protocol, anything that can't is persisted for the next launch, and the transcript
     * says which of the two happened.
     *
     * Until 0.8.0 this wrote the setting and repainted the chip and did nothing else, so changing the
     * mode mid-conversation was a no-op that *looked* like it had worked — the chip claiming a policy
     * that was not in force, which is the exact failure [notePermissionModeFallback] exists to prevent
     * at launch.
     */
    private fun setMode(value: String) {
        ClaudeSettings.getInstance().state.permissionMode = value
        updateModeChip()
        val label = PermissionModes.byValue(value).shortName
        if (session.setPermissionMode(value)) {
            // Reported from the reply, not here: the CLI can refuse (`auto` on Haiku), and an optimistic
            // "switched" line would be the same lie in a new place.
            pendingModeLabel = label
        } else {
            showEmptyState(false)
            addInfo("Permission mode set to \"$label\" — it applies to the next conversation.", false)
            scrollToBottomSoon()
        }
    }
    private fun updateModeChip() {
        val m = PermissionModes.byValue(ClaudeSettings.getInstance().state.permissionMode)
        composer.setMode(m.shortName, m.dangerous)
    }

    private fun showEmptyState(show: Boolean) {
        chatHost.removeAll()
        chatHost.add(if (show) emptyState else transcriptLayer, BorderLayout.CENTER)
        chatHost.revalidate(); chatHost.repaint()
    }

    // ---------- event intake ----------

    private fun onLine(line: String) {
        ApplicationManager.getApplication().invokeLater({ handleEvent(line) }, ModalityState.any())
    }

    /**
     * Feed one raw protocol line straight into the renderer, synchronously.
     *
     * Test-only seam for the headless layout preview (`ChatLayoutPreviewTest`), which needs a
     * transcript with real content — bubbles, tool cards, diffs — to render. It is deliberately the
     * *same* path a live session takes ([handleEvent]), so a preview cannot drift from production
     * rendering. Nothing in the plugin calls this; it never touches the CLI or the session.
     */
    @TestOnly
    internal fun renderProtocolLineForPreview(line: String) = handleEvent(line)

    /**
     * Test-only: presses Stop and reports what it did and whether the "a command is still running"
     * caveat applied, so the wiring between the production event path and [StopPolicy] is assertable
     * headlessly. With no live session the interrupt cannot be written, so a test sees the FORCE
     * fallback — which is itself the behaviour worth pinning.
     */
    @TestOnly
    internal fun stopForTest(): Pair<StopPolicy.StopAction, Boolean> {
        val commandInFlight = inFlightCommands.isNotEmpty()
        return stopRequest() to commandInFlight
    }

    /** Test-only companion to [renderProtocolLineForPreview]: the user half of a turn. */
    @TestOnly
    internal fun addUserMessageForPreview(text: String, images: List<PendingImage> = emptyList()) {
        showEmptyState(false)
        addUserBubble(text, emptyList(), images)
        // Mirrors the production send, whose UI half this stands in for: without it a preview could
        // never show a failure card's Retry, which is exactly the state a real failure is in.
        lastSentText = text.takeIf { it.isNotEmpty() && images.isEmpty() }
    }

    /**
     * Test-only: the advice a failure would be rendered with *right now*, gating included — so a test
     * can pin what the card offers after a tool has run without spawning a CLI process.
     */
    @TestOnly
    internal fun failureAdviceForTest(raw: String, exitCode: Int? = null): SessionFailure.Advice =
        SessionFailure.classify(raw, exitCode, canRetry = retryableMessage() != null)

    /**
     * Test-only: pushes an Android context straight into the composer, bypassing the resolver.
     *
     * The resolver needs a real Android project on disk, an SDK and a device; a preview needs neither.
     * This seam feeds the **same** `setAndroidContext` the live path calls, so the strip and chips under
     * test are the production ones — only the fact-gathering is substituted.
     */
    @TestOnly
    internal fun setAndroidContextForPreview(context: io.mp.sightline.android.AndroidContext) {
        androidContextPinned = true
        composerModel.androidContextBlock = { chips -> AndroidContextFormatter.promptBlock(context, chips) }
        composer.setAndroidContext(context)
    }

    /** Test-only: the message that would actually be sent, context block and all. */
    @TestOnly
    internal fun buildMessageForPreview(text: String): String = composerModel.buildMessage(text)

    /** Test-only: how many turns are still held as live components (see [TranscriptRetention]). */
    @TestOnly
    internal fun liveTurnCountForTest(): Int = turns.size

    /** Test-only: the horizontal padding currently applied to the transcript column. */
    @TestOnly
    internal fun transcriptPaddingForTest(): Int = transcript.border?.getBorderInsets(transcript)?.left ?: 0

    /** Test-only: the width the transcript column actually has. */
    @TestOnly
    internal fun transcriptColumnWidthForTest(): Int = scroll.viewport.width

    /** Test-only: whether auto-scroll is still following the live end. */
    @TestOnly
    internal fun isFollowingForTest(): Boolean = following

    /** Test-only: drives the same deferred scroll the streaming path uses. */
    @TestOnly
    internal fun scrollToBottomForTest() = scrollToBottomSoon()

    /**
     * Test-only: runs the current text block's pending live render tick now. Deltas after the first
     * coalesce behind a wall-clock timer ([StreamingMarkdown.TICK_MS]); a test asserting mid-stream
     * rendering flushes that tick deterministically instead of sleeping through it.
     */
    @TestOnly
    internal fun flushStreamingRenderForTest() { curText?.flushLive() }

    /** Test-only: simulates the user dragging the scrollbar up, away from the live end. */
    @TestOnly
    internal fun scrollUpForTest() {
        val bar = scroll.verticalScrollBar
        bar.value = 0
    }

    /**
     * Test-only: puts the panel in the running state and parks [message] behind the in-flight turn,
     * so the queued-composer state can be previewed and asserted without a live CLI session.
     *
     * This is the fallback path — a stop in flight or a dead process — which is what a test gets for
     * free, since [canInterject] finds no live session. [interjectMessageForPreview] drives the other.
     */
    @TestOnly
    internal fun queueMessageForPreview(message: String) {
        // setRunning() defers to invokeLater, so apply the running state synchronously here — otherwise
        // the queue would still see an idle model and send the message instead of parking it.
        running = true
        composer.setRunning(true)
        composer.queueForTest(message)
    }

    /**
     * Test-only: submits [message] mid-turn with a session that *can* take it, so the interjection path
     * (bubble captioned into the running turn, no new task, still running) is assertable headlessly.
     * No CLI is behind it, so the write itself is a no-op — everything this exercises is panel-side.
     */
    @TestOnly
    internal fun interjectMessageForPreview(message: String) {
        running = true
        composer.setRunning(true)
        assumeLiveSessionForTest = true
        composer.refreshPlaceholder()
        doSend(message)
    }

    /**
     * Test-only: how many sent messages are still waiting for the CLI to replay their checkpoint id.
     * The invariant worth pinning is that this tracks stdin writes exactly — one entry per message
     * written, interjections included — because a queue that drifts from stdin pops the wrong entry.
     */
    @TestOnly
    internal fun awaitingCheckpointCountForTest(): Int = awaitingCheckpoint.size

    /** Test-only: whether a turn is in flight — an interjection must not end the run it joined. */
    @TestOnly
    internal fun isRunningForTest(): Boolean = running

    private fun handleEvent(line: String) {
        val o = try {
            JsonParser.parseString(line).asJsonObject
        } catch (e: Exception) {
            noteMalformedEvent("<unparseable>", null, null, line.length, e, critical = false)
            return
        }
        val type = o.str("type")
        // Any event at all is proof the CLI is alive — that is what the quiet check measures silence
        // against, so it is stamped before anything can decide to ignore this event.
        lastEventAt = System.currentTimeMillis()
        try {
            when (type) {
                "__panel" -> onPanel(o)
                "system" -> onSystem(o)
                "stream_event" -> o.objOrNull("event")?.let { onStream(it) }
                // A forwarded subagent message is distinguished only by parent_tool_use_id. Rendered as
                // an ordinary block it would interleave with the main agent's reply and read as one
                // confused voice, so it folds into the Task card that spawned it instead.
                "assistant" -> SubagentPresentation.parentOf(o)?.let { onSubagent(it, o) } ?: onAssistant(o)
                "user" -> SubagentPresentation.parentOf(o)?.let { onSubagent(it, o) } ?: onUser(o)
                "result" -> onResult(o)
                "control_request" -> onControlRequest(o)
                "control_response" -> onControlResponse(o)
                // Not a `system` event: rate limits arrive on their own top-level type.
                "rate_limit_event" -> rateLimits.onEvent(o, System.currentTimeMillis())
                    ?.let { addInfo(it.text, it.isError) }
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
            // A new process may be a new model, so the mode-fallback notice re-arms with each launch.
            // A new process has just read every MCP config file itself, so nothing is Sightline's to
            // manage and the config stamp is re-read from scratch rather than compared against a
            // previous process's view.
            "started" -> {
                setRunning(true)
                permissionModeFallbackNoted = false
                mcpSync.onProcessStarted()
                mcpTimeoutTimer.stop()
                project.getService(McpConfigWatcher::class.java)?.invalidate()
            }
            "cleared" -> clearAll()
            "config" -> applyConfigToUi()
            "error" -> {
                val text = o.str("text") ?: ""
                setRunning(false)
                addFailure(text)
                noteError(text.ifBlank { "Error" })
            }
            "exited" -> {
                finalizeCurrent(); inAssistant = false; setRunning(false)
                val c = o.intOrNull("code")
                if (c != null && c != 0) {
                    // The CLI's own stderr is the only thing that explains an exit code — an expired
                    // login, an unknown flag from extraArgs. Showing the code alone sends the user to
                    // idea.log to find out what the plugin already had in hand.
                    // The whole captured stderr tail goes to the card, not the one line the status
                    // needs: the card shows the CLI's own text and its "Copy details" hands over all
                    // of it, so summarising here would make that a claim it could not keep.
                    val why = o.str("text")?.takeIf { it.isNotBlank() }
                    addFailure(why ?: "", c)
                    val line = why?.lineSequence()?.last { l -> l.isNotBlank() }
                    noteError(if (line != null) "Claude exited (code $c): $line" else "Claude exited (code $c)")
                }
            }
        }
    }

    private fun onSystem(o: JsonObject) {
        when (o.str("subtype")) {
            "init" -> {
                setRunning(true)
                notePermissionModeFallback(o.str("permissionMode"))
                // The only trustworthy answer to "which model is this?": the CLI resolves an alias to a
                // dated id and re-announces init after a set_model, so this is read on every init.
                o.str("model")?.takeIf { it.isNotBlank() }?.let { reportedModel = it }
                // Names only, so it never displaces a richer entry — see [SlashCommands.merge].
                noteSlashCommands(SlashCommands.fromInitEvent(o))
            }
            // Earlier conversation has just been replaced by a summary. Left unsaid, Claude "forgetting"
            // something from an hour ago looks like a fault rather than the documented behaviour it is.
            "compact_boundary" -> SessionNotices.compactNotice(o)?.let { addInfo(it.text, it.isError) }
            "status" -> {
                if (o.str("status") == "requesting") feed(interpreter.status("Thinking"))
                // A live `set_permission_mode` is echoed back here, so the same divergence check that
                // guards launch also guards a mid-session switch.
                notePermissionModeFallback(o.str("permissionMode"))
            }
        }
    }

    /**
     * `--permission-mode auto` is model-gated: on a model that can't run the classifier the CLI
     * **silently** falls back to `default` and only says so by echoing the mode back in `system/init`
     * (docs/PROTOCOL.md §2). Left unreported, the mode chip claims a policy that isn't in force — the
     * user believes routine actions are being auto-approved while every one of them is prompting, or
     * worse, reads the chip as the reason something was allowed. Reported once per launch, not per turn.
     */
    private fun notePermissionModeFallback(reported: String?) {
        if (reported.isNullOrBlank() || permissionModeFallbackNoted) return
        val requested = (ClaudeSettings.getInstance().state.permissionMode ?: "").ifBlank { return }
        if (reported == requested) return
        permissionModeFallbackNoted = true
        // Name an unrecognised mode by its raw CLI value rather than through byValue(), which falls back
        // to `auto` — naming the wrong mode here would be the same class of error this notice exists for.
        fun name(v: String) = PermissionModes.all.firstOrNull { it.value == v }?.shortName ?: v
        addInfo("Claude is running in \"${name(reported)}\", not \"${name(requested)}\" — this model does not support that mode.", false)
    }

    /**
     * Checks whether the MCP servers declared on disk have changed, and if so offers them to the
     * running conversation.
     *
     * The stat-and-parse runs on a pooled thread and the decision comes back to the EDT, because the
     * file is one another process rewrites constantly and reading it on the EDT for a feature nobody
     * asked to wait for would be the wrong trade. Skipped entirely when no process is running: a fresh
     * CLI reads these files itself, so there is nothing to sync into.
     */
    private fun pollMcpConfig() {
        if (mcpPollInFlight || !session.isRunning || mcpSync.busy) return
        // Mid-turn is not the moment: the running turn's tools are already resolved, and dropping a
        // server while a tool call is in flight against it is a real way to break one. `running` is
        // this panel's "a turn is in flight", not "a process exists" — those are different questions.
        if (running) return
        mcpPollInFlight = true
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val declared = try {
                project.getService(McpConfigWatcher::class.java)?.pollDeclared()
            } catch (e: Exception) {
                thisLogger().warn("Sightline: MCP config poll failed", e)
                null
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                mcpPollInFlight = false
                if (declared == null || project.isDisposed) return@invokeLater
                val autoSync = ClaudeSettings.getInstance().state.mcpAutoSync
                if (mcpSync.offer(declared, autoSync, idle = !running)) mcpTimeoutTimer.restart()
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }

    /**
     * Routes a `control_response` line. Returns true when live MCP sync consumed it, in which case it
     * is not a reply anyone else is waiting for.
     */
    private fun onControlResponse(o: JsonObject): Boolean {
        SessionControlJson.parseReply(o)?.let { onSessionControlReply(it); return true }
        val consumed = mcpSync.onControlResponse(o)
        if (consumed && !mcpSync.busy) mcpTimeoutTimer.stop()
        return consumed
    }

    /**
     * The replies to Sightline's own session-control requests. Each is read rather than assumed: a
     * request the CLI refuses is precisely the case where a silent success would leave the panel
     * claiming something that did not happen.
     */
    private fun onSessionControlReply(reply: SessionControlJson.Reply) {
        when (reply.kind) {
            // The handshake's reply is the only place command descriptions and argument hints exist.
            SessionControlJson.Kind.INITIALIZE ->
                if (reply.ok) noteSlashCommands(SlashCommands.fromInitializeReply(reply.payload))
            SessionControlJson.Kind.INTERRUPT -> if (!reply.ok) {
                // This CLI cannot interrupt. Latch it so later presses don't ask again, say so once,
                // and finish the stop the user asked for the only way left.
                interruptSupported = false
                interruptPending = false
                if (!interruptUnsupportedNoted) {
                    interruptUnsupportedNoted = true
                    addInfo(StopPolicy.UNSUPPORTED_NOTICE, false)
                }
                session.stop()
            }

            SessionControlJson.Kind.PERMISSION_MODE -> {
                val label = pendingModeLabel ?: PermissionModes.byValue(ClaudeSettings.getInstance().state.permissionMode).shortName
                pendingModeLabel = null
                showEmptyState(false)
                if (reply.ok) {
                    addInfo("Permission mode switched to \"$label\" for this conversation.", false)
                } else {
                    // The CLI's own words: `auto` on a model that can't run the classifier is refused
                    // here rather than at launch, and its reason is more use than ours would be.
                    val why = reply.error?.let { " — $it" } ?: ""
                    addInfo(
                        "This conversation could not switch to \"$label\"$why. " +
                            "The setting is saved and applies to the next conversation.",
                        true,
                    )
                }
                scrollToBottomSoon()
            }

            SessionControlJson.Kind.REWIND -> {
                rewindInFlight = false
                showEmptyState(false)
                val notice = if (!reply.ok) {
                    // An older CLI refuses the subtype outright; anything else is its own words.
                    CheckpointPolicy.Notice(reply.error ?: CheckpointPolicy.UNSUPPORTED, true)
                } else {
                    // A *success* can still carry canRewind:false, so the payload is read rather than
                    // the subtype trusted — reporting a restore that did not happen is the one outcome
                    // this feature must never produce.
                    val p = reply.payload
                    CheckpointPolicy.outcome(
                        canRewind = p?.get("canRewind")?.let { it.isJsonPrimitive && it.asBoolean } ?: false,
                        skippedLinks = p?.intOrNull("skippedLinks") ?: 0,
                        error = p?.str("error"),
                    )
                }
                addInfo(notice.text, notice.isError)
                scrollToBottomSoon()
            }

            // A successful model switch is already reported by chooseModel, and the CLI re-announces the
            // resolved id in system/init. Only a refusal is news.
            SessionControlJson.Kind.MODEL -> if (!reply.ok) {
                showEmptyState(false)
                val why = reply.error?.let { " — $it" } ?: ""
                addInfo("This conversation could not switch model$why. The setting applies to the next conversation.", true)
                scrollToBottomSoon()
            }
        }
    }

    private fun onStream(ev: JsonObject) {
        when (ev.str("type")) {
            "message_start" -> {
                // The request's own usage, before a token of the reply arrives — so the chip moves as
                // the conversation grows rather than only at the end of a turn.
                noteUsage(ev.objOrNull("message")?.objOrNull("usage"))
                beginAssistant()
            }
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
        evictOldTurns()
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

    /**
     * Folds one forwarded subagent event into the `Task` card that owns it.
     *
     * A parent we have no card for is dropped rather than rendered loose — that happens when the Task
     * predates this transcript's retention window, and a stray "→ Ran grep" with nothing above it is
     * worse than silence. The status model is fed either way: what a subagent touched is observed fact
     * and belongs in the session's activity whether or not a card survived to hold it.
     */
    private fun onSubagent(parentId: String, o: JsonObject) {
        val card = toolCardsById[parentId]
        for (entry in SubagentPresentation.entries(o)) {
            when (entry) {
                is SubagentPresentation.Entry.Activity -> {
                    card?.appendSubagentActivity(entry.tool, entry.summary)
                    // Null id: the subagent's tool_use ids are its own, and correlating results across
                    // the boundary is not something the map needs to claim it can do.
                    feed(interpreter.toolUse(null, entry.tool, entry.input))
                }
                is SubagentPresentation.Entry.Say -> card?.appendSubagentText(entry.text)
            }
        }
        scrollToBottomSoon()
    }

    private fun onUser(o: JsonObject) {
        if (noteCheckpoint(o)) return
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
                id?.let { toolMetaById[it] }?.let { meta ->
                    meta.turn?.noteToolOutcome(meta.name, if (isErr) ToolOutcome.ERROR else ToolOutcome.OK, meta.path)
                }
                id?.let { tid -> pendingReportScans.remove(tid)?.let { (cmd, started) -> scanReportsAsync(cmd, started) } }
                id?.let { inFlightCommands.remove(it) }
            }
        }
        scrollToBottomSoon()
    }

    /**
     * Matches a replayed user message to the bubble that produced it and arms its revert action.
     *
     * Returns true when the event was a replay and nothing else should look at it. Matching is on the
     * text, not on position, so the CLI's synthetic `[Request interrupted by user]` — which is also a
     * `user` event with a `uuid` — can never claim a checkpoint that isn't its.
     */
    private fun noteCheckpoint(o: JsonObject): Boolean {
        if (awaitingCheckpoint.isEmpty()) return false
        val replayed = CheckpointPolicy.replayedText(o) ?: return false
        // The CLI writes its own messages into this stream, shaped identically to a replay. Those are
        // excluded before the queue is touched at all — letting one through would consume a checkpoint
        // that belongs to a message the user actually sent.
        if (CheckpointPolicy.isCliSynthetic(replayed)) return false
        val (sent, bubble) = awaitingCheckpoint.removeFirst()
        if (CheckpointPolicy.isSameMessage(sent, replayed)) {
            o.str("uuid")?.let { bubble.armRevert(sent, it) }
            return true
        }
        // Popped and discarded, not left at the head. A queued message whose replay never arrived is
        // gone, and holding it would mean one lost replay costs every checkpoint for the rest of the
        // conversation. Discarding **one** per replay self-heals within a message or two, and the
        // discarded bubble simply never offers a revert — which is honest, since we have no id for it.
        // What is never done is assigning this uuid to it anyway: a revert to the wrong point is the
        // one outcome worse than no revert.
        return false
    }

    /**
     * Asks the CLI to restore files, once the user has confirmed against the limits.
     *
     * The confirmation is not ceremony: the restore covers only what Claude wrote with Edit/Write, so a
     * user who believes it undoes a `Bash` command's damage would stop looking exactly when they should
     * start. [CheckpointPolicy.confirmation] puts that in the dialog, every time.
     */
    private fun requestRewind(messageText: String, checkpointId: String) {
        if (rewindInFlight) return
        val ok = Messages.showYesNoDialog(
            project, CheckpointPolicy.confirmation(messageText), "Revert File Changes",
            "Restore Files", "Cancel", Messages.getWarningIcon(),
        )
        if (ok != Messages.YES) return
        showEmptyState(false)
        if (!session.rewindFiles(checkpointId)) {
            addInfo("There is no Claude session running, so there is nothing to restore from.", true)
            scrollToBottomSoon()
            return
        }
        rewindInFlight = true
    }

    private fun onResult(o: JsonObject) {
        finalizeCurrent(); inAssistant = false; setRunning(false)
        val isErr = o.has("is_error") && o.get("is_error").let { it.isJsonPrimitive && it.asBoolean }
        // Run metadata lives in a structured, secondary completion card — not the status strip. It also
        // surfaces recovered command failures (the health layer) as end-of-turn warnings.
        curTurn?.addCompletionFooter(
            o.dblOrNull("total_cost_usd"), o.dblOrNull("duration_ms"), o.intOrNull("num_turns"),
            isErr, statusModel.health().recoveredFailures,
        )
        noteUsage(o.objOrNull("usage"))
        noteContextWindow(o.objOrNull("modelUsage"))
        // Only ever writes when the user has accepted; SessionMemory is the single gate.
        sessionMemory.remember(session.currentSessionId)
        feed(interpreter.taskDone(o.str("result") ?: "", isErr))
        if (isErr) o.str("result")?.let { addFailure(it) }
    }

    // ---------- tool body rendering ----------

    private fun renderToolBody(name: String, id: String?, input: JsonObject?, card: ToolCard) {
        // Once a tool has run, re-sending the message that started the turn would replay it — the
        // failure card's Retry is withheld from here on.
        toolsRanThisTurn = true
        target = card.bodyDoc
        card.setDetails(name, input ?: JsonObject(), renderToolContent(name, input ?: JsonObject(), card))
        target = null
        if (name == "Edit" || name == "Write" || name == "MultiEdit") card.expand()
        if (id != null) {
            renderedTools.add(id); toolCardsById[id] = card
            // A Bash leaves a child process running; a Task may have a subagent running one we cannot
            // see the end of. Either means the Stop notice must say so — see [StopPolicy.LINGERING_TOOLS].
            if (name in StopPolicy.LINGERING_TOOLS) inFlightCommands.add(id)
            // Remembered so the turn can tally the outcome when the result arrives — the result event
            // carries only the tool_use_id, not what the tool was or which file it touched.
            toolMetaById[id] = ToolMeta(name, input?.str("file_path"), curTurn, card)
        }
        feed(interpreter.toolUse(id, name, input))
        noteBuildCommand(id, name, input)
    }

    /**
     * Attaches a [FileEditBlock] when there is a card to host it, and otherwise writes the diff into
     * the current [target] document as unified text.
     *
     * The text path is what the **approval preview** uses: an Allow/Deny prompt for an edit renders
     * the same tool content but has no ToolCard, and showing nothing there would ask the user to
     * approve a change they cannot see.
     */
    private fun addEditOrText(
        card: ToolCard?, path: String?, hunks: List<List<Pair<String, String>>>, note: String?,
    ) {
        if (card != null) { card.addEdit(path, hunks, note); return }
        val rows = hunks.flatten()
        val shown = rows.take(DiffPresentation.visibleRows(rows.size, expanded = false))
        for ((kind, text) in shown) {
            val style = when (kind) { "add" -> sDiffAdd; "del" -> sDiffDel; else -> sCode }
            insert(LineDiff.sign(kind) + text + "\n", style)
        }
        DiffPresentation.overflowText(DiffPresentation.overflow(rows.size, expanded = false))
            ?.let { insert("$it\n", sMuted) }
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
    private fun renderToolContent(name: String, inp: JsonObject, card: ToolCard?): String {
        var summary = ""
        when (name) {
            "Bash" -> {
                inp.str("description")?.let { insert("$it\n", sMuted) }
                val cmd = inp.str("command") ?: ""
                insert("$ " + cmd + "\n", sCode); summary = oneLine(cmd, 72)
            }
            "Edit" -> {
                val path = inp.str("file_path")
                summary = shortPath(path)
                addEditOrText(card, path, listOf(LineDiff.diff(inp.str("old_string") ?: "", inp.str("new_string") ?: "")), null)
            }
            "MultiEdit" -> {
                val path = inp.str("file_path")
                summary = shortPath(path)
                val edits = if (inp.has("edits") && inp.get("edits").isJsonArray) inp.getAsJsonArray("edits") else null
                // Each edit keeps its own hunk so they can be numbered — concatenating them into one
                // document made several edits to one file read as a single incoherent change.
                val hunks = edits?.map { e ->
                    val eo = e.asJsonObject
                    LineDiff.diff(eo.str("old_string") ?: "", eo.str("new_string") ?: "")
                }.orEmpty()
                addEditOrText(card, path, hunks, null)
            }
            "Write" -> {
                val path = inp.str("file_path")
                summary = shortPath(path)
                addEditOrText(card, path, listOf(LineDiff.diff("", inp.str("content") ?: "")), "New file")
            }
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
            "AskUserQuestion" -> {
                // A structured question — never dump the raw JSON here; the interactive block shows it.
                val qs = if (inp.has("questions") && inp.get("questions").isJsonArray) inp.getAsJsonArray("questions") else null
                val n = qs?.size() ?: 0
                val count = if (n == 1) "1 question" else "$n questions"
                summary = qs?.firstOrNull { it.isJsonObject }?.asJsonObject?.str("header")?.takeIf { it.isNotBlank() } ?: count
                insert(count + "\n", sMuted)
            }
            "Task" -> {
                // Until 0.8.0 a Task fell through to the JSON dump below — so every subagent, now the
                // most common tool there is, rendered as a wall of escaped prompt text.
                val agent = inp.str("subagent_type")?.takeIf { it.isNotBlank() }
                summary = inp.str("description")?.takeIf { it.isNotBlank() } ?: agent ?: "subagent"
                agent?.let { insert("agent: $it\n", sMuted) }
                inp.str("prompt")?.takeIf { it.isNotBlank() }?.let { insert(truncate(it) + "\n", sMuted) }
            }
            "Skill" -> {
                summary = inp.str("skill") ?: inp.str("command") ?: ""
                inp.str("args")?.takeIf { it.isNotBlank() }?.let { insert("$it\n", sMuted) }
            }
            "SlashCommand" -> { summary = inp.str("command") ?: ""; insert((inp.str("command") ?: "") + "\n", sMuted) }
            "ExitPlanMode" -> {
                summary = "plan"
                inp.str("plan")?.takeIf { it.isNotBlank() }?.let { insert(truncate(it) + "\n", sMuted) }
            }
            "BashOutput" -> { summary = inp.str("bash_id") ?: ""; insert("shell: " + (inp.str("bash_id") ?: "") + "\n", sMuted) }
            "KillShell" -> { summary = inp.str("shell_id") ?: inp.str("bash_id") ?: "" ; insert("shell: $summary\n", sMuted) }
            "NotebookEdit" -> {
                val path = inp.str("notebook_path") ?: inp.str("file_path")
                summary = shortPath(path)
                addEditOrText(card, path, listOf(LineDiff.diff("", inp.str("new_source") ?: "")), inp.str("cell_id")?.let { "Cell $it" })
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
        // AskUserQuestion rides the same can_use_tool channel but is a request for input, not permission
        // to act — route it to the structured question UI instead of a generic Allow/Deny approval.
        if (req.str("tool_name") == "AskUserQuestion") { showAskUserQuestion(reqId, req); return }
        // Plan mode ends by asking permission for ExitPlanMode, with the plan itself in the payload.
        // That is a plan to *review*, not a yes/no — approving something you cannot edit is how a
        // wrong plan becomes a wrong branch.
        if (req.str("tool_name") == "ExitPlanMode" && showPlanReview(reqId, req)) return
        val turn = ensureAssistant()
        val toolName = req.str("tool_name") ?: "tool"
        val input = req.objOrNull("input") ?: JsonObject()
        val inputJson = input.toString()
        val toolUseId = req.str("tool_use_id")
        val title = req.str("title") ?: "Allow $toolName?"
        val targetPath = input.str("file_path") ?: input.str("notebook_path") ?: input.str("new_file_path")
        val suggestions = if (req.has("permission_suggestions") && req.get("permission_suggestions").isJsonArray)
            req.getAsJsonArray("permission_suggestions").toString() else null

        // The buttons just ask the coordinator to resolve; the one handler below does the real work,
        // whether the decision came from a human click or the sandbox test bridge.
        val block = ApprovalBlock(
            title, toolName, input, canAllowAlways = suggestions != null,
            onDecision = { decision, reason -> approvalCoordinator.respond(reqId, decision, reason) },
        )
        turn.addBlock(block)

        val handler: (ApprovalDecision, String?) -> Unit = { decision, reason ->
            runOnEdt {
                when (decision) {
                    ApprovalDecision.ALLOW -> session.respondAllow(reqId, inputJson, null)
                    ApprovalDecision.ALLOW_ALWAYS -> session.respondAllow(reqId, inputJson, suggestions)
                    ApprovalDecision.DENY -> {
                        // The reason is what the model is told the tool returned, so it is the whole
                        // point of the affordance: "not that — do this instead" without ending the turn.
                        session.respondDeny(reqId, reason ?: "Denied by user")
                        onToolDenied(toolUseId, toolName, input)
                    }
                }
                resolvePermission()
                block.markResolved(decision, reason)
            }
        }
        approvalCoordinator.register(
            PendingApproval(reqId, toolUseId, toolName, title, targetPath, suggestions != null, handler),
        )
        statusModel.permissionRequested(); refreshStatus()
        requestAttention()
        scrollToBottomSoon()
    }

    /**
     * Structured `AskUserQuestion` interaction — a request for input, not permission. Parses the input
     * off the raw JSON (never shown to the user), presents a question block, and returns the selected
     * answers through the same control response. A parse failure still registers so Cancel can unblock
     * the CLI; it just can't be answered.
     */
    /**
     * Renders the proposed plan as a reviewable document: the Markdown as Claude wrote it, with
     * **Approve**, **Edit plan…** and **Keep planning**. Returns false when the payload carries no
     * plan, so the caller falls back to the ordinary approval card rather than showing an empty one.
     *
     * Editing sends the rewritten plan back through the denial channel, which the CLI hands to the
     * model verbatim — so "not that, this" reaches Claude as an instruction rather than as a refusal
     * it will simply retry.
     */
    private fun showPlanReview(reqId: String, req: JsonObject): Boolean {
        val input = req.objOrNull("input") ?: return false
        val plan = PlanReview.of(input.str("plan"), input.str("planFilePath")) ?: return false
        val turn = ensureAssistant()
        val block = PlanBlock(plan) { decision -> planCoordinator(reqId, plan, decision) }
        turn.addBlock(block)
        statusModel.permissionRequested("Waiting for you to review the plan"); refreshStatus()
        requestAttention()
        scrollToBottomSoon()
        return true
    }

    /** One-shot resolution for a plan review — the same discipline as an approval. */
    private fun planCoordinator(reqId: String, plan: PlanReview.Plan, decision: PlanDecision) {
        when (decision) {
            is PlanDecision.Approve -> session.respondAllow(reqId, JsonObject().also {
                it.addProperty("plan", plan.markdown)
                plan.filePath?.let { p -> it.addProperty("planFilePath", p) }
            }.toString(), null)
            is PlanDecision.Revise -> session.respondDeny(reqId, PlanReview.revisedMessage(decision.plan))
            is PlanDecision.KeepPlanning -> session.respondDeny(reqId, PlanReview.keepPlanningMessage(decision.reason))
        }
        resolvePermission()
    }

    private sealed interface PlanDecision {
        object Approve : PlanDecision
        data class Revise(val plan: String) : PlanDecision
        data class KeepPlanning(val reason: String?) : PlanDecision
    }

    private fun showAskUserQuestion(reqId: String, req: JsonObject) {
        val turn = ensureAssistant()
        val input = req.objOrNull("input") ?: JsonObject()
        val toolUseId = req.str("tool_use_id")
        val parsed = AskUserQuestionParser.parse(input)
        val request = (parsed as? ParseResult.Ok)?.value
        if (parsed is ParseResult.Invalid) {
            thisLogger().warn("AskUserQuestion could not be parsed: ${parsed.reason} (fields=${parsed.fields})")
        }

        val block = AskUserQuestionBlock(
            request = request,
            invalidReason = (parsed as? ParseResult.Invalid)?.reason,
            onSubmit = { answers ->
                request?.let { r ->
                    val updated = runCatching { AskUserQuestionResponseBuilder.build(input, r, answers).toString() }
                        .onFailure { thisLogger().warn("AskUserQuestion response build failed", it) }
                        .getOrNull()
                    if (updated != null) questionCoordinator.respond(reqId, QuestionResolution.Answered(updated))
                }
            },
            onCancel = { questionCoordinator.respond(reqId, QuestionResolution.Cancelled) },
        )
        turn.addBlock(block)

        val handler: (QuestionResolution) -> Unit = { resolution ->
            runOnEdt {
                // A simulated (test-bridge) request has no real CLI behind it — resolve the UI only.
                if (!reqId.startsWith(SIM_REQ_PREFIX)) when (resolution) {
                    is QuestionResolution.Answered -> session.respondAllow(reqId, resolution.updatedInputJson, null)
                    QuestionResolution.Cancelled -> session.respondDeny(reqId, "User cancelled the question")
                }
                resolvePermission()
                block.markResolved(resolution)
            }
        }
        questionCoordinator.register(
            PendingQuestion(reqId, toolUseId, request ?: UserQuestionRequest(emptyList()), input.toString(), handler),
        )
        statusModel.permissionRequested("Waiting for your answer"); refreshStatus()
        requestAttention()
        scrollToBottomSoon()
    }

    /** TEST-ONLY (sandbox bridge): render a synthetic AskUserQuestion as if the CLI had asked it. */
    private fun simulateAskUserQuestion(input: JsonObject) {
        runOnEdt {
            val req = JsonObject().apply {
                addProperty("subtype", "can_use_tool")
                addProperty("tool_name", "AskUserQuestion")
                add("input", input)
            }
            showAskUserQuestion(SIM_REQ_PREFIX + System.nanoTime(), req)
        }
    }

    /** Runs [r] on the EDT now if already there (human click), else marshals it (test-bridge thread). */
    private fun runOnEdt(r: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) r() else app.invokeLater({ r() }, ModalityState.any())
    }

    /** Sets a stable accessible name (calls the getter explicitly; the inherited field is null-until-lazy). */
    private fun <T : JComponent> T.named(accessibleName: String): T {
        getAccessibleContext()?.accessibleName = accessibleName
        return this
    }

    /** The user denied a tool: record a blocked node/status (never an execution) and mark its card. */
    private fun onToolDenied(toolUseId: String?, toolName: String, input: JsonObject) {
        feed(interpreter.toolDenied(toolUseId, toolName, input))
        val reason = "Denied by user"
        toolUseId?.let { toolCardsById[it] }?.markBlocked(reason)
        toolUseId?.let { toolMetaById[it] }?.let { it.turn?.noteToolOutcome(it.name, ToolOutcome.BLOCKED, it.path) }
    }

    private fun resolvePermission() { statusModel.permissionResolved(); refreshStatus() }

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
        // A turn boundary resets the quiet clock: silence before this moment belongs to the turn that
        // has just ended, and carrying it over would greet the next turn with a stale complaint.
        lastEventAt = System.currentTimeMillis()
        lastQuietNoticeAt = 0L
        quietTimer.let { if (v) it.start() else it.stop() }
        // Either boundary ends a stop window: a new process is listening again, or the old one is gone
        // and the next message will start one.
        stopping = false
        // The turn is over however it ended, so the next Stop starts from the polite path again and no
        // command from it can still be in flight. `interruptSupported` deliberately does NOT reset —
        // it is a fact about this CLI, not about this turn.
        interruptPending = false
        inFlightCommands.clear()
        // The reply this was waiting for can no longer arrive if the process is gone. Left set, every
        // later revert would silently do nothing — the worst shape of failure for a destructive action,
        // since the user sees no error and assumes the click was ignored rather than lost.
        rewindInFlight = false
        SwingUtilities.invokeLater {
            composer.setRunning(v)
            refreshStatus()
            // A turn just ended — send whatever couldn't be folded into it (a message submitted during a
            // Stop, or after the process had exited).
            if (!v) drainQueuedMessage()
        }
    }

    /**
     * Sends one queued message per completed turn. The queue is now only the fallback for messages that
     * couldn't be interjected into a live turn (see [canInterject]) — one at a time still, because each
     * of those is a full turn of its own and firing them together would interleave their output.
     */
    private fun drainQueuedMessage() {
        if (running) return
        val next = composer.takeQueuedMessage() ?: return
        doSend(next.text, next.images)
    }

    /**
     * Push normalised activity events into the status model.
     *
     * These are the same observable tool events the activity map consumed before it was removed; the
     * status strip's live text, its recovered-failure tally and the per-turn processing summary all
     * still run on them, which is why the interpreter and the output parsers stayed.
     */
    private fun feed(events: List<AgentActivityEvent>) {
        if (events.isEmpty()) return
        observedEvents += events.size
        for (e in events) statusModel.apply(e)
        refreshStatus()
    }

    /**
     * Records the occupancy of one `usage` object. Everything is relayed, nothing derived: the CLI
     * states these four numbers and [ContextUsage] only adds them up.
     */
    private fun noteUsage(usage: JsonObject?) {
        if (usage == null) return
        val tokens = ContextUsage.occupancy(
            inputTokens = usage.longOrZero("input_tokens"),
            cacheCreationTokens = usage.longOrZero("cache_creation_input_tokens"),
            cacheReadTokens = usage.longOrZero("cache_read_input_tokens"),
            outputTokens = usage.longOrZero("output_tokens"),
        )
        if (tokens <= 0) return
        contextTokens = tokens
        refreshContextUsage()
    }

    /**
     * The context window, read from `result.modelUsage`. Keyed by model id, and the CLI reports usage
     * for **every** model a turn touched (a subagent on a different model adds its own entry), so the
     * entry for the model this session announced wins; failing that, the largest window reported, since
     * picking an arbitrary entry could shrink the denominator to a subagent's smaller model.
     */
    private fun noteContextWindow(modelUsage: JsonObject?) {
        if (modelUsage == null) return
        val reported = reportedModel
        val exact = reported?.let { modelUsage.objOrNull(it)?.longOrNull("contextWindow") }
        val window = exact ?: modelUsage.keySet()
            .mapNotNull { modelUsage.objOrNull(it)?.longOrNull("contextWindow") }
            .maxOrNull()
        if (window != null && window > 0) {
            contextWindow = window
            refreshContextUsage()
        }
    }

    /**
     * Runs the CLI's own `/context`, which prints a breakdown by category (system prompt, tools,
     * memory, messages). Sent as an ordinary user line because that is what it is — a local command,
     * answered with a synthetic turn at no token cost (verified: `num_turns: 0`, zero usage).
     */
    /**
     * Project files matching a typed `@` prefix, for the composer's mention popup.
     *
     * Runs on the EDT because a completion popup has to answer within a keystroke, so it is kept cheap
     * deliberately: the filename index is asked for **names** first and only the survivors are resolved
     * to files, which is the difference between touching a few dozen entries and walking the project.
     * During indexing there is no index to ask, so it returns nothing rather than blocking the typist —
     * a popup that doesn't appear is a far smaller harm than a composer that freezes.
     */
    private fun searchProjectFiles(prefix: String): List<String> {
        if (DumbService.getInstance(project).isDumb) return emptyList()
        val base = project.basePath
        val scope = GlobalSearchScope.projectScope(project)
        val names = ArrayList<String>(MENTION_NAME_CAP)
        val needle = prefix.lowercase()
        return runCatching {
            // computeBlocking, not the deprecated compute(...) — the Marketplace's own report flagged
            // three of those on an earlier release, and this codebase now carries zero deprecated API.
            ReadAction.computeBlocking<List<String>, RuntimeException> {
                FilenameIndex.processAllFileNames({ name ->
                    if (names.size >= MENTION_NAME_CAP) false
                    else {
                        if (prefix.isBlank() || name.lowercase().contains(needle)) names.add(name)
                        true
                    }
                }, scope, null)
                val paths = names.asSequence()
                    .flatMap { FilenameIndex.getVirtualFilesByName(it, scope).asSequence() }
                    .filter { !it.isDirectory }
                    .map { f -> base?.let { b -> f.path.removePrefix(b).trimStart('/') } ?: f.path }
                    .take(MENTION_PATH_CAP)
                    .toList()
                MentionQuery.rank(paths, prefix)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * "Resume conversation from <date>", when there is one saved and nothing running. Absent entirely
     * when remembering is off or nothing is stored — an action that explains why it is disabled is
     * still an action the user has to read past.
     */
    private fun resumeAction(): AnAction? {
        if (!sessionMemory.enabled || running) return null
        val id = sessionMemory.savedId ?: return null
        return action(SessionPersistence.resumeLabel(sessionMemory.savedDate)) { resumeSavedSession(id) }
    }

    /**
     * Hands a stored session id back to the CLI. The transcript is **not** restored and the notice says
     * so: Sightline keeps no transcript, so the panel starts where you are now while Claude gets its
     * history back. Promising otherwise would be the one thing this feature must not do.
     */
    /**
     * Offered only while remembering is **off**: the invitation to turn it on, which opens the consent
     * dialog rather than flipping a flag. Once on, it disappears and the resume action takes its place.
     */
    private fun rememberAction(): AnAction? {
        if (sessionMemory.enabled) return null
        return action("Remember this conversation…") {
            if (ensureSessionMemoryConsent()) {
                sessionMemory.remember(session.currentSessionId)
                showEmptyState(false)
                addInfo(
                    if (session.currentSessionId != null) {
                        "This project's session id will be remembered, so you can resume after a restart."
                    } else {
                        // Nothing to save yet, and saying "saved" would be false. The setting is on; the
                        // id lands at the end of the first turn.
                        "Sightline will remember this project's session id once a conversation has started."
                    },
                    false,
                )
                scrollToBottomSoon()
            }
        }
    }

    private fun resumeSavedSession(id: String) {
        if (!session.resumeSession(id)) {
            addInfo("Claude is already running — clear the conversation first to resume another.", false)
            scrollToBottomSoon()
            return
        }
        showEmptyState(false)
        addInfo(SessionPersistence.RESUMED_NOTICE, false)
        scrollToBottomSoon()
        composer.requestInputFocus()
    }

    /**
     * Asks, once, whether Sightline may remember this project's session id — and does nothing at all
     * unless the answer is yes.
     *
     * This is a privacy decision with a standing decision behind it ("nothing is persisted but
     * settings"), so it is taken by the user in as many words, not inferred from the fact that they
     * clicked something adjacent. Returns true when remembering is on afterwards.
     */
    private fun ensureSessionMemoryConsent(): Boolean {
        if (sessionMemory.enabled) return true
        val accepted = Messages.showYesNoDialog(
            project,
            SessionPersistence.CONSENT,
            SessionPersistence.TITLE,
            SessionPersistence.ACCEPT,
            SessionPersistence.DECLINE,
            Messages.getQuestionIcon(),
        ) == Messages.YES
        ClaudeSettings.getInstance().state.rememberSessions = accepted
        if (!accepted) sessionMemory.forget()
        return accepted
    }

    private fun askForContextBreakdown() {
        showEmptyState(false)
        if (running) {
            addInfo("Claude is working — /context can be run once the turn finishes.", false)
            scrollToBottomSoon()
            return
        }
        doSend("/context")
    }

    private fun refreshContextUsage() {
        val tokens = contextTokens ?: return
        composer.setContextUsage(ContextUsage.view(ContextUsage.Snapshot(tokens, contextWindow)))
    }

    /**
     * Says so when a running turn has produced nothing for a while — the "it just sits there with no
     * timeout and no error" case. [StallPolicy] holds the rule that makes this safe in an Android
     * project: a tool in flight (a Gradle build, a test run, a subagent) explains the silence, so
     * nothing is said at any duration while one is running.
     */
    /**
     * Marks the tool window when the turn cannot proceed without the user — a permission prompt or a
     * question — and the panel is not on screen. Without it, a docked window the user has switched away
     * from blocks silently and the turn simply never finishes.
     *
     * **Only for the blocking cases.** A turn finishing while hidden is not something the user must act
     * on, and a badge that fires on every completed run is noise that teaches people to ignore it. Uses
     * the platform's own attention affordance rather than a balloon, which would steal focus — the
     * complaint this is meant to answer, not cause.
     */
    private fun requestAttention() {
        val window = runCatching {
            ToolWindowManager.getInstance(project).getToolWindow(SightlineEditorAction.TOOL_WINDOW_ID)
        }.getOrNull() ?: return
        if (window.isVisible) return
        runCatching { ToolWindowManager.getInstance(project).notifyByBalloon(
            SightlineEditorAction.TOOL_WINDOW_ID,
            com.intellij.openapi.ui.MessageType.INFO,
            "Claude is waiting for you.",
        ) }
    }

    private fun checkForQuiet() {
        if (!running) return
        val now = System.currentTimeMillis()
        val silence = now - lastEventAt
        val sinceNotice = lastQuietNoticeAt.takeIf { it > 0 }?.let { now - it }
        if (!StallPolicy.quiet(silence, inFlightCommands.size, sinceNotice)) return
        lastQuietNoticeAt = now
        showEmptyState(false)
        addInfo(StallPolicy.notice(silence), false)
        scrollToBottomSoon()
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
        // Live model name while working; nothing extra once done (run metadata lives in the turn footer).
        // The health layer rides alongside as muted right-aligned meta — a recovered command failure is
        // reported here, deliberately kept off the primary status line so it can't pin the agent red.
        val health = statusModel.health()
        val healthChip = if (running && health.recoveredFailures > 0) {
            val n = health.recoveredFailures
            "$n recovered ${if (n == 1) "failure" else "failures"}"
        } else null
        val meta = listOfNotNull(if (running) modelLabel().takeIf { it.isNotBlank() } else null, healthChip)
            .joinToString("   ·   ").takeIf { it.isNotBlank() }
        statusStrip.update(view, meta)
        header.setSessionState(coarseKind(view), coarseLabel(view))
        uiState.sessionState = if (approvalCoordinator.hasPending()) "WAITING_FOR_APPROVAL" else view.kind.name
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
        // Follow the model's prompt so a question reads "Waiting for your answer", not "…approval".
        StatusKind.PERMISSION -> v.primary
        StatusKind.WARNING -> "Stopped"
        StatusKind.SUCCESS, StatusKind.COMPLETED -> "Ready"
        else -> "Working"
    }

    // ---------- lifecycle ----------

    private fun clearAll() {
        transcript.removeAll()
        // removeAll() takes the notice with it — put it back, or a later eviction has nowhere to report.
        transcript.add(retentionNotice)
        toolCardsById.clear(); toolMetaById.clear(); renderedTools.clear(); evictedTurns = 0; pendingReportScans.clear(); approvalCoordinator.clear(); questionCoordinator.clear(); turns.clear(); inAssistant = false; curTurn = null; resetBlock()
        updateRetentionNotice()
        interpreter.reset(); observedEvents = 0
        // A new conversation starts with no context of its own. The window is kept: it is a property
        // of the model, not of the conversation, and re-learning it costs a whole turn.
        contextTokens = null
        composer.setContextUsage(null)
        // Clearing means clearing: leaving the id behind would offer to resume a conversation the user
        // has just discarded.
        sessionMemory.forget()
        statusModel.reset(); transcriptPresenter.reset()
        transcript.revalidate(); transcript.repaint()
        showEmptyState(true)
        refreshStatus()
    }

    private fun applyConfigToUi() {
        SwingUtilities.invokeLater {
            updateModeChip()
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
        if (!following || pendingScroll) return // don't yank the user back while they read up-thread
        pendingScroll = true
        SwingUtilities.invokeLater {
            pendingScroll = false
            transcript.revalidate(); transcript.repaint()
            // `revalidate()` only *schedules* layout, so the scrollbar's maximum is still the
            // pre-growth value right now. Jumping to it lands short of the real bottom by the height
            // of whatever was just added; the adjustment listener then sees that gap and concludes the
            // user scrolled up, silently killing follow and popping "Jump to latest" mid-stream.
            // Defer one more EDT turn so the model has caught up.
            SwingUtilities.invokeLater { scrollToBottomNow() }
        }
    }

    /** Snaps to the live end without letting our own scroll be mistaken for the user scrolling. */
    private fun scrollToBottomNow() {
        val bar = scroll.verticalScrollBar
        programmaticScroll = true
        try {
            bar.value = bar.maximum - bar.visibleAmount
            lastScrollValue = bar.value
            lastScrollMaximum = bar.maximum
        } finally {
            // Cleared after the resulting adjustment events have been dispatched.
            SwingUtilities.invokeLater { programmaticScroll = false; updateJumpToLatest() }
        }
    }

    /** Back to the live end, which re-arms auto-follow via the adjustment listener. */
    private fun jumpToLatest() {
        val bar = scroll.verticalScrollBar
        bar.value = bar.maximum
        following = true
        updateJumpToLatest()
    }

    private fun updateJumpToLatest() {
        val bar = scroll.verticalScrollBar
        val show = ScrollFollow.shouldOfferJumpToLatest(following, bar.visibleAmount, bar.maximum)
        if (jumpToLatestButton.isVisible != show) {
            jumpToLatestButton.isVisible = show
            transcriptLayer.repaint()
        }
    }
    private fun relayout() { SwingUtilities.invokeLater { transcript.revalidate(); transcript.repaint() } }

    private fun basename(p: String): String { val q = p.replace('\\', '/').trimEnd('/'); val i = q.lastIndexOf('/'); return if (i >= 0) q.substring(i + 1) else q }
    /**
     * A file path as a transcript row should show it: project-relative where possible, and shortened at
     * segment boundaries. See [PathDisplay] — a plain character cut lands mid-segment and renders the
     * prefix as garbage, and an absolute in-project path spends the row on text every other row repeats.
     */
    private fun shortPath(p: String?): String =
        PathDisplay.display(p, project.basePath, System.getProperty("user.home"))
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

    private fun JsonObject.str(k: String): String? = if (has(k) && get(k).isJsonPrimitive) get(k).asString else null
    private fun JsonObject.intOrNull(k: String): Int? = if (has(k) && get(k).isJsonPrimitive) get(k).asInt else null
    private fun JsonObject.dblOrNull(k: String): Double? = if (has(k) && get(k).isJsonPrimitive) get(k).asDouble else null
    private fun JsonObject.objOrNull(k: String): JsonObject? = if (has(k) && get(k).isJsonObject) getAsJsonObject(k) else null
    private fun JsonObject.longOrNull(k: String): Long? = if (has(k) && get(k).isJsonPrimitive) runCatching { get(k).asLong }.getOrNull() else null
    private fun JsonObject.longOrZero(k: String): Long = longOrNull(k) ?: 0L
    private fun parseObj(s: String): JsonObject? = try { if (s.isBlank()) null else JsonParser.parseString(s).asJsonObject } catch (e: Exception) { null }

    // ---------- tool presentation ----------

    private fun toolAction(name: String): String = when (name) {
        "Read" -> "Read"; "Edit", "MultiEdit", "NotebookEdit" -> "Edited"; "Write" -> "Created"
        "Bash" -> "Ran"; "Grep", "Glob", "WebSearch" -> "Searched"; "WebFetch" -> "Fetched"
        "TodoWrite" -> "Planned"; "AskUserQuestion" -> "Asked"
        "Task" -> "Delegated"; "Skill" -> "Used skill"; "SlashCommand" -> "Ran command"
        "ExitPlanMode" -> "Proposed a plan"; "BashOutput" -> "Read output"; "KillShell" -> "Stopped shell"
        "NotebookEdit" -> "Edited notebook"
        else -> humanizeTool(name)
    }

    private fun toolIcon(name: String): Icon = when (name) {
        "Read" -> ClaudeIcons.read
        "Edit", "MultiEdit", "Write", "NotebookEdit" -> ClaudeIcons.edit
        "Bash" -> ClaudeIcons.command
        "Grep", "Glob", "WebSearch" -> ClaudeIcons.search
        "WebFetch" -> ClaudeIcons.web
        "TodoWrite", "AskUserQuestion", "ExitPlanMode" -> ClaudeIcons.diamond
        "Task", "Skill", "SlashCommand" -> ClaudeIcons.diamond
        else -> ClaudeIcons.diamond
    }

    private fun humanizeTool(name: String): String {
        val short = name.substringAfterLast("__")
        return short.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** The component keyboard focus should land on when the tool window activates. */
    fun preferredFocusComponent(): JComponent = composer.inputComponent()

    /**
     * Releases everything this panel installed on **project-level** services, not just the process.
     *
     * The coordinators, `SightlineUiState` and the test-bridge simulator seam all outlive the panel,
     * so a disposed panel that left them wired would keep a dead component reachable and leave stale
     * pending approvals/questions that a later panel — or the sandbox bridge — would still see.
     */
    override fun dispose() {
        mcpPollTimer.stop()
        mcpTimeoutTimer.stop()
        session.dispose()
        approvalCoordinator.clear()
        questionCoordinator.clear()
        if (uiState.rootComponent === component) {
            uiState.rootComponent = null
            uiState.toolWindowVisible = false
        }
        uiState.askQuestionSimulator = null
    }

    // ---------- block components ----------

    private abstract inner class Block : JPanel() {
        init { alignmentX = Component.LEFT_ALIGNMENT; isOpaque = false }
        override fun getMaximumSize(): Dimension = Dimension(Integer.MAX_VALUE, preferredSize.height)
    }

    private inner class AssistantTurn : Block() {
        private val body = JPanel()
        private var textCount = 0
        private val detailKids = ArrayList<Component>()
        private var summary = ProcessingSummary()
        private val summaryLabel = JBLabel("")
        private val summaryRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        private val copiedLabel = JBLabel("Copied")
        private val copyButton = IconActionButton(ClaudeIcons.copy.withSize(14), "Copy response (Markdown)") {
            val text = markdownText()
            if (text.isNotBlank()) {
                copyToClipboard(text)
                copiedLabel.isVisible = true
                javax.swing.Timer(1200) { copiedLabel.isVisible = false }.apply { isRepeats = false }.start()
            }
        }
        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(2, 2, 6, 2)
            val role = JBLabel("Claude")
            role.foreground = mutedFg(); role.font = role.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(11f).toFloat())
            // Copy lives on the role line, a row that already exists — so it can never move the reply
            // underneath it. It is a small always-visible icon rather than a hover reveal: "copy the
            // whole response" is the affordance users actually hunt for, and one they could not find
            // while it only existed on hover.
            val roleRow = JPanel(BorderLayout()); roleRow.isOpaque = false
            roleRow.border = JBUI.Borders.emptyBottom(3)
            roleRow.add(role, BorderLayout.WEST)
            copiedLabel.foreground = mutedFg()
            copiedLabel.font = copiedLabel.font.deriveFont(JBUI.scaleFontSize(10.5f).toFloat())
            copiedLabel.isVisible = false
            val copyGroup = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)); copyGroup.isOpaque = false
            copyGroup.add(copiedLabel); copyGroup.add(copyButton)
            roleRow.add(copyGroup, BorderLayout.EAST)
            add(roleRow, BorderLayout.NORTH)
            body.layout = BoxLayout(body, BoxLayout.Y_AXIS); body.isOpaque = false; body.alignmentX = Component.LEFT_ALIGNMENT
            add(body, BorderLayout.CENTER)

            // The collapsed stand-in for the hidden tool cards. Clicking it turns details on, so the
            // summary is a way *into* the detail rather than a dead end.
            summaryRow.isOpaque = false
            summaryRow.isVisible = false
            summaryRow.cursor = hand
            summaryLabel.foreground = mutedFg()
            summaryLabel.font = summaryLabel.font.deriveFont(JBUI.scaleFontSize(11f).toFloat())
            val chev = JBLabel(ClaudeIcons.chevronRight.withSize(11))
            summaryRow.add(chev); summaryRow.add(summaryLabel)
            summaryRow.addMouseListener(click { setShowDetails(true) })
            summaryRow.alignmentX = Component.LEFT_ALIGNMENT
            body.add(fullWidth(summaryRow))
            refreshVisibility()
        }
        fun addBlock(c: JComponent) {
            val strut = Box.createVerticalStrut(JBUI.scale(5))
            body.add(fullWidth(c)); body.add(strut)
            if (c is TextBlock || c is ApprovalBlock || c is AskUserQuestionBlock) textCount++
            if (c is ThinkingBlock || c is ToolCard) {
                c.isVisible = showDetails; strut.isVisible = showDetails
                detailKids.add(c); detailKids.add(strut)
            }
            refreshVisibility(); relayout()
        }
        /** Every text block in this turn, as the Markdown Claude actually sent. */
        private fun markdownText(): String = body.components
            .filterIsInstance<TextBlock>()
            .joinToString("\n\n") { it.rawMarkdown }
            .trim()

        fun applyDetails(show: Boolean) {
            detailKids.forEach { it.isVisible = show }
            refreshSummary()
            refreshVisibility()
        }

        /** Propagates the transcript column's width so hosted diffs can pick unified vs side-by-side. */
        fun applyDiffWidth(widthPx: Int) {
            for (c in body.components) if (c is ToolCard) c.applyDiffWidth(widthPx)
        }

        /**
         * Folds a finished tool call into this turn's tally. With details off every card is hidden, so
         * without this a turn that edited four files looks identical to one that answered from memory.
         */
        fun noteToolOutcome(toolName: String?, outcome: ToolOutcome, path: String?) {
            summary = summary.plus(toolName, outcome, path)
            refreshSummary()
        }

        /** Shows the one-line tally only when the detailed cards are hidden and something actually ran. */
        private fun refreshSummary() {
            val show = !showDetails && !summary.isEmpty
            summaryRow.isVisible = show
            if (show) summaryLabel.text = summary.text()
            relayout()
        }

        /**
         * A structured completion card: a colour-keyed terminal **state** (Completed / Completed with
         * warnings / Stopped), the run metadata, and any warnings the turn actually observed. Replaces
         * the old single grey line so the outcome is glanceable rather than buried at the end of prose —
         * while staying secondary (it never competes with the answer) and stating only observed facts,
         * never a fabricated "implemented X, Y" narrative. See [CompletionCard].
         */
        fun addCompletionFooter(costUsd: Double?, durationMs: Double?, numTurns: Int?, isError: Boolean, recoveredFailures: Int) {
            val view = CompletionCard.of(summary, costUsd, durationMs, numTurns, isError, recoveredFailures)
            val stateColor = when (view.state) {
                CompletionCard.State.COMPLETED -> ClaudeUiTokens.success()
                CompletionCard.State.COMPLETED_WITH_WARNINGS -> ClaudeUiTokens.warning()
                CompletionCard.State.STOPPED -> ClaudeUiTokens.error()
            }
            val card = JPanel(BorderLayout()); card.isOpaque = false
            card.border = JBUI.Borders.emptyTop(6); card.alignmentX = Component.LEFT_ALIGNMENT

            val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)); head.isOpaque = false
            head.add(JBLabel(completionDot(stateColor)))
            val headline = JBLabel(view.headline)
            headline.foreground = stateColor
            headline.font = headline.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(11.5f).toFloat())
            head.add(headline)
            card.add(head, BorderLayout.WEST)

            if (view.meta.isNotBlank()) {
                val meta = JBLabel(view.meta)
                meta.foreground = mutedFg()
                meta.font = meta.font.deriveFont(JBUI.scaleFontSize(10.5f).toFloat())
                card.add(meta, BorderLayout.EAST)
            }
            if (view.warnings.isNotEmpty()) {
                val warn = JBLabel(view.warnings.joinToString("  ·  "))
                warn.foreground = ClaudeUiTokens.warning()
                warn.font = warn.font.deriveFont(JBUI.scaleFontSize(10.5f).toFloat())
                warn.border = JBUI.Borders.emptyTop(2)
                card.add(warn, BorderLayout.SOUTH)
            }
            body.add(fullWidth(card))
            relayout()
        }

        /** A small filled state dot for the completion card. */
        private fun completionDot(color: Color): javax.swing.Icon = object : javax.swing.Icon {
            override fun getIconWidth() = JBUI.scale(8)
            override fun getIconHeight() = JBUI.scale(8)
            override fun paintIcon(c: Component?, g: java.awt.Graphics, x: Int, y: Int) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(x, y, JBUI.scale(8), JBUI.scale(8))
                g2.dispose()
            }
        }
        private fun refreshVisibility() {
            isVisible = showDetails || textCount > 0
            // A turn can be nothing but tool activity; a copy control there would yield "".
            copyButton.isVisible = body.components.any { it is TextBlock }
        }
    }

    /**
     * A streamed assistant text block, rendered as **Markdown while it streams**. Each delta re-parses
     * the accumulated text on a coalescing tick ([StreamingMarkdown.TICK_MS]) and rebuilds only the
     * blocks that actually changed — [StreamingMarkdown.stablePrefix] keeps every finished block's
     * component, so a tick costs one parse plus the growing tail block, and formatting appears as it
     * arrives instead of at `content_block_stop`. Live ticks skip file-reference linkification (it
     * queries the project index per candidate); the finalize pass runs the full pipeline, links
     * included, exactly as the pre-live renderer did.
     *
     * Failure never drops the response: a live parse/render failure falls back to plain streamed text
     * ([stream]) for the rest of the stream, and finalize retries the full render with the old
     * regex-renderer fallback behind it.
     */
    private inner class TextBlock : Block() {
        /** The original Markdown, which is what "Copy" should yield — not the rendered text. */
        val rawMarkdown: String get() = sb.toString()
        private val stream = styledPane()
        private val blocks = JPanel()
        private val sb = StringBuilder()
        private var showingBlocks = false
        /** The model [blocks] currently renders — the baseline the next tick diffs against. */
        private var renderedModel: List<MdBlock> = emptyList()
        /** Set when a live tick failed; streaming then stays plain text until finalize retries. */
        private var liveBroken = false
        private var dirty = false
        private val liveTimer = javax.swing.Timer(StreamingMarkdown.TICK_MS) { liveTick() }.apply { isRepeats = false }
        init {
            layout = BorderLayout()
            blocks.layout = BoxLayout(blocks, BoxLayout.Y_AXIS); blocks.isOpaque = false; blocks.alignmentX = Component.LEFT_ALIGNMENT
            add(stream, BorderLayout.CENTER)
        }
        fun append(t: String) {
            sb.append(t)
            if (liveBroken) { insStream(t); scrollToBottomSoon(); return }
            dirty = true
            // First delta renders immediately (the message shows formatted from its first tokens);
            // while the timer runs, further deltas coalesce into the next tick.
            if (!liveTimer.isRunning) liveTick()
            scrollToBottomSoon()
        }
        private fun liveTick() {
            if (!dirty) return
            dirty = false
            renderLive()
            liveTimer.initialDelay = StreamingMarkdown.tickMs(sb.length)
            liveTimer.restart()
        }
        /** Test seam: render any coalesced-but-unrendered deltas now (see [flushStreamingRenderForTest]). */
        fun flushLive() {
            if (liveBroken) return
            liveTimer.stop()
            if (dirty) { dirty = false; renderLive() }
        }
        private fun renderLive() {
            try {
                val model = MarkdownDocParser.parse(sb.toString())
                val stable = StreamingMarkdown.stablePrefix(renderedModel, model)
                while (blocks.componentCount > stable) blocks.remove(blocks.componentCount - 1)
                markdownRenderer.render(model.subList(stable, model.size)).forEach { blocks.add(fullWidth(it)) }
                renderedModel = model
                if (!showingBlocks) { remove(stream); add(blocks, BorderLayout.CENTER); showingBlocks = true }
                relayout()
            } catch (e: Exception) {
                // Fail once, stay plain for the rest of the stream — retrying a failing parse on
                // every tick would burn the EDT for nothing. Finalize retries the full pipeline.
                thisLogger().warn("Live Markdown render failed (${e.javaClass.simpleName}); streaming plain text")
                liveBroken = true
                showStream(); insStream(sb.toString())
                relayout()
            }
        }
        fun finalizeMarkdown() {
            liveTimer.stop(); dirty = false; liveBroken = false
            renderMarkdown(sb.toString())
        }
        fun setMarkdown(t: String) { sb.setLength(0); sb.append(t); renderMarkdown(t) }

        private fun renderMarkdown(raw: String) {
            try {
                val model = FileRefDetector.linkify(MarkdownDocParser.parse(raw)) { resolveProjectFile(it) != null }
                val comps = markdownRenderer.render(model)
                blocks.removeAll()
                comps.forEach { blocks.add(fullWidth(it)) }
                renderedModel = model
                if (!showingBlocks) { remove(stream); add(blocks, BorderLayout.CENTER); showingBlocks = true }
                relayout()
            } catch (e: Exception) {
                thisLogger().warn("Markdown render failed (${e.javaClass.simpleName}); showing plain text")
                if (showingBlocks) showStream()
                blocks.removeAll(); renderedModel = emptyList()
                clearStream(); target = stream.styledDocument; insertMarkdown(raw); target = null
                relayout()
            }
        }
        private fun showStream() {
            remove(blocks); add(stream, BorderLayout.CENTER); showingBlocks = false; clearStream()
            blocks.removeAll(); renderedModel = emptyList()
        }
        private fun clearStream() { try { stream.styledDocument.remove(0, stream.styledDocument.length) } catch (e: Exception) {} }
        private fun insStream(t: String) { try { stream.styledDocument.insertString(stream.styledDocument.length, t, sNormal) } catch (e: BadLocationException) {} }
    }

    /** Extended thinking, rendered subtly under "Processing details" (only when details are on). */
    private inner class ThinkingBlock : Block() {
        private val header = JBLabel("Processing details")
        private val area = plainArea("")
        private var open = false
        private val chevron = JBLabel(ClaudeIcons.chevronRight.withSize(12))
        private val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        init {
            layout = BorderLayout()
            head.isOpaque = false; head.cursor = hand
            // The block is created at `content_block_start`, before a single token has arrived — and some
            // thinking blocks never deliver visible text at all (a signature-only delta, say). Showing the
            // disclosure regardless left a control that expanded to nothing, which is worse than no
            // control: it reads as content that failed to load. The header appears with the first text.
            head.isVisible = false
            header.foreground = mutedFg(); header.font = header.font.deriveFont(Font.ITALIC)
            head.add(chevron); head.add(header)
            head.addMouseListener(click { toggle() })
            area.foreground = mutedFg(); area.font = area.font.deriveFont(Font.ITALIC); area.isVisible = false
            area.border = JBUI.Borders.empty(2, 16, 2, 0)
            add(head, BorderLayout.NORTH); add(area, BorderLayout.CENTER)
        }
        /**
         * Append via the document, not `area.text = area.text + t`: the latter rebuilds the whole
         * string on every delta, which is O(n²) over a long thinking block and visibly stutters.
         */
        fun append(t: String) {
            try { area.document.insertString(area.document.length, t, null) } catch (e: BadLocationException) { /* drop */ }
            if (!head.isVisible && area.document.length > 0 && t.isNotBlank()) { head.isVisible = true; relayout() }
            scrollToBottomSoon()
        }
        private fun toggle() { open = !open; area.isVisible = open; chevron.icon = (if (open) ClaudeIcons.chevronDown else ClaudeIcons.chevronRight).withSize(12); relayout() }
    }

    /**
     * One tool call. Renders either as a quiet **compact row** (routine, successful work) or as a
     * bordered **card** (failures, denials, and edits) — see [ToolEventPresentation], which decides
     * from structured metadata only.
     */
    private inner class ToolCard(name: String) : Block() {
        private val bodyPane = styledPane()
        val bodyDoc: StyledDocument get() = bodyPane.styledDocument
        private val actionLabel = JBLabel(name)
        private val targetLabel = JBLabel("")
        private val iconLabel = JBLabel(toolIcon(name).let { (it as? io.mp.sightline.theme.VectorIcon)?.withColor { ClaudeUiTokens.textSecondary() } ?: it })
        private val stateLabel = JBLabel("")
        private val chevron = JBLabel(ClaudeIcons.chevronRight.withSize(12))
        private val bodyWrap = JPanel(BorderLayout())
        private val head = JPanel(BorderLayout(JBUI.scale(6), 0))
        /** Host for hover actions inside the existing header row, so revealing them shifts nothing. */
        private val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        private var open = false

        private var toolName: String = name
        private var outcome: ToolOutcome = ToolOutcome.RUNNING
        private var weight: ToolWeight = ToolEventPresentation.weight(name, ToolOutcome.RUNNING)
        private val editBlocks = ArrayList<FileEditBlock>()
        private var installedActions = false
        /** Subagent fold-in state; see [appendSubagentActivity]. */
        private var subagentSteps = 0
        private var subagentHidden = 0
        private var subagentOverflowFrom = -1
        var linkPath: String? = null
        var linkLabel: String? = null

        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(1, 0)
            head.isOpaque = false; head.cursor = hand
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)); left.isOpaque = false
            actionLabel.font = actionLabel.font.deriveFont(Font.BOLD)
            actionLabel.foreground = ClaudeUiTokens.textPrimary()
            targetLabel.foreground = mutedFg()
            left.add(iconLabel); left.add(actionLabel); left.add(targetLabel)
            head.add(left, BorderLayout.WEST)
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)); right.isOpaque = false
            headerActions.isOpaque = false
            right.add(headerActions); right.add(stateLabel); right.add(chevron)
            head.add(right, BorderLayout.EAST)
            head.addMouseListener(click { toggle() })
            add(head, BorderLayout.NORTH)
            bodyWrap.isOpaque = false; bodyWrap.border = JBUI.Borders.empty(0, 12, 6, 8); bodyWrap.add(bodyPane, BorderLayout.CENTER)
            bodyWrap.isVisible = false
            add(bodyWrap, BorderLayout.CENTER)
            chevron.isVisible = false // nothing to disclose until a result arrives
            applyWeight()
        }

        /** Re-derives presentation from the current tool + outcome and restyles in place. */
        private fun applyWeight() {
            weight = ToolEventPresentation.weight(toolName, outcome)
            // A compact row is a line of text, not a container: tighter insets, no chrome.
            head.border = if (weight == ToolWeight.COMPACT) JBUI.Borders.empty(2, 4) else JBUI.Borders.empty(6, 8)
            bodyWrap.border = if (weight == ToolWeight.COMPACT) JBUI.Borders.empty(0, 10, 4, 4) else JBUI.Borders.empty(0, 12, 6, 8)
            chevron.isVisible = ToolEventPresentation.hasDisclosure(bodyDoc.length)
            revalidate(); repaint()
        }

        fun setDetails(name: String, input: JsonObject, summary: String) {
            toolName = name
            actionLabel.text = toolAction(name)
            targetLabel.text = summary
            // A command is the thing people most often want to re-run or paste into a report, so it
            // gets Copy command / Copy output on hover.
            linkPath = input.str("file_path")
            linkLabel = if (name == "Bash") oneLine(input.str("command") ?: "", 72) else summary.ifBlank { null }
            if (!installedActions) {
                installedActions = true
                val cmd = input.str("command") ?: ""
                val acts = ArrayList<Pair<String, () -> Unit>>(3)
                if (name == "Bash") {
                    acts.add("Copy command" to { copyToClipboard(cmd) })
                    acts.add("Copy output" to { copyToClipboard(bodyDoc.getText(0, bodyDoc.length)) })
                }
                // Into the header row that already exists, not a new row below: the header's height is
                // set by the labels either way, so revealing these can never move the transcript.
                headerActions.add(hoverActions(this, *acts.toTypedArray()))
            }
            applyWeight()
            relayout()
        }
        fun addResult(text: String, isErr: Boolean) {
            target = bodyDoc
            if (bodyDoc.length > 0) insert("\n", sMuted)
            insert(if (isErr) "! " else "» ", if (isErr) sError else sMuted)
            insert(truncate(text).ifBlank { if (isErr) "(error)" else "(no output)" } + "\n", sMuted)
            target = null
            stateLabel.icon = if (isErr) ClaudeIcons.errorCircle.withSize(13) else ClaudeIcons.check.withSize(13)
            outcome = if (isErr) ToolOutcome.ERROR else ToolOutcome.OK
            applyWeight()
            if (isErr) expand()
            relayout()
        }
        fun expand() { if (!open) toggle() }

        /**
         * Folds one step of a **subagent**'s forwarded output into this Task card — see
         * [SubagentPresentation] for what is kept and what is dropped, and why.
         *
         * The card is never auto-expanded by this: a subagent runs while the main agent is still
         * working, and opening a card under the reader's cursor moves the text they are reading. The
         * chevron appearing is signal enough that there is something inside.
         */
        fun appendSubagentActivity(tool: String, summary: String) {
            if (subagentSteps >= SubagentPresentation.MAX_ACTIVITY) {
                subagentHidden += 1
                noteSubagentOverflow()
                return
            }
            subagentSteps += 1
            target = bodyDoc
            insert("→ ", sMuted)
            insert(toolAction(tool), sMuted)
            if (summary.isNotBlank()) insert("  $summary", sMuted)
            insert("\n", sMuted)
            target = null
            refreshDisclosure()
        }

        /** The subagent's own words — normally its conclusion, which is the point of having run it. */
        fun appendSubagentText(text: String) {
            target = bodyDoc
            if (bodyDoc.length > 0) insert("\n", sMuted)
            insert(truncate(text) + "\n", sMuted)
            target = null
            refreshDisclosure()
        }

        /**
         * Replaces the overflow line in place rather than adding one per hidden step, so the count
         * stays accurate and the card stays one line longer than the cap, not fifty.
         */
        private fun noteSubagentOverflow() {
            val note = SubagentPresentation.overflowNote(subagentHidden) ?: return
            target = bodyDoc
            try {
                if (subagentOverflowFrom >= 0 && subagentOverflowFrom <= bodyDoc.length) {
                    bodyDoc.remove(subagentOverflowFrom, bodyDoc.length - subagentOverflowFrom)
                } else {
                    subagentOverflowFrom = bodyDoc.length
                }
                insert(note + "\n", sMuted)
            } catch (e: BadLocationException) {
                // Nothing to do but leave the last accurate note standing.
            }
            target = null
            refreshDisclosure()
        }

        private fun refreshDisclosure() {
            chevron.isVisible = ToolEventPresentation.hasDisclosure(bodyDoc.length)
            relayout()
        }

        /**
         * Attaches a real [FileEditBlock] for an Edit/MultiEdit/Write, instead of dumping diff lines
         * into the shared body document. An edit is the point of a turn, so it gets a header with
         * line counts, Open file / Copy diff actions, per-hunk numbering, and a bounded view.
         */
        fun addEdit(path: String?, hunks: List<List<Pair<String, String>>>, note: String?) {
            val block = FileEditBlock(path, hunks, note)
            editBlocks.add(block)
            bodyWrap.add(block, BorderLayout.NORTH)
            expand() // an edit is worth seeing without a click
            applyWeight()
            relayout()
        }

        /** Re-lays the hosted edit diffs when the available width changes (unified vs side-by-side). */
        fun applyDiffWidth(widthPx: Int) = editBlocks.forEach { it.applyWidth(widthPx) }

        /** Marks this tool as denied/cancelled: blocked icon + a muted "not run" note; never looks done. */
        fun markBlocked(reason: String) {
            stateLabel.icon = ClaudeIcons.blocked.withSize(13)
            targetLabel.foreground = mutedFg()
            target = bodyDoc
            if (bodyDoc.length > 0) insert("\n", sMuted)
            insert("⦸ $reason\n", sMuted)
            target = null
            outcome = ToolOutcome.BLOCKED
            applyWeight()
            relayout()
        }
        private fun toggle() { open = !open; bodyWrap.isVisible = open; chevron.icon = (if (open) ClaudeIcons.chevronDown else ClaudeIcons.chevronRight).withSize(12); relayout() }

        override fun paintComponent(g: Graphics) {
            // A compact row draws no chrome at all — that is the whole point of the tier.
            if (weight == ToolWeight.CARD) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = ClaudeUiTokens.radiusMd()
                g2.color = cardBg()
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = ClaudeUiTokens.subtleBorder()
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /**
     * A file edit, rendered as a first-class block: a header stating what changed
     * ("Added 33 lines · Removed 8 lines") with **Open file** and **Copy diff** actions, then the diff
     * itself — side-by-side when there is room, unified when there isn't (see [DiffPresentation]),
     * collapsed past [DiffPresentation.COLLAPSE_ABOVE] rows and hard-capped so a whole-file `Write`
     * can't bury the conversation. Any capped remainder is stated, never silently dropped.
     */
    private inner class FileEditBlock(
        private val path: String?,
        private val hunks: List<List<Pair<String, String>>>,
        note: String?,
    ) : JPanel(BorderLayout()) {

        private val rows: List<Pair<String, String>> = hunks.flatten()
        private val stat = DiffPresentation.stat(rows)
        private val body = JPanel(BorderLayout())
        private val toggle = ActionLink("") {}
        private var expanded = false
        private var layoutMode = DiffLayout.UNIFIED

        init {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0, 4, 0)

            val head = JPanel(BorderLayout(JBUI.scale(8), 0)); head.isOpaque = false
            head.border = JBUI.Borders.empty(0, 0, 4, 0)
            val statLabel = JBLabel(buildString {
                append(DiffPresentation.headerText(stat))
                if (note != null) append(" · ").append(note)
                if (hunks.size > 1) append(" · ").append(hunks.size).append(" hunks")
            })
            statLabel.foreground = mutedFg()
            statLabel.font = statLabel.font.deriveFont(JBUI.scaleFontSize(11f).toFloat())
            head.add(statLabel, BorderLayout.WEST)

            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)); actions.isOpaque = false
            if (path != null) actions.add(smallLink("Open file") { openFileRef(path) })
            actions.add(smallLink("Copy diff") { copyToClipboard(unifiedText()) })
            if (DiffPresentation.isCollapsible(rows.size)) {
                toggle.text = DiffPresentation.expandText(rows.size)
                toggle.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
                toggle.addActionListener { expanded = !expanded; rebuild(); relayout() }
                actions.add(toggle)
            }
            head.add(actions, BorderLayout.EAST)

            add(head, BorderLayout.NORTH)
            body.isOpaque = false
            add(body, BorderLayout.CENTER)
            rebuild()
        }

        fun applyWidth(widthPx: Int) {
            val mode = DiffPresentation.layout(widthPx, JBUI.scale(1000) / 1000f)
            if (mode != layoutMode) { layoutMode = mode; rebuild(); relayout() }
        }

        private fun visible(): List<Pair<String, String>> =
            rows.take(DiffPresentation.visibleRows(rows.size, expanded))

        private fun rebuild() {
            body.removeAll()
            body.add(
                if (layoutMode == DiffLayout.SIDE_BY_SIDE) sideBySide(visible()) else unified(visible()),
                BorderLayout.CENTER,
            )
            DiffPresentation.overflowText(DiffPresentation.overflow(rows.size, expanded))?.let { msg ->
                val more = JBLabel(msg)
                more.foreground = mutedFg()
                more.font = more.font.deriveFont(Font.ITALIC, JBUI.scaleFontSize(11f).toFloat())
                more.border = JBUI.Borders.empty(3, 2, 0, 0)
                body.add(more, BorderLayout.SOUTH)
            }
            if (DiffPresentation.isCollapsible(rows.size)) {
                toggle.text = if (expanded) "Collapse" else DiffPresentation.expandText(rows.size)
            }
            body.revalidate(); body.repaint()
        }

        private fun unified(rs: List<Pair<String, String>>): JComponent {
            val pane = diffPane()
            val doc = pane.styledDocument
            for ((kind, text) in rs) {
                val style = when (kind) { "add" -> sDiffAdd; "del" -> sDiffDel; else -> sCode }
                val sign = LineDiff.sign(kind)
                try { doc.insertString(doc.length, sign + text + "\n", style) } catch (e: BadLocationException) { /* drop */ }
            }
            return pane
        }

        /**
         * Two columns: removals on the left, additions on the right, context on both. Rows are paired
         * so a change lines up horizontally rather than the columns drifting apart.
         */
        private fun sideBySide(rs: List<Pair<String, String>>): JComponent {
            val left = diffPane(); val right = diffPane()
            fun put(pane: JTextPane, text: String, style: javax.swing.text.AttributeSet) {
                val d = pane.styledDocument
                try { d.insertString(d.length, text + "\n", style) } catch (e: BadLocationException) { /* drop */ }
            }
            var i = 0
            while (i < rs.size) {
                val (kind, text) = rs[i]
                when (kind) {
                    "del" -> {
                        // Pair this removal with the addition that follows it, when there is one.
                        val next = rs.getOrNull(i + 1)
                        if (next?.first == "add") {
                            put(left, text, sDiffDel); put(right, next.second, sDiffAdd); i += 2
                        } else {
                            put(left, text, sDiffDel); put(right, "", sCode); i++
                        }
                    }
                    "add" -> { put(left, "", sCode); put(right, text, sDiffAdd); i++ }
                    else -> { put(left, text, sCode); put(right, text, sCode); i++ }
                }
            }
            val grid = JPanel(GridLayout(1, 2, JBUI.scale(6), 0))
            grid.isOpaque = false
            grid.add(left); grid.add(right)
            return grid
        }

        private fun unifiedText(): String = buildString {
            path?.let { append("--- ").append(it).append('\n') }
            for ((kind, text) in rows) {
                append(LineDiff.sign(kind)).append(text).append('\n')
            }
        }
    }

    /** Always-visible approval prompt for a can_use_tool control request. */
    private inner class ApprovalBlock(
        title: String, toolName: String, input: JsonObject,
        canAllowAlways: Boolean, private val onDecision: (ApprovalDecision, String?) -> Unit,
    ) : Block() {
        // WrapLayout, not FlowLayout: four buttons no longer fit one row on a narrow docked panel, and
        // a FlowLayout reports a single row's height however many it lays out — which is how this
        // codebase previously lost a context chip off the bottom of the composer. An approval whose
        // Deny is clipped off-screen is the worst version of that bug.
        private val buttons = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)))
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
            target = pane.styledDocument; renderToolContent(toolName, input, null); target = null
            val mid = JPanel(BorderLayout()); mid.isOpaque = false; mid.border = JBUI.Borders.emptyBottom(6); mid.add(pane, BorderLayout.CENTER)
            add(mid, BorderLayout.CENTER)

            buttons.isOpaque = false
            val allow = JButton("Allow").named(A11yNames.APPROVAL_ALLOW)
            allow.addActionListener { onDecision(ApprovalDecision.ALLOW, null) }; buttons.add(allow)
            if (canAllowAlways) {
                val aa = JButton("Allow always").named(A11yNames.APPROVAL_ALLOW_ALWAYS)
                aa.addActionListener { onDecision(ApprovalDecision.ALLOW_ALWAYS, null) }; buttons.add(aa)
            }
            val deny = JButton("Deny").named(A11yNames.APPROVAL_DENY)
            deny.addActionListener { onDecision(ApprovalDecision.DENY, null) }; buttons.add(deny)
            // "Not that — do this instead", without ending the turn. The text becomes the tool result
            // the model reads, so a denial can redirect the work rather than only block it.
            val why = JButton("Deny with reason…").named(A11yNames.APPROVAL_DENY_REASON)
            why.addActionListener { promptForDenyReason() }
            buttons.add(why)
            decided.foreground = mutedFg()
            val south = JPanel(BorderLayout()); south.isOpaque = false
            south.add(buttons, BorderLayout.WEST); south.add(decided, BorderLayout.EAST)
            add(south, BorderLayout.SOUTH)
        }
        /**
         * Asks for the reason, then denies with it. Cancelling the dialog leaves the request pending —
         * it is not a denial, and treating a dismissed dialog as one would block a tool the user never
         * decided about.
         */
        private fun promptForDenyReason() {
            val reason = Messages.showInputDialog(
                project,
                "What should Claude do instead? This is sent back as the tool's result, so it can " +
                    "redirect the work rather than only block it.",
                "Deny With Reason",
                null,
            )?.trim()
            if (reason.isNullOrEmpty()) return
            onDecision(ApprovalDecision.DENY, reason)
        }

        /** Reflects a resolved decision (from a human click or the test bridge) in the card. */
        fun markResolved(decision: ApprovalDecision, reason: String? = null) {
            buttons.isVisible = false
            decided.text = when (decision) {
                ApprovalDecision.ALLOW -> "Allowed"
                ApprovalDecision.ALLOW_ALWAYS -> "Always allowed"
                // The reason is shown because it is now part of the conversation: the model was told
                // it, and a transcript that hid it would leave the next reply unexplained.
                ApprovalDecision.DENY -> if (reason.isNullOrBlank()) "Denied" else "Denied — told: $reason"
            }
            decided.toolTipText = decided.text
            relayout()
        }
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


    /**
     * A proposed plan, as a document you can read and change before it runs.
     *
     * Plan mode's value is the pause before the work; a yes/no on a wall of chat text throws that away.
     * The plan renders through the same Markdown pipeline as an assistant message — headings, lists,
     * code — and **Edit plan…** turns it into an editable document in place. Saving an edit sends the
     * rewritten plan back to Claude as its instruction.
     *
     * Rejecting with nothing actionable makes the model re-propose the same plan (observed: three times
     * in a row), so neither rejection path here is silent — one carries the new plan, the other carries
     * a reason.
     */
    private inner class PlanBlock(
        private val plan: PlanReview.Plan,
        private val onDecision: (PlanDecision) -> Unit,
    ) : Block() {
        private val buttons = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)))
        private val decided = JBLabel("")
        private val body = JPanel(BorderLayout())
        private val editor = JTextArea(plan.markdown)
        private var editing = false

        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(9, 11)

            val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
            head.isOpaque = false
            head.border = JBUI.Borders.emptyBottom(5)
            val titleLabel = JBLabel("Claude's plan")
            titleLabel.foreground = ClaudeUiTokens.accent()
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
            head.add(titleLabel)
            // Where the CLI saved it, so the plan outlives this panel and can be opened like any file.
            plan.filePath?.let { path ->
                val open = ActionLink("Open plan file") { openPathInEditor(path) }
                open.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
                head.add(open)
            }
            add(head, BorderLayout.NORTH)

            body.isOpaque = false
            showRendered()
            add(body, BorderLayout.CENTER)

            buttons.isOpaque = false
            val approve = JButton("Approve plan").named(A11yNames.PLAN_APPROVE)
            approve.addActionListener { resolve(PlanDecision.Approve, "Approved") }
            buttons.add(approve)

            val edit = JButton("Edit plan…").named(A11yNames.PLAN_EDIT)
            edit.addActionListener { if (editing) saveEdit() else startEditing(edit) }
            buttons.add(edit)

            val keep = JButton("Keep planning").named(A11yNames.PLAN_KEEP)
            keep.addActionListener { keepPlanning() }
            buttons.add(keep)

            decided.foreground = mutedFg()
            val south = JPanel(BorderLayout()); south.isOpaque = false
            south.add(buttons, BorderLayout.WEST); south.add(decided, BorderLayout.EAST)
            add(south, BorderLayout.SOUTH)
        }

        private fun showRendered() {
            body.removeAll()
            val rendered = runCatching {
                val model = MarkdownDocParser.parse(plan.markdown)
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    markdownRenderer.render(model).forEach { add(fullWidth(it)) }
                }
            }.getOrElse {
                // A plan that will not parse is still a plan: show it as text rather than nothing.
                plainArea(plan.markdown)
            }
            body.add(rendered, BorderLayout.CENTER)
            relayout()
        }

        private fun startEditing(trigger: JButton) {
            editing = true
            trigger.text = "Save plan"
            editor.lineWrap = true
            editor.wrapStyleWord = true
            editor.font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
            editor.border = JBUI.Borders.empty(6)
            editor.rows = editor.text.lines().size.coerceIn(6, 24)
            body.removeAll()
            body.add(JBScrollPane(editor), BorderLayout.CENTER)
            relayout()
            editor.requestFocusInWindow()
        }

        private fun saveEdit() {
            val edited = editor.text
            if (!PlanReview.isChanged(plan.markdown, edited)) {
                // Opening the editor and changing nothing is an approval, not a rejection — sending an
                // identical plan back as a correction would read to the model as disagreement it can't act on.
                resolve(PlanDecision.Approve, "Approved")
                return
            }
            resolve(PlanDecision.Revise(edited), "Sent your revised plan")
        }

        private fun keepPlanning() {
            val reason = Messages.showInputDialog(
                project,
                "What should Claude reconsider? Left blank, it is simply asked to revise the plan.",
                "Keep Planning",
                null,
            )
            resolve(PlanDecision.KeepPlanning(reason), "Asked for more planning")
        }

        private fun resolve(decision: PlanDecision, label: String) {
            buttons.isVisible = false
            decided.text = label
            if (decision is PlanDecision.Revise) {
                // The transcript keeps what was actually sent — the conversation continues from the
                // edited plan, so hiding it would leave the next step unexplained.
                body.removeAll()
                body.add(plainArea(decision.plan), BorderLayout.CENTER)
            }
            relayout()
            onDecision(decision)
        }

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

    /**
     * A failed turn, as something the user can act on rather than a red line of the CLI's own text.
     *
     * The content — headline, whether there is anything evidenced to explain, and which actions are
     * worth offering — is decided by the platform-free [SessionFailure]; this is only the Swing half.
     * Two things it is careful about:
     * - **The detail is shown as the CLI wrote it**, and when it is too long to show whole, the card
     *   *says* how much it clipped and that "Copy details" carries all of it. A silently shortened
     *   error is how a user ends up debugging half a stack trace.
     * - **Every action reports back into the card** ([note]), because three of them (copy, copy the
     *   sign-in command, check the sign-in) produce nothing visible anywhere else, and a button that
     *   looks inert is indistinguishable from one that is.
     */
    private inner class FailureBlock(
        private val advice: SessionFailure.Advice,
        private val onAction: (SessionFailure.Action, FailureBlock) -> Unit,
    ) : Block() {
        private val note = JBLabel("")
        private val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))

        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(9, 11)

            val head = JPanel(BorderLayout(JBUI.scale(6), 0)); head.isOpaque = false
            head.border = JBUI.Borders.emptyBottom(5)
            val icon = JBLabel(ClaudeIcons.errorCircle.withSize(14))
            icon.verticalAlignment = javax.swing.SwingConstants.TOP
            head.add(icon, BorderLayout.WEST)
            val headline = plainArea(advice.headline)
            headline.foreground = ClaudeUiTokens.error()
            headline.font = headline.font.deriveFont(Font.BOLD)
            head.add(headline, BorderLayout.CENTER)
            add(head, BorderLayout.NORTH)

            val body = JPanel(); body.isOpaque = false; body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
            advice.explanation?.let {
                val area = plainArea(it)
                area.foreground = ClaudeUiTokens.textPrimary()
                area.alignmentX = Component.LEFT_ALIGNMENT
                body.add(area)
                body.add(Box.createVerticalStrut(JBUI.scale(6)))
            }
            if (advice.detail.isNotEmpty()) {
                val lines = advice.detail.lines()
                val shown = lines.take(DETAIL_LINES)
                val area = plainArea(shown.joinToString("\n"))
                area.foreground = mutedFg()
                area.font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
                    .deriveFont(UIUtil.getLabelFont().size2D - JBUI.scale(1))
                area.alignmentX = Component.LEFT_ALIGNMENT
                body.add(area)
                if (lines.size > shown.size) {
                    val more = plainArea(clippedNote(lines.size - shown.size))
                    more.foreground = mutedFg()
                    more.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC)
                    more.alignmentX = Component.LEFT_ALIGNMENT
                    body.add(more)
                }
                body.add(Box.createVerticalStrut(JBUI.scale(6)))
            }
            add(body, BorderLayout.CENTER)

            buttons.isOpaque = false
            for (action in advice.actions) {
                val b = JButton(action.label).named(A11yNames.failureAction(action.name))
                b.addActionListener { onAction(action, this) }
                buttons.add(b)
            }
            note.foreground = mutedFg()
            val south = JPanel(BorderLayout()); south.isOpaque = false
            south.add(buttons, BorderLayout.WEST); south.add(note, BorderLayout.EAST)
            add(south, BorderLayout.SOUTH)
        }

        /** Says what an action did, in the card that offered it. */
        fun note(text: String) {
            note.text = text
            relayout()
        }

        /**
         * A retry has gone out, so the buttons go: the turn it belonged to is over, and a second press
         * would send the same message a second time into a conversation that is now live again.
         */
        fun markRetried() {
            buttons.isVisible = false
            note("Sent again.")
        }

        private fun clippedNote(hidden: Int): String =
            if (hidden == 1) "1 more line — \"Copy details\" copies all of it."
            else "$hidden more lines — \"Copy details\" copies all of them."

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = ClaudeUiTokens.radiusMd()
            g2.color = ClaudeUiTokens.overlaySurface()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = ClaudeUiTokens.error()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    /**
     * Structured `AskUserQuestion` prompt: radio (single-select) / checkbox (multi-select) options plus
     * an optional free-text "Other", with Continue gated until every question is answered. This is user
     * **input**, not a permission decision — Cancel denies the request to unblock the turn, while a valid
     * option like "Skip" is a normal answer. Parsing, validity and response-building live in the
     * platform-free `interaction` package; this inner class is the thin Swing view over [QuestionFormState].
     */
    private inner class AskUserQuestionBlock(
        private val request: UserQuestionRequest?,
        private val invalidReason: String?,
        private val onSubmit: (Map<String, List<String>>) -> Unit,
        private val onCancel: () -> Unit,
    ) : Block() {
        private val form = request?.let { QuestionFormState(it) }
        private val centerPanel = JPanel()
        private val continueBtn = JButton("Continue").named(A11yNames.QUESTION_CONTINUE)
        private val cancelBtn = JButton("Cancel").named(A11yNames.QUESTION_CANCEL)
        private val decided = JBLabel("")
        private val otherWraps = HashMap<Int, JComponent>()
        private var resolved = false

        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(9, 11)

            val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)); head.isOpaque = false; head.border = JBUI.Borders.emptyBottom(6)
            val titleLabel = JBLabel(if (request != null) "Claude needs your input" else "Claude requested input")
            titleLabel.foreground = ClaudeUiTokens.accent(); titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
            head.add(titleLabel)
            add(head, BorderLayout.NORTH)

            centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
            centerPanel.isOpaque = false; centerPanel.alignmentX = Component.LEFT_ALIGNMENT
            if (request != null) buildQuestions() else buildUnavailable()
            add(centerPanel, BorderLayout.CENTER)

            val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)); buttons.isOpaque = false
            if (request != null) {
                continueBtn.isEnabled = false
                continueBtn.font = continueBtn.font.deriveFont(Font.BOLD)
                continueBtn.addActionListener { submit() }
                buttons.add(continueBtn)
            }
            cancelBtn.addActionListener { cancel() }
            buttons.add(cancelBtn)
            decided.foreground = mutedFg()
            val south = JPanel(BorderLayout()); south.isOpaque = false
            south.border = JBUI.Borders.emptyTop(4)
            south.add(buttons, BorderLayout.WEST); south.add(decided, BorderLayout.EAST)
            add(south, BorderLayout.SOUTH)
        }

        private fun buildQuestions() {
            request!!.questions.forEachIndexed { qi, q ->
                val qPanel = column(); qPanel.border = JBUI.Borders.emptyBottom(8)
                q.header?.takeIf { it.isNotBlank() }?.let { qPanel.add(categoryLabel(it)) }
                qPanel.add(leftLabel(plainArea(q.question)))
                if (q.multiSelect) qPanel.add(leftLabel(italicMuted("Select all that apply")))
                qPanel.add(Box.createVerticalStrut(JBUI.scale(5)))
                val group = if (q.multiSelect) null else ButtonGroup()
                q.options.forEachIndexed { oi, opt ->
                    qPanel.add(optionRow(qi, oi, opt, q.multiSelect, group))
                    qPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
                }
                qPanel.add(otherRow(qi, q.multiSelect, group))
                centerPanel.add(qPanel)
            }
        }

        private fun buildUnavailable() {
            centerPanel.add(leftLabel(plainArea("Claude requested input, but the question could not be displayed.")))
            if (showDetails && !invalidReason.isNullOrBlank()) centerPanel.add(leftLabel(italicMuted(invalidReason)))
        }

        /**
         * One option as a **selectable card**: the whole rounded row is a click target with a hover
         * fill and an accent border while selected, so choosing an option feels like picking from a
         * list rather than hunting a 12px radio circle. The toggle button stays for accessibility
         * (name, state, keyboard); the card is presentation around it.
         */
        private fun optionRow(qi: Int, oi: Int, opt: UserQuestionOption, multi: Boolean, group: ButtonGroup?): JComponent {
            val f = form!!
            val button: JToggleButton = if (multi) JCheckBox(opt.label) else JRadioButton(opt.label)
            button.isOpaque = false; button.font = button.font.deriveFont(Font.BOLD)
            button.named(A11yNames.questionOption(qi, oi))
            opt.description?.let { button.accessibleContext?.accessibleDescription = it }
            group?.add(button)
            button.addActionListener {
                if (multi) f.select(qi, opt.label) else { f.select(qi, opt.label); syncOther(qi) }
                updateContinue()
            }
            val card = OptionCard { button.isSelected }
            // Group-driven deselection (another radio chosen) repaints this card too — an action
            // listener alone would only repaint the one that was clicked.
            button.addItemListener { card.repaint() }
            card.installHoverOn(button)
            val col = column(); col.add(leftLabel(button))
            opt.description?.takeIf { it.isNotBlank() }?.let {
                val d = plainArea(it); d.foreground = mutedFg(); d.font = d.font.deriveFont(JBUI.scaleFontSize(11.5f).toFloat())
                d.border = JBUI.Borders.emptyLeft(22); d.cursor = hand; d.addMouseListener(click { button.doClick() })
                card.installHoverOn(d)
                col.add(leftLabel(d))
            }
            opt.preview?.takeIf { it.isNotBlank() }?.let {
                val pv = plainArea(it); pv.foreground = mutedFg()
                pv.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(11.5f))
                pv.border = JBUI.Borders.empty(2, 22, 2, 0)
                pv.cursor = hand; pv.addMouseListener(click { button.doClick() })
                card.installHoverOn(pv)
                col.add(leftLabel(pv))
            }
            card.add(col, BorderLayout.CENTER)
            card.addMouseListener(click { button.doClick() })
            card.installHoverOn(card)
            return leftLabel(card)
        }

        /** The rounded, hover/selection-aware surface behind one option. */
        private inner class OptionCard(private val selected: () -> Boolean) : JPanel(BorderLayout()) {
            private var hovered = false
            init {
                isOpaque = false
                cursor = hand
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(6, 8)
            }
            /** Children with their own listeners steal enter/exit from the card; mirror hover from them. */
            fun installHoverOn(c: Component) {
                c.addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { if (!hovered) { hovered = true; repaint() } }
                    override fun mouseExited(e: MouseEvent) {
                        // Leaving a child for elsewhere *inside* the card is not leaving the card.
                        val pt = SwingUtilities.convertPoint(e.component, e.point, this@OptionCard)
                        if (!contains(pt)) { hovered = false; repaint() }
                    }
                })
            }
            override fun getMaximumSize(): Dimension = Dimension(Integer.MAX_VALUE, preferredSize.height)
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = ClaudeUiTokens.radiusMd()
                val sel = selected()
                val fill = when {
                    sel -> ClaudeUiTokens.withAlpha(ClaudeUiTokens.accent(), 0.10f)
                    hovered -> ClaudeUiTokens.subtleSurface()
                    else -> null
                }
                fill?.let { g2.color = it; g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc) }
                g2.color = if (sel) ClaudeUiTokens.accent() else ClaudeUiTokens.subtleBorder()
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
                super.paintComponent(g)
            }
        }

        private fun otherRow(qi: Int, multi: Boolean, group: ButtonGroup?): JComponent {
            val f = form!!
            val button: JToggleButton = if (multi) JCheckBox("Other…") else JRadioButton("Other…")
            button.isOpaque = false; button.named(A11yNames.questionOther(qi))
            group?.add(button)
            val field = JTextArea(2, 24); field.lineWrap = true; field.wrapStyleWord = true; field.border = JBUI.Borders.empty(3)
            val wrap = JPanel(BorderLayout()); wrap.isOpaque = false; wrap.border = JBUI.Borders.empty(2, 22, 4, 0)
            wrap.add(field, BorderLayout.CENTER); wrap.isVisible = false
            otherWraps[qi] = wrap
            button.addActionListener {
                f.setOther(qi, button.isSelected); syncOther(qi)
                if (button.isSelected) field.requestFocusInWindow()
                updateContinue()
            }
            field.document.addDocumentListener(object : DocumentListener {
                private fun changed() { f.setOtherText(qi, field.text); updateContinue() }
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) = changed()
            })
            val col = column(); col.add(leftLabel(button)); col.add(leftLabel(wrap))
            return leftLabel(col)
        }

        private fun syncOther(qi: Int) {
            val show = form?.isOther(qi) == true
            otherWraps[qi]?.let { if (it.isVisible != show) { it.isVisible = show; relayout() } }
        }

        private fun updateContinue() { continueBtn.isEnabled = !resolved && form?.isComplete() == true }

        private fun submit() {
            val f = form ?: return
            if (resolved || !f.isComplete()) return
            onSubmit(f.resolvedAnswers())
        }

        private fun cancel() { if (!resolved) onCancel() }

        /** Reflects a resolved answer/cancel (human click or test bridge): swaps controls for a summary. */
        fun markResolved(resolution: QuestionResolution) {
            resolved = true
            centerPanel.removeAll()
            continueBtn.isVisible = false; cancelBtn.isVisible = false
            when (resolution) {
                is QuestionResolution.Answered -> {
                    val n = request?.questions?.size ?: 0
                    decided.text = if (n > 1) "Answered $n questions" else "Answered"
                    answeredSummary()?.let { centerPanel.add(it) }
                }
                QuestionResolution.Cancelled -> decided.text = "Question cancelled"
            }
            centerPanel.revalidate(); relayout()
        }

        private fun answeredSummary(): JComponent? {
            val f = form ?: return null
            val answers = f.resolvedAnswers()
            if (answers.isEmpty()) return null
            val panel = column()
            request!!.questions.forEach { q ->
                val chosen = answers[q.question] ?: return@forEach
                panel.add(categoryLabel(q.header?.takeIf { it.isNotBlank() } ?: q.question))
                val v = plainArea(chosen.joinToString(", ")); v.border = JBUI.Borders.emptyBottom(4)
                panel.add(leftLabel(v))
            }
            return leftLabel(panel)
        }

        private fun column(): JPanel {
            val p = JPanel(); p.layout = BoxLayout(p, BoxLayout.Y_AXIS); p.isOpaque = false; p.alignmentX = Component.LEFT_ALIGNMENT
            return p
        }
        private fun <T : JComponent> leftLabel(c: T): T { c.alignmentX = Component.LEFT_ALIGNMENT; return c }
        private fun categoryLabel(text: String): JBLabel {
            val l = JBLabel(text.uppercase()); l.foreground = mutedFg()
            l.font = l.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(10.5f).toFloat()); l.alignmentX = Component.LEFT_ALIGNMENT
            return l
        }
        private fun italicMuted(text: String): JBLabel {
            val l = JBLabel(text); l.foreground = mutedFg(); l.font = l.font.deriveFont(Font.ITALIC, JBUI.scaleFontSize(11f).toFloat())
            return l
        }

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

        /**
         * Offers "Revert Claude's file changes to here" on right-click, once the CLI has replayed this
         * message and told us its checkpoint id. A context menu rather than a visible button on purpose:
         * every user turn would carry one, and a destructive action repeated down the whole transcript
         * invites the misclick it is least able to undo.
         */
        fun armRevert(messageText: String, checkpointId: String) {
            componentPopupMenu = JPopupMenu().apply {
                add(JMenuItem(CheckpointPolicy.ACTION_LABEL).apply {
                    toolTipText = CheckpointPolicy.LIMITS
                    addActionListener { requestRewind(messageText, checkpointId) }
                })
            }
        }

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
