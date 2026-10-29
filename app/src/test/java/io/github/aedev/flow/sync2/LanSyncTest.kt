package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.WsClient
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.lan.LanLink
import io.github.aedev.flow.sync2.lan.LanPeer
import io.github.aedev.flow.sync2.lan.SESSION_PATH
import io.github.aedev.flow.sync2.lan.acceptLink
import io.github.aedev.flow.sync2.lan.connectLink
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.LocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** LAN sessions over loopback (spec/live.md, Session and Requests): the hello, then each member pulls from the other. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LanSyncTest {
    private class Member(
        val keys: MemberSecrets,
        val db: LinkedDatabase,
        val local: LocalStore,
    ) {
        suspend fun session() = Session.open(db, GroupStore(local, null), keys)
    }

    /** A member of [g] that holds the roster and its own wrap, as a LAN group member would. */
    private suspend fun TestScope.member(
        g: LinkedGroup,
        keys: MemberSecrets,
    ): Member {
        val db = g.deviceDb()
        val local = heldStore(db)
        local.addEntries(g.store.roster)
        val did = SyncBytes.toHex(keys.did)
        g.store.keys[0 to did]?.let { local.putKey(0, did, it) }
        return Member(keys, db, local)
    }

    /** One session from [client] to [server], both pulling, as LanSync runs it. */
    private suspend fun meet(
        client: Member,
        server: Member,
    ) = withContext(Dispatchers.Default) {
        coroutineScope {
            val accepted = CompletableDeferred<SyncConnection>()
            val listener = WsServer(SESSION_PATH) { accepted.complete(it) }
            val port = listener.start()
            val connection = WsClient(OkHttpClient()).connect("ws://127.0.0.1:$port$SESSION_PATH")
            val serverSide = async { pullBoth(acceptLink(accepted.await(), server.session()), server) }
            pullBoth(connectLink(connection, client.session()), client)
            serverSide.await()
            listener.stop()
        }
    }

    private suspend fun pullBoth(
        link: LanLink,
        member: Member,
    ) = coroutineScope {
        val peer = LanPeer(link, member.session(), member.local)
        val reader = peer.start(this)
        peer.pull()
        peer.awaitQuiet(500)
        link.close()
        reader.join()
    }

    @Test
    fun members_pull_each_others_objects_in_one_session() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = member(g, g.a)
            val b = member(g, g.b)
            a.session().push(
                mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"aaaaaaaaaaa"}"""), 1, "")),
            )
            b.session().push(
                mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"bbbbbbbbbbb"}"""), 1, "")),
            )

            meet(client = a, server = b)

            assertTrue(SyncBytes.toHex(g.a.did) in b.session().pull().batches)
            assertTrue(SyncBytes.toHex(g.b.did) in a.session().pull().batches)
        }

    @Test
    fun a_device_that_is_not_a_member_is_refused_at_the_hello() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val b = member(g, g.b)
            val outsiderGroup = LinkedGroup(this).apply { create() }
            val outsider = member(outsiderGroup, outsiderGroup.a)
            val error =
                withContext(Dispatchers.Default) {
                    runCatching { meet(client = outsider, server = b) }.exceptionOrNull()
                }
            assertTrue("$error", error is ProtocolException || error is LinkedException)
        }
}
