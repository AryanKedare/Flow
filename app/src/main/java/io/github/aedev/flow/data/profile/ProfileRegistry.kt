package io.github.aedev.flow.data.profile

import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** One profile as the registry keeps it. A blank [name] is shown as the localized default name. */
@Serializable
data class Profile(
    val id: String,
    val name: String = "",
    val colorKey: String = "",
    val avatar: ProfileAvatar = ProfileAvatar(),
    val createdAt: Long = 0L,
)

/**
 * Flat rather than sealed, so an older build that meets an avatar kind it doesn't know shows the initial instead
 * of treating the whole registry as corrupt.
 */
@Serializable
data class ProfileAvatar(
    val kind: String = INITIAL,
    val emoji: String = "",
    val photoVersion: Long = 0L,
) {
    companion object {
        const val INITIAL = "initial"
        const val EMOJI = "emoji"
        const val PHOTO = "photo"
    }
}

@Serializable
data class ProfileRegistryState(
    val profiles: List<Profile> = emptyList(),
    val defaultId: String? = null,
    val activeId: String? = null,
)

/** The profile a process runs as. [id] is blank only when the registry could not be read; Default's paths never use it. */
data class RunningProfile(
    val id: String,
    val isDefault: Boolean,
)

/**
 * The profiles on this install and which one is active, in `datastore/profiles_registry.json`. It is the one store
 * every profile shares. DataStore allows one instance per file, so [ActiveProfile] owns the only one.
 */
class ProfileRegistry internal constructor(
    file: File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val store: DataStore<ProfileRegistryState> =
        DataStoreFactory.create(
            serializer = RegistrySerializer,
            corruptionHandler =
                ReplaceFileCorruptionHandler { error ->
                    Log.e(TAG, "Profile registry corrupted, starting over with Default", error)
                    ProfileRegistryState()
                },
            scope = scope,
            produceFile = { file },
        )

    val state: Flow<ProfileRegistryState> = store.data

    /** The active profile. Creates Default on first run, and falls back to it when the active profile is gone. */
    suspend fun running(): RunningProfile {
        val stored = store.data.first()
        val complete = if (stored.isComplete()) stored else store.updateData { it.completed(newId, now) }
        return complete.running()
    }

    companion object {
        const val FILE_NAME = "profiles_registry.json"
        private const val TAG = "ProfileRegistry"

        /**
         * The active profile read straight from [file] with the store's own format, or null when the file is
         * missing, unreadable or incomplete and [running] has to repair it. Process start uses this: opening the
         * DataStore there cost about 30 ms more on the main thread, and nothing needs writing on a normal start.
         * DataStore replaces the file by rename, so a reader never sees a partial write.
         */
        fun peek(file: File): RunningProfile? =
            try {
                file
                    .takeIf(File::isFile)
                    ?.readText()
                    ?.let { RegistryJson.decodeFromString(ProfileRegistryState.serializer(), it) }
                    ?.takeIf(ProfileRegistryState::isComplete)
                    ?.running()
            } catch (e: IOException) {
                null
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
    }
}

private fun ProfileRegistryState.running() = RunningProfile(id = checkNotNull(activeId), isDefault = activeId == defaultId)

internal fun ProfileRegistryState.isComplete(): Boolean =
    isSafeProfileId(defaultId) &&
        isSafeProfileId(activeId) &&
        profiles.any { it.id == defaultId } &&
        profiles.any { it.id == activeId }

internal fun ProfileRegistryState.completed(
    newId: () -> String,
    now: () -> Long,
): ProfileRegistryState {
    val withDefault =
        if (isSafeProfileId(defaultId) && profiles.any { it.id == defaultId }) {
            this
        } else {
            val id = newId()
            copy(profiles = listOf(Profile(id = id, createdAt = now())) + profiles, defaultId = id)
        }
    return if (isSafeProfileId(withDefault.activeId) && withDefault.profiles.any { it.id == withDefault.activeId }) {
        withDefault
    } else {
        withDefault.copy(
            activeId = withDefault.defaultId,
        )
    }
}

private val RegistryJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

private object RegistrySerializer : Serializer<ProfileRegistryState> {
    override val defaultValue = ProfileRegistryState()

    override suspend fun readFrom(input: InputStream): ProfileRegistryState {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            RegistryJson.decodeFromString(ProfileRegistryState.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable profile registry", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Unreadable profile registry", e)
        }
    }

    override suspend fun writeTo(
        t: ProfileRegistryState,
        output: OutputStream,
    ) {
        output.write(RegistryJson.encodeToString(ProfileRegistryState.serializer(), t).encodeToByteArray())
    }
}
