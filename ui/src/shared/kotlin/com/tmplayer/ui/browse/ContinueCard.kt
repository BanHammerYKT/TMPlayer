package com.tmplayer.ui.browse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.data.LessonSteps
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SeriesShelf
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.GeneratedCover
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.TileBadge
import com.tmplayer.ui.components.TileBadgeInset
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.OnlineArt
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Scrims
import com.tmplayer.ui.theme.Tone

/**
 * A Continue watching card's art: the picture (or the generated cover), the tile's foot fade, how
 * long is left in a chip at the top end, and a pill of progress across the foot. The name is not
 * on the art: the card writes it under the picture ([continueCardTitle]), above its facts line, so
 * it is never there twice.
 */
@Composable
fun ContinueCardArt(record: ResumeRecord, item: MediaItem, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val cover = rememberCover(item)
    Box(modifier) {
        OnlineArt(item, Modifier.fillMaxSize()) {
            MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
                GeneratedCover(cover, Modifier.fillMaxSize(), showTitle = false)
            }
        }
        Box(Modifier.fillMaxSize().background(Scrims.tileFoot))
        if (record.remainingMs > 0) {
            TileBadge(
                s.browseTimeLeft(s.formatter.duration(record.remainingMs / 1000)),
                Modifier.align(Alignment.TopEnd).padding(TileBadgeInset),
            )
        }
        PillProgress(
            record.fraction,
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}

/**
 * The name a Continue watching card writes under its picture: "S1 E3  ·  Harbour Notes" for an
 * episode, "Part 4  ·  Small shelf" for a lesson, the film's or the video's name otherwise.
 */
fun continueCardTitle(item: MediaItem, s: Messages): String {
    val words = ContinueWords.of(item, s)
    return listOfNotNull(words.label, words.title.ifBlank { null }).joinToString("  ·  ")
}

/** What a continue card says of its video: "S1 E3", "Part 4" or nothing, then the name. */
internal class ContinueWords(val label: String?, val title: String) {
    companion object {
        fun of(item: MediaItem, s: Messages): ContinueWords {
            val parsed = SeriesShelf.episodeOf(item)
            val episode = parsed.episode?.let { number ->
                parsed.season?.let { s.seriesEpisodeShort(it, number) } ?: s.seriesEpisode(number)
            }
            if (episode != null) {
                val name = parsed.title.ifBlank { SeriesShelf.tileTitle(item) }
                // A file named by its episode alone ("Episode 1") is not given the label twice.
                return if (name.isBlank() || episode.startsWith(name, ignoreCase = true)) ContinueWords(null, episode) else ContinueWords(episode, name)
            }
            val lesson = LessonSteps.lessonOf(item)
            if (lesson != null) return ContinueWords(lesson.code, readable(lesson.course).ifBlank { SeriesShelf.tileTitle(item) })
            return ContinueWords(null, if (SeriesShelf.isFilm(item)) SeriesShelf.posterTitle(item) else SeriesShelf.tileTitle(item))
        }
    }
}

/**
 * How far through: a [PILL_PROGRESS] high pill in the accent on a [Tone.track], rounded at both
 * ends. Grows from the start edge, so from the right in a right to left layout.
 */
@Composable
fun PillProgress(fraction: Float, modifier: Modifier = Modifier) {
    val track = Tone.track
    val fill = if (LocalDarkTheme.current) Tone.accent else MaterialTheme.colorScheme.inversePrimary
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val done = fraction.coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(PILL_PROGRESS)) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val width = (size.width * done).coerceAtLeast(if (done > 0f) size.height else 0f)
        if (width > 0f) {
            drawRoundRect(
                fill,
                topLeft = Offset(if (rtl) size.width - width else 0f, 0f),
                size = Size(width, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** The pill of progress on a continue card: 3 dp, as Nuvio measures it. */
private val PILL_PROGRESS = 3.dp

/**
 * A course's name as a file name writes it ("small-shelf"), read as words ("Small shelf"):
 * separators become spaces, and a name in lower case alone gets its first letter raised.
 */
internal fun readable(name: String): String {
    val words = name.replace(Regex("[._-]+"), " ").trim().replace(Regex("\\s+"), " ")
    return if (words == words.lowercase()) words.replaceFirstChar { it.titlecase() } else words
}
