package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.roster.Entry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Strict, and absent fields omitted, like roster entries. */
internal val pairingJson = Json { explicitNulls = false }

internal fun <T> canonical(
    serializer: KSerializer<T>,
    value: T,
): String = SyncSerialization.canonicalJson(pairingJson.encodeToJsonElement(serializer, value))

@Serializable
data class StoreLocation(
    val k: String,
    val u: String,
)

/** What the QR code carries: no long-term secret, only a one-time token and an ephemeral key. */
@Serializable
data class QrCode(
    val v: Int,
    val t: String,
    val e: String,
    val r: String,
    val d: String,
    val s: StoreLocation,
)

@Serializable
data class DeviceMeta(
    val app: String,
    val name: String,
    val platform: String,
)

@Serializable
data class WelcomeStore(
    val k: String,
    val u: String,
    val i: String,
)

@Serializable
internal sealed interface Message {
    @Serializable
    @SerialName("join")
    data class Join(
        val sig: String,
        val kem: String,
        val meta: DeviceMeta,
    ) : Message

    @Serializable
    @SerialName("entry")
    data class EntryMessage(
        val entry: Entry,
    ) : Message

    @Serializable
    @SerialName("accept")
    data class Accept(
        val sig: String,
    ) : Message

    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val group: String,
        val head: String,
        val seq: Long,
        val link: String,
        val store: WelcomeStore,
    ) : Message

    @Serializable
    @SerialName("done")
    data object Done : Message
}
