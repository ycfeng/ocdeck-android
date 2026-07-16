package io.github.ycfeng.ocdeck.core.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private val redactor = Redactor()

    @Test
    fun redactsComplexQuotedKeyValues() {
        val input =
            """{"api key" : "abc, def \"quoted\"", 'passphrase' = 'p a,ss \'word\'', "safe":"keep, me"}"""

        val result = redactor.redact(input)

        assertEquals(
            """{"api key" : "<redacted>", 'passphrase' = '<redacted>', "safe":"keep, me"}""",
            result,
        )
        assertFalse(result.contains("abc, def"))
        assertFalse(result.contains("p a,ss"))
    }

    @Test
    fun redactsAssignmentsEmbeddedInQuotedLogMessages() {
        val input = """message="request failed: token=embedded-secret, retry later""""

        val result = redactor.redact(input)

        assertEquals("""message="request failed: token=<redacted>, retry later"""", result)
        assertFalse(result.contains("embedded-secret"))
    }

    @Test
    fun redactsCompleteAndTruncatedPrivateKeyPemBlocks() {
        val complete =
            """
            prefix
            -----BEGIN OPENSSH PRIVATE KEY-----
            complete-line-one
            complete-line-two
            -----END OPENSSH PRIVATE KEY-----
            suffix
            """.trimIndent()
        val truncated =
            """
            prefix
            -----BEGIN RSA PRIVATE KEY-----
            truncated-line-one
            truncated-line-two
            suffix-that-must-also-be-redacted
            """.trimIndent()

        val completeResult = redactor.redact(complete)
        val truncatedResult = redactor.redact(truncated)

        assertEquals("prefix\n<redacted>\nsuffix", completeResult)
        assertEquals("prefix\n<redacted>", truncatedResult)
        assertFalse(completeResult.contains("complete-line-one"))
        assertFalse(truncatedResult.contains("suffix-that-must-also-be-redacted"))
    }

    @Test
    fun redactsSensitiveHeadersAndContinuationLines() {
        val input =
            "Authorization: Bearer authorization-secret\r\n" +
                " continuation-secret\r\n" +
                "\tcontinued: secret\r\n" +
                "X-Token-Count: 7\r\n" +
                "Proxy-Authorization: Basic proxy-secret\r\n" +
                "Set-Cookie: session=cookie-secret\r\n" +
                "X-Api-Key: api-secret\r\n" +
                "X-Auth-Token: auth-token-secret\r\n" +
                "Content-Type: text/plain\r\n" +
                "log-prefix Authorization: Bearer prefixed-secret\r\n" +
                "  Authorization: Bearer indented-secret\r\n" +
                "  Content-Type: application/json"

        val result = redactor.redact(input)

        assertEquals(
            "Authorization: <redacted>\r\n" +
                " <redacted>\r\n" +
                "\t<redacted>\r\n" +
                "X-Token-Count: 7\r\n" +
                "Proxy-Authorization: <redacted>\r\n" +
                "Set-Cookie: <redacted>\r\n" +
                "X-Api-Key: <redacted>\r\n" +
                "X-Auth-Token: <redacted>\r\n" +
                "Content-Type: text/plain\r\n" +
                "log-prefix Authorization: <redacted>\r\n" +
                "  Authorization: <redacted>\r\n" +
                "  Content-Type: application/json",
            result,
        )
        assertFalse(result.contains("authorization-secret"))
        assertFalse(result.contains("continuation-secret"))
        assertFalse(result.contains("cookie-secret"))
        assertFalse(result.contains("prefixed-secret"))
        assertFalse(result.contains("indented-secret"))
    }

    @Test
    fun redactsSensitiveQueryParametersIncludingEncodedNames() {
        val input =
            "GET /x?token=query,secret&name=open&api%5Fkey=encoded-secret&" +
                "passwordPolicy=strict&tokenCount=2 HTTP/1.1"

        val result = redactor.redact(input)

        assertEquals(
            "GET /x?token=<redacted>&name=open&api%5Fkey=<redacted>&" +
                "passwordPolicy=strict&tokenCount=2 HTTP/1.1",
            result,
        )
        assertEquals(result, redactor.redact(result))
    }

    @Test
    fun doesNotTrustRedactionMarkerWhenSecretDataFollowsIt() {
        val input =
            "password=<redacted>assignment-secret " +
                "GET /x?token=<redacted>query-secret&safe=value"

        val result = redactor.redact(input)

        assertEquals(
            "password=<redacted> GET /x?token=<redacted>&safe=value",
            result,
        )
        assertFalse(result.contains("assignment-secret"))
        assertFalse(result.contains("query-secret"))
        assertEquals(result, redactor.redact(result))
    }

    @Test
    fun redactsSshAndStcpSecretAssignments() {
        val input =
            "sshPassword=ssh-secret authToken=frps-token secretKey=stcp-secret " +
                "privateKey=BEGIN_OPENSSH_PRIVATE_KEY passphrase=key-passphrase " +
                "hostFingerprint=SHA256:fingerprint"

        val result = redactor.redact(input)

        assertEquals(
            "sshPassword=<redacted> authToken=<redacted> secretKey=<redacted> " +
                "privateKey=<redacted> passphrase=<redacted> hostFingerprint=<redacted>",
            result,
        )
        assertFalse(result.contains("ssh-secret"))
        assertFalse(result.contains("frps-token"))
        assertFalse(result.contains("fingerprint"))
    }

    @Test
    fun doesNotOverRedactRelatedNonSecretKeys() {
        val input =
            "tokenCount=12 passwordPolicy=\"strict mode\" identity=alice " +
                "identity_provider=oidc tokenizer=model apiKeyId=public-id " +
                "privateKeyPath=/tmp/key secretName=label"

        assertEquals(input, redactor.redact(input))
    }

    @Test
    fun redactsNestedConfigWhilePreservingSafeStructure() {
        val input = parse(
            """
            {
              "theme": "dark",
              "identity": "developer",
              "passwordPolicy": "strict",
              "tokenCount": 3,
              "provider": {
                "openai": {
                  "options": {
                    "apiKey": "provider-secret",
                    "headers": {
                      "X-Custom": "custom-header-secret",
                      "Accept": "application/json"
                    },
                    "nested": [
                      {"accessToken": "array-token"},
                      {"safe": "value"}
                    ]
                  },
                  "auth": {
                    "type": "api",
                    "key": "provider-auth-secret",
                    "nested": {"identity": "auth-identity"},
                    "nullable": null
                  }
                }
              },
              "env": {
                "SAFE_NAME": "environment-secret",
                "NESTED": {"NUMBER": 7, "NULL": null}
              }
            }
            """.trimIndent(),
        )
        val expected = parse(
            """
            {
              "theme": "dark",
              "identity": "developer",
              "passwordPolicy": "strict",
              "tokenCount": 3,
              "provider": {
                "openai": {
                  "options": {
                    "apiKey": "<redacted>",
                    "headers": {
                      "X-Custom": "<redacted>",
                      "Accept": "<redacted>"
                    },
                    "nested": [
                      {"accessToken": "<redacted>"},
                      {"safe": "value"}
                    ]
                  },
                  "auth": {
                    "type": "<redacted>",
                    "key": "<redacted>",
                    "nested": {"identity": "<redacted>"},
                    "nullable": null
                  }
                }
              },
              "env": {
                "SAFE_NAME": "<redacted>",
                "NESTED": {"NUMBER": "<redacted>", "NULL": null}
              }
            }
            """.trimIndent(),
        )

        val result = redactor.redact(input, RedactionScope.Config)

        assertEquals(expected, result)
        assertEquals(result, redactor.redact(result, RedactionScope.Config))
        assertEquals(result, parse(result.toString()))
        assertFalse(result.toString().contains("provider-secret"))
        assertFalse(result.toString().contains("environment-secret"))
    }

    @Test
    fun sensitiveContainersPreserveShapeAndRedactEveryNonNullLeaf() {
        val providerAuth = parse(
            """{"type":"oauth","tokens":["one",null,{"value":"two"}],"enabled":true}""",
        )
        val expectedProviderAuth = parse(
            """{"type":"<redacted>","tokens":["<redacted>",null,{"value":"<redacted>"}],"enabled":"<redacted>"}""",
        )
        val ordinaryHeaders = parse(
            """{"Authorization":"Bearer secret","Content-Type":"application/json","X-Token-Count":"4"}""",
        )
        val expectedOrdinaryHeaders = parse(
            """{"Authorization":"<redacted>","Content-Type":"application/json","X-Token-Count":"4"}""",
        )
        val expectedCustomHeaders = parse(
            """{"Authorization":"<redacted>","Content-Type":"<redacted>","X-Token-Count":"<redacted>"}""",
        )

        assertEquals(expectedProviderAuth, redactor.redact(providerAuth, RedactionScope.ProviderAuth))
        assertEquals(expectedOrdinaryHeaders, redactor.redact(ordinaryHeaders, RedactionScope.Headers))
        assertEquals(expectedCustomHeaders, redactor.redact(ordinaryHeaders, RedactionScope.CustomHeaders))
    }

    @Test
    fun directSecretKeysReplaceObjectArrayAndNullValues() {
        val input = parse(
            """
            {
              "password": {"raw": "object-secret"},
              "privateKey": ["line-one", {"line": "line-two"}],
              "apikey": "compact-secret",
              "api_key_value": "described-secret",
              "token": null,
              "safe": {"tokenCount": 2, "passwordPolicy": "strict", "identity": "me"}
            }
            """.trimIndent(),
        )
        val expected = parse(
            """
            {
              "password": "<redacted>",
              "privateKey": "<redacted>",
              "apikey": "<redacted>",
              "api_key_value": "<redacted>",
              "token": "<redacted>",
              "safe": {"tokenCount": 2, "passwordPolicy": "strict", "identity": "me"}
            }
            """.trimIndent(),
        )

        val result = redactor.redact(input)

        assertEquals(expected, result)
        assertEquals(result, parse(result.toString()))
        assertFalse(result.toString().contains("object-secret"))
        assertFalse(result.toString().contains("line-two"))
        assertFalse(result.toString().contains("compact-secret"))
        assertFalse(result.toString().contains("described-secret"))
    }

    @Test
    fun redactsHttpUrlUserInfoAndKeepsRepeatedQueryOrder() {
        val input =
            "https://alice:p%40ss@example.com:8443/a/b?token=first&safe=ok&" +
                "api_key=second&tokenCount=3&token=third#fragment"
        val url = input.toHttpUrl()

        val result = redactor.redactUrl(url)

        assertEquals("https", result.scheme)
        assertEquals(Redactor.REDACTED, result.username)
        assertEquals(Redactor.REDACTED, result.password)
        assertEquals("example.com", result.host)
        assertEquals(8443, result.port)
        assertEquals("/a/b", result.encodedPath)
        assertEquals(Redactor.REDACTED, result.fragment)
        assertEquals(5, result.querySize)
        assertEquals("token", result.queryParameterName(0))
        assertEquals(Redactor.REDACTED, result.queryParameterValue(0))
        assertEquals("safe", result.queryParameterName(1))
        assertEquals("ok", result.queryParameterValue(1))
        assertEquals("api_key", result.queryParameterName(2))
        assertEquals(Redactor.REDACTED, result.queryParameterValue(2))
        assertEquals("tokenCount", result.queryParameterName(3))
        assertEquals("3", result.queryParameterValue(3))
        assertEquals("token", result.queryParameterName(4))
        assertEquals(Redactor.REDACTED, result.queryParameterValue(4))
        assertEquals(result, redactor.redactUrl(result))
        assertFalse(result.toString().contains("p%40ss"))
        assertFalse(result.toString().contains("first"))
        assertFalse(result.toString().contains("second"))
        assertFalse(result.toString().contains("third"))
        assertFalse(result.toString().contains("alice"))
        assertFalse(result.toString().contains("fragment"))
    }

    @Test
    fun redactsBareAuthenticationSchemesAndCredentialsInFreeTextUrls() {
        val input =
            "request failed with Bearer standalone-secret at " +
                "https://alice:p%40ss@example.com/path?token=query-secret#fragment-secret."

        val result = redactor.redact(input)

        assertTrue(result.contains("Bearer <redacted>"))
        assertFalse(result.contains("standalone-secret"))
        assertFalse(result.contains("alice"))
        assertFalse(result.contains("p%40ss"))
        assertFalse(result.contains("query-secret"))
        assertFalse(result.contains("fragment-secret"))
        assertEquals(result, redactor.redact(result))
    }

    @Test
    fun redactsOkHttpHeadersByScopeAndPreservesOrder() {
        val headers = Headers.Builder()
            .add("Authorization", "Bearer authorization-secret")
            .add("Content-Type", "application/json")
            .add("Set-Cookie", "a=cookie-one")
            .add("Set-Cookie", "b=cookie-two")
            .add("X-Token-Count", "9")
            .build()

        val ordinary = redactor.redactHeaders(headers)
        val custom = redactor.redactHeaders(headers, RedactionScope.CustomHeaders)

        assertEquals(Redactor.REDACTED, ordinary.value(0))
        assertEquals("application/json", ordinary.value(1))
        assertEquals(Redactor.REDACTED, ordinary.value(2))
        assertEquals(Redactor.REDACTED, ordinary.value(3))
        assertEquals("9", ordinary.value(4))
        repeat(custom.size) { assertEquals(Redactor.REDACTED, custom.value(it)) }
        assertEquals(ordinary, redactor.redactHeaders(ordinary))
        assertFalse(ordinary.toString().contains("authorization-secret"))
        assertFalse(ordinary.toString().contains("cookie-one"))
    }

    @Test
    fun jsonDepthLimitFailsClosed() {
        var nested: JsonElement = JsonPrimitive("deep-secret")
        repeat(4) { index ->
            nested = JsonObject(mapOf("level$index" to nested))
        }
        val input = JsonObject(
            mapOf(
                "safe" to JsonPrimitive("root-value"),
                "nested" to nested,
            ),
        )
        val depthLimitedRedactor = Redactor(maxJsonDepth = 2)

        val result = depthLimitedRedactor.redact(input)

        assertEquals("root-value", (result as JsonObject)["safe"].toString().trim('"'))
        assertTrue(result.toString().contains(Redactor.REDACTED))
        assertFalse(result.toString().contains("deep-secret"))
        assertEquals(result, parse(result.toString()))
        assertEquals(result, depthLimitedRedactor.redact(result))
    }

    private fun parse(value: String): JsonElement = Json.parseToJsonElement(value)
}
