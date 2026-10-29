package io.github.aedev.flow.sync2.service

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.sync2.data.LinkedDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Ties linked sync to the app being in use: what changed here leaves as soon as the app goes to the background. */
@Singleton
class LinkedForeground
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        fun install() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        override fun onStop(owner: LifecycleOwner) {
            scope.launch { if (db.groups().group() != null) LinkedSyncWorker.syncSoon(context) }
        }
    }
