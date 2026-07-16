package io.github.ycfeng.ocdeck.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class FrpcStcpVisitorUrlResolverTest {
    @Test
    fun rewritesBaseUrlToLocalBindPort() {
        val effective = FrpcStcpVisitorUrlResolver.effectiveBaseUrl("http://opencode.example.test:4096", 5096)

        assertEquals("http://127.0.0.1:5096", effective)
    }

    @Test
    fun preservesSchemeAndBasePathWhenRewritingBaseUrl() {
        val effective = FrpcStcpVisitorUrlResolver.effectiveBaseUrl("https://opencode.example.test:8443/opencode/", 4096)

        assertEquals("https://127.0.0.1:4096/opencode", effective)
    }
}
