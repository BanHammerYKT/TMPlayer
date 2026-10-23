package com.tmplayer.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.tmplayer.MainActivity
import com.tmplayer.R
import com.tmplayer.data.TvChannels.Channel
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import com.tmplayer.online.OnlineMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/**
 * Writes [TvChannels]' cards into the TV provider, where the Android TV home screen reads an
 * app's channels from: the rows of cards under the app's name on the classic launcher.
 *
 * The platform's own `TvContract`, as [WatchNextPublisher] uses, rather than the androidx
 * tvprovider library, for the same reason. Every call is best effort: a launcher without
 * channels, a provider that refuses a write, or a viewer who hid a channel all end in nothing
 * happening, never in a crash.
 *
 * **When it runs.** Only while the app's process is up: the cards are Home's rows, which need a
 * signed in TDLib, and TDLib lives in this process. A periodic background job would have to start
 * the process and open the Telegram session just to refresh a row of pictures, which a 1 GB stick
 * should not pay for. So the channels are refreshed when the app opens, whenever Home's rows change
 * while it runs (a post in a starred chat, a video saved for later), and after playback, which
 * changes Continue watching and with it Recently added.
 *
 * **Pictures.** A card's picture must be a URI the launcher's process can read. With the online
 * lookups on, the film's or show's wide picture (its backdrop, an episode's still) in the
 * launcher's own 16:9 shape, else its upright poster, else the video's own thumbnail from
 * Telegram; each is copied into [TvArtProvider]'s directory and handed out from there. A card
 * whose picture could not be had yet wears the app's mark, as "Play next" does, and is tried again
 * on the next refresh.
 */
object TvChannelsPublisher {

    private const val TAG = "TvChannels"

    /** The launch intent's action for a folded show's card: open the show's page on Home. */
    const val ACTION_OPEN_SHOW = "com.tmplayer.action.OPEN_SHOW_FROM_HOME"

    private const val EXTRA_CHAT = "ch_chat"
    private const val EXTRA_SERIES = "ch_series"
    private const val EXTRA_TITLE = "ch_title"

    private const val LOGO_SIZE = 256
    private const val ART_TIMEOUT_MS = 20_000L

    /**
     * Online lookups not already answered from the cache, and wide pictures fetched, that one
     * publish may make. A 1 GB stick paid for a lookup and a backdrop per card, a row of them at
     * a time, while the viewer was trying to start a video. The cards past it wear Telegram's
     * thumbnail for now and are tried again, a few at a time, on the next refreshes.
     */
    private const val ONLINE_PER_RUN = 4

    /**
     * How long after the process starts a publish asks nothing of the network for pictures:
     * the first half minute is when the app is opened and a video started, and both are slower
     * with the launcher's cards fetching beside them. Only answers already cached are used then.
     */
    private const val START_QUIET_MS = 30_000L

    private val lock = Mutex()

    @Volatile
    private var supportedCache: Boolean? = null

    /**
     * The promo build's demo pictures, which live in its resources rather than in TDLib: a file for
     * a card's [TvChannels.Art], tried after the poster and before Telegram's thumbnail. Null, and
     * so never consulted, in every other build.
     */
    @Volatile
    internal var fixtureArt: ((TvChannels.Art) -> File?)? = null

    /**
     * Whether this device has a home screen that shows channels: a television with the TV
     * provider, and not a Fire TV, whose launcher reads neither channels nor "Play next".
     */
    @androidx.annotation.ChecksSdkIntAtLeast(api = android.os.Build.VERSION_CODES.O)
    fun supported(context: Context): Boolean = supportedCache ?: runCatching {
        val pm = context.packageManager
        // Channels and Play next are the TV provider of Android 8 and later. Android 7's launcher
        // had only the recommendations row, so an older television simply gets no channels.
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            DeviceQuirks.remote(context).watchNextSupported &&
            pm.resolveContentProvider(TvContract.AUTHORITY, 0) != null
    }.getOrDefault(false).also { supportedCache = it }

    /** The show a card asks for, or null for any other launch. */
    fun showFrom(intent: Intent?): TvChannels.Target.Show? {
        if (intent?.action != ACTION_OPEN_SHOW) return null
        val chat = intent.getLongExtra(EXTRA_CHAT, 0)
        val series = intent.getStringExtra(EXTRA_SERIES).orEmpty()
        if (chat == 0L || series.isEmpty()) return null
        return TvChannels.Target.Show(chat, series, intent.getStringExtra(EXTRA_TITLE).orEmpty())
    }

    /**
     * Makes the channels hold [programs]: missing channels created, cards inserted, rewritten or
     * removed as [TvChannels.diff] says. [names] gives each channel its title in the UI language.
     */
    suspend fun publish(context: Context, programs: List<TvChannels.Program>, names: (Channel) -> String) {
        if (!supported(context)) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching { publishLocked(context, programs, names) }.onFailure { Log.w(TAG, "channels not written", it) }
            }
        }
    }

    /**
     * Takes every card out of the channels and leaves the channels, which the launcher then hides:
     * the kids lock went on, the setting was turned off, or the viewer signed out. The channels are
     * never deleted, since one made again would no longer be let onto the home screen by itself.
     */
    suspend fun clearPrograms(context: Context) {
        if (!supported(context)) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    ownChannels(context).values.forEach { channel ->
                        context.contentResolver.delete(TvContract.buildPreviewProgramsUriForChannel(channel.id), null, null)
                    }
                    pruneArt(context, emptySet())
                }.onFailure { Log.w(TAG, "channels not cleared", it) }
            }
        }
    }

    // ---- channels -------------------------------------------------------------------------------

    private class ChannelRow(val id: Long, val name: String?)

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun ownChannels(context: Context): Map<Channel, ChannelRow> = runCatching {
        val projection = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
        )
        context.contentResolver.query(TvContract.Channels.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val channel = Channel.ofKey(cursor.getString(1)) ?: continue
                    put(channel, ChannelRow(cursor.getLong(0), cursor.getString(2)))
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyMap())

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun ensureChannel(context: Context, channel: Channel, name: String, existing: ChannelRow?): Long? {
        if (existing != null) {
            if (existing.name != name) {
                runCatching {
                    context.contentResolver.update(
                        TvContract.buildChannelUri(existing.id),
                        ContentValues().apply { put(TvContract.Channels.COLUMN_DISPLAY_NAME, name) },
                        null,
                        null,
                    )
                }
            }
            return existing.id
        }
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, name)
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, channel.key)
            // The channel's logo opens the app as it is.
            put(
                TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).toUri(Intent.URI_INTENT_SCHEME),
            )
        }
        val uri = context.contentResolver.insert(TvContract.Channels.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)
        Log.i(TAG, "channel ${channel.key}: $uri")
        writeLogo(context, id)
        // The default channel goes on the home screen without asking: Android lets an app's first
        // channel through. The others wait in the launcher's channel list for the viewer to add.
        if (channel == Channel.Recent) {
            runCatching { TvContract.requestChannelBrowsable(context, id) }.onFailure { Log.w(TAG, "channel not made browsable", it) }
        }
        return id
    }

    private fun writeLogo(context: Context, channelId: Long) {
        runCatching {
            val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return
            val bitmap = Bitmap.createBitmap(LOGO_SIZE, LOGO_SIZE, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, LOGO_SIZE, LOGO_SIZE)
            icon.draw(Canvas(bitmap))
            context.contentResolver.openOutputStream(TvContract.buildChannelLogoUri(channelId))?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
        }.onFailure { Log.w(TAG, "channel logo not written", it) }
    }

    // ---- cards ----------------------------------------------------------------------------------

    private class ProgramRow(val existing: TvChannels.Existing, val art: String?)

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun ownPrograms(context: Context, channelId: Long): Map<String, ProgramRow> = runCatching {
        val projection = arrayOf(
            TvContract.PreviewPrograms._ID,
            TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_DATA,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
        )
        context.contentResolver.query(TvContract.buildPreviewProgramsUriForChannel(channelId), projection, null, null, null)
            ?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val internal = cursor.getString(1) ?: continue
                        val data = runCatching { cursor.getBlob(2) }.getOrNull()?.let { String(it, Charsets.UTF_8) }
                        put(
                            internal,
                            ProgramRow(TvChannels.Existing(cursor.getLong(0), data, cursor.getInt(3) != 0), cursor.getString(4)),
                        )
                    }
                }
            }.orEmpty()
    }.getOrDefault(emptyMap())

    /**
     * What one publish may still ask of the network for pictures ([ONLINE_PER_RUN]). A card the
     * run cannot afford is left out and asked for again on the next run.
     */
    private class OnlineBudget(var left: Int) {
        fun take(): Boolean {
            if (left <= 0) return false
            left--
            return true
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private suspend fun publishLocked(context: Context, programs: List<TvChannels.Program>, names: (Channel) -> String) {
        val channels = ownChannels(context)
        val quiet = runCatching { SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < START_QUIET_MS }
            .getOrDefault(false)
        val budget = OnlineBudget(if (quiet) 0 else ONLINE_PER_RUN)
        val byChannel = programs.groupBy { it.channel }
        val keptArt = HashSet<String>()
        for (channel in Channel.entries) {
            val wanted = byChannel[channel].orEmpty()
            val row = channels[channel]
            // An optional channel is only made once it has something in it: an empty one would
            // sit in the launcher's channel list with nothing to show for it.
            if (row == null && wanted.isEmpty() && channel != Channel.Recent) continue
            val channelId = ensureChannel(context, channel, names(channel), row) ?: continue
            val existing = ownPrograms(context, channelId)
            val diff = TvChannels.diff(existing.mapValues { it.value.existing }, wanted)
            diff.delete.forEach { id ->
                runCatching { context.contentResolver.delete(TvContract.buildPreviewProgramUri(id), null, null) }
            }
            val deleted = diff.delete.toHashSet()
            existing.values.filter { it.existing.rowId !in deleted }.mapNotNullTo(keptArt) { artName(context, it.art) }
            for (program in diff.insert) {
                // A card without a real picture is left out, and asked for again on the next run.
                val values = values(context, channelId, program, keptArt, budget) ?: continue
                runCatching { context.contentResolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values) }
                    .onFailure { Log.w(TAG, "card not added", it) }
            }
            for ((id, program) in diff.update) {
                val values = values(context, channelId, program, keptArt, budget)
                if (values == null) {
                    runCatching { context.contentResolver.delete(TvContract.buildPreviewProgramUri(id), null, null) }
                    continue
                }
                runCatching { context.contentResolver.update(TvContract.buildPreviewProgramUri(id), values, null, null) }
                    .onFailure { Log.w(TAG, "card not updated", it) }
            }
            if (!diff.isEmpty) {
                Log.i(TAG, "${channel.key}: +${diff.insert.size} ~${diff.update.size} -${diff.delete.size}")
            }
        }
        pruneArt(context, keptArt)
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private suspend fun values(
        context: Context,
        channelId: Long,
        program: TvChannels.Program,
        keptArt: MutableSet<String>,
        budget: OnlineBudget,
    ): ContentValues? {
        // Only cards with the film's or show's own artwork go on the home screen (user,
        // 2026-10-23): a frame from the file or the app's stand-in picture looked unfinished
        // beside them. One whose picture is not known yet stays off until a later run finds it.
        val art = withTimeoutOrNull(ART_TIMEOUT_MS) { resolveArt(context, program.art, budget) } ?: return null
        keptArt += art.first
        val fingerprint = program.fingerprint
        return ContentValues().apply {
            put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, program.internalId)
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_DATA, fingerprint.toByteArray(Charsets.UTF_8))
            put(
                TvContract.PreviewPrograms.COLUMN_TYPE,
                if (program.isShow) TvContract.PreviewPrograms.TYPE_TV_SERIES else TvContract.PreviewPrograms.TYPE_MOVIE,
            )
            put(TvContract.PreviewPrograms.COLUMN_TITLE, program.title)
            put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, program.description)
            put(TvContract.PreviewPrograms.COLUMN_WEIGHT, program.weight)
            if (program.durationMs > 0) {
                put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, program.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            if (program.positionMs > 0) {
                put(
                    TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                    program.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
            put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvArtProvider.uriFor(context, art.first).toString())
            put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, art.second)
            put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, intentFor(context, program.target).toUri(Intent.URI_INTENT_SCHEME))
        }
    }

    private fun intentFor(context: Context, target: TvChannels.Target): Intent = when (target) {
        is TvChannels.Target.Play -> WatchNextPublisher.intentFor(context, target.video)
        is TvChannels.Target.Show -> Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_SHOW
            putExtra(EXTRA_CHAT, target.chatId)
            putExtra(EXTRA_SERIES, target.seriesKey)
            putExtra(EXTRA_TITLE, target.title)
        }
    }

    // ---- pictures -------------------------------------------------------------------------------

    /** The copy's name in [TvArtProvider.dir] and its aspect ratio, or null without the title's own artwork. */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private suspend fun resolveArt(context: Context, art: TvChannels.Art, budget: OnlineBudget): Pair<String, Int>? {
        found(art, budget)?.let { info ->
            // The wide picture (a film's or show's backdrop), a better card than a frame from the
            // file. Only landscape pictures: a 2:3 poster beside them made the launcher's row
            // uneven. A few downloads each run; one the run cannot afford is left for the next.
            info.wideUrl?.let { url ->
                if (budget.take()) fetched(context, url, "w")?.let { return it to TvContract.PreviewPrograms.ASPECT_RATIO_16_9 }
            }
        }
        fixtureArt?.invoke(art)?.let { file ->
            return copyIn(context, file, "f_${sha1(art.key)}.jpg")?.let { it to TvContract.PreviewPrograms.ASPECT_RATIO_16_9 }
        }
        // No frame from the file: a card without the title's own artwork stays off the home screen.
        return null
    }

    /** What the online lookups know of the film or show, when the viewer turned them on. */
    private suspend fun found(art: TvChannels.Art, budget: OnlineBudget): com.tmplayer.online.MetaInfo? {
        val online = OnlineMetadata.current ?: return null
        if (!OnlineMetadata.enabled) return null
        val query = MetaQuery.of(art.name, art.caption.ifBlank { null })?.showOnly() ?: return null
        // What Home has already learnt costs nothing; only a title not yet looked up spends the run's budget.
        online.peek(query)?.let { known -> return (known as? MetaResult.Found)?.info }
        runCatching { online.cachedMatch(query) }.getOrNull()?.let { return it }
        if (!budget.take()) return null
        return (runCatching { online.lookup(query) }.getOrNull() as? MetaResult.Found)?.info
    }

    /** One of its pictures, fetched and copied in under [prefix]: "w" wide, "p" poster. */
    private suspend fun fetched(context: Context, url: String, prefix: String): String? {
        val online = OnlineMetadata.current ?: return null
        val file = runCatching { online.image(url) }.getOrNull() ?: return null
        return copyIn(context, file, "${prefix}_${sha1(url)}.jpg")
    }

    private fun copyIn(context: Context, source: File, name: String): String? = runCatching {
        if (!TvArtProvider.isValidName(name) || !source.isFile) return null
        val dir = TvArtProvider.dir(context).apply { mkdirs() }
        val target = File(dir, name)
        if (!target.isFile || target.length() != source.length()) {
            val temp = File(dir, "$name.part")
            source.copyTo(temp, overwrite = true)
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
        }
        name
    }.getOrNull()

    /** The copy a card's stored picture URI names, when it is one of this app's. */
    private fun artName(context: Context, uri: String?): String? {
        val prefix = "content://${TvArtProvider.authority(context)}/"
        if (uri == null || !uri.startsWith(prefix)) return null
        return uri.removePrefix(prefix).takeIf { TvArtProvider.isValidName(it) }
    }

    /** Deletes the copies no card names any more. */
    private fun pruneArt(context: Context, kept: Set<String>) {
        runCatching {
            TvArtProvider.dir(context).listFiles()?.forEach { file -> if (file.name !in kept) file.delete() }
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
