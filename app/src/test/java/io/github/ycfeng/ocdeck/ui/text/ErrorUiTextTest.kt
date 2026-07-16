package io.github.ycfeng.ocdeck.ui.text

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailure
import io.github.ycfeng.ocdeck.core.network.OpenCodeOperationRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ErrorUiTextTest {
    @Test
    fun semanticFailuresMapToLocalizedResources() {
        val fallback = R.string.session_send_failed
        val cases = listOf(
            OpenCodeFailure.HttpStatus(401) to UiText.Resource(R.string.error_http_unauthorized),
            OpenCodeFailure.HttpStatus(403) to UiText.Resource(R.string.error_http_forbidden),
            OpenCodeFailure.HttpStatus(429) to UiText.Resource(R.string.error_http_rate_limited),
            OpenCodeFailure.HttpStatus(503) to UiText.Resource(R.string.error_http_server, listOf(503)),
            OpenCodeFailure.HttpStatus(418) to UiText.Resource(R.string.error_http_status, listOf(418)),
            OpenCodeFailure.NetworkUnavailable to UiText.Resource(R.string.error_network_unavailable),
            OpenCodeFailure.Timeout to UiText.Resource(R.string.error_timeout),
            OpenCodeFailure.InvalidResponse to UiText.Resource(R.string.error_invalid_response),
            OpenCodeFailure.ResponseTooLarge to UiText.Resource(R.string.error_response_too_large),
            OpenCodeFailure.TransportChanged to UiText.Resource(R.string.error_transport_changed),
            OpenCodeFailure.OperationRejected(OpenCodeOperationRejectionReason.LocalPortInUse) to
                UiText.Resource(R.string.error_local_port_in_use),
            OpenCodeFailure.OperationRejected(OpenCodeOperationRejectionReason.StcpComponentUnavailable) to
                UiText.Resource(R.string.error_stcp_component_unavailable),
            OpenCodeFailure.OperationRejected() to UiText.Resource(R.string.error_operation_rejected),
            OpenCodeFailure.Unknown to UiText.Resource(fallback),
        )

        cases.forEach { (failure, expected) ->
            assertEquals(expected, failure.toErrorUiText(fallback))
        }
    }

    @Test
    fun uiTextSummariesDoNotExposeRawValuesOrArguments() {
        val secret = "ui-secret"

        assertFalse(UiText.Raw(secret).toString().contains(secret))
        assertFalse(UiText.Resource(R.string.error_http_status, listOf(secret)).toString().contains(secret))
    }
}
