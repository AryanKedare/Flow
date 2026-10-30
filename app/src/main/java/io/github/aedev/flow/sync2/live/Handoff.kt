package io.github.aedev.flow.sync2.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A device that hasn't answered by then didn't take the request. */
private const val RESULT_MS = 15_000L

/**
 * Handoff and remote control between the group's devices, like a single active player (spec/live.md,
 * Playing on another device): this device answers `play` and `cmd` addressed to it, once the user
 * allows the sender, and sends them to others.
 */
@Singleton
class Handoff
    @Inject
    constructor(
        private val live: LiveChannel,
        private val devices: LiveDevices,
        private val consent: LiveConsent,
        private val local: LocalPlayback,
        private val now: NowPlaying,
    ) {
        private val waiting = ConcurrentHashMap<Long, CompletableDeferred<LiveMessage.Result>>()
        private val applied = MutableSharedFlow<String>(extraBufferCapacity = 4)

        /** Devices whose `play` or `cmd` this device just applied, so it can say who drives it. */
        val controlledBy: SharedFlow<String> = applied.asSharedFlow()

        /** Results that came back before their request was registered, on a fast LAN. */
        private val early = ConcurrentHashMap<Long, LiveMessage.Result>()

        /** Answers what other devices send until cancelled; a question to the user never holds up the rest. */
        suspend fun run() {
            coroutineScope {
                live.messages.collect { received ->
                    devices.heard(received)
                    launch { handle(received) }
                }
            }
        }

        private suspend fun handle(received: LiveReceived) {
            val me = live.me() ?: return
            when (val message = received.message) {
                is LiveMessage.Play -> {
                    if (message.to == me) answer(received) { local.start(message) }
                }

                is LiveMessage.Cmd -> {
                    if (message.to == me) answer(received) { local.command(message) }
                }

                is LiveMessage.Result -> {
                    if (message.to ==
                        me
                    ) {
                        waiting.remove(message.ref)?.complete(message) ?: early.put(message.ref, message)
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        private suspend fun answer(
            received: LiveReceived,
            apply: suspend () -> String?,
        ) {
            val reason = if (consent.allows(received.sender)) apply() else "declined"
            live.send(LiveMessage.Result(received.sender, received.seq, reason == null, reason))
            if (reason == null) applied.emit(received.sender)
        }

        /** Moves this device's session to [to]; this device pauses only once [to] says it started, so a failed handoff never stops the music. */
        suspend fun transfer(to: String): Boolean {
            val current = now.snapshot()
            val video = current.video ?: return false
            val play =
                LiveMessage.Play(
                    to,
                    video,
                    current.pos,
                    current.music,
                    current.link,
                    current.queue,
                    current.index,
                    LiveMessage.PLAYING,
                )
            val ok = request(play)?.ok == true
            if (ok) local.pause()
            return ok
        }

        /** Brings the active device's session here: it pauses, and this device starts from its latest `now`. */
        suspend fun pull(): Boolean {
            val active = devices.active() ?: return false
            val playing = active.now ?: return false
            val video = playing.video ?: return false
            val me = live.me() ?: return false
            live.send(LiveMessage.Cmd(active.did, "pause"))
            val position = active.position(System.currentTimeMillis())
            return local.start(
                LiveMessage.Play(me, video, position, playing.music, playing.link, playing.queue, playing.index, LiveMessage.PLAYING),
            ) ==
                null
        }

        /** Controls playback on [to]; true once it applied the command. */
        suspend fun control(
            to: String,
            cmd: String,
            pos: Long? = null,
            vol: Int? = null,
        ): Boolean = request(LiveMessage.Cmd(to, cmd, pos, vol))?.ok == true

        private suspend fun request(message: LiveMessage): LiveMessage.Result? {
            val seq = live.send(message) ?: return null
            val answer = CompletableDeferred<LiveMessage.Result>()
            waiting[seq] = answer
            early.remove(seq)?.let { answer.complete(it) }
            return try {
                withTimeoutOrNull(RESULT_MS) { answer.await() }
            } finally {
                waiting.remove(seq)
            }
        }
    }
