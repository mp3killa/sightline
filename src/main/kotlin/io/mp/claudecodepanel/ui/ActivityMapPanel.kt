package io.mp.claudecodepanel.ui

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.activity.ActivityColorRole
import io.mp.claudecodepanel.activity.ActivityColorRoles
import io.mp.claudecodepanel.activity.ActivityGraph
import io.mp.claudecodepanel.activity.ActivityNode
import io.mp.claudecodepanel.activity.ActivityNodeType
import io.mp.claudecodepanel.activity.AgentActivityEvent
import io.mp.claudecodepanel.activity.TimelineEntry
import io.mp.claudecodepanel.settings.ClaudeSettings
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ListCellRenderer
import javax.swing.Timer
import javax.swing.ToolTipManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The **Agent Activity Map** (Neural Activity Map / Live Context Graph): a 2.5D force-directed
 * Swing visualisation of what the agent is observably touching. Fed normalised
 * [AgentActivityEvent]s, it lights up files, commands, tests, errors and clusters as Claude works.
 *
 * This is NOT a view of the model's hidden reasoning — only observable tool activity is shown.
 *
 * Performance: physics runs on a Swing [Timer] that only ticks while the component is showing and
 * either the layout hasn't settled or a node glow is still fading; it auto-suspends when idle.
 * A "reduce motion" mode settles the layout synchronously with no animation.
 */
class ActivityMapPanel(private val project: Project, parent: Disposable) : Disposable {

    val component: JComponent
    private val graph = ActivityGraph(maxRetained = retainedCap())

    private val focusVerb = JBLabel("Agent Activity Map")
    private val focusLabel = JBLabel(" ")
    private val focusDetail = JBLabel(" ")
    private val countLabel = JBLabel(" ")
    private val canvas = GraphCanvas()
    private val details = DetailsPanel()
    private val timelineModel = DefaultListModel<TimelineEntry>()
    private val timelineList = JBList(timelineModel)

    private val pauseButton = JToggleButton("Pause")
    private val reduceMotionButton = JToggleButton("Reduce motion")
    private val filterCombo = JComboBox(Filter.values())

    private var selectedId: String? = null
    private var paused = false
    private var reduceMotion = ClaudeSettings.getInstance().state.activityReduceMotion

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
        refreshFocusCard()
    }

    // ---------- public API (called from ClaudePanel) ----------

    /** Feed a batch of normalised events; updates the model, timeline and (re)starts animation. */
    fun apply(events: List<AgentActivityEvent>) {
        if (events.isEmpty()) return
        for (e in events) graph.apply(e)
        rebuildTimeline()
        ensurePositions()
        refreshFocusCard()
        details.refresh()
        if (reduceMotion) settleSync(60) else kick()
        canvas.repaint()
    }

    fun clearSession() {
        graph.clear()
        timelineModel.clear()
        selectedId = null
        canvas.positions.clear(); canvas.velocities.clear()
        refreshFocusCard(); details.refresh()
        canvas.repaint()
    }

    override fun dispose() { timer.stop() }

    // ---------- build ----------

    private fun build(): JComponent {
        val root = JPanel(BorderLayout())
        root.border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
        root.add(buildHeader(), BorderLayout.NORTH)

        val split = JBSplitter(false, 0.72f)
        split.firstComponent = canvas
        split.secondComponent = JBScrollPane(details).apply { border = JBUI.Borders.empty() }
        split.setHonorComponentsMinimumSize(false)
        root.add(split, BorderLayout.CENTER)

        root.add(buildTimeline(), BorderLayout.SOUTH)
        root.preferredSize = Dimension(JBUI.scale(480), JBUI.scale(320))
        return root
    }

    private fun buildHeader(): JComponent {
        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.border = JBUI.Borders.empty(6, 10, 4, 10)

        val focusRow = JPanel(BorderLayout(8, 0)); focusRow.isOpaque = false
        focusVerb.font = focusVerb.font.deriveFont(Font.BOLD, JBUI.scale(12f))
        focusVerb.foreground = accent()
        val texts = JPanel(); texts.layout = BoxLayout(texts, BoxLayout.Y_AXIS); texts.isOpaque = false
        focusLabel.font = focusLabel.font.deriveFont(Font.BOLD)
        focusDetail.foreground = UIUtil.getContextHelpForeground()
        focusDetail.font = focusDetail.font.deriveFont(JBUI.scale(11f))
        texts.add(focusLabel); texts.add(focusDetail)
        focusRow.add(focusVerb, BorderLayout.WEST)
        focusRow.add(texts, BorderLayout.CENTER)
        countLabel.foreground = UIUtil.getContextHelpForeground()
        countLabel.font = countLabel.font.deriveFont(JBUI.scale(11f))
        focusRow.add(countLabel, BorderLayout.EAST)
        focusRow.alignmentX = Component.LEFT_ALIGNMENT
        header.add(focusRow)

        val note = JBLabel("Observable activity only — not the model's private reasoning.")
        note.foreground = UIUtil.getContextHelpForeground()
        note.font = note.font.deriveFont(Font.ITALIC, JBUI.scale(10.5f))
        note.alignmentX = Component.LEFT_ALIGNMENT
        note.border = JBUI.Borders.emptyTop(1)
        header.add(note)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2)); controls.isOpaque = false
        controls.alignmentX = Component.LEFT_ALIGNMENT
        filterCombo.toolTipText = "Filter which nodes are shown"
        filterCombo.addActionListener { ensurePositions(); canvas.repaint() }
        controls.add(filterCombo)
        pauseButton.toolTipText = "Pause live layout animation"
        pauseButton.addActionListener { paused = pauseButton.isSelected; if (!paused) kick() else timer.stop() }
        controls.add(pauseButton)
        reduceMotionButton.isSelected = reduceMotion
        reduceMotionButton.toolTipText = "Static layout, no pulsing (lower CPU)"
        reduceMotionButton.addActionListener {
            reduceMotion = reduceMotionButton.isSelected
            ClaudeSettings.getInstance().state.activityReduceMotion = reduceMotion
            if (reduceMotion) { timer.stop(); settleSync() } else kick()
            canvas.repaint()
        }
        controls.add(reduceMotionButton)
        controls.add(smallButton("Fit") { fit() })
        controls.add(smallButton("Clear") { clearSession() })
        header.add(controls)
        return header
    }

    private fun buildTimeline(): JComponent {
        timelineList.cellRenderer = TimelineRenderer()
        timelineList.visibleRowCount = 3
        timelineList.addListSelectionListener {
            (timelineList.selectedValue)?.nodeId?.let { selectNode(it, center = true) }
        }
        val scroll = JBScrollPane(timelineList)
        scroll.border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
        scroll.preferredSize = Dimension(JBUI.scale(480), JBUI.scale(70))
        return scroll
    }

    private fun smallButton(text: String, run: () -> Unit): JButton {
        val b = JButton(text); b.putClientProperty("JButton.buttonType", "square")
        b.margin = JBUI.insets(2, 6); b.addActionListener { run() }
        return b
    }

    // ---------- focus / selection ----------

    private fun refreshFocusCard() {
        val f = graph.focus
        focusVerb.text = f.verb.ifBlank { "Agent Activity Map" }
        focusLabel.text = f.label.ifBlank { " " }
        focusDetail.text = f.detail ?: " "
        // Count non-structural (non-category) nodes consistently, so "shown" is never > "total".
        val visible = visibleNodeIds()
        val total = graph.nodes.count { it.type != ActivityNodeType.CATEGORY }
        val shown = visible.count { graph.node(it)?.type != ActivityNodeType.CATEGORY }
        countLabel.text = if (shown < total) "$shown of $total nodes" else "$total nodes"
    }

    private fun selectNode(id: String?, center: Boolean = false) {
        selectedId = id
        details.refresh()
        if (center && id != null) canvas.positions[id]?.let { canvas.centerOn(it) }
        canvas.repaint()
    }

    // ---------- layout / physics ----------

    private fun retainedCap(): Int = ClaudeSettings.getInstance().state.activityMaxRetained.coerceIn(50, 5000)
    private fun visibleCap(): Int = ClaudeSettings.getInstance().state.activityMaxNodes.coerceIn(20, 2000)

    private fun currentFilter(): Filter = (filterCombo.selectedItem as? Filter) ?: Filter.ALL

    /** Capped + filtered set of node ids to render, always including task/focus/selected + live clusters. */
    private fun visibleNodeIds(): Set<String> {
        val filter = currentFilter()
        val all = graph.nodes.filter { !it.hidden }
        val matched = all.filter { it.type == ActivityNodeType.TASK || it.type == ActivityNodeType.CATEGORY || filter.pred(it) }
        val must = LinkedHashSet<String>()
        must.add(ActivityGraph.TASK_ID)
        graph.focus.nodeId?.let { must.add(it) }
        selectedId?.let { must.add(it) }
        val nonCat = matched.filter { it.type != ActivityNodeType.CATEGORY }
            .sortedByDescending { it.lastSeenAt }
        val cap = visibleCap()
        val chosen = LinkedHashSet<String>(must)
        for (n in nonCat) { if (chosen.size >= cap) break; chosen.add(n.id) }
        // Include category clusters that still have a visible child.
        val childCats = chosen.mapNotNull { graph.node(it)?.category?.name }.toSet()
        for (n in matched) if (n.type == ActivityNodeType.CATEGORY && n.category.name in childCats) chosen.add(n.id)
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
    }

    private fun step() {
        if (paused || !canvas.isShowing) { timer.stop(); return }
        val energy = simulate()
        canvas.pulsePhase += 0.12
        canvas.repaint()
        if (energy < 0.05 && !hasFreshGlow() && !canvas.dragging) timer.stop()
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

    /** One force-directed integration step over visible nodes; returns total kinetic energy. */
    private fun simulate(): Double {
        val ids = visibleNodeIds().toList()
        if (ids.size < 2) return 0.0
        val pos = canvas.positions
        val vel = canvas.velocities
        val force = HashMap<String, Vec>(ids.size)
        for (id in ids) force[id] = Vec(0.0, 0.0)

        // Repulsion (Coulomb-ish) between all visible pairs.
        for (i in ids.indices) {
            val a = pos[ids[i]] ?: continue
            for (j in i + 1 until ids.size) {
                val b = pos[ids[j]] ?: continue
                var dx = a.x - b.x; var dy = a.y - b.y
                var d2 = dx * dx + dy * dy
                if (d2 < 0.01) { dx = Random.nextDouble(-1.0, 1.0); dy = Random.nextDouble(-1.0, 1.0); d2 = 1.0 }
                val d = Math.sqrt(d2)
                val rep = REPULSION / d2
                val fx = dx / d * rep; val fy = dy / d * rep
                force[ids[i]]!!.x += fx; force[ids[i]]!!.y += fy
                force[ids[j]]!!.x -= fx; force[ids[j]]!!.y -= fy
            }
        }
        // Spring attraction along visible edges.
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
        // Weak gravity toward centre so disconnected nodes don't drift away.
        for (id in ids) {
            val p = pos[id] ?: continue
            force[id]!!.x -= p.x * GRAVITY
            force[id]!!.y -= p.y * GRAVITY
        }
        // Integrate. Task node is pinned at the origin (the hub).
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
        return energy / ids.size
    }

    private fun fit() {
        val ids = visibleNodeIds()
        val pts = ids.mapNotNull { canvas.positions[it] }
        if (pts.isEmpty()) return
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val w = max(1.0, maxX - minX); val h = max(1.0, maxY - minY)
        val cw = max(1, canvas.width - JBUI.scale(80)); val ch = max(1, canvas.height - JBUI.scale(80))
        canvas.scale = min(3.0, min(cw / w, ch / h)).coerceIn(0.15, 3.0)
        canvas.offset.x = -(minX + maxX) / 2 * canvas.scale
        canvas.offset.y = -(minY + maxY) / 2 * canvas.scale
        canvas.repaint()
    }

    private fun rebuildTimeline() {
        timelineModel.clear()
        for (e in graph.timeline.takeLast(200)) timelineModel.addElement(e)
        if (!paused && timelineModel.size() > 0) timelineList.ensureIndexIsVisible(timelineModel.size() - 1)
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
    }

    private fun canvasBg(): Color {
        val base = UIUtil.getPanelBackground()
        val dark = ColorUtilLocal.isDark(base)
        return if (dark) darken(base, 8) else brighten(base, 4)
    }

    private fun darken(c: Color, amt: Int) = Color(clamp(c.red - amt), clamp(c.green - amt), clamp(c.blue - amt))
    private fun brighten(c: Color, amt: Int) = Color(clamp(c.red + amt), clamp(c.green + amt), clamp(c.blue + amt))
    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
    private fun withAlpha(c: Color, a: Float) = Color(c.red, c.green, c.blue, (a.coerceIn(0f, 1f) * 255).toInt())

    private object ColorUtilLocal {
        fun isDark(c: Color) = (c.red * 0.299 + c.green * 0.587 + c.blue * 0.114) < 128
    }

    private class Vec(var x: Double, var y: Double)

    companion object {
        private const val REPULSION = 5200.0
        private const val SPRING = 0.02
        private const val GRAVITY = 0.012
        private const val DAMPING = 0.82
        private const val DT = 0.9
        private const val MAX_SPEED = 22.0
        private const val GLOW_SECONDS = 12L
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
        private var panning = false
        private var lastDrag: Point? = null

        init {
            isOpaque = true
            background = canvasBg()
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    lastDrag = e.point
                    val hit = hitTest(e.point)
                    if (hit != null) {
                        draggedId = hit; dragging = true; selectNode(hit)
                    } else { panning = true; if (e.clickCount == 1) selectNode(null) }
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
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) hitTest(e.point)?.let { openNode(graph.node(it)) }
                }
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    val factor = if (e.wheelRotation < 0) 1.1 else 1 / 1.1
                    val newScale = (scale * factor).coerceIn(0.1, 4.0)
                    // Zoom around the cursor.
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

        override fun getToolTipText(event: MouseEvent): String? {
            val n = hitTest(event.point)?.let { graph.node(it) } ?: return null
            val state = n.state.name.lowercase(Locale.ROOT)
            val sub = n.subtitle?.let { " · $it" } ?: ""
            return "${n.label} — $state$sub"
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

            // Edges first.
            for (e in graph.edges) {
                if (e.sourceNodeId !in ids || e.targetNodeId !in ids) continue
                val a = positions[e.sourceNodeId] ?: continue
                val b = positions[e.targetNodeId] ?: continue
                val sa = worldToScreen(a); val sb = worldToScreen(b)
                val age = Duration.between(e.lastActivatedAt, now).seconds
                val fresh = (1.0 - age.toDouble() / GLOW_SECONDS).coerceIn(0.0, 1.0)
                val seq = e.type.name == "SEQUENTIAL_ACTIVITY"
                val base = if (e.type.name == "AFFECTED_BY") colorForRole(ActivityColorRole.ERROR) else JBColor.border()
                g2.color = withAlpha(base, (0.12 + fresh * 0.5).toFloat())
                g2.stroke = if (seq) dashedStroke else BasicStroke((0.8 + fresh * 1.6).toFloat())
                g2.drawLine(sa.x, sa.y, sb.x, sb.y)
            }
            g2.stroke = BasicStroke(1f)

            // Nodes.
            val focusId = graph.focus.nodeId
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

                // Glow halo by recency.
                if (fresh > 0.05 && n.type != ActivityNodeType.CATEGORY) {
                    val gr = r + 6 + fresh * 6
                    g2.color = withAlpha(col, (fresh * 0.28).toFloat())
                    g2.fillOval((s.x - gr).toInt(), (s.y - gr).toInt(), (gr * 2).toInt(), (gr * 2).toInt())
                }
                // Body.
                val bodyAlpha = (0.35 + fresh * 0.55).toFloat().coerceIn(0.35f, 1f)
                g2.color = if (n.type == ActivityNodeType.CATEGORY) withAlpha(col, 0.18f) else withAlpha(col, bodyAlpha)
                g2.fillOval((s.x - r).toInt(), (s.y - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
                // Outline (dashed + subtle for low-confidence inferred nodes).
                g2.color = withAlpha(col, if (low) 0.5f else 0.9f)
                g2.stroke = if (low) dashedStroke else BasicStroke(1.2f)
                g2.drawOval((s.x - r).toInt(), (s.y - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
                g2.stroke = BasicStroke(1f)

                // Persistent red ring if an active error affects this node.
                if (graph.hasActiveError(id)) {
                    g2.color = colorForRole(ActivityColorRole.ERROR)
                    val er = r + 3
                    g2.drawOval((s.x - er).toInt(), (s.y - er).toInt(), (er * 2).toInt(), (er * 2).toInt())
                }
                // Pinned indicator.
                if (n.pinned) { g2.color = accent(); g2.fillOval(s.x + r.toInt() - 2, s.y - r.toInt() - 2, 5, 5) }
                // Focus pulse.
                if (id == focusId && !reduceMotion) {
                    val pr = r + 5 + Math.sin(pulsePhase) * 3
                    g2.color = withAlpha(colorForRole(ActivityColorRole.FOCUS), 0.75f)
                    g2.stroke = BasicStroke(1.6f)
                    g2.drawOval((s.x - pr).toInt(), (s.y - pr).toInt(), (pr * 2).toInt(), (pr * 2).toInt())
                    g2.stroke = BasicStroke(1f)
                }
                // Selection ring.
                if (id == selectedId) {
                    g2.color = accent(); g2.stroke = BasicStroke(2f)
                    val sr = r + 4
                    g2.drawOval((s.x - sr).toInt(), (s.y - sr).toInt(), (sr * 2).toInt(), (sr * 2).toInt())
                    g2.stroke = BasicStroke(1f)
                }
                // Labels: always for task/category/patch/error/focus/selected, else only when zoomed in.
                val alwaysLabel = n.type == ActivityNodeType.TASK || n.type == ActivityNodeType.CATEGORY ||
                    n.type == ActivityNodeType.PATCH || n.type == ActivityNodeType.ERROR
                val showLabel = alwaysLabel || id == focusId || id == selectedId || (scale >= 0.75 && r >= 6)
                if (showLabel) {
                    g2.font = UIUtil.getLabelFont().deriveFont(if (n.type == ActivityNodeType.CATEGORY) JBUI.scale(11f) else JBUI.scale(10.5f))
                    g2.color = withAlpha(UIUtil.getLabelForeground(), (0.55 + fresh * 0.45).toFloat())
                    val label = n.label
                    val tx = s.x + r.toInt() + 4
                    val ty = s.y + g2.fontMetrics.ascent / 2 - 1
                    g2.drawString(label, tx, ty)
                }
            }
            g2.dispose()
        }

        private fun drawEmpty(g2: Graphics2D) {
            g2.color = UIUtil.getContextHelpForeground()
            g2.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC)
            val msg = "Agent active — waiting for contextual activity…"
            val fm = g2.fontMetrics
            g2.drawString(msg, (width - fm.stringWidth(msg)) / 2, height / 2)
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(360), JBUI.scale(260))
    }

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

    // ---------- details panel ----------

    private inner class DetailsPanel : JPanel() {
        private val body = JPanel()
        init {
            layout = BorderLayout()
            border = JBUI.Borders.empty(8)
            body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
            add(body, BorderLayout.NORTH)
            refresh()
        }

        fun refresh() {
            body.removeAll()
            val n = selectedId?.let { graph.node(it) }
            if (n == null) {
                addLine("Select a node", bold = true)
                addLine("Click any node to see its details, related files and actions.", muted = true)
            } else {
                addLine(n.label, bold = true)
                addLine("${prettyType(n.type)} · ${n.category.label}", muted = true)
                n.path?.let { addLine(it, muted = true) }
                addGap()
                addLine("State: ${n.state.name.lowercase(Locale.ROOT)}")
                addLine("Interactions: ${n.interactionCount}")
                addLine("Confidence: ${(n.confidence * 100).toInt()}%")
                addLine("First seen: ${timeAgo(n.firstSeenAt)}   ·   Last: ${timeAgo(n.lastSeenAt)}")
                if (graph.hasActiveError(n.id)) addLine("⚠ affected by an active error", muted = false)
                val related = graph.neighbors(n.id).mapNotNull { graph.node(it) }
                    .filter { it.type != ActivityNodeType.CATEGORY }.take(6)
                if (related.isNotEmpty()) {
                    addGap(); addLine("Related", bold = true)
                    related.forEach { addLine("• ${it.label}", muted = true) }
                }
                addGap()
                val actions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)); actions.isOpaque = false
                actions.alignmentX = Component.LEFT_ALIGNMENT
                if (n.path != null) {
                    actions.add(smallButton("Open") { openNode(n) })
                    actions.add(smallButton("Reveal") { revealNode(n) })
                }
                actions.add(smallButton(if (n.pinned) "Unpin" else "Pin") { graph.setPinned(n.id, !n.pinned); refresh(); canvas.repaint() })
                actions.add(smallButton("Hide") { graph.setHidden(n.id, true); selectNode(null) })
                body.add(actions)
            }
            body.revalidate(); body.repaint()
        }

        private fun addLine(text: String, bold: Boolean = false, muted: Boolean = false) {
            val l = JBLabel("<html>${escape(text)}</html>")
            l.alignmentX = Component.LEFT_ALIGNMENT
            if (bold) l.font = l.font.deriveFont(Font.BOLD)
            if (muted) l.foreground = UIUtil.getContextHelpForeground()
            l.font = l.font.deriveFont(JBUI.scale(11.5f))
            body.add(l)
        }
        private fun addGap() { body.add(Box.createVerticalStrut(JBUI.scale(6))) }
        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun prettyType(t: ActivityNodeType): String =
        t.name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercase() }

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
            val row = JPanel(BorderLayout(6, 0))
            row.border = JBUI.Borders.empty(1, 6)
            row.background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            val dot = JBLabel("●")
            dot.foreground = colorForRole(ActivityColorRoles.roleForState(value.state))
            val fg = if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getLabelForeground()
            val verb = JBLabel(value.verb)
            verb.font = verb.font.deriveFont(Font.BOLD, JBUI.scale(11f)); verb.foreground = fg
            val label = JBLabel(value.label)
            label.font = label.font.deriveFont(JBUI.scale(11f))
            label.foreground = if (selected) fg else UIUtil.getContextHelpForeground()
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)); left.isOpaque = false
            left.add(dot); left.add(verb); left.add(label)
            row.add(left, BorderLayout.WEST)
            return row
        }
    }
}
