package com.tmplayer.player

import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.theme.Motion

/**
 * "Back to 12:34" after a long jump ([SeekUndo]): the way back to where the viewer was before a
 * digit, a chapter step, a slip on the bar or a skip took them somewhere else.
 *
 * Bottom left, opposite the skip pill and the next-up card, and drawn the same way, so the corner
 * offers read as one family. A phone taps it. A television never focuses it, as it never focuses
 * the skip pill: the activity hands it Up instead, a key that otherwise only raises the row, and
 * the second line says so. Left, Right and OK keep their meanings.
 *
 * It slides in from its edge, sinks a little under a finger and goes once taken or after a few
 * seconds; with reduced motion it only fades.
 */
class UndoSeekChip(
    root: FrameLayout,
    below: View,
    private val tv: Boolean,
    onUndo: () -> Unit,
) {
    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    private val label = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        textSize = if (tv) 18f else 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val hint = TextView(context).apply {
        setTextColor(context.getColor(R.color.accent))
        textSize = 13f
        maxLines = 1
        text = L.playerBackToUp
        visibility = if (tv) View.VISIBLE else View.GONE
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = context.getDrawable(R.drawable.bg_player_chip)
        setPadding(px(22), px(if (tv) 12 else 11), px(22), px(if (tv) 10 else 11))
        visibility = View.GONE
        isFocusable = false
        if (!tv) {
            isClickable = true
            setOnClickListener { onUndo() }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> if (moving()) v.animate().scaleX(PRESSED).scaleY(PRESSED).setDuration(PRESS_MS).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(PRESS_MS).start()
                }
                false
            }
        }
        addView(label)
        addView(hint)
    }

    init {
        root.addView(
            view,
            root.indexOfChild(below),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM,
            ).apply {
                leftMargin = NextUpCard.marginPx(root)
                marginStart = NextUpCard.marginPx(root)
                bottomMargin = NextUpCard.bottomPx(root)
            },
        )
    }

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    /** Puts the offer up, or changes its time if it already is. */
    fun show(backToMs: Long) {
        label.text = L.playerBackTo(Translator.messages.formatter.clock(backToMs))
        // A longer time on a chip already up ("1:45" to "1:08:35") is measured again rather than
        // cut to the width the first one had.
        label.requestLayout()
        view.requestLayout()
        if (isShown) return
        view.visibility = View.VISIBLE
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        if (moving()) {
            view.alpha = 0f
            view.translationX = -px(SLIDE_DP).toFloat() * if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
            view.animate().alpha(1f).translationX(0f).setDuration(IN_MS).start()
        } else {
            view.alpha = 1f
            view.translationX = 0f
        }
    }

    /** Takes it away: [taken] shrinks it into the press, otherwise it just fades. */
    fun hide(taken: Boolean = false) {
        if (!isShown) return
        view.animate().cancel()
        if (!moving()) {
            view.visibility = View.GONE
            return
        }
        val shrink = if (taken) PRESSED else 1f
        view.animate().alpha(0f).scaleX(shrink).scaleY(shrink).setDuration(OUT_MS)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
            }
            .start()
    }

    private fun moving(): Boolean = !Motion.reduced && com.tmplayer.ui.theme.systemAnimatorsEnabled(context)

    private companion object {
        const val SLIDE_DP = 24
        const val IN_MS = 220L
        const val OUT_MS = 160L
        const val PRESS_MS = 90L
        const val PRESSED = 0.94f
    }
}
