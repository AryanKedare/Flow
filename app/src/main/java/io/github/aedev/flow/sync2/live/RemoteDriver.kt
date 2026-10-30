package io.github.aedev.flow.sync2.live

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.PlaybackTarget
import io.github.aedev.flow.player.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A followed song's position moves on this often between the `now` its device sends. */
private const val FOLLOW_TICK_MS = 1_000L

/**
 * Drives the device the user chose in "Play on" from this one, Spotify Connect style: every playback
 * action in the app goes there, and the music players follow what it plays.
 */
@Singleton
class RemoteDriver
    @Inject
    constructor(
        private val choice: RemoteChoice,
        private val handoff: Handoff,
        private val devices: LiveDevices,
        private val details: TrackDetails,
        private val nowPlaying: NowPlaying,
        @ApplicationScope private val scope: CoroutineScope,
    ) : RemotePlayer {
        private fun peer(): LivePeer? = choice.did.value?.let { devices.devices.value[it] }

        override val playing: Boolean get() = peer()?.playing == true

        override fun command(
            cmd: String,
            pos: Long?,
        ) {
            val did = choice.did.value ?: return
            scope.launch { handoff.control(did, cmd, pos) }
        }

        override fun play(
            queue: List<String>,
            index: Int,
            music: Boolean,
        ) {
            val did = choice.did.value ?: return
            scope.launch { handoff.play(did, queue, index, music) }
        }

        override fun queue(ids: List<String>) {
            val did = choice.did.value ?: return
            if (ids == peer()?.now?.queue) return
            scope.launch { handoff.control(did, "queue", queue = ids) }
        }

        /** Sends the app's playback actions to the chosen device while there is one. */
        fun install() {
            scope.launch { choice.did.collect { PlaybackTarget.remote = if (it != null) this@RemoteDriver else null } }
        }

        /** Keeps the choice current and the music players on the chosen device's song, while live runs. */
        suspend fun run() =
            coroutineScope {
                launch { choice.watch(nowPlaying.playing, merge(devices.devices, devices.online)) { devices.isOnline(it) } }
                combine(choice.did, devices.devices) { did, peers -> did?.let { peers[it] } }
                    .distinctUntilChanged { old, new -> old?.now == new?.now }
                    .collectLatest { peer -> peer?.takeIf { it.now?.music == true }?.let { follow(it) } }
            }

        private suspend fun follow(peer: LivePeer) {
            val now = peer.now ?: return
            val ids = now.queue.ifEmpty { listOfNotNull(now.video) }
            val index = now.index.takeIf { ids.getOrNull(it) == now.video } ?: ids.indexOf(now.video)
            EnhancedMusicPlayerManager.follow(tracks(ids, now), index, peer.position(System.currentTimeMillis()), now.dur)
            if (ids.any { details.knownSong(it) == null }) {
                details.songs(ids)
                EnhancedMusicPlayerManager.follow(tracks(ids, now), index, peer.position(System.currentTimeMillis()), now.dur)
            }
            while (now.state == LiveMessage.PLAYING) {
                delay(FOLLOW_TICK_MS)
                EnhancedMusicPlayerManager.followPosition(peer.position(System.currentTimeMillis()), now.dur)
            }
        }

        private fun tracks(
            ids: List<String>,
            now: LiveMessage.Now,
        ): List<MusicTrack> = ids.map { id -> details.knownSong(id) ?: songOf(id, now) }
    }
