package dev.g000sha256.tdl

import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.Update
import dev.g000sha256.tdl.dto.UpdateAuthorizationState
import dev.g000sha256.tdl.serialization.serialize
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull

// tdl-coroutines in the browser (CP92). The library's own common code (its DTOs, its JSON
// serializers, TdlClient and TdlRepository) is compiled for wasmJs from its published sources
// (core/build.gradle.kts, fetchTdlSources); this file stands in for the three files that reach
// TDLib's native JSON interface on the JVM (TdlEngine, TdlNative, TdlServiceLocator), and speaks
// to tdweb in the page instead, through web/.../td-bridge.js (window.TmTd). So :core's Td and
// ChatRepository, and every screen on them, run in the browser unchanged.

@JsFun("(options, onUpdate) => window.TmTd.start(options, onUpdate)")
private external fun tmTdStart(options: String, onUpdate: (String) -> Unit)

@JsFun("(query) => window.TmTd.send(query)")
private external fun tmTdSend(query: String): Promise<JsString>

@JsFun("() => !!(window.TmTd && window.TmTd.started)")
private external fun tmTdStarted(): Boolean

internal class TdlEngine(private val deserializer: TdlDeserializer) {

    private var requestIds = 0L
    private var clientIds = 0
    private var current = 0

    /** Updates, each tagged with the client it belongs to: tdweb holds one client at a time. */
    private val updates = MutableSharedFlow<Pair<Int, Update>>(extraBufferCapacity = Int.MAX_VALUE)

    /** A close or a sign out this page asked for; any other Closed is another tab's (TdTabs). */
    private var closeAsked = false

    fun createClientId(): Int {
        val id = ++clientIds
        current = id
        // After another tab took the account, the next client waits for "Use it here" rather
        // than taking it straight back, which would close that tab's client in turn.
        if (com.tmplayer.data.TdTabs.elsewhere.value) {
            com.tmplayer.data.TdTabs.startWaiting = { startTdweb() }
        } else {
            startTdweb()
        }
        return id
    }

    private fun startTdweb() {
        if (tmTdStarted()) return
        closeAsked = false
        tmTdStart("""{"instanceName":"tmplayer","jsLogVerbosityLevel":"error","logVerbosityLevel":1}""") { json ->
            val dto = runCatching { deserializer.deserialize(json = json).third }.getOrNull()
            if (dto is UpdateAuthorizationState && dto.authorizationState is AuthorizationStateClosed) {
                if (!closeAsked) com.tmplayer.data.TdTabs.takenElsewhere()
                closeAsked = false
            }
            if (dto is Update) updates.tryEmit(current to dto)
        }
    }

    fun getUpdates(clientId: Int): Flow<Update> =
        updates.mapNotNull { (id, update) -> if (id == clientId) update else null }

    suspend fun <F : Any> send(function: F, clientId: Int): Any {
        val requestId = ++requestIds
        if (function is dev.g000sha256.tdl.function.Close || function is dev.g000sha256.tdl.function.LogOut) closeAsked = true
        val json = serialize(function = function, requestId = requestId)
        val answer = tmTdSend(json).await<JsString>().toString()
        return try {
            deserializer.deserialize(json = answer).third
        } catch (e: Throwable) {
            // tdweb's TDLib can be a few versions behind the schema tdl-coroutines was generated
            // from; a field it does not send yet is named here, with what was asked.
            println("TMPlayer: could not read TDLib's answer to ${function::class.simpleName}: ${e.message}: ${answer.take(400)}")
            throw e
        }
    }
}

private val engine by lazy { TdlEngine(deserializer = TdlDeserializer()) }

internal fun createClient(): TdlClient = TdlClientImpl(repository = TdlRepository(engine = engine))
