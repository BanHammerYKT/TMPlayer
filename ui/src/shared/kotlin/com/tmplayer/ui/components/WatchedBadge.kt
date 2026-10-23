package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/**
 * The tick a video's artwork wears once it is on the "Watched" list, finished or marked by hand.
 *
 * A disc in the app's colour with a thin white edge, so it reads over any thumbnail, dark or
 * light, without a plate behind it. Shared by the phone, the television and the desktop so one
 * finished video looks the same wherever it is listed. Placement is the caller's: pass the
 * alignment and padding in [modifier].
 *
 * [size] is the disc's diameter. The default is [TileBadge]'s height, since on a tile the tick is
 * one of the corner badges and stands where the others would.
 */
@Composable
fun WatchedBadge(
    modifier: Modifier = Modifier,
    size: Dp = TileBadgeHeight,
) {
    val s = LocalStrings.current
    Box(
        modifier
            .size(size)
            .background(Tone.accent, CircleShape)
            .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            TmIcons.Check,
            contentDescription = s.commonWatched,
            tint = Tone.onAccent,
            modifier = Modifier.size(size * 0.64f),
        )
    }
}

/**
 * A word on a tile's art: "Queued", "37%", "Downloaded", "Cached", or a caller's own such as "New".
 *
 * A pill of black at 64 per cent with white text, the same in both themes because it lies on a
 * picture, which is dark under it either way. At most one of these sits in a tile's corner (the
 * queue state, else where the copy is, else the [WatchedBadge] tick), so a tile reads at a glance
 * rather than as a collection of labels. [fill] is for the one that has to stand out, a failed
 * download in the danger tone.
 *
 * At least [TileBadgeHeight] tall and taller when the font is scaled up, never clipped.
 */
@Composable
fun TileBadge(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fill: Color = TileBadgeFill,
) {
    Row(
        modifier
            .heightIn(min = TileBadgeHeight)
            // A rounded fill rather than a clip and a fill: a clip is a layer of its own for each
            // badge, and the words inside stay within the pill either way.
            .background(fill, CircleShape)
            .padding(start = if (icon != null) 5.dp else 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
    }
}

/** How tall a [TileBadge] is, and the tick beside it. */
val TileBadgeHeight: Dp = 20.dp

/** The badge's plate: black at 64 per cent. */
val TileBadgeFill: Color = Color.Black.copy(alpha = 0.64f)

/** How far a badge sits in from the corner of the art. */
val TileBadgeInset: Dp = 6.dp
