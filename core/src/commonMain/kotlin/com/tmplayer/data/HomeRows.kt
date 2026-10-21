package com.tmplayer.data

/**
 * One row on Home: what the viewer was part way through, the newest videos in a starred chat, or
 * the newest videos across the starred chats together.
 *
 * A chat row and the Recent row can be on screen before their videos are: [loaded] false is a row
 * still asking Telegram, which draws as a placeholder strip so the rows under it do not jump when
 * it fills.
 */
sealed interface HomeRow {
    /** Stable while the row loads and refills, so a lazy list can key on it. */
    val key: String

    /** What a row's "See all" has to say: how many videos there are and whether that is all of them. */
    val total: Int get() = 0

    /** True when the count is only what has been fetched so far, so "12+" rather than "12". */
    val totalAtLeast: Boolean get() = false

    /** Whether the row leaves anything out, which is the only time it offers "See all". */
    val hasMore: Boolean get() = false

    /**
     * @param more the records past the row's [HomeRows.LIMIT], newest first, for the See all
     *   tile's picture and count.
     */
    data class Continue(
        val records: List<ResumeRecord>,
        val more: List<ResumeRecord> = emptyList(),
    ) : HomeRow {
        override val key: String get() = "continue"
        override val total: Int get() = records.size + more.size
        override val hasMore: Boolean get() = more.isNotEmpty()
    }

    /**
     * @param more the chat's fetched videos that the row does not show, newest first.
     * @param complete false when Telegram has more of this chat than was fetched for the row.
     * @param fetched how many of the chat's videos were fetched, within the size limits.
     */
    data class Chat(
        val chatId: Long,
        val title: String,
        val entries: List<ShelfEntry>,
        val loaded: Boolean,
        val more: List<MediaItem> = emptyList(),
        val complete: Boolean = true,
        val fetched: Int = 0,
        /** Where Home fills itself: whether the row holds the chat's single videos or its shows, which never share a row. */
        val kind: Across.Kind? = null,
        /** The chat's second row (its shows, after its videos), keyed apart from the first. */
        val second: Boolean = false,
    ) : HomeRow {
        override val key: String get() = if (second) "chat-$chatId-more" else "chat-$chatId"
        override val total: Int get() = fetched
        override val totalAtLeast: Boolean get() = !complete
        override val hasMore: Boolean get() = loaded && (more.isNotEmpty() || !complete)
    }

    /**
     * The videos the viewer put aside for later, newest first.
     *
     * @param more the records past the row's [HomeRows.LIMIT], for the See all count.
     */
    data class WatchLater(
        val records: List<WatchLaterRecord>,
        val more: List<WatchLaterRecord> = emptyList(),
    ) : HomeRow {
        override val key: String get() = "watch-later"
        override val total: Int get() = records.size + more.size
        override val hasMore: Boolean get() = more.isNotEmpty()
    }

    /**
     * Episodes of the shows the viewer is watching that arrived since they last watched them (see
     * [com.tmplayer.data.NewEpisodes]). Only ever built with something in it, so it has no loading
     * state: a row that may well stay empty should not hold a placeholder strip open on Home.
     */
    data class NewEpisodes(val episodes: List<NewEpisode>) : HomeRow {
        override val key: String get() = "new-episodes"
    }

    /**
     * Home's chats' newest videos in one line, newest first, whichever chat they came from.
     *
     * @param starredOnly true when every chat it draws from is starred, which is what its heading
     *   says; false once Home fills itself from the chats with videos ([HomeRows.homeChats]).
     */
    data class Recent(val entries: List<ShelfEntry>, val loaded: Boolean, val starredOnly: Boolean = true) : HomeRow {
        override val key: String get() = "recent"
    }

    /**
     * The shows, or the films and other videos, across all of Home's chats in one row, newest
     * first: Home's "Shows" and "Videos" (user, 2026-10-21: "carousel, movies and shows"). Like
     * Recently added it repeats what the chat rows under it hold, merged; it leaves out only what
     * Continue watching and Watch later already have.
     */
    data class Across(val kind: Kind, val entries: List<ShelfEntry>) : HomeRow {
        enum class Kind { Shows, Videos }

        override val key: String get() = "across-${kind.name.lowercase()}"
    }
}

/**
 * Builds Home's rows from what is on this device and what Telegram has answered so far.
 *
 * Pure, so the rules live in one place for the phone, the television and the desktop:
 * - Continue watching first, then "Recently added in starred chats", so what just arrived in a
 *   favourite is near the top, then Watch later, then New episodes when there are any, then one
 *   row per starred chat in the chat list's own order.
 * - Recently added draws from Home's chats: the starred ones, and where Home fills itself
 *   ([homeChats], the app's default since 2026-10-21) the chats the search across chats found the
 *   most videos in, which leaves out family groups and news that only ever post a clip. Starred
 *   chats alone need two at least: with one, it would repeat that chat's own row.
 * - Filling itself, Home puts Movies and Shows across its chats straight under Continue watching,
 *   and gives each chat a row of its movies and a row of its shows: the two never share a row.
 * - Every row holds at most [LIMIT] tiles. A show folded into one tile counts once.
 * - Continue watching holds one record per film or episode: copies of it (a 1080p and a 720p)
 *   fold into the one watched last ([foldCopies]).
 * - A video appears once among Continue, Watch later and the chat rows: something part way
 *   through is in Continue and nowhere else, something saved for later is in Watch later. Recently added is the starred chats' videos again, merged into one
 *   timeline, so it leaves out only what is in Continue.
 * - A row that has loaded and found nothing is left out rather than drawn as an empty strip.
 * - New episodes are not taken out of the chat rows: a starred chat's show tile stands for the
 *   whole show, and losing its newest episodes there would make it look further behind.
 */
object HomeRows {

    /** Tiles per row. Ten fill a television's width twice over and keep each row cheap to compose. */
    const val LIMIT = 10

    /** Starred chats it takes for Recently added: one alone already has a row of its own. */
    const val RECENT_MIN_STARRED = 2

    /** The starred chats that get a row, in the chat list's order, which is Telegram's recency. */
    fun favouriteChats(chats: List<ChatSummary>, favourites: Set<Long>): List<ChatSummary> =
        if (favourites.isEmpty()) emptyList() else chats.filter { it.id in favourites }

    /** Chats Home picks for itself, besides the starred ones: a screen's worth of rows. */
    const val AUTO_CHATS = 6

    /**
     * The chats Home draws from: the starred ones first, in the chat list's order, each with a
     * row of its own, then up to [limit] more that the search across every chat ([recent]) found
     * videos in, the most videos first, then the newest. So Home is full on the first open with
     * nothing starred (user, 2026-10-21); starring a chat only puts it first. An archived chat is
     * never picked, nor one missing from [chats] (the kids lock's, or one this account left).
     * Before [recent] arrives only the starred chats are known.
     */
    fun homeChats(
        chats: List<ChatSummary>,
        favourites: Set<Long>,
        recent: List<MediaItem>?,
        limit: Int = AUTO_CHATS,
    ): List<ChatSummary> {
        val starred = favouriteChats(chats, favourites)
        if (recent.isNullOrEmpty() || limit <= 0) return starred
        val listed = chats.filterNot { it.isArchived || it.id in favourites }.associateBy { it.id }
        val picked = recent
            .filter { it.chatId in listed }
            .groupBy { it.chatId }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, List<MediaItem>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.maxOf { it.date } }
                    .thenBy { it.key },
            )
            .take(limit)
            .mapNotNull { listed[it.key] }
        return starred + picked
    }

    /**
     * @param favourites the starred chats, already in display order (see [favouriteChats]).
     * @param loaded each starred chat's newest videos, by chat id; a chat missing here is still loading.
     * @param recent the newest videos across every chat, or null while that search is running. Only
     *   the starred chats' are kept, together with what [loaded] already has of them.
     * @param partial the starred chats whose fetch stopped short of their oldest video.
     * @param watchLater the Watch later list, newest first.
     * @param fresh the new episodes of the shows in progress, as [NewEpisodes.collect] lists them.
     * @param recentMinStarred the starred chats it takes for Recently added: [RECENT_MIN_STARRED],
     *   or one on a phone, where the row is the featured carousel ([HomeFeatured]) rather than a
     *   repeat of the one chat's own row.
     * @param starred which of [favourites] the viewer starred, when [favourites] is [homeChats]'
     *   answer; null when they all are. Only says what Recently added's heading may claim.
     * @param autoFill true where Home fills itself ([homeChats]): with nothing starred and the
     *   search across chats still running, Recently added holds its place as loading rather than
     *   Home opening on the first-run card, and Shows and Videos are built across the chats.
     */
    fun build(
        continueWatching: List<ResumeRecord>,
        favourites: List<ChatSummary>,
        loaded: Map<Long, List<MediaItem>>,
        recent: List<MediaItem>?,
        partial: Set<Long> = emptySet(),
        watchLater: List<WatchLaterRecord> = emptyList(),
        fresh: List<NewEpisode> = emptyList(),
        recentMinStarred: Int = RECENT_MIN_STARRED,
        starred: Set<Long>? = null,
        autoFill: Boolean = false,
    ): List<HomeRow> = buildList<HomeRow> {
        val seen = HashSet<String>()

        val distinct = continueWatching.distinctBy { keyOf(it.chatId, it.messageId) }
        val everyResume = foldCopies(distinct)
        val resume = everyResume.take(LIMIT)
        resume.forEach { seen += keyOf(it.chatId, it.messageId) }
        // A copy folded under the one watched last is in Continue too, through that one.
        val shownCopies = resume.mapNotNullTo(HashSet()) { record -> copyKeyOf(record) }
        distinct.forEach { record -> if (copyKeyOf(record).let { it != null && it in shownCopies }) seen += keyOf(record.chatId, record.messageId) }
        if (resume.isNotEmpty()) add(HomeRow.Continue(resume, everyResume.drop(LIMIT)))
        val inContinue = HashSet(seen)

        val homeChats = favourites.distinctBy { it.id }
        val homeIds = homeChats.mapTo(HashSet()) { it.id }
        val starredOnly = starred == null || homeIds.all { it in starred }
        if (homeChats.size >= recentMinStarred.coerceAtLeast(1) || (autoFill && recent == null)) {
            val starredIds = homeIds
            if (recent == null) {
                add(HomeRow.Recent(emptyList(), loaded = false, starredOnly = starredOnly))
            } else {
                // The search across chats reaches a starred chat further back than its own row's
                // fetch, and a starred chat's fetch fills in whatever the search did not reach.
                val pool = recent.filter { it.chatId in starredIds } + starredIds.flatMap { loaded[it].orEmpty() }
                // A copy, so the rows below still show these videos in their own chats.
                val entries = row(pool, HashSet(inContinue), shownCopies, inContinue)
                if (entries.isNotEmpty()) {
                    add(HomeRow.Recent(entries, loaded = true, starredOnly = starredOnly))
                } else if (starredIds.any { it !in loaded }) {
                    // Nothing yet, but a starred chat's own videos are still on their way: the row
                    // stays as loading, so the carousel holds its place rather than vanishing and
                    // coming back once they land (seen on a cold start on the TV stick).
                    add(HomeRow.Recent(emptyList(), loaded = false, starredOnly = starredOnly))
                }
            }
        }

        // Started since it was saved: Continue already has it, with its place.
        val everyLater = watchLater.distinctBy { it.key }.filterNot { it.key in inContinue }
        val later = everyLater.take(LIMIT)
        later.forEach { seen += it.key }
        if (later.isNotEmpty()) add(HomeRow.WatchLater(later, everyLater.drop(LIMIT)))
        val newEpisodes = fresh.filterNot { keyOf(it.item.chatId, it.item.messageId) in seen }.take(LIMIT)
        if (newEpisodes.isNotEmpty()) add(HomeRow.NewEpisodes(newEpisodes))

        // Shows, then films and other videos, across the chats: only where Home fills itself, and
        // from two chats at least, or they would be one chat's row twice over.
        if (autoFill && homeChats.size >= 2) {
            val pool = recent.orEmpty().filter { it.chatId in homeIds } + homeIds.flatMap { loaded[it].orEmpty() }
            // A copy of what is seen: the chat rows below still show these in their own chats.
            val across = arranged(pool, HashSet(seen), shownCopies, inContinue)
            // Films first among the videos, then the other videos, each newest first as they came.
            val videos = across.filter { kindOf(it) == HomeRow.Across.Kind.Videos }
                .sortedBy { if (it is ShelfEntry.File && SeriesShelf.isFilm(it.item)) 0 else 1 }
                .take(LIMIT)
            val shows = across.filter { kindOf(it) == HomeRow.Across.Kind.Shows }.take(LIMIT)
            if (videos.isNotEmpty()) add(HomeRow.Across(HomeRow.Across.Kind.Videos, videos))
            if (shows.isNotEmpty()) add(HomeRow.Across(HomeRow.Across.Kind.Shows, shows))
        }

        homeChats.forEach { chat ->
            val items = loaded[chat.id]
            if (items == null) {
                add(HomeRow.Chat(chat.id, chat.title, emptyList(), loaded = false))
                return@forEach
            }
            if (autoFill) {
                // Videos and shows never share a row (user, 2026-10-21): a chat's videos, then
                // its shows, each a row of its own when it has any.
                val all = items.distinctBy { it.id }
                val arranged = arranged(all, seen, shownCopies, inContinue)
                var first = true
                for (kind in listOf(HomeRow.Across.Kind.Videos, HomeRow.Across.Kind.Shows)) {
                    val entries = arranged.filter { kindOf(it) == kind }.take(LIMIT)
                    if (entries.isEmpty()) continue
                    markSeen(entries, seen)
                    val ofKind = all.filter { kindOf(it) == kind }
                    add(
                        HomeRow.Chat(
                            chat.id,
                            chat.title,
                            entries,
                            loaded = true,
                            more = beyond(ofKind, entries),
                            complete = chat.id !in partial,
                            fetched = ofKind.size,
                            kind = kind,
                            second = !first,
                        ),
                    )
                    first = false
                }
                return@forEach
            }
            val entries = row(items, seen, shownCopies, inContinue)
            if (entries.isNotEmpty()) {
                val all = items.distinctBy { it.id }
                add(
                    HomeRow.Chat(
                        chat.id,
                        chat.title,
                        entries,
                        loaded = true,
                        more = beyond(all, entries),
                        complete = chat.id !in partial,
                        fetched = all.size,
                    ),
                )
            }
        }

    }.let { rows -> if (autoFill) moviesAndShowsFirst(rows) else rows }

    /**
     * Movies and Shows straight under Continue watching (and the carousel), ahead of Watch later
     * and New episodes: the order the viewer asked for (user, 2026-10-21). They are built after
     * those rows, which they leave out, and moved up here.
     */
    private fun moviesAndShowsFirst(rows: List<HomeRow>): List<HomeRow> {
        val across = rows.filterIsInstance<HomeRow.Across>()
        if (across.isEmpty()) return rows
        val rest = rows.filterNot { it is HomeRow.Across }
        val at = rest.indexOfLast { it is HomeRow.Continue || it is HomeRow.Recent } + 1
        return rest.take(at) + across + rest.drop(at)
    }

    /**
     * Newest first, without anything an earlier row already showed, folded into shows, cut to
     * [LIMIT]. Whatever ends up in the row is added to [seen], every episode of a folded show
     * included (every copy of each), so a later row cannot show one of them again on its own.
     * Nor can another copy of a film or episode in Continue ([continuing], as [copyKeyOf] keys
     * them): the 1080p of a film started in 720p is the same film, part way through.
     *
     * An episode in Continue ([inProgress]) still counts towards its show, though: left out, the
     * show's tile said "11 episodes" for a show of 12. It is only never a tile of its own.
     */
    private fun row(
        items: List<MediaItem>,
        seen: MutableSet<String>,
        continuing: Set<String> = emptySet(),
        inProgress: Set<String> = emptySet(),
    ): List<ShelfEntry> {
        val entries = arranged(items, seen, continuing, inProgress).take(LIMIT)
        markSeen(entries, seen)
        return entries
    }

    private fun markSeen(entries: List<ShelfEntry>, seen: MutableSet<String>) {
        entries.forEach { entry ->
            when (entry) {
                is ShelfEntry.File -> seen += keyOf(entry.item.chatId, entry.item.messageId)
                is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }.forEach { seen += keyOf(it.chatId, it.messageId) }
            }
        }
    }

    /**
     * Whether a tile is a show's or a single video's (user, 2026-10-21): a show is a folded
     * series, or a lone file whose name or caption gives a season or an episode; everything else,
     * a film, a clip, a lecture, is a video.
     */
    fun kindOf(entry: ShelfEntry): HomeRow.Across.Kind = when (entry) {
        is ShelfEntry.Show -> HomeRow.Across.Kind.Shows
        is ShelfEntry.File -> kindOf(entry.item)
    }

    /** [kindOf] for one file. */
    fun kindOf(item: MediaItem): HomeRow.Across.Kind =
        if (SeriesShelf.episodeOf(item).isEpisode) HomeRow.Across.Kind.Shows else HomeRow.Across.Kind.Videos

    /** [row]'s tiles before the cut to [LIMIT], with nothing added to [seen]. */
    private fun arranged(
        items: List<MediaItem>,
        seen: Set<String>,
        continuing: Set<String> = emptySet(),
        inProgress: Set<String> = emptySet(),
    ): List<ShelfEntry> {
        fun copyOfContinuing(item: MediaItem): Boolean =
            continuing.isNotEmpty() && VideoCopies.keyOf(item)?.let { it in continuing } == true
        fun episode(item: MediaItem): Boolean = SeriesShelf.episodeOf(item).episode != null
        val fresh = items
            .filterNot { item ->
                val key = keyOf(item.chatId, item.messageId)
                val partOfAShow = episode(item) && (key in inProgress || copyOfContinuing(item))
                !partOfAShow && (key in seen || copyOfContinuing(item))
            }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId })
        return SeriesShelf.arrange(fresh)
            .filterNot { entry ->
                entry is ShelfEntry.File && entry.item.let { keyOf(it.chatId, it.messageId) in inProgress || copyOfContinuing(it) }
            }
    }

    /** A chat's videos that none of the row's tiles stands for, newest first: what See all adds. */
    private fun beyond(items: List<MediaItem>, entries: List<ShelfEntry>): List<MediaItem> {
        val shown = HashSet<String>()
        entries.forEach { entry ->
            when (entry) {
                is ShelfEntry.File -> shown += entry.item.id
                is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }.forEach { shown += it.id }
            }
        }
        return items
            .filterNot { it.id in shown }
            .sortedWith(compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId })
    }

    /**
     * [records] with the copies of one film or one episode in a chat (a 1080p and a 720p of it,
     * see [SeriesShelf.sameVideoKey]) folded into one: the copy watched last, with its own place
     * and quality, where the newest of them stood. Everything else is left as it is, in its order.
     * What Continue watching lists, on Home and in History alike.
     */
    fun foldCopies(records: List<ResumeRecord>): List<ResumeRecord> {
        val latest = HashMap<String, ResumeRecord>()
        records.forEach { record ->
            val key = copyKeyOf(record) ?: return@forEach
            val held = latest[key]
            if (held == null || record.updatedAt > held.updatedAt) latest[key] = record
        }
        val placed = HashSet<String>()
        return records.mapNotNull { record ->
            val key = copyKeyOf(record) ?: return@mapNotNull record
            if (placed.add(key)) latest.getValue(key) else null
        }
    }

    /**
     * The Watched list with the copies of one film or episode as one card, as [foldCopies] folds
     * Continue watching: the copy finished last, where it stood. [records] come newest first.
     */
    fun foldWatched(records: List<WatchedRecord>): List<WatchedRecord> {
        val seen = HashSet<String>()
        return records.filter { record -> watchedKeyOf(record)?.let(seen::add) ?: true }
    }

    /**
     * [record] as the video its folded card stands for: every copy on the Watched list as its
     * [MediaItem.versions], so taking the mark off takes it off all of them.
     */
    fun watchedItem(record: WatchedRecord, records: List<WatchedRecord>): MediaItem {
        val item = record.toMediaItem()
        val key = watchedKeyOf(record) ?: return item
        val copies = records.filter { it.key == record.key || watchedKeyOf(it) == key }.map { it.toMediaItem() }
        return if (copies.size > 1) item.copy(versions = copies) else item
    }

    private fun watchedKeyOf(record: WatchedRecord): String? =
        if (record.fileName != null) VideoCopies.keyOf(record.toMediaItem()) else VideoCopies.keyOfTitle(record.title)

    /**
     * [record] and every other copy of the same film or episode in [records]: what removing a
     * card [foldCopies] made must remove, or the copy behind it takes its place at once.
     */
    fun copiesOf(record: ResumeRecord, records: List<ResumeRecord>): List<ResumeRecord> {
        val key = copyKeyOf(record) ?: return listOf(record)
        return (listOf(record) + records.filter { copyKeyOf(it) == key }).distinctBy { it.chatId to it.messageId }
    }

    /**
     * Which film or episode [record] is a copy of, in any chat ([VideoCopies.keyOf]), or null for a
     * video that names neither. An old record is read cautiously: its title alone has to name a
     * year or an episode, so a caption's bare "S02E04" never folds a whole show into one card.
     */
    private fun copyKeyOf(record: ResumeRecord): String? = VideoCopies.keyOf(record)

    /** The same key [SettingsStore.progressKey] writes, so a resume record and a video match. */
    private fun keyOf(chatId: Long, messageId: Long) = SettingsStore.progressKey(chatId, messageId)

    /**
     * [row] with only the tiles that have a picture of their own ([HomeFeatured.hasArt]), as the
     * desktop's Home draws it: never a tile of nothing but a generated cover. A record of Continue
     * watching or Watch later keeps no picture, so it shows once its video has been asked for and
     * is in [art] (keyed as [SettingsStore.progressKey]) with one. Null for a loaded row left with
     * nothing to show; a row still loading is returned as it is.
     */
    fun withArt(row: HomeRow, art: Map<String, MediaItem>): HomeRow? {
        fun pictured(chatId: Long, messageId: Long) = art[keyOf(chatId, messageId)]?.let(HomeFeatured::hasArt) == true
        val kept = when (row) {
            is HomeRow.Continue -> row.copy(records = row.records.filter { pictured(it.chatId, it.messageId) })
            is HomeRow.WatchLater -> row.copy(records = row.records.filter { pictured(it.chatId, it.messageId) })
            is HomeRow.NewEpisodes -> row.copy(episodes = row.episodes.filter { HomeFeatured.hasArt(it.item) })
            is HomeRow.Chat -> if (!row.loaded) return row else row.copy(entries = row.entries.filter(HomeFeatured::hasArt))
            is HomeRow.Recent -> if (!row.loaded) return row else row.copy(entries = row.entries.filter(HomeFeatured::hasArt))
            is HomeRow.Across -> row.copy(entries = row.entries.filter(HomeFeatured::hasArt))
        }
        val empty = when (kept) {
            is HomeRow.Continue -> kept.records.isEmpty()
            is HomeRow.WatchLater -> kept.records.isEmpty()
            is HomeRow.NewEpisodes -> kept.episodes.isEmpty()
            is HomeRow.Chat -> kept.entries.isEmpty()
            is HomeRow.Recent -> kept.entries.isEmpty()
            is HomeRow.Across -> kept.entries.isEmpty()
        }
        return if (empty) null else kept
    }
}
