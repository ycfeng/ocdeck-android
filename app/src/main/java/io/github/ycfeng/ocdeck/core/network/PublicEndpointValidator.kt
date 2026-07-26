package io.github.ycfeng.ocdeck.core.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal object PublicEndpointValidator {
    fun isValidPublicEndpointSyntax(value: String): Boolean {
        if (value.isBlank() || value != value.trim()) return false
        return when {
            IPV4_LITERAL.matches(value) -> parseIpv4(value)?.let(::isPublicAddress) == true
            ':' in value && '%' !in value -> parseNumeric(value)?.let(::isPublicAddress) == true
            else -> SAFE_PUBLIC_DNS_NAME.matches(value)
        }
    }

    fun areAllResolvedAddressesPublic(addresses: Array<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all { isPublicAddress(it, allowNat64 = true) }

    private fun parseIpv4(value: String): Inet4Address? {
        val octets = value.split('.').map { part ->
            if (part.length > 1 && part.startsWith('0')) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()) as Inet4Address
    }

    private fun parseNumeric(value: String): InetAddress? = try {
        InetAddress.getByName(value).takeIf { it is Inet6Address }
    } catch (_: Exception) {
        null
    }

    private fun isPublicAddress(address: InetAddress, allowNat64: Boolean = false): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        return when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address, allowNat64)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return when {
            first == 0 || first == 10 || first == 127 || first >= 224 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 && third in setOf(0, 2) -> false
            first == 192 && second == 88 && third == 99 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray, allowNat64: Boolean): Boolean {
        if (allowNat64 && (bytes.hasPrefix(NAT64_WELL_KNOWN_PREFIX) || bytes.hasPrefix(NAT64_LOCAL_USE_PREFIX))) {
            return true
        }
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        if (first !in 0x20..0x3f) return false
        if (first == 0x20 && second == 0x01 && third in 0x00..0x01) return false
        if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) return false
        if (first == 0x20 && second == 0x02) return false
        if (first == 0x3f && second == 0xff && third and 0xf0 == 0) return false
        return true
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val IPV4_LITERAL = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")
    private val SAFE_PUBLIC_DNS_NAME = Regex(
        "(?=.{1,253}$)(?=.*\\.)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+" +
            "[A-Za-z](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?",
    )
    private val NAT64_WELL_KNOWN_PREFIX = byteArrayOf(0x00, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
    private val NAT64_LOCAL_USE_PREFIX = byteArrayOf(0x00, 0x64, 0xff.toByte(), 0x9b.toByte(), 0x00, 0x01)
}
