package com.tmplayer.data

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OldAndroid writes the platform's notification constants out because the shared core cannot see
 * android.jar. These are compile-time constants, so this compares them with the real ones.
 */
class OldAndroidConstantsTest {

    @Test
    fun `the importances and priorities are the platform's own`() {
        assertEquals(NotificationManager.IMPORTANCE_MIN, OldAndroid.IMPORTANCE_MIN)
        assertEquals(NotificationManager.IMPORTANCE_LOW, OldAndroid.IMPORTANCE_LOW)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, OldAndroid.IMPORTANCE_DEFAULT)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, OldAndroid.IMPORTANCE_HIGH)
        @Suppress("DEPRECATION")
        run {
            assertEquals(Notification.PRIORITY_MIN, OldAndroid.PRIORITY_MIN)
            assertEquals(Notification.PRIORITY_LOW, OldAndroid.PRIORITY_LOW)
            assertEquals(Notification.PRIORITY_DEFAULT, OldAndroid.PRIORITY_DEFAULT)
            assertEquals(Notification.PRIORITY_HIGH, OldAndroid.PRIORITY_HIGH)
        }
    }
}
