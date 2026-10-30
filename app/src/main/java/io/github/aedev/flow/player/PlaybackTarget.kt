package io.github.aedev.flow.player

/** Another device the app's playback goes to while the user drives it from here ("Play on"). */
interface RemotePlayer {
    val playing: Boolean

    fun command(
        cmd: String,
        pos: Long? = null,
    )

    fun play(
        queue: List<String>,
        index: Int,
        music: Boolean,
    )

    fun queue(ids: List<String>)
}

/**
 * Where the app's playback actions go: this device's players, or [remote] while the user drives
 * another device. A request another device makes of this one runs [here], so it never bounces back.
 */
object PlaybackTarget {
    @Volatile var remote: RemotePlayer? = null

    @Volatile private var applyingHere = 0

    /** The device that takes a playback action now, or null for this one. Main thread only. */
    val current: RemotePlayer? get() = remote.takeIf { applyingHere == 0 }

    fun <T> here(block: () -> T): T {
        applyingHere++
        try {
            return block()
        } finally {
            applyingHere--
        }
    }
}
