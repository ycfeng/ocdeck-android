package io.github.ycfeng.ocdeck.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerBaseUrlTest {
    @Test
    fun normalizesHttpUrlWhileKeepingPath() {
        assertEquals(
            "https://example.com:8443/opencode",
            normalizeServerBaseUrl("  https://example.com:8443/opencode///  "),
        )
    }

    @Test
    fun rejectsUserInfoBeforeItCanBeDisplayedOrConnected() {
        assertFailure(ServerBaseUrlFailure.UserInfo, "https://alice:secret@example.com/opencode")
        assertFailure(ServerBaseUrlFailure.UserInfo, "https://alice%40team@example.com/opencode")
        assertNull(safeServerBaseUrlForDisplay("https://alice:secret@example.com"))
    }

    @Test
    fun rejectsQueriesAndFragmentsIncludingEmptyValues() {
        assertFailure(ServerBaseUrlFailure.Query, "https://example.com/opencode?")
        assertFailure(ServerBaseUrlFailure.Query, "https://example.com/opencode?token=secret")
        assertFailure(ServerBaseUrlFailure.Fragment, "https://example.com/opencode#")
        assertFailure(ServerBaseUrlFailure.Fragment, "https://example.com/opencode#secret")
    }

    @Test
    fun rejectsNonHttpAndHostlessUrls() {
        assertFailure(ServerBaseUrlFailure.Invalid, "ftp://example.com/opencode")
        assertFailure(ServerBaseUrlFailure.Invalid, "https:///opencode")
        assertFailure(ServerBaseUrlFailure.Invalid, "not a url")
    }

    @Test
    fun identifiesNonLoopbackHttpWithoutDnsResolution() {
        listOf(
            "http://example.com",
            "http://192.168.1.20:4096",
            "http://10.0.2.2:4096",
            "http://0.0.0.0:4096",
            "http://localhost.example:4096",
            "http://127.0.0.1.example:4096",
        ).forEach { value ->
            assertTrue(value, isNonLoopbackHttpServerBaseUrl(normalizeServerBaseUrl(value)))
        }
    }

    @Test
    fun exemptsHttpsAndSyntacticLoopbackHosts() {
        listOf(
            "https://example.com",
            "http://LOCALHOST.:4096",
            "http://api.localhost:4096",
            "http://127.0.0.1:4096",
            "http://127.42.0.9:4096",
            "http://[::1]:4096",
            "http://[0:0:0:0:0:0:0:1]:4096",
            "http://[::ffff:127.0.0.1]:4096",
        ).forEach { value ->
            assertFalse(value, isNonLoopbackHttpServerBaseUrl(normalizeServerBaseUrl(value)))
        }
    }

    private fun assertFailure(expected: ServerBaseUrlFailure, value: String) {
        val exception = assertThrows(ServerBaseUrlValidationException::class.java) {
            normalizeServerBaseUrl(value)
        }
        assertEquals(expected, exception.reason)
    }
}
