package com.tmplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/**
 * The Appearance page's live preview (REELBOX-PARITY): a poster card as a chat's grid draws one,
 * with a name, a second line, a Play button and a progress bar, in the theme, the accent, the
 * typeface and the contrast as they stand. Every choice above it shows here the moment it is made.
 * Only a picture: a screen reader is told nothing about it, and it takes no focus.
 */
@Composable
fun AppearancePreview(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tone.surface)
            .border(1.dp, Tone.outline, shape)
            .padding(12.dp)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A 2:3 poster of the accent's own colours, as a show with no picture of its own is drawn.
        val scheme = MaterialTheme.colorScheme
        Box(
            Modifier
                .width(56.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer))),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.settingsAppearancePreviewTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Tone.text, maxLines = 1)
            Text(s.settingsAppearancePreviewLine, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tone.surfaceHigh),
            ) {
                Box(Modifier.fillMaxWidth(0.4f).height(4.dp).background(Tone.accent))
            }
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Tone.accent)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(TmIcons.Play, contentDescription = null, tint = Tone.onAccent, modifier = Modifier.size(16.dp))
            Text(s.commonPlay, style = MaterialTheme.typography.labelLarge, color = Tone.onAccent)
        }
    }
}
