package io.github.aedev.flow.sync2.live

import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A handed-off video that hasn't loaded by then didn't start. */
private const val START_MS = 20_000L

/** Music is the player in use while it plays, or when it holds a track and video isn't playing. */
internal fun musicInUse(): Boolean =
    EnhancedMusicPlayerManager.currentTrack.value != null &&
        (EnhancedMusicPlayerManager.playerState.value.isPlaying || !GlobalPlayerState.playerState.value.isPlaying)

/**
 * What a handoff or a remote control does to this device's players (spec/live.md, Playing on another
 * device), on the main thread where they live. A video opens through the app shell, as a tapped link would.
 */
@Singleton
class LocalPlayback
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val video = EnhancedPlayerManager.getInstance()
        private val videos = MutableSharedFlow<String>(extraBufferCapacity = 1)

        /** Videos a handoff asks the app to open. */
        val openVideo: SharedFlow<String> = videos.asSharedFlow()

        /** Starts [play]'s session here; returns why it couldn't, or null once it started. */
        suspend fun start(play: LiveMessage.Play): String? = if (play.music) startMusic(play) else startVideo(play)

        private suspend fun startMusic(play: LiveMessage.Play): String? {
            val tracks =
                play.queue
                    .ifEmpty {
                        listOf(
                            play.video,
                        )
                    }.map { MusicTrack(it, "", "", "https://i.ytimg.com/vi/$it/hqdefault.jpg", 0) }
            val index = tracks.indexOfFirst { it.videoId == play.video }.takeIf { it >= 0 } ?: return "invalid"
            withContext(Dispatchers.Main) {
                // The music service resolves the stream from the id, as when a track is tapped.
                EnhancedMusicPlayerManager.playTrack(tracks[index], "music://${play.video}", tracks, index, play.pos)
                if (play.state != LiveMessage.PLAYING) EnhancedMusicPlayerManager.pause()
            }
            return null
        }

        private suspend fun startVideo(play: LiveMessage.Play): String? {
            withContext(Dispatchers.Main) { EnhancedMusicPlayerManager.pause() }
            if (!videos.tryEmit(play.video)) return "busy"
            withTimeoutOrNull(START_MS) {
                GlobalPlayerState.playerState.first { it.currentVideoId == play.video && it.isPrepared }
            } ?: return "unavailable"
            withContext(Dispatchers.Main) {
                video.seekTo(play.pos)
                if (play.state != LiveMessage.PLAYING) video.pause()
            }
            return null
        }

        /** Pauses whatever plays here, once another device has taken over. */
        suspend fun pause() =
            withContext(Dispatchers.Main) {
                EnhancedMusicPlayerManager.pause()
                video.pause()
            }

        /** Applies [cmd] to the player in use; returns why it couldn't, or null. */
        suspend fun command(cmd: LiveMessage.Cmd): String? =
            withContext(Dispatchers.Main) {
                val music = musicInUse()
                when (cmd.cmd) {
                    "play" -> {
                        if (music) EnhancedMusicPlayerManager.play() else video.play()
                    }

                    "pause" -> {
                        if (music) EnhancedMusicPlayerManager.pause() else video.pause()
                    }

                    "seek" -> {
                        (cmd.pos ?: return@withContext "invalid").let {
                            if (music) {
                                EnhancedMusicPlayerManager.seekTo(
                                    it,
                                )
                            } else {
                                video.seekTo(it)
                            }
                        }
                    }

                    "next" -> {
                        if (music) EnhancedMusicPlayerManager.playNext() else video.playNext()
                    }

                    "previous" -> {
                        if (music) EnhancedMusicPlayerManager.playPrevious() else video.playPrevious()
                    }

                    "volume" -> {
                        setVolume(cmd.vol ?: return@withContext "invalid")
                    }

                    "queue" -> {
                        return@withContext "unsupported"
                    }

                    else -> {
                        return@withContext "invalid"
                    }
                }
                null
            }

        private fun setVolume(percent: Int) {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, percent.coerceIn(0, 100) * max / 100, 0)
        }
    }
