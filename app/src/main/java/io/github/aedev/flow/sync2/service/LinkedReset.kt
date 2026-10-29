package io.github.aedev.flow.sync2.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.sync.apply.SyncChangeLedgerStore
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.device.StoreCredentials
import io.github.aedev.flow.sync2.store.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Leaving the linked group on this device: by choice, because its data was replaced, or because it was removed. */
@Singleton
class LinkedReset
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        private val local: LocalStore,
        private val ledger: SyncChangeLedgerStore,
        private val keys: DeviceKeys,
        private val credentials: StoreCredentials,
    ) {
        /** Forgets the group on this device: its state, what it holds and its keys (spec/crypto.md, Device keys) go. */
        suspend fun forget() {
            LinkedSyncWorker.cancel(context)
            withContext(Dispatchers.IO) { db.clearAllTables() }
            local.clear()
            keys.delete()
            credentials.clear()
        }

        /**
         * A verified roster no longer lists this device: it stops syncing and deletes its keys and the
         * group's epoch keys with everything it held. The group stays recorded, so Linked devices can
         * say so until the user forgets it.
         */
        suspend fun removed() {
            LinkedSyncWorker.cancel(context)
            local.clear()
            keys.delete()
        }

        /**
         * Default's data was replaced wholesale (a restore or an import). Diffing it against what was sent
         * would read every missing record as a deletion and send it to every device, so both syncs start
         * over: the ledgers are reset and the linked group is left; linking again brings the data back.
         */
        suspend fun dataReplaced() {
            if (!ActiveProfile.get(context).isDefault) return
            ledger.reset()
            forget()
        }
    }
