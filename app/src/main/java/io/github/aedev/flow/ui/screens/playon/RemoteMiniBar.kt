package io.github.aedev.flow.ui.screens.playon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.ui.components.shared.MediaMiniBarBounds
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import kotlin.math.roundToInt

/**
 * The mini player for another device's session while nothing plays here, as music's mini player
 * shows it: what plays, "Playing on <name>", and transport that drives that device. Tapping it
 * opens "Play on". It rests where the shell's mini bars rest.
 */
@Composable
internal fun RemoteMiniBar(
    bounds: MediaMiniBarBounds,
    containerHeightPx: Float,
    restingBottomPx: () -> Float,
) {
    val playOn = LocalPlayOn.current ?: return
    val elsewhere by playOn.elsewhere.collectAsStateWithLifecycle()
    val remote = elsewhere ?: return
    val video = remote.now.video ?: return
    val playing = remote.now.state == LiveMessage.PLAYING
    val density = LocalDensity.current
    val heightPx = with(density) { MediaMiniBarDefaults.Height.toPx() }
    val spacerPx = with(density) { MediaMiniBarDefaults.BottomSpacer.toPx() }
    Surface(
        shape = RoundedCornerShape(MediaMiniBarDefaults.CornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .offset { IntOffset(bounds.start.roundToInt(), (containerHeightPx - restingBottomPx() - spacerPx - heightPx).roundToInt()) }
                .size(with(density) { bounds.width.toDp() }, MediaMiniBarDefaults.Height)
                .clickable { playOn.open.value = true },
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = "https://i.ytimg.com/vi/$video/hqdefault.jpg",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .padding(vertical = 10.dp)
                        .fillMaxHeight()
                        .aspectRatio(if (remote.now.music) 1f else 16f / 9f)
                        .clip(RoundedCornerShape(12.dp)),
            )
            Column(Modifier.padding(horizontal = 12.dp).weight(1f), verticalArrangement = Arrangement.Center) {
                Text(remote.now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.play_on_playing_on, remote.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { playOn.controlElsewhere("previous") }) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.previous))
            }
            IconButton(onClick = { playOn.controlElsewhere(if (playing) "pause" else "play") }) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                )
            }
            IconButton(onClick = { playOn.controlElsewhere("next") }) {
                Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next))
            }
        }
    }
}
