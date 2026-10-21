package com.tmplayer.ui.components

/**
 * What a touch has just done, as the hand should feel it. One of a few kinds of buzz, named for
 * the moment rather than for the motor, so a screen says "a toggle went on" and [Haptics] decides
 * what this phone's Android can play for it (private/HAPTICS-PLAN.md has the audit behind each).
 */
enum class Haptic {
    /** A press on a key-like control that does something at once: a step arrow, a stepper. */
    Tap,

    /** One key of a keypad: the PIN's digits and its delete. */
    KeyTap,

    /** A long press that opened something (a menu, picking mode). */
    LongPress,

    /** Something asked for is done: signed in, a PIN accepted, the screen locked. */
    Confirm,

    /** Something asked for was refused: a wrong code, a wrong PIN, two PINs that differ. */
    Reject,

    /** A switch, a star, a filter chip turned on. */
    ToggleOn,

    /** The same turned off. */
    ToggleOff,

    /** One step crossed: a choice taken in a list, a stepper's value, a pull past refresh. */
    Tick,

    /** A step of a slider being dragged: lighter, since a drag crosses many in a second. */
    FrequentTick,

    /** A gesture took hold: the player's hold for double speed. */
    GestureStart,

    /** That gesture let go. */
    GestureEnd,
}

/**
 * How a [Haptic] is played, and whether it is played at all, as plain functions so the decision
 * can be tested away from a device. The platform half (`HapticsAndroid.kt`, `HapticsDesktop.kt`)
 * only asks these and hands the answer to the view.
 *
 * Who has a motor to play it on: Android phones and tablets. An Android TV box, a Fire TV stick and
 * their remotes have none, so a television plays nothing; a desktop has no haptics API for a mouse
 * or a keyboard, so the desktop build plays nothing either. A gamepad's own vibrator (Android's
 * `InputDevice.getVibratorManager`) is left for later.
 */
object Haptics {
    // android.view.HapticFeedbackConstants, mirrored so the mapping runs in a JVM test. The values
    // are part of the public API and never change (checked against android-37's android.jar).
    const val LONG_PRESS = 0
    const val VIRTUAL_KEY = 1
    const val KEYBOARD_TAP = 3
    const val CLOCK_TICK = 4
    const val TEXT_HANDLE_MOVE = 9
    const val GESTURE_START = 12
    const val GESTURE_END = 13
    const val CONFIRM = 16
    const val REJECT = 17
    const val TOGGLE_ON = 21
    const val TOGGLE_OFF = 22
    const val SEGMENT_TICK = 26
    const val SEGMENT_FREQUENT_TICK = 27

    /** Build.VERSION_CODES, for the same reason. */
    const val API_O_MR1 = 27
    const val API_R = 30
    const val API_U = 34

    /**
     * The constant to play [event] with on Android [sdk], or null for nothing. The newer constants
     * fall back to the nearest older one with a similar weight: a confirm to a key press, a reject
     * to the heavier long press (so it still feels unlike a success), a toggle to a key press when
     * on and a clock tick when off, a slider's frequent tick to the light text handle move.
     */
    fun constant(event: Haptic, sdk: Int): Int? = when (event) {
        Haptic.Tap -> VIRTUAL_KEY
        Haptic.KeyTap -> KEYBOARD_TAP
        Haptic.LongPress -> LONG_PRESS
        Haptic.Confirm -> if (sdk >= API_R) CONFIRM else VIRTUAL_KEY
        Haptic.Reject -> if (sdk >= API_R) REJECT else LONG_PRESS
        Haptic.ToggleOn -> if (sdk >= API_U) TOGGLE_ON else VIRTUAL_KEY
        Haptic.ToggleOff -> if (sdk >= API_U) TOGGLE_OFF else CLOCK_TICK
        Haptic.Tick -> if (sdk >= API_U) SEGMENT_TICK else CLOCK_TICK
        Haptic.FrequentTick -> when {
            sdk >= API_U -> SEGMENT_FREQUENT_TICK
            sdk >= API_O_MR1 -> TEXT_HANDLE_MOVE
            else -> CLOCK_TICK
        }
        Haptic.GestureStart -> if (sdk >= API_R) GESTURE_START else CLOCK_TICK
        // Before R there is no light release, and a second full tick on letting go is noise.
        Haptic.GestureEnd -> if (sdk >= API_R) GESTURE_END else null
    }

    /**
     * Whether to play anything: the viewer's "Vibrate on tap" is on ([enabled], [Micro.haptics]),
     * this is not a television, and the device has a vibrator. The system's own "Touch feedback"
     * setting is honoured on top of this by the platform, which drops the buzz when it is off.
     */
    fun allowed(enabled: Boolean, television: Boolean, hasVibrator: Boolean): Boolean =
        enabled && !television && hasVibrator
}
