package io.github.aedev.flow.data.profile

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.window.SplashScreen
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.MainActivity
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.data.scrobble.Scrobbler
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.notification.WellbeingAlarms
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicResumeTracker
import io.github.aedev.flow.player.PlaybackShutdown
import io.github.aedev.flow.service.MusicServiceState
import io.github.aedev.flow.widget.core.state.clearNowPlayingSnapshot
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** The switch steps on a device. It only calls the release and save paths the app already has. */
class AppProfileSwitchSteps
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val musicResumeTracker: MusicResumeTracker,
        private val scrobbler: Scrobbler,
        private val musicBrainEngine: MusicBrainEngine,
        private val neuroEngine: FlowNeuroEngine,
        private val videoStats: VideoStatsRecorder,
    ) : ProfileSwitchSteps {
        private val registry: ProfileRegistry get() = ActiveProfile.registry(context)

        override suspend fun refusal(target: String): SwitchRefusal? =
            when {
                target == ActiveProfile.get(context).id -> SwitchRefusal.ALREADY_ACTIVE

                // The car shows this profile's library and would drop its connection mid-drive.
                MusicServiceState.isCarConnected -> SwitchRefusal.CAR_CONNECTED

                registry.state
                    .first()
                    .profiles
                    .none { it.id == target } -> SwitchRefusal.UNKNOWN_PROFILE

                else -> null
            }

        override fun closeGate() = ProfileSwitchGate.close()

        override fun reopenGate() = ProfileSwitchGate.reopen()

        override fun finishOpenSessions() = BeforeProfileSwitch.run()

        override suspend fun saveQueue() = EnhancedMusicPlayerManager.persistQueueNow()

        override suspend fun stopPlayback() = PlaybackShutdown.stopForSwitch(context)

        // The music brain goes after the service has stopped: stopping finalizes the listen session it learns from.
        override suspend fun awaitWrites() {
            musicResumeTracker.awaitWrites()
            scrobbler.awaitWrites()
            musicBrainEngine.awaitWrites()
            neuroEngine.awaitWrites()
            videoStats.awaitWrites()
        }

        override suspend fun cancelProfileWork() {
            ProfileWork.cancelAll(context)
            WellbeingAlarms.cancel(context)
            NotificationHelper.cancelProfileNotifications(context)
            // The Now Playing widgets would keep this profile's last track until the next one plays something.
            context.clearNowPlayingSnapshot()
        }

        override suspend fun persistActive(target: String) {
            registry.setActive(target)
        }

        override fun restart() {
            val relaunch = Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java))
            // Measured in PR 2.0: a start that does not come from the launcher gets a splash without the icon.
            val options =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityOptions.makeBasic().setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON).toBundle()
                } else {
                    null
                }
            context.startActivity(relaunch, options)
            Runtime.getRuntime().exit(0)
        }
    }
