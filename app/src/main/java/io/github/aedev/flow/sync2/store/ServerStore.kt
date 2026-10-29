package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.qr.LocalNetworkAddress
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.sign
import io.github.aedev.flow.sync2.crypto.tag
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.entryJson
import io.github.aedev.flow.sync2.roster.parseEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Refresh a token this long before it expires. */
private const val TOKEN_MARGIN_MS = 60_000L
private val JSON = "application/json".toMediaType()
private val BYTES = "application/octet-stream".toMediaType()

/** A `.local` name may resolve only to local addresses, checked on every connection. */
private val LOCAL_ONLY_DNS =
    Dns { host ->
        Dns.SYSTEM.lookup(host).takeIf { addresses -> addresses.all { LocalNetworkAddress.isLocal(it.hostAddress.orEmpty()) } }
            ?: throw UnknownHostException("$host is not on the local network")
    }

/**
 * The Flow sync server as a store (spec/api.md). Requests are signed in with this member's roster
 * key; the server never sees anything but sealed objects.
 */
class ServerStore private constructor(
    private val base: HttpUrl,
    private val client: OkHttpClient,
    private val member: MemberSecrets,
    private var instance: String?,
    private var group: String?,
) : Store,
    Mailbox {
    private val session = Mutex()
    private var token: Pair<String, Long>? = null

    companion object {
        /**
         * Prepares a connection to [url] for [member]. HTTPS anywhere; plain HTTP only to a private
         * address or a `.local` name. [instance] and [group] are the ids pinned at pairing; a device
         * restoring from its recovery key doesn't know them yet.
         */
        fun connect(
            url: String,
            member: MemberSecrets,
            instance: String?,
            group: String?,
        ): ServerStore {
            val base = url.toHttpUrlOrNull() ?: throw StoreException.Insecure()
            val builder =
                OkHttpClient
                    .Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS)
            when {
                base.isHttps || LocalNetworkAddress.isLocal(base.host) -> Unit
                base.host.endsWith(".local", ignoreCase = true) -> builder.dns(LOCAL_ONLY_DNS)
                else -> throw StoreException.Insecure()
            }
            return ServerStore(base, builder.build(), member, instance, group)
        }
    }

    /** The instance and group ids, once signed in; the caller stores them to pin later sessions. */
    suspend fun identity(): Pair<String?, String?> = session.withLock { instance to group }

    private fun url(path: String): HttpUrl = base.resolve(path) ?: throw StoreException.Malformed()

    private suspend fun send(request: Request): Response =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw StoreException.Network(e)
            }
        }

    private suspend fun postJson(
        path: String,
        body: JsonElement,
    ): JsonObject =
        decode(
            send(
                Request
                    .Builder()
                    .url(url(path))
                    .post(body.toString().toRequestBody(JSON))
                    .build(),
            ),
        )

    private suspend fun signIn(): String {
        val did = SyncBytes.toHex(member.did)
        val challenge = postJson("v1/auth/challenge", buildJsonObject { put("did", did) })
        val field = { name: String -> challenge[name]?.jsonPrimitive?.contentOrNull ?: throw StoreException.Malformed() }
        val (instanceId, groupId, nonce) = Triple(field("instance"), field("group"), field("challenge"))
        if (instance.let { it != null && it != instanceId } || group.let { it != null && it != groupId }) {
            throw StoreException.InstanceChanged()
        }
        val message =
            try {
                tag("auth") + SyncBytes.fromHex(instanceId) + SyncBytes.fromHex(groupId) + SyncBytes.b64urlDecode(nonce)
            } catch (_: IllegalArgumentException) {
                throw StoreException.Malformed()
            }
        val issued =
            postJson(
                "v1/auth/token",
                buildJsonObject {
                    put("did", did)
                    put("challenge", nonce)
                    put("sig", SyncBytes.b64urlEncode(sign(member.sigSeed, message)))
                },
            )
        val value = issued["token"]?.jsonPrimitive?.contentOrNull ?: throw StoreException.Malformed()
        val expires = issued["expires"]?.jsonPrimitive?.longOrNull ?: throw StoreException.Malformed()
        instance = instanceId
        group = groupId
        token = value to expires
        return value
    }

    private suspend fun token(renew: Boolean): String =
        session.withLock {
            token?.takeIf { (_, expires) -> !renew && expires > System.currentTimeMillis() + TOKEN_MARGIN_MS }?.first ?: signIn()
        }

    /** Sends an authenticated request, signing in again once if the token was refused. */
    private suspend fun authorized(
        method: String,
        path: String,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        for (renew in listOf(false, true)) {
            val request =
                Request
                    .Builder()
                    .url(url(path))
                    .method(method, body)
                    .header("Authorization", "Bearer ${token(renew)}")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
            val response = send(request)
            if (response.code != 401 || renew) return response
            response.close()
        }
        error("the loop returns on its second pass")
    }

    private suspend fun getBytes(path: String): ByteArray? =
        authorized("GET", path).use { response ->
            if (response.code == 404) null else check(response).body.bytes()
        }

    /** Creates the group on an unclaimed server with the operator's admin token. */
    suspend fun claim(
        adminToken: String,
        genesis: Entry,
        wraps: Map<String, String>,
    ) {
        val body =
            buildJsonObject {
                put("entry", entryJson(genesis))
                put("wraps", wrapsJson(wraps))
            }
        val request =
            Request
                .Builder()
                .url(
                    url("v1/claim"),
                ).header("Authorization", "Bearer $adminToken")
                .post(body.toString().toRequestBody(JSON))
                .build()
        check(send(request)).close()
    }

    override suspend fun rosterAfter(after: Long): List<Entry> =
        decode(authorized("GET", "v1/roster?after=$after"))["entries"]
            ?.jsonArray
            ?.map(::parseEntry)
            ?: throw StoreException.Malformed()

    override suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended {
        val body =
            buildJsonObject {
                put("entry", entryJson(entry))
                put("wraps", wrapsJson(wraps))
                chain?.let { put("chain", it) }
            }
        val response = authorized("POST", "v1/roster", body.toString().toRequestBody(JSON), mapOf("If-Match" to "\"$head\""))
        if (response.code == 409) {
            val current = decodeBody(response)["head"]?.jsonPrimitive?.contentOrNull ?: throw StoreException.Malformed()
            return Appended.Conflict(current)
        }
        check(response).close()
        return Appended.Done
    }

    override suspend fun key(
        epoch: Int,
        name: String,
    ): ByteArray? = getBytes("v1/keys/${epochName(epoch)}/$name.k")

    override suspend fun putObject(
        path: String,
        bytes: ByteArray,
    ) {
        check(authorized("PUT", "v1/objects/$path", bytes.toRequestBody(BYTES))).close()
    }

    override suspend fun getObject(path: String): ByteArray? = getBytes("v1/objects/$path")

    override suspend fun deleteObject(path: String) {
        authorized("DELETE", "v1/objects/$path").use { if (it.code != 404) check(it) }
    }

    override suspend fun changes(cursor: Long): Pair<List<ChangedObject>, Long> {
        val value = decode(authorized("GET", "v1/changes?after=$cursor"))
        val objects =
            try {
                Json.decodeFromJsonElement(ListSerializer(ChangedObject.serializer()), value["objects"] ?: throw StoreException.Malformed())
            } catch (_: SerializationException) {
                throw StoreException.Malformed()
            }
        val next = value["cursor"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: throw StoreException.Malformed()
        return objects to next
    }

    // Messages need no token: the new device has none yet, and only a holder of the QR code can derive the slot.
    override suspend fun openSlot(slot: String) {
        check(authorized("POST", "v1/pair", buildJsonObject { put("slot", slot) }.toString().toRequestBody(JSON))).close()
    }

    override suspend fun putMessage(
        slot: String,
        name: String,
        bytes: ByteArray,
    ) {
        check(
            send(
                Request
                    .Builder()
                    .url(url("v1/pair/$slot/$name"))
                    .put(bytes.toRequestBody(BYTES))
                    .build(),
            ),
        ).close()
    }

    override suspend fun getMessage(
        slot: String,
        name: String,
    ): ByteArray? =
        send(Request.Builder().url(url("v1/pair/$slot/$name")).build()).use { response ->
            if (response.code == 404) null else check(response).body.bytes()
        }

    override suspend fun closeSlot(slot: String) {
        check(authorized("DELETE", "v1/pair/$slot")).close()
    }
}

private fun check(response: Response): Response {
    if (response.isSuccessful) return response
    val code =
        response.use {
            runCatching {
                Json
                    .parseToJsonElement(it.body.string())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        }
    throw StoreException.Refused(response.code, code ?: "unknown")
}

private fun decodeBody(response: Response): JsonObject =
    response.use {
        runCatching { Json.parseToJsonElement(it.body.string()).jsonObject }.getOrElse { throw StoreException.Malformed() }
    }

private fun wrapsJson(wraps: Map<String, String>): JsonObject = JsonObject(wraps.mapValues { (_, wrap) -> JsonPrimitive(wrap) })

private fun decode(response: Response): JsonObject = decodeBody(check(response))
