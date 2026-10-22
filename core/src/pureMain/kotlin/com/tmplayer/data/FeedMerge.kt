package com.tmplayer.data

/**
 * One chat read a page at a time as part of a list drawn from several chats ([FeedMerge]).
 *
 * @param cursors where the next page starts.
 * @param oldest the date of the oldest video read from it so far, before any size limit, or null
 *   while nothing has been read: the chat can only add videos at or before this.
 * @param done true once Telegram said there is nothing older.
 */
data class FeedLane(
    val chatId: Long,
    val cursors: MediaCursors = MediaCursors(),
    val oldest: Int? = null,
    val done: Boolean = false,
)

/**
 * Several chats' videos merged into one list, newest first, that only ever grows at its foot: a
 * "See all" page over Home's chats reading on past what Home fetched.
 *
 * Each chat is read newest first, so a chat whose oldest video read so far is from Tuesday can
 * still turn up anything older than Tuesday, never anything newer. A video is shown once every
 * chat still open has been read back past it ([horizon]), so a page arriving from one chat never
 * slips a video in above the ones already on screen: the list does not jump. The chat to read
 * next is the one holding the horizon back ([next]).
 */
object FeedMerge {

    /** Newest first, the later message first within one second. */
    val newestFirst: Comparator<MediaItem> =
        compareByDescending<MediaItem> { it.date }.thenByDescending { it.messageId }.thenBy { it.chatId }

    /**
     * The date a video must be newer than to be in its final place: the newest of the open
     * chats' oldest dates. [Int.MAX_VALUE] while an open chat has not been read at all (it could
     * hold anything), null once every chat is read to its end (everything is final).
     */
    fun horizon(lanes: List<FeedLane>): Int? {
        val open = lanes.filterNot { it.done }
        if (open.isEmpty()) return null
        return open.maxOf { it.oldest ?: Int.MAX_VALUE }
    }

    /**
     * What of [items] is in its final place ([horizon]), newest first, each video once. A video
     * on the horizon itself waits: the chat holding it back may still have another of that second.
     */
    fun settled(items: List<MediaItem>, lanes: List<FeedLane>): List<MediaItem> {
        val line = horizon(lanes)
        return items
            .asSequence()
            .filter { line == null || it.date > line }
            .distinctBy { it.id }
            .sortedWith(newestFirst)
            .toList()
    }

    /** The chat to read next: one not read at all first, then the one holding [horizon] back. Null when all are done. */
    fun next(lanes: List<FeedLane>): FeedLane? =
        lanes.filterNot { it.done }.maxByOrNull { it.oldest?.toLong() ?: Long.MAX_VALUE }

    /** [lanes] after [chatId]'s next [page] was read. */
    fun advance(lanes: List<FeedLane>, chatId: Long, page: MediaPage): List<FeedLane> = lanes.map { lane ->
        if (lane.chatId != chatId) return@map lane
        val pageOldest = page.items.minOfOrNull { it.date }
        val oldest = listOfNotNull(lane.oldest, pageOldest).minOrNull()
        // A page that came back empty without reaching the end moved no date: the cursors did move.
        lane.copy(cursors = page.cursors, oldest = oldest, done = page.endReached || page.cursors.allDone)
    }

    /**
     * The lanes for [chatIds] as Home left them: each chat's cursors and whether it ended, where
     * Home read it ([reads]), and the oldest of what Home holds of it ([held]), so the videos Home
     * already has count as read even when its cursors are not known (a Home drawn from its cache).
     */
    fun lanes(chatIds: List<Long>, reads: Map<Long, FeedLane>, held: Map<Long, List<MediaItem>>): List<FeedLane> =
        chatIds.distinct().map { chatId ->
            val read = reads[chatId]
            val heldOldest = held[chatId].orEmpty().minOfOrNull { it.date }
            val oldest = listOfNotNull(read?.oldest, heldOldest).minOrNull()
            FeedLane(
                chatId = chatId,
                cursors = read?.cursors ?: MediaCursors(),
                oldest = oldest,
                done = read?.done == true,
            )
        }
}
