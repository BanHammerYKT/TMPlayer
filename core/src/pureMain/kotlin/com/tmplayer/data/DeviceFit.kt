package com.tmplayer.data

import kotlin.concurrent.Volatile

/**
 * Low-end devices (CP68): whether this one is short of memory or storage, and which of the app's
 * own settings would make it lighter. Pure, so the lines are tested; Android and the desktop each
 * read the device into a [DeviceProbe] and hand it here.
 *
 * Nothing is changed by itself. The answer is a list of [Recommendation]s, each one a setting with
 * its own row in Settings, which the setup's step, the Settings card and the one-time hint offer
 * to apply together and Settings can undo one by one.
 */
data class DeviceProbe(
    /** What the system reports as total memory, or null where it cannot be read (the desktop). */
    val totalMemoryBytes: Long? = null,
    /** Android's own verdict, `ActivityManager.isLowRamDevice()`: the 1 GB sticks among others. */
    val lowRamDevice: Boolean = false,
    /** Free space where the app keeps videos, or null where it cannot be read. */
    val freeStorageBytes: Long? = null,
    /** Android's API level, or null on the desktop. */
    val sdk: Int? = null,
    /** True when the device runs only 32-bit code: no 64-bit ABI at all, as on the Mi TV stick. */
    val only32Bit: Boolean = false,
    val tv: Boolean = false,
)

/** What the probe says about the device. */
data class DeviceFit(val shortOfMemory: Boolean, val shortOfStorage: Boolean) {
    val any: Boolean get() = shortOfMemory || shortOfStorage

    companion object {
        val Fine = DeviceFit(shortOfMemory = false, shortOfStorage = false)
    }
}

/** One setting the app recommends for a device short of memory or storage. */
enum class Recommendation {
    /** Low storage mode on: a stream keeps a rolling window on the disk, not the whole file. */
    LowStorageMode,

    /** Thumbnails while seeking off: no second decoder running beside the video. */
    NoSeekThumbnails,

    /** Lighter pictures on: a smaller picture memory, posters decoded smaller, no blur behind art. */
    LighterPictures,

    /** Reduce motion on: pictures and panels appear at once, the loading shimmer stands still. */
    ReduceMotion,
}

/** The settings [Recommendation]s are about, as they are now. */
data class LightSettings(
    val lowStorageMode: Boolean,
    val seekThumbnails: Boolean,
    val lighterPictures: Boolean,
    val reduceMotion: Boolean,
)

object DeviceFitRules {

    /**
     * Total memory under which a phone counts as short: anything sold as 2 GB or less, which
     * reports a little under 2 GiB. A television is sold with less and asks less of it (no
     * second app beside it), so its line is 1.5 GiB: the 1 GB and 1.5 GB sticks.
     */
    const val PHONE_MEMORY_BYTES = 2L * 1024 * 1024 * 1024
    const val TV_MEMORY_BYTES = 3L * 512 * 1024 * 1024

    /**
     * Free space under which storage counts as short: about three films of the size Telegram
     * channels share, which is when Low storage mode starts to matter.
     */
    const val STORAGE_BYTES = 3L * 1024 * 1024 * 1024

    /** Android 8.0: older devices are both slower and short of the decoders this app leans on. */
    const val MIN_COMFORTABLE_SDK = 26

    fun assess(probe: DeviceProbe): DeviceFit {
        val memoryLine = if (probe.tv) TV_MEMORY_BYTES else PHONE_MEMORY_BYTES
        val memory = probe.lowRamDevice ||
            (probe.totalMemoryBytes != null && probe.totalMemoryBytes in 1 until memoryLine) ||
            (probe.sdk != null && probe.sdk < MIN_COMFORTABLE_SDK) ||
            probe.only32Bit
        val storage = probe.freeStorageBytes != null && probe.freeStorageBytes in 0 until STORAGE_BYTES
        return DeviceFit(shortOfMemory = memory, shortOfStorage = storage)
    }

    /**
     * What is worth changing on a device that [fit] describes, given the settings [now]: only
     * what is still off. [seekThumbnailsShown] is false where the setting does nothing anyway
     * (the device is below [Trickplay]'s own memory line, or has no such setting, the desktop),
     * so the list never offers a change nobody would notice. [pictures] is false where the app
     * has no Lighter pictures or Reduce motion rows (the desktop).
     */
    fun recommendations(
        fit: DeviceFit,
        now: LightSettings,
        seekThumbnailsShown: Boolean,
        pictures: Boolean = true,
    ): List<Recommendation> = buildList {
        if (fit.shortOfStorage && !now.lowStorageMode) add(Recommendation.LowStorageMode)
        if (fit.shortOfMemory) {
            if (seekThumbnailsShown && now.seekThumbnails) add(Recommendation.NoSeekThumbnails)
            if (pictures && !now.lighterPictures) add(Recommendation.LighterPictures)
            if (pictures && !now.reduceMotion) add(Recommendation.ReduceMotion)
        }
    }
}

/**
 * The verdict the screenshot fixture and a debug build can force (`--ez lowend true`), so the
 * setup step, the Settings card and the hint can be seen on a device that is not low-end. Nothing
 * in a release build sets it.
 */
object DeviceFitOverride {
    @Volatile
    var forced: DeviceFit? = null
}

/**
 * Raised when the device shows it is struggling while in use: the player dropping frames, or the
 * system asking the app to give memory back. The shell answers with the one-time hint offering
 * the recommended settings; once per install (`SettingsStore.deviceHintShown`).
 */
object DeviceFitHint {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(false)

    val raised: kotlinx.coroutines.flow.StateFlow<Boolean> = state

    fun raise() {
        state.value = true
    }

    fun clear() {
        state.value = false
    }
}
