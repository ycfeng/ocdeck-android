package io.github.ycfeng.ocdeck.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SshTunnelUrlResolverTest {
    @Test
    fun extractsRemoteEndpointFromBaseUrl() {
        val endpoint = SshTunnelUrlResolver.remoteEndpoint("http://127.0.0.1:4096")

        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(4096, endpoint.port)
    }

    @Test
    fun rewritesBaseUrlToLocalForwardPort() {
        val effective = SshTunnelUrlResolver.effectiveBaseUrl("http://opencode.example.test:4096", 5096)

        assertEquals("http://127.0.0.1:5096", effective)
    }

    @Test
    fun preservesBasePathWhenRewritingBaseUrl() {
        val effective = SshTunnelUrlResolver.effectiveBaseUrl("http://opencode.example.test:4096/opencode", 4096)

        assertEquals("http://127.0.0.1:4096/opencode", effective)
    }
}
