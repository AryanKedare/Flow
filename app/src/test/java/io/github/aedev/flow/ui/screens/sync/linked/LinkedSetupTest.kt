package io.github.aedev.flow.ui.screens.sync.linked

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Choosing where a new group lives, and the WebDAV form that creates it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h1600dp")
class LinkedSetupTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(id: Int) = context.getString(id)

    private val created = ArrayList<List<String>>()

    private fun show() =
        rule.setContent {
            LinkedSetup(
                hostOnly = false,
                busy = false,
                onCreate = { _, _ -> },
                onCreateOnWebDav = { url, user, password -> created += listOf(url, user, password) },
                onCreateOnNetwork = {},
                onEnterCode = { _, _ -> },
                onJoinWithAdmin = { _, _ -> },
                onShowCode = {},
                onRestore = { _, _ -> },
                onRestoreFromWebDav = { _, _, _, _ -> },
            )
        }

    @Test
    fun a_new_group_can_live_on_a_server_in_a_webdav_folder_or_on_this_network() {
        show()
        rule.onNodeWithText(text(R.string.linked_create_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_server)).assertExists()
        rule.onNodeWithText(text(R.string.linked_where_webdav)).assertExists()
        rule.onNodeWithText(text(R.string.linked_create_lan_title)).assertExists()
    }

    @Test
    fun the_webdav_form_creates_only_with_an_address_and_a_login() {
        show()
        rule.onNodeWithText(text(R.string.linked_create_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_webdav)).performClick()
        val create = rule.onNodeWithText(text(R.string.linked_create))
        create.assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.linked_webdav_url)).performTextInput(" https://cloud.example.org/dav/flow/ ")
        rule.onNodeWithText(text(R.string.linked_webdav_user)).performTextInput("me")
        create.assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.linked_webdav_password)).performTextInput("secret")
        create.assertIsEnabled().performClick()
        assertEquals(listOf(listOf("https://cloud.example.org/dav/flow/", "me", "secret")), created)
    }

    @Test
    fun a_group_is_restored_from_a_server_or_a_webdav_folder_but_not_a_network() {
        show()
        rule.onNodeWithText(text(R.string.linked_restore_title)).performClick()
        rule.onNodeWithText(text(R.string.linked_where_webdav)).assertExists()
        rule.onNodeWithText(text(R.string.linked_create_lan_title)).assertDoesNotExist()
    }
}
