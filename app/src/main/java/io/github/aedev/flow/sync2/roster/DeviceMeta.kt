package io.github.aedev.flow.sync2.roster

import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.open
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.tag
import io.github.aedev.flow.sync2.crypto.u64

private fun metaAad(
    group: ByteArray,
    seq: Long,
    did: ByteArray,
): ByteArray = tag("meta") + group + u64(seq) + did

/** Opens the `meta` box an entry carries for one device, under the epoch in force after it. */
fun openDeviceMeta(
    epochKey: ByteArray,
    group: ByteArray,
    epoch: Int,
    seq: Long,
    did: ByteArray,
    sealed: ByteArray,
): String =
    decodePayload(open(purposeKey(epochKey, group, epoch, Purpose.ROSTER), metaAad(group, seq, did), sealed)).toString(Charsets.UTF_8)
