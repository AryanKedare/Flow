package io.github.aedev.flow.ui.screens.playon

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SliderStyle
import io.github.aedev.flow.sync2.live.ConsentAnswer
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.ui.components.PlayingWaveform
import io.github.aedev.flow.ui.components.musicplayer.controls.ExpressivePlayerSlider
import io.github.aedev.flow.ui.components.musicplayer.controls.expressiveSliderSpec
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.MediaPlaybackControls
import io.github.aedev.flow.ui.components.shared.VideoThumbnailImage
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet
import kotlinx.coroutines.delay

/** The playback position moves on this often while the sheet shows. */
private const val TICK_MS = 1_000L

private fun kindIcon(kind: String): ImageVector =
    when (kind) {
        "tv" -> Icons.Outlined.Tv
        "tablet" -> Icons.Outlined.TabletAndroid
        "desktop" -> Icons.Outlined.Computer
        else -> Icons.Outlined.PhoneAndroid
    }

/** "Play on" for the whole app, provided once at the root; absent where it isn't, as in previews and tests. */
val LocalPlayOn = staticCompositionLocalOf<PlayOnViewModel?> { null }

/**
 * Opens "Play on" (Flow-Tube/Flow#1412), when the group can reach another device. Tinted while
 * the players drive another device, so they show that it isn't playing here.
 */
@Composable
fun PlayOnButton(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val viewModel = LocalPlayOn.current ?: return
    val available by viewModel.available.collectAsStateWithLifecycle()
    if (!available) return
    val elsewhere by viewModel.elsewhere.collectAsStateWithLifecycle()
    IconButton(onClick = { viewModel.open.value = true }, modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.Speaker,
            contentDescription =
                elsewhere?.let { stringResource(R.string.play_on_playing_on, it.name) } ?: stringResource(R.string.play_on_button),
            tint = if (elsewhere != null) MaterialTheme.colorScheme.primary else tint,
        )
    }
}

/**
 * The shell's part of "Play on": the sheet when it is open, the consent question when a device
 * first asks, and a note when another device takes over playback here.
 */
@Composable
fun PlayOnHost() {
    val viewModel = LocalPlayOn.current ?: return
    val open by viewModel.open.collectAsStateWithLifecycle()
    val request by viewModel.consentRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.controlledBy.collect { did ->
            Toast.makeText(context, context.getString(R.string.play_on_controlled_by, viewModel.nameOf(did)), Toast.LENGTH_SHORT).show()
        }
    }
    if (open) {
        QuickActionsSheet(onDismiss = { viewModel.open.value = false }, expandFully = true) { sheet ->
            PlayOnContent(viewModel, onClose = sheet::close)
        }
    }
    request?.let { asking ->
        val name = viewModel.nameOf(asking.did)
        AlertDialog(
            onDismissRequest = { viewModel.answer(asking, ConsentAnswer.DECLINE) },
            icon = { Icon(Icons.Outlined.Speaker, contentDescription = null) },
            title = { Text(stringResource(R.string.play_on_consent_title, name)) },
            text = { Text(stringResource(R.string.play_on_consent_body, name)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = { viewModel.answer(asking, ConsentAnswer.ALWAYS) },
                    ) { Text(stringResource(R.string.play_on_consent_always)) }
                    TextButton(
                        onClick = { viewModel.answer(asking, ConsentAnswer.ONCE) },
                    ) { Text(stringResource(R.string.play_on_consent_once)) }
                    TextButton(
                        onClick = { viewModel.answer(asking, ConsentAnswer.DECLINE) },
                    ) { Text(stringResource(R.string.play_on_consent_decline)) }
                }
            },
        )
    }
}

/** The sheet itself: the active session on top, then the group's devices. */
@Composable
internal fun PlayOnContent(
    viewModel: PlayOnViewModel,
    onClose: () -> Unit,
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Reads the players and moves positions on only while the sheet shows.
    LaunchedEffect(viewModel) {
        viewModel.refresh(withRoster = true)
        while (true) {
            delay(TICK_MS)
            tick = System.currentTimeMillis()
            viewModel.refresh(withRoster = false)
        }
    }
    val session = remember(devices, tick) { viewModel.session(tick) }
    FlowSheetHeader(
        title = stringResource(R.string.play_on_title),
        subtitle = stringResource(R.string.play_on_subtitle),
        onClose = onClose,
        showDragHandle = false,
        dividerAlpha = null,
    )
    if (session != null) {
        SessionPanel(session, busy) { cmd, vol -> viewModel.control(session, cmd, vol = vol) }
    } else {
        Text(
            stringResource(R.string.play_on_nothing),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    DeviceList(devices, busy, onPick = viewModel::playOn)
}

/** What plays on the active device, where it plays, and the music player's own controls for it. */
@Composable
private fun SessionPanel(
    session: PlayOnSession,
    busy: Boolean,
    onControl: (cmd: String, vol: Int?) -> Unit,
) {
    val now = session.now
    val video = now.video ?: return
    val playing = now.state == LiveMessage.PLAYING
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // A YouTube frame is 16:9; the crop drops the bars the fallback thumbnail pads it with.
        VideoThumbnailImage(
            videoId = video,
            model = null,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(24.dp)),
        )
        Column {
            Text(
                now.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (now.by.isNotEmpty()) {
                Text(
                    now.by,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AssistChip(
            onClick = {},
            label = { Text(playingWhere(session.device)) },
            leadingIcon = {
                Icon(kindIcon(session.device.kind), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
            },
        )
        MediaPlaybackControls(
            isPlaying = playing,
            isBuffering = busy,
            onPreviousClick = { onControl("previous", null) },
            onPlayPauseToggle = { onControl(if (playing) "pause" else "play", null) },
            onNextClick = { onControl("next", null) },
        )
        var volume by remember(session.device.did) { mutableFloatStateOf(now.vol / 100f) }
        val slider = expressiveSliderSpec(SliderStyle.COMPACT)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.VolumeUp, contentDescription = stringResource(R.string.play_on_volume))
            ExpressivePlayerSlider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { onControl("volume", (volume * 100).toInt()) },
                valueRange = 0f..1f,
                trackHeight = slider.trackHeight,
                thumbHeight = slider.thumbHeight,
                thumbTrackGap = slider.thumbTrackGap,
            )
        }
    }
}

/** "On this phone" and the like for this device, the device's name for another one. */
@Composable
private fun playingWhere(device: PlayOnDevice): String {
    if (!device.thisDevice) return device.name
    return stringResource(
        when (device.kind) {
            "tv" -> R.string.play_on_this_tv
            "tablet" -> R.string.play_on_this_tablet
            else -> R.string.play_on_this_phone
        },
    )
}

/** The devices as one group, playing ones first, as Settings draws its rows. Tapping one picks it. */
@Composable
internal fun DeviceList(
    devices: List<PlayOnDevice>,
    busy: Boolean,
    onPick: (PlayOnDevice) -> Unit,
) {
    QuickActionsGroup(
        title = stringResource(R.string.linked_devices),
        rows =
            devices.sortedWith(compareBy<PlayOnDevice> { it.group }.thenByDescending { it.thisDevice }).map { device ->
                QuickActionRow(device.did) { shape ->
                    FlowNavRow(
                        title = if (device.thisDevice) stringResource(R.string.play_on_this_device, device.name) else device.name,
                        onClick = { onPick(device) },
                        supportingText = deviceStatus(device),
                        leadingIcon = kindIcon(device.kind),
                        showChevron = false,
                        enabled = !busy && device.group != PlayOnGroup.OFFLINE,
                        selected = device.group == PlayOnGroup.PLAYING,
                        shape = shape,
                        trailingContent = if (device.group == PlayOnGroup.PLAYING) ({ PlayingWaveform() }) else null,
                    )
                }
            },
    )
}

@Composable
private fun deviceStatus(device: PlayOnDevice): String {
    val title = device.now?.title.orEmpty()
    return when (device.group) {
        PlayOnGroup.PLAYING -> {
            stringResource(R.string.play_on_status_playing, title)
        }

        PlayOnGroup.AVAILABLE -> {
            if (device.now?.video !=
                null
            ) {
                stringResource(R.string.play_on_status_paused, title)
            } else {
                stringResource(R.string.play_on_status_ready)
            }
        }

        PlayOnGroup.OFFLINE -> {
            device.seenAt?.let { stringResource(R.string.play_on_last_seen, DateUtils.getRelativeTimeSpanString(it).toString()) }
                ?: stringResource(R.string.play_on_never_seen)
        }
    }
}
