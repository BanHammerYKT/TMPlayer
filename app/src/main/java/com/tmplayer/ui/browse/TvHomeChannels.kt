package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.App
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.TvChannels
import com.tmplayer.data.TvChannelsPublisher
import com.tmplayer.data.WatchPoint
import com.tmplayer.i18n.L
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A show a card on the TV home screen asked for, waiting for Home to open its page. */
object HomeLaunch {
    val show = MutableStateFlow<TvChannels.Target.Show?>(null)

    /** How long the ask waits for the show to turn up in Home's rows. */
    const val WAIT_MS = 60_000L

    /** The show [ask] names among [rows], or null while it is not there (yet). */
    fun find(ask: TvChannels.Target.Show, rows: List<HomeRow>): com.tmplayer.data.Series? =
        find(ask.seriesKey, ask.chatId, rows)

    /** The show with [seriesKey] in [chatId] among [rows], or null while it is not there (yet). */
    fun find(seriesKey: String, chatId: Long, rows: List<HomeRow>): com.tmplayer.data.Series? =
        rows.asSequence()
            .flatMap { row ->
                when (row) {
                    is HomeRow.Recent -> row.entries.asSequence()
                    is HomeRow.Chat -> row.entries.asSequence()
                    is HomeRow.Across -> row.entries.asSequence()
                    else -> emptySequence()
                }
            }
            .filterIsInstance<com.tmplayer.data.ShelfEntry.Show>()
            .map { it.series }
            .firstOrNull { it.key == seriesKey && it.cover.chatId == chatId }
}

/**
 * Keeps TMPlayer's channels on the Android TV home screen in step with Home's [rows] (see
 * [TvChannels] for what goes in them, [TvChannelsPublisher] for how and when). Draws nothing, and
 * does nothing at all on a phone, a Fire TV or the promo build's fixture-only screens.
 *
 * - Asks Home for Recently added (or the single starred chat's row) as soon as the viewer is signed
 *   in, so the channel fills even when Home itself is not opened.
 * - Writes a couple of seconds after the rows settle, so a Home filling row by row is one write.
 * - Takes every card out when the kids lock goes on, then puts back what the lock allows; empties the
 *   channels when the setting is turned off.
 *
 * @param enabled the Settings switch; null until it has been read, which does nothing either way.
 */
@Composable
fun TvHomeChannels(
    model: HomeViewModel,
    rows: List<HomeRow>,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    watchProgress: Map<String, WatchPoint>,
    signedIn: Boolean,
    enabled: Boolean?,
) {
    val context = LocalContext.current.applicationContext
    val supported = remember { TvChannelsPublisher.supported(context) }
    if (!supported) return
    val allowed by ChatGate.allowed.collectAsStateWithLifecycle()
    val art by model.art.collectAsStateWithLifecycle()
    val starred = remember(chats, favourites) { HomeRows.favouriteChats(chats, favourites) }

    // Off empties the channels rather than deleting them. The launcher hides an empty channel, and
    // keeps the place the viewer gave it: Android lets an app's first channel onto the home screen
    // without asking, but a channel made again after a delete waits in the launcher's settings.
    LaunchedEffect(enabled) {
        if (enabled == false) App.backgroundScope.launch { TvChannelsPublisher.clearPrograms(context) }
    }

    // What the default channel is drawn from, asked for whether or not Home is on screen. Both are
    // asked once per Home, so this costs nothing once Home has asked for itself.
    val starredIds = remember(starred) { starred.map { it.id } }
    LaunchedEffect(enabled, signedIn, starredIds) {
        if (enabled != true || !signedIn) return@LaunchedEffect
        // Home fills itself from the search across chats, which with one starred chat or none is
        // where the default channel's videos come from too.
        model.requestRecent()
        starredIds.singleOrNull()?.let(model::request)
    }
    // Watch later's records keep no picture; Home looks each one up for its tile, and so do the cards.
    val later = remember(rows) { rows.filterIsInstance<HomeRow.WatchLater>().firstOrNull()?.records.orEmpty() }
    LaunchedEffect(enabled, signedIn, later) {
        if (enabled != true || !signedIn) return@LaunchedEffect
        later.forEach(model::requestArt)
    }

    // The kids lock going on clears the cards outright, before the plan below puts back the ones
    // from the allowed chats: nothing from the other chats may sit on the home screen meanwhile.
    val locked = allowed != null
    LaunchedEffect(locked, enabled) {
        if (locked && enabled == true) App.backgroundScope.launch { TvChannelsPublisher.clearPrograms(context) }
    }

    val titles = remember(chats) { chats.associate { it.id to it.title } }
    val plan = remember(rows, starred.size, allowed, art, watchProgress, titles) {
        runCatching {
            TvChannels.plan(
                rows = rows,
                starred = starred.size,
                allowed = allowed,
                chatTitle = { titles[it].orEmpty() },
                progress = { chat, message -> watchProgress[SettingsStore.progressKey(chat, message)] },
                art = art,
            )
        }.getOrNull()
    }
    LaunchedEffect(plan, enabled, signedIn) {
        if (enabled != true || !signedIn || plan == null) return@LaunchedEffect
        // Rows arrive one after another as Home fills; the write waits for them to settle.
        delay(SETTLE_MS)
        App.backgroundScope.launch {
            TvChannelsPublisher.publish(context, plan) { channel ->
                when (channel) {
                    TvChannels.Channel.Recent -> L.homeChannelRecent
                    TvChannels.Channel.WatchLater -> L.homeRowWatchLater
                    TvChannels.Channel.NewEpisodes -> L.homeRowNewEpisodes
                }
            }
        }
    }
}

private const val SETTLE_MS = 2_500L
