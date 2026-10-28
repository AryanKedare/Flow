package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.notifyingIds
import org.junit.Test

class SubscriptionBellTest {
    private fun channel(
        id: String,
        notify: Boolean,
    ) = ChannelSubscription(channelId = id, channelName = id, channelThumbnail = "", isNotificationEnabled = notify)

    @Test
    fun `only channels with the bell on are notifying`() {
        val ids = notifyingIds(listOf(channel("a", true), channel("b", false), channel("c", true)))

        assertThat(ids).containsExactly("a", "c")
    }

}
