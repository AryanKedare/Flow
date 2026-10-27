package io.github.aedev.flow.data.profile

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.video.downloader.collection.DownloadedCollections
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
        private val slices: ProfileSlices,
        private val collections: dagger.Lazy<DownloadedCollections>,
    ) {
        private val registry: ProfileRegistry get() = ActiveProfile.registry(context)

        val state: Flow<ProfileRegistryState> get() = registry.state

        /**
         * Registers a profile and, when [copy] names parts, writes them from the running profile as its seed. A seed
         * that can't be written takes the new entry with it, so a half-copied profile never appears.
         */
        suspend fun create(
            name: String,
            colorKey: String = "",
            avatar: ProfileAvatar = ProfileAvatar(),
            copy: Set<SlicePart> = emptySet(),
        ): CreateResult {
            require(name.isNotBlank()) { "A new profile needs a name" }
            val profile = registry.add(name.trim(), colorKey, avatar) ?: return CreateResult.LimitReached
            if (copy.isEmpty()) return CreateResult.Created(profile)
            return slices.writeSeed(profile.id, copy).fold(
                onSuccess = { CreateResult.Created(profile) },
                onFailure = { cause ->
                    withContext(Dispatchers.IO) {
                        registry.remove(profile.id)
                        ProfileFolders.of(context).delete(profile.id)
                    }
                    CreateResult.CopyFailed(cause)
                },
            )
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
                collections.get().forgetProfile(id)
                true
            }
    }

sealed interface CreateResult {
    data class Created(
        val profile: Profile,
    ) : CreateResult

    /** There are already [ProfileRegistry.MAX_PROFILES]. */
    data object LimitReached : CreateResult

    data class CopyFailed(
        val cause: Throwable,
    ) : CreateResult
}
