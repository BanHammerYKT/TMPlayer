package com.tmplayer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One tab at a time (CP92). tdweb lets one client per origin open the account's database: a
 * newer tab's client closes the older one's. [Td] answers any Closed with a new client, which in
 * the older tab would close the newer one's in turn, and the two tabs would take the database from
 * each other for as long as both were open. So a close nobody here asked for (no logOut, no close
 * sent) is the other tab's, and the client after it waits ([elsewhere]) until the viewer chooses
 * this tab again ([useHere]). Nothing is logged out: the session stays in the browser's storage,
 * and a reload or a second tab never signs anybody out.
 */
object TdTabs {
    private val _elsewhere = MutableStateFlow(false)

    /** True while another tab holds the account. */
    val elsewhere: StateFlow<Boolean> = _elsewhere.asStateFlow()

    /** Set by the engine: starts tdweb for the client that is waiting. */
    internal var startWaiting: (() -> Unit)? = null

    internal fun takenElsewhere() {
        _elsewhere.value = true
    }

    /** This tab takes the account back; the other tab's client closes, and it waits in turn. */
    fun useHere() {
        if (!_elsewhere.value) return
        _elsewhere.value = false
        startWaiting?.invoke()
    }
}
