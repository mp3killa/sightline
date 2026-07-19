package io.mp.claudecodepanel.ui.markdown

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.mp.claudecodepanel.theme.ClaudeUiTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Turns the platform-free [MdBlock] model into theme-aware Swing components — the thin presentation half
 * of the Markdown layer. Text-bearing blocks (headings, paragraphs, list items, quotes, table cells)
 * render into wrapping, selectable [JTextPane]s that mirror the app's existing styled-pane approach;
 * tables and code become dedicated components. All colours/fonts come from [ClaudeUiTokens] / the editor
 * scheme, so it follows the light/dark theme.
 *
 * [onLink] is invoked when a hyperlink is clicked (default: open in the browser); Phase 2 routes project
 * file references through it. Rendering is defensive — a single bad block never throws past its component.
 */
class BlockRenderer(private val onLink: (String) -> Unit = { runCatching { BrowserUtil.browse(it) } }) {

    fun render(blocks: List<MdBlock>): List<JComponent> = blocks.map { block(it) }

    private fun block(b: MdBlock): JComponent = when (b) {
        is MdHeading -> heading(b)
        is MdParagraph -> paragraph(b.inlines)
        is MdList -> list(b, depth = 0)
        is MdCodeBlock -> codeBlock(b)
        is MdQuote -> quote(b.blocks)
        is MdTable -> table(b)
        is MdCallout -> callout(b) // populated in Phase 2; render as a tinted quote until then
        MdThematicBreak -> thematicBreak()
    }

    // ---- text blocks ----

    private fun heading(h: MdHeading): JComponent {
        val size = when (h.level) {
            1 -> 17f; 2 -> 15f; 3 -> 13.5f; else -> 12.5f
        }
        val pane = inlinePane(h.inlines, baseFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(size).toFloat()))
        return boxed(pane, top = if (h.level <= 2) 10 else 8, bottom = 3)
    }

    private fun paragraph(inlines: List<MdInline>): JComponent =
        boxed(inlinePane(inlines, baseFont()), top = 0, bottom = 4)

    private fun quote(blocks: List<MdBlock>): JComponent {
        val inner = JPanel()
        inner.layout = BoxLayout(inner, BoxLayout.Y_AXIS)
        inner.isOpaque = true
        inner.background = ClaudeUiTokens.subtleSurface()
        inner.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, ClaudeUiTokens.withAlpha(ClaudeUiTokens.accent(), 0.5f)),
            JBUI.Borders.empty(2, 10),
        )
        blocks.map { block(it) }.forEach { it.alignmentX = Component.LEFT_ALIGNMENT; inner.add(it) }
        return boxed(inner, top = 2, bottom = 4)
    }

    private fun callout(c: MdCallout): JComponent {
        val accent = when (c.kind) {
            MdCalloutKind.WARNING, MdCalloutKind.CAUTION -> ClaudeUiTokens.warning()
            MdCalloutKind.IMPORTANT -> ClaudeUiTokens.accent()
            else -> ClaudeUiTokens.info() // NOTE, TIP
        }
        val inner = JPanel()
        inner.layout = BoxLayout(inner, BoxLayout.Y_AXIS)
        inner.isOpaque = true
        inner.background = ClaudeUiTokens.subtleSurface()
        // Restrained: a coloured left rule + label, not a bright full-card border.
        inner.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, accent),
            JBUI.Borders.empty(4, 10),
        )
        val head = JBLabel(c.kind.name.lowercase().replaceFirstChar { it.uppercase() })
        head.foreground = accent
        head.font = baseFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(11f).toFloat())
        head.alignmentX = Component.LEFT_ALIGNMENT
        inner.add(head)
        c.blocks.map { block(it) }.forEach { it.alignmentX = Component.LEFT_ALIGNMENT; inner.add(it) }
        return boxed(inner, top = 4, bottom = 6)
    }

    private fun thematicBreak(): JComponent {
        val line = JPanel()
        line.isOpaque = true
        line.background = ClaudeUiTokens.border()
        line.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
        line.preferredSize = Dimension(JBUI.scale(1), JBUI.scale(1))
        return boxed(line, top = 8, bottom = 8)
    }

    // ---- lists ----

    private fun list(l: MdList, depth: Int): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        l.items.forEachIndexed { i, item -> panel.add(listItem(l, item, i)) }
        val col = MdColumn()
        col.add(panel, BorderLayout.CENTER)
        col.border = JBUI.Borders.emptyLeft(if (depth == 0) 2 else 14)
        return boxed(col, top = 2, bottom = 4)
    }

    private fun listItem(l: MdList, item: MdListItem, index: Int): JComponent {
        val row = MdColumn()
        val marker = when {
            item.task != MdTask.NONE -> if (item.task == MdTask.CHECKED) "☑" else "☐"
            l.ordered -> "${l.start + index}."
            else -> "•"
        }
        val bullet = JBLabel(marker)
        bullet.foreground = if (item.task != MdTask.NONE) ClaudeUiTokens.textSecondary() else ClaudeUiTokens.accent()
        bullet.font = baseFont()
        bullet.border = JBUI.Borders.empty(0, 0, 0, 6)
        bullet.verticalAlignment = javax.swing.SwingConstants.TOP
        val bulletWrap = JPanel(BorderLayout())
        bulletWrap.isOpaque = false
        bulletWrap.add(bullet, BorderLayout.NORTH)
        bulletWrap.preferredSize = Dimension(JBUI.scale(if (l.ordered) 22 else 14), bullet.preferredSize.height)

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        item.blocks.map { block(it) }.forEach { it.alignmentX = Component.LEFT_ALIGNMENT; content.add(it) }

        row.add(bulletWrap, BorderLayout.WEST)
        row.add(content, BorderLayout.CENTER)
        return row
    }

    // ---- code ----

    private fun codeBlock(c: MdCodeBlock): JComponent {
        val wrap = MdColumn()
        wrap.isOpaque = true
        wrap.background = ClaudeUiTokens.subtleSurface()
        wrap.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ClaudeUiTokens.subtleBorder(), 1, true),
            JBUI.Borders.empty(4, 8),
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false
        c.language?.let {
            val lang = JBLabel(it)
            lang.foreground = ClaudeUiTokens.textSecondary()
            lang.font = baseFont().deriveFont(JBUI.scaleFontSize(10.5f).toFloat())
            header.add(lang, BorderLayout.WEST)
        }
        val copy = JButton("Copy")
        copy.font = baseFont().deriveFont(JBUI.scaleFontSize(10.5f).toFloat())
        copy.isFocusable = true
        copy.margin = Insets(0, 6, 0, 6)
        copy.addActionListener {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(c.code), null)
            copy.text = "Copied"
            javax.swing.Timer(1200) { copy.text = "Copy" }.apply { isRepeats = false }.start()
        }
        header.add(copy, BorderLayout.EAST)

        val area = JTextArea(c.code)
        area.isEditable = false
        area.lineWrap = false
        area.isOpaque = false
        area.font = monoFont()
        area.foreground = ClaudeUiTokens.textPrimary()
        area.border = JBUI.Borders.emptyTop(4)
        val scroll = JBScrollPane(area, JBScrollPane.VERTICAL_SCROLLBAR_NEVER, JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        scroll.isOpaque = false
        scroll.viewport.isOpaque = false
        scroll.border = JBUI.Borders.empty()

        wrap.add(header, BorderLayout.NORTH)
        wrap.add(scroll, BorderLayout.CENTER)
        return boxed(wrap, top = 4, bottom = 6)
    }

    // ---- table ----

    private fun table(t: MdTable): JComponent {
        val grid = JPanel(GridBagLayout())
        grid.isOpaque = false
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
        val gridColor = ClaudeUiTokens.subtleBorder()

        fun addCell(cellInlines: List<MdInline>, row: Int, col: Int, headerRow: Boolean) {
            val align = t.alignments.getOrNull(col) ?: MdAlign.LEFT
            val pane = inlinePane(cellInlines, if (headerRow) baseFont().deriveFont(Font.BOLD) else baseFont(), align)
            val cell = JPanel(BorderLayout())
            cell.isOpaque = headerRow
            if (headerRow) cell.background = ClaudeUiTokens.elevatedSurface()
            cell.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, if (col < cols - 1) 1 else 0, gridColor),
                JBUI.Borders.empty(4, 8),
            )
            cell.add(pane, BorderLayout.CENTER)
            val gbc = GridBagConstraints().apply {
                gridx = col; gridy = row; weightx = 1.0; fill = GridBagConstraints.BOTH
                anchor = GridBagConstraints.NORTHWEST
            }
            grid.add(cell, gbc)
        }

        t.header.forEachIndexed { col, cell -> addCell(cell, 0, col, headerRow = true) }
        t.rows.forEachIndexed { r, row -> row.forEachIndexed { col, cell -> addCell(cell, r + 1, col, headerRow = false) } }

        val framed = MdColumn()
        framed.border = BorderFactory.createLineBorder(gridColor, 1, true)
        framed.add(grid, BorderLayout.CENTER)
        return boxed(framed, top = 4, bottom = 6)
    }

    // ---- inline ----

    /** A wrapping, selectable pane rendering [inlines] with the app's inline styling + link handling. */
    private fun inlinePane(inlines: List<MdInline>, font: Font, align: MdAlign = MdAlign.LEFT): JTextPane {
        val pane = JTextPane()
        pane.isEditable = false
        pane.isOpaque = false
        pane.border = JBUI.Borders.empty()
        pane.font = font
        pane.foreground = ClaudeUiTokens.textPrimary()
        val doc = pane.styledDocument
        val links = ArrayList<LinkSpan>()
        val base = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, ClaudeUiTokens.textPrimary())
            StyleConstants.setFontFamily(this, font.family)
            StyleConstants.setFontSize(this, font.size)
            if (font.isBold) StyleConstants.setBold(this, true)
        }
        appendInlines(doc, inlines, base, links)
        // Paragraph attributes: readable line spacing + cell/heading alignment.
        val para = SimpleAttributeSet().apply {
            StyleConstants.setLineSpacing(this, 0.25f)
            StyleConstants.setAlignment(this, when (align) {
                MdAlign.CENTER -> StyleConstants.ALIGN_CENTER
                MdAlign.RIGHT -> StyleConstants.ALIGN_RIGHT
                MdAlign.LEFT -> StyleConstants.ALIGN_LEFT
            })
        }
        doc.setParagraphAttributes(0, doc.length.coerceAtLeast(1), para, false)
        installLinks(pane, links)
        return pane
    }

    private fun appendInlines(doc: StyledDocument, inlines: List<MdInline>, attr: SimpleAttributeSet, links: MutableList<LinkSpan>) {
        for (inline in inlines) when (inline) {
            is MdText -> doc.insertString(doc.length, inline.text, attr)
            is MdBold -> appendInlines(doc, inline.inlines, clone(attr) { StyleConstants.setBold(it, true) }, links)
            is MdItalic -> appendInlines(doc, inline.inlines, clone(attr) { StyleConstants.setItalic(it, true) }, links)
            is MdStrikethrough -> appendInlines(doc, inline.inlines, clone(attr) { StyleConstants.setStrikeThrough(it, true) }, links)
            is MdCode -> doc.insertString(doc.length, inline.text, codeAttr(attr))
            is MdLink -> {
                val start = doc.length
                appendInlines(doc, inline.inlines, clone(attr) {
                    StyleConstants.setForeground(it, ClaudeUiTokens.info())
                    StyleConstants.setUnderline(it, true)
                }, links)
                if (inline.href.isNotBlank()) links.add(LinkSpan(start, doc.length, inline.href))
            }
        }
    }

    private fun codeAttr(base: SimpleAttributeSet) = clone(base) {
        StyleConstants.setFontFamily(it, monoFont().family)
        StyleConstants.setBackground(it, ClaudeUiTokens.subtleSurface())
        StyleConstants.setForeground(it, ClaudeUiTokens.textPrimary())
    }

    private fun clone(attr: SimpleAttributeSet, mutate: (SimpleAttributeSet) -> Unit): SimpleAttributeSet =
        SimpleAttributeSet(attr).also(mutate)

    private data class LinkSpan(val start: Int, val end: Int, val href: String)

    private fun installLinks(pane: JTextPane, links: List<LinkSpan>) {
        if (links.isEmpty()) return
        fun hrefAt(e: MouseEvent): String? {
            val offset = pane.viewToModel2D(e.point)
            return links.firstOrNull { offset in it.start until it.end }?.href
        }
        pane.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { hrefAt(e)?.let { onLink(it) } }
        })
        pane.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                pane.cursor = Cursor.getPredefinedCursor(if (hrefAt(e) != null) Cursor.HAND_CURSOR else Cursor.TEXT_CURSOR)
            }
        })
    }

    // ---- layout helpers ----

    /** A BorderLayout panel with the block contract for a BoxLayout.Y transcript (left-aligned, height-capped). */
    private open class MdColumn : JPanel(BorderLayout()) {
        init { alignmentX = Component.LEFT_ALIGNMENT; isOpaque = false }
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun boxed(content: JComponent, top: Int, bottom: Int): JComponent {
        val col = MdColumn()
        col.isOpaque = content.isOpaque && content !is JTextPane
        if (col.isOpaque) col.background = content.background
        col.border = JBUI.Borders.empty(JBUI.scale(top), 0, JBUI.scale(bottom), 0)
        col.add(content, BorderLayout.CENTER)
        return col
    }

    private fun baseFont(): Font = UIUtil.getLabelFont()
    private fun monoFont(): Font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
}
