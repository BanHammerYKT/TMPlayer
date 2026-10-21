package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * What the viewer chose in Settings, under Appearance.
 *
 * Stored by name rather than by ordinal, so reordering this list cannot silently move everybody's
 * setting to whichever entry now sits at their old number.
 *
 * @property label the one word shown in a segment, short enough that three fit across a phone
 * @property description the sentence under the picker, also read out by a screen reader
 */
enum class ThemeChoice(private val labelText: () -> String, private val descriptionText: () -> String) {
    /** Whatever the phone itself is set to, which is what almost everybody wants. */
    System({ L.themeSystem }, { L.themeSystemDetail }),
    Light({ L.themeLight }, { L.themeLightDetail }),
    Dark({ L.themeDark }, { L.themeDarkDetail }),
    ;

    /** Whether this choice is drawn dark whatever the device says: Dark. */
    val alwaysDark: Boolean get() = this == Dark

    /**
     * Whether the app is drawn dark under this choice, with [systemDark] the device's own setting
     * (a television, which has none, passes true).
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        Light -> false
        Dark -> true
        System -> systemDark
    }

    /**
     * Whether the Pure black switch is offered under this choice: wherever the app can be dark,
     * so System and Dark. Light hides it, since it changes nothing there.
     */
    val offersPureBlack: Boolean get() = this != Light

    /** The one word shown in a segment, in the UI language. */
    val label: String get() = labelText()

    /** The sentence under the picker, in the UI language. */
    val description: String get() = descriptionText()

    /**
     * The same sentence for a television, which has no system setting to match.
     *
     * Android TV has no light mode of its own, so "System" cannot mean "follow the device" there.
     * It means the dark panel, which is the right default for a screen watched in the evening.
     */
    val tvDescription: String
        get() = when (this) {
            System -> L.themeSystemTvDetail
            Light -> L.themeLightDetail
            Dark -> L.themeDarkDetail
        }

    companion object {
        val Default = System

        /**
         * The name Black was stored under while it was a fourth choice (until 2026-10-19). It now
         * reads as [Dark] with the Pure black switch on; see [SettingsStore.pureBlack].
         */
        const val LEGACY_BLACK = "Black"

        fun from(stored: String?): ThemeChoice =
            if (stored == LEGACY_BLACK) Dark else entries.firstOrNull { it.name == stored } ?: Default
    }
}

/**
 * Whether the app is drawn on true black: the Pure black switch is on and the app is dark, from
 * [choice] or, under System, from the device ([systemDark]). Off in a light theme, whatever the
 * switch says, so turning Light back to System on a dark phone brings the black back.
 */
fun pureBlackGround(choice: ThemeChoice, pureBlack: Boolean, systemDark: Boolean): Boolean =
    pureBlack && choice.isDark(systemDark)

/**
 * How large the whole interface is drawn, under Appearance as "Interface size" (CP59).
 *
 * The [factor] multiplies the screen's density at the root of every composition, so a dp and an sp
 * both grow by it and every layout keeps its proportions. The system's own font scale is left as it
 * is and still applies on top: somebody with large text set on the device gets it at every size
 * here too. Stored by name, like [ThemeChoice].
 *
 * The video itself is never scaled: the picture fills the screen whatever is chosen, and only the
 * controls and panels over it follow.
 */
enum class UiScale(val factor: Float, private val labelText: () -> String) {
    Smaller(0.9f, { L.interfaceSizeSmaller }),
    Default(1f, { L.interfaceSizeDefault }),
    Larger(1.15f, { L.interfaceSizeLarger }),
    Largest(1.3f, { L.interfaceSizeLargest }),
    ;

    /** The word shown on the row and in the segments, in the UI language. */
    val label: String get() = labelText()

    /** The size [direction] steps away (-1 smaller, +1 larger), or this one at either end. */
    fun step(direction: Int): UiScale = entries.getOrNull(ordinal + direction) ?: this

    companion object {
        fun from(stored: String?, unchosen: UiScale = Default): UiScale = entries.firstOrNull { it.name == stored } ?: unchosen

        /**
         * The desktop's size for a viewer who never chose one, from the screen the window opens on,
         * [widthDp] by [heightDp] in the screen's own units (pixels over the system's scaling):
         * the largest size that still leaves [ROOM_WIDTH] by [ROOM_HEIGHT] for a maximized window,
         * which is Home with its carousel and a row of cards under it, the bar and the player's
         * controls with room around them. A 1920 by 1080 screen at 100 per cent (or a 4K one at 200)
         * gets Largest, 1536 by 864 (1080p at 125) and 1440 by 900 Larger, 1366 by 768 and smaller
         * Default.
         */
        fun forScreen(widthDp: Int, heightDp: Int): UiScale =
            listOf(Largest, Larger).firstOrNull { widthDp / it.factor >= ROOM_WIDTH && heightDp / it.factor >= ROOM_HEIGHT } ?: Default

        /** What [forScreen] leaves a maximized window at the size it picks. */
        const val ROOM_WIDTH = 1200
        const val ROOM_HEIGHT = 680
    }
}

/**
 * The app's accent colour, under Appearance as a row of swatches: the colour of buttons, the
 * focus fill, switches, progress and the selected tab. The ground, the surfaces and the text stay
 * the theme's own. Stored by name, like [ThemeChoice].
 *
 * [Telegram] is the default and is exactly the palette the app always had. The rest are tuned for
 * a video app on a dark and a light ground, and [Monochrome] is the one with no hue at all. On an
 * Android phone that takes its colours from the wallpaper, the wallpaper wins.
 *
 * @property label the colour's name, for a screen reader and a desktop tooltip only: the swatches
 * show no words
 */
enum class Accent(private val labelText: () -> String) {
    Telegram({ L.accentTelegram }),
    Red({ L.accentRed }),
    Orange({ L.accentOrange }),
    Amber({ L.accentAmber }),
    Emerald({ L.accentEmerald }),
    Teal({ L.accentTeal }),
    Violet({ L.accentViolet }),
    Pink({ L.accentPink }),
    Monochrome({ L.accentMonochrome }),
    ;

    val label: String get() = labelText()

    companion object {
        val Default = Telegram

        fun from(stored: String?): Accent = entries.firstOrNull { it.name == stored } ?: Default
    }
}
