package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.ui.icons.TmIcons
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Every play glyph sits centred by eye in its box: the weight of the drawn triangle (where a round
 * button's middle is judged from) within a few per cent of the box's middle, a little right of
 * the triangle's own bounds. A play button that looked pushed left on the cards' hover (user,
 * 2026-10-22) is what this keeps from coming back, for every button, badge and pill at once.
 */
class PlayGlyphCentreTest {

    private fun weightX(icon: ImageVector, size: Int = 240): Pair<Double, Double> {
        val image = ImageComposeScene(size, size, Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.fillMaxSize())
            }
        }.use { it.render(0) }
        val bitmap = Bitmap.makeFromImage(image)
        var mass = 0.0
        var sum = 0.0
        var left = size
        var right = 0
        for (y in 0 until size) for (x in 0 until size) {
            val ink = 1.0 - (bitmap.getColor(x, y) and 0xFF) / 255.0
            if (ink <= 0.02) continue
            mass += ink
            sum += ink * x
            if (x < left) left = x
            if (x > right) right = x
        }
        return sum / mass / size to (left + right) / 2.0 / size
    }

    @Test
    fun `a play glyph's weight sits in the middle of its box`() {
        for (icon in listOf(TmIcons.Play, TmIcons.PlaySolid, TmIcons.PlaySelected.body)) {
            val (weight, bounds) = weightX(icon)
            assertTrue("${icon.name}: weight at $weight of the width", abs(weight - 0.5) < 0.03)
            // Its bounds right of the middle: the nudge is there.
            assertTrue("${icon.name}: bounds centred at $bounds", bounds > 0.52)
        }
    }
}
