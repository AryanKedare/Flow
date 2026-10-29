package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.service.LinkPhase
import io.github.aedev.flow.ui.screens.sync.SyncActionRow
import io.github.aedev.flow.ui.screens.sync.SyncScanContent

/** Linked devices, below the one-time sync choices on the Sync screen. */
@Composable
internal fun LinkedDevicesSection(
    hostOnly: Boolean,
    viewModel: LinkedDevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    LaunchedEffect(state.linked) { if (state.linked) viewModel.refreshDevices() }

    HorizontalDivider()
    val words = state.words
    var scanning by rememberSaveable { mutableStateOf(false) }
    when {
        words != null -> {
            LinkedRecoveryWords(words, state.busy, viewModel::confirmWordsSaved)
        }

        link != LinkPhase.Idle -> {
            LinkedLinkPanel(link, viewModel::answer, viewModel::closeLink)
        }

        scanning -> {
            SyncScanContent(prompt = stringResource(R.string.linked_scan_new_device)) {
                scanning = false
                viewModel.enterCode(it)
            }
            SyncActionRow(confirmLabel = stringResource(R.string.linked_back), onConfirm = { scanning = false })
        }

        state.linked -> {
            LinkedOverview(
                state = state,
                onSyncNow = viewModel::syncNow,
                onAddDevice = viewModel::addDevice,
                onScanNewDevice = if (hostOnly) null else ({ scanning = true }),
                onRename = viewModel::rename,
                onRemove = viewModel::remove,
                onForget = viewModel::forget,
            )
        }

        else -> {
            LinkedSetup(hostOnly, state.busy, viewModel::create, viewModel::enterCode, viewModel::showCode, viewModel::restore)
        }
    }
}
