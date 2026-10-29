package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.sync2.service.LinkPhase

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
    when {
        words != null -> {
            LinkedRecoveryWords(words, state.busy, viewModel::confirmWordsSaved)
        }

        link != LinkPhase.Idle -> {
            LinkedLinkPanel(link, viewModel::answer, viewModel::closeLink)
        }

        state.linked -> {
            LinkedOverview(
                state = state,
                onSyncNow = viewModel::syncNow,
                onAddDevice = viewModel::addDevice,
                onRename = viewModel::rename,
                onRemove = viewModel::remove,
                onForget = viewModel::forget,
            )
        }

        else -> {
            LinkedSetup(hostOnly, state.busy, viewModel::create, viewModel::join, viewModel::restore)
        }
    }
}
