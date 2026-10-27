package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingAvailability
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

internal object ProfilesIndex {
    val add =
        SettingEntry(
            key = "profiles.add",
            title = R.string.profiles_add,
            summary = R.string.profiles_add_summary,
            keywords = R.string.profiles_keywords,
            destination = SettingsDestination.PROFILES,
            availability = SettingAvailability.Profiles,
        )

    /** Only the list is searchable: the editor needs a profile to open. */
    val all = listOf(add)
}
