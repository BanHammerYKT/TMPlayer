package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeCache
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaItem
import com.tmplayer.data.NewEpisode
import com.tmplayer.data.NewEpisodes
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShowReached
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import com.tmplayer.data.WatchedRecord
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import com.tmplayer.data.WatchLaterRecord
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import com.tmplayer.data.batched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Where Home's videos come from: Telegram in the app, a fixture in the promo build and the tests. */
interface HomeSource {
    /** A starred chat's newest videos, newest first. */
    suspend fun chatMedia(chatId: Long): List<MediaItem>

    /**
     * [chatMedia], and whether that reached the chat's oldest video. A source that cannot tell says
     * it did, so a row only offers "See all" for what it can show is there.
     */
    suspend fun chatMediaPage(chatId: Long): Pair<List<MediaItem>, Boolean> = chatMedia(chatId) to true

    /** The newest videos across every chat. */
    suspend fun recentMedia(): List<MediaItem>

    /** The video behind a Continue watching record, for its picture; null when Telegram has no answer. */
    suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem?

    /** Whether [item]'s file is whole on this device now, rather than when it was fetched. */
    suspend fun wholeOnDevice(item: MediaItem): Boolean = item.onDevice

    /** The chats whose videos changed while Home is open, a window at a time. None by default. */
    fun changedChats(): Flow<Set<Long>> = emptyFlow()

    /** Asks for [chatId]'s new posts as they happen until the handle is closed; null where nothing follows them. */
    suspend fun follow(chatId: Long): AutoCloseable? = null

    /**
     * A show's newest videos in [chatId], found by [query] and kept to the forum topic [messageId]
     * (an episode the viewer watched) was posted in, newest first: one small search, for New
     * episodes. None by default.
     */
    suspend fun showMedia(chatId: Long, messageId: Long, query: String): List<MediaItem> = emptyList()
}

/** The signed in account, through TDLib. */
object TdHomeSource : HomeSource {
    private suspend fun repository() = ChatRepository(Td.awaitAuthorizedSession().client)

    override suspend fun chatMedia(chatId: Long): List<MediaItem> =
        repository().mediaPage(chatId, pageSize = CHAT_PAGE).items

    override suspend fun chatMediaPage(chatId: Long): Pair<List<MediaItem>, Boolean> =
        repository().mediaPage(chatId, pageSize = CHAT_PAGE).let { it.items to it.endReached }

    override suspend fun recentMedia(): List<MediaItem> = repository().recentMedia()

    override suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? =
        repository().mediaItem(chatId, messageId)

    override suspend fun wholeOnDevice(item: MediaItem): Boolean =
        Td.localFileAvailability(item.fileId) == LocalFileAvailability.Complete

    override fun changedChats(): Flow<Set<Long>> =
        // Not for every message edited into text: a busy group edits all day, and each would be a
        // search of that chat. A row showing a video edited away catches up at the next refresh.
        Td.videoChanges.filterNot { it is com.tmplayer.data.VideoChange.Removed && it.byEdit }.batched(LIVE_WINDOW_MS).map { batches -> batches.mapTo(HashSet()) { it.chatId } }

    override suspend fun follow(chatId: Long): AutoCloseable? {
        Td.awaitAuthorizedSession()
        return Td.watchChat(chatId)
    }

    override suspend fun showMedia(chatId: Long, messageId: Long, query: String): List<MediaItem> {
        val repository = repository()
        // In a group with topics the show is looked for in its own topic, as the player does, or
        // another section's "Lecture 12" would pass for the next one of this course.
        val topic = runCatching { repository.topicOf(chatId, messageId) }.getOrDefault(0)
        return repository.mediaPage(chatId, query = query, pageSize = SHOW_PAGE, topicId = topic).items
    }

    /** Per search, for a show in progress: what arrived since a visit is the newest handful. */
    private const val SHOW_PAGE = 10

    /** Longer than a grid's: a row fetched again is a search, and Home has several rows to keep. */
    private const val LIVE_WINDOW_MS = 3_000L

    /**
     * Per search, three searches a chat: room for a row of ten after the size limits and a show
     * folding several episodes into one tile, and a fraction of a full grid page.
     */
    private const val CHAT_PAGE = 20
}

/**
 * Home's videos, fetched a row at a time as the rows come on screen.
 *
 * Nothing is asked for up front: a row asks with [request] when the lazy list first composes it,
 * so somebody with thirty starred chats pays for the three on screen, and a 1 GB television is
 * never holding thirty pages of thumbnails at once. At most [PARALLEL] fetches run together.
 *
 * What arrives is kept for the life of the screen, so scrolling back up does not ask again;
 * [refresh] starts over.
 */
class HomeViewModel(
    private val source: HomeSource = TdHomeSource,
    /** The size limits from Settings, applied as in a chat's grid; read per fetch so a change lands on refresh. */
    private val sizeLimits: suspend () -> Pair<Long, Long> = { SizeFilter.DEFAULT_MIN to SizeFilter.DEFAULT_MAX },
    /** Where the shows in progress and their new episodes are worked out: off the main thread. */
    private val work: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
    /**
     * Whether Home is drawn from [HomeCache] and written back to it. Off in the tests and the
     * promo build, whose sources are fixtures.
     */
    private val cached: Boolean = false,
) : ViewModel() {

    private val _loaded = MutableStateFlow<Map<Long, List<MediaItem>>>(emptyMap())

    /** Each starred chat's videos, once fetched. A chat missing here has not answered yet. */
    val loaded: StateFlow<Map<Long, List<MediaItem>>> = _loaded.asStateFlow()

    private val _partial = MutableStateFlow<Set<Long>>(emptySet())

    /** The starred chats whose fetch stopped short of their oldest video: they have more than Home holds. */
    val partial: StateFlow<Set<Long>> = _partial.asStateFlow()

    private val _recent = MutableStateFlow<List<MediaItem>?>(null)

    /** The newest videos across every chat, or null until they arrive. */
    val recent: StateFlow<List<MediaItem>?> = _recent.asStateFlow()

    private val _art = MutableStateFlow<Map<String, MediaItem>>(emptyMap())

    /** Continue watching and Watch later videos as Telegram describes them, by progress key, for their pictures. */
    val art: StateFlow<Map<String, MediaItem>> = _art.asStateFlow()

    private val gate = Semaphore(PARALLEL)
    private val asked = HashSet<String>()
    private val jobs = mutableListOf<Job>()

    /** The starred chats held open in TDLib for their new posts, as their rows were fetched. */
    private val following = LinkedHashMap<Long, AutoCloseable>()

    /** The shows in progress, and every video the viewer has watched or started, by progress key. */
    private class Tracked(val shows: List<ShowReached>, val touched: Set<String>)

    /** Everything the New episodes row is drawn from, gathered for one pass of [NewEpisodes.collect]. */
    private class NewInputs(
        val tracked: Tracked,
        val found: Map<String, List<MediaItem>>,
        val loaded: Map<Long, List<MediaItem>>,
        val recent: List<MediaItem>?,
    ) {
        fun newEpisodes(): List<NewEpisode> {
            val pools = tracked.shows.associate { show ->
                val searched = found[show.key].orEmpty()
                // A search kept to a forum topic says which topic the show is in; the rows' videos
                // join only from that topic, or from a chat without topics when nothing says.
                val topic = searched.firstOrNull { it.topicId != 0 }?.topicId ?: 0
                val alongside = (loaded[show.chatId].orEmpty() + recent.orEmpty().filter { it.chatId == show.chatId })
                    .filter { it.topicId == topic }
                show.key to searched + alongside
            }
            return NewEpisodes.collect(tracked.shows, pools) { SettingsStore.progressKey(it.chatId, it.messageId) in tracked.touched }
        }
    }

    private val tracked = MutableStateFlow(Tracked(emptyList(), emptySet()))

    /** What each show's search found, by [ShowReached.key], within the size limits. */
    private val found = MutableStateFlow<Map<String, List<MediaItem>>>(emptyMap())

    private val _newEpisodes = MutableStateFlow<List<NewEpisode>>(emptyList())

    /** The New episodes row's tiles, empty until a show in progress turns one up. */
    val newEpisodes: StateFlow<List<NewEpisode>> = _newEpisodes.asStateFlow()

    private var showsJob: Job? = null

    /**
     * The rows whose last fetch failed or ran past [ROW_TIMEOUT_MS], asked for again once Telegram
     * is reachable ([retryFailed]): a row lost to a dropped line comes back with the line, rather
     * than staying gone, or a skeleton waiting for good, until the next launch. Declared ahead of
     * `init`, whose collector of [Td.connected] can call [retryFailed] before the constructor ends.
     */
    private val failedChats = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /** The chats whose grid Home has prefetched this run, at most [PREFETCH_CHATS] of them. */
    private val prefetched = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    @Volatile private var recentFailed = false

    private val _refreshing = MutableStateFlow(false)

    /** True while a refresh in place is fetching: the refresh control turns, the tiles stay up. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** When the rows were last brought up to date from Telegram, in elapsed real time; 0 for never. */
    @Volatile private var refreshedAt = 0L

    private val created = System.nanoTime()
    @Volatile private var firstFresh = false

    init {
        // Home as it was last time, drawn on the first frame (no placeholder strips), then
        // brought up to date in place once Telegram answers: see [restore].
        if (cached) restore()
        // A show's new episodes are worked out again whenever anything they are drawn from moves:
        // a search landing, a starred chat's row or the recent row arriving or refreshing (which
        // is how a post in a starred chat shows up here without a search of its own), or the
        // viewer watching something. Off the main thread, and only the latest answer counts.
        viewModelScope.launch {
            combine(tracked, found, _loaded, _recent) { tracked, found, loaded, recent ->
                NewInputs(tracked, found, loaded, recent)
            }.collectLatest { inputs ->
                if (inputs.tracked.shows.isEmpty()) {
                    _newEpisodes.value = emptyList()
                    return@collectLatest
                }
                _newEpisodes.value = withContext(work) { inputs.newEpisodes() }
            }
        }
        // The line to Telegram back: the rows it cost are fetched again.
        viewModelScope.launch {
            Td.connected.collect { connected -> if (connected) retryFailed() }
        }
        // A post in a starred chat refreshes its row, and the recent row that draws from those
        // chats, in place: the old tiles stay up until the new ones arrive.
        viewModelScope.launch {
            source.changedChats().collect { chats ->
                // A post in the chat of a show in progress asks for that show again, past its cache.
                tracked.value.shows.filter { it.chatId in chats }.forEach { show ->
                    showCache.remove(show.key)
                    asked -= "show:${show.key}"
                    requestShow(show)
                }
                val rows = chats.filter { it in _loaded.value }
                if (rows.isEmpty()) return@collect
                rows.forEach { chatId ->
                    asked -= "chat:$chatId"
                    request(chatId)
                }
                if (_recent.value != null) {
                    asked -= "recent"
                    requestRecent()
                }
            }
        }
    }

    /**
     * Fills the rows from [HomeCache], and asks Telegram for each of them again, in place: what
     * was drawn stays up until its fresh copy lands, so nothing blinks, moves or loses focus.
     * The cache is written back a moment after the rows settle ([persist]).
     */
    private fun restore() {
        val snapshot = HomeCache.load()
        if (snapshot != null && !snapshot.isEmpty) {
            _loaded.value = snapshot.loaded
            _recent.value = snapshot.recent
            _partial.value = snapshot.partial
            _art.value = snapshot.art
            snapshot.art.values.forEach(RecordItems::keep)
            com.tmplayer.data.PerfLog.mark("home rows from cache (${snapshot.loaded.size} chats)", com.tmplayer.data.PerfLog.processStart)
            // Written moments ago (the desktop builds Home again on every visit): good as it is.
            val age = System.currentTimeMillis() - snapshot.savedAt
            if (age in 0 until STALE_MS) {
                refreshedAt = System.nanoTime() / 1_000_000 - age
                snapshot.loaded.keys.forEach { asked += "chat:$it" }
                if (snapshot.recent != null) asked += "recent"
            } else {
                refreshInPlace()
            }
        }
        viewModelScope.launch {
            combine(_loaded, _recent, _partial, _art) { loaded, recent, partial, art ->
                HomeCache.Snapshot(loaded, recent, partial, art, System.currentTimeMillis())
            }.collectLatest { snapshot ->
                // Settled, not mid-refresh: a row at a time arriving should not write ten times.
                kotlinx.coroutines.delay(PERSIST_AFTER_MS)
                if (snapshot.isEmpty) return@collectLatest
                withContext(Dispatchers.IO) { HomeCache.save(snapshot) }
            }
        }
    }

    /**
     * Asks Telegram for every row Home holds again, keeping the tiles up meanwhile: the pull to
     * refresh, the television's Refresh, coming back to the app, and [AUTO_REFRESH_MS] while Home
     * is open. Rows answer one by one and replace their own tiles only, keyed as before, so the
     * list does not jump and the remote stays on its tile.
     */
    fun refreshInPlace() {
        refreshedAt = (System.nanoTime() / 1_000_000)
        // Every chat with a row, the ones still on their way and the ones that failed included.
        val chats = (_loaded.value.keys + asked.filter { it.startsWith("chat:") }.mapNotNull { it.removePrefix("chat:").toLongOrNull() } +
            synchronized(failedChats) { failedChats.toList() }).distinct()
        _refreshing.value = true
        chats.forEach { chatId ->
            asked -= "chat:$chatId"
            request(chatId)
        }
        asked -= "recent"
        requestRecent()
        viewModelScope.launch {
            // Done when every fetch asked above has come back.
            jobs.toList().forEach { it.join() }
            _refreshing.value = false
        }
    }

    /** [refreshInPlace] when the rows are older than [STALE_MS]: for the app coming back to the front. */
    fun refreshIfStale() {
        val at = refreshedAt
        if (at == 0L || (System.nanoTime() / 1_000_000) - at > STALE_MS) refreshInPlace()
    }

    /** Asks again for every row whose last fetch failed: see [failedChats]. */
    fun retryFailed() {
        val chats = synchronized(failedChats) { failedChats.toList().also { failedChats.clear() } }
        chats.forEach { chatId ->
            asked -= "chat:$chatId"
            request(chatId)
        }
        if (recentFailed && _recent.value != null) {
            asked -= "recent"
            requestRecent()
        }
    }

    override fun onCleared() {
        following.values.forEach { runCatching { it.close() } }
        following.clear()
    }

    /** Follows [chatId]'s new posts while Home is open, for as many rows as [FOLLOW_MAX] allows. */
    private fun follow(chatId: Long) {
        if (chatId in following || following.size >= FOLLOW_MAX) return
        viewModelScope.launch {
            val handle = runCatching { source.follow(chatId) }.getOrNull() ?: return@launch
            if (chatId in following || following.size >= FOLLOW_MAX) handle.close() else following[chatId] = handle
        }
    }

    /** Fetches a starred chat's row, once (again after a post in it). */
    fun request(chatId: Long) = once("chat:$chatId") {
        follow(chatId)
        val (items, ended) = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(ROW_TIMEOUT_MS) { source.chatMediaPage(chatId) }
                ?: throw java.io.IOException("Home row timed out")
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            failedChats += chatId
            emptyList<MediaItem>() to true
        }
        if (items.isNotEmpty()) failedChats -= chatId
        // A failed refresh keeps the tiles it had rather than emptying the row.
        if (items.isEmpty() && chatId in failedChats && !_loaded.value[chatId].isNullOrEmpty()) return@once
        val kept = withinLimits(items)
        _partial.update { if (ended) it - chatId else it + chatId }
        // Unchanged rows are not written again, so nothing recomposes for an answer that is the same.
        _loaded.update { if (it[chatId] == kept) it else it + (chatId to kept) }
        noteFresh()
        // A chat shown on Home is one likely opened next: the first few have their grid's first
        // page fetched behind, at low priority, so opening one draws it at once.
        if (source === TdHomeSource && items.isNotEmpty() && prefetched.size < PREFETCH_CHATS && prefetched.add(chatId)) {
            com.tmplayer.data.PageQueries.prefetchChat(chatId)
        }
    }

    private fun noteFresh() {
        if (firstFresh) return
        firstFresh = true
        com.tmplayer.data.PerfLog.mark("home first fresh row", com.tmplayer.data.PerfLog.processStart)
    }

    /** Fetches the newest videos across every chat, once, for "Recently added in starred chats" (see [HomeRows]). */
    fun requestRecent() = once("recent") {
        recentFailed = false
        val items = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(ROW_TIMEOUT_MS) { source.recentMedia() }
                ?: throw java.io.IOException("Recent row timed out")
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            recentFailed = true
            emptyList()
        }
        // As for a chat's row: a failed refresh keeps what was there.
        if (recentFailed && !_recent.value.isNullOrEmpty()) return@once
        val kept = withinLimits(items)
        if (_recent.value != kept) _recent.value = kept
        noteFresh()
    }

    /** Looks up a Continue watching tile's picture, once. A miss leaves the tile its neutral art. */
    fun requestArt(record: ResumeRecord) = requestArt(record.chatId, record.messageId)

    /** The same for a Watch later tile, whose record keeps no picture either. */
    fun requestArt(record: WatchLaterRecord) = requestArt(record.chatId, record.messageId)

    private fun requestArt(chatId: Long, messageId: Long) {
        val key = SettingsStore.progressKey(chatId, messageId)
        once("art:$key") {
            val item = runCatching { source.mediaItem(chatId, messageId) }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                null
            } ?: return@once
            RecordItems.keep(item)
            _art.update { it + (key to item) }
        }
    }

    /**
     * Works out which shows the viewer is part way through from [resume] and [watched], and asks
     * each one's chat what has arrived since (see [NewEpisodes]): at most [NewEpisodes.SHOW_LIMIT]
     * shows, one small search apiece, each answer kept for [SHOW_TTL_MS] across visits to Home.
     * Called again whenever the records change; a show already asked is not asked again.
     */
    fun followShows(resume: List<ResumeRecord>, watched: Collection<WatchedRecord>) {
        showsJob?.cancel()
        showsJob = viewModelScope.launch {
            val next = withContext(work) {
                Tracked(NewEpisodes.inProgress(resume, watched), NewEpisodes.touchTimes(resume, watched).keys)
            }
            tracked.value = next
            next.shows.forEach(::requestShow)
        }
    }

    private fun requestShow(show: ShowReached) = once("show:${show.key}") {
        val now = System.currentTimeMillis()
        val items = showCache[show.key]?.takeIf { now - it.first < SHOW_TTL_MS }?.second
            ?: runCatching { source.showMedia(show.chatId, show.item.messageId, show.searchFor) }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                // Not cached: a show whose search failed is asked again on the next visit.
                null
            }?.also { showCache[show.key] = now to it }
            ?: return@once
        val kept = withinLimits(items)
        found.update { it + (show.key to kept) }
    }

    /**
     * Forgets everything fetched, the cached copy too, so every row on screen asks again: for a
     * sign out, when what Home held was another account's. A refresh the viewer asks for keeps
     * the tiles up: [refreshInPlace].
     */
    fun refresh() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        asked.clear()
        _loaded.value = emptyMap()
        _partial.value = emptySet()
        _recent.value = null
        _art.value = emptyMap()
        _refreshing.value = false
        refreshedAt = 0L
        if (cached) HomeCache.clear()
        // The shows are asked again past their cache. What they found stays up meanwhile, so the
        // New episodes row does not blink out and back.
        tracked.value.shows.forEach { show ->
            showCache.remove(show.key)
            requestShow(show)
        }
    }

    /**
     * Reads every tile's Cached badge off the disk again, without fetching anything.
     *
     * What arrives is kept for the life of the screen, badges included, and the watch cache holds
     * one video: playing another took the last one's copy away while its tile went on saying
     * Cached. Called when the screen comes back from the player and when the cache record changes.
     */
    fun refreshLocalAvailability() {
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            val answers = HashMap<Int, Boolean>()
            suspend fun recheckOne(item: MediaItem): MediaItem {
                val whole = answers.getOrPut(item.fileId) {
                    runCatching { source.wholeOnDevice(item) }.getOrElse { failure ->
                        if (failure is CancellationException) throw failure
                        item.onDevice
                    }
                }
                return item.recheckedCache(whole)
            }
            suspend fun recheckAll(items: List<MediaItem>): List<MediaItem> {
                val updated = items.map { recheckOne(it) }
                return if (updated.indices.all { updated[it] === items[it] }) items else updated
            }

            val loadedBefore = _loaded.value
            val recentBefore = _recent.value
            val artBefore = _art.value
            val loaded = loadedBefore.mapValues { (_, items) -> recheckAll(items) }
            val recent = recentBefore?.let { recheckAll(it) }
            val art = artBefore.mapValues { (_, item) -> recheckOne(item) }
            // Written only over what is still the list that was checked: a row fetched meanwhile
            // came after the change and is right as it stands.
            _loaded.update { now ->
                now.mapValues { (chatId, items) ->
                    if (items === loadedBefore[chatId]) loaded.getValue(chatId) else items
                }
            }
            _recent.update { now -> if (now != null && now === recentBefore) recent else now }
            _art.update { now ->
                now.mapValues { (key, item) -> if (item === artBefore[key]) art.getValue(key) else item }
            }
        }
    }

    private var availabilityJob: Job? = null

    private fun once(key: String, fetch: suspend () -> Unit) {
        if (!asked.add(key)) return
        jobs += viewModelScope.launch { gate.withPermit { fetch() } }
        jobs.removeAll { it.isCompleted }
    }

    private suspend fun withinLimits(items: List<MediaItem>): List<MediaItem> {
        val (min, max) = runCatching { sizeLimits() }.getOrDefault(SizeFilter.DEFAULT_MIN to SizeFilter.DEFAULT_MAX)
        return items.filter { SizeFilter.matches(it.sizeBytes, min, max) }
    }

    companion object {
        /** Rows fetched at once: enough to fill a screen quickly, few enough to stay polite to Telegram. */
        const val PARALLEL = 3

        /** How many of Home's chats have their grid prefetched in a run: the first rows, not all of them. */
        const val PREFETCH_CHATS = 6

        /** How often Home brings itself up to date while it is open (user, 2026-10-21: "short auto refresh"). */
        const val AUTO_REFRESH_MS = 3 * 60 * 1000L

        /** Older than this when the app comes back to the front, and Home refreshes in place. */
        const val STALE_MS = 60 * 1000L

        /** How long the rows have to stay still before the cache is written. */
        const val PERSIST_AFTER_MS = 1_500L

        /**
         * Past this a row's fetch counts as failed: its skeleton goes, and it is asked for again
         * when the line comes back ([retryFailed]). Twice the slow notice's wait.
         */
        const val ROW_TIMEOUT_MS = 30_000L

        /**
         * Starred chats held open for their posts at once. An open channel is one TDLib keeps in
         * step with the server, which is not free on a 1 GB stick; the first rows are the ones seen.
         */
        const val FOLLOW_MAX = 12

        /**
         * How long a show's search answers for. Ten minutes, like the forum topic counts: coming
         * back to Home from the player is not worth a fresh round of searches, and a post that
         * arrives while the app is open asks again on its own.
         */
        const val SHOW_TTL_MS = 10 * 60 * 1000L

        /**
         * Each show's search, by [ShowReached.key], with when it was made. For the process, not
         * the screen: Home is built again on every visit and its searches should not be.
         */
        private val showCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<MediaItem>>>()
    }
}

/**
 * Home's rows for what is on screen now, rebuilt only when one of the inputs changes.
 *
 * @param chats the chat list, which decides the starred rows' order.
 * @param watched the Watched list, which with [continueWatching] says which shows are in progress
 *   for New episodes.
 */
@Composable
fun rememberHomeRows(
    model: HomeViewModel,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    continueWatching: List<ResumeRecord>,
    watchLater: List<WatchLaterRecord> = emptyList(),
    watched: Collection<WatchedRecord> = emptyList(),
    /** See [HomeRows.build]: one on a phone, whose Recently added is the featured carousel. */
    recentMinStarred: Int = HomeRows.RECENT_MIN_STARRED,
    /**
     * Home fills itself from the chats with videos, favourites first ([HomeRows.homeChats]), with
     * Movies and Shows rows across them and each chat's movies and shows in rows of their own.
     * False draws the starred chats alone, as Home did before 2026-10-21.
     */
    autoFill: Boolean = true,
): List<HomeRow> {
    // A hidden chat's videos leave Home with it: its Continue watching and Watch later cards too.
    val hidden by com.tmplayer.data.HiddenChats.ids.collectAsState()
    val continueWatching = remember(continueWatching, hidden) { com.tmplayer.data.HiddenChats.filter(continueWatching, hidden) { it.chatId } }
    val watchLater = remember(watchLater, hidden) { com.tmplayer.data.HiddenChats.filter(watchLater, hidden) { it.chatId } }
    val loadedAll by model.loaded.collectAsState()
    val recentAll by model.recent.collectAsState()
    // What was read before the chat was hidden goes too, without waiting for the next refresh.
    val loaded = remember(loadedAll, hidden) { if (hidden.isEmpty()) loadedAll else loadedAll - hidden }
    val recent = remember(recentAll, hidden) { recentAll?.let { all -> com.tmplayer.data.HiddenChats.filter(all, hidden) { it.chatId } } }
    val partial by model.partial.collectAsState()
    val fresh by model.newEpisodes.collectAsState()
    LaunchedEffect(model, continueWatching, watched) { model.followShows(continueWatching, watched) }
    val home = remember(chats, favourites, recent, autoFill) {
        if (autoFill) HomeRows.homeChats(chats, favourites, recent) else HomeRows.favouriteChats(chats, favourites)
    }
    // "Not the same video" pulls a copy out of its folded card at once, so the rows are built
    // again when a correction changes.
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsState()
    val rows = remember(continueWatching, watchLater, home, favourites, loaded, recent, partial, fresh, corrections, recentMinStarred, autoFill) {
        HomeRows.build(
            continueWatching, home, loaded, recent, partial = partial, watchLater = watchLater, fresh = fresh,
            recentMinStarred = recentMinStarred, starred = favourites, autoFill = autoFill,
        )
    }
    PrefetchHomeArt(rows)
    return rows
}

/**
 * Looks up the posters of the first tiles in each row, and fetches them, before the rows are
 * drawn: a row a screen down is sharp when it scrolls in rather than starting its lookups then.
 * A handful per row, two lookups at a time, and only while lookups are on. Everything it finds
 * lands in [OnlineMetadata]'s memory and disk caches, which the tiles read on their first frame.
 */
@Composable
private fun PrefetchHomeArt(rows: List<HomeRow>) {
    val online = com.tmplayer.online.OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    if (!settings.enabled) return
    val items = remember(rows) {
        rows.flatMap { row ->
            val entries = when (row) {
                is HomeRow.Chat -> row.entries
                is HomeRow.Recent -> row.entries
                is HomeRow.Across -> row.entries
                else -> emptyList()
            }
            // The carousel's pages want their title logos too.
            val logo = row is HomeRow.Recent
            entries.take(if (logo) com.tmplayer.data.HomeFeatured.LIMIT else PREFETCH_PER_ROW).map { entry ->
                when (entry) {
                    is com.tmplayer.data.ShelfEntry.File -> Ahead(entry.item, show = false, logo = logo)
                    is com.tmplayer.data.ShelfEntry.Show -> Ahead(entry.series.cover, show = true, logo = logo)
                }
            }
        }.distinctBy { it.item.id }
    }
    LaunchedEffect(items) {
        val gate = Semaphore(PREFETCH_PARALLEL)
        kotlinx.coroutines.coroutineScope {
            items.forEach { (item, show, logo) ->
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        val query = com.tmplayer.online.MetaQuery.of(item.nameForParsing, item.caption)
                            ?.let { if (show) it.showOnly() else it } ?: return@withPermit
                        val known = (online.peek(query) as? com.tmplayer.online.MetaResult.Found)?.info
                        if (known != null && !logo) return@withPermit
                        val info = known ?: (online.lookup(query) as? com.tmplayer.online.MetaResult.Found)?.info ?: return@withPermit
                        if (known == null) info.posterUrl?.let { runCatching { online.image(it) } }
                        if (logo && online.peekLogo(info) == null) runCatching { online.logo(info)?.let { online.image(it) } }
                    }
                }
            }
        }
    }
}

/** A tile looked up ahead: a show by its name alone, and the carousel's with its title logo. */
private data class Ahead(val item: com.tmplayer.data.MediaItem, val show: Boolean, val logo: Boolean)

/** Tiles per row whose posters are looked up ahead: a phone's screen width and a little more. */
private const val PREFETCH_PER_ROW = 4

/** Lookups ahead at once, under the tiles' own: TMDB's spacing is shared with them. */
private const val PREFETCH_PARALLEL = 2
