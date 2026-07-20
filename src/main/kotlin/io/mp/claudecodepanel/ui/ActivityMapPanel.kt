package io.mp.claudecodepanel.ui

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.activity.ActivityColorRole
import io.mp.claudecodepanel.activity.ActivityColorRoles
import io.mp.claudecodepanel.activity.ActivityGraph
import io.mp.claudecodepanel.activity.ClusterCollapser
import io.mp.claudecodepanel.activity.LabelPlacement
import io.mp.claudecodepanel.activity.MapDensity
import io.mp.claudecodepanel.ide.ProjectStructureEnricher
import io.mp.claudecodepanel.activity.ActivityNode
import io.mp.claudecodepanel.activity.ActivityNodeState
import io.mp.claudecodepanel.activity.ActivityNodeType
import io.mp.claudecodepanel.activity.EvidenceSource
import io.mp.claudecodepanel.activity.AgentActivityEvent
import io.mp.claudecodepanel.activity.TimelineEntry
import io.mp.claudecodepanel.settings.ClaudeSettings
import io.mp.claudecodepanel.theme.ClaudeIcons
import io.mp.claudecodepanel.theme.ClaudeUiTokens
import io.mp.claudecodepanel.ui.components.IconActionButton
import io.mp.claudecodepanel.ui.state.LayoutProfile
import io.mp.claudecodepanel.ui.state.ResponsiveLayout
import io.mp.claudecodepanel.ui.state.TimelineDockState
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.Timer
import javax.swing.ToolTipManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The **Agent Activity Map**: a force-directed Swing visualisation of what the agent is observably
 * touching. Chrome is progressively disclosed — a compact header (Activity + live state + count +
 * filter/fit/overflow), a translucent focus overlay inside the canvas, a right-side inspector drawer
 * that opens only on selection, and a collapsible activity-log dock. Observable activity only; the
 * disclaimer lives in the Activity tooltip and the About popup rather than in permanent chrome.
 */
class ActivityMapPanel(private val project: Project, parent: Disposable) : Disposable {

    val component: JComponent
    private val graph = ActivityGraph(maxRetained = retainedCap())
    // On-demand "find usages" for a selected file node (the lazy PSI tier); results feed back into the graph.
    private val usageEnricher by lazy { ProjectStructureEnricher(project, this) }

    private val canvas = GraphCanvas()
    private val inspector = InspectorPanel()
    private val inspectorScroll = JBScrollPane(inspector).apply { border = JBUI.Borders.empty() }
    private val contentSplit = JBSplitter(false, 0.70f)
    private val bodyHost = JPanel(BorderLayout())

    private val liveDot = JBLabel()
    private val countLabel = JBLabel(" ")
    private val filterCombo = javax.swing.JComboBox(Filter.values())
    private lateinit var fitButton: IconActionButton
    private lateinit var pauseButton: IconActionButton
    private lateinit var overflowButton: IconActionButton

    private val timelineModel = DefaultListModel<TimelineEntry>()
    private val timelineList = JBList(timelineModel)
    private val dockState = TimelineDockState(expanded = ClaudeSettings.getInstance().state.activityTimelineExpanded)
    private val dockToggle = JBLabel()
    private val dockBody = JPanel(BorderLayout())

    private var selectedId: String? = null
    private var paused = false
    private var reduceMotion = ClaudeSettings.getInstance().state.activityReduceMotion
    // Session-only view toggle: fold finished command/test/gradle history into its clusters to declutter.
    private var collapseHistory = false
    // Aggregate ids the user expanded in place (their folded members are shown again). Cleared with the toggle.
    private val expandedAggregates = LinkedHashSet<String>()
    private var profile = LayoutProfile.MEDIUM

    private val timer = Timer(33) { step() }
    private val dashedStroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, floatArrayOf(4f, 4f), 0f)

    private enum class Filter(val label: String, val pred: (ActivityNode) -> Boolean) {
        ALL("All activity", { true }),
        FILES("Files", { it.type == ActivityNodeType.FILE || it.type == ActivityNodeType.COMPOSABLE || it.type == ActivityNodeType.VIEW_MODEL || it.type == ActivityNodeType.REPOSITORY || it.type == ActivityNodeType.USE_CASE || it.type == ActivityNodeType.API_ENDPOINT || it.type == ActivityNodeType.CLASS }),
        EDITS("Edits", { it.type == ActivityNodeType.PATCH || it.state.name in setOf("EDITING", "CREATED", "DELETED") }),
        TESTS("Tests", { it.type == ActivityNodeType.TEST }),
        BUILDS("Builds", { it.type == ActivityNodeType.GRADLE_TASK || it.type == ActivityNodeType.COMMAND }),
        ERRORS("Errors", { it.type == ActivityNodeType.ERROR || it.type == ActivityNodeType.WARNING }),
        SYMBOLS("Symbols", { it.type == ActivityNodeType.SYMBOL }),
        ;
        override fun toString() = label
    }

    init {
        Disposer.register(parent, this)
        component = build()
        canvas.addHierarchyListener { e ->
            if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) {
                if (canvas.isShowing) kick() else timer.stop()
            }
        }
        updateLayout()
        refreshHeader()
    }

    // ---------- public API ----------

    fun apply(events: List<AgentActivityEvent>) {
        if (events.isEmpty()) return
        for (e in events) graph.apply(e)
        rebuildTimeline()
        ensurePositions()
        refreshHeader()
        inspector.refresh()
        if (reduceMotion) settleSync(60) else kick()
        canvas.repaint()
    }

    /** Number of timeline events observed this session — read by the health panel as context. */
    fun observedEventCount(): Int = graph.timeline.size

    fun clearSession() {
        graph.clear()
        timelineModel.clear()
        selectedId = null
        canvas.positions.clear(); canvas.velocities.clear()
        refreshHeader(); inspector.refresh(); updateLayout()
        canvas.repaint()
    }

    /** Adapt controls/inspector placement to the panel width. */
    fun applyProfile(p: LayoutProfile) {
        if (p == profile) return
        profile = p
        updateLayout()
    }

    override fun dispose() { timer.stop() }

    // ---------- build ----------

    private fun build(): JComponent {
        val root = JPanel(BorderLayout())
        root.border = BorderFactory.createMatteBorder(1, 0, 0, 0, ClaudeUiTokens.border())
        root.add(buildHeader(), BorderLayout.NORTH)

        contentSplit.firstComponent = canvas
        contentSplit.setHonorComponentsMinimumSize(false)
        bodyHost.add(contentSplit, BorderLayout.CENTER)
        root.add(bodyHost, BorderLayout.CENTER)

        root.add(buildDock(), BorderLayout.SOUTH)
        root.preferredSize = Dimension(JBUI.scale(460), JBUI.scale(320))
        return root
    }

    private fun buildHeader(): JComponent {
        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.border = JBUI.Borders.empty(4, 10, 4, 6)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)); left.isOpaque = false
        val title = JBLabel("Activity")
        title.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        title.foreground = ClaudeUiTokens.textPrimary()
        title.toolTipText = DISCLAIMER
        left.add(title)
        liveDot.toolTipText = "Live activity state"
        left.add(liveDot)
        countLabel.foreground = ClaudeUiTokens.textSecondary()
        countLabel.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        // The counter doubles as the "Show more" control once the cap is truncating the graph.
        countLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showMoreNodes()
        })
        left.add(countLabel)
        header.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(3), 0)); right.isOpaque = false
        filterCombo.toolTipText = "Filter which nodes are shown"
        filterCombo.addActionListener { ensurePositions(); refreshHeader(); canvas.repaint() }
        right.add(filterCombo)
        fitButton = IconActionButton(ClaudeIcons.fit, "Fit graph to view") { fit() }
        right.add(fitButton)
        pauseButton = IconActionButton(ClaudeIcons.pause, "Pause layout animation") { togglePause() }
        right.add(pauseButton)
        overflowButton = IconActionButton(ClaudeIcons.more, "More activity options") { showOverflow(overflowButton) }
        right.add(overflowButton)
        header.add(right, BorderLayout.EAST)
        return header
    }

    private fun togglePause() {
        paused = !paused
        pauseButton.update(if (paused) ClaudeIcons.resume else ClaudeIcons.pause, if (paused) "Resume layout animation" else "Pause layout animation")
        pauseButton.toggledOn = paused
        if (!paused) kick() else timer.stop()
    }

    private fun showOverflow(anchor: Component) {
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup()
        group.add(toggle("Reduce motion", reduceMotion) { setReduceMotion(!reduceMotion) })
        group.add(toggle("Collapse finished history", collapseHistory) { setCollapseHistory(!collapseHistory) })
        group.add(action("Legend…") { showLegend(anchor) })
        group.add(action("About the Activity Map…") { showAbout(anchor) })
        group.add(com.intellij.openapi.actionSystem.Separator.getInstance())
        group.add(action("Clear activity") { clearSession() })
        group.add(action("Activity map preferences…") { openSettings() })
        JBPopupFactory.getInstance()
            .createActionGroupPopup("Activity", group, com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showUnderneathOf(anchor)
    }

    private fun action(text: String, run: () -> Unit) = object : com.intellij.openapi.actionSystem.AnAction(text) {
        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) { run() }
    }
    private fun toggle(text: String, state: Boolean, run: () -> Unit) =
        action((if (state) "✓ " else "   ") + text, run)

    private fun openSettings() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, io.mp.claudecodepanel.settings.ClaudeSettingsConfigurable::class.java)
    }

    private fun showAbout(anchor: Component) {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10, 12)
        panel.background = UIUtil.getListBackground()
        val text = JBLabel("<html><div style='width:280px;'>The Activity Map shows only <b>observable</b> agent " +
            "activity — files, searches, edits, commands, tests and errors it actually touches. " +
            "It is <b>not</b> a view of the model's private reasoning.</div></html>")
        panel.add(text, BorderLayout.CENTER)
        ClaudeSettings.getInstance().state.activityAboutDismissed = true
        JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
            .setRequestFocus(true).setTitle("About the Activity Map").createPopup().showUnderneathOf(anchor)
    }

    fun setReduceMotion(value: Boolean) {
        reduceMotion = value
        ClaudeSettings.getInstance().state.activityReduceMotion = value
        if (reduceMotion) { timer.stop(); settleSync() } else kick()
        canvas.repaint()
    }

    private fun setCollapseHistory(value: Boolean) {
        collapseHistory = value
        if (!value) expandedAggregates.clear()
        ensurePositions(); refreshHeader(); canvas.repaint()
    }

    private fun collapseKeepIds(): Set<String> {
        val keep = LinkedHashSet<String>()
        keep.add(ActivityGraph.TASK_ID)
        graph.focus.nodeId?.let { keep.add(it) }
        selectedId?.let { keep.add(it) }
        return keep
    }

    /** The active fold plan (honours [expandedAggregates]); drives which member nodes are hidden. */
    private fun collapsePlan(): ClusterCollapser.Plan =
        if (!collapseHistory) ClusterCollapser.Plan.EMPTY
        else ClusterCollapser.plan(graph.nodes.filter { !it.hidden }, keepIds = collapseKeepIds(), expanded = expandedAggregates)

    /** Every foldable cluster (ignoring current expansion) — one chip is drawn per entry. */
    private fun allAggregates(): List<ClusterCollapser.Aggregate> =
        if (!collapseHistory) emptyList()
        else ClusterCollapser.plan(graph.nodes.filter { !it.hidden }, keepIds = collapseKeepIds(), expanded = emptySet()).aggregates

    /** Expand a folded cluster in place, or re-collapse an expanded one. */
    private fun toggleAggregate(id: String) {
        if (!expandedAggregates.add(id)) expandedAggregates.remove(id)
        ensurePositions(); refreshHeader(); canvas.repaint()
    }

    private fun showLegend(anchor: Component) {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8, 10)
        panel.background = UIUtil.getListBackground()
        val entries = listOf(
            ActivityColorRole.TASK to "Current task",
            ActivityColorRole.DISCOVERED to "Discovered · searched",
            ActivityColorRole.READING to "Read · inspected · testing",
            ActivityColorRole.FOCUS to "Current focus",
            ActivityColorRole.EDITING to "Editing · proposed change",
            ActivityColorRole.SUCCESS to "Created · passed · completed",
            ActivityColorRole.WARNING to "Warning",
            ActivityColorRole.ERROR to "Error · failed",
            ActivityColorRole.SUGGESTION to "Generated patch",
            ActivityColorRole.BLOCKED to "Denied · cancelled",
            ActivityColorRole.IDLE to "Idle · historical",
        )
        for ((role, label) in entries) {
            val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 1)); row.isOpaque = false
            row.alignmentX = Component.LEFT_ALIGNMENT
            val dot = JBLabel(dotIcon(colorForRole(role)))
            val text = JBLabel(label); text.font = text.font.deriveFont(JBUI.scaleFontSize(11.5f).toFloat())
            row.add(dot); row.add(text)
            panel.add(row)
        }
        val hint = JBLabel("State drives node colour; brightness fades with recency. Failed nodes keep a red ring.")
        hint.foreground = ClaudeUiTokens.textSecondary()
        hint.font = hint.font.deriveFont(Font.ITALIC, JBUI.scaleFontSize(10.5f).toFloat())
        hint.border = JBUI.Borders.emptyTop(4)
        panel.add(hint)
        JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
            .setRequestFocus(true).setTitle("Activity map legend").createPopup().showUnderneathOf(anchor)
    }

    // ---------- timeline dock ----------

    private fun buildDock(): JComponent {
        val dock = JPanel(BorderLayout())
        dock.border = BorderFactory.createMatteBorder(1, 0, 0, 0, ClaudeUiTokens.border())

        val bar = JPanel(BorderLayout()); bar.isOpaque = false; bar.border = JBUI.Borders.empty(3, 10)
        bar.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        dockToggle.foreground = ClaudeUiTokens.textSecondary()
        dockToggle.font = UIUtil.getLabelFont().deriveFont(JBUI.scaleFontSize(11f).toFloat())
        bar.add(dockToggle, BorderLayout.WEST)
        val chevron = JBLabel(ClaudeIcons.chevronUp.withSize(12))
        bar.add(chevron, BorderLayout.EAST)
        bar.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { toggleDock(chevron) }
        })
        dock.add(bar, BorderLayout.NORTH)

        timelineList.cellRenderer = TimelineRenderer()
        timelineList.addListSelectionListener {
            if (!timelineList.valueIsAdjusting) {
                dockState.following = false
                timelineList.selectedValue?.nodeId?.let { selectNode(it, center = true) }
            }
        }
        val listScroll = JBScrollPane(timelineList)
        listScroll.border = JBUI.Borders.empty()
        listScroll.preferredSize = Dimension(JBUI.scale(460), JBUI.scale(120))
        dockBody.add(listScroll, BorderLayout.CENTER)
        dock.add(dockBody, BorderLayout.CENTER)

        applyDockExpanded(chevron)
        return dock
    }

    private fun toggleDock(chevron: JBLabel) {
        dockState.expanded = !dockState.expanded
        ClaudeSettings.getInstance().state.activityTimelineExpanded = dockState.expanded
        if (dockState.expanded) dockState.following = true
        applyDockExpanded(chevron)
    }

    private fun applyDockExpanded(chevron: JBLabel) {
        dockBody.isVisible = dockState.expanded
        chevron.icon = (if (dockState.expanded) ClaudeIcons.chevronDown else ClaudeIcons.chevronUp).withSize(12)
        refreshHeader()
        dockBody.revalidate(); dockBody.repaint()
    }

    private fun rebuildTimeline() {
        timelineModel.clear()
        for (e in graph.timeline.takeLast(200)) timelineModel.addElement(e)
        if (dockState.shouldAutoScroll(userNearBottom = true) && timelineModel.size() > 0) {
            timelineList.ensureIndexIsVisible(timelineModel.size() - 1)
        }
    }

    // ---------- header refresh ----------

    private fun refreshHeader() {
        val total = graph.nodes.count { it.type != ActivityNodeType.CATEGORY }
        val shown = visibleNodeIds().count { graph.node(it)?.type != ActivityNodeType.CATEGORY }
        val hidden = MapDensity.hiddenCount(shown, total)
        countLabel.text = MapDensity.countText(shown, total)
        countLabel.cursor = if (hidden > 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        countLabel.toolTipText = if (hidden > 0) "$hidden more node" + (if (hidden == 1) "" else "s") + " hidden by the visible-node cap — click to show more" else null
        val active = !reduceMotion && hasFreshGlow()
        liveDot.icon = dotIcon(if (active) ClaudeUiTokens.success() else ClaudeUiTokens.textSecondary())
        pauseButton.isVisible = timer.isRunning || paused
        dockToggle.text = dockState.collapsedSummary(graph.timeline.size, graph.timeline.lastOrNull()?.let { "${it.verb} ${it.label}".trim() })
    }

    private fun dotIcon(color: Color): javax.swing.Icon = object : javax.swing.Icon {
        override fun getIconWidth() = JBUI.scale(10)
        override fun getIconHeight() = JBUI.scale(10)
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            val r = JBUI.scale(4)
            g2.fillOval(x + (iconWidth - 2 * r) / 2, y + (iconHeight - 2 * r) / 2, 2 * r, 2 * r)
            g2.dispose()
        }
    }

    // ---------- selection / inspector placement ----------

    /**
     * Fired when the user selects a node, so the host can reveal the transcript event that produced it.
     * The map is an *inspection surface* for the conversation, not a second competing view — without a
     * way back, selecting a node told you a file was touched but not why or by which step.
     */
    var onNodeSelected: ((ActivityNode) -> Unit)? = null

    private fun selectNode(id: String?, center: Boolean = false) {
        selectedId = id
        inspector.refresh()
        updateLayout()
        if (center && id != null) canvas.positions[id]?.let { canvas.centerOn(it) }
        canvas.repaint()
        id?.let { sel -> graph.nodes.firstOrNull { it.id == sel } }?.let { onNodeSelected?.invoke(it) }
    }

    /**
     * Selects the node for [path] (chat → map). Returns false when that file has no node yet, so the
     * caller can decline to switch views rather than switching to a map with nothing selected.
     */
    fun selectByPath(path: String): Boolean {
        val node = graph.nodes.lastOrNull { it.path == path && !it.hidden } ?: return false
        selectNode(node.id, center = true)
        return true
    }

    /** Selects the most recent node whose label matches [label] (used for command/test nodes). */
    fun selectByLabel(label: String): Boolean {
        val node = graph.nodes.lastOrNull { !it.hidden && it.label == label } ?: return false
        selectNode(node.id, center = true)
        return true
    }

    /** Keyboard selection: step through visible, selectable nodes in a stable order and centre on them. */
    private fun cycleSelection(dir: Int) {
        val ordered = graph.nodes
            .filter { !it.hidden && it.type != ActivityNodeType.CATEGORY }
            .sortedBy { it.firstSeenAt }
        if (ordered.isEmpty()) return
        val idx = ordered.indexOfFirst { it.id == selectedId }
        val next = if (idx < 0) 0 else (((idx + dir) % ordered.size) + ordered.size) % ordered.size
        selectNode(ordered[next].id, center = true)
        canvas.requestFocusInWindow()
    }

    /** Places the inspector: side drawer (medium/wide) or full replacement (narrow). */
    private fun updateLayout() {
        val hasSelection = selectedId?.let { graph.node(it) } != null
        bodyHost.removeAll()
        if (hasSelection && ResponsiveLayout.inspectorAsOverlay(profile)) {
            contentSplit.secondComponent = null
            bodyHost.add(inspectorScroll, BorderLayout.CENTER)
        } else {
            contentSplit.firstComponent = canvas
            contentSplit.secondComponent = if (hasSelection) inspectorScroll else null
            contentSplit.proportion = if (hasSelection) 0.66f else 1f
            bodyHost.add(contentSplit, BorderLayout.CENTER)
        }
        bodyHost.revalidate(); bodyHost.repaint()
    }

    // ---------- layout / physics ----------

    private fun retainedCap(): Int = ClaudeSettings.getInstance().state.activityMaxRetained.coerceIn(50, 5000)
    /**
     * Session-only lift of the visible-node cap, set by "Show more". Deliberately not persisted: the
     * setting stays the user's standing preference, and one busy session shouldn't quietly rewrite it.
     */
    private var capOverride: Int? = null

    private fun visibleCap(): Int =
        maxOf(ClaudeSettings.getInstance().state.activityMaxNodes.coerceIn(20, MapDensity.MAX_VISIBLE_CAP), capOverride ?: 0)

    /** Raise the cap one step so more of the graph is drawn; no-op once everything already fits. */
    private fun showMoreNodes() {
        val total = graph.nodes.count { it.type != ActivityNodeType.CATEGORY }
        val next = MapDensity.nextVisibleCap(visibleCap(), total)
        if (next <= visibleCap()) return
        capOverride = next
        ensurePositions(); refreshHeader(); canvas.repaint()
    }
    private fun currentFilter(): Filter = (filterCombo.selectedItem as? Filter) ?: Filter.ALL

    private fun visibleNodeIds(): Set<String> {
        val filter = currentFilter()
        val all = graph.nodes.filter { !it.hidden }
        val matched = all.filter { it.type == ActivityNodeType.TASK || it.type == ActivityNodeType.CATEGORY || filter.pred(it) }
        val must = LinkedHashSet<String>()
        must.add(ActivityGraph.TASK_ID)
        graph.focus.nodeId?.let { must.add(it) }
        selectedId?.let { must.add(it) }
        // Fold finished command/test/gradle history into its clusters when the user turns collapse on;
        // the task, current focus, selection, pinned nodes and anything still failing/active never fold.
        val plan = collapsePlan()
        val nonCat = matched.filter { it.type != ActivityNodeType.CATEGORY && it.id !in plan.hiddenIds }.sortedByDescending { it.lastSeenAt }
        val cap = visibleCap()
        val chosen = LinkedHashSet<String>(must)
        for (n in nonCat) { if (chosen.size >= cap) break; chosen.add(n.id) }
        val childCats = chosen.mapNotNull { graph.node(it)?.category?.name }.toSet()
        for (n in matched) if (n.type == ActivityNodeType.CATEGORY && n.category.name in childCats) chosen.add(n.id)
        // Keep a fully-folded cluster's category visible so its chip has an anchor to hang under.
        for (agg in plan.aggregates) chosen.add("cat:${agg.category.name}")
        return chosen
    }

    private fun ensurePositions() {
        val ids = graph.nodes.map { it.id }.toSet()
        canvas.positions.keys.retainAll(ids)
        canvas.velocities.keys.retainAll(ids)
        for (n in graph.nodes) {
            if (canvas.positions.containsKey(n.id)) continue
            val anchor = if (n.type == ActivityNodeType.TASK) Vec(0.0, 0.0)
            else canvas.positions["cat:${n.category.name}"] ?: canvas.positions[ActivityGraph.TASK_ID] ?: Vec(0.0, 0.0)
            canvas.positions[n.id] = Vec(anchor.x + Random.nextDouble(-40.0, 40.0), anchor.y + Random.nextDouble(-40.0, 40.0))
            canvas.velocities[n.id] = Vec(0.0, 0.0)
        }
    }

    private fun kick() {
        if (paused || reduceMotion || !canvas.isShowing) return
        if (!timer.isRunning) timer.start()
        refreshHeader()
    }

    private fun step() {
        if (paused || !canvas.isShowing) { timer.stop(); return }
        val energy = simulate()
        canvas.pulsePhase += 0.12
        canvas.repaint()
        if (energy < 0.05 && !hasFreshGlow() && !canvas.dragging) { timer.stop(); refreshHeader() }
    }

    private fun settleSync(iterations: Int = 240) {
        ensurePositions()
        repeat(iterations) { simulate() }
        canvas.repaint()
    }

    private fun hasFreshGlow(): Boolean {
        val now = Instant.now()
        return graph.nodes.any { Duration.between(it.lastSeenAt, now).seconds < GLOW_SECONDS }
    }

    private fun simulate(): Double {
        val ids = visibleNodeIds().toList()
        if (ids.size < 2) return 0.0
        val pos = canvas.positions
        val vel = canvas.velocities
        val force = HashMap<String, Vec>(ids.size)
        for (id in ids) force[id] = Vec(0.0, 0.0)
        for (i in ids.indices) {
            val a = pos[ids[i]] ?: continue
            for (j in i + 1 until ids.size) {
                val b = pos[ids[j]] ?: continue
                var dx = a.x - b.x; var dy = a.y - b.y
                var d2 = dx * dx + dy * dy
                if (d2 < 0.01) { dx = Random.nextDouble(-1.0, 1.0); dy = Random.nextDouble(-1.0, 1.0); d2 = 1.0 }
                val d = Math.sqrt(d2)
                val rep = REPULSION / d2
                // Spread harder in x than y: labels hang off each node's right side, so the footprint that
                // actually has to clear its neighbours is wide, not circular (MapDensity.LABEL_X_BIAS).
                val fx = dx / d * rep * MapDensity.LABEL_X_BIAS; val fy = dy / d * rep
                force[ids[i]]!!.x += fx; force[ids[i]]!!.y += fy
                force[ids[j]]!!.x -= fx; force[ids[j]]!!.y -= fy
            }
        }
        val visible = ids.toHashSet()
        for (e in graph.edges) {
            if (e.sourceNodeId !in visible || e.targetNodeId !in visible) continue
            val a = pos[e.sourceNodeId] ?: continue
            val b = pos[e.targetNodeId] ?: continue
            val dx = b.x - a.x; val dy = b.y - a.y
            val d = max(0.01, hypot(dx, dy))
            val rest = if (e.type.name == "CONTAINS") 120.0 else 90.0
            val f = SPRING * (d - rest)
            val fx = dx / d * f; val fy = dy / d * f
            force[e.sourceNodeId]!!.x += fx; force[e.sourceNodeId]!!.y += fy
            force[e.targetNodeId]!!.x -= fx; force[e.targetNodeId]!!.y -= fy
        }
        for (id in ids) {
            val p = pos[id] ?: continue
            force[id]!!.x -= p.x * GRAVITY
            force[id]!!.y -= p.y * GRAVITY
        }
        var energy = 0.0
        for (id in ids) {
            if (id == ActivityGraph.TASK_ID) { pos[id]?.let { it.x = 0.0; it.y = 0.0 }; continue }
            if (canvas.dragging && id == canvas.draggedId) continue
            val p = pos[id] ?: continue
            val v = vel[id] ?: continue
            val f = force[id]!!
            v.x = (v.x + f.x * DT) * DAMPING
            v.y = (v.y + f.y * DT) * DAMPING
            val speed = hypot(v.x, v.y)
            if (speed > MAX_SPEED) { v.x = v.x / speed * MAX_SPEED; v.y = v.y / speed * MAX_SPEED }
            p.x += v.x * DT; p.y += v.y * DT
            energy += speed
        }
        for (id in ids) {
            if (id == ActivityGraph.TASK_ID) continue
            if (canvas.dragging && id == canvas.draggedId) continue
            val p = pos[id] ?: continue
            val d = hypot(p.x, p.y)
            when {
                d <= 0.001 -> { p.x = TASK_CLEARANCE; p.y = 0.0 }
                d < TASK_CLEARANCE -> { val s = TASK_CLEARANCE / d; p.x *= s; p.y *= s }
            }
        }
        return energy / ids.size
    }

    private fun fit() {
        val ids = visibleNodeIds()
        val pts = ids.mapNotNull { canvas.positions[it] }
        if (pts.isEmpty()) return
        // On a big graph, frame the bulk rather than letting a couple of stragglers dictate the zoom, and
        // leave more edge room for the labels that hang off each node.
        val b = MapDensity.fitBounds(pts.map { it.x }, pts.map { it.y }) ?: return
        val pad = JBUI.scale(MapDensity.fitPadding(pts.size))
        val w = max(1.0, b.maxX - b.minX); val h = max(1.0, b.maxY - b.minY)
        val cw = max(1, canvas.width - pad); val ch = max(1, canvas.height - pad)
        canvas.scale = min(3.0, min(cw / w, ch / h)).coerceIn(0.15, 3.0)
        canvas.offset.x = -(b.minX + b.maxX) / 2 * canvas.scale
        canvas.offset.y = -(b.minY + b.maxY) / 2 * canvas.scale
        canvas.repaint()
    }

    // ---------- theme colours ----------

    private fun accent(): JBColor = JBColor(Color(0xC2, 0x55, 0x2E), Color(0xE8, 0x8A, 0x6B))

    private fun colorForRole(role: ActivityColorRole): JBColor = when (role) {
        ActivityColorRole.IDLE -> JBColor(0x8A9099, 0x6A7075)
        ActivityColorRole.DISCOVERED -> JBColor(0x0A98AC, 0x33C6DA)
        ActivityColorRole.READING -> JBColor(0x2F6FEB, 0x5B9BFF)
        ActivityColorRole.FOCUS -> JBColor(0x8250DF, 0xB98BFF)
        ActivityColorRole.EDITING -> JBColor(0xC97A00, 0xF0B429)
        ActivityColorRole.SUCCESS -> JBColor(0x1F9D57, 0x54D98A)
        ActivityColorRole.ERROR -> JBColor(0xD1242F, 0xF66A63)
        ActivityColorRole.WARNING -> JBColor(0xB08500, 0xE6C34D)
        ActivityColorRole.SUGGESTION -> JBColor(0xC44FA0, 0xF07AC8)
        ActivityColorRole.TASK -> JBColor(0x3A3F45, 0xF2F4F8)
        ActivityColorRole.BLOCKED -> JBColor(0x9A8C86, 0xA79A94)
    }

    private fun canvasBg(): Color {
        val base = UIUtil.getPanelBackground()
        return if (ClaudeUiTokens.isDark(base)) darken(base, 8) else brighten(base, 4)
    }

    private fun darken(c: Color, amt: Int) = Color(clamp(c.red - amt), clamp(c.green - amt), clamp(c.blue - amt))
    private fun brighten(c: Color, amt: Int) = Color(clamp(c.red + amt), clamp(c.green + amt), clamp(c.blue + amt))
    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
    private fun withAlpha(c: Color, a: Float) = Color(c.red, c.green, c.blue, (a.coerceIn(0f, 1f) * 255).toInt())

    private class Vec(var x: Double, var y: Double)

    companion object {
        private const val REPULSION = 5200.0
        private const val SPRING = 0.02
        private const val GRAVITY = 0.012
        private const val DAMPING = 0.82
        private const val DT = 0.9
        private const val MAX_SPEED = 22.0
        private const val GLOW_SECONDS = 12L
        private const val TASK_CLEARANCE = 52.0
        private const val DISCLAIMER = "Observable activity only — not the model's private reasoning."
    }

    // ---------- canvas ----------

    private inner class GraphCanvas : JComponent() {
        val positions = HashMap<String, Vec>()
        val velocities = HashMap<String, Vec>()
        var scale = 1.0
        val offset = Vec(0.0, 0.0)
        var pulsePhase = 0.0
        var dragging = false
        var draggedId: String? = null
        var hoveredId: String? = null
        // Screen rects of the collapse "N commands" chips, recorded each paint for click hit-testing.
        val aggregateChipBounds = HashMap<String, Rectangle>()
        private var panning = false
        private var lastDrag: Point? = null

        init {
            isOpaque = true
            background = canvasBg()
            isFocusable = true
            getAccessibleContext()?.accessibleName = A11yNames.ACTIVITY_GRAPH
            installKeyboardNav()
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    lastDrag = e.point
                    aggregateChipBounds.entries.firstOrNull { it.value.contains(e.point) }?.let { toggleAggregate(it.key); return }
                    val hit = hitTest(e.point)
                    if (hit != null) { draggedId = hit; dragging = true; selectNode(hit) }
                    else { panning = true; if (e.clickCount == 1) selectNode(null) }
                }
                override fun mouseReleased(e: MouseEvent) { dragging = false; panning = false; draggedId = null; kick() }
                override fun mouseDragged(e: MouseEvent) {
                    val prev = lastDrag ?: e.point; lastDrag = e.point
                    val dx = e.x - prev.x; val dy = e.y - prev.y
                    if (dragging && draggedId != null) {
                        val p = positions[draggedId]; if (p != null) { p.x += dx / scale; p.y += dy / scale }
                        velocities[draggedId]?.let { it.x = 0.0; it.y = 0.0 }
                        repaint()
                    } else if (panning) { offset.x += dx; offset.y += dy; repaint() }
                }
                override fun mouseMoved(e: MouseEvent) {
                    val h = hitTest(e.point)
                    if (h != hoveredId) { hoveredId = h; repaint() }
                }
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) hitTest(e.point)?.let { openNode(graph.node(it)) }
                }
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    val factor = if (e.wheelRotation < 0) 1.1 else 1 / 1.1
                    val newScale = (scale * factor).coerceIn(0.1, 4.0)
                    val cx = width / 2.0 + offset.x; val cy = height / 2.0 + offset.y
                    val wx = (e.x - cx) / scale; val wy = (e.y - cy) / scale
                    scale = newScale
                    offset.x = e.x - width / 2.0 - wx * scale
                    offset.y = e.y - height / 2.0 - wy * scale
                    repaint()
                }
            }
            addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse)
            ToolTipManager.sharedInstance().registerComponent(this)
        }

        /**
         * Keyboard access (a11y): Tab focuses the canvas, then arrows move the selection, Enter opens the
         * selected file, Esc clears it. No delete binding — selection is non-destructive.
         */
        private fun installKeyboardNav() {
            val im = getInputMap(JComponent.WHEN_FOCUSED)
            val am = actionMap
            fun bind(name: String, keys: List<KeyStroke>, run: () -> Unit) {
                keys.forEach { im.put(it, name) }
                am.put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { run() } })
            }
            bind("nav.next", listOf(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0))) { cycleSelection(1) }
            bind("nav.prev", listOf(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0))) { cycleSelection(-1) }
            bind("nav.open", listOf(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))) { selectedId?.let { openNode(graph.node(it)) } }
            bind("nav.clear", listOf(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))) { selectNode(null) }
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val id = hitTest(event.point) ?: return null
            val n = graph.node(id) ?: return null
            val state = n.state.name.lowercase(Locale.ROOT)
            val sub = n.subtitle?.let { " · $it" } ?: ""
            // Surface the strongest relationship provenance ("why") on hover, not only in the inspector.
            val why = graph.edgesTouching(id).mapNotNull { it.evidence }
                .minByOrNull { it.source.ordinal }
                ?.let { "<br>${escapeHtml(it.explanation)} · ${prettyEvidence(it.source)}" } ?: ""
            return "<html>${escapeHtml("${cleanLabel(n)} — $state$sub")}$why</html>"
        }

        fun centerOn(v: Vec) { offset.x = -v.x * scale; offset.y = -v.y * scale; repaint() }

        private fun worldToScreen(v: Vec): Point =
            Point((width / 2.0 + offset.x + v.x * scale).toInt(), (height / 2.0 + offset.y + v.y * scale).toInt())

        private fun hitTest(p: Point): String? {
            val ids = visibleNodeIds()
            var best: String? = null; var bestD = Double.MAX_VALUE
            for (id in ids) {
                val pos = positions[id] ?: continue
                val s = worldToScreen(pos)
                val r = radiusOf(graph.node(id)) + 3
                val d = hypot((p.x - s.x).toDouble(), (p.y - s.y).toDouble())
                if (d <= r && d < bestD) { bestD = d; best = id }
            }
            return best
        }

        private fun radiusOf(n: ActivityNode?): Double {
            if (n == null) return 6.0
            val base = when (n.type) {
                ActivityNodeType.TASK -> 15.0
                ActivityNodeType.CATEGORY -> 10.0
                else -> 7.0
            }
            return (base + min(6.0, n.interactionCount * 0.7)) * (0.65 + 0.35 * scale).coerceIn(0.6, 1.6)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = canvasBg(); g2.fillRect(0, 0, width, height)
            val ids = visibleNodeIds()
            if (ids.size <= 1) { drawEmpty(g2); g2.dispose(); return }
            val now = Instant.now()
            val focusId = graph.focus.nodeId

            // Edges: faded historical, stronger recent, error paths tinted.
            for (e in graph.edges) {
                if (e.sourceNodeId !in ids || e.targetNodeId !in ids) continue
                val a = positions[e.sourceNodeId] ?: continue
                val b = positions[e.targetNodeId] ?: continue
                val sa = worldToScreen(a); val sb = worldToScreen(b)
                val age = Duration.between(e.lastActivatedAt, now).seconds
                val fresh = (1.0 - age.toDouble() / GLOW_SECONDS).coerceIn(0.0, 1.0)
                val seq = e.type.name == "SEQUENTIAL_ACTIVITY"
                val onFocusPath = e.sourceNodeId == focusId || e.targetNodeId == focusId
                val base = if (e.type.name == "AFFECTED_BY") colorForRole(ActivityColorRole.ERROR) else ClaudeUiTokens.border()
                val alpha = (0.08 + fresh * 0.42 + if (onFocusPath) 0.2 else 0.0).toFloat().coerceAtMost(0.85f)
                g2.color = withAlpha(base, alpha)
                g2.stroke = if (seq) dashedStroke else BasicStroke((0.7 + fresh * 1.6 + if (onFocusPath) 0.6 else 0.0).toFloat())
                g2.drawLine(sa.x, sa.y, sb.x, sb.y)
            }
            g2.stroke = BasicStroke(1f)

            // One tier for the whole frame: labelling must not flicker per-node as the graph grows.
            val labelTier = MapDensity.labelTier(ids.size, scale)
            val pendingLabels = ArrayList<PendingLabel>(ids.size)

            for (id in ids) {
                val n = graph.node(id) ?: continue
                val p = positions[id] ?: continue
                val s = worldToScreen(p)
                val r = radiusOf(n)
                val role = ActivityColorRoles.roleForNode(n)
                val col = colorForRole(role)
                val age = Duration.between(n.lastSeenAt, now).seconds
                val fresh = (1.0 - age.toDouble() / GLOW_SECONDS).coerceIn(0.0, 1.0)
                val low = n.confidence < 0.6f

                // Restrained glow only on genuinely fresh, non-cluster nodes.
                if (fresh > 0.1 && n.type != ActivityNodeType.CATEGORY) {
                    val gr = r + 4 + fresh * 5
                    g2.color = withAlpha(col, (fresh * 0.18).toFloat())
                    g2.fillOval((s.x - gr).toInt(), (s.y - gr).toInt(), (gr * 2).toInt(), (gr * 2).toInt())
                }
                // Category clusters: soft containing halo only.
                val bodyAlpha = (0.4 + fresh * 0.5).toFloat().coerceIn(0.4f, 1f)
                g2.color = if (n.type == ActivityNodeType.CATEGORY) withAlpha(col, 0.14f) else withAlpha(col, bodyAlpha)
                g2.fillOval((s.x - r).toInt(), (s.y - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
                g2.color = withAlpha(col, if (low) 0.5f else 0.9f)
                g2.stroke = if (low) dashedStroke else BasicStroke(1.2f)
                g2.drawOval((s.x - r).toInt(), (s.y - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
                g2.stroke = BasicStroke(1f)

                if (n.type == ActivityNodeType.TASK) {
                    g2.color = accent(); g2.stroke = BasicStroke(2f)
                    val tr = r + 4
                    g2.drawOval((s.x - tr).toInt(), (s.y - tr).toInt(), (tr * 2).toInt(), (tr * 2).toInt())
                    g2.stroke = BasicStroke(1f)
                }
                if (graph.hasActiveError(id)) {
                    g2.color = colorForRole(ActivityColorRole.ERROR)
                    val er = r + 3
                    g2.drawOval((s.x - er).toInt(), (s.y - er).toInt(), (er * 2).toInt(), (er * 2).toInt())
                }
                if (n.state == ActivityNodeState.DENIED || n.state == ActivityNodeState.CANCELLED) {
                    // Dashed muted ring + slash so a denied/cancelled action never reads as done.
                    g2.color = colorForRole(ActivityColorRole.BLOCKED)
                    g2.stroke = dashedStroke
                    val br = r + 3
                    g2.drawOval((s.x - br).toInt(), (s.y - br).toInt(), (br * 2).toInt(), (br * 2).toInt())
                    g2.stroke = BasicStroke(1.5f)
                    val d = r * 0.7
                    g2.drawLine((s.x - d).toInt(), (s.y - d).toInt(), (s.x + d).toInt(), (s.y + d).toInt())
                    g2.stroke = BasicStroke(1f)
                }
                if (n.pinned) { g2.color = accent(); g2.fillOval(s.x + r.toInt() - 2, s.y - r.toInt() - 2, JBUI.scale(5), JBUI.scale(5)) }
                // Focus pulse: only the active node animates.
                if (id == focusId && !reduceMotion) {
                    val pr = r + 5 + Math.sin(pulsePhase) * 3
                    g2.color = withAlpha(colorForRole(ActivityColorRole.FOCUS), 0.75f)
                    g2.stroke = BasicStroke(1.6f)
                    g2.drawOval((s.x - pr).toInt(), (s.y - pr).toInt(), (pr * 2).toInt(), (pr * 2).toInt())
                    g2.stroke = BasicStroke(1f)
                }
                if (id == selectedId) {
                    g2.color = accent(); g2.stroke = BasicStroke(2f)
                    val sr = r + 4
                    g2.drawOval((s.x - sr).toInt(), (s.y - sr).toInt(), (sr * 2).toInt(), (sr * 2).toInt())
                    g2.stroke = BasicStroke(1f)
                }
                // Labels thin out as the graph grows (MapDensity): anchors and errors always keep theirs,
                // whatever the user is pointing at keeps its own, and ordinary nodes drop out first.
                val attention = id == focusId || id == selectedId || id == hoveredId
                val showLabel = MapDensity.showsLabel(
                    tier = labelTier,
                    type = n.type,
                    attention = attention,
                    radius = r,
                    scale = scale,
                )
                if (showLabel) {
                    // Measured now, drawn after every node body — so a label can neither be overdrawn by a
                    // later node nor overprint a neighbouring label (LabelPlacement resolves collisions).
                    val font = UIUtil.getLabelFont()
                        .deriveFont(if (n.type == ActivityNodeType.CATEGORY) JBUIScale.scale(11f) else JBUIScale.scale(10.5f))
                    val fm = g2.getFontMetrics(font)
                    val label = truncateLabel(cleanLabel(n), 26)
                    val boxW = fm.stringWidth(label) + 6
                    val ty = s.y + fm.ascent / 2 - 1
                    pendingLabels.add(
                        PendingLabel(
                            box = LabelPlacement.Candidate(
                                id = id,
                                x = s.x + r.toInt() + 1,                  // preferred: right of the node
                                alternateX = s.x - r.toInt() - 1 - boxW,  // fallback: to its left
                                y = ty - fm.ascent,
                                width = boxW,
                                height = fm.height,
                                priority = MapDensity.labelPriority(attention, n.type),
                            ),
                            text = label, font = font, baseline = ty, fresh = fresh,
                        ),
                    )
                }
            }

            drawLabels(g2, pendingLabels)
            drawAggregateChips(g2, ids)
            drawFocusOverlay(g2)
            g2.dispose()
        }

        /** A measured but not-yet-drawn node label, held until every node body is painted. */
        private inner class PendingLabel(
            val box: LabelPlacement.Candidate,
            val text: String,
            val font: Font,
            val baseline: Int,
            val fresh: Double,
        )

        /**
         * Draws the labels that survive collision resolution. Losing a collision withholds only the text —
         * the node itself is already painted, and hovering it restores the label (attention wins outright).
         */
        private fun drawLabels(g2: Graphics2D, labels: List<PendingLabel>) {
            if (labels.isEmpty()) return
            val placed = LabelPlacement.place(labels.map { it.box })
            for (l in labels) {
                val boxX = placed[l.box.id] ?: continue
                g2.font = l.font
                g2.color = withAlpha(canvasBg(), 0.72f)
                g2.fillRoundRect(boxX, l.box.y, l.box.width, l.box.height, 7, 7)
                g2.color = withAlpha(ClaudeUiTokens.textPrimary(), (0.6 + l.fresh * 0.4).toFloat())
                g2.drawString(l.text, boxX + 3, l.baseline)
            }
        }

        /** A "N commands" chip under each foldable cluster; click toggles expand-in-place ([toggleAggregate]). */
        private fun drawAggregateChips(g2: Graphics2D, ids: Set<String>) {
            aggregateChipBounds.clear()
            if (!collapseHistory) return
            g2.font = UIUtil.getLabelFont().deriveFont(JBUIScale.scale(10.5f))
            val fm = g2.fontMetrics
            for (agg in allAggregates()) {
                val catId = "cat:${agg.category.name}"
                if (catId !in ids) continue
                val pos = positions[catId] ?: continue
                val s = worldToScreen(pos)
                val expanded = agg.id in expandedAggregates
                val padX = JBUI.scale(7); val padY = JBUI.scale(3)
                val w = fm.stringWidth(agg.label) + padX * 2
                val h = fm.height + padY * 2
                val x = s.x - w / 2
                val y = s.y + radiusOf(graph.node(catId)).toInt() + JBUI.scale(7)
                g2.color = if (expanded) withAlpha(accent(), 0.16f) else withAlpha(ClaudeUiTokens.elevatedSurface(), 0.95f)
                g2.fillRoundRect(x, y, w, h, JBUI.scale(9), JBUI.scale(9))
                g2.color = if (expanded) accent() else ClaudeUiTokens.border()
                g2.drawRoundRect(x, y, w, h, JBUI.scale(9), JBUI.scale(9))
                g2.color = if (expanded) accent() else ClaudeUiTokens.textSecondary()
                g2.drawString(agg.label, x + padX, y + padY + fm.ascent)
                aggregateChipBounds[agg.id] = Rectangle(x, y, w, h)
            }
        }

        /** Compact translucent focus card, top-left; hidden when there is no meaningful focus. */
        private fun drawFocusOverlay(g2: Graphics2D) {
            val f = graph.focus
            val node = f.nodeId?.let { graph.node(it) }
            if (node == null || f.label.isBlank()) return
            val verb = f.verb.uppercase(Locale.ROOT)
            val label = truncateLabel(f.label, 30)
            val detail = f.detail?.let { truncateLabel(it, 34) }
            val pad = JBUI.scale(8)
            g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUIScale.scale(9.5f))
            val vfm = g2.fontMetrics
            g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUIScale.scale(12f))
            val lfm = g2.fontMetrics
            val w = maxOf(vfm.stringWidth(verb), lfm.stringWidth(label), detail?.let { lfm.stringWidth(it) } ?: 0) + pad * 2
            var h = pad * 2 + vfm.height + lfm.height + JBUI.scale(2)
            if (detail != null) h += lfm.height
            val x = JBUI.scale(10); val y = JBUI.scale(10)
            g2.color = withAlpha(ClaudeUiTokens.overlaySurface(), 0.9f)
            g2.fillRoundRect(x, y, w, h, JBUI.scale(10), JBUI.scale(10))
            g2.color = withAlpha(colorForRole(ActivityColorRoles.roleForNode(node)), 0.8f)
            g2.stroke = BasicStroke(1.4f)
            g2.drawRoundRect(x, y, w, h, JBUI.scale(10), JBUI.scale(10))
            g2.stroke = BasicStroke(1f)
            var ty = y + pad + vfm.ascent
            g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUIScale.scale(9.5f))
            g2.color = withAlpha(colorForRole(ActivityColorRoles.roleForNode(node)), 1f)
            g2.drawString(verb, x + pad, ty)
            ty += vfm.height - vfm.descent + JBUI.scale(3)
            g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUIScale.scale(12f))
            g2.color = ClaudeUiTokens.textPrimary()
            g2.drawString(label, x + pad, ty)
            if (detail != null) {
                ty += lfm.height
                g2.font = UIUtil.getLabelFont().deriveFont(JBUIScale.scale(11f))
                g2.color = ClaudeUiTokens.textSecondary()
                g2.drawString(detail, x + pad, ty)
            }
        }

        private fun drawEmpty(g2: Graphics2D) {
            // A designed empty state: a central task node + a few faint placeholders + copy.
            val cx = width / 2; val cy = height / 2 - JBUI.scale(14)
            val faint = withAlpha(ClaudeUiTokens.textSecondary(), 0.22f)
            g2.color = faint
            val ring = JBUI.scale(46)
            for (i in 0 until 3) {
                val a = Math.PI * 2 * i / 3 - Math.PI / 2
                val px = cx + (Math.cos(a) * ring).toInt(); val py = cy + (Math.sin(a) * ring).toInt()
                g2.drawLine(cx, cy, px, py)
                val rr = JBUI.scale(7)
                g2.fillOval(px - rr, py - rr, rr * 2, rr * 2)
            }
            g2.color = withAlpha(accent(), 0.55f)
            val cr = JBUI.scale(12)
            g2.fillOval(cx - cr, cy - cr, cr * 2, cr * 2)

            g2.color = ClaudeUiTokens.textPrimary()
            g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUIScale.scale(13f))
            val head = "Activity will appear here"
            g2.drawString(head, cx - g2.fontMetrics.stringWidth(head) / 2, cy + JBUI.scale(70))
            g2.color = ClaudeUiTokens.textSecondary()
            g2.font = UIUtil.getLabelFont().deriveFont(JBUIScale.scale(11.5f))
            val desc = "Files, searches, edits, commands and tests light up as Claude works."
            g2.drawString(desc, cx - g2.fontMetrics.stringWidth(desc) / 2, cy + JBUI.scale(88))
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(360), JBUI.scale(260))
    }

    // ---------- label helpers ----------

    /** Strips decorative glyph prefixes the graph model may carry, for a clean UI label. */
    private fun cleanLabel(n: ActivityNode): String =
        n.label.trimStart('⌕', '↗', '⚠', '△', '✱', '$', ' ').ifBlank { n.label }

    private fun truncateLabel(s: String, max: Int) = if (s.length > max) s.substring(0, max - 1) + "…" else s

    private fun openNode(n: ActivityNode?) {
        val path = n?.path ?: return
        val abs = if (java.io.File(path).isAbsolute) path else (project.basePath?.let { "$it/$path" } ?: path)
        val vf = LocalFileSystem.getInstance().findFileByPath(abs) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun revealNode(n: ActivityNode?) {
        val path = n?.path ?: return
        val abs = if (java.io.File(path).isAbsolute) path else (project.basePath?.let { "$it/$path" } ?: path)
        val vf = LocalFileSystem.getInstance().findFileByPath(abs) ?: return
        ProjectView.getInstance(project).select(null, vf, true)
    }

    // ---------- inspector ----------

    private inner class InspectorPanel : JPanel(BorderLayout()) {
        private val body = JPanel()
        init {
            border = JBUI.Borders.empty(8, 10)
            body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
            add(body, BorderLayout.NORTH)
            // Esc clears the selection from anywhere inside the inspector (mirrors the canvas' Esc), so
            // keyboard users aren't trapped once focus moves off the canvas into the inspector's controls.
            getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeInspector")
            actionMap.put("closeInspector", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) { selectNode(null) }
            })
            refresh()
        }

        fun refresh() {
            body.removeAll()
            val n = selectedId?.let { graph.node(it) } ?: return

            // Header: icon + name + state + close.
            val head = JPanel(BorderLayout()); head.isOpaque = false; head.alignmentX = Component.LEFT_ALIGNMENT
            head.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
            val name = JBLabel(cleanLabel(n))
            name.font = name.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(13f).toFloat())
            head.add(name, BorderLayout.CENTER)
            head.add(IconActionButton(ClaudeIcons.close.withSize(13), "Close inspector") { selectNode(null) }, BorderLayout.EAST)
            body.add(head)
            addLine("${prettyType(n.type)} · ${n.state.name.lowercase(Locale.ROOT)}", muted = true)
            n.path?.let { addLine(it, muted = true) }

            // Primary actions.
            addGap()
            val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))); actions.isOpaque = false
            actions.alignmentX = Component.LEFT_ALIGNMENT
            if (n.path != null) {
                actions.add(IconActionButton(ClaudeIcons.openFile, "Open in editor") { openNode(n) })
                actions.add(IconActionButton(ClaudeIcons.reveal, "Reveal in Project view") { revealNode(n) })
            }
            n.path?.takeIf { it.endsWith(".kt") || it.endsWith(".java") || it.endsWith(".kts") }?.let { path ->
                actions.add(IconActionButton(ClaudeIcons.search, "Find usages in project") {
                    usageEnricher.findUsagesAsync(path) { this@ActivityMapPanel.apply(it) }
                })
            }
            actions.add(IconActionButton(if (n.pinned) ClaudeIcons.diamond else ClaudeIcons.diamond, if (n.pinned) "Unpin" else "Pin (keep from eviction)") { graph.setPinned(n.id, !n.pinned); refresh(); canvas.repaint() })
            actions.add(IconActionButton(ClaudeIcons.close, "Hide from map") { graph.setHidden(n.id, true); selectNode(null) })
            body.add(actions)

            // Property rows.
            addGap()
            propRow("Category", n.category.label)
            propRow("Interactions", n.interactionCount.toString())
            propRow("Last active", timeAgo(n.lastSeenAt))
            if (graph.hasActiveError(n.id)) addLine("Affected by an active error", muted = false)

            // Relationships — with provenance ("why") when we have it (PSI/import/naming evidence).
            val related = graph.edgesTouching(n.id).mapNotNull { e ->
                val otherId = if (e.sourceNodeId == n.id) e.targetNodeId else e.sourceNodeId
                val other = graph.node(otherId) ?: return@mapNotNull null
                if (other.type == ActivityNodeType.CATEGORY) null else e to other
            }.take(6)
            if (related.isNotEmpty()) {
                addGap(); addLine("Related", bold = true)
                related.forEach { (e, other) ->
                    val ev = e.evidence
                    // The explanation ("DriverRepositoryImpl implements DriverRepository") already names both
                    // ends, so it stands alone; fall back to the node label when there's no provenance.
                    if (ev != null) addLine("• ${ev.explanation} · ${prettyEvidence(ev.source)}", muted = true)
                    else addLine("• " + cleanLabel(other), muted = true)
                }
            }

            // Evidence (confidence) — secondary; only surfaced for inferred/low-confidence.
            if (n.confidence < 0.9f) {
                addGap(); addLine("Evidence", bold = true)
                addLine("Inferred · confidence ${(n.confidence * 100).toInt()}%", muted = true)
            }
            body.revalidate(); body.repaint()
        }

        private fun propRow(key: String, value: String) {
            val row = JPanel(BorderLayout()); row.isOpaque = false; row.alignmentX = Component.LEFT_ALIGNMENT
            row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            val k = JBLabel(key); k.foreground = ClaudeUiTokens.textSecondary(); k.font = k.font.deriveFont(JBUI.scaleFontSize(11.5f).toFloat())
            k.preferredSize = Dimension(JBUI.scale(96), JBUI.scale(18))
            val v = JBLabel(value); v.foreground = ClaudeUiTokens.textPrimary(); v.font = v.font.deriveFont(JBUI.scaleFontSize(11.5f).toFloat())
            row.add(k, BorderLayout.WEST); row.add(v, BorderLayout.CENTER)
            body.add(row)
        }

        private fun addLine(text: String, bold: Boolean = false, muted: Boolean = false) {
            val l = JBLabel("<html>${escape(text)}</html>")
            l.alignmentX = Component.LEFT_ALIGNMENT
            if (bold) l.font = l.font.deriveFont(Font.BOLD)
            l.foreground = if (muted) ClaudeUiTokens.textSecondary() else ClaudeUiTokens.textPrimary()
            l.font = l.font.deriveFont(JBUI.scaleFontSize(11.5f).toFloat())
            body.add(l)
        }
        private fun addGap() { body.add(Box.createVerticalStrut(JBUI.scale(6))) }
        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun prettyType(t: ActivityNodeType): String =
        t.name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun prettyEvidence(s: EvidenceSource): String = when (s) {
        EvidenceSource.STRUCTURED_TOOL_EVENT -> "tool event"
        EvidenceSource.PSI_DECLARATION -> "PSI"
        EvidenceSource.PSI_REFERENCE -> "PSI reference"
        EvidenceSource.IMPORT -> "import"
        EvidenceSource.NAMING_HEURISTIC -> "naming"
        EvidenceSource.PATH_HEURISTIC -> "path"
        EvidenceSource.COMMAND_OUTPUT -> "command output"
    }

    private fun timeAgo(instant: Instant): String {
        val s = Duration.between(instant, Instant.now()).seconds
        return when {
            s < 2 -> "just now"; s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60}m ago"; else -> "${s / 3600}h ago"
        }
    }

    // ---------- timeline renderer ----------

    private inner class TimelineRenderer : ListCellRenderer<TimelineEntry> {
        override fun getListCellRendererComponent(
            list: JList<out TimelineEntry>, value: TimelineEntry, index: Int, selected: Boolean, focus: Boolean,
        ): Component {
            val row = JPanel(BorderLayout(JBUI.scale(6), 0))
            row.border = JBUI.Borders.empty(2, 8)
            row.background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            val dot = JBLabel(dotIcon(colorForRole(ActivityColorRoles.roleForState(value.state))))
            val fg = if (selected) UIUtil.getListSelectionForeground(true) else ClaudeUiTokens.textPrimary()
            val verb = JBLabel(value.verb)
            verb.font = verb.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(11f).toFloat()); verb.foreground = fg
            val label = JBLabel(value.label)
            label.font = label.font.deriveFont(JBUI.scaleFontSize(11f).toFloat())
            label.foreground = if (selected) fg else ClaudeUiTokens.textSecondary()
            val leftRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)); leftRow.isOpaque = false
            leftRow.add(dot); leftRow.add(verb); leftRow.add(label)
            row.add(leftRow, BorderLayout.WEST)
            return row
        }
    }
}
