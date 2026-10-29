package io.github.aedev.flow.ui.screens.sync.linked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.sync2.service.LastSync
import io.github.aedev.flow.sync2.service.LinkPhase
import io.github.aedev.flow.sync2.service.LinkedActions
import io.github.aedev.flow.sync2.service.LinkedDevice
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.sync2.service.linkedError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The group as this device sees it; null fields are not known yet. */
internal data class LinkedUiState(
    val storeUrl: String? = null,
    val recoverySaved: Boolean = true,
    val lastSync: LastSync? = null,
    val devices: List<LinkedDevice>? = null,
    /** Recovery words to show once, until the user proves they wrote them down. */
    val words: String? = null,
    val busy: Boolean = false,
    val error: LinkedError? = null,
) {
    val linked: Boolean get() = storeUrl != null
}

/** What the screen itself holds: the words being shown and the outcome of the last action. */
private data class Local(
    val devices: List<LinkedDevice>? = null,
    val words: String? = null,
    val busy: Boolean = false,
    val error: LinkedError? = null,
)

@HiltViewModel
internal class LinkedDevicesViewModel
    @Inject
    constructor(
        private val actions: LinkedActions,
    ) : ViewModel() {
        private val local = MutableStateFlow(Local())

        val state: StateFlow<LinkedUiState> =
            combine(actions.group, actions.lastSync, local) { group, lastSync, l ->
                LinkedUiState(
                    group?.storeUrl,
                    group?.recoverySaved ?: true,
                    lastSync,
                    l.devices,
                    l.words,
                    l.busy,
                    l.error ?: lastSync?.error,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkedUiState())

        val link: StateFlow<LinkPhase> = actions.link

        /** Runs one user action with the shared busy flag, showing its failure as the error. */
        private fun run(action: suspend () -> Unit) {
            viewModelScope.launch {
                local.update { it.copy(busy = true, error = null) }
                val error =
                    try {
                        action()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.linkedError()
                    }
                local.update { it.copy(busy = false, error = error) }
            }
        }

        fun refreshDevices() = run { local.update { it.copy(devices = actions.devices()) } }

        fun create(
            url: String,
            adminToken: String,
        ) = run { local.update { it.copy(words = actions.create(url, adminToken)) } }

        fun restore(
            url: String,
            words: String,
        ) = run { local.update { it.copy(words = actions.restore(url, words)) } }

        fun confirmWordsSaved() =
            run {
                actions.confirmRecoverySaved()
                local.update { it.copy(words = null) }
            }

        fun rename(
            id: String,
            name: String,
        ) = run {
            actions.rename(id, name)
            local.update { it.copy(devices = actions.devices()) }
        }

        fun remove(id: String) =
            run {
                actions.remove(id)
                local.update { it.copy(devices = actions.devices()) }
            }

        fun forget() =
            run {
                actions.forget()
                local.value = Local()
            }

        fun syncNow() = run { actions.syncNow() }

        fun join(code: String) = actions.join(code)

        fun addDevice() = actions.addDevice()

        fun answer(accept: Boolean) = actions.answer(accept)

        fun closeLink() {
            actions.cancel()
            if (state.value.linked) refreshDevices()
        }
    }
