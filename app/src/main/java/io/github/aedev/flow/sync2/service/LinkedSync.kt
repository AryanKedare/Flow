package io.github.aedev.flow.sync2.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.data.profile.SyncProfile
import io.github.aedev.flow.sync.apply.PeerInfo
import io.github.aedev.flow.sync.apply.SyncApplier
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.identity.DeviceIdentity
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.applyBatches
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LAN_STORE
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.engine.collectGarbage
import io.github.aedev.flow.sync2.engine.isDue
import io.github.aedev.flow.sync2.engine.writeSnapshot
import io.github.aedev.flow.sync2.linking.deviceNames
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.LocalStore
import io.github.aedev.flow.sync2.store.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** When the last sync ran, and why it failed if it did. */
data class LastSync(
    val atMs: Long,
    val error: LinkedError?,
)

/**
 * One sync of this device's group, for the worker and for Sync now. Like FLOW-SYNC/1 it runs only
 * in the Default profile, and one at a time.
 */
@Singleton
class LinkedSync
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        private val keys: DeviceKeys,
        private val applier: SyncApplier,
        private val identity: DeviceIdentity,
        private val local: LocalStore,
    ) {
        private val running = Mutex()
        private val last = MutableStateFlow<LastSync?>(null)

        val lastSync: StateFlow<LastSync?> = last.asStateFlow()

        /** The group's Flow server, or null for a group that lives on its devices alone. */
        fun server(
            record: GroupRecord,
            device: MemberSecrets,
        ): ServerStore? =
            if (record.storeUrl ==
                LAN_STORE
            ) {
                null
            } else {
                ServerStore.connect(record.storeUrl, device, record.instance, record.groupId)
            }

        fun groupStore(
            record: GroupRecord,
            device: MemberSecrets,
        ): GroupStore = GroupStore(local, server(record, device))

        /** A session on what this device holds, after copying the server's roster and keys. */
        suspend fun open(
            record: GroupRecord,
            device: MemberSecrets,
        ): Session {
            val store = groupStore(record, device)
            store.download(SyncBytes.toHex(device.did), record.serverCursor, objects = false)
            return Session.open(db, store, device)
        }

        suspend fun syncNow(): LastSync =
            running.withLock {
                val error = runCatching { run() }.exceptionOrNull()?.linkedError()
                error?.let { Log.w(TAG, "Linked-devices sync failed: $it") }
                LastSync(System.currentTimeMillis(), error).also { last.value = it }
            }

        private suspend fun export(): Map<String, CollectionWire> =
            applier.exportPayload(SyncCollection.ANDROID_SYNCABLE, identity.hlcNode(), identity.newClock().now().encode())

        private suspend fun run() {
            if (!SyncProfile.syncsHere(context)) return
            val record = db.groups().group() ?: return
            val device = keys.load()
            val me = SyncBytes.toHex(device.did)
            val store = groupStore(record, device)
            db.groups().setServerCursor(store.download(me, record.serverCursor, objects = true))
            val session = Session.open(db, store, device)
            if (!session.adoptLink(ActiveProfile.get(context).id)) return
            val link = session.record.link
            session.push(export())

            val pulled = session.pull()
            val names = if (pulled.batches.isEmpty()) emptyMap() else deviceNames(session)
            val hlc = identity.newClock().now().encode()
            for ((sender, batches) in pulled.batches) {
                val meta = names[sender]
                applyBatches(db.sent(), batches, System.currentTimeMillis()) { received ->
                    applier.applyPayload(PeerInfo(sender, meta?.name.orEmpty(), meta?.platform.orEmpty()), received, hlc)
                }
            }
            session.finish(pulled)

            val now = System.currentTimeMillis()
            db.snapshots().noteMerged(link, pulled.batches.values.sumOf { batches -> batches.sumOf { it.size.toLong() } }, now)
            if (db.snapshots().isDue(link, now)) session.writeSnapshot(export())
            session.collectGarbage()
            db.groups().setUploaded(store.upload(me, db.groups().group()?.uploaded ?: 0))
        }

        private companion object {
            const val TAG = "LinkedSync"
        }
    }
