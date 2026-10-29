package io.github.aedev.flow.sync2.engine

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.changes.IncomingBatch
import io.github.aedev.flow.sync2.changes.markSent
import io.github.aedev.flow.sync2.changes.outgoingBatches
import io.github.aedev.flow.sync2.changes.parseBatch
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.checkEpochKey
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.openChain
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.crypto.unwrapEpochKey
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.StreamRow
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.parseEntry
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.store.Header
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.Store
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.StreamPosition
import io.github.aedev.flow.sync2.store.nextInStream
import io.github.aedev.flow.sync2.store.parseObject
import io.github.aedev.flow.sync2.store.sealObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.TreeMap

private class ReadObject(
    val position: StreamPosition,
    val type: ObjectType,
    val batch: IncomingBatch?,
)

/** Batches read from other devices, not applied yet, and how far reading got. */
class Pulled internal constructor(
    val batches: Map<String, List<IncomingBatch>>,
    internal val positions: Map<String, StreamPosition>,
    internal val cursor: Long,
    /** Batches and snapshot manifests read; acks alone don't call for an ack. */
    internal val consumed: Int,
)

/**
 * One sync with a store: verify the roster, push this device's changes, pull everyone else's. It
 * holds no secrets beyond the session: epoch keys are unwrapped from the store each time.
 */
class Session private constructor(
    private val db: LinkedDatabase,
    internal val store: Store,
    private val device: MemberSecrets,
    record: GroupRecord,
    internal val group: ByteArray,
    val state: RosterState,
    entries: List<Entry>,
) {
    private val me = SyncBytes.toHex(device.did)
    private val signingKeys = HashMap<String, ByteArray>()
    private val commitments = HashMap<Int, ByteArray>()
    private val keys = TreeMap<Int, ByteArray>()

    var record = record
        private set

    internal fun currentKey(): ByteArray = keys.getValue(state.epoch)

    init {
        for (entry in entries) {
            entry.add?.forEach { signingKeys[it.did] = SyncBytes.b64urlDecode(it.sig) }
            entry.epoch?.let { commitments[it.n] = SyncBytes.b64urlDecode(it.kc) }
        }
    }

    companion object {
        /** Brings the roster up to date and unwraps the current epoch key. */
        suspend fun open(
            db: LinkedDatabase,
            store: Store,
            device: MemberSecrets,
        ): Session {
            val groups = db.groups()
            val record = groups.group() ?: throw LinkedException.NotLinked()
            val entries = groups.roster().map { parseEntry(Json.parseToJsonElement(it.entry)) }.toMutableList()
            var state = replayRoster(entries)
            val fresh = store.rosterAfter(state.seq)
            for (entry in fresh) state = applyEntry(state, entry)
            groups.addEntries(fresh)
            entries += fresh
            if (SyncBytes.toHex(device.did) !in state.members) throw LinkedException.Removed()
            return Session(db, store, device, record, SyncBytes.fromHex(record.groupId), state, entries).apply { unwrapCurrent() }
        }
    }

    private suspend fun unwrapCurrent() {
        val epoch = state.epoch
        val wrap = store.key(epoch, me) ?: reject(ProtocolError.KEY_COMMITMENT)
        val key = unwrapEpochKey(device.kemSecret, group, epoch, device.did, wrap)
        checkEpochKey(key, group, epoch, commitments[epoch] ?: reject(ProtocolError.KEY_COMMITMENT))
        keys[epoch] = key
    }

    /** The key of [epoch], walking the history chain back from the oldest key this device holds. */
    private suspend fun epochKey(epoch: Int): ByteArray {
        while (epoch !in keys) {
            val (known, key) = keys.firstEntry() ?: reject(ProtocolError.KEY_COMMITMENT)
            if (known <= epoch) reject(ProtocolError.KEY_COMMITMENT)
            val chain = store.key(known, "chain") ?: reject(ProtocolError.KEY_COMMITMENT)
            val previous = openChain(key, group, known, chain)
            checkEpochKey(previous, group, known - 1, commitments[known - 1] ?: reject(ProtocolError.KEY_COMMITMENT))
            keys[known - 1] = previous
        }
        return keys.getValue(epoch)
    }

    private suspend fun write(
        type: ObjectType,
        content: ByteArray,
    ) {
        val seq = record.ownSeq + 1
        val epoch = state.epoch
        val header = Header(type, group, epoch, device.did, seq, SyncBytes.fromHex(record.ownPrev))
        val key = purposeKey(keys.getValue(epoch), group, epoch, type.purpose)
        val sealed = sealObject(header, key, encodePayload(content), device.sigSeed)
        store.putObject(StorePaths.log(me, seq), sealed)
        record = record.copy(ownSeq = seq, ownPrev = SyncBytes.toHex(sha256(sealed)))
        db.groups().saveGroup(record)
    }

    /** Uploads what changed locally since the last upload; returns how many batches went out. */
    suspend fun push(exported: Map<String, CollectionWire>): Int {
        val outgoing = outgoingBatches(db.sent(), exported, state.head, record.link, System.currentTimeMillis())
        for (batch in outgoing.batches) write(ObjectType.CHANGES, batch.content)
        markSent(db.sent(), outgoing)
        return outgoing.batches.size
    }

    /**
     * Reads other devices' new objects in stream order, checking each one. Nothing is applied: the
     * caller applies [Pulled.batches] and then calls [finish].
     */
    suspend fun pull(): Pulled {
        val positions = db.groups().streams().associateTo(HashMap()) { it.did to StreamPosition(it.seq, SyncBytes.fromHex(it.hash)) }
        val batches = LinkedHashMap<String, MutableList<IncomingBatch>>()
        var cursor = record.cursor
        var consumed = 0
        while (true) {
            val (changes, next) = store.changes(cursor)
            if (changes.isEmpty()) break
            // Objects of one sender may be listed out of order: retry while a pass makes progress.
            var waiting =
                changes
                    .mapNotNull { change ->
                        StorePaths.parseLog(change.path)?.let { (did, seq) -> Triple(did, seq, change.path) }
                    }.filter { (did, seq) ->
                        did != me && (positions[did]?.seq ?: 0) < seq
                    }
            while (waiting.isNotEmpty()) {
                val still =
                    waiting.filter { (did, seq, path) ->
                        val read = readObject(path, did, seq, positions[did]) ?: return@filter true
                        positions[did] = read.position
                        if (read.type != ObjectType.ACK) consumed++
                        read.batch?.takeIf { it.link == record.link }?.let { batches.getOrPut(did) { ArrayList() } += it }
                        false
                    }
                if (still.size == waiting.size) break
                waiting = still
            }
            // A missing object means the store hasn't caught up: read this page again next time.
            if (waiting.isNotEmpty()) break
            cursor = next
        }
        return Pulled(batches, positions, cursor, consumed)
    }

    /** One log object, checked in the order of spec/store.md; `null` while an earlier one of this sender is missing. */
    private suspend fun readObject(
        path: String,
        did: String,
        seq: Long,
        last: StreamPosition?,
    ): ReadObject? {
        val bytes = store.getObject(path) ?: return null
        val parsed = parseObject(bytes, group)
        parsed.verify(signingKeys[did] ?: reject(ProtocolError.SENDER))
        if (SyncBytes.toHex(parsed.header.sender) != did || parsed.header.seq != seq) reject(ProtocolError.REPLAY)
        val position =
            try {
                nextInStream(last, parsed)
            } catch (e: ProtocolException) {
                if (e.error == ProtocolError.GAP) return null
                throw e
            }
        val epoch = parsed.header.epoch
        val content = decodePayload(parsed.open(purposeKey(epochKey(epoch), group, epoch, parsed.header.type.purpose)))
        val batch =
            when (parsed.header.type) {
                ObjectType.CHANGES -> parseBatch(content)
                ObjectType.ACK, ObjectType.SNAPSHOT -> null
                ObjectType.LIVE -> reject(ProtocolError.SUITE)
            }
        return ReadObject(position, parsed.header.type, batch)
    }

    /** Saves how far reading got, after the caller applied the batches, and acknowledges it. */
    suspend fun finish(pulled: Pulled) {
        db.groups().saveStreams(pulled.positions.map { (did, p) -> StreamRow(did, p.seq, SyncBytes.toHex(p.hash)) }, pulled.cursor)
        record = record.copy(cursor = pulled.cursor)
        if (pulled.consumed > 0) {
            val vv = JsonObject(pulled.positions.toSortedMap().mapValues { (_, p) -> JsonPrimitive(p.seq) })
            write(ObjectType.ACK, SyncSerialization.canonicalJson(JsonObject(mapOf("vv" to vv))).toByteArray())
        }
    }
}
