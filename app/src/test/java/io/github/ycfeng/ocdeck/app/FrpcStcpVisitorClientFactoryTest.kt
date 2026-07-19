package io.github.ycfeng.ocdeck.app

import io.github.ycfeng.ocdeck.BuildConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileFrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FrpcStcpVisitorClientFactoryTest {
    @Test
    fun buildConfigSelectsBackendForCurrentVariant() {
        val expectedKotlinBackend = BuildConfig.BUILD_TYPE == "canary"
        assertEquals(expectedKotlinBackend, BuildConfig.USE_KOTLIN_FRPC_STCP_VISITOR)

        val expectedType = if (expectedKotlinBackend) {
            KotlinFrpcStcpVisitorClient::class.java
        } else {
            GoMobileFrpcStcpVisitorClient::class.java
        }
        assertBackendType(BuildConfig.USE_KOTLIN_FRPC_STCP_VISITOR, expectedType)
    }

    @Test
    fun explicitSelectorCreatesBothBackends() {
        assertBackendType(false, GoMobileFrpcStcpVisitorClient::class.java)
        assertBackendType(true, KotlinFrpcStcpVisitorClient::class.java)
    }

    private fun assertBackendType(
        useKotlinBackend: Boolean,
        expectedType: Class<out FrpcStcpVisitorClient>,
    ) {
        val client = createFrpcStcpVisitorClient(
            json = Json,
            useKotlinBackend = useKotlinBackend,
        )
        try {
            assertEquals(expectedType, client.javaClass)
        } finally {
            (client as? AutoCloseable)?.close()
        }
    }
}
