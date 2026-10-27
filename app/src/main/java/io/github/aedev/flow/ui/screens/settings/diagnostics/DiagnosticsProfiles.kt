package io.github.aedev.flow.ui.screens.settings.diagnostics

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.profile.Profile
import io.github.aedev.flow.data.profile.ProfileRegistry
import io.github.aedev.flow.data.profile.ProfileRegistryState
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.shared.FlowNavRow

/** Debug builds only, until the Profiles screens arrive: add, switch to and delete profiles. Kept out of settings search. */
internal fun SettingsListScope.debugProfiles(
    state: ProfileRegistryState?,
    runningId: String,
    onAdd: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (Profile) -> Unit,
) {
    if (state == null) return
    group(key = "diagnostics.profiles", header = R.string.diagnostics_profiles_section, footer = R.string.diagnostics_profiles_footer) {
        state.profiles.forEach { profile ->
            val running = profile.id == runningId
            row("diagnostics.profiles.${profile.id}") { shape ->
                FlowNavRow(
                    title = profile.name.ifBlank { stringResource(R.string.profile_default_name) },
                    supportingText =
                        stringResource(
                            if (running) R.string.diagnostics_profile_running else R.string.diagnostics_profile_switch,
                        ),
                    onClick = { onSwitch(profile.id) },
                    enabled = !running,
                    showChevron = false,
                    shape = shape,
                    trailingContent =
                        if (running || profile.id == state.defaultId) {
                            null
                        } else {
                            {
                                IconButton(onClick = { onDelete(profile) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.diagnostics_profile_delete))
                                }
                            }
                        },
                )
            }
        }
        if (state.profiles.size < ProfileRegistry.MAX_PROFILES) {
            row("diagnostics.profiles.add") { shape ->
                FlowNavRow(
                    title = stringResource(R.string.diagnostics_profile_add),
                    onClick = onAdd,
                    leadingIcon = Icons.Outlined.PersonAdd,
                    showChevron = false,
                    shape = shape,
                )
            }
        }
    }
}
