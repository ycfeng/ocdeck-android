package io.github.ycfeng.ocdeck.core.network

import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeConnectionClientTest {
    @Test
    fun closeReleasesOwnedClientsOnlyOnce() {
        var closeCalls = 0
        val api = Proxy.newProxyInstance(
            OpenCodeApi::class.java.classLoader,
            arrayOf(OpenCodeApi::class.java),
        ) { _, _, _ -> error("API must not be invoked") } as OpenCodeApi
        val client = OpenCodeConnectionClient(
            api = api,
            providerOAuthApi = api,
            sessionMessagesTransport = SessionMessagesTransport.Unavailable,
            closeAction = { closeCalls += 1 },
        )

        client.close()
        client.close()

        assertEquals(1, closeCalls)
    }
}
