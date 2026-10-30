package io.github.aedev.flow.ui.screens.playon

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
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
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.live.ConsentAnswer
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.ui.components.PlayingWaveform
import io.github.aedev.flow.ui.components.shared.rememberMediaPalette
import kotlinx.coroutines.delay

/** The playback position moves on this often while the sheet shows. */
private const val TICK_MS = 1_000L

private fun thumbnail(video: String) = "https://i.ytimg.com/vi/$video/hqdefault.jpg"

private fun kindIcon(kind: String): ImageVector =
    when (kind) {
        "tv" -> Icons.Outlined.Tv
        "tablet" -> Icons.Outlined.TabletAndroid
        "desktop" -> Icons.Outlined.Computer
        else -> Icons.Outlined.PhoneAndroid
    }

private fun time(ms: Long): String = DateUtils.formatElapsedTime(ms.coerceAtLeast(0) / 1000)

/** "Play on" for the whole app, provided once at the root; absent where it isn't, as in previews and tests. */
val LocalPlayOn = staticCompositionLocalOf<PlayOnViewModel?> { null }

/**
 * Opens "Play on" (Flow-Tube/Flow#1412), when the group can reach another device. Tinted while
 * another device plays, so the player shows that it isn't playing here.
 */
@Composable
fun PlayOnButton(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val viewModel = LocalPlayOn.current ?: return
    val available by viewModel.available.collectAsStateWithLifecycle()
    if (!available) return
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val elsewhere = devices.firstOrNull { it.group == PlayOnGroup.PLAYING && !it.thisDevice }
    IconButton(onClick = { viewModel.open.value = true }, modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.Devices,
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
@OptIn(ExperimentalMaterial3Api::class)
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
        ModalBottomSheet(
            onDismissRequest = { viewModel.open.value = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PlayOnContent(viewModel)
        }
    }
    request?.let { asking ->
        val name = viewModel.nameOf(asking.did)
        AlertDialog(
            onDismissRequest = { viewModel.answer(asking, ConsentAnswer.DECLINE) },
            icon = { Icon(Icons.Outlined.Devices, contentDescription = null) },
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

/** The sheet itself: the active session on top, then the devices grouped by what they do. */
@Composable
internal fun PlayOnContent(viewModel: PlayOnViewModel) {
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
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(stringResource(R.string.play_on_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.play_on_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (session != null) {
            NowPlayingCard(session, busy) { cmd, pos, vol -> viewModel.control(session, cmd, pos, vol) }
        } else {
            Text(stringResource(R.string.play_on_nothing), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DeviceSections(devices, busy, onPick = viewModel::playOn)
        Spacer(Modifier.height(8.dp))
    }
}

/** What plays on the active device: artwork tinted card, its controls, and where it plays. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NowPlayingCard(
    session: PlayOnSession,
    busy: Boolean,
    onControl: (cmd: String, pos: Long?, vol: Int?) -> Unit,
) {
    val now = session.now
    val video = now.video ?: return
    val palette = rememberMediaPalette(thumbnail(video))
    val playing = now.state == LiveMessage.PLAYING
    Surface(color = palette.base, contentColor = palette.onBase, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = thumbnail(video),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .width(if (now.music) 72.dp else 112.dp)
                            .aspectRatio(if (now.music) 1f else 16f / 9f)
                            .clip(RoundedCornerShape(16.dp)),
                )
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alpha(0.8f),
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.play_on_on_device, session.device.name)) },
                        leadingIcon = {
                            Icon(
                                kindIcon(session.device.kind),
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(labelColor = palette.onBase, leadingIconContentColor = palette.onBase),
                    )
                }
            }
            if (now.dur > 0) {
                LinearWavyProgressIndicator(
                    progress = { (now.pos.toFloat() / now.dur).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.accent,
                )
                Row {
                    Text(time(now.pos), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(time(now.dur), style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onControl("previous", null, null) }, enabled = !busy) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.previous))
                }
                FilledIconButton(
                    onClick = { onControl(if (playing) "pause" else "play", null, null) },
                    enabled = !busy,
                    modifier = Modifier.padding(horizontal = 16.dp).size(IconButtonDefaults.mediumContainerSize()),
                ) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                    )
                }
                FilledTonalIconButton(onClick = { onControl("next", null, null) }, enabled = !busy) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.next))
                }
            }
            var volume by remember(session.device.did) { mutableFloatStateOf(now.vol / 100f) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VolumeUp, contentDescription = stringResource(R.string.play_on_volume))
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = { onControl("volume", null, (volume * 100).toInt()) },
                    enabled = !busy,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
            }
        }
    }
}

/** The devices grouped: playing now, available, offline. Tapping one moves playback there. */
@Composable
internal fun DeviceSections(
    devices: List<PlayOnDevice>,
    busy: Boolean,
    onPick: (PlayOnDevice) -> Unit,
) {
    for ((group, title) in listOf(
        PlayOnGroup.PLAYING to R.string.play_on_playing_now,
        PlayOnGroup.AVAILABLE to R.string.play_on_available,
        PlayOnGroup.OFFLINE to R.string.play_on_offline,
    )) {
        val inGroup = devices.filter { it.group == group }.sortedByDescending { it.thisDevice }
        if (inGroup.isEmpty()) continue
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                inGroup.forEach { device -> DeviceRow(device, enabled = !busy && group != PlayOnGroup.OFFLINE) { onPick(device) } }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: PlayOnDevice,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val title = device.now?.title.orEmpty()
    val status =
        when (device.group) {
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
    ListItem(
        headlineContent = {
            Text(
                if (device.thisDevice) stringResource(R.string.play_on_this_device, device.name) else device.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(kindIcon(device.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        },
        trailingContent = {
            if (device.group == PlayOnGroup.PLAYING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayingWaveform(animate = true)
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .alpha(if (device.group == PlayOnGroup.OFFLINE) 0.6f else 1f)
                .clickable(enabled = enabled, onClick = onClick),
    )
}
