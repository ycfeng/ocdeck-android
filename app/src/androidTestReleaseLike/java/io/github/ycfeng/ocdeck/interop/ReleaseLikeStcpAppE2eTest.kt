package io.github.ycfeng.ocdeck.interop

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ycfeng.ocdeck.BuildConfig
import io.github.ycfeng.ocdeck.OpenCodeApplication
import io.github.ycfeng.ocdeck.core.network.PublicEndpointValidator
import io.github.ycfeng.ocdeck.core.notification.OpenCodeNotificationTarget
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.core.store.SessionListWindowState
import io.github.ycfeng.ocdeck.core.store.SseConnectionStatus
import io.github.ycfeng.ocdeck.data.server.FrpcTransportProtocol
import io.github.ycfeng.ocdeck.data.server.FrpcWireProtocol
import io.github.ycfeng.ocdeck.data.server.NewServerFrpcStcpVisitor
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerConnection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class ReleaseLikeStcpAppE2eTest {
    @Test
    fun threadLifecycleClassifierUsesStartTimeToDistinguishReusedTid() {
        val reusedBefore = ProcTaskIdentity(7, 100)
        val reusedAfter = ProcTaskIdentity(7, 200)
        val lifecycle = buildThreadLifecycleEvidence(
            start = setOf(reusedBefore),
            visitorReady = setOf(reusedBefore),
            postCleanup = setOf(reusedAfter),
            quiescence = setOf(reusedAfter),
        )

        assertEquals(1, lifecycle.presenceHistogram[3])
        assertEquals(1, lifecycle.presenceHistogram[12])
        assertEquals(2, lifecycle.presenceHistogram.sum())
    }

    @Test
    fun threadLifecycleCaptureFailsClosedAboveLimit() {
        val taskIds = (1..THREAD_LIFECYCLE_CAPTURE_LIMIT + 1).map(Int::toString)

        expectAssertion {
            captureThreadIdentities(
                listTaskIds = { taskIds },
                readStat = { validProcTaskStat(1) },
            )
        }
    }

    @Test
    fun procTaskStatParserUsesLastCommandDelimiterAndEnforcesBounds() {
        assertEquals(123L, parseProcTaskStartTime(validProcTaskStat(123, "worker ) name")))
        val exactLimit = validProcTaskStat(456).let { it + ByteArray(MAXIMUM_PROC_STAT_BYTES - it.size) { ' '.code.toByte() } }
        assertEquals(456L, parseProcTaskStartTime(exactLimit))
        expectAssertion { parseProcTaskStartTime(ByteArray(513)) }
        expectAssertion {
            parseProcTaskStartTime("7 (worker) S 1 2".toByteArray())
        }
    }

    private fun validProcTaskStat(startTime: Long, command: String = "worker"): ByteArray =
        ("7 ($command) " +
            (listOf("S") + (1..18).map(Int::toString) + startTime.toString()).joinToString(" ")).toByteArray()

    private fun expectAssertion(block: () -> Unit) {
        try {
            block()
            fail("expected bounded classifier failure")
        } catch (_: AppE2eAssertionException) {
        }
    }

    @Test
    fun resourceSampleCollectorRetainsTrueHeadAndTailWindows() {
        val collector = ResourceSampleCollector()

        repeat(1_000) { index ->
            collector.add(
                AppE2eResourceSnapshot(
                    elapsedRealtimeMillis = index.toLong(),
                    processCpuMillis = index.toLong(),
                    pssKiB = 1,
                    javaHeapBytes = 1,
                    nativeHeapBytes = 1,
                    openFdCount = 1,
                    threadCount = 1,
                ),
            )
        }

        val values = collector.values().map(AppE2eResourceSnapshot::elapsedRealtimeMillis)
        assertEquals((0L..126L).toList() + (873L..999L).toList(), values)
        assertEquals((1..127).toList() + (874..1_000).toList(), collector.values().map { it.sampleOrdinal })
        assertEquals(746, collector.droppedCount)
        assertEquals(
            AppE2eResourceSampleRetention(
                strategy = RESOURCE_SAMPLE_RETENTION_STRATEGY,
                observedSampleCount = 1_000,
                retainedHeadSampleCount = RESOURCE_HEAD_SAMPLES,
                retainedTailSampleCount = RESOURCE_TAIL_SAMPLES,
            ),
            collector.retention(),
        )
    }

    @Test
    fun runConfiguredAppFlow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_ENABLED) == "true")

        val runId = requireSafeRunId(arguments.getString(ARG_RUN_ID))
        val configFile = requireSafeFileName(arguments.getString(ARG_CONFIG_FILE))
        var stage = STAGE_CONFIGURATION
        val checks = linkedSetOf<String>()
        var startThreads = captureThreadIdentities()
        var resourceStart = captureResourceSnapshot(threadIdentities = startThreads)
        var runtimeInitialization: AppE2eRuntimeInitialization? = null
        var visitorReadyThreads: Set<ProcTaskIdentity>? = null
        var resourceVisitorReady: AppE2eResourceSnapshot? = null
        val resourceSamples = ResourceSampleCollector()

        val scenarioResult = try {
            val configured = readPrivateJson<AppE2eInvocation>(instrumentation, configFile).also {
                validateInvocation(it, runId)
            }
            runBlocking {
                if (arguments.getString(ARG_RUNTIME_DIAGNOSTIC_ENABLED) == "true" &&
                    configured.workload.profile != PROFILE_FUNCTIONAL
                ) {
                    stage = STAGE_RUNTIME_DIAGNOSTIC_INITIALIZE
                    val application = instrumentation.targetContext.applicationContext as? OpenCodeApplication
                        ?: throw AppE2eAssertionException()
                    application.container.frpcStcpVisitorManager.initializeBackendForRuntimeDiagnostics()
                    delay(RUNTIME_DIAGNOSTIC_QUIESCENCE_MILLIS)
                    stage = STAGE_RUNTIME_DIAGNOSTIC_SAMPLE
                    val initializedThreads = captureThreadIdentities()
                    val nativeDiagnostics = application.container.frpcStcpVisitorManager
                        .captureBackendRuntimeDiagnostics()
                    runtimeInitialization = AppE2eRuntimeInitialization(
                        requestedDelayMillis = RUNTIME_DIAGNOSTIC_QUIESCENCE_MILLIS,
                        coldThreadCount = startThreads.size,
                        initializedThreadCount = initializedThreads.size,
                        initializedGoroutineCount = nativeDiagnostics?.goroutineCount,
                        initializedSessionCount = nativeDiagnostics?.sessionCount,
                        initializedOwnerCounts = nativeDiagnostics?.ownerCounts.orEmpty(),
                        initializedRuntimeCounts = nativeDiagnostics?.runtimeCounts.orEmpty(),
                    )
                    startThreads = initializedThreads
                    resourceStart = captureResourceSnapshot(threadIdentities = initializedThreads)
                }
                withTimeout(scenarioTimeoutMillis(configured.workload)) {
                    runScenario(
                        invocation = configured,
                        checks = checks,
                        resourceSamples = resourceSamples,
                        updateStage = { stage = it },
                        onVisitorReady = {
                            if (configured.workload.profile != PROFILE_FUNCTIONAL) {
                                val identities = captureThreadIdentities()
                                visitorReadyThreads = identities
                                resourceVisitorReady = captureResourceSnapshot(threadIdentities = identities)
                            }
                        },
                    )
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            failedResult(runId, stage, checks, FAILURE_TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Error) {
            throw failure
        } catch (_: AppE2eAssertionException) {
            failedResult(runId, stage, checks, FAILURE_ASSERTION)
        } catch (_: Exception) {
            failedResult(runId, stage, checks, FAILURE_UNEXPECTED)
        }
        val postCleanupThreads = captureThreadIdentities()
        val resourceEnd = captureResourceSnapshot(threadIdentities = postCleanupThreads)
        var quiescenceThreads: Set<ProcTaskIdentity>? = null
        val quiescence = if (scenarioResult.status == STATUS_PASSED && scenarioResult.workload != null) {
            runBlocking { delay(RESOURCE_QUIESCENCE_MILLIS) }
            val identities = captureThreadIdentities()
            quiescenceThreads = identities
            AppE2eResourceQuiescence(
                requestedDelayMillis = RESOURCE_QUIESCENCE_MILLIS,
                snapshot = captureResourceSnapshot(threadIdentities = identities),
            )
        } else {
            null
        }
        val postSessionNativeDiagnostics = if (runtimeInitialization != null) {
            runBlocking {
                val application = instrumentation.targetContext.applicationContext as? OpenCodeApplication
                    ?: throw AppE2eAssertionException()
                application.container.frpcStcpVisitorManager.captureBackendRuntimeDiagnostics()
            }
        } else {
            null
        }
        val result = scenarioResult.copy(
            runtimeInitialization = runtimeInitialization?.copy(
                postSessionQuiescenceThreadCount = quiescence?.snapshot?.threadCount,
                postSessionQuiescenceGoroutineCount = postSessionNativeDiagnostics?.goroutineCount,
                postSessionQuiescenceSessionCount = postSessionNativeDiagnostics?.sessionCount,
                postSessionQuiescenceOwnerCounts = postSessionNativeDiagnostics?.ownerCounts.orEmpty(),
                postSessionQuiescenceRuntimeCounts = postSessionNativeDiagnostics?.runtimeCounts.orEmpty(),
            ),
            resources = buildResourceEvidence(
                start = resourceStart,
                visitorReady = resourceVisitorReady,
                end = resourceEnd,
                samples = resourceSamples,
                quiescence = quiescence,
                threadLifecycle = if (scenarioResult.status == STATUS_PASSED && scenarioResult.workload != null) {
                    buildThreadLifecycleEvidence(
                        startThreads,
                        visitorReadyThreads ?: throw AppE2eAssertionException(),
                        postCleanupThreads,
                        quiescenceThreads ?: throw AppE2eAssertionException(),
                    )
                } else {
                    null
                },
            ),
        )

        try {
            val encoded = JSON.encodeToString(result)
            if (encoded.toByteArray(Charsets.UTF_8).size > MAXIMUM_JSON_BYTES) throw AppE2eAssertionException()
            instrumentation.sendStatus(0, Bundle().apply { putString(STATUS_RESULT_KEY, encoded) })
        } catch (_: Exception) {
            fail("STCP App E2E result publication failed")
        }
        if (result.status != STATUS_PASSED) {
            fail("STCP App E2E failed at ${result.failedStage} with ${result.failureCode}")
        }
    }

    private suspend fun runScenario(
        invocation: AppE2eInvocation,
        checks: MutableSet<String>,
        resourceSamples: ResourceSampleCollector,
        updateStage: (String) -> Unit,
        onVisitorReady: () -> Unit,
    ): AppE2eResult {
        if (invocation.suite == SUITE_PROCESS) {
            return runProcessScenario(invocation, checks, updateStage)
        }
        checks += backendCheck(invocation.backend)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as? OpenCodeApplication
            ?: throw AppE2eAssertionException()
        val container = application.container
        val bindPort = reserveLoopbackPort()
        var server: ServerConfig? = null
        var globalLease: io.github.ycfeng.ocdeck.core.network.GlobalConnectionLease? = null
        var projectLease: io.github.ycfeng.ocdeck.core.network.ProjectConnectionLease? = null
        var primaryFailure: Throwable? = null
        var initialGeneration = 0L
        var initialControlEpoch = 0L
        var recoveredGeneration = 0L
        var recoveredControlEpoch = 0L
        var deviceIdleForced = false
        var workloadResult: AppE2eWorkloadResult? = null

        try {
            if (invocation.suite == SUITE_NETWORK) {
                updateStage(STAGE_NETWORK_INITIAL_WIFI)
                awaitValidatedTransport(
                    instrumentation.targetContext,
                    NetworkCapabilities.TRANSPORT_WIFI,
                    invocation.frpsHost,
                )
                checks += CHECK_INITIAL_WIFI_VALIDATED
            }
            updateStage(STAGE_SERVER_CONFIGURATION)
            server = container.serverRepository.addServer(
                baseUrl = "http://127.0.0.1:$bindPort/",
                name = "STCP App E2E",
                username = null,
                password = null,
                frpcStcpVisitor = NewServerFrpcStcpVisitor(
                    enabled = true,
                    serverAddr = invocation.frpsHost,
                    serverPort = invocation.frpsPort,
                    authToken = invocation.authToken,
                    tlsServerCertificateSha256 = invocation.tlsServerCertificateSha256,
                    user = invocation.user,
                    serverUser = invocation.serverUser,
                    serverName = invocation.serverName,
                    secretKey = invocation.secretKey,
                    bindPort = bindPort,
                    transportProtocol = FrpcTransportProtocol.Tcp,
                    wireProtocol = FrpcWireProtocol.V2,
                ),
            )

            updateStage(STAGE_STCP_READY)
            val connection = container.serverRepository.getConnection(server.id)
            val generation = connection.stcpGeneration ?: throw AppE2eAssertionException()
            initialGeneration = generation.ordinal
            initialControlEpoch = connection.stcpControlEpoch ?: throw AppE2eAssertionException()
            var restartBaselineTransportIdentity = connection.transportIdentity
            if (connection.readinessHealth?.healthy != true || initialGeneration <= 0L || initialControlEpoch <= 0L) {
                throw AppE2eAssertionException()
            }
            checks += CHECK_STCP_READY
            onVisitorReady()

            updateStage(STAGE_SNAPSHOT)
            val loaded = container.openCodeRepository.loadProjectSnapshot(
                serverId = server.id,
                directory = PROJECT_DIRECTORY,
                workspace = null,
                sessionWindow = SessionListWindowState(),
                activeSessionMessages = null,
            ).getOrThrow()
            if (loaded.transportIdentity != connection.transportIdentity) throw AppE2eAssertionException()
            container.openCodeStore.applyProjectSnapshot(loaded.snapshot)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1" &&
                    state.project?.projectId == "project-e2e"
            }
            checks += CHECK_SNAPSHOT_STORE

            updateStage(STAGE_GLOBAL_SSE)
            globalLease = container.openCodeEventClient.connectGlobal(server.id)
            withTimeout(STATE_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1"
            }
            checks += CHECK_GLOBAL_SSE_STORE

            updateStage(STAGE_PROJECT_SSE)
            projectLease = container.openCodeEventClient.connectProject(server.id, PROJECT_DIRECTORY)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"
            }
            checks += CHECK_PROJECT_SSE_STORE

            if (invocation.workload.profile != PROFILE_FUNCTIONAL) {
                workloadResult = runResourceWorkload(
                    invocation = invocation,
                    container = container,
                    server = server,
                    resourceSamples = resourceSamples,
                    updateStage = updateStage,
                )
                updateStage(STAGE_RESOURCE_POST_WORKLOAD_CONNECTION)
                val workloadConnection = container.serverRepository.getConnection(server.id)
                updateStage(STAGE_RESOURCE_POST_WORKLOAD_IDENTITY)
                initialGeneration = workloadConnection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
                initialControlEpoch = workloadConnection.stcpControlEpoch ?: throw AppE2eAssertionException()
                restartBaselineTransportIdentity = workloadConnection.transportIdentity
                updateStage(STAGE_RESOURCE_POST_WORKLOAD_HEALTH)
                // The already-ready epoch-refresh fast path intentionally omits a duplicate HTTP health result.
                if (workloadConnection.readinessHealth?.healthy == false) throw AppE2eAssertionException()
            }

            when (invocation.suite) {
                SUITE_CORE -> {
                updateStage(STAGE_FOREGROUND_RECONCILIATION)
                val revisionBeforeForeground = container.openCodeStore.captureProjectDataRevision(
                    server.id,
                    PROJECT_DIRECTORY,
                )
                container.appConnectionCoordinator.onForeground()
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.connectionStatus == SseConnectionStatus.Open &&
                        state.projectDataRevision > revisionBeforeForeground &&
                        state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1"
                }
                checks += CHECK_FOREGROUND_RECONCILIATION
                }
                SUITE_LIFECYCLE -> {
                updateStage(STAGE_ACTIVITY_FOREGROUND)
                val revisionBeforeActivity = container.openCodeStore.captureProjectDataRevision(
                    server.id,
                    PROJECT_DIRECTORY,
                )
                launchMainActivity(instrumentation, server.id)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == SESSION_SNAPSHOT &&
                        state.connectionStatus == SseConnectionStatus.Open &&
                        state.projectDataRevision > revisionBeforeActivity
                }
                checks += CHECK_ACTIVITY_FOREGROUND

                updateStage(STAGE_ACTIVITY_CYCLES)
                val revisionBeforeCycles = container.openCodeStore.captureProjectDataRevision(
                    server.id,
                    PROJECT_DIRECTORY,
                )
                repeat(LIFECYCLE_CYCLE_COUNT) { cycle ->
                    updateStage("activity_cycle_${cycle + 1}_background")
                    pressHome(instrumentation)
                    awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                        state.activeSessionId == null && state.hasGenerationOneData()
                    }
                    updateStage("activity_cycle_${cycle + 1}_foreground")
                    launchMainActivity(instrumentation, server.id)
                    awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                        state.activeSessionId == SESSION_SNAPSHOT &&
                            state.connectionStatus == SseConnectionStatus.Open &&
                            state.hasGenerationOneData()
                    }
                }
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.projectDataRevision > revisionBeforeCycles && state.hasGenerationOneData()
                }
                checks += CHECK_ACTIVITY_BACKGROUND_DATA_RETAINED
                checks += CHECK_ACTIVITY_FOREGROUND_RECONCILIATION

                updateStage(STAGE_DOZE_ENTER)
                pressHome(instrumentation)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == null && state.hasGenerationOneData()
                }
                deviceIdleForced = true
                forceDeviceIdle(instrumentation)
                checks += CHECK_DOZE_ENTERED
                delay(DOZE_HOLD_MILLIS)

                updateStage(STAGE_DOZE_EXIT)
                restoreDeviceIdle(instrumentation)
                deviceIdleForced = false
                launchMainActivity(instrumentation, server.id)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == SESSION_SNAPSHOT &&
                        state.connectionStatus == SseConnectionStatus.Open &&
                        state.hasGenerationOneData()
                }
                checks += CHECK_DOZE_EXIT_RECOVERY

                pressHome(instrumentation)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == null && state.hasGenerationOneData()
                }
                }
                SUITE_NETWORK -> Unit
                else -> throw AppE2eAssertionException()
            }

            if (invocation.suite == SUITE_NETWORK) {
                val recovered = runNetworkSwitchScenario(
                    instrumentation = instrumentation,
                    invocation = invocation,
                    container = container,
                    server = server,
                    initialConnection = connection,
                    initialGeneration = initialGeneration,
                    initialControlEpoch = initialControlEpoch,
                    checks = checks,
                    updateStage = updateStage,
                )
                recoveredGeneration = recovered.first
                recoveredControlEpoch = recovered.second
            } else {
            updateStage(STAGE_RESTART_DISCONNECT)
            writePrivateMarker(
                instrumentation,
                invocation.restartMarkers.deviceReadyFile,
            )
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus != SseConnectionStatus.Open &&
                    state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1" &&
                    state.sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1" &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"
            }
            withTimeout(STATE_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first {
                    it != SseConnectionStatus.Open
                }
            }
            checks += CHECK_DISCONNECT_DATA_RETAINED
            writePrivateMarker(
                instrumentation,
                invocation.restartMarkers.deviceDisconnectedFile,
            )

            updateStage(STAGE_RESTART_RECOVERY)
            if (invocation.suite == SUITE_LIFECYCLE) {
                awaitPrivateMarker(
                    instrumentation,
                    invocation.restartMarkers.frpsOnlyFile,
                    MARKER_FRPS_ONLY,
                )
                launchMainActivity(instrumentation, server.id)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == SESSION_SNAPSHOT && state.hasGenerationOneData()
                }
                delay(PROVIDER_DOWN_ASSERTION_MILLIS)
                val providerWindowProject = withTimeout(STATE_TIMEOUT_MILLIS) {
                    container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY).first()
                }
                val providerWindowGlobal = withTimeout(STATE_TIMEOUT_MILLIS) {
                    container.openCodeStore.observeGlobalConnection(server.id).first()
                }
                if (providerWindowProject.connectionStatus == SseConnectionStatus.Open ||
                    providerWindowGlobal == SseConnectionStatus.Open ||
                    !providerWindowProject.hasGenerationOneData()
                ) {
                    throw AppE2eAssertionException()
                }
                checks += CHECK_PROVIDER_RACE_DATA_RETAINED
                writePrivateMarker(
                    instrumentation,
                    invocation.restartMarkers.providerWindowObservedFile,
                )
            }
            updateStage(STAGE_RESTART_RECOVERY_MARKER)
            awaitPrivateMarker(
                instrumentation,
                invocation.restartMarkers.frpsRestartedFile,
                MARKER_FRPS_RESTARTED,
            )
            updateStage(STAGE_RESTART_RECOVERY_GLOBAL)
            withTimeout(RECOVERY_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            updateStage(STAGE_RESTART_RECOVERY_PROJECT_OPEN)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open
            }
            updateStage(STAGE_RESTART_RECOVERY_PROJECT_SSE)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-2"
            }
            updateStage(STAGE_RESTART_RECOVERY_SNAPSHOT)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-2"
            }
            updateStage(STAGE_RESTART_RECOVERY_GLOBAL_DATA)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_GLOBAL_SSE) in setOf("global-sse-1", "global-sse-2")
            }
            updateStage(STAGE_RESTART_RECOVERY_IDENTITY)
            val recoveredConnection = container.serverRepository.getConnection(server.id)
            recoveredGeneration = recoveredConnection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
            recoveredControlEpoch = recoveredConnection.stcpControlEpoch ?: throw AppE2eAssertionException()
            updateStage(STAGE_RESTART_RECOVERY_GENERATION)
            if (invocation.backend == BACKEND_KOTLIN && recoveredGeneration != initialGeneration) {
                throw AppE2eAssertionException()
            }
            updateStage(STAGE_RESTART_RECOVERY_EPOCH)
            if (invocation.backend == BACKEND_KOTLIN && recoveredControlEpoch <= initialControlEpoch) {
                throw AppE2eAssertionException()
            }
            updateStage(STAGE_RESTART_RECOVERY_TRANSPORT)
            if (recoveredConnection.transportIdentity.monotonicCompareTo(restartBaselineTransportIdentity) != 1) {
                throw AppE2eAssertionException()
            }
            if (invocation.suite == SUITE_LIFECYCLE) checks += CHECK_LONG_OUTAGE_DATA_RETAINED
            checks += if (invocation.backend == BACKEND_KOTLIN) {
                CHECK_CONTROL_EPOCH_ADVANCED
            } else {
                CHECK_TRANSPORT_IDENTITY_ADVANCED
            }
            checks += CHECK_RESTART_RECONCILIATION

            updateStage(STAGE_POST_RESTART_FOREGROUND)
            val revisionBeforeRecoveryForeground = container.openCodeStore.captureProjectDataRevision(
                server.id,
                PROJECT_DIRECTORY,
            )
            if (invocation.suite == SUITE_CORE) {
                container.appConnectionCoordinator.onForeground()
            } else {
                pressHome(instrumentation)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == null && state.hasGenerationTwoData()
                }
                launchMainActivity(instrumentation, server.id)
            }
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.projectDataRevision > revisionBeforeRecoveryForeground &&
                    state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-2" &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-2" &&
                    (invocation.suite == SUITE_CORE || state.activeSessionId == SESSION_SNAPSHOT)
            }
            checks += CHECK_POST_RESTART_FOREGROUND
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = withContext(NonCancellable) {
            try {
                if (primaryFailure == null) updateStage(STAGE_CLEANUP)
                if (deviceIdleForced) {
                    restoreDeviceIdle(instrumentation)
                    deviceIdleForced = false
                }
                projectLease?.release()
                globalLease?.release()
                server?.let { configured ->
                    container.openCodeEventClient.closeServer(configured.id)
                    awaitProject(container.openCodeStore.observeProject(configured.id, PROJECT_DIRECTORY)) { state ->
                        state.connectionStatus == SseConnectionStatus.Closed
                    }
                    withTimeout(STATE_TIMEOUT_MILLIS) {
                        container.openCodeStore.observeGlobalConnection(configured.id).first {
                            it == SseConnectionStatus.Closed
                        }
                    }
                    checks += CHECK_STREAMS_CLOSED
                    container.serverRepository.deleteServer(configured)
                    awaitPortReleased(bindPort)
                    checks += CHECK_VISITOR_CLOSED
                    checks += CHECK_PORT_RELEASED
                }
                null
            } catch (failure: Throwable) {
                failure
            }
        }
        throwSelected(primaryFailure, cleanupFailure)

        val expectedChecks = expectedChecks(invocation.suite, invocation.backend)
        if (checks != expectedChecks) throw AppE2eAssertionException()
        return AppE2eResult(
            schemaVersion = SCHEMA_VERSION,
            runId = invocation.runId,
            status = STATUS_PASSED,
            checks = checks.toList(),
            backend = invocation.backend,
            bindPort = bindPort,
            initialGeneration = initialGeneration,
            initialControlEpoch = initialControlEpoch,
            recoveredGeneration = recoveredGeneration,
            recoveredControlEpoch = recoveredControlEpoch,
            suite = invocation.suite,
            topology = topologyFor(invocation.suite),
            networkSwitchProven = invocation.suite == SUITE_NETWORK,
            phase = invocation.phase,
            processId = Process.myPid(),
            workload = workloadResult,
        )
    }

    private suspend fun runProcessScenario(
        invocation: AppE2eInvocation,
        checks: MutableSet<String>,
        updateStage: (String) -> Unit,
    ): AppE2eResult = when (invocation.phase) {
        PHASE_PROCESS_PREPARE -> runProcessPrepare(invocation, checks, updateStage)
        PHASE_PROCESS_RECOVER -> runProcessRecover(invocation, checks, updateStage)
        else -> throw AppE2eAssertionException()
    }

    private suspend fun runResourceWorkload(
        invocation: AppE2eInvocation,
        container: io.github.ycfeng.ocdeck.app.AppContainer,
        server: ServerConfig,
        resourceSamples: ResourceSampleCollector,
        updateStage: (String) -> Unit,
    ): AppE2eWorkloadResult {
        val workload = invocation.workload
        updateStage(STAGE_RESOURCE_WORKLOAD)
        val startedAt = SystemClock.elapsedRealtime()
        var completedIterations = 0
        var reconnectCount = 0
        while (completedIterations < workload.iterations &&
            (workload.durationMillis == 0L || SystemClock.elapsedRealtime() - startedAt < workload.durationMillis)
        ) {
            updateStage(STAGE_RESOURCE_SNAPSHOT)
            val connection = container.serverRepository.getConnection(server.id)
            val snapshots = coroutineScope {
                List(workload.concurrency) {
                    async(Dispatchers.IO) {
                        container.openCodeRepository.loadProjectSnapshot(
                            serverId = server.id,
                            directory = PROJECT_DIRECTORY,
                            workspace = null,
                            sessionWindow = SessionListWindowState(),
                            activeSessionMessages = null,
                        ).getOrThrow()
                    }
                }.awaitAll()
            }
            updateStage(STAGE_RESOURCE_SNAPSHOT_IDENTITY)
            if (snapshots.isEmpty() || snapshots.any { it.transportIdentity != connection.transportIdentity }) {
                throw AppE2eAssertionException()
            }
            container.openCodeStore.applyProjectSnapshot(snapshots.last().snapshot)
            completedIterations += 1

            if (workload.reconnectEveryIterations > 0 &&
                completedIterations % workload.reconnectEveryIterations == 0
            ) {
                updateStage(STAGE_RESOURCE_DISCONNECT)
                coroutineScope {
                    val globalDisconnected = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(RECOVERY_TIMEOUT_MILLIS) {
                            container.openCodeStore.observeGlobalConnection(server.id)
                                .first { it != SseConnectionStatus.Open }
                        }
                    }
                    val projectDisconnected = async(start = CoroutineStart.UNDISPATCHED) {
                        awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) {
                            it.connectionStatus != SseConnectionStatus.Open
                        }
                    }
                    container.frpcStcpVisitorManager.closeVisitor(server.id)
                    globalDisconnected.await()
                    projectDisconnected.await()
                }
                updateStage(STAGE_RESOURCE_RECONNECT)
                val recovered = container.serverRepository.getConnection(server.id)
                if (recovered.readinessHealth?.healthy != true ||
                    recovered.transportIdentity.monotonicCompareTo(connection.transportIdentity) != 1
                ) {
                    throw AppE2eAssertionException()
                }
                updateStage(STAGE_RESOURCE_GLOBAL_REOPEN)
                withTimeout(RECOVERY_TIMEOUT_MILLIS) {
                    container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
                }
                updateStage(STAGE_RESOURCE_PROJECT_REOPEN)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) {
                    it.connectionStatus == SseConnectionStatus.Open
                }
                reconnectCount += 1
            }
            if (completedIterations % workload.sampleEveryIterations == 0) {
                updateStage(STAGE_RESOURCE_SAMPLE)
                resourceSamples.add(captureResourceSnapshot(updateStage))
            }
            if (workload.profile == PROFILE_SOAK) {
                val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
                val targetElapsedMillis = workload.durationMillis * completedIterations / workload.iterations
                val pacingDelayMillis = targetElapsedMillis - elapsedMillis
                if (pacingDelayMillis > 0L) {
                    delay(pacingDelayMillis)
                }
            }
        }
        if (completedIterations <= 0 ||
            (workload.profile != PROFILE_SOAK && completedIterations != workload.iterations) ||
            (workload.profile == PROFILE_SOAK && SystemClock.elapsedRealtime() - startedAt < workload.durationMillis)
        ) {
            throw AppE2eAssertionException()
        }
        return AppE2eWorkloadResult(
            profile = workload.profile,
            completedIterations = completedIterations,
            reconnectCount = reconnectCount,
        )
    }

    private suspend fun runNetworkSwitchScenario(
        instrumentation: Instrumentation,
        invocation: AppE2eInvocation,
        container: io.github.ycfeng.ocdeck.app.AppContainer,
        server: ServerConfig,
        initialConnection: ServerConnection,
        initialGeneration: Long,
        initialControlEpoch: Long,
        checks: MutableSet<String>,
        updateStage: (String) -> Unit,
    ): Pair<Long, Long> {
        updateStage(STAGE_NETWORK_CELLULAR_WAIT)
        writePrivateMarker(instrumentation, invocation.restartMarkers.deviceReadyFile)
        awaitPrivateMarker(
            instrumentation,
            invocation.restartMarkers.frpsOnlyFile,
            MARKER_NETWORK_CELLULAR_DISCONNECTED,
        )
        awaitValidatedTransport(
            instrumentation.targetContext,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            invocation.frpsHost,
        )
        checks += CHECK_CELLULAR_VALIDATED

        updateStage(STAGE_NETWORK_CELLULAR_RETENTION)
        awaitStreamsUnavailable(container, server.id)
        awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
            state.hasGenerationOneData()
        }
        checks += CHECK_CELLULAR_DATA_RETAINED
        writePrivateMarker(instrumentation, invocation.restartMarkers.deviceDisconnectedFile)

        updateStage(STAGE_NETWORK_CELLULAR_RECOVERY)
        awaitPrivateMarker(
            instrumentation,
            invocation.restartMarkers.frpsRestartedFile,
            MARKER_NETWORK_CELLULAR_RECOVERED,
        )
        withTimeout(RECOVERY_TIMEOUT_MILLIS) {
            container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
        }
        awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
            state.connectionStatus == SseConnectionStatus.Open &&
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-2" &&
                state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-2"
        }
        val cellularConnection = container.serverRepository.getConnection(server.id)
        val cellularGeneration = cellularConnection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
        val cellularControlEpoch = cellularConnection.stcpControlEpoch ?: throw AppE2eAssertionException()
        if (cellularGeneration != initialGeneration || cellularControlEpoch <= initialControlEpoch ||
            cellularConnection.transportIdentity.isOlderThan(initialConnection.transportIdentity)
        ) {
            throw AppE2eAssertionException()
        }
        checks += CHECK_CELLULAR_EPOCH_ADVANCED
        checks += CHECK_CELLULAR_RECONCILIATION
        writePrivateMarker(instrumentation, invocation.restartMarkers.providerWindowObservedFile)

        updateStage(STAGE_NETWORK_WIFI_WAIT)
        awaitPrivateMarker(
            instrumentation,
            invocation.restartMarkers.wifiDisconnectedFile,
            MARKER_NETWORK_WIFI_DISCONNECTED,
        )
        awaitValidatedTransport(
            instrumentation.targetContext,
            NetworkCapabilities.TRANSPORT_WIFI,
            invocation.frpsHost,
        )
        checks += CHECK_WIFI_RESTORED_VALIDATED

        updateStage(STAGE_NETWORK_WIFI_RETENTION)
        awaitStreamsUnavailable(container, server.id)
        awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
            state.hasGenerationTwoData()
        }
        checks += CHECK_WIFI_RETURN_DATA_RETAINED
        writePrivateMarker(instrumentation, invocation.restartMarkers.wifiRetentionObservedFile)

        updateStage(STAGE_NETWORK_WIFI_RECOVERY)
        awaitPrivateMarker(
            instrumentation,
            invocation.restartMarkers.wifiRecoveredFile,
            MARKER_NETWORK_WIFI_RECOVERED,
        )
        withTimeout(RECOVERY_TIMEOUT_MILLIS) {
            container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
        }
        awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
            state.connectionStatus == SseConnectionStatus.Open &&
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-3" &&
                state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-3"
        }
        val wifiConnection = container.serverRepository.getConnection(server.id)
        val wifiGeneration = wifiConnection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
        val wifiControlEpoch = wifiConnection.stcpControlEpoch ?: throw AppE2eAssertionException()
        if (wifiGeneration != initialGeneration || wifiControlEpoch <= cellularControlEpoch ||
            wifiConnection.transportIdentity.isOlderThan(cellularConnection.transportIdentity)
        ) {
            throw AppE2eAssertionException()
        }
        checks += CHECK_WIFI_RETURN_EPOCH_ADVANCED
        checks += CHECK_WIFI_RETURN_RECONCILIATION
        writePrivateMarker(instrumentation, invocation.restartMarkers.wifiRecoveryObservedFile)
        return wifiGeneration to wifiControlEpoch
    }

    private suspend fun awaitStreamsUnavailable(
        container: io.github.ycfeng.ocdeck.app.AppContainer,
        serverId: String,
    ) {
        withTimeout(RECOVERY_TIMEOUT_MILLIS) {
            container.openCodeStore.observeGlobalConnection(serverId).first { it != SseConnectionStatus.Open }
        }
        awaitProject(container.openCodeStore.observeProject(serverId, PROJECT_DIRECTORY)) { state ->
            state.connectionStatus != SseConnectionStatus.Open
        }
    }

    private suspend fun runProcessPrepare(
        invocation: AppE2eInvocation,
        checks: MutableSet<String>,
        updateStage: (String) -> Unit,
    ): AppE2eResult {
        checks += backendCheck(invocation.backend)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as? OpenCodeApplication
            ?: throw AppE2eAssertionException()
        val container = application.container
        val bindPort = reserveLoopbackPort()
        var server: ServerConfig? = null
        var globalLease: io.github.ycfeng.ocdeck.core.network.GlobalConnectionLease? = null
        var projectLease: io.github.ycfeng.ocdeck.core.network.ProjectConnectionLease? = null
        var primaryFailure: Throwable? = null
        var generation = 0L
        var controlEpoch = 0L

        try {
            updateStage(STAGE_PROCESS_SERVER_PERSIST)
            server = container.serverRepository.addServer(
                baseUrl = "http://127.0.0.1:$bindPort/",
                name = SERVER_NAME,
                username = null,
                password = null,
                frpcStcpVisitor = NewServerFrpcStcpVisitor(
                    enabled = true,
                    serverAddr = invocation.frpsHost,
                    serverPort = invocation.frpsPort,
                    authToken = invocation.authToken,
                    tlsServerCertificateSha256 = invocation.tlsServerCertificateSha256,
                    user = invocation.user,
                    serverUser = invocation.serverUser,
                    serverName = invocation.serverName,
                    secretKey = invocation.secretKey,
                    bindPort = bindPort,
                    transportProtocol = FrpcTransportProtocol.Tcp,
                    wireProtocol = FrpcWireProtocol.V2,
                ),
            )
            val persisted = withTimeout(STATE_TIMEOUT_MILLIS) {
                container.serverRepository.observeServers().first { servers ->
                    servers.size == 1 && servers.single().id == server.id
                }
            }.single()
            if (persisted.frpcStcpVisitor?.bindPort != bindPort) throw AppE2eAssertionException()
            checks += CHECK_SERVER_PERSISTED

            updateStage(STAGE_PROCESS_INITIAL_CONNECTION)
            val connection = container.serverRepository.getConnection(server.id)
            generation = connection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
            controlEpoch = connection.stcpControlEpoch ?: throw AppE2eAssertionException()
            if (connection.readinessHealth?.healthy != true || generation <= 0L || controlEpoch <= 0L) {
                throw AppE2eAssertionException()
            }
            checks += CHECK_STCP_READY

            updateStage(STAGE_PROCESS_INITIAL_SNAPSHOT)
            val loaded = container.openCodeRepository.loadProjectSnapshot(
                serverId = server.id,
                directory = PROJECT_DIRECTORY,
                workspace = null,
                sessionWindow = SessionListWindowState(),
                activeSessionMessages = null,
            ).getOrThrow()
            if (loaded.transportIdentity != connection.transportIdentity) throw AppE2eAssertionException()
            container.openCodeStore.applyProjectSnapshot(loaded.snapshot)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1"
            }
            checks += CHECK_SNAPSHOT_STORE

            updateStage(STAGE_PROCESS_INITIAL_STREAMS)
            globalLease = container.openCodeEventClient.connectGlobal(server.id)
            withTimeout(STATE_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1"
            }
            projectLease = container.openCodeEventClient.connectProject(server.id, PROJECT_DIRECTORY)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"
            }
            checks += CHECK_INITIAL_STREAMS

            updateStage(STAGE_PROCESS_INITIAL_ACTIVITY)
            launchMainActivity(instrumentation, server.id)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.activeSessionId == SESSION_SNAPSHOT && state.hasGenerationOneData()
            }
            checks += CHECK_INITIAL_ACTIVITY_ROUTE
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = withContext(NonCancellable) {
            val cleanup = CleanupCollector()
            cleanup.run { projectLease?.release() }
            cleanup.run { globalLease?.release() }
            val configured = server
            val streamsClosed = configured != null && cleanup.run {
                container.openCodeEventClient.closeServer(configured.id)
                awaitStreamsClosed(container, configured.id)
            }
            val visitorClosed = configured != null && cleanup.run {
                container.frpcStcpVisitorManager.closeVisitor(configured.id)
            }
            val portReleased = bindPort > 0 && cleanup.run { awaitPortReleased(bindPort) }
            val preserveServer = primaryFailure == null && cleanup.failure() == null && configured != null
            val persistenceVerified = preserveServer && cleanup.run {
                val persisted = container.serverRepository.observeServers().first()
                if (persisted.size != 1 || persisted.single().id != configured.id) {
                    throw AppE2eAssertionException()
                }
            }
            if (persistenceVerified) {
                if (streamsClosed && visitorClosed) checks += CHECK_RUNTIME_CLOSED
                if (portReleased) checks += CHECK_PORT_RELEASED
            } else {
                cleanup.run { deleteProcessServers(container, configured) }
            }
            cleanup.failure()
        }
        throwSelected(primaryFailure, cleanupFailure)

        if (checks != expectedProcessChecks(PROCESS_PREPARE_EXPECTED_CHECKS, invocation.backend)) {
            throw AppE2eAssertionException()
        }
        return AppE2eResult(
            schemaVersion = SCHEMA_VERSION,
            runId = invocation.runId,
            status = STATUS_PASSED,
            checks = checks.toList(),
            backend = invocation.backend,
            bindPort = bindPort,
            initialGeneration = generation,
            initialControlEpoch = controlEpoch,
            suite = invocation.suite,
            topology = TOPOLOGY_ADB_REVERSE,
            networkSwitchProven = false,
            phase = invocation.phase,
            processId = Process.myPid(),
        )
    }

    private suspend fun runProcessRecover(
        invocation: AppE2eInvocation,
        checks: MutableSet<String>,
        updateStage: (String) -> Unit,
    ): AppE2eResult {
        checks += backendCheck(invocation.backend)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as? OpenCodeApplication
            ?: throw AppE2eAssertionException()
        val container = application.container
        var server: ServerConfig? = null
        var globalLease: io.github.ycfeng.ocdeck.core.network.GlobalConnectionLease? = null
        var projectLease: io.github.ycfeng.ocdeck.core.network.ProjectConnectionLease? = null
        var primaryFailure: Throwable? = null
        var generation = 0L
        var controlEpoch = 0L
        var bindPort = 0

        try {
            updateStage(STAGE_PROCESS_PERSISTED_LOAD)
            server = withTimeout(STATE_TIMEOUT_MILLIS) {
                container.serverRepository.observeServers().first { servers ->
                    servers.size == 1 && servers.single().name == SERVER_NAME
                }
            }.single()
            bindPort = server.frpcStcpVisitor?.bindPort ?: throw AppE2eAssertionException()
            if (bindPort !in 1..65_535) throw AppE2eAssertionException()
            checks += CHECK_PERSISTED_SERVER_LOADED

            updateStage(STAGE_PROCESS_RECONNECTED)
            val connection = container.serverRepository.getConnection(server.id)
            generation = connection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
            controlEpoch = connection.stcpControlEpoch ?: throw AppE2eAssertionException()
            if (connection.readinessHealth?.healthy != true || generation <= 0L || controlEpoch <= 0L) {
                throw AppE2eAssertionException()
            }
            checks += CHECK_STCP_RECONNECTED

            updateStage(STAGE_PROCESS_RESTORED_SNAPSHOT)
            val loaded = container.openCodeRepository.loadProjectSnapshot(
                serverId = server.id,
                directory = PROJECT_DIRECTORY,
                workspace = null,
                sessionWindow = SessionListWindowState(),
                activeSessionMessages = null,
            ).getOrThrow()
            if (loaded.transportIdentity != connection.transportIdentity) throw AppE2eAssertionException()
            container.openCodeStore.applyProjectSnapshot(loaded.snapshot)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1"
            }
            checks += CHECK_SNAPSHOT_RESTORED

            updateStage(STAGE_PROCESS_RESTORED_STREAMS)
            globalLease = container.openCodeEventClient.connectGlobal(server.id)
            withTimeout(STATE_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1"
            }
            projectLease = container.openCodeEventClient.connectProject(server.id, PROJECT_DIRECTORY)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"
            }
            checks += CHECK_STREAMS_RESTORED

            updateStage(STAGE_PROCESS_RESTORED_ACTIVITY)
            launchMainActivity(instrumentation, server.id)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.activeSessionId == SESSION_SNAPSHOT && state.hasGenerationOneData()
            }
            checks += CHECK_ACTIVITY_ROUTE_RESTORED
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = withContext(NonCancellable) {
            val cleanup = CleanupCollector()
            cleanup.run { projectLease?.release() }
            cleanup.run { globalLease?.release() }
            val configured = server
            val streamsClosed = configured != null && cleanup.run {
                container.openCodeEventClient.closeServer(configured.id)
                awaitStreamsClosed(container, configured.id)
            }
            if (streamsClosed) checks += CHECK_STREAMS_CLOSED
            if (configured != null) {
                cleanup.run { container.frpcStcpVisitorManager.closeVisitor(configured.id) }
            }
            val serverDeleted = cleanup.run {
                deleteProcessServers(container, configured)
                if (container.serverRepository.observeServers().first().isNotEmpty()) {
                    throw AppE2eAssertionException()
                }
            }
            if (serverDeleted) {
                checks += CHECK_SERVER_DELETED
                checks += CHECK_VISITOR_CLOSED
            }
            val portReleased = bindPort > 0 && cleanup.run { awaitPortReleased(bindPort) }
            if (portReleased) checks += CHECK_PORT_RELEASED
            cleanup.failure()
        }
        throwSelected(primaryFailure, cleanupFailure)

        if (checks != expectedProcessChecks(PROCESS_RECOVER_EXPECTED_CHECKS, invocation.backend)) {
            throw AppE2eAssertionException()
        }
        return AppE2eResult(
            schemaVersion = SCHEMA_VERSION,
            runId = invocation.runId,
            status = STATUS_PASSED,
            checks = checks.toList(),
            backend = invocation.backend,
            bindPort = bindPort,
            initialGeneration = generation,
            initialControlEpoch = controlEpoch,
            suite = invocation.suite,
            topology = TOPOLOGY_ADB_REVERSE,
            networkSwitchProven = false,
            phase = invocation.phase,
            processId = Process.myPid(),
        )
    }

    private suspend fun awaitStreamsClosed(
        container: io.github.ycfeng.ocdeck.app.AppContainer,
        serverId: String,
    ) {
        awaitProject(container.openCodeStore.observeProject(serverId, PROJECT_DIRECTORY)) { state ->
            state.connectionStatus == SseConnectionStatus.Closed
        }
        withTimeout(STATE_TIMEOUT_MILLIS) {
            container.openCodeStore.observeGlobalConnection(serverId).first { it == SseConnectionStatus.Closed }
        }
    }

    private suspend fun deleteProcessServers(
        container: io.github.ycfeng.ocdeck.app.AppContainer,
        preferred: ServerConfig?,
    ) {
        val persisted = container.serverRepository.observeServers().first()
        (listOfNotNull(preferred) + persisted.filter { it.name == SERVER_NAME })
            .distinctBy(ServerConfig::id)
            .forEach { container.serverRepository.deleteServer(it) }
        if (container.serverRepository.observeServers().first().any { it.name == SERVER_NAME }) {
            throw AppE2eAssertionException()
        }
    }

    private suspend fun awaitProject(
        flow: Flow<OpenCodeProjectState>,
        predicate: (OpenCodeProjectState) -> Boolean,
    ): OpenCodeProjectState = withTimeout(STATE_TIMEOUT_MILLIS) { flow.first(predicate) }

    private suspend fun reserveLoopbackPort(): Int = withContext(Dispatchers.IO) {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(IPV4_LOOPBACK, 0), 1)
            socket.localPort
        }
    }

    private suspend fun awaitPortReleased(port: Int) = withTimeout(PORT_RELEASE_TIMEOUT_MILLIS) {
        while (true) {
            val released = withContext(Dispatchers.IO) {
                try {
                    ServerSocket().use { socket ->
                        // Active listeners still prevent the bind, while reuse avoids TIME_WAIT false positives.
                        socket.reuseAddress = true
                        socket.bind(InetSocketAddress(IPV4_LOOPBACK, port), 1)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (released) return@withTimeout
            delay(PORT_RELEASE_POLL_MILLIS)
        }
    }

    private fun OpenCodeProjectState.sessionTitle(id: String): String? =
        sessions.firstOrNull { it.id == id }?.title

    private fun OpenCodeProjectState.hasGenerationOneData(): Boolean =
        sessionTitle(SESSION_SNAPSHOT) == "snapshot-1" &&
            sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1" &&
            sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"

    private fun OpenCodeProjectState.hasGenerationTwoData(): Boolean =
        sessionTitle(SESSION_SNAPSHOT) == "snapshot-2" &&
            sessionTitle(SESSION_PROJECT_SSE) == "project-sse-2"

    private fun OpenCodeProjectState.hasRequiredFixtureData(): Boolean =
        sessionTitle(SESSION_SNAPSHOT) != null &&
            sessionTitle(SESSION_GLOBAL_SSE) != null &&
            sessionTitle(SESSION_PROJECT_SSE) != null

    private suspend fun awaitValidatedTransport(
        context: Context,
        transport: Int,
        externalHost: String? = null,
    ) =
        withTimeout(NETWORK_TRANSPORT_TIMEOUT_MILLIS) {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: throw AppE2eAssertionException()
            val opposite = if (transport == NetworkCapabilities.TRANSPORT_WIFI) {
                NetworkCapabilities.TRANSPORT_CELLULAR
            } else {
                NetworkCapabilities.TRANSPORT_WIFI
            }
            while (true) {
                val network = connectivity.activeNetwork
                val capabilities = network?.let(connectivity::getNetworkCapabilities)
                if (capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    capabilities.hasTransport(transport) &&
                    !capabilities.hasTransport(opposite)
                ) {
                    externalHost?.let { requirePublicResolution(network, it) }
                    return@withTimeout
                }
                delay(MARKER_POLL_MILLIS)
            }
        }

    private fun requirePublicResolution(network: android.net.Network, host: String) {
        if (!PublicEndpointValidator.isValidPublicEndpointSyntax(host)) throw AppE2eAssertionException()
        if (IPV4_LITERAL.matches(host) || ':' in host) return
        val addresses = try {
            network.getAllByName(host)
        } catch (_: Exception) {
            throw AppE2eAssertionException()
        }
        if (!PublicEndpointValidator.areAllResolvedAddressesPublic(addresses)) throw AppE2eAssertionException()
    }

    private fun launchMainActivity(instrumentation: Instrumentation, serverId: String) {
        val intent = Intent().apply {
            setClassName(instrumentation.targetContext.packageName, MAIN_ACTIVITY_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(OpenCodeNotificationTarget.ExtraServerId, serverId)
            putExtra(OpenCodeNotificationTarget.ExtraDirectory, PROJECT_DIRECTORY)
            putExtra(OpenCodeNotificationTarget.ExtraSessionId, SESSION_SNAPSHOT)
        }
        instrumentation.targetContext.startActivity(intent)
    }

    private fun pressHome(instrumentation: Instrumentation) {
        readShellOutput(instrumentation, "input keyevent KEYCODE_HOME")
    }

    private suspend fun forceDeviceIdle(instrumentation: Instrumentation) {
        readShellOutput(instrumentation, "dumpsys battery unplug")
        readShellOutput(instrumentation, "cmd deviceidle force-idle")
        awaitDeviceIdleState(instrumentation, DEVICE_IDLE)
    }

    private suspend fun restoreDeviceIdle(instrumentation: Instrumentation) {
        readShellOutput(instrumentation, "cmd deviceidle unforce")
        readShellOutput(instrumentation, "dumpsys battery reset")
        readShellOutput(instrumentation, "input keyevent KEYCODE_WAKEUP")
        readShellOutput(instrumentation, "wm dismiss-keyguard")
        awaitDeviceIdleState(instrumentation, DEVICE_ACTIVE)
    }

    private suspend fun awaitDeviceIdleState(instrumentation: Instrumentation, expected: String) =
        withTimeout(DEVICE_IDLE_TIMEOUT_MILLIS) {
            while (true) {
                if (readShellOutput(instrumentation, "cmd deviceidle get deep") == expected) return@withTimeout
                delay(MARKER_POLL_MILLIS)
            }
        }

    private fun validateInvocation(invocation: AppE2eInvocation, runId: String) {
        val expectedBackend = if (BuildConfig.USE_KOTLIN_FRPC_STCP_VISITOR) BACKEND_KOTLIN else BACKEND_GO_MOBILE
        if (invocation.schemaVersion != SCHEMA_VERSION || invocation.runId != runId ||
            invocation.backend != expectedBackend ||
            (invocation.suite == SUITE_NETWORK && invocation.backend != BACKEND_KOTLIN) ||
            invocation.suite !in setOf(SUITE_CORE, SUITE_LIFECYCLE, SUITE_PROCESS, SUITE_NETWORK) ||
            !isValidPhase(invocation.suite, invocation.phase) ||
            !isValidOutage(invocation.suite, invocation.outageMillis) ||
            invocation.frpsHost.isBlank() || invocation.frpsPort !in 1..65_535 || invocation.authToken.isBlank() ||
            !isValidTlsCertificatePin(invocation.suite, invocation.tlsServerCertificateSha256) ||
            invocation.user.isBlank() || invocation.serverUser.isBlank() ||
            invocation.serverName.isBlank() || invocation.secretKey.isBlank() ||
            !isValidWorkload(invocation.suite, invocation.workload)
        ) {
            throw AppE2eAssertionException()
        }
        val markerFiles = invocation.restartMarkers.allFiles
        if (markerFiles.distinct().size != markerFiles.size || markerFiles.any { !SAFE_FILE_NAME.matches(it) }) {
            throw AppE2eAssertionException()
        }
    }

    private inline fun <reified T> readPrivateJson(instrumentation: Instrumentation, fileName: String): T {
        val output = readShellOutput(
            instrumentation,
            "run-as ${instrumentation.context.packageName} cat files/$fileName",
        )
        return JSON.decodeFromString(output)
    }

    private suspend fun awaitPrivateMarker(
        instrumentation: Instrumentation,
        fileName: String,
        expected: String,
    ) = withTimeout(MARKER_TIMEOUT_MILLIS) {
        while (true) {
            val output = readShellOutput(
                instrumentation,
                "run-as ${instrumentation.context.packageName} cat files/$fileName",
            )
            if (output == expected) return@withTimeout
            delay(MARKER_POLL_MILLIS)
        }
    }

    private fun writePrivateMarker(
        instrumentation: Instrumentation,
        fileName: String,
    ) {
        val output = readShellOutput(
            instrumentation,
            "run-as ${instrumentation.context.packageName} touch files/$fileName",
        )
        if (output.isNotEmpty()) throw AppE2eAssertionException()
    }

    private fun readShellOutput(instrumentation: Instrumentation, command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val output = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val buffer = ByteArray(JSON_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAXIMUM_JSON_BYTES) throw AppE2eAssertionException()
                output.write(buffer, 0, count)
            }
            buffer.fill(0)
        }
        return output.toString(Charsets.UTF_8.name()).trim()
    }

    private fun requireSafeRunId(value: String?): String = value
        ?.takeIf { SAFE_RUN_ID.matches(it) }
        ?: throw AppE2eAssertionException()

    private fun requireSafeFileName(value: String?): String = value
        ?.takeIf { SAFE_FILE_NAME.matches(it) && it.endsWith(".json") }
        ?: throw AppE2eAssertionException()

    private fun failedResult(
        runId: String,
        stage: String,
        checks: Set<String>,
        failureCode: String,
    ): AppE2eResult = AppE2eResult(
        schemaVersion = SCHEMA_VERSION,
        runId = runId,
        status = STATUS_FAILED,
        checks = checks.toList(),
        failedStage = stage,
        failureCode = failureCode,
    )

    private fun captureResourceSnapshot(
        updateStage: ((String) -> Unit)? = null,
        threadIdentities: Set<ProcTaskIdentity>? = null,
    ): AppE2eResourceSnapshot {
        val runtime = Runtime.getRuntime()
        val javaHeapBytes = runtime.totalMemory() - runtime.freeMemory()
        updateStage?.invoke(STAGE_RESOURCE_SAMPLE_FD)
        val openFdCount = countResourceEntries(File("/proc/self/fd"))
        updateStage?.invoke(STAGE_RESOURCE_SAMPLE_THREAD)
        val threadCount = threadIdentities?.size ?: countResourceEntries(File("/proc/self/task"))
        updateStage?.invoke(STAGE_RESOURCE_SAMPLE_PSS)
        val pssKiB = Debug.getPss()
        updateStage?.invoke(STAGE_RESOURCE_SAMPLE_VALIDATE)
        return AppE2eResourceSnapshot(
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            processCpuMillis = Process.getElapsedCpuTime(),
            pssKiB = pssKiB,
            javaHeapBytes = javaHeapBytes,
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            openFdCount = openFdCount,
            threadCount = threadCount,
        ).also { snapshot -> validateResourceSnapshot(snapshot, updateStage) }
    }

    private fun countResourceEntries(directory: File): Int {
        repeat(RESOURCE_COUNT_ATTEMPTS) { attempt ->
            try {
                var count = 0
                Files.newDirectoryStream(directory.toPath()).use { entries ->
                    entries.forEach { _ ->
                        count += 1
                        if (count > MAXIMUM_RESOURCE_COUNT) throw AppE2eAssertionException()
                    }
                }
                if (count <= 0) throw AppE2eAssertionException()
                return count
            } catch (failure: AppE2eAssertionException) {
                throw failure
            } catch (_: Exception) {
                if (attempt == RESOURCE_COUNT_ATTEMPTS - 1) throw AppE2eAssertionException()
                Thread.yield()
            }
        }
        throw AppE2eAssertionException()
    }

    private fun buildResourceEvidence(
        start: AppE2eResourceSnapshot,
        visitorReady: AppE2eResourceSnapshot?,
        end: AppE2eResourceSnapshot,
        samples: ResourceSampleCollector,
        quiescence: AppE2eResourceQuiescence?,
        threadLifecycle: AppE2eThreadLifecycle?,
    ): AppE2eResourceEvidence {
        validateResourceSnapshot(start)
        validateResourceSnapshot(end)
        val wallDurationMillis = end.elapsedRealtimeMillis - start.elapsedRealtimeMillis
        val cpuDurationMillis = end.processCpuMillis - start.processCpuMillis
        if (wallDurationMillis < 0L || cpuDurationMillis < 0L || cpuDurationMillis > wallDurationMillis * 64L) {
            throw AppE2eAssertionException()
        }
        return AppE2eResourceEvidence(
            start = start,
            visitorReady = visitorReady,
            end = end,
            wallDurationMillis = wallDurationMillis,
            cpuDurationMillis = cpuDurationMillis,
            samples = samples.values(),
            droppedSampleCount = samples.droppedCount,
            sampleRetention = samples.retention(),
            quiescence = quiescence,
            threadLifecycle = threadLifecycle,
        )
    }

    private fun captureThreadIdentities(): Set<ProcTaskIdentity> = captureThreadIdentities(
        listTaskIds = {
            buildList {
                Files.newDirectoryStream(File("/proc/self/task").toPath()).use { entries ->
                    entries.forEach { entry ->
                        if (size >= THREAD_LIFECYCLE_CAPTURE_LIMIT) throw AppE2eAssertionException()
                        add(entry.fileName.toString())
                    }
                }
            }
        },
        readStat = { tid ->
            Files.newInputStream(File("/proc/self/task/$tid/stat").toPath()).use { input ->
                input.readAtMost(MAXIMUM_PROC_STAT_BYTES + 1)
            }
        },
    )

    private fun InputStream.readAtMost(maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(maximumBytes)
        val buffer = ByteArray(minOf(256, maximumBytes))
        while (output.size() < maximumBytes) {
            val count = read(buffer, 0, minOf(buffer.size, maximumBytes - output.size()))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun captureThreadIdentities(
        listTaskIds: () -> List<String>,
        readStat: (String) -> ByteArray,
    ): Set<ProcTaskIdentity> {
        repeat(THREAD_LIFECYCLE_CAPTURE_ATTEMPTS) { attempt ->
            try {
                val taskIds = listTaskIds()
                if (taskIds.isEmpty() || taskIds.size > THREAD_LIFECYCLE_CAPTURE_LIMIT) {
                    throw AppE2eAssertionException()
                }
                return taskIds.mapTo(linkedSetOf()) { value ->
                    val tid = value.toIntOrNull()?.takeIf { it > 0 } ?: throw AppE2eAssertionException()
                    ProcTaskIdentity(tid, parseProcTaskStartTime(readStat(value)))
                }.also { identities ->
                    if (identities.size != taskIds.size) throw AppE2eAssertionException()
                }
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                if (attempt == THREAD_LIFECYCLE_CAPTURE_ATTEMPTS - 1) throw AppE2eAssertionException()
                Thread.yield()
            }
        }
        throw AppE2eAssertionException()
    }

    private fun parseProcTaskStartTime(statBytes: ByteArray): Long {
        if (statBytes.isEmpty() || statBytes.size > MAXIMUM_PROC_STAT_BYTES) throw AppE2eAssertionException()
        val stat = statBytes.toString(Charsets.UTF_8)
        val commandEnd = stat.lastIndexOf(')')
        if (commandEnd < 0 || commandEnd + 1 >= stat.length) throw AppE2eAssertionException()
        val fieldsFromThree = stat.substring(commandEnd + 1).trim().split(Regex("\\s+"))
        return fieldsFromThree.getOrNull(PROC_STAT_STARTTIME_INDEX_FROM_FIELD_THREE)
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
            ?: throw AppE2eAssertionException()
    }

    private fun buildThreadLifecycleEvidence(
        start: Set<ProcTaskIdentity>,
        visitorReady: Set<ProcTaskIdentity>,
        postCleanup: Set<ProcTaskIdentity>,
        quiescence: Set<ProcTaskIdentity>,
    ): AppE2eThreadLifecycle {
        val histogram = MutableList(THREAD_LIFECYCLE_BUCKET_COUNT) { 0 }
        val union = start + visitorReady + postCleanup + quiescence
        if (union.size > THREAD_LIFECYCLE_CAPTURE_LIMIT) throw AppE2eAssertionException()
        union.forEach { identity ->
            var mask = 0
            if (identity in start) mask = mask or THREAD_PRESENCE_START
            if (identity in visitorReady) mask = mask or THREAD_PRESENCE_VISITOR_READY
            if (identity in postCleanup) mask = mask or THREAD_PRESENCE_POST_CLEANUP
            if (identity in quiescence) mask = mask or THREAD_PRESENCE_QUIESCENCE
            histogram[mask] += 1
        }
        return AppE2eThreadLifecycle(
            schemaVersion = THREAD_LIFECYCLE_SCHEMA_VERSION,
            classifier = THREAD_LIFECYCLE_CLASSIFIER,
            captureLimit = THREAD_LIFECYCLE_CAPTURE_LIMIT,
            presenceHistogram = histogram,
        )
    }

    private fun isValidWorkload(suite: String, workload: AppE2eWorkload): Boolean {
        if (workload.profile !in setOf(PROFILE_FUNCTIONAL, PROFILE_BASELINE, PROFILE_STRESS, PROFILE_LEAK, PROFILE_SOAK) ||
            workload.iterations !in 0..MAXIMUM_WORKLOAD_ITERATIONS ||
            workload.concurrency !in 1..MAXIMUM_WORKLOAD_CONCURRENCY ||
            workload.reconnectEveryIterations !in 0..maxOf(1, workload.iterations) ||
            workload.sampleEveryIterations !in 1..maxOf(1, workload.iterations)
        ) {
            return false
        }
        if (workload.profile == PROFILE_FUNCTIONAL) {
            return workload == AppE2eWorkload.functional()
        }
        if (suite != SUITE_CORE || workload.iterations <= 0) return false
        return if (workload.profile == PROFILE_SOAK) {
            workload.durationMillis in MINIMUM_SOAK_MILLIS..MAXIMUM_SOAK_MILLIS
        } else {
            workload.durationMillis == 0L &&
                (workload.profile != PROFILE_LEAK || workload.reconnectEveryIterations > 0)
        }
    }

    private fun scenarioTimeoutMillis(workload: AppE2eWorkload): Long {
        val workloadBudget = if (workload.profile == PROFILE_SOAK) {
            workload.durationMillis
        } else {
            workload.iterations.toLong() * ITERATION_TIMEOUT_BUDGET_MILLIS
        }
        return (SCENARIO_TIMEOUT_MILLIS + workloadBudget).coerceAtMost(MAXIMUM_SCENARIO_TIMEOUT_MILLIS)
    }

    private fun validateResourceSnapshot(
        snapshot: AppE2eResourceSnapshot,
        updateStage: ((String) -> Unit)? = null,
    ) {
        updateStage?.invoke(STAGE_RESOURCE_SAMPLE_TIME)
        if (snapshot.elapsedRealtimeMillis < 0L || snapshot.processCpuMillis < 0L) {
            throw AppE2eAssertionException()
        }
        updateStage?.invoke(STAGE_RESOURCE_SAMPLE_MEMORY)
        if (snapshot.pssKiB < 0L || snapshot.javaHeapBytes < 0L || snapshot.nativeHeapBytes < 0L) {
            throw AppE2eAssertionException()
        }
        updateStage?.invoke(STAGE_RESOURCE_SAMPLE_COUNT)
        if (snapshot.openFdCount !in 1..MAXIMUM_RESOURCE_COUNT ||
            snapshot.threadCount !in 1..MAXIMUM_RESOURCE_COUNT
        ) {
            throw AppE2eAssertionException()
        }
    }

    private fun throwSelected(vararg failures: Throwable?) {
        failures.firstOrNull { it is Error }?.let { throw it }
        failures.firstOrNull { it is CancellationException }?.let { throw it }
        failures.firstOrNull()?.let { throw it }
    }

    private class CleanupCollector {
        private val failures = mutableListOf<Throwable>()

        suspend fun run(block: suspend () -> Unit): Boolean = try {
            block()
            true
        } catch (failure: Throwable) {
            failures += failure
            false
        }

        fun failure(): Throwable? = failures.firstOrNull { it is Error }
            ?: failures.firstOrNull { it is CancellationException }
            ?: failures.firstOrNull()
    }

    private class ResourceSampleCollector {
        private val head = mutableListOf<AppE2eResourceSnapshot>()
        private val tail = mutableListOf<AppE2eResourceSnapshot>()
        private var observedCount = 0
        var droppedCount: Int = 0
            private set

        fun add(snapshot: AppE2eResourceSnapshot) {
            observedCount += 1
            val retainedSnapshot = snapshot.copy(sampleOrdinal = observedCount)
            if (head.size < RESOURCE_HEAD_SAMPLES) {
                head += retainedSnapshot
            } else {
                if (tail.size == RESOURCE_TAIL_SAMPLES) {
                    tail.removeAt(0)
                    droppedCount += 1
                }
                tail += retainedSnapshot
            }
        }

        fun values(): List<AppE2eResourceSnapshot> = head + tail

        fun retention(): AppE2eResourceSampleRetention = AppE2eResourceSampleRetention(
            strategy = RESOURCE_SAMPLE_RETENTION_STRATEGY,
            observedSampleCount = observedCount,
            retainedHeadSampleCount = head.size,
            retainedTailSampleCount = tail.size,
        )
    }

    private class AppE2eAssertionException : Exception()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val SAFE_RUN_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val SAFE_FILE_NAME = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        const val SCHEMA_VERSION = 12
        const val ARG_ENABLED = "ocdeck.appE2e.enabled"
        const val ARG_RUN_ID = "ocdeck.appE2e.runId"
        const val ARG_CONFIG_FILE = "ocdeck.appE2e.configFile"
        const val ARG_RUNTIME_DIAGNOSTIC_ENABLED = "ocdeck.appE2e.runtimeDiagnostic.enabled"
        const val STATUS_RESULT_KEY = "ocdeckAppE2eResult"
        const val MAIN_ACTIVITY_CLASS = "io.github.ycfeng.ocdeck.MainActivity"
        const val BACKEND_GO_MOBILE = "gomobile"
        const val BACKEND_KOTLIN = "kotlin"
        const val SUITE_CORE = "core"
        const val SUITE_LIFECYCLE = "lifecycle"
        const val SUITE_PROCESS = "process"
        const val SUITE_NETWORK = "network"
        const val PHASE_SINGLE = "single"
        const val PHASE_PROCESS_PREPARE = "process_prepare"
        const val PHASE_PROCESS_RECOVER = "process_recover"
        const val TOPOLOGY_ADB_REVERSE = "adb_reverse"
        const val TOPOLOGY_EXTERNAL = "external"
        const val PROJECT_DIRECTORY = "/workspace"
        const val SESSION_SNAPSHOT = "session-snapshot"
        const val SESSION_GLOBAL_SSE = "session-global-sse"
        const val SESSION_PROJECT_SSE = "session-project-sse"
        const val SERVER_NAME = "STCP App E2E"
        const val STATUS_PASSED = "passed"
        const val STATUS_FAILED = "failed"
        const val FAILURE_TIMEOUT = "TIMEOUT"
        const val FAILURE_ASSERTION = "ASSERTION"
        const val FAILURE_UNEXPECTED = "UNEXPECTED"
        const val STAGE_CONFIGURATION = "configuration"
        const val STAGE_RUNTIME_DIAGNOSTIC_INITIALIZE = "runtime_diagnostic_initialize"
        const val STAGE_RUNTIME_DIAGNOSTIC_SAMPLE = "runtime_diagnostic_sample"
        const val STAGE_SERVER_CONFIGURATION = "server_configuration"
        const val STAGE_STCP_READY = "stcp_ready"
        const val STAGE_SNAPSHOT = "snapshot"
        const val STAGE_GLOBAL_SSE = "global_sse"
        const val STAGE_PROJECT_SSE = "project_sse"
        const val STAGE_FOREGROUND_RECONCILIATION = "foreground_reconciliation"
        const val STAGE_ACTIVITY_FOREGROUND = "activity_foreground"
        const val STAGE_ACTIVITY_CYCLES = "activity_cycles"
        const val STAGE_DOZE_ENTER = "doze_enter"
        const val STAGE_DOZE_EXIT = "doze_exit"
        const val STAGE_RESTART_DISCONNECT = "restart_disconnect"
        const val STAGE_RESTART_RECOVERY = "restart_recovery"
        const val STAGE_RESTART_RECOVERY_MARKER = "restart_recovery_marker"
        const val STAGE_RESTART_RECOVERY_GLOBAL = "restart_recovery_global"
        const val STAGE_RESTART_RECOVERY_PROJECT_OPEN = "restart_recovery_project_open"
        const val STAGE_RESTART_RECOVERY_PROJECT_SSE = "restart_recovery_project_sse"
        const val STAGE_RESTART_RECOVERY_SNAPSHOT = "restart_recovery_snapshot"
        const val STAGE_RESTART_RECOVERY_GLOBAL_DATA = "restart_recovery_global_data"
        const val STAGE_RESTART_RECOVERY_IDENTITY = "restart_recovery_identity"
        const val STAGE_RESTART_RECOVERY_GENERATION = "restart_recovery_generation"
        const val STAGE_RESTART_RECOVERY_EPOCH = "restart_recovery_epoch"
        const val STAGE_RESTART_RECOVERY_TRANSPORT = "restart_recovery_transport"
        const val STAGE_POST_RESTART_FOREGROUND = "post_restart_foreground"
        const val STAGE_CLEANUP = "cleanup"
        const val STAGE_PROCESS_SERVER_PERSIST = "process_server_persist"
        const val STAGE_PROCESS_INITIAL_CONNECTION = "process_initial_connection"
        const val STAGE_PROCESS_INITIAL_SNAPSHOT = "process_initial_snapshot"
        const val STAGE_PROCESS_INITIAL_STREAMS = "process_initial_streams"
        const val STAGE_PROCESS_INITIAL_ACTIVITY = "process_initial_activity"
        const val STAGE_PROCESS_PERSISTED_LOAD = "process_persisted_load"
        const val STAGE_PROCESS_RECONNECTED = "process_reconnected"
        const val STAGE_PROCESS_RESTORED_SNAPSHOT = "process_restored_snapshot"
        const val STAGE_PROCESS_RESTORED_STREAMS = "process_restored_streams"
        const val STAGE_PROCESS_RESTORED_ACTIVITY = "process_restored_activity"
        const val STAGE_NETWORK_INITIAL_WIFI = "network_initial_wifi"
        const val STAGE_NETWORK_CELLULAR_WAIT = "network_cellular_wait"
        const val STAGE_NETWORK_CELLULAR_RETENTION = "network_cellular_retention"
        const val STAGE_NETWORK_CELLULAR_RECOVERY = "network_cellular_recovery"
        const val STAGE_NETWORK_WIFI_WAIT = "network_wifi_wait"
        const val STAGE_NETWORK_WIFI_RETENTION = "network_wifi_retention"
        const val STAGE_NETWORK_WIFI_RECOVERY = "network_wifi_recovery"
        const val STAGE_RESOURCE_WORKLOAD = "resource_workload"
        const val STAGE_RESOURCE_SNAPSHOT = "resource_snapshot"
        const val STAGE_RESOURCE_SNAPSHOT_IDENTITY = "resource_snapshot_identity"
        const val STAGE_RESOURCE_DISCONNECT = "resource_disconnect"
        const val STAGE_RESOURCE_RECONNECT = "resource_reconnect"
        const val STAGE_RESOURCE_GLOBAL_REOPEN = "resource_global_reopen"
        const val STAGE_RESOURCE_PROJECT_REOPEN = "resource_project_reopen"
        const val STAGE_RESOURCE_SAMPLE = "resource_sample"
        const val STAGE_RESOURCE_SAMPLE_FD = "resource_sample_fd"
        const val STAGE_RESOURCE_SAMPLE_THREAD = "resource_sample_thread"
        const val STAGE_RESOURCE_SAMPLE_PSS = "resource_sample_pss"
        const val STAGE_RESOURCE_SAMPLE_VALIDATE = "resource_sample_validate"
        const val STAGE_RESOURCE_SAMPLE_TIME = "resource_sample_time"
        const val STAGE_RESOURCE_SAMPLE_MEMORY = "resource_sample_memory"
        const val STAGE_RESOURCE_SAMPLE_COUNT = "resource_sample_count"
        const val STAGE_RESOURCE_POST_WORKLOAD_CONNECTION = "resource_post_workload_connection"
        const val STAGE_RESOURCE_POST_WORKLOAD_IDENTITY = "resource_post_workload_identity"
        const val STAGE_RESOURCE_POST_WORKLOAD_HEALTH = "resource_post_workload_health"
        const val CHECK_KOTLIN_BACKEND = "kotlin_backend"
        const val CHECK_GO_MOBILE_BACKEND = "gomobile_backend"
        const val CHECK_STCP_READY = "stcp_ready"
        const val CHECK_SNAPSHOT_STORE = "snapshot_store"
        const val CHECK_GLOBAL_SSE_STORE = "global_sse_store"
        const val CHECK_PROJECT_SSE_STORE = "project_sse_store"
        const val CHECK_FOREGROUND_RECONCILIATION = "foreground_reconciliation"
        const val CHECK_ACTIVITY_FOREGROUND = "activity_foreground"
        const val CHECK_ACTIVITY_BACKGROUND_DATA_RETAINED = "activity_background_data_retained"
        const val CHECK_ACTIVITY_FOREGROUND_RECONCILIATION = "activity_foreground_reconciliation"
        const val CHECK_DOZE_ENTERED = "doze_entered"
        const val CHECK_DOZE_EXIT_RECOVERY = "doze_exit_recovery"
        const val CHECK_DISCONNECT_DATA_RETAINED = "disconnect_data_retained"
        const val CHECK_LONG_OUTAGE_DATA_RETAINED = "long_outage_data_retained"
        const val CHECK_PROVIDER_RACE_DATA_RETAINED = "provider_race_data_retained"
        const val CHECK_CONTROL_EPOCH_ADVANCED = "control_epoch_advanced"
        const val CHECK_TRANSPORT_IDENTITY_ADVANCED = "transport_identity_advanced"
        const val CHECK_RESTART_RECONCILIATION = "restart_reconciliation"
        const val CHECK_POST_RESTART_FOREGROUND = "post_restart_foreground"
        const val CHECK_STREAMS_CLOSED = "streams_closed"
        const val CHECK_VISITOR_CLOSED = "visitor_closed"
        const val CHECK_PORT_RELEASED = "port_released"
        const val CHECK_SERVER_PERSISTED = "server_persisted"
        const val CHECK_INITIAL_STREAMS = "initial_streams"
        const val CHECK_INITIAL_ACTIVITY_ROUTE = "initial_activity_route"
        const val CHECK_RUNTIME_CLOSED = "runtime_closed"
        const val CHECK_PERSISTED_SERVER_LOADED = "persisted_server_loaded"
        const val CHECK_STCP_RECONNECTED = "stcp_reconnected"
        const val CHECK_SNAPSHOT_RESTORED = "snapshot_restored"
        const val CHECK_STREAMS_RESTORED = "streams_restored"
        const val CHECK_ACTIVITY_ROUTE_RESTORED = "activity_route_restored"
        const val CHECK_SERVER_DELETED = "server_deleted"
        const val CHECK_INITIAL_WIFI_VALIDATED = "initial_wifi_validated"
        const val CHECK_CELLULAR_VALIDATED = "cellular_validated"
        const val CHECK_CELLULAR_DATA_RETAINED = "cellular_data_retained"
        const val CHECK_CELLULAR_EPOCH_ADVANCED = "cellular_epoch_advanced"
        const val CHECK_CELLULAR_RECONCILIATION = "cellular_reconciliation"
        const val CHECK_WIFI_RESTORED_VALIDATED = "wifi_restored_validated"
        const val CHECK_WIFI_RETURN_DATA_RETAINED = "wifi_return_data_retained"
        const val CHECK_WIFI_RETURN_EPOCH_ADVANCED = "wifi_return_epoch_advanced"
        const val CHECK_WIFI_RETURN_RECONCILIATION = "wifi_return_reconciliation"
        val CORE_EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_STCP_READY,
            CHECK_SNAPSHOT_STORE,
            CHECK_GLOBAL_SSE_STORE,
            CHECK_PROJECT_SSE_STORE,
            CHECK_FOREGROUND_RECONCILIATION,
            CHECK_DISCONNECT_DATA_RETAINED,
            CHECK_CONTROL_EPOCH_ADVANCED,
            CHECK_RESTART_RECONCILIATION,
            CHECK_POST_RESTART_FOREGROUND,
            CHECK_STREAMS_CLOSED,
            CHECK_VISITOR_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val LIFECYCLE_EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_STCP_READY,
            CHECK_SNAPSHOT_STORE,
            CHECK_GLOBAL_SSE_STORE,
            CHECK_PROJECT_SSE_STORE,
            CHECK_ACTIVITY_FOREGROUND,
            CHECK_ACTIVITY_BACKGROUND_DATA_RETAINED,
            CHECK_ACTIVITY_FOREGROUND_RECONCILIATION,
            CHECK_DOZE_ENTERED,
            CHECK_DOZE_EXIT_RECOVERY,
            CHECK_DISCONNECT_DATA_RETAINED,
            CHECK_PROVIDER_RACE_DATA_RETAINED,
            CHECK_LONG_OUTAGE_DATA_RETAINED,
            CHECK_CONTROL_EPOCH_ADVANCED,
            CHECK_RESTART_RECONCILIATION,
            CHECK_POST_RESTART_FOREGROUND,
            CHECK_STREAMS_CLOSED,
            CHECK_VISITOR_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val PROCESS_PREPARE_EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_SERVER_PERSISTED,
            CHECK_STCP_READY,
            CHECK_SNAPSHOT_STORE,
            CHECK_INITIAL_STREAMS,
            CHECK_INITIAL_ACTIVITY_ROUTE,
            CHECK_RUNTIME_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val PROCESS_RECOVER_EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_PERSISTED_SERVER_LOADED,
            CHECK_STCP_RECONNECTED,
            CHECK_SNAPSHOT_RESTORED,
            CHECK_STREAMS_RESTORED,
            CHECK_ACTIVITY_ROUTE_RESTORED,
            CHECK_STREAMS_CLOSED,
            CHECK_SERVER_DELETED,
            CHECK_VISITOR_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val NETWORK_EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_INITIAL_WIFI_VALIDATED,
            CHECK_STCP_READY,
            CHECK_SNAPSHOT_STORE,
            CHECK_GLOBAL_SSE_STORE,
            CHECK_PROJECT_SSE_STORE,
            CHECK_CELLULAR_VALIDATED,
            CHECK_CELLULAR_DATA_RETAINED,
            CHECK_CELLULAR_EPOCH_ADVANCED,
            CHECK_CELLULAR_RECONCILIATION,
            CHECK_WIFI_RESTORED_VALIDATED,
            CHECK_WIFI_RETURN_DATA_RETAINED,
            CHECK_WIFI_RETURN_EPOCH_ADVANCED,
            CHECK_WIFI_RETURN_RECONCILIATION,
            CHECK_STREAMS_CLOSED,
            CHECK_VISITOR_CLOSED,
            CHECK_PORT_RELEASED,
        )
        fun backendCheck(backend: String): String = when (backend) {
            BACKEND_GO_MOBILE -> CHECK_GO_MOBILE_BACKEND
            BACKEND_KOTLIN -> CHECK_KOTLIN_BACKEND
            else -> throw AppE2eAssertionException()
        }
        fun expectedProcessChecks(checks: Set<String>, backend: String): Set<String> =
            checks.mapTo(linkedSetOf()) { check ->
                when {
                    check == CHECK_KOTLIN_BACKEND -> backendCheck(backend)
                    backend == BACKEND_GO_MOBILE && check == CHECK_CONTROL_EPOCH_ADVANCED ->
                        CHECK_TRANSPORT_IDENTITY_ADVANCED
                    else -> check
                }
            }
        fun expectedChecks(suite: String, backend: String): Set<String> = expectedProcessChecks(when (suite) {
            SUITE_CORE -> CORE_EXPECTED_CHECKS
            SUITE_LIFECYCLE -> LIFECYCLE_EXPECTED_CHECKS
            SUITE_NETWORK -> NETWORK_EXPECTED_CHECKS
            else -> throw AppE2eAssertionException()
        }, backend)
        fun isValidPhase(suite: String, phase: String): Boolean = when (suite) {
            SUITE_CORE, SUITE_LIFECYCLE, SUITE_NETWORK -> phase == PHASE_SINGLE
            SUITE_PROCESS -> phase in setOf(PHASE_PROCESS_PREPARE, PHASE_PROCESS_RECOVER)
            else -> false
        }
        fun isValidOutage(suite: String, outageMillis: Long): Boolean =
            if (suite == SUITE_NETWORK) outageMillis == 0L
            else outageMillis in MINIMUM_OUTAGE_MILLIS..MAXIMUM_OUTAGE_MILLIS

        fun topologyFor(suite: String): String =
            if (suite == SUITE_NETWORK) TOPOLOGY_EXTERNAL else TOPOLOGY_ADB_REVERSE
        fun isValidTlsCertificatePin(suite: String, pin: String?): Boolean =
            if (suite == SUITE_NETWORK) pin != null && TLS_SERVER_CERTIFICATE_SHA256.matches(pin)
            else pin == null
        val TLS_SERVER_CERTIFICATE_SHA256 = Regex("[0-9a-f]{64}")
        val IPV4_LITERAL = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
        const val MAXIMUM_JSON_BYTES = 64 * 1024
        const val JSON_BUFFER_BYTES = 8 * 1024
        const val SCENARIO_TIMEOUT_MILLIS = 600_000L
        const val ITERATION_TIMEOUT_BUDGET_MILLIS = 30_000L
        const val MAXIMUM_SOAK_MILLIS = 86_400_000L
        const val MAXIMUM_SCENARIO_TIMEOUT_MILLIS = MAXIMUM_SOAK_MILLIS + SCENARIO_TIMEOUT_MILLIS
        const val STATE_TIMEOUT_MILLIS = 45_000L
        const val RECOVERY_TIMEOUT_MILLIS = 90_000L
        const val MARKER_TIMEOUT_MILLIS = 360_000L
        const val MARKER_POLL_MILLIS = 100L
        const val PORT_RELEASE_TIMEOUT_MILLIS = 30_000L
        const val PORT_RELEASE_POLL_MILLIS = 100L
        const val MARKER_FRPS_RESTARTED = "frps_restarted"
        const val MARKER_FRPS_ONLY = "frps_only"
        const val MARKER_NETWORK_CELLULAR_DISCONNECTED = "network_cellular_disconnected"
        const val MARKER_NETWORK_CELLULAR_RECOVERED = "network_cellular_recovered"
        const val MARKER_NETWORK_WIFI_DISCONNECTED = "network_wifi_disconnected"
        const val MARKER_NETWORK_WIFI_RECOVERED = "network_wifi_recovered"
        const val DEVICE_IDLE = "IDLE"
        const val DEVICE_ACTIVE = "ACTIVE"
        const val DEVICE_IDLE_TIMEOUT_MILLIS = 30_000L
        const val DOZE_HOLD_MILLIS = 5_000L
        const val PROVIDER_DOWN_ASSERTION_MILLIS = 5_000L
        const val LIFECYCLE_CYCLE_COUNT = 3
        const val MINIMUM_OUTAGE_MILLIS = 60_000L
        const val MAXIMUM_OUTAGE_MILLIS = 300_000L
        const val NETWORK_TRANSPORT_TIMEOUT_MILLIS = 90_000L
        const val MAXIMUM_RESOURCE_COUNT = 16_384
        const val THREAD_LIFECYCLE_SCHEMA_VERSION = 1
        const val THREAD_LIFECYCLE_CLASSIFIER = "proc-task-lifecycle-v1"
        const val THREAD_LIFECYCLE_CAPTURE_LIMIT = 16_384
        const val THREAD_LIFECYCLE_BUCKET_COUNT = 16
        const val THREAD_LIFECYCLE_CAPTURE_ATTEMPTS = 3
        const val MAXIMUM_PROC_STAT_BYTES = 512
        const val PROC_STAT_STARTTIME_INDEX_FROM_FIELD_THREE = 19
        const val THREAD_PRESENCE_START = 1
        const val THREAD_PRESENCE_VISITOR_READY = 2
        const val THREAD_PRESENCE_POST_CLEANUP = 4
        const val THREAD_PRESENCE_QUIESCENCE = 8
        const val MAXIMUM_RESOURCE_SAMPLES = 254
        const val RESOURCE_HEAD_SAMPLES = MAXIMUM_RESOURCE_SAMPLES / 2
        const val RESOURCE_TAIL_SAMPLES = MAXIMUM_RESOURCE_SAMPLES - RESOURCE_HEAD_SAMPLES
        const val RESOURCE_SAMPLE_RETENTION_STRATEGY = "head-tail-v1"
        const val RESOURCE_QUIESCENCE_MILLIS = 90_000L
        const val RUNTIME_DIAGNOSTIC_QUIESCENCE_MILLIS = 90_000L
        const val RESOURCE_COUNT_ATTEMPTS = 3
        const val MAXIMUM_WORKLOAD_ITERATIONS = 100_000
        const val MAXIMUM_WORKLOAD_CONCURRENCY = 32
        const val MINIMUM_SOAK_MILLIS = 60_000L
        const val PROFILE_FUNCTIONAL = "functional"
        const val PROFILE_BASELINE = "baseline"
        const val PROFILE_STRESS = "stress"
        const val PROFILE_LEAK = "leak"
        const val PROFILE_SOAK = "soak"
    }
}

@Serializable
private data class AppE2eInvocation(
    val schemaVersion: Int,
    val runId: String,
    val backend: String,
    val suite: String,
    val phase: String,
    val outageMillis: Long,
    val frpsHost: String,
    val frpsPort: Int,
    val authToken: String,
    val tlsServerCertificateSha256: String?,
    val user: String,
    val serverUser: String,
    val serverName: String,
    val secretKey: String,
    val restartMarkers: AppE2eRestartMarkers,
    val workload: AppE2eWorkload,
) {
    override fun toString(): String =
        "AppE2eInvocation(schemaVersion=$schemaVersion, runId=<redacted>, backend=$backend, suite=$suite, phase=$phase, " +
            "outageMillis=$outageMillis, frpsHost=<redacted>, frpsPort=$frpsPort, " +
            "authToken=<redacted>, tlsServerCertificateSha256=" +
            "${if (tlsServerCertificateSha256 == null) "null" else "<redacted>"}, " +
            "user=<redacted>, serverUser=<redacted>, " +
            "serverName=<redacted>, secretKey=<redacted>, workload=$workload)"
}

@Serializable
private data class AppE2eWorkload(
    val profile: String,
    val iterations: Int,
    val concurrency: Int,
    val durationMillis: Long,
    val reconnectEveryIterations: Int,
    val sampleEveryIterations: Int,
) {
    companion object {
        fun functional(): AppE2eWorkload = AppE2eWorkload(
            profile = "functional",
            iterations = 0,
            concurrency = 1,
            durationMillis = 0L,
            reconnectEveryIterations = 0,
            sampleEveryIterations = 1,
        )
    }
}

@Serializable
private data class AppE2eWorkloadResult(
    val profile: String,
    val completedIterations: Int,
    val reconnectCount: Int,
)

@Serializable
private data class AppE2eRestartMarkers(
    val deviceReadyFile: String,
    val deviceDisconnectedFile: String,
    val frpsOnlyFile: String,
    val providerWindowObservedFile: String,
    val frpsRestartedFile: String,
    val wifiDisconnectedFile: String,
    val wifiRetentionObservedFile: String,
    val wifiRecoveredFile: String,
    val wifiRecoveryObservedFile: String,
) {
    val allFiles: List<String>
        get() = listOf(
            deviceReadyFile,
            deviceDisconnectedFile,
            frpsOnlyFile,
            providerWindowObservedFile,
            frpsRestartedFile,
            wifiDisconnectedFile,
            wifiRetentionObservedFile,
            wifiRecoveredFile,
            wifiRecoveryObservedFile,
        )

    override fun toString(): String = "AppE2eRestartMarkers(configured=true)"
}

@Serializable
private data class AppE2eResult(
    val schemaVersion: Int,
    val runId: String,
    val status: String,
    val checks: List<String>,
    val backend: String? = null,
    val bindPort: Int? = null,
    val initialGeneration: Long? = null,
    val initialControlEpoch: Long? = null,
    val recoveredGeneration: Long? = null,
    val recoveredControlEpoch: Long? = null,
    val suite: String? = null,
    val topology: String? = null,
    val networkSwitchProven: Boolean? = null,
    val phase: String? = null,
    val processId: Int? = null,
    val failedStage: String? = null,
    val failureCode: String? = null,
    val runtimeInitialization: AppE2eRuntimeInitialization? = null,
    val resources: AppE2eResourceEvidence? = null,
    val workload: AppE2eWorkloadResult? = null,
) {
    override fun toString(): String =
        "AppE2eResult(schemaVersion=$schemaVersion, runId=<redacted>, status=$status, backend=$backend, " +
            "checkCount=${checks.size}, bindPort=$bindPort, initialGeneration=$initialGeneration, " +
            "initialControlEpoch=$initialControlEpoch, recoveredGeneration=$recoveredGeneration, " +
            "recoveredControlEpoch=$recoveredControlEpoch, suite=$suite, topology=$topology, " +
            "networkSwitchProven=$networkSwitchProven, phase=$phase, processPresent=${processId != null}, " +
            "failedStage=$failedStage, failureCode=$failureCode, " +
            "runtimeInitializationPresent=${runtimeInitialization != null}, resourcesPresent=${resources != null}, " +
            "workloadProfile=${workload?.profile})"
}

@Serializable
private data class AppE2eRuntimeInitialization(
    val requestedDelayMillis: Long,
    val coldThreadCount: Int,
    val initializedThreadCount: Int,
    val initializedGoroutineCount: Int? = null,
    val initializedSessionCount: Int? = null,
    val initializedOwnerCounts: Map<String, Long> = emptyMap(),
    val initializedRuntimeCounts: Map<String, Long> = emptyMap(),
    val postSessionQuiescenceThreadCount: Int? = null,
    val postSessionQuiescenceGoroutineCount: Int? = null,
    val postSessionQuiescenceSessionCount: Int? = null,
    val postSessionQuiescenceOwnerCounts: Map<String, Long> = emptyMap(),
    val postSessionQuiescenceRuntimeCounts: Map<String, Long> = emptyMap(),
)

@Serializable
private data class AppE2eResourceSnapshot(
    val elapsedRealtimeMillis: Long,
    val processCpuMillis: Long,
    val pssKiB: Long,
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long,
    val openFdCount: Int,
    val threadCount: Int,
    val sampleOrdinal: Int? = null,
)

@Serializable
private data class AppE2eResourceSampleRetention(
    val strategy: String,
    val observedSampleCount: Int,
    val retainedHeadSampleCount: Int,
    val retainedTailSampleCount: Int,
)

@Serializable
private data class AppE2eResourceQuiescence(
    val requestedDelayMillis: Long,
    val snapshot: AppE2eResourceSnapshot,
)

@Serializable
private data class AppE2eResourceEvidence(
    val start: AppE2eResourceSnapshot,
    val visitorReady: AppE2eResourceSnapshot? = null,
    val end: AppE2eResourceSnapshot,
    val wallDurationMillis: Long,
    val cpuDurationMillis: Long,
    val samples: List<AppE2eResourceSnapshot>,
    val droppedSampleCount: Int,
    val sampleRetention: AppE2eResourceSampleRetention,
    val quiescence: AppE2eResourceQuiescence? = null,
    val threadLifecycle: AppE2eThreadLifecycle? = null,
)

@Serializable
private data class AppE2eThreadLifecycle(
    val schemaVersion: Int,
    val classifier: String,
    val captureLimit: Int,
    val presenceHistogram: List<Int>,
)

private data class ProcTaskIdentity(
    val tid: Int,
    val startTime: Long,
)
