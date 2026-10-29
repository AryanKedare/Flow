package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.agreementPublicKey
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.memberId
import io.github.aedev.flow.sync2.crypto.open
import io.github.aedev.flow.sync2.crypto.pairingAad
import io.github.aedev.flow.sync2.crypto.pairingKeys
import io.github.aedev.flow.sync2.crypto.pairingSlot
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.crypto.sign
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.Draft
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.roster.sealDeviceMeta
import io.github.aedev.flow.sync2.roster.signingBytes
import io.github.aedev.flow.sync2.store.Appended
import io.github.aedev.flow.sync2.store.Mailbox
import io.github.aedev.flow.sync2.store.Store
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import java.util.Locale

private const val POLL_INTERVAL_MS = 2_000L
private const val SLOT_LIFETIME_MS = 180_000L
private const val KEY_SIZE = 32

/** A code shown by a member, valid until its slot closes. */
class Offer internal constructor(
    val qr: String,
    internal val token: ByteArray,
    internal val secret: ByteArray,
    internal val public: ByteArray,
)

fun newOffer(
    storeUrl: String,
    deviceName: String,
): Offer {
    val token = SyncCrypto.randomBytes(16)
    val secret = SyncCrypto.randomBytes(KEY_SIZE)
    val public = agreementPublicKey(secret)
    val code =
        QrCode(
            2,
            SyncBytes.b64urlEncode(token),
            SyncBytes.b64urlEncode(public),
            "m",
            deviceName.take(64),
            StoreLocation("server", storeUrl),
        )
    return Offer(canonical(QrCode.serializer(), code), token, secret, public)
}

/** A member's code, checked before anything is sent: version 2, shown by a member, on a Flow server. */
fun readOffer(qr: String): QrCode =
    try {
        pairingJson.decodeFromString(QrCode.serializer(), qr).takeIf { it.v == 2 && it.r == "m" && it.s.k == "server" }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw LinkedException.Unexpected()

/** One side of the mailbox: seals what it sends and opens what the other side sent, in order. */
private class Channel(
    private val mailbox: Mailbox,
    private val slot: String,
    private val token: ByteArray,
    private val sendDirection: Char,
    private val receiveDirection: Char,
) {
    lateinit var sealKey: ByteArray
    lateinit var openKey: ByteArray
    private var sent = 0L
    private var received = 0L

    private fun name(
        direction: Char,
        seq: Long,
    ) = "%c-%016x.p".format(Locale.ROOT, direction, seq)

    suspend fun send(
        message: Message,
        prefix: ByteArray = ByteArray(0),
    ) {
        val content = canonical(Message.serializer(), message)
        val sealed = seal(sealKey, pairingAad(token, sent), encodePayload(content.toByteArray()))
        mailbox.putMessage(slot, name(sendDirection, sent), prefix + sealed)
        sent++
    }

    suspend fun nextBytes(): ByteArray {
        val name = name(receiveDirection, received)
        var waited = 0L
        while (true) {
            mailbox.getMessage(slot, name)?.let { return it }
            if (waited >= SLOT_LIFETIME_MS) throw LinkedException.TimedOut()
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
    }

    fun open(sealed: ByteArray): Message {
        val content = decodePayload(open(openKey, pairingAad(token, received), sealed))
        received++
        return try {
            pairingJson.decodeFromString(Message.serializer(), content.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw LinkedException.Unexpected()
        }
    }

    suspend fun receive(): Message = open(nextBytes())
}

private fun key(value: String): ByteArray =
    runCatching { SyncBytes.b64urlDecode(value) }.getOrNull()?.takeIf { it.size == KEY_SIZE } ?: throw LinkedException.Unexpected()

internal fun metaJson(meta: DeviceMeta): ByteArray = canonical(DeviceMeta.serializer(), meta).toByteArray()

/**
 * The member's side: waits for the scanner, shows the code through [confirm], and adds the new
 * device once the user confirms. Returns the new device's id.
 */
suspend fun addDevice(
    session: Session,
    mailbox: Mailbox,
    offer: Offer,
    device: MemberSecrets,
    confirm: suspend (String) -> Boolean,
): String {
    val slot = SyncBytes.toHex(pairingSlot(offer.token))
    mailbox.openSlot(slot)
    try {
        return runMember(session, mailbox, offer, slot, device, confirm)
    } finally {
        // The slot expires on its own if this fails.
        runCatching { mailbox.closeSlot(slot) }
    }
}

private suspend fun runMember(
    session: Session,
    mailbox: Mailbox,
    offer: Offer,
    slot: String,
    device: MemberSecrets,
    confirm: suspend (String) -> Boolean,
): String {
    val channel = Channel(mailbox, slot, offer.token, 'a', 'b')
    val first = channel.nextBytes()
    if (first.size <= KEY_SIZE) throw LinkedException.Unexpected()
    val scannerPublic = first.copyOfRange(0, KEY_SIZE)
    val keys = pairingKeys(offer.secret, scannerPublic, offer.token, offer.public, scannerPublic)
    channel.sealKey = keys.showerKey
    channel.openKey = keys.scannerKey
    val join = channel.open(first.copyOfRange(KEY_SIZE, first.size)) as? Message.Join ?: throw LinkedException.Unexpected()
    if (!confirm(keys.sas)) throw LinkedException.Declined()

    val sigPublic = key(join.sig)
    val kemPublic = key(join.kem)
    val did = memberId(sigPublic, kemPublic)
    val didHex = SyncBytes.toHex(did)
    val state = session.state
    val sealedMeta = sealDeviceMeta(session.currentKey(), session.group, state.epoch, state.seq + 1, did, metaJson(join.meta))
    val subject = memberSubject(sigPublic, kemPublic, Role.DEVICE)
    val draft =
        Draft(Op.ADD, SyncBytes.toHex(device.did), add = listOf(subject), meta = mapOf(didHex to SyncBytes.b64urlEncode(sealedMeta)))
    val unsigned = buildEntry(session.group, state, draft, listOf(device))
    channel.send(Message.EntryMessage(unsigned))

    val accept = channel.receive() as? Message.Accept ?: throw LinkedException.Unexpected()
    val entry = unsigned.copy(sigs = unsigned.sigs + (didHex to accept.sig))
    val after = applyEntry(state, entry)
    val wrap = wrapEpochKey(kemPublic, session.group, state.epoch, did, session.currentKey())
    if (session.store.appendRoster(state.head, entry, mapOf(didHex to SyncBytes.b64urlEncode(wrap)), null) != Appended.Done) {
        reject(ProtocolError.ROSTER_CHAIN)
    }

    val record = session.record
    channel.send(
        Message.Welcome(
            record.groupId,
            after.head,
            after.seq,
            record.link,
            WelcomeStore("server", record.storeUrl, record.instance.orEmpty()),
        ),
    )
    if (channel.receive() != Message.Done) throw LinkedException.Unexpected()
    return didHex
}

/**
 * The new device's side: answers a member's code, shows the six digits through [confirm], and once
 * accepted saves the group with the roster verified up to the head the member named.
 */
suspend fun join(
    db: LinkedDatabase,
    store: Store,
    mailbox: Mailbox,
    qr: String,
    device: MemberSecrets,
    meta: DeviceMeta,
    confirm: suspend (String) -> Boolean,
) {
    val code = readOffer(qr)
    val token = runCatching { SyncBytes.b64urlDecode(code.t) }.getOrNull()?.takeIf { it.size == 16 } ?: throw LinkedException.Unexpected()
    val showerPublic = key(code.e)
    val secret = SyncCrypto.randomBytes(KEY_SIZE)
    val public = agreementPublicKey(secret)
    val keys = pairingKeys(secret, showerPublic, token, showerPublic, public)
    val channel = Channel(mailbox, SyncBytes.toHex(pairingSlot(token)), token, 'b', 'a')
    channel.sealKey = keys.scannerKey
    channel.openKey = keys.showerKey

    channel.send(Message.Join(SyncBytes.b64urlEncode(device.sigPublic), SyncBytes.b64urlEncode(device.kemPublic), meta), prefix = public)
    if (!confirm(keys.sas)) throw LinkedException.Declined()

    val entry = (channel.receive() as? Message.EntryMessage)?.entry ?: throw LinkedException.Unexpected()
    val me = memberSubject(device.sigPublic, device.kemPublic, Role.DEVICE)
    if (entry.op != Op.ADD || entry.add != listOf(me)) throw LinkedException.Unexpected()
    channel.send(Message.Accept(SyncBytes.b64urlEncode(sign(device.sigSeed, signingBytes(entry)))))

    val welcome = channel.receive() as? Message.Welcome ?: throw LinkedException.Unexpected()
    if (welcome.store.u != code.s.u || welcome.group != entry.group) throw LinkedException.Unexpected()
    val roster = store.rosterAfter(-1)
    val end = roster.indexOfFirst { it.seq == welcome.seq }
    if (end < 0 || replayRoster(roster.take(end + 1)).head != welcome.head || me.did !in replayRoster(roster).members) {
        reject(ProtocolError.ROSTER_CHAIN)
    }
    db.groups().addEntries(roster)
    db.groups().saveGroup(GroupRecord(welcome.group, welcome.store.u, welcome.store.i, welcome.link))
    channel.send(Message.Done)
}
