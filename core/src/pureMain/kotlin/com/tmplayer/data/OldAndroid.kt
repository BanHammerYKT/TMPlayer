package com.tmplayer.data

/**
 * The few decisions about Android 6 and 7 (API 23 to 25) that are more than an API level check.
 * The app installs down to API 23 so that the older televisions (the Fire TV Stick 4K on Fire OS
 * 6 is Android 7.1) can run it; what those lack is left out quietly where it is called, behind a
 * plain `Build.VERSION.SDK_INT` comparison that lint's NewApi check can follow. Pure, so the lines
 * are tested.
 */
object OldAndroid {
    /**
     * Whether the TV provider has the rows the app writes: the home screen's channels and the
     * Play next row, both Android 8's. Android 7's launcher had only the recommendations row,
     * which the app does not fill, so the settings for them are not offered there. Null is a
     * platform that is not Android, where the question is decided elsewhere.
     */
    fun hasTvProviderRows(sdk: Int?): Boolean = sdk == null || sdk >= 26

    /**
     * A notification's priority on Android 7 and older, matched to the importance its channel has
     * from 8.0, so a quiet channel stays quiet there too. The arguments and the answer are the
     * platform's own constants: `NotificationManager.IMPORTANCE_*` in, `Notification.PRIORITY_*`
     * out.
     */
    fun priorityFor(importance: Int): Int = when {
        importance <= IMPORTANCE_MIN -> PRIORITY_MIN
        importance == IMPORTANCE_LOW -> PRIORITY_LOW
        importance == IMPORTANCE_DEFAULT -> PRIORITY_DEFAULT
        else -> PRIORITY_HIGH
    }

    /** Whether a channel of [importance] makes a sound, which before 8.0 the notification asks for. */
    fun soundFor(importance: Int): Boolean = importance >= IMPORTANCE_DEFAULT

    // The platform's values, written out because this file is not Android's. They are part of the
    // public API and have not changed since they were added.
    const val IMPORTANCE_MIN = 1
    const val IMPORTANCE_LOW = 2
    const val IMPORTANCE_DEFAULT = 3
    const val IMPORTANCE_HIGH = 4
    const val PRIORITY_MIN = -2
    const val PRIORITY_LOW = -1
    const val PRIORITY_DEFAULT = 0
    const val PRIORITY_HIGH = 1
}
