package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.service.LinkedError

@Composable
internal fun LinkedError.message(): String =
    stringResource(
        when (this) {
            LinkedError.INSECURE -> R.string.linked_error_insecure
            LinkedError.UNREACHABLE -> R.string.linked_error_unreachable
            LinkedError.REFUSED -> R.string.linked_error_refused
            LinkedError.INSTANCE_CHANGED -> R.string.linked_error_instance_changed
            LinkedError.PROTOCOL -> R.string.linked_error_protocol
            LinkedError.LOCAL -> R.string.linked_error_local
            LinkedError.NOT_LINKED -> R.string.linked_error_not_linked
            LinkedError.REMOVED -> R.string.linked_error_removed
            LinkedError.DECLINED -> R.string.linked_error_declined
            LinkedError.TIMED_OUT -> R.string.linked_error_timed_out
            LinkedError.WRONG_WORDS -> R.string.linked_error_wrong_words
            LinkedError.RECOVERY_USED -> R.string.linked_error_recovery_used
            LinkedError.ALREADY_LINKED -> R.string.linked_error_already_linked
            LinkedError.UNEXPECTED -> R.string.linked_error_unexpected
        },
    )
