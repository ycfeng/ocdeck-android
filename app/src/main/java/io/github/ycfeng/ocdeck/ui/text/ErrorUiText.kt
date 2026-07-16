package io.github.ycfeng.ocdeck.ui.text

import androidx.annotation.StringRes
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailure
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailureClassifier
import io.github.ycfeng.ocdeck.core.network.OpenCodeOperationRejectionReason

fun Throwable.toErrorUiText(@StringRes fallback: Int): UiText =
    OpenCodeFailureClassifier.classify(this).toErrorUiText(fallback)

fun OpenCodeFailure.toErrorUiText(@StringRes fallback: Int): UiText = when (this) {
    is OpenCodeFailure.HttpStatus -> when (code) {
        401 -> UiText.Resource(R.string.error_http_unauthorized)
        403 -> UiText.Resource(R.string.error_http_forbidden)
        429 -> UiText.Resource(R.string.error_http_rate_limited)
        in 500..599 -> UiText.Resource(R.string.error_http_server, listOf(code))
        else -> UiText.Resource(R.string.error_http_status, listOf(code))
    }
    OpenCodeFailure.NetworkUnavailable -> UiText.Resource(R.string.error_network_unavailable)
    OpenCodeFailure.Timeout -> UiText.Resource(R.string.error_timeout)
    OpenCodeFailure.InvalidResponse -> UiText.Resource(R.string.error_invalid_response)
    OpenCodeFailure.ResponseTooLarge -> UiText.Resource(R.string.error_response_too_large)
    OpenCodeFailure.TransportChanged -> UiText.Resource(R.string.error_transport_changed)
    is OpenCodeFailure.OperationRejected -> UiText.Resource(
        when (reason) {
            OpenCodeOperationRejectionReason.LocalPortInUse -> R.string.error_local_port_in_use
            OpenCodeOperationRejectionReason.StcpComponentUnavailable -> R.string.error_stcp_component_unavailable
            OpenCodeOperationRejectionReason.Unspecified -> R.string.error_operation_rejected
        },
    )
    OpenCodeFailure.Unknown -> UiText.Resource(fallback)
}
