package io.github.aedev.flow.ui.components.profiles

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.profile.Profile
import io.github.aedev.flow.data.profile.ProfileRegistry
import io.github.aedev.flow.data.profile.displayName
import io.github.aedev.flow.ui.components.layout.LocalFlowSnackbarHostState
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet

private val TopBarAvatarSize = 32.dp
private val FooterPadding = 20.dp

/**
 * The running profile's avatar as a top bar action, opening the way to switch, add or manage profiles. It shows with
 * a single profile too, so the feature can be found.
 */
@Composable
fun ProfileTopBarAction(
    onOpenSettings: (SettingsTarget) -> Unit,
    viewModel: ProfileSwitchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refused by viewModel.refused.collectAsStateWithLifecycle()
    val running = state?.profiles?.firstOrNull { it.id == viewModel.runningId } ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.profiles_action_description, running.displayName(context))

    IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
        ProfileAvatar(running, size = TopBarAvatarSize)
    }

    val snackbarHostState = LocalFlowSnackbarHostState.current
    val refusedText = refused?.let { stringResource(switchRefusalText(it.reason)) }
    LaunchedEffect(refused) {
        if (refusedText == null) return@LaunchedEffect
        try {
            snackbarHostState?.showSnackbar(refusedText)
        } finally {
            viewModel.consumeRefused()
        }
    }

    if (open) {
        ProfileSwitchSheet(
            profiles = state?.profiles.orEmpty(),
            runningId = viewModel.runningId,
            onSwitch = viewModel::switchTo,
            onAdd = { onOpenSettings(SettingsTarget(SettingsDestination.PROFILE_EDIT)) },
            onManage = { onOpenSettings(SettingsTarget(SettingsDestination.PROFILES)) },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun ProfileSwitchSheet(
    profiles: List<Profile>,
    runningId: String,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val atLimit = profiles.size >= ProfileRegistry.MAX_PROFILES
    QuickActionsSheet(onDismiss = onDismiss) { sheet ->
        QuickActionsGroup(
            title = stringResource(R.string.profiles_title),
            rows =
                profiles.map { profile ->
                    val running = profile.id == runningId
                    QuickActionRow("profile.${profile.id}") { shape ->
                        FlowNavRow(
                            title = profile.displayName(context),
                            supportingText = stringResource(R.string.profiles_in_use).takeIf { running },
                            onClick = {
                                sheet.close()
                                if (!running) onSwitch(profile.id)
                            },
                            selected = running,
                            showChevron = false,
                            leadingContent = { ProfileAvatar(profile) },
                            trailingContent = if (running) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                            shape = shape,
                        )
                    }
                },
        )
        QuickActionsGroup(
            title = null,
            rows =
                listOf(
                    QuickActionRow("profiles.add") { shape ->
                        FlowNavRow(
                            title = stringResource(R.string.profiles_add),
                            supportingText = stringResource(R.string.profiles_limit, ProfileRegistry.MAX_PROFILES).takeIf { atLimit },
                            onClick = {
                                sheet.close()
                                onAdd()
                            },
                            enabled = !atLimit,
                            showChevron = false,
                            leadingIcon = Icons.Outlined.PersonAdd,
                            shape = shape,
                        )
                    },
                    QuickActionRow("profiles.manage") { shape ->
                        FlowNavRow(
                            title = stringResource(R.string.profiles_manage),
                            onClick = {
                                sheet.close()
                                onManage()
                            },
                            leadingIcon = Icons.Outlined.ManageAccounts,
                            shape = shape,
                        )
                    },
                ),
        )
        Text(
            text = stringResource(R.string.profiles_sheet_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(FooterPadding),
        )
    }
}
