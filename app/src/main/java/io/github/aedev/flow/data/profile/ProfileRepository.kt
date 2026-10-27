package io.github.aedev.flow.data.profile

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Creates, renames and deletes profiles. Switching to one restarts the process (see ProfileSwitcher). */
@Singleton
class ProfileRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val registry: ProfileRegistry get() = ActiveProfile.registry(context)

        val state: Flow<ProfileRegistryState> get() = registry.state

        /** The new profile, or null when there are already [ProfileRegistry.MAX_PROFILES]. */
        suspend fun create(
            name: String,
            colorKey: String = "",
            avatar: ProfileAvatar = ProfileAvatar(),
        ): Profile? {
            require(name.isNotBlank()) { "A new profile needs a name" }
            return registry.add(name.trim(), colorKey, avatar)
        }

        suspend fun rename(
            id: String,
            name: String,
        ): Boolean = registry.rename(id, name.trim())

        /**
         * Removes an inactive profile and everything it kept on disk. Its files are closed: only the running
         * profile's are open in this process.
         */
        suspend fun delete(id: String): Boolean =
            withContext(Dispatchers.IO) {
                if (!isSafeProfileId(id) || id == ActiveProfile.get(context).id) return@withContext false
                if (!registry.remove(id)) return@withContext false
                ProfileFolders.of(context).delete(id)
                true
            }
    }
