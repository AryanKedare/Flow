package io.github.aedev.flow.data.profile

import android.content.Context
import java.io.File

/**
 * Where a store keeps its file for the profile this process runs as. Default and [StoreScope.DEVICE] stores keep
 * the exact paths they had before profiles existed, so upgrading moves nothing and an older build still finds them.
 */
object ProfileFiles {
    fun preferences(
        context: Context,
        scope: StoreScope,
        name: String,
    ): File {
        val app = context.applicationContext
        return preferences(app.filesDir, app.noBackupFilesDir, ActiveProfile.get(app), scope, name)
    }

    internal fun preferences(
        filesDir: File,
        noBackupFilesDir: File,
        profile: RunningProfile,
        scope: StoreScope,
        name: String,
    ): File = File(root(filesDir, noBackupFilesDir, profile, scope), "datastore/$name.preferences_pb")

    // DEFAULT_ONLY resolves to the private folder rather than Default's file, so a sync path that runs in another
    // profile by mistake writes to a file nobody reads instead of into Default's sync state.
    internal fun root(
        filesDir: File,
        noBackupFilesDir: File,
        profile: RunningProfile,
        scope: StoreScope,
    ): File {
        if (profile.isDefault || scope == StoreScope.DEVICE) return filesDir
        require(isSafeProfileId(profile.id)) { "Unsafe profile id" }
        return when (scope) {
            StoreScope.PROFILE_SETTINGS -> File(filesDir, "profile_settings/${profile.id}")
            StoreScope.PROFILE_LIBRARY -> File(filesDir, "profile_library/${profile.id}")
            StoreScope.PROFILE_PRIVATE, StoreScope.DEFAULT_ONLY -> File(noBackupFilesDir, "profiles/${profile.id}")
            StoreScope.DEVICE -> filesDir
        }
    }
}

private val SafeProfileId = Regex("[A-Za-z0-9-]{1,64}")

/** Profile ids become folder names, so anything that could step outside its folder is refused. */
internal fun isSafeProfileId(id: String?): Boolean = id != null && SafeProfileId.matches(id)
