package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpcAndroidInteropHostTest {
    @Test
    fun compatSuiteContainsOnlyTheFrozenV1PlainProfile() {
        val profiles = androidInteropProfiles("compat")

        assertEquals(1, profiles.size)
        assertEquals("success-v1-00", profiles.single().caseId)
        assertEquals("success", profiles.single().scenario.kind)
        assertEquals("v1", profiles.single().wireProtocol)
        assertFalse(profiles.single().useEncryption)
        assertFalse(profiles.single().useCompression)
        assertTrue(profiles.single().usesConcurrentTraffic)
        assertFalse(profiles.single().isRestart)
    }

    @Test
    fun fullSuiteContainsTheFrozenProfilesInOrder() {
        val profiles = androidInteropProfiles("full")

        assertEquals(12, profiles.size)
        assertEquals(
            listOf(
                "success-v1-00|success|v1|false|false",
                "success-v1-01|success|v1|false|true",
                "success-v1-10|success|v1|true|false",
                "success-v1-11|success|v1|true|true",
                "success-v2-00|success|v2|false|false",
                "success-v2-01|success|v2|false|true",
                "success-v2-10|success|v2|true|false",
                "success-v2-11|success|v2|true|true",
                "negative-wrong-token-v2-00|wrong_token|v2|false|false",
                "negative-wrong-secret-v2-00|wrong_secret|v2|false|false",
                "negative-bind-conflict-v2-00|bind_conflict|v2|false|false",
                "restart-v2-11|restart|v2|true|true",
            ),
            profiles.map { profile ->
                "${profile.caseId}|${profile.scenario.kind}|${profile.wireProtocol}|" +
                    "${profile.useEncryption}|${profile.useCompression}"
            },
        )
        assertFalse(profiles[8].usesConcurrentTraffic)
        assertFalse(profiles[9].usesConcurrentTraffic)
        assertFalse(profiles[10].usesConcurrentTraffic)
        assertTrue(profiles.last().usesConcurrentTraffic)
        assertTrue(profiles.last().isRestart)
        assertEquals(
            listOf(
                "success", "success", "success", "success",
                "success", "success", "success", "success",
                "control_failure", "stcp_secret_rejected", "bind_conflict", "recovered",
            ),
            profiles.map { it.expectedSemanticOutcome },
        )
    }

    @Test
    fun suitesRejectMissingAliasesAndUnexpectedValues() {
        listOf("", "Compat", "compat ", "matrix").forEach { suite ->
            assertThrows(InteropFailure::class.java) { androidInteropProfiles(suite) }
        }
    }

    @Test
    fun androidRuntimePageSizeParserAcceptsGetconfOutputAndRejectsInvalidValues() {
        assertEquals(4_096, parseAndroidRuntimePageSize("4096\n"))
        assertEquals(16_384, parseAndroidRuntimePageSize(" 16384 \r\n"))
        listOf("", "0", "-4096", "16384\nunexpected", "unavailable").forEach { output ->
            assertThrows(InteropFailure::class.java) { parseAndroidRuntimePageSize(output) }
        }
    }

    @Test
    fun androidRuntimePageSizeUsesLegacyProbeOnlyWhenGetconfIsMissing() {
        var legacyProbeCalls = 0
        assertEquals(
            16_384,
            resolveAndroidRuntimePageSize(AdbCommandResult(0, "16384\n", "")) {
                legacyProbeCalls += 1
                "4096\n"
            },
        )
        assertEquals(0, legacyProbeCalls)

        assertEquals(
            4_096,
            resolveAndroidRuntimePageSize(AdbCommandResult(127, "", "getconf: not found")) {
                legacyProbeCalls += 1
                "4096\n"
            },
        )
        assertEquals(1, legacyProbeCalls)

        assertThrows(InteropFailure::class.java) {
            resolveAndroidRuntimePageSize(AdbCommandResult(1, "", "device unavailable")) {
                legacyProbeCalls += 1
                "4096\n"
            }
        }
        assertEquals(1, legacyProbeCalls)
    }

    @Test
    fun profilesExposeTheExactScenarioChecks() {
        val expected = mapOf(
            "success" to listOf(
                "open_state",
                "health",
                "global_sse",
                "project_sse",
                "concurrent_rest_sse",
                "closed",
                "port_released",
            ),
            "wrong_token" to listOf(
                "control_rejected",
                "session_failed",
                "listener_not_bound",
                "closed",
                "port_released",
            ),
            "wrong_secret" to listOf(
                "open_state",
                "health_rejected",
                "listener_consistent",
                "closed",
                "port_released",
            ),
            "bind_conflict" to listOf(
                "bind_rejected",
                "visitor_failed",
                "listener_not_bound",
                "closed",
                "port_released",
            ),
            "restart" to listOf(
                "initial_open_state",
                "initial_health",
                "initial_global_sse",
                "initial_project_sse",
                "sse_disconnected",
                "control_unavailable",
                "control_epoch_advanced",
                "recovered_open_state",
                "health",
                "global_sse",
                "project_sse",
                "concurrent_rest_sse",
                "closed",
                "port_released",
            ),
        )

        androidInteropProfiles("full").forEach { profile ->
            assertEquals(expected.getValue(profile.scenario.kind), profile.expectedChecks)
        }
    }

    @Test
    fun resultValidationRejectsStructuralEchoDrift() {
        val profile = androidInteropProfiles("full")[4]
        val result = passedResult(profile, "gomobile")
        val expectation = AndroidInteropResultExpectation(
            runId = RUN_ID,
            backend = "gomobile",
            profile = profile,
        )

        validateAndroidInteropResult(result, expectation)
        listOf(
            result.copy(caseId = "success-v2-01"),
            result.copy(scenario = AndroidInteropScenario("restart")),
            result.copy(wireProtocol = "v1"),
            result.copy(useEncryption = true),
            result.copy(useCompression = true),
            result.copy(backend = "kotlin"),
            result.copy(semanticOutcome = "recovered"),
        ).forEach { drifted ->
            assertThrows(InteropFailure::class.java) {
                validateAndroidInteropResult(drifted, expectation)
            }
        }
    }

    @Test
    fun resultValidationRequiresPassedStatusExactChecksAndExpectedPort() {
        val profile = androidInteropProfiles("compat").single()
        val result = passedResult(profile, "kotlin")
        val expectation = AndroidInteropResultExpectation(
            runId = RUN_ID,
            backend = "kotlin",
            profile = profile,
            expectedBindPort = BIND_PORT,
        )

        validateAndroidInteropResult(result, expectation)
        listOf(
            result.copy(status = "failed", failedStage = "tunnel_health", failureCode = "TUNNEL_PROBE"),
            result.copy(checks = result.checks.dropLast(1)),
            result.copy(checks = result.checks.reversed()),
            result.copy(bindPort = BIND_PORT + 1),
            result.copy(semanticOutcome = null),
        ).forEach { invalid ->
            assertThrows(InteropFailure::class.java) {
                validateAndroidInteropResult(invalid, expectation)
            }
        }
    }

    @Test
    fun backendEquivalenceRequiresGoMobileThenKotlinWithTheSameSemanticsAndPort() {
        val profile = androidInteropProfiles("full").last()
        val goMobile = passedResult(profile, "gomobile")
        val kotlin = passedResult(profile, "kotlin")

        requireEquivalentAndroidInteropResults(goMobile, kotlin)
        assertThrows(InteropFailure::class.java) {
            requireEquivalentAndroidInteropResults(goMobile, kotlin.copy(bindPort = BIND_PORT + 1))
        }
        assertThrows(InteropFailure::class.java) {
            requireEquivalentAndroidInteropResults(goMobile, kotlin.copy(checks = kotlin.checks.dropLast(1)))
        }
        assertThrows(InteropFailure::class.java) {
            requireEquivalentAndroidInteropResults(goMobile, kotlin.copy(semanticOutcome = "success"))
        }
        assertThrows(InteropFailure::class.java) {
            requireEquivalentAndroidInteropResults(goMobile.copy(backend = "kotlin"), kotlin)
        }
    }

    @Test
    fun suiteSummaryContainsTheExactCompletedProfilesWithoutRuntimeIdentifiers() {
        val profiles = androidInteropProfiles("compat")
        val summary = buildAndroidInteropSuiteSummary(
            suite = "compat",
            apiLevel = 26,
            abi = "x86_64",
            pageSize = 4_096,
            completedProfiles = profiles.map { profile ->
                AndroidInteropCompletedProfile(
                    profile = profile,
                    goMobile = passedResult(profile, "gomobile"),
                    kotlin = passedResult(profile, "kotlin"),
                )
            },
        )

        assertEquals(1, summary.schemaVersion)
        assertEquals("compat", summary.suite)
        assertEquals(AndroidInteropSummaryDevice(26, "x86_64", 4_096), summary.device)
        assertEquals(listOf("gomobile", "kotlin"), summary.backendOrder)
        assertFalse(summary.authorizesKotlinDefault)
        assertEquals(listOf("success-v1-00"), summary.profiles.map { it.caseId })
        assertEquals(
            listOf("gomobile", "kotlin"),
            summary.profiles.single().backends.map { it.backend },
        )
        assertEquals(
            listOf("success", "success"),
            summary.profiles.single().backends.map { it.semanticOutcome },
        )
        assertTrue(summary.profiles.single().equivalent)

        assertThrows(InteropFailure::class.java) {
            buildAndroidInteropSuiteSummary("full", 36, "x86_64", 4_096, emptyList())
        }
    }

    @Test
    fun suiteSummaryWriterPublishesOnceWithoutFollowingAnExistingDestination() {
        val directory = Files.createTempDirectory("frpc-android-summary-test-")
        try {
            val profile = androidInteropProfiles("compat").single()
            val summary = buildAndroidInteropSuiteSummary(
                suite = "compat",
                apiLevel = 26,
                abi = "x86_64",
                pageSize = 4_096,
                completedProfiles = listOf(
                    AndroidInteropCompletedProfile(
                        profile,
                        passedResult(profile, "gomobile"),
                        passedResult(profile, "kotlin"),
                    ),
                ),
            )
            val output = directory.resolve("summary.json")

            writeAndroidInteropSuiteSummary(output, summary)

            assertEquals(
                summary,
                kotlinx.serialization.json.Json.decodeFromString<AndroidInteropSuiteSummary>(
                    String(Files.readAllBytes(output), StandardCharsets.UTF_8),
                ),
            )
            assertThrows(InteropFailure::class.java) {
                writeAndroidInteropSuiteSummary(output, summary)
            }
        } finally {
            deleteTreeBestEffort(directory)
        }
    }

    @Test
    fun restartMarkerPollingIgnoresTransientOutputBeforeAcceptingTheMatchingMarker() {
        val expected = restartMarker()
        val reads = listOf(
            AdbCommandResult(0, "marker is not published yet", ""),
            AdbCommandResult(0, Json.encodeToString(expected), ""),
        )
        var readIndex = 0
        var completionObserved = false

        awaitAndroidInteropRestartMarker(
            expected = expected,
            readMarker = { reads[readIndex++] },
            instrumentationIsDone = { false },
            observeInstrumentation = { completionObserved = true },
            timeoutMillis = 1_000L,
            pollMillis = 1L,
            sleep = {},
        )

        assertEquals(2, readIndex)
        assertFalse(completionObserved)
    }

    @Test
    fun restartMarkerPollingRejectsMatchingSchemaFromAnotherInvocation() {
        val expected = restartMarker()
        val actual = expected.copy(step = "device_disconnected")

        assertThrows(InteropFailure::class.java) {
            awaitAndroidInteropRestartMarker(
                expected = expected,
                readMarker = { AdbCommandResult(0, Json.encodeToString(actual), "") },
                instrumentationIsDone = { false },
                observeInstrumentation = {},
                timeoutMillis = 1_000L,
                pollMillis = 1L,
                sleep = {},
            )
        }
    }

    @Test
    fun restartMarkerPollingFailsClosedWhenInstrumentationCompletesFirst() {
        val expected = restartMarker()
        var completionObserved = false

        assertThrows(InteropFailure::class.java) {
            awaitAndroidInteropRestartMarker(
                expected = expected,
                readMarker = { AdbCommandResult(0, "", "marker unavailable") },
                instrumentationIsDone = { true },
                observeInstrumentation = { completionObserved = true },
                timeoutMillis = 1_000L,
                pollMillis = 1L,
                sleep = {},
            )
        }
        assertTrue(completionObserved)
    }

    @Test
    fun reverseRecoveryReturnsOnlyTheMappingAddedByThisRun() {
        val output = """
            device-serial tcp:41001 tcp:7000
            device-serial tcp:41002 tcp:7000
            device-serial tcp:41003 tcp:8000
        """.trimIndent()

        assertEquals(41_002, recoverOwnedReversePort(output, 7_000, setOf(41_001)))
        assertNull(recoverOwnedReversePort(output, 8_000, setOf(41_003)))
    }

    @Test
    fun reverseRecoveryRejectsAmbiguousNewMappings() {
        val output = """
            device-serial tcp:41001 tcp:7000
            device-serial tcp:41002 tcp:7000
        """.trimIndent()

        assertThrows(InteropFailure::class.java) {
            recoverOwnedReversePort(output, 7_000, emptySet())
        }
    }

    private fun passedResult(
        profile: AndroidInteropProfile,
        backend: String,
    ): AndroidInteropResult = AndroidInteropResult(
        schemaVersion = 2,
        runId = RUN_ID,
        backend = backend,
        caseId = profile.caseId,
        scenario = profile.scenario,
        wireProtocol = profile.wireProtocol,
        useEncryption = profile.useEncryption,
        useCompression = profile.useCompression,
        status = "passed",
        semanticOutcome = profile.expectedSemanticOutcome,
        bindPort = BIND_PORT,
        checks = profile.expectedChecks,
    )

    private fun restartMarker(): AndroidInteropRestartMarker = AndroidInteropRestartMarker(
        schemaVersion = 1,
        runId = RUN_ID,
        caseId = "restart-v2-11",
        backend = "gomobile",
        step = "device_ready",
    )

    private companion object {
        const val RUN_ID = "00000000-0000-0000-0000-000000000001"
        const val BIND_PORT = 41_234
    }
}
