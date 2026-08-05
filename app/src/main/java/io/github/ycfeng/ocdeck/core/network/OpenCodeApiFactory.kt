package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.Invocation
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenCodeApiFactory(
    private val json: Json,
    private val redactor: Redactor,
) {
    fun create(
        server: ServerConfig,
        password: String?,
        baseUrl: String = server.baseUrl,
        timeouts: OpenCodeHttpTimeouts = OpenCodeHttpTimeouts.Default,
    ): OpenCodeApi = createApi(
        baseUrl = baseUrl,
        client = createHttpClient(server, password, timeouts),
    )

    internal fun createSingleApiClient(
        server: ServerConfig,
        password: String?,
        baseUrl: String = server.baseUrl,
        timeouts: OpenCodeHttpTimeouts = OpenCodeHttpTimeouts.Default,
    ): OpenCodeSingleApiClient {
        val client = createHttpClient(server, password, timeouts)
        return try {
            OpenCodeSingleApiClient(
                api = createApi(baseUrl, client),
                closeAction = { closeHttpClients(client) },
            )
        } catch (exception: Exception) {
            closeHttpClientsAfterFailure(exception, client)
            throw exception
        }
    }

    internal fun createConnectionClient(
        server: ServerConfig,
        password: String?,
        baseUrl: String = server.baseUrl,
        timeouts: OpenCodeHttpTimeouts = OpenCodeHttpTimeouts.Default,
    ): OpenCodeConnectionClient {
        val client = createHttpClient(server, password, timeouts)
        val providerOAuthClient = try {
            createHttpClient(server, password, OpenCodeHttpTimeouts.ProviderOAuth)
        } catch (exception: Exception) {
            closeHttpClientsAfterFailure(exception, client)
            throw exception
        }
        return try {
            OpenCodeConnectionClient(
                api = createApi(baseUrl, client),
                providerOAuthApi = createApi(baseUrl, providerOAuthClient),
                sessionMessagesTransport = OkHttpSessionMessagesTransport(
                    baseUrl = baseUrl,
                    callFactory = client,
                    json = json,
                ),
                closeAction = {
                    closeHttpClients(client, providerOAuthClient)
                },
            )
        } catch (exception: Exception) {
            closeHttpClientsAfterFailure(exception, client, providerOAuthClient)
            throw exception
        }
    }

    private fun createHttpClient(
        server: ServerConfig,
        password: String?,
        timeouts: OpenCodeHttpTimeouts,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeouts.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.writeTimeoutMillis, TimeUnit.MILLISECONDS)
        .addInterceptor(RetrofitInboundResponsePolicyInterceptor())
        .addInterceptor(AuthInterceptor(server.username, password))
        .addInterceptor(RedactingInterceptor(redactor))
        .addNetworkInterceptor(EncodedResponseLimitInterceptor())
        .apply {
            timeouts.callTimeoutMillis?.let { callTimeout(it, TimeUnit.MILLISECONDS) }
        }
        .build()

    private fun createApi(baseUrl: String, client: OkHttpClient): OpenCodeApi {
        val noRetryClient = client.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(PreventRequestReplayInterceptor())
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl.asRetrofitBaseUrl())
            .callFactory(RetrofitRequestPolicyCallFactory(client, noRetryClient))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenCodeApi::class.java)
    }
}

internal class RetrofitRequestPolicyCallFactory(
    private val defaultFactory: Call.Factory,
    private val noRetryFactory: Call.Factory,
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val method = request.tag(Invocation::class.java)?.method()
        val factory = if (method?.isAnnotationPresent(DisableAutomaticRetry::class.java) == true) {
            noRetryFactory
        } else {
            defaultFactory
        }
        return factory.newCall(request)
    }
}

internal class PreventRequestReplayInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request().preventReplay())
}

internal fun Request.preventReplay(): Request {
    val currentBody = body ?: return this
    if (currentBody.isOneShot()) return this
    return newBuilder()
        .method(method, OneShotRequestBody(currentBody))
        .build()
}

private class OneShotRequestBody(
    private val delegate: RequestBody,
) : RequestBody() {
    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}

internal class OpenCodeConnectionClient(
    val api: OpenCodeApi,
    val providerOAuthApi: OpenCodeApi,
    val sessionMessagesTransport: SessionMessagesTransport,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }

    override fun toString(): String =
        "OpenCodeConnectionClient(apiPresent=true, providerOAuthApiPresent=true, " +
            "sessionMessagesTransportPresent=true)"
}

internal class OpenCodeSingleApiClient(
    val api: OpenCodeApi,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }

    override fun toString(): String = "OpenCodeSingleApiClient(apiPresent=true)"
}

private fun closeHttpClients(vararg clients: OkHttpClient) {
    var firstFailure: Exception? = null
    clients.forEach { client ->
        listOf<() -> Unit>(
            client.dispatcher::cancelAll,
            client.connectionPool::evictAll,
            client.dispatcher.executorService::shutdown,
        ).forEach { cleanup ->
            try {
                cleanup()
            } catch (exception: Exception) {
                if (firstFailure == null) {
                    firstFailure = exception
                } else {
                    firstFailure.addSuppressed(exception)
                }
            }
        }
    }
    firstFailure?.let { throw it }
}

private fun closeHttpClientsAfterFailure(failure: Exception, vararg clients: OkHttpClient) {
    try {
        closeHttpClients(*clients)
    } catch (cleanupFailure: Exception) {
        failure.addSuppressed(cleanupFailure)
    }
}

data class OpenCodeHttpTimeouts(
    val connectTimeoutMillis: Long,
    val readTimeoutMillis: Long,
    val writeTimeoutMillis: Long,
    val callTimeoutMillis: Long?,
) {
    companion object {
        val Default = OpenCodeHttpTimeouts(
            connectTimeoutMillis = 10_000,
            readTimeoutMillis = 30_000,
            writeTimeoutMillis = 30_000,
            callTimeoutMillis = null,
        )
        val Readiness = OpenCodeHttpTimeouts(
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 2_500,
            writeTimeoutMillis = 2_500,
            callTimeoutMillis = 2_500,
        )
        val ProviderOAuth = OpenCodeHttpTimeouts(
            connectTimeoutMillis = 10_000,
            readTimeoutMillis = 10 * 60_000L,
            writeTimeoutMillis = 30_000,
            callTimeoutMillis = 10 * 60_000L,
        )
    }
}

private fun String.asRetrofitBaseUrl(): String = trim().trimEnd('/') + "/"
