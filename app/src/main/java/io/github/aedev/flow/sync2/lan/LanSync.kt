package io.github.aedev.flow.sync2.lan

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.SyncProfile
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync.qr.LocalNetworkAddress
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.WsClient
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.lanTag
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.observeEpoch
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.live.LiveChannel
import io.github.aedev.flow.sync2.live.LiveDevices
import io.github.aedev.flow.sync2.live.LiveTransport
import io.github.aedev.flow.sync2.service.LinkedSync
import io.github.aedev.flow.sync2.service.linkedError
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

const val SESSION_PATH = "/flow-sync/2"

/** A peer that just synced isn't dialed again sooner than this. */
private const val REDIAL_MS = 60_000L

/** A side that has heard nothing for this long takes the other's pull as done. */
private const val QUIET_MS = 3_000L

private const val CONNECT_TIMEOUT_MS = 3_000L
private const val ATTEMPT_MS = 30_000L

private const val HOUR_S = 3_600L
private const val HOUR_MS = HOUR_S * 1_000
private const val FIRST_RETRY_MS = 5_000L
private const val LAST_RETRY_MS = 5 * 60_000L

/**
 * Syncs linked devices on the same network (spec/live.md, LAN transport), for LAN groups and groups
 * on a server alike: it serves sessions, advertises this device, finds the others, and when two
 * members meet each pulls what it lacks; the usual sync then merges it.
 */
@Singleton
class LanSync
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        private val keys: DeviceKeys,
        private val local: LocalStore,
        private val sync: LinkedSync,
        private val live: LiveChannel,
        private val devices: LiveDevices,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        /** The address each member this device dialed serves on, to bring it live messages later. */
        private val reached = ConcurrentHashMap<String, String>()
        private val open = ConcurrentHashMap.newKeySet<String>()
        private val seen = MutableStateFlow<Map<String, Long>>(emptyMap())
        private val dialed = ConcurrentHashMap<String, Long>()

        /** The mDNS names this device advertised, which it never dials. */
        private val names = ConcurrentHashMap.newKeySet<String>()
        private var running: Job? = null
        private var users = 0
        private var group: String? = null

        /** Devices of this group met on this network while this process runs, and when. */
        val nearby: StateFlow<Map<String, Long>> = seen.asStateFlow()

        /**
         * Serves the group this device is in, starting over when it joins another one or the epoch moves
         * on. Runs until every [start] has had its [stop].
         */
        @Synchronized
        fun start() {
            users++
            if (running?.isActive == true || !SyncProfile.syncsHere(context)) return
            Log.i(TAG, "LAN sync started")
            running =
                scope.launch {
                    try {
                        db
                            .groups()
                            .observeGroup()
                            .map { it?.groupId }
                            .distinctUntilChanged()
                            .collectLatest { id ->
                                if (id != group) forgetPeers(id)
                                if (id != null) db.groups().observeEpoch().collectLatest { if (it != null) keepServing() }
                            }
                    } finally {
                        devices.lanPeers { emptySet() }
                    }
                }
        }

        @Synchronized
        fun stop() {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0) return
            running?.cancel()
            running = null
            Log.i(TAG, "LAN sync stopped")
        }

        /** A short look for other members, for a periodic attempt on Wi-Fi. */
        suspend fun attempt() {
            start()
            try {
                delay(ATTEMPT_MS)
            } finally {
                stop()
            }
        }

        private suspend fun session(): Session? {
            db.groups().group() ?: return null
            return open()
        }

        private suspend fun open(): Session = Session.open(db, GroupStore(local, null), keys.load())

        /** Peers met in another group are no longer this device's. */
        private fun forgetPeers(id: String?) {
            group = id
            seen.value = emptyMap()
            reached.clear()
            dialed.clear()
            devices.lanPeers { emptySet() }
        }

        /** Serves afresh each hour, when the tag changes, and again after a failure, until cancelled. */
        private suspend fun keepServing() {
            var retry = FIRST_RETRY_MS
            while (true) {
                try {
                    withTimeoutOrNull(HOUR_MS - System.currentTimeMillis() % HOUR_MS) { serve() }
                    retry = FIRST_RETRY_MS
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "LAN sync stopped: ${e.linkedError()}")
                    delay(retry)
                    retry = (retry * 2).coerceAtMost(LAST_RETRY_MS)
                }
            }
        }

        private suspend fun serve(): Nothing =
            coroutineScope {
                val session = open()
                val lanKey = purposeKey(session.currentKey(), session.group, session.state.epoch, Purpose.LAN)
                val server = WsServer(SESSION_PATH) { connection -> meet(connection, accepting = true) }
                val discovery = LanDiscovery(context)
                val name = "Flow-" + SyncBytes.toHex(SyncCrypto.randomBytes(2))
                names += name
                try {
                    val port = server.start()
                    val now = System.currentTimeMillis() / 1000
                    discovery.advertise(name, port, lanTag(lanKey, now))
                    val tags = listOf(now - HOUR_S, now, now + HOUR_S).map { SyncBytes.toHex(lanTag(lanKey, it)) }.toSet()
                    discovery.discover(names) { host, peerPort, tag ->
                        if (tag in tags && LocalNetworkAddress.isLocal(host)) launch { dial(host, peerPort) }
                    }
                    Log.i(TAG, "LAN sync serving")
                    // A live message for a member reached before, with no session open to it now, opens one.
                    launch {
                        live.envelopes.collect { envelope ->
                            for (address in reached.values) {
                                if (address !in open) launch { dial(address, envelope) }
                            }
                        }
                    }
                    discovery.failed.await()
                    throw LinkedException.LanUnreachable()
                } finally {
                    discovery.stop()
                    server.stop()
                }
            }

        private suspend fun dial(
            host: String,
            port: Int,
        ) {
            val address = "$host:$port"
            val now = System.currentTimeMillis()
            if (dialed[address]?.let { now - it < REDIAL_MS } == true) return
            dialed[address] = now
            dial(address, null)
        }

        /** A session with the peer at [address], which first brings it [envelope] when there is one. */
        private suspend fun dial(
            address: String,
            envelope: ByteArray?,
        ) {
            val host = address.substringBeforeLast(':')
            val url = "ws://${LocalNetworkAddress.urlHost(host)}:${address.substringAfterLast(':')}$SESSION_PATH"
            if (!open.add(address)) return
            try {
                val connection =
                    withTimeoutOrNull(CONNECT_TIMEOUT_MS) { runCatching { WsClient(OkHttpClient()).connect(url) }.getOrNull() }
                val peer = connection?.let { meet(it, accepting = false, envelope) }
                if (peer != null) {
                    reached[peer] = address
                    devices.lanPeers { it + peer }
                } else {
                    val gone = reached.filterValues { it == address }.keys
                    devices.lanPeers { it - gone }
                }
            } finally {
                open.remove(address)
            }
        }

        /**
         * One session with another member: both pull, live messages travel while it lasts, then the
         * usual sync merges what arrived. Returns the member when it got that far.
         */
        private suspend fun meet(
            connection: SyncConnection,
            accepting: Boolean,
            envelope: ByteArray? = null,
        ): String? {
            try {
                val session = session() ?: return null
                val link = if (accepting) acceptLink(connection, session) else connectLink(connection, session)
                coroutineScope {
                    val peer = LanPeer(link, session, local) { live.receive(it) }
                    val reader = peer.start(this)
                    val detach =
                        live.attach(
                            object : LiveTransport {
                                override suspend fun send(envelope: ByteArray) = peer.sendLive(envelope)
                            },
                        )
                    try {
                        envelope?.let { peer.sendLive(it) }
                        peer.pull()
                        peer.awaitQuiet(QUIET_MS)
                    } finally {
                        detach()
                    }
                    link.close()
                    reader.join()
                }
                seen.update { it + (link.peer to System.currentTimeMillis()) }
                Log.i(TAG, if (accepting) "LAN session accepted" else "LAN session dialed")
                sync.syncNow()
                return link.peer
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LAN session failed: ${e.linkedError()}")
                return null
            } finally {
                connection.close()
            }
        }

        private companion object {
            const val TAG = "LanSync"
        }
    }
