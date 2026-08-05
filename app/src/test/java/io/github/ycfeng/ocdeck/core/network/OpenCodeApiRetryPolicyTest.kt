package io.github.ycfeng.ocdeck.core.network

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Invocation

class OpenCodeApiRetryPolicyTest {
    @Test
    fun forkUsesNoRetryFactoryWhileOrdinaryRequestsUseDefaultFactory() {
        val defaultFactory = RecordingCallFactory()
        val noRetryFactory = RecordingCallFactory()
        val policyFactory = RetrofitRequestPolicyCallFactory(defaultFactory, noRetryFactory)

        policyFactory.newCall(requestFor("forkSession"))
        policyFactory.newCall(requestFor("getSession"))

        assertEquals(1, noRetryFactory.callCount)
        assertEquals(1, defaultFactory.callCount)
    }

    @Test
    fun replayProtectionMarksBodyOneShotWithoutChangingBytes() {
        val request = Request.Builder()
            .url("https://example.invalid/")
            .post("fork-body".toRequestBody())
            .build()

        val protected = request.preventReplay()
        val buffer = Buffer()
        protected.body?.writeTo(buffer)

        assertTrue(protected.body?.isOneShot() == true)
        assertEquals("fork-body", buffer.readUtf8())
    }

    private fun requestFor(methodName: String): Request {
        val method = OpenCodeApi::class.java.methods.single { it.name == methodName }
        val arguments = List(method.parameterTypes.size) { Any() }
        return Request.Builder()
            .url("https://example.invalid/")
            .tag(Invocation::class.java, Invocation.of(method, arguments))
            .build()
    }

    private class RecordingCallFactory : Call.Factory {
        private val delegate = OkHttpClient()
        var callCount: Int = 0
            private set

        override fun newCall(request: Request): Call {
            callCount += 1
            return delegate.newCall(request)
        }
    }
}
