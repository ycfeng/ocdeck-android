package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

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

    internal fun createConnectionClient(
        server: ServerConfig,
        password: String?,
        baseUrl: String = server.baseUrl,
        timeouts: OpenCodeHttpTimeouts = OpenCodeHttpTimeouts.Default,
    ): OpenCodeConnectionClient {
        val client = createHttpClient(server, password, timeouts)
        val providerOAuthClient = createHttpClient(server, password, OpenCodeHttpTimeouts.ProviderOAuth)
        return OpenCodeConnectionClient(
            api = createApi(baseUrl, client),
            providerOAuthApi = createApi(baseUrl, providerOAuthClient),
            sessionMessagesTransport = OkHttpSessionMessagesTransport(
                baseUrl = baseUrl,
                callFactory = client,
                json = json,
            ),
        )
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
        .apply {
            timeouts.callTimeoutMillis?.let { callTimeout(it, TimeUnit.MILLISECONDS) }
        }
        .build()

    private fun createApi(baseUrl: String, client: OkHttpClient): OpenCodeApi = Retrofit.Builder()
        .baseUrl(baseUrl.asRetrofitBaseUrl())
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OpenCodeApi::class.java)
}

internal data class OpenCodeConnectionClient(
    val api: OpenCodeApi,
    val providerOAuthApi: OpenCodeApi,
    val sessionMessagesTransport: SessionMessagesTransport,
) {
    override fun toString(): String =
        "OpenCodeConnectionClient(apiPresent=true, providerOAuthApiPresent=true, " +
            "sessionMessagesTransportPresent=true)"
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
