package io.github.aedev.flow.ui

/** The one mini player the shell shows. Never more than one: they are not stacked. */
internal enum class ActiveMiniPlayer { None, Video, VideoBar, Music, Remote }

/**
 * A video wins over music because starting either clears the other, so both are only cached
 * together for the moment a hand-over takes. A video playing in the background gets the bar.
 * With nothing loaded here, the device the user chose in "Play on" gets the remote bar.
 */
internal fun resolveActiveMiniPlayer(
    hasVideo: Boolean,
    videoVisible: Boolean,
    videoInBackground: Boolean,
    onShortsPlayer: Boolean,
    hasMusic: Boolean,
    musicSuppressed: Boolean,
    remoteSession: Boolean = false,
): ActiveMiniPlayer =
    when {
        hasVideo && onShortsPlayer -> ActiveMiniPlayer.None
        hasVideo && videoInBackground -> ActiveMiniPlayer.VideoBar
        hasVideo -> if (videoVisible) ActiveMiniPlayer.Video else ActiveMiniPlayer.None
        hasMusic && !musicSuppressed -> ActiveMiniPlayer.Music
        remoteSession -> ActiveMiniPlayer.Remote
        else -> ActiveMiniPlayer.None
    }
