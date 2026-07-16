package io.github.ycfeng.ocdeck.core.network

import android.util.Log
import io.github.ycfeng.ocdeck.BuildConfig
import io.github.ycfeng.ocdeck.core.security.Redactor
import okhttp3.Interceptor
import okhttp3.Response

class RedactingInterceptor(
    private val redactor: Redactor,
    private val enableLogging: Boolean = BuildConfig.DEBUG,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (enableLogging) {
            Log.d(TAG, "${request.method} ${redactor.redactUrl(request.url)}")
        }

        val response = chain.proceed(request)
        if (enableLogging) {
            Log.d(TAG, "HTTP ${response.code} ${request.method} ${redactor.redactUrl(request.url)}")
        }
        return response
    }

    private companion object {
        const val TAG = "OpenCodeNetwork"
    }
}
