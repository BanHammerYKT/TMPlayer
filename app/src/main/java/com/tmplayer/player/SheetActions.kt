package com.tmplayer.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * The status sheet's buttons, as a column or as a row that wraps.
 *
 * A column is what a television keeps: one axis of the D-pad reaches every button, in the order
 * they should be tried. A phone held sideways has about 390dp of height, and three full width pills
 * under a message pushed the last one off the bottom while the right half of the screen sat empty.
 * There the same buttons go side by side from the start edge, each as wide as its words, centred on
 * their line, and a label too long for what is left of the line starts the next one. Held upright,
 * under words that are centred, each line of buttons is centred too ([centred]).
 *
 * In a row a label never wraps inside its pill: each button is measured at the width of its words
 * on one line, and one that does not fit beside the others moves to a line of its own. Only a label
 * wider than the whole sheet is given the width there is and allowed to wrap.
 */
class SheetActions @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val gap = (12 * resources.displayMetrics.density).toInt()

    /** Above a row: one gap between the words and the buttons, whichever button comes first. */
    private val rowTop = (20 * resources.displayMetrics.density).toInt()

    /** Side by side when true; stacked, each button's top margin kept, when false. */
    var inRow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Each line (in a row) or button (stacked) centred across the width rather than from the start edge. */
    var centred: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        }
        val childSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
        val anyHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val shown = shown()
        shown.forEach { child ->
            if (inRow) {
                // The width its words take on one line, and only the room there is when even a
                // line of its own is too narrow for that.
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), anyHeight)
                if (child.measuredWidth > maxWidth) child.measure(childSpec, anyHeight)
            } else {
                child.measure(childSpec, anyHeight)
            }
        }
        var width = 0
        var height = 0
        if (inRow) {
            val lines = lines(shown, maxWidth)
            lines.forEach { line -> width = max(width, lineWidth(line)) }
            if (lines.isNotEmpty()) height = rowTop + lines.sumOf(::lineHeight) + gap * (lines.size - 1)
        } else {
            shown.forEach {
                width = max(width, it.measuredWidth)
                height += topMargin(it) + it.measuredHeight
            }
        }
        setMeasuredDimension(
            resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inner = r - l - paddingLeft - paddingRight
        var y = paddingTop
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        if (!inRow) {
            for (child in shown()) {
                y += topMargin(child)
                // From the start edge, as the words above read on a television, unless asked to
                // centre: a quieter, narrower pill such as Save logs then lines up with the rest.
                val x = when {
                    centred -> paddingLeft + (inner - child.measuredWidth) / 2
                    rtl -> paddingLeft + inner - child.measuredWidth
                    else -> paddingLeft
                }
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                y += child.measuredHeight
            }
            return
        }
        y += rowTop
        for (line in lines(shown(), inner)) {
            val height = lineHeight(line)
            var x = if (centred) ((inner - lineWidth(line)) / 2).coerceAtLeast(0) else 0
            for (child in line) {
                val left = if (rtl) paddingLeft + inner - x - child.measuredWidth else paddingLeft + x
                val top = y + (height - child.measuredHeight) / 2
                child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
                x += child.measuredWidth + gap
            }
            y += height + gap
        }
    }

    /** The buttons cut into lines no wider than [width], in order. */
    private fun lines(shown: List<View>, width: Int): List<List<View>> {
        val lines = mutableListOf<MutableList<View>>()
        var used = 0
        for (child in shown) {
            val line = lines.lastOrNull()
            if (line == null || used + gap + child.measuredWidth > width) {
                lines += mutableListOf(child)
                used = child.measuredWidth
            } else {
                line += child
                used += gap + child.measuredWidth
            }
        }
        return lines
    }

    private fun lineWidth(line: List<View>): Int = line.sumOf { it.measuredWidth } + gap * (line.size - 1)

    private fun lineHeight(line: List<View>): Int = line.maxOf { it.measuredHeight }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)

    private fun shown(): List<View> = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }

    private fun topMargin(child: View): Int = (child.layoutParams as? MarginLayoutParams)?.topMargin ?: 0
}
