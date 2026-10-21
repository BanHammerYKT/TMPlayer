package com.tmplayer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The update states the shared screens draw, as in the JVM half's Updates.kt. The browser is
 * always on the newest build (the page is the release), so nothing here ever leaves [Idle].
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: Release, val skipped: Boolean = false) : UpdateState
    data class Downloading(val release: Release, val fraction: Float?) : UpdateState
    data class Ready(val release: Release, val file: File) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

val UpdateState.release: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Ready -> release
        is UpdateState.Failed -> release
        else -> null
    }

/** The browser build's updater: a page reload is the update, so it only answers "nothing new". */
object Updates {
    const val RELEASES_PAGE = "github.com/dracu-lah/TMPlayer/releases"

    var enabled: Boolean = false

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    var installedVersion: String = "0"
        private set

    fun configure(installedVersion: String) {
        this.installedVersion = installedVersion
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun check(quiet: Boolean = false, skipped: String = ""): Boolean = false

    fun onMeteredNetwork(): Boolean = false

    fun installFailed(message: String) {
        _state.value = UpdateState.Failed(message, _state.value.release)
    }

    fun refuse(message: String) {
        _state.value = UpdateState.Failed(message, _state.value.release)
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    fun skip() {
        _state.value = UpdateState.Idle
    }
}
