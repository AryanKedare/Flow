package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.recoveryKeys
import io.github.aedev.flow.sync2.crypto.recoveryWords
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.roster.Draft
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.epochDraft
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.roster.sealDeviceMeta
import io.github.aedev.flow.sync2.store.ServerStore

/**
 * Creates the group with this device and a new recovery key, claims the server with the operator's
 * admin token, and returns the recovery words to show once.
 */
suspend fun createGroup(
    db: LinkedDatabase,
    storeUrl: String,
    adminToken: String,
    device: MemberSecrets,
    meta: DeviceMeta,
    link: String,
): String {
    val group = SyncCrypto.randomBytes(16)
    val epochKey = SyncCrypto.randomBytes(32)
    val recoverySecret = SyncCrypto.randomBytes(32)
    val recovery = recoveryKeys(recoverySecret)
    val members = listOf(device, recovery)
    val sealedMeta = sealDeviceMeta(epochKey, group, 0, 0, device.did, metaJson(meta))
    val draft =
        Draft(
            op = Op.GENESIS,
            signer = SyncBytes.toHex(device.did),
            add =
                listOf(
                    memberSubject(device.sigPublic, device.kemPublic, Role.DEVICE),
                    memberSubject(recovery.sigPublic, recovery.kemPublic, Role.RECOVERY),
                ),
            epoch = epochDraft(epochKey, group, 0, members.map { SyncBytes.toHex(it.did) }),
            meta = mapOf(SyncBytes.toHex(device.did) to SyncBytes.b64urlEncode(sealedMeta)),
        )
    val genesis = buildEntry(group, null, draft, members)
    val wraps =
        members.associate {
            SyncBytes.toHex(it.did) to
                SyncBytes.b64urlEncode(wrapEpochKey(it.kemPublic, group, 0, it.did, epochKey))
        }

    val store = ServerStore.connect(storeUrl, device, null, SyncBytes.toHex(group))
    store.claim(adminToken, genesis, wraps)
    val roster = store.rosterAfter(-1)
    if (roster.map(::entryHash) != listOf(entryHash(genesis))) throw LinkedException.Unexpected()
    db.groups().addEntries(roster)
    db.groups().saveGroup(GroupRecord(SyncBytes.toHex(group), storeUrl, store.identity().first, link))
    recovery.wipe()
    return recoveryWords(recoverySecret).also { recoverySecret.fill(0) }
}
