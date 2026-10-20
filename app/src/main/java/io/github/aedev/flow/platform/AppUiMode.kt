package io.github.aedev.flow.platform

import io.github.aedev.flow.BuildConfig

/** User-selected interface mode for the shared Flow APK. */
enum class AppUiMode {
    AUTOMATIC,
    MOBILE,
    TV,
    ;

    fun resolve(
        deviceFormFactor: DeviceFormFactor,
        tvEdition: Boolean = BuildConfig.TV_EDITION,
    ): AppUiRoot =
        if (tvEdition) {
            AppUiRoot.TV
        } else {
            when (this) {
                AUTOMATIC -> if (deviceFormFactor == DeviceFormFactor.TV) AppUiRoot.TV else AppUiRoot.MOBILE
                MOBILE -> AppUiRoot.MOBILE
                TV -> AppUiRoot.TV
            }
        }

    companion object {
        fun fromStorage(value: String?): AppUiMode = entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
}

/** The UI root rendered by [io.github.aedev.flow.MainActivity]. */
enum class AppUiRoot {
    MOBILE,
    TV,
}
