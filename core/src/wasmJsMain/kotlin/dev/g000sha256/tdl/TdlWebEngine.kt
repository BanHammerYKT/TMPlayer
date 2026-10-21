package dev.g000sha256.tdl

import dev.g000sha256.tdl.dto.Update
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

    fun createClientId(): Int {
        val id = ++clientIds
        current = id
        if (!tmTdStarted()) {
            tmTdStart("""{"instanceName":"tmplayer","jsLogVerbosityLevel":"error","logVerbosityLevel":1}""") { json ->
                val dto = runCatching { deserializer.deserialize(json = json).third }.getOrNull()
                if (dto is Update) updates.tryEmit(current to dto)
            }
        }
        return id
    }

    fun getUpdates(clientId: Int): Flow<Update> =
        updates.mapNotNull { (id, update) -> if (id == clientId) update else null }

    suspend fun <F : Any> send(function: F, clientId: Int): Any {
        val requestId = ++requestIds
        val json = serialize(function = function, requestId = requestId)
        val answer = tmTdSend(json).await<JsString>().toString()
        return deserializer.deserialize(json = answer).third
    }
}

private val engine by lazy { TdlEngine(deserializer = TdlDeserializer()) }

internal fun createClient(): TdlClient = TdlClientImpl(repository = TdlRepository(engine = engine))
