package io.github.aedev.flow.sync2.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.profile.ProfileWork
import io.github.aedev.flow.data.profile.SyncProfile
import io.github.aedev.flow.sync2.data.LinkedDatabase
import java.util.concurrent.TimeUnit

/** Opening the app syncs at most this often; the periodic run doesn't wait for it. */
private const val OPEN_INTERVAL_MS = 5 * 60 * 1000L

/** Syncs linked devices on app open and every hour, only with a network, and keeps itself scheduled while linked. */
class LinkedSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun linkedSync(): LinkedSync

        fun linkedDatabase(): LinkedDatabase
    }

    override suspend fun doWork(): Result {
        if (!ProfileWork.belongsHere(this)) return Result.success()
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        if (dependencies.linkedDatabase().groups().group() == null) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(PERIODIC_NAME)
            return Result.success()
        }
        schedule(applicationContext)
        val sync = dependencies.linkedSync()
        if (tags.contains(OPEN_TAG) && sync.lastSync.value.let { it != null && System.currentTimeMillis() - it.atMs < OPEN_INTERVAL_MS }) {
            return Result.success()
        }
        return if (sync.syncNow().error?.retryable == true) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "linked_sync"
        private const val OPEN_NAME = "linked_sync_open"
        private const val OPEN_TAG = "linked_sync_open"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val work =
                PeriodicWorkRequestBuilder<LinkedSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInputData(ProfileWork.data(context))
                    .addTag(ProfileWork.tag(context))
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }

        /** On app open, in the profile that syncs; the worker does nothing when this device isn't linked. */
        fun syncOnOpen(context: Context) {
            if (!SyncProfile.syncsHere(context)) return
            val work =
                OneTimeWorkRequestBuilder<LinkedSyncWorker>()
                    .setConstraints(constraints)
                    .setInputData(ProfileWork.data(context))
                    .addTag(ProfileWork.tag(context))
                    .addTag(OPEN_TAG)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(OPEN_NAME, ExistingWorkPolicy.KEEP, work)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).run {
                cancelUniqueWork(PERIODIC_NAME)
                cancelUniqueWork(OPEN_NAME)
            }
        }
    }
}
