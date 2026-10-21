package com.tmplayer.web

import com.tmplayer.web.data.WebChats
import com.tmplayer.web.data.WebFiles
import com.tmplayer.web.data.WebLookups
import com.tmplayer.web.data.WebPrefs
import com.tmplayer.web.data.WebResume
import com.tmplayer.web.data.WebVideos
import com.tmplayer.web.td.WebAuth
import com.tmplayer.web.td.WebTd
import kotlinx.coroutines.CoroutineScope

/** Everything the page holds for as long as it is open: TDLib, the sign in, the account's data. */
class WebAppState(val scope: CoroutineScope) {
    val prefs = WebPrefs()
    val td = WebTd()
    val auth = WebAuth(td, scope)
    val files = WebFiles(td, scope)
    val chats = WebChats(td, scope, files)
    val videos = WebVideos(td)
    val resume = WebResume(prefs)
    val lookups = WebLookups(td, chats)

    fun start() {
        auth.onReady = { chats.onSignedIn() }
        auth.onSignedOut = { chats.clear() }
        auth.start()
    }
}
