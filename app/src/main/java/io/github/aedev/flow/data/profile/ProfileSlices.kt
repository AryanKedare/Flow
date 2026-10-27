package io.github.aedev.flow.data.profile

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.BackupRepository
import io.github.aedev.flow.data.recommendation.music.FavouriteArtistsStore
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.data.stats.RecapBackup
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A profile in the single-profile master backup format: app data, the video and music engines, recap and favourite
 * artists. The master backup and its restore, sync rollback and a new profile's seed all write and read it here.
 */
@Singleton
class ProfileSlices
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: BackupRepository,
        private val musicBrain: MusicBrainEngine,
        private val videoStats: VideoStatsRecorder,
        private val favouriteArtists: FavouriteArtistsStore,
    ) {
        /** The whole running profile. */
        suspend fun export(uri: Uri): Result<Unit> =
            measured("export") { repository.exportMasterBackup(uri, musicBrainBytes(), recapBytes(), favouriteArtists.export()) }

        suspend fun export(file: File): Result<Unit> = export(Uri.fromFile(file)).also { logSize(file) }

        suspend fun exportToFolder(folder: Uri): Result<Unit> =
            measured("folder export") {
                repository.exportMasterToFolder(folder, musicBrainBytes(), recapBytes(), favouriteArtists.export())
            }

        /** Writes [parts] of the running profile as [profileId]'s seed, applied when that profile first opens. */
        suspend fun writeSeed(
            profileId: String,
            parts: Set<SlicePart>,
        ): Result<Unit> {
            val file = PendingSlice.file(ProfileFiles.noBackupRoot(context.noBackupFilesDir, RunningProfile(profileId, isDefault = false)))
            file.parentFile?.mkdirs()
            val engine = SlicePart.RECOMMENDATIONS in parts
            return measured("seed") {
                repository.exportMasterBackup(
                    Uri.fromFile(file),
                    musicBrain = if (engine) musicBrainBytes() else null,
                    favouriteArtists = if (engine) favouriteArtists.export() else null,
                    seed = parts,
                )
            }.onSuccess { logSize(file) }.onFailure { file.delete() }
        }

        suspend fun import(uri: Uri): Result<Unit> =
            repository.importMasterBackup(
                uri,
                onMusicBrain = { bytes -> musicBrain.importBrainFromStream(bytes.inputStream()) },
                onRecap = ::restoreRecap,
                onFavouriteArtists = favouriteArtists::restore,
            )

        /** A seed or restore that waited for the running profile; see [PendingSlice]. */
        suspend fun applyPending(file: File) {
            measured("pending import") { import(Uri.fromFile(file)) }
                .onFailure { Log.e(TAG, "Could not apply the pending slice", it) }
        }

        private suspend fun musicBrainBytes(): ByteArray? =
            runCatching { ByteArrayOutputStream().also { musicBrain.exportBrainToStream(it) }.toByteArray() }.getOrNull()

        private suspend fun recapBytes(): ByteArray? =
            runCatching { RecapBackup(video = videoStats.snapshot(), music = musicBrain.listeningStats()).encode() }.getOrNull()

        private suspend fun restoreRecap(bytes: ByteArray) {
            val backup = RecapBackup.decode(bytes) ?: return
            backup.video?.let { videoStats.restore(it) }
            backup.music?.let { musicBrain.restoreListeningStats(it) }
        }

        // History can be large; these lines are how a slice's cost is measured on a real data set.
        private inline fun <T> measured(
            what: String,
            block: () -> Result<T>,
        ): Result<T> {
            val start = SystemClock.elapsedRealtime()
            return block().also { Log.i(TAG, "Slice $what took ${SystemClock.elapsedRealtime() - start} ms, ok=${it.isSuccess}") }
        }

        private fun logSize(file: File) {
            if (file.exists()) Log.i(TAG, "Slice ${file.name} is ${file.length()} bytes")
        }

        private companion object {
            const val TAG = "ProfileSlices"
        }
    }
