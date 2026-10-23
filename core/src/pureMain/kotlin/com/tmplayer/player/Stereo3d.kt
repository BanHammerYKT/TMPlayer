package com.tmplayer.player

/**
 * Watching a 3D video on an ordinary screen: the two pictures a 3D file carries in each frame,
 * one per eye, drawn as one anaglyph for paper glasses, or as one eye's picture alone.
 *
 * A 3D file keeps the eyes side by side (left half and right half) or one above the other, at
 * full size (a 3840x1080 frame, each eye 1920x1080) or squeezed into an ordinary frame (1920x1080,
 * each eye 960x1080 stretched back on playback): what [outputSize] tells apart by the frame's
 * shape, so the picture comes out the shape of one eye either way.
 *
 * The colours are Eric Dubois' least squares anaglyphs, as FFmpeg's `stereo3d` filter carries them
 * (`arcd`, `agmd`, `aybd`): less ghosting and truer colour than taking one channel from each eye.
 * The player menu's page, its choices and their order follow a friend's dashboard player that
 * asked for the feature (DestinyTgRestrict, GPL 3), and Kodi's and VLC's 3D menus.
 *
 * Kept free of any platform so the phone, the television (a Media3 shader) and the desktop (an mpv
 * shader) draw from the same tables, and the rules can be tested without a screen.
 */
data class Stereo3d(
    val layout: Layout = Layout.Off,
    val view: View = View.RedCyan,
    /** For a file stored right eye first: a red lens that sees the wrong eye shows depth inside out. */
    val swapEyes: Boolean = false,
) {
    enum class Layout { Off, SideBySide, TopBottom }

    /** How the two eyes are shown. */
    enum class View(val key: String) {
        RedCyan("rc"),
        GreenMagenta("gm"),
        YellowBlue("yb"),

        /** One eye's picture alone: a 3D video watched flat, with no glasses. */
        OneEye("2d"),
        ;

        companion object {
            fun ofKey(key: String?): View = entries.firstOrNull { it.key == key } ?: RedCyan
        }
    }

    val on: Boolean get() = layout != Layout.Off

    companion object {
        val OFF = Stereo3d()

        /**
         * The 3D layout a file's name or caption declares, or null for none. The tags release
         * names use, as Kodi reads them: SBS, HSBS, Half-SBS, Full-SBS, TAB, HTAB, OU, HOU and
         * the words spelled out. OU on its own is a French word ("or"), and TB or HTB (top and
         * bottom) also reads as a size, so either counts only beside a 3D.
         */
        fun detect(name: String): Layout? {
            val text = name.lowercase()
            if (SIDE_BY_SIDE.containsMatchIn(text)) return Layout.SideBySide
            if (TOP_BOTTOM.containsMatchIn(text)) return Layout.TopBottom
            if (THREE_D.containsMatchIn(text) && (OVER_UNDER.containsMatchIn(text) || TB.containsMatchIn(text))) return Layout.TopBottom
            return null
        }

        /**
         * Whether a frame holds both eyes at full size: twice as wide as a picture would be (side
         * by side) or twice as tall (top and bottom). Otherwise each eye is squeezed into half the
         * frame, the common release, and is stretched back to the whole frame's shape.
         */
        fun fullSize(width: Int, height: Int, layout: Layout): Boolean {
            if (width <= 0 || height <= 0) return false
            val aspect = width.toFloat() / height
            return when (layout) {
                Layout.Off -> false
                // 3840x1080 is 3.56; a 2.39:1 film squeezed side by side is still 2.39.
                Layout.SideBySide -> aspect >= FULL_SBS_FROM
                // 1920x2160 is 0.89; a squeezed one is the film's own 1.78 or wider.
                Layout.TopBottom -> aspect <= FULL_TAB_UNTIL
            }
        }

        /** The picture's size once the two eyes are drawn as one, for a [width] by [height] frame. */
        fun outputSize(width: Int, height: Int, layout: Layout): Pair<Int, Int> = when {
            layout == Layout.Off || !fullSize(width, height, layout) -> width to height
            layout == Layout.SideBySide -> (width / 2).coerceAtLeast(1) to height
            else -> width to (height / 2).coerceAtLeast(1)
        }

        /**
         * Dubois' matrices, FFmpeg's `ana_coeff` divided out of its 16.16 fixed point: three rows
         * (red, green, blue out), each the left eye's red, green and blue weights, then the right's.
         */
        fun matrix(view: View): FloatArray? = when (view) {
            View.RedCyan -> dubois(
                29884, 32768, 11534, -2818, -5767, -131,
                -2621, -2490, -1049, 24773, 48103, -1180,
                -983, -1376, -328, -4719, -7406, 80347,
            )
            View.GreenMagenta -> dubois(
                -4063, -10354, -2556, 34669, 46203, 1573,
                18612, 43778, 9372, -1049, -983, -4260,
                -983, -1769, 1376, 590, 4915, 61407,
            )
            View.YellowBlue -> dubois(
                69599, -13435, 19595, -1048, -8061, -1114,
                -1704, 59507, 4456, 393, 4063, -1114,
                -2490, -11338, 1442, 6160, 12124, 59703,
            )
            View.OneEye -> null
        }

        private fun dubois(vararg fixed: Int): FloatArray = FloatArray(fixed.size) { fixed[it] / 65536f }

        /**
         * The body of a GLSL function that colours one pixel at [pos] (0 to 1 across the output,
         * y as [yDown] says) from [sample], a function of a texture position returning a vec4.
         * The choices are written in as constants, so a change compiles a new shader rather than
         * branching on every pixel of every frame. [texelX] and [texelY] are one source pixel, to
         * keep each eye's samples from bleeding over the seam into the other.
         */
        fun glslBody(stereo: Stereo3d, sample: String, pos: String, yDown: Boolean, texelX: Float, texelY: Float): String {
            val firstIsLeft = !stereo.swapEyes
            // Each eye's own half of the frame. The first stored eye is the left or the top one.
            val (first, second) = when (stereo.layout) {
                Layout.SideBySide -> {
                    val edge = glslFloat(0.5f - texelX / 2)
                    "vec2(min($pos.x * 0.5, $edge), $pos.y)" to "vec2(max(0.5 + $pos.x * 0.5, ${glslFloat(0.5f + texelX / 2)}), $pos.y)"
                }
                else -> {
                    // With y up the top of the picture is the upper half of the texture.
                    val top = if (yDown) "$pos.y * 0.5" else "0.5 + $pos.y * 0.5"
                    val bottom = if (yDown) "0.5 + $pos.y * 0.5" else "$pos.y * 0.5"
                    val topClamp = if (yDown) "min($top, ${glslFloat(0.5f - texelY / 2)})" else "max($top, ${glslFloat(0.5f + texelY / 2)})"
                    val bottomClamp = if (yDown) "max($bottom, ${glslFloat(0.5f + texelY / 2)})" else "min($bottom, ${glslFloat(0.5f - texelY / 2)})"
                    "vec2($pos.x, $topClamp)" to "vec2($pos.x, $bottomClamp)"
                }
            }
            val (left, right) = if (firstIsLeft) first to second else second to first
            val m = matrix(stereo.view)
                ?: return "return vec4($sample($left).rgb, 1.0);"
            fun row(r: Int, eye: Int) = (0 until 3).joinToString(", ") { glslFloat(m[r * 6 + eye * 3 + it]) }
            return buildString {
                append("vec3 l = $sample($left).rgb;\n")
                append("vec3 r = $sample($right).rgb;\n")
                append("vec3 c = vec3(\n")
                append("    dot(vec3(${row(0, 0)}), l) + dot(vec3(${row(0, 1)}), r),\n")
                append("    dot(vec3(${row(1, 0)}), l) + dot(vec3(${row(1, 1)}), r),\n")
                append("    dot(vec3(${row(2, 0)}), l) + dot(vec3(${row(2, 1)}), r));\n")
                append("return vec4(clamp(c, 0.0, 1.0), 1.0);")
            }
        }

        /**
         * mpv's user shader for the desktop: hooked on the picture after it is turned to RGB and
         * before it is scaled, so hardware decoding stays on and the subtitles, drawn after, stay
         * as they are. The output keeps the frame's size; the shape comes from [aspect] instead.
         */
        fun mpvShader(stereo: Stereo3d, width: Int, height: Int): String {
            val texelX = if (width > 0) 1f / width else 0f
            val texelY = if (height > 0) 1f / height else 0f
            return buildString {
                append("//!HOOK MAIN\n")
                append("//!BIND HOOKED\n")
                append("//!DESC TMPlayer 3D\n")
                append("vec4 hook() {\n")
                append(glslBody(stereo, "HOOKED_tex", "HOOKED_pos", yDown = true, texelX = texelX, texelY = texelY).prependIndent("    "))
                append("\n}\n")
            }
        }

        /** The width over height the picture is shown at, for a [width] by [height] frame. */
        fun aspect(width: Int, height: Int, layout: Layout): Double? {
            if (width <= 0 || height <= 0 || layout == Layout.Off) return null
            val (w, h) = outputSize(width, height, layout)
            return w.toDouble() / h
        }

        private fun glslFloat(value: Float): String {
            val text = value.toString()
            return if ('.' in text || 'E' in text || 'e' in text) text else "$text.0"
        }

        private const val FULL_SBS_FROM = 2.9f
        private const val FULL_TAB_UNTIL = 1.2f

        // A tag stands between separators or at either end, never inside a longer word.
        private const val B = "(?<![a-z0-9])"
        private const val E = "(?![a-z0-9])"
        private val SIDE_BY_SIDE = Regex("$B(h|half|f|full)?[-. _]?sbs$E|${B}side[-. _]?by[-. _]?side$E")
        private val TOP_BOTTOM = Regex("$B(h|half|f|full)?[-. _]?tab$E|${B}top[-. _]?(and[-. _]?)?bottom$E|${B}over[-. _]?under$E")
        private val OVER_UNDER = Regex("$B(h|half|f|full)?[-. _]?ou$E")
        private val TB = Regex("$B(h|half|f|full)?[-. _]?tb$E")
        private val THREE_D = Regex("${B}3d$E")
    }
}
