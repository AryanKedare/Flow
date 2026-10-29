package io.github.aedev.flow.sync2.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.identity.DeviceIdentity
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.addScanned
import io.github.aedev.flow.sync2.linking.answerShown
import io.github.aedev.flow.sync2.linking.createGroup
import io.github.aedev.flow.sync2.linking.deviceNames
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.listen
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import io.github.aedev.flow.sync2.linking.removeDevice
import io.github.aedev.flow.sync2.linking.renameDevice
import io.github.aedev.flow.sync2.linking.restoreGroup
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.store.ServerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val NAME_LIMIT = 64
private const val PLATFORM = "android"

/** Where adding or joining a device is: the code to show, the six digits to compare, the outcome. */
sealed interface LinkPhase {
    data object Idle : LinkPhase

    /** [code] is the QR this device shows; null while it answers another device's code. */
    data class Waiting(
        val code: String?,
    ) : LinkPhase

    data class Confirm(
        val sas: String,
    ) : LinkPhase

    data object Linking : LinkPhase

    data object Done : LinkPhase

    data class Failed(
        val error: LinkedError,
    ) : LinkPhase
}

data class LinkedDevice(
    val id: String,
    val name: String,
    val platform: String,
    val thisDevice: Boolean,
)

/** What the Linked devices screen can do. Pairing runs in the application scope, so it survives the screen. */
@Singleton
class LinkedActions
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        private val keys: DeviceKeys,
        private val sync: LinkedSync,
        private val identity: DeviceIdentity,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val phase = MutableStateFlow<LinkPhase>(LinkPhase.Idle)
        private var answer: CompletableDeferred<Boolean>? = null
        private var task: Job? = null

        val group: Flow<GroupRecord?> = db.groups().observeGroup()
        val lastSync: StateFlow<LastSync?> = sync.lastSync
        val link: StateFlow<LinkPhase> = phase.asStateFlow()

        private suspend fun meta() = DeviceMeta(identity.deviceId(), identity.deviceName(), PLATFORM)

        private suspend fun linked(): GroupRecord = db.groups().group() ?: throw LinkedException.NotLinked()

        private suspend fun ensureUnlinked() {
            if (db.groups().group() != null) throw LinkedException.AlreadyLinked()
        }

        private suspend fun session(): Session = sync.open(linked(), keys.load())

        private fun linkedNow() {
            LinkedSyncWorker.schedule(context)
            scope.launch { sync.syncNow() }
        }

        /** This group's devices, this one first. */
        suspend fun devices(): List<LinkedDevice> {
            val session = session()
            val names = deviceNames(session)
            val me = SyncBytes.toHex(session.device.did)
            return session.state.members
                .filterValues { it.role == Role.DEVICE }
                .keys
                .map { id -> LinkedDevice(id, names[id]?.name.orEmpty(), names[id]?.platform.orEmpty(), id == me) }
                .sortedByDescending { it.thisDevice }
        }

        /** Creates a group on a fresh server and returns the recovery words to show once. */
        suspend fun create(
            url: String,
            adminToken: String,
        ): String {
            ensureUnlinked()
            return createGroup(db, url.trim(), adminToken.trim(), keys.load(), meta(), ActiveProfile.get(context).id).also { linkedNow() }
        }

        /** Restores the group with the recovery words and returns the new words to show once. */
        suspend fun restore(
            url: String,
            words: String,
        ): String {
            ensureUnlinked()
            return restoreGroup(db, url.trim(), words, keys.load(), meta()).also { linkedNow() }
        }

        suspend fun confirmRecoverySaved() = db.groups().setRecoverySaved(true)

        suspend fun rename(
            id: String,
            name: String,
        ) {
            val session = session()
            val current = deviceNames(session)[id] ?: DeviceMeta("", "", "")
            renameDevice(session, keys.load(), id, current.copy(name = name.trim().take(NAME_LIMIT)))
        }

        suspend fun remove(id: String) {
            removeDevice(session(), keys.load(), id)
            db.snapshots().snapshotSoon()
        }

        /** Leaves the group on this device only: its state goes, its keys stay. */
        suspend fun forget() {
            LinkedSyncWorker.cancel(context)
            withContext(Dispatchers.IO) { db.clearAllTables() }
        }

        suspend fun syncNow(): LastSync = sync.syncNow()

        /** Shows a member's code, answered through the server's mailbox or over the LAN. */
        fun addDevice() =
            start {
                val record = linked()
                val device = keys.load()
                val offer = newOffer(record.storeUrl, meta().name, listen())
                phase.value = LinkPhase.Waiting(offer.qr)
                addDevice(sync.open(record, device), sync.server(record, device), offer, device, ::confirm)
            }

        /** Answers another device's code: a member's when this device isn't linked, a new device's when it is. */
        fun enterCode(text: String) =
            start {
                val device = keys.load()
                when (val code = readCode(text)) {
                    is Code.Member -> {
                        ensureUnlinked()
                        val store = ServerStore.connect(code.storeUrl, device, null, null)
                        join(db, store, store, code, device, meta(), ::confirm)
                        linkedNow()
                    }

                    is Code.New -> {
                        if (db.groups().group() == null) throw LinkedException.NeedsMember()
                        addScanned(session(), code, device, ::confirm)
                    }
                }
            }

        /** Shows a new device's code, for a member on the same network to scan: how a TV joins. */
        fun showCode() =
            start {
                ensureUnlinked()
                val device = keys.load()
                val offer = newOffer(null, meta().name, listen() ?: throw LinkedException.LanUnreachable())
                phase.value = LinkPhase.Waiting(offer.qr)
                val welcomed = answerShown(offer, device, meta(), ::confirm)
                welcomed.finish(db, ServerStore.connect(welcomed.storeUrl, device, welcomed.instance, welcomed.group))
                linkedNow()
            }

        fun answer(accept: Boolean) {
            answer?.complete(accept)
        }

        fun cancel() {
            task?.cancel()
            answer?.complete(false)
            phase.value = LinkPhase.Idle
        }

        private suspend fun confirm(sas: String): Boolean {
            val decision = CompletableDeferred<Boolean>().also { answer = it }
            phase.value = LinkPhase.Confirm(sas)
            return decision.await().also { if (it) phase.value = LinkPhase.Linking }
        }

        private fun start(block: suspend () -> Unit) {
            if (task?.isActive == true) return
            phase.value = LinkPhase.Waiting(null)
            task =
                scope.launch {
                    phase.value =
                        try {
                            block()
                            LinkPhase.Done
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LinkPhase.Failed(e.linkedError())
                        }
                }
        }
    }
