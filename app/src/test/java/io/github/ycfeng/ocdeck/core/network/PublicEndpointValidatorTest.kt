package io.github.ycfeng.ocdeck.core.network

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicEndpointValidatorTest {
    @Test
    fun acceptsPublicLiteralsAndStrictDnsNames() {
        assertTrue(PublicEndpointValidator.isValidPublicEndpointSyntax("8.8.8.8"))
        assertTrue(PublicEndpointValidator.isValidPublicEndpointSyntax("2001:4860:4860::8888"))
        assertTrue(PublicEndpointValidator.isValidPublicEndpointSyntax("frps.example.com"))

        listOf("example", "-bad.example", "bad_.example", "01.2.3.4", "2001:db8::1", "2001:::1").forEach {
            assertFalse(PublicEndpointValidator.isValidPublicEndpointSyntax(it))
        }
    }

    @Test
    fun requiresEveryResolvedAddressToBePublicAndAllowsNat64Results() {
        assertTrue(
            PublicEndpointValidator.areAllResolvedAddressesPublic(
                arrayOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("2001:4860:4860::8888")),
            ),
        )
        assertTrue(
            PublicEndpointValidator.areAllResolvedAddressesPublic(
                arrayOf(InetAddress.getByName("64:ff9b::808:808"), InetAddress.getByName("64:ff9b:1::808:808")),
            ),
        )
        assertFalse(
            PublicEndpointValidator.areAllResolvedAddressesPublic(
                arrayOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.1")),
            ),
        )
        assertFalse(PublicEndpointValidator.areAllResolvedAddressesPublic(emptyArray()))
    }
}
