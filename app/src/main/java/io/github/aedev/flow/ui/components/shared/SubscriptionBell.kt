package io.github.aedev.flow.ui.components.shared

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val BELL_SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** The bell on every subscribe button that has no screen of its own behind it. */
@HiltViewModel
class SubscriptionBellViewModel
    @Inject
    constructor(
        private val subscriptionRepository: SubscriptionRepository,
    ) : ViewModel() {
        val notifyingChannelIds: StateFlow<Set<String>> =
            subscriptionRepository
                .notifyingChannelIds()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(BELL_SUBSCRIPTION_TIMEOUT_MS), emptySet())

        fun setNotifications(
            channelId: String,
            enabled: Boolean,
        ) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                subscriptionRepository.updateNotificationState(channelId, enabled)
            }
        }
    }

/** The activity's [SubscriptionBellViewModel], so every card, sheet and Short shares one. */
@Composable
fun sharedSubscriptionBellViewModel(): SubscriptionBellViewModel {
    val activity = LocalContext.current as? ComponentActivity
    return if (activity != null) hiltViewModel(activity) else hiltViewModel()
}

