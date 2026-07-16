package io.github.ycfeng.ocdeck.core.network

import com.jcraft.jsch.JSchException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeApiMismatchException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

sealed interface OpenCodeFailure {
    data class HttpStatus(val code: Int) : OpenCodeFailure
    data object NetworkUnavailable : OpenCodeFailure
    data object Timeout : OpenCodeFailure
    data object InvalidResponse : OpenCodeFailure
    data object ResponseTooLarge : OpenCodeFailure
    data object TransportChanged : OpenCodeFailure
    data class OperationRejected(
        val reason: OpenCodeOperationRejectionReason = OpenCodeOperationRejectionReason.Unspecified,
    ) : OpenCodeFailure
    data object Unknown : OpenCodeFailure
}

enum class OpenCodeOperationRejectionReason {
    Unspecified,
    LocalPortInUse,
    StcpComponentUnavailable,
}

open class OpenCodeRequestException(
    val failure: OpenCodeFailure,
    cause: Throwable? = null,
) : IOException(null, cause) {
    override fun toString(): String = "${javaClass.name}(failure=$failure)"
}

class LocalPortInUseException(
    val localPort: Int,
    cause: Throwable? = null,
) : OpenCodeRequestException(
    failure = OpenCodeFailure.OperationRejected(OpenCodeOperationRejectionReason.LocalPortInUse),
    cause = cause,
) {
    override fun toString(): String = "${javaClass.name}(localPort=$localPort, failure=$failure)"
}

object OpenCodeFailureClassifier {
    fun classify(throwable: Throwable): OpenCodeFailure {
        val causes = throwable.causeChain()
        causes.firstOrNull { it is CancellationException || it is Error }?.let { throw it }

        causes.filterIsInstance<OpenCodeRequestException>().firstOrNull()?.let { return it.failure }
        if (causes.any { it is GoMobileBridgeUnavailableException }) {
            return OpenCodeFailure.OperationRejected(OpenCodeOperationRejectionReason.StcpComponentUnavailable)
        }
        if (causes.any { it is GoMobileBridgeApiMismatchException }) {
            return OpenCodeFailure.InvalidResponse
        }
        causes.filterIsInstance<SessionMessagesHttpException>().firstOrNull()?.let {
            return OpenCodeFailure.HttpStatus(it.statusCode)
        }
        causes.filterIsInstance<SseUnexpectedHttpStatusException>().firstOrNull()?.let {
            return OpenCodeFailure.HttpStatus(it.statusCode)
        }
        causes.filterIsInstance<HttpException>().firstOrNull()?.let {
            return OpenCodeFailure.HttpStatus(it.code())
        }
        if (causes.any { it is InboundPayloadTooLargeException }) {
            return OpenCodeFailure.ResponseTooLarge
        }
        if (
            causes.any {
                it is SocketTimeoutException ||
                    it is InterruptedIOException ||
                    it is OpenCodeHealthAttemptTimeoutException ||
                    it is FrpcStcpReadinessTimeoutException
            }
        ) {
            return OpenCodeFailure.Timeout
        }
        if (
            causes.any {
                it is SerializationException ||
                    it is EOFException ||
                    it is SseContentTypeException ||
                    it is SseContentEncodingException ||
                    it is RetrofitInboundResponsePolicyMissingException
            }
        ) {
            return OpenCodeFailure.InvalidResponse
        }
        if (
            causes.any {
                it is SSLException ||
                    it is SecurityException ||
                    it is SshHostKeyFingerprintFormatException ||
                    it is SshHostKeyMismatchException ||
                    it is SshHostKeyDiscoveryRequiredException ||
                    it is SshHostKeyPinRequiredException ||
                    it is SshHostKeyPinUnavailableException ||
                    it is SshHostKeyDiscoveryException ||
                    it is SshHostKeyVerificationException ||
                    it is SessionMessagesTransportUnavailableException ||
                    it is OpenCodeUnhealthyException
            }
        ) {
            return OpenCodeFailure.OperationRejected()
        }
        if (
            causes.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketException
            }
        ) {
            return OpenCodeFailure.NetworkUnavailable
        }
        if (causes.any { it is JSchException }) return OpenCodeFailure.OperationRejected()
        if (causes.any { it is IOException }) return OpenCodeFailure.NetworkUnavailable
        return OpenCodeFailure.Unknown
    }

    fun toRequestException(throwable: Throwable): OpenCodeRequestException {
        throwable.fatalCauseOrNull()?.let { throw it }
        if (throwable is OpenCodeRequestException) return throwable
        return OpenCodeRequestException(classify(throwable), throwable)
    }
}

internal fun Throwable.causeChain(): List<Throwable> {
    val causes = mutableListOf<Throwable>()
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        causes += current
        current = current.cause
    }
    return causes
}

internal fun Throwable.fatalCauseOrNull(): Throwable? = causeChain()
    .firstOrNull { it is CancellationException || it is Error }
