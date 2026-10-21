package com.tmplayer.web.js

import kotlin.js.Promise
import kotlinx.coroutines.await

/** td-bridge.js: the page's TDLib client. */
external object TmTd : JsAny {
    fun start(optionsJson: String, onUpdate: (String) -> Unit)
    fun send(queryJson: String): Promise<JsString>
    fun readBase64(fileId: Int, priority: Int): Promise<JsString>
    fun prefix(fileId: Int, offset: Double): Promise<JsNumber>
    fun close(): Promise<JsAny?>
    val started: Boolean
}

/** web-host.js: the browser features Compose has no word for. */
external object TmHost : JsAny {
    fun ready()
    fun splashNote(text: String)
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
    fun removeItem(key: String)
    fun canPlay(mime: String): String
    fun canSource(mime: String): Boolean
    fun fetchText(url: String): Promise<JsString>
    fun fetchBase64(url: String): Promise<JsString>
    fun fetchJson(url: String, headersJson: String): Promise<JsString>
    fun openUrl(url: String)
    fun copyText(text: String): Promise<JsBoolean>
    fun language(): String
    fun userAgent(): String
    fun deviceKind(): String
    fun platform(): String
    fun prefersDark(): Boolean
    fun setThemeColor(color: String, dark: Boolean)
    fun setTitle(text: String)
    fun pushState(name: String)
    fun onPop(handler: () -> Unit)
    fun back()
    fun persist(): Promise<JsBoolean>
    fun estimate(): Promise<JsString>
    fun notify(title: String, body: String): Boolean
    fun askNotify(): Promise<JsString>
    fun notifyPermission(): String
    fun registerWorker()
    fun online(): Boolean
    fun onOnline(handler: (Boolean) -> Unit)
    fun onVisible(handler: (Boolean) -> Unit)
}

suspend fun Promise<JsString>.text(): String = await<JsString>().toString()
