package io.github.aedev.flow.ui.components.shared

import android.app.Application
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FlowSearchFieldKeyboardTest {
    @get:Rule
    val rule = createComposeRule()

    private val focusRequester = FocusRequester()
    private val state = TextFieldState()
    private val editorInfo = EditorInfo()
    private var inputConnection: InputConnection? = null
    private lateinit var inputModeManager: InputModeManager
    private val searches = mutableListOf<String>()
    private val keyboard = RecordingKeyboardController()
    private val interceptor =
        PlatformTextInputInterceptor { request, _ ->
            inputConnection = request.createInputConnection(editorInfo)
            awaitCancellation()
        }

    private fun focusSearchUsingRemote() {
        rule.setContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDpadNavigation provides true,
                    LocalSoftwareKeyboardController provides keyboard,
                ) {
                    InterceptPlatformTextInput(interceptor) {
                        FlowSearchField(
                            state = state,
                            placeholder = "Search",
                            focusRequester = focusRequester,
                            onSearch = searches::add,
                        )
                    }
                }
            }
        }
        rule.runOnIdle {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            focusRequester.requestFocus()
        }
        rule.waitForIdle()
    }

    @Test
    fun `remote focus starts a working keyboard session with a search action`() {
        focusSearchUsingRemote()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.runOnIdle {
            assertThat(inputConnection).isNotNull()
            assertThat(editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION).isEqualTo(EditorInfo.IME_ACTION_SEARCH)
            inputConnection!!.commitText("android tv", 1)
        }
        rule.onNode(hasSetTextAction()).assertTextEquals("android tv", includeEditableText = true)
        rule.runOnIdle { inputConnection!!.performEditorAction(EditorInfo.IME_ACTION_SEARCH) }
        rule.runOnIdle { assertThat(searches).containsExactly("android tv") }
    }

    @Test
    fun `remote OK can reopen the keyboard while the search field remains focused`() {
        focusSearchUsingRemote()
        rule.runOnIdle { keyboard.hide() }
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertThat(keyboard.showCalls).isEqualTo(1) }
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }
}

private class RecordingKeyboardController : SoftwareKeyboardController {
    var showCalls = 0

    override fun show() {
        showCalls++
    }

    override fun hide() = Unit
}
