package io.mp.sightline.ui.components

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * A [FlowLayout] that reports the height it will actually occupy once its children wrap.
 *
 * Plain `FlowLayout` lays children out on multiple rows but computes `preferredLayoutSize` as though
 * they were all on **one** row. A parent sizing itself from that preferred height therefore gives the
 * row a single line of space, and everything past the first line is clipped — which is exactly how a
 * fourth context chip vanished from the narrow composer while looking fine at every wider size.
 *
 * The fix is to measure against the width the container has been given, which means
 * `preferredLayoutSize` has to consult the container's own width rather than assume infinity.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = false).also { it.width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            // Fall back to the parent's width before this container has one of its own; without that,
            // the first layout pass measures against zero and wraps every child onto its own row.
            var targetWidth = target.size.width
            if (targetWidth == 0) targetWidth = target.parent?.size?.width ?: Int.MAX_VALUE
            if (targetWidth == 0) targetWidth = Int.MAX_VALUE

            val insets = target.insets
            val horizontalInsets = insets.left + insets.right + hgap * 2
            val maxWidth = targetWidth - horizontalInsets

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            fun endRow() {
                dim.width = maxOf(dim.width, rowWidth)
                if (dim.height > 0) dim.height += vgap
                dim.height += rowHeight
                rowWidth = 0
                rowHeight = 0
            }

            for (i in 0 until target.componentCount) {
                val c = target.getComponent(i)
                if (!c.isVisible) continue
                val d = if (preferred) c.preferredSize else c.minimumSize
                if (rowWidth + d.width > maxWidth && rowWidth > 0) endRow()
                if (rowWidth > 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
            endRow()

            dim.width += horizontalInsets
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }
}
