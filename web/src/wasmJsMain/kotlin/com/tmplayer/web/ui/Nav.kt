package com.tmplayer.web.ui

import androidx.compose.runtime.mutableStateListOf
import com.tmplayer.web.data.ChatListKey
import com.tmplayer.web.js.TmHost

/** A screen of the signed in app. */
sealed interface Route {
    data object Home : Route
    data class Chats(val list: ChatListKey = ChatListKey.Main) : Route
    data class Chat(val id: Long) : Route
    data object Search : Route
    data object Settings : Route
    data object Watched : Route
}

/**
 * The back stack, kept in step with the browser's history: every screen pushed is a history
 * entry, so the browser's Back (and an Android phone's back gesture, and the swipe on an iPhone's
 * edge in the installed app) walks it the way the apps' Back does.
 */
class Nav(start: Route) {
    val stack = mutableStateListOf(start)

    val top: Route get() = stack.last()

    init {
        TmHost.onPop { popLocal() }
    }

    fun push(route: Route) {
        if (stack.last() == route) return
        stack.add(route)
        TmHost.pushState(route.toString())
    }

    /** A top level destination: the stack is that one screen again. */
    fun reset(route: Route) {
        if (stack.size == 1 && stack[0] == route) return
        val depth = stack.size - 1
        stack.clear()
        stack.add(route)
        // History cannot be cut; the entries above the first stay and the next Back lands here.
        if (depth == 0) TmHost.pushState(route.toString())
    }

    /** Back from the app (a back arrow): the browser's history moves with it. */
    fun back() {
        if (stack.size > 1) TmHost.back()
    }

    private fun popLocal() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}
