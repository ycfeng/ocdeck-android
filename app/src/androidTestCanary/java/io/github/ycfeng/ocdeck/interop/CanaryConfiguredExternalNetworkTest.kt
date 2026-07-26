package io.github.ycfeng.ocdeck.interop

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ycfeng.ocdeck.BuildConfig
import io.github.ycfeng.ocdeck.CanaryExternalGateActivity
import io.github.ycfeng.ocdeck.OpenCodeApplication
import io.github.ycfeng.ocdeck.core.network.AppConnectionRecoverySnapshot
import io.github.ycfeng.ocdeck.core.network.FrpcStcpRecoveryStage
import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorGeneration
import io.github.ycfeng.ocdeck.core.network.FrpcTlsServerCertificatePinUnavailableException
import io.github.ycfeng.ocdeck.core.network.ProjectCalibrationStage
import io.github.ycfeng.ocdeck.core.network.PublicEndpointValidator
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.core.store.SessionListWindowState
import io.github.ycfeng.ocdeck.core.store.SseConnectionStatus
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.fail
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class CanaryConfiguredExternalNetworkTest {
    private var foregroundActivity: CanaryExternalGateActivity? = null

    @Before
    fun keepTargetApplicationForeground() {
        if (InstrumentationRegistry.getArguments().getString(ARG_ENABLED) != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        foregroundActivity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, CanaryExternalGateActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as CanaryExternalGateActivity
    }

    @After
    fun releaseTargetApplicationForeground() {
        val activity = foregroundActivity ?: return
        foregroundActivity = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish)
    }

    @Test
    fun validateConfiguredExternalNetworkSwitches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARG_ENABLED) == "true")

        var stage = STAGE_CONFIGURATION
        val checks = linkedSetOf<String>()
        val progressLock = Any()
        val latestStage = AtomicReference(stage)
        val latestCheckCount = AtomicInteger(0)
        val heartbeatCount = AtomicInteger(0)
        val heartbeatStopped = AtomicBoolean(false)
        val publishProgress: (String) -> Unit = { currentStage ->
            latestStage.set(currentStage)
            latestCheckCount.set(checks.size)
            val currentHeartbeatCount = heartbeatCount.updateAndGet(::incrementHeartbeatCount)
            try {
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(STATUS_STAGE_KEY, currentStage)
                        putInt(STATUS_CHECK_COUNT_KEY, checks.size)
                        putInt(STATUS_HEARTBEAT_COUNT_KEY, currentHeartbeatCount)
                    },
                )
            } catch (_: Exception) {
                // Progress is diagnostic only; the structured final result remains authoritative.
            }
            synchronized(progressLock) {
                writeProgressFile(
                    context = instrumentation.targetContext,
                    stage = currentStage,
                    checkCount = checks.size,
                    heartbeatCount = currentHeartbeatCount,
                )
            }
        }
        val heartbeatThread = Thread({
            while (!heartbeatStopped.get()) {
                try {
                    Thread.sleep(PROGRESS_HEARTBEAT_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
                if (heartbeatStopped.get()) break
                val currentHeartbeatCount = heartbeatCount.updateAndGet(::incrementHeartbeatCount)
                synchronized(progressLock) {
                    writeProgressFile(
                        context = instrumentation.targetContext,
                        stage = latestStage.get(),
                        checkCount = latestCheckCount.get(),
                        heartbeatCount = currentHeartbeatCount,
                    )
                }
            }
        }, "CanaryExternalHeartbeat").apply {
            isDaemon = true
            start()
        }
        publishProgress(stage)
        val result = try {
            try {
                runBlocking {
                    withTimeout(SCENARIO_TIMEOUT_MILLIS) {
                        runScenario(
                            instrumentation = instrumentation,
                            checks = checks,
                            updateStage = { currentStage ->
                                stage = currentStage
                                publishProgress(currentStage)
                            },
                            reportProgress = publishProgress,
                        )
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                failedResult(latestStage.get(), checks, FAILURE_TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Error) {
                throw failure
            } catch (_: FrpcTlsServerCertificatePinUnavailableException) {
                failedResult(latestStage.get(), checks, FAILURE_PIN_UNAVAILABLE)
            } catch (failure: CanaryExternalPreconditionException) {
                failedResult(latestStage.get(), checks, failure.code)
            } catch (_: CanaryExternalAssertionException) {
                failedResult(latestStage.get(), checks, FAILURE_ASSERTION)
            } catch (_: Exception) {
                failedResult(latestStage.get(), checks, FAILURE_UNEXPECTED)
            }
        } finally {
            heartbeatStopped.set(true)
            heartbeatThread.interrupt()
            heartbeatThread.join(PROGRESS_HEARTBEAT_STOP_TIMEOUT_MILLIS)
        }

        try {
            val encoded = JSON.encodeToString(result)
            if (encoded.toByteArray(Charsets.UTF_8).size > MAXIMUM_RESULT_BYTES) {
                throw CanaryExternalAssertionException()
            }
            val terminalStage = result.failedStage ?: stage
            writeProgressFile(
                context = instrumentation.targetContext,
                stage = terminalStage,
                checkCount = checks.size,
                heartbeatCount = heartbeatCount.get(),
                status = result.status,
                failureCode = result.failureCode,
            )
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(STATUS_RESULT_KEY, encoded)
                    putString(STATUS_STAGE_KEY, terminalStage)
                    putInt(STATUS_CHECK_COUNT_KEY, checks.size)
                },
            )
        } catch (_: Exception) {
            fail("Canary external network result publication failed")
        }
        if (result.status != STATUS_PASSED) {
            fail("Canary external network validation failed at ${result.failedStage} with ${result.failureCode}")
        }
    }

    private fun writeProgressFile(
        context: Context,
        stage: String,
        checkCount: Int,
        heartbeatCount: Int,
        status: String? = null,
        failureCode: String? = null,
    ) {
        val safeStage = stage.takeIf(ALLOWED_PROGRESS_STAGES::contains) ?: STAGE_UNKNOWN
        val safeCheckCount = checkCount.coerceIn(0, EXPECTED_CHECKS.size)
        val safeHeartbeatCount = heartbeatCount.coerceIn(0, MAXIMUM_HEARTBEAT_COUNT)
        val safeStatus = status?.takeIf(ALLOWED_PROGRESS_STATUSES::contains)
        val safeFailureCode = failureCode?.takeIf(ALLOWED_FAILURE_CODES::contains)
            ?: failureCode?.let { FAILURE_UNEXPECTED }
        val encoded = buildString {
            append("{\"stage\":\"")
            append(safeStage)
            append("\",\"checkCount\":")
            append(safeCheckCount)
            append(",\"heartbeatCount\":")
            append(safeHeartbeatCount)
            append(",\"status\":")
            append(safeStatus?.let { "\"$it\"" } ?: "null")
            append(",\"failureCode\":")
            append(safeFailureCode?.let { "\"$it\"" } ?: "null")
            append('}')
        }.toByteArray(Charsets.UTF_8)
        if (encoded.size > MAXIMUM_PROGRESS_BYTES) return

        val filesDirectory = context.filesDir
        if (!filesDirectory.isDirectory && !filesDirectory.mkdirs()) return
        val progressFile = File(filesDirectory, PROGRESS_FILE_NAME)
        val temporaryFile = File(filesDirectory, "$PROGRESS_FILE_NAME.tmp")
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            if (!temporaryFile.renameTo(progressFile)) {
                progressFile.delete()
                if (!temporaryFile.renameTo(progressFile)) {
                    temporaryFile.delete()
                }
            }
        } catch (_: Exception) {
            temporaryFile.delete()
        }
    }

    private fun incrementHeartbeatCount(current: Int): Int =
        if (current >= MAXIMUM_HEARTBEAT_COUNT) MAXIMUM_HEARTBEAT_COUNT else current + 1

    private suspend fun runScenario(
        instrumentation: Instrumentation,
        checks: MutableSet<String>,
        updateStage: (String) -> Unit,
        reportProgress: (String) -> Unit,
    ): CanaryExternalResult {
        if (!BuildConfig.USE_KOTLIN_FRPC_STCP_VISITOR || !BuildConfig.APPLICATION_ID.endsWith(".canary")) {
            throw CanaryExternalAssertionException()
        }
        checks += CHECK_KOTLIN_BACKEND

        val application = instrumentation.targetContext.applicationContext as? OpenCodeApplication
            ?: throw CanaryExternalAssertionException()
        val container = application.container
        var server: ServerConfig? = null
        var directory: String? = null
        var globalLease: io.github.ycfeng.ocdeck.core.network.GlobalConnectionLease? = null
        var projectLease: io.github.ycfeng.ocdeck.core.network.ProjectConnectionLease? = null
        var bindPort: Int? = null
        var visitorGeneration: FrpcStcpVisitorGeneration? = null
        var initialGeneration = 0L
        var initialControlEpoch = 0L
        var cellularControlEpoch = 0L
        var wifiControlEpoch = 0L
        var primaryFailure: Throwable? = null
        val wifiInitiallyEnabled = readToggleState(instrumentation, WIFI_SETTING)
        val mobileInitiallyEnabled = readToggleState(instrumentation, MOBILE_DATA_SETTING)

        try {
            updateStage(STAGE_CONFIGURATION)
            if (!wifiInitiallyEnabled || !mobileInitiallyEnabled) throw CanaryExternalAssertionException()
            reportProgress(STAGE_CONFIGURATION_WIFI)
            awaitValidatedTransport(instrumentation.targetContext, NetworkCapabilities.TRANSPORT_WIFI)
            checks += CHECK_INITIAL_WIFI_VALIDATED

            reportProgress(STAGE_CONFIGURATION_SERVERS)
            val configuredServers = container.serverRepository.observeServers().first()
                .filter { it.frpcStcpVisitor != null }
            if (configuredServers.size != 1) {
                throw CanaryExternalPreconditionException(FAILURE_STCP_CONFIG_NOT_UNIQUE)
            }
            server = configuredServers.single()
            val stcp = server.frpcStcpVisitor ?: throw CanaryExternalAssertionException()
            val publicEndpoint = isValidPublicEndpointSyntax(stcp.serverAddr)
            val pinConfigured = !stcp.tlsServerCertificateSha256Key.isNullOrBlank()
            when {
                !publicEndpoint && !pinConfigured -> {
                    throw CanaryExternalPreconditionException(FAILURE_PUBLIC_ENDPOINT_AND_PIN_REQUIRED)
                }
                !publicEndpoint -> throw CanaryExternalPreconditionException(FAILURE_PUBLIC_ENDPOINT_REQUIRED)
                !pinConfigured -> throw CanaryExternalPreconditionException(FAILURE_PIN_REQUIRED)
            }
            reportProgress(STAGE_CONFIGURATION_RESOLUTION)
            requirePublicResolutionOnActiveNetwork(
                instrumentation.targetContext,
                stcp.serverAddr,
                NetworkCapabilities.TRANSPORT_WIFI,
            )
            bindPort = stcp.bindPort
            checks += CHECK_PINNED_PUBLIC_SERVER

            updateStage(STAGE_STCP_READY)
            val initialConnection = container.serverRepository.getConnection(server.id)
            visitorGeneration = initialConnection.stcpGeneration ?: throw CanaryExternalAssertionException()
            initialGeneration = visitorGeneration.ordinal
            initialControlEpoch = initialConnection.stcpControlEpoch ?: throw CanaryExternalAssertionException()
            if (initialConnection.readinessHealth?.healthy != true || initialGeneration <= 0L || initialControlEpoch <= 0L) {
                throw CanaryExternalAssertionException()
            }
            checks += CHECK_STCP_READY

            updateStage(STAGE_SNAPSHOT)
            val path = initialConnection.api.getPath()
            directory = path.worktree?.takeIf(String::isNotBlank)
                ?: path.directory?.takeIf(String::isNotBlank)
                ?: throw CanaryExternalAssertionException()
            val loaded = container.openCodeRepository.loadProjectSnapshot(
                serverId = server.id,
                directory = directory,
                workspace = null,
                sessionWindow = SessionListWindowState(),
                activeSessionMessages = null,
            ).getOrThrow()
            if (loaded.transportIdentity != initialConnection.transportIdentity) throw CanaryExternalAssertionException()
            container.openCodeStore.applyProjectSnapshot(loaded.snapshot)
            val projectFlow = container.openCodeStore.observeProject(server.id, directory)
            awaitProject(projectFlow) { it.pathInfo != null }
            checks += CHECK_SNAPSHOT_STORE

            updateStage(STAGE_INITIAL_STREAMS)
            globalLease = container.openCodeEventClient.connectGlobal(server.id)
            withTimeout(STATE_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            projectLease = container.openCodeEventClient.connectProject(server.id, directory)
            val initialState = awaitProject(projectFlow) { it.connectionStatus == SseConnectionStatus.Open }
            checks += CHECK_GLOBAL_SSE
            checks += CHECK_PROJECT_SSE

            updateStage(STAGE_CELLULAR_DISCONNECT)
            val initialFingerprint = initialState.retentionFingerprint()
            val revisionBeforeCellular = initialState.projectDataRevision
            val networkChangesBeforeCellular = container.defaultNetworkMonitor.captureSnapshot().dispatchedChangeCount
            val coordinatorBeforeCellular = container.appConnectionCoordinator.captureRecoverySnapshot()
            setWifiEnabled(instrumentation, false)
            reportProgress(STAGE_CELLULAR_VALIDATION)
            awaitValidatedTransport(instrumentation.targetContext, NetworkCapabilities.TRANSPORT_CELLULAR)
            reportProgress(STAGE_CELLULAR_RESOLUTION)
            requirePublicResolutionOnActiveNetwork(
                instrumentation.targetContext,
                stcp.serverAddr,
                NetworkCapabilities.TRANSPORT_CELLULAR,
            )
            reportProgress(STAGE_CELLULAR_TCP)
            requireTcpReachableOnActiveNetwork(
                context = instrumentation.targetContext,
                host = stcp.serverAddr,
                port = stcp.serverPort,
                transport = NetworkCapabilities.TRANSPORT_CELLULAR,
            )
            checks += CHECK_CELLULAR_VALIDATED
            reportProgress(STAGE_CELLULAR_STREAMS_UNAVAILABLE)
            awaitStreamsUnavailable(container, server.id, projectFlow)
            val cellularDisconnected = projectFlow.first()
            if (cellularDisconnected.retentionFingerprint() != initialFingerprint) {
                throw CanaryExternalAssertionException()
            }
            checks += CHECK_CELLULAR_DATA_RETAINED

            updateStage(STAGE_CELLULAR_RECOVERY)
            awaitStreamsRecovered(
                container = container,
                serverId = server.id,
                directory = directory,
                projectFlow = projectFlow,
                minimumRevision = revisionBeforeCellular,
                minimumNetworkChangeCount = networkChangesBeforeCellular,
                minimumCoordinatorSnapshot = coordinatorBeforeCellular,
                visitorGeneration = visitorGeneration,
                baselineControlEpoch = initialControlEpoch,
                progressStages = RecoveryProgressStages(
                    callback = STAGE_CELLULAR_RECOVERY_CALLBACK,
                    global = STAGE_CELLULAR_RECOVERY_GLOBAL,
                    project = STAGE_CELLULAR_RECOVERY_PROJECT,
                    revision = STAGE_CELLULAR_RECOVERY_REVISION,
                ),
                reportProgress = reportProgress,
            )
            updateStage(STAGE_CELLULAR_RECOVERY_CONNECTION)
            val cellularConnection = container.serverRepository.getConnection(server.id)
            updateStage(STAGE_CELLULAR_RECOVERY_GENERATION)
            if (cellularConnection.stcpGeneration?.ordinal != initialGeneration) throw CanaryExternalAssertionException()
            cellularControlEpoch = cellularConnection.stcpControlEpoch ?: throw CanaryExternalAssertionException()
            updateStage(STAGE_CELLULAR_RECOVERY_EPOCH)
            if (cellularControlEpoch <= initialControlEpoch) {
                throw CanaryExternalAssertionException()
            }
            updateStage(STAGE_CELLULAR_RECOVERY_IDENTITY)
            if (cellularConnection.transportIdentity.isOlderThan(initialConnection.transportIdentity)) {
                throw CanaryExternalAssertionException()
            }
            checks += CHECK_CELLULAR_EPOCH_ADVANCED
            checks += CHECK_CELLULAR_RECONCILIATION

            updateStage(STAGE_WIFI_DISCONNECT)
            val stateBeforeWifi = projectFlow.first()
            val cellularFingerprint = stateBeforeWifi.retentionFingerprint()
            val revisionBeforeWifi = stateBeforeWifi.projectDataRevision
            val networkChangesBeforeWifi = container.defaultNetworkMonitor.captureSnapshot().dispatchedChangeCount
            val coordinatorBeforeWifi = container.appConnectionCoordinator.captureRecoverySnapshot()
            setWifiEnabled(instrumentation, true)
            reportProgress(STAGE_WIFI_VALIDATION)
            awaitValidatedTransport(instrumentation.targetContext, NetworkCapabilities.TRANSPORT_WIFI)
            setMobileDataEnabled(instrumentation, false)
            awaitValidatedTransport(instrumentation.targetContext, NetworkCapabilities.TRANSPORT_WIFI)
            reportProgress(STAGE_WIFI_RESOLUTION)
            requirePublicResolutionOnActiveNetwork(
                instrumentation.targetContext,
                stcp.serverAddr,
                NetworkCapabilities.TRANSPORT_WIFI,
            )
            checks += CHECK_WIFI_VALIDATED
            reportProgress(STAGE_WIFI_STREAMS_UNAVAILABLE)
            awaitStreamsUnavailable(container, server.id, projectFlow)
            val wifiDisconnected = projectFlow.first()
            if (wifiDisconnected.retentionFingerprint() != cellularFingerprint) {
                throw CanaryExternalAssertionException()
            }
            checks += CHECK_WIFI_DATA_RETAINED

            updateStage(STAGE_WIFI_RECOVERY)
            awaitStreamsRecovered(
                container = container,
                serverId = server.id,
                directory = directory,
                projectFlow = projectFlow,
                minimumRevision = revisionBeforeWifi,
                minimumNetworkChangeCount = networkChangesBeforeWifi,
                minimumCoordinatorSnapshot = coordinatorBeforeWifi,
                visitorGeneration = visitorGeneration,
                baselineControlEpoch = cellularControlEpoch,
                progressStages = RecoveryProgressStages(
                    callback = STAGE_WIFI_RECOVERY_CALLBACK,
                    global = STAGE_WIFI_RECOVERY_GLOBAL,
                    project = STAGE_WIFI_RECOVERY_PROJECT,
                    revision = STAGE_WIFI_RECOVERY_REVISION,
                ),
                reportProgress = reportProgress,
            )
            updateStage(STAGE_WIFI_RECOVERY_CONNECTION)
            val wifiConnection = container.serverRepository.getConnection(server.id)
            updateStage(STAGE_WIFI_RECOVERY_GENERATION)
            if (wifiConnection.stcpGeneration?.ordinal != initialGeneration) throw CanaryExternalAssertionException()
            wifiControlEpoch = wifiConnection.stcpControlEpoch ?: throw CanaryExternalAssertionException()
            updateStage(STAGE_WIFI_RECOVERY_EPOCH)
            if (wifiControlEpoch <= cellularControlEpoch) {
                throw CanaryExternalAssertionException()
            }
            updateStage(STAGE_WIFI_RECOVERY_IDENTITY)
            if (wifiConnection.transportIdentity.isOlderThan(cellularConnection.transportIdentity)) {
                throw CanaryExternalAssertionException()
            }
            checks += CHECK_WIFI_EPOCH_ADVANCED
            checks += CHECK_WIFI_RECONCILIATION
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        var cleanupFailure: Throwable? = null
        withContext(NonCancellable) {
            withTimeout(CLEANUP_TIMEOUT_MILLIS) {
                if (primaryFailure == null) updateStage(STAGE_CLEANUP)
                val cleanup = CleanupCollector()
                cleanup.run {
                    if (wifiInitiallyEnabled) setWifiEnabled(instrumentation, true)
                }
                cleanup.run {
                    if (mobileInitiallyEnabled) setMobileDataEnabled(instrumentation, true)
                }
                cleanup.run {
                    if (wifiInitiallyEnabled) awaitValidatedTransport(
                        instrumentation.targetContext,
                        NetworkCapabilities.TRANSPORT_WIFI,
                    )
                    checks += CHECK_NETWORK_RESTORED
                }
                cleanup.run {
                    projectLease?.release()
                }
                cleanup.run {
                    globalLease?.release()
                }
                server?.let { configured ->
                    cleanup.run {
                        container.openCodeEventClient.closeServer(configured.id)
                    }
                    cleanup.run {
                        directory?.let { normalizedDirectory ->
                            awaitProject(
                                container.openCodeStore.observeProject(configured.id, normalizedDirectory),
                            ) { it.connectionStatus == SseConnectionStatus.Closed }
                        }
                    }
                    cleanup.run {
                        withTimeout(STATE_TIMEOUT_MILLIS) {
                            container.openCodeStore.observeGlobalConnection(configured.id)
                                .first { it == SseConnectionStatus.Closed }
                        }
                    }
                    cleanup.run {
                        checks += CHECK_STREAMS_CLOSED
                    }
                    cleanup.run {
                        container.frpcStcpVisitorManager.closeVisitor(configured.id)
                        checks += CHECK_VISITOR_CLOSED
                    }
                }
                cleanup.run {
                    bindPort?.let { awaitPortReleased(it) }
                    checks += CHECK_PORT_RELEASED
                }
                cleanupFailure = cleanup.failure
            }
        }
        throwSelected(primaryFailure, cleanupFailure)

        if (checks != EXPECTED_CHECKS) throw CanaryExternalAssertionException()
        return CanaryExternalResult(
            schemaVersion = SCHEMA_VERSION,
            status = STATUS_PASSED,
            checks = checks.toList(),
            initialGeneration = initialGeneration,
            initialControlEpoch = initialControlEpoch,
            cellularControlEpoch = cellularControlEpoch,
            wifiControlEpoch = wifiControlEpoch,
            topology = TOPOLOGY_EXTERNAL,
            networkSwitchProven = true,
            authorizesKotlinDefault = false,
        )
    }

    private suspend fun awaitStreamsUnavailable(
        container: io.github.ycfeng.ocdeck.app.AppContainer,
        serverId: String,
        projectFlow: Flow<OpenCodeProjectState>,
    ) = withTimeout(NETWORK_TIMEOUT_MILLIS) {
        while (true) {
            if (container.openCodeStore.observeGlobalConnection(serverId).first() != SseConnectionStatus.Open &&
                projectFlow.first().connectionStatus != SseConnectionStatus.Open
            ) {
                return@withTimeout
            }
            delay(POLL_MILLIS)
        }
    }

    private suspend fun awaitStreamsRecovered(
        container: io.github.ycfeng.ocdeck.app.AppContainer,
        serverId: String,
        directory: String,
        projectFlow: Flow<OpenCodeProjectState>,
        minimumRevision: Long,
        minimumNetworkChangeCount: Long,
        minimumCoordinatorSnapshot: AppConnectionRecoverySnapshot,
        visitorGeneration: FrpcStcpVisitorGeneration,
        baselineControlEpoch: Long,
        progressStages: RecoveryProgressStages,
        reportProgress: (String) -> Unit,
    ) = withTimeout(RECOVERY_TIMEOUT_MILLIS) {
        var reportedStage: String? = null
        var progressPollCount = 0
        while (true) {
            val globalOpen = container.openCodeStore.observeGlobalConnection(serverId).first() == SseConnectionStatus.Open
            val project = projectFlow.first()
            val currentStage = when {
                container.defaultNetworkMonitor.captureSnapshot().dispatchedChangeCount <= minimumNetworkChangeCount -> {
                    progressStages.callback
                }
                !globalOpen -> {
                    val managerStage = container.frpcStcpVisitorManager.captureRecoveryStage(
                        generation = visitorGeneration,
                        baselineControlEpoch = baselineControlEpoch,
                    )
                    val coordinatorStage = if (managerStage == FrpcStcpRecoveryStage.MANAGER_NATIVE_TIMEOUT) {
                        container.appConnectionCoordinator.captureRecoverySnapshot()
                            .stageAfter(minimumCoordinatorSnapshot)
                    } else {
                        null
                    }
                    coordinatorStage ?: when (managerStage) {
                    FrpcStcpRecoveryStage.GENERATION_NOT_CURRENT -> STAGE_RECOVERY_GENERATION_CHANGED
                    FrpcStcpRecoveryStage.SAMPLE_UNAVAILABLE -> STAGE_RECOVERY_SAMPLE_UNAVAILABLE
                    FrpcStcpRecoveryStage.CONTROL_CONNECTING -> STAGE_RECOVERY_CONTROL_CONNECTING
                    FrpcStcpRecoveryStage.CONTROL_OLD_OPEN -> STAGE_RECOVERY_CONTROL_OLD_OPEN
                    FrpcStcpRecoveryStage.CONTROL_RETRYING -> STAGE_RECOVERY_CONTROL_RETRYING
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT ->
                        STAGE_RECOVERY_CONTROL_TRANSPORT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CONNECT ->
                        STAGE_RECOVERY_CONTROL_CONNECT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CONNECT_TIMEOUT ->
                        STAGE_RECOVERY_CONTROL_CONNECT_TIMEOUT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TLS_SETUP ->
                        STAGE_RECOVERY_CONTROL_TLS_SETUP
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TLS_HANDSHAKE ->
                        STAGE_RECOVERY_CONTROL_TLS_HANDSHAKE
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TLS_HANDSHAKE_TIMEOUT ->
                        STAGE_RECOVERY_CONTROL_TLS_HANDSHAKE_TIMEOUT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT_READ ->
                        STAGE_RECOVERY_CONTROL_TRANSPORT_READ
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT_WRITE ->
                        STAGE_RECOVERY_CONTROL_TRANSPORT_WRITE
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT_CLOSED ->
                        STAGE_RECOVERY_CONTROL_TRANSPORT_CLOSED
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT_INVALID_CONFIGURATION ->
                        STAGE_RECOVERY_CONTROL_TRANSPORT_INVALID_CONFIGURATION
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX_TRANSPORT ->
                        STAGE_RECOVERY_CONTROL_YAMUX_TRANSPORT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX_PROTOCOL ->
                        STAGE_RECOVERY_CONTROL_YAMUX_PROTOCOL
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX_STREAM_OPEN_TIMEOUT ->
                        STAGE_RECOVERY_CONTROL_YAMUX_STREAM_OPEN_TIMEOUT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX_TIMEOUT ->
                        STAGE_RECOVERY_CONTROL_YAMUX_TIMEOUT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX -> STAGE_RECOVERY_CONTROL_YAMUX
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CONNECTING_TRANSPORT ->
                        STAGE_RECOVERY_CONTROL_CONNECTING_TRANSPORT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_OPENING_CONTROL_STREAM ->
                        STAGE_RECOVERY_CONTROL_OPENING_CONTROL_STREAM
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_WAITING_LOGIN ->
                        STAGE_RECOVERY_CONTROL_WAITING_LOGIN
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_WAITING_LOGIN_AFTER_NEW_EPOCH ->
                        STAGE_RECOVERY_CONTROL_WAITING_LOGIN_AFTER_NEW_EPOCH
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_TIMER_SCHEDULED ->
                        STAGE_RECOVERY_CONTROL_LOGIN_TIMER_SCHEDULED
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_TIMER_FIRED ->
                        STAGE_RECOVERY_CONTROL_LOGIN_TIMER_FIRED
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_ABORTING_LOGIN_TIMEOUT ->
                        STAGE_RECOVERY_CONTROL_ABORTING_LOGIN_TIMEOUT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_WAITING_LOGIN_AFTER_TIMEOUT ->
                        STAGE_RECOVERY_CONTROL_WAITING_LOGIN_AFTER_TIMEOUT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_TRANSPORT ->
                        STAGE_RECOVERY_CONTROL_CLOSING_TRANSPORT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_JOINING_JOBS ->
                        STAGE_RECOVERY_CONTROL_JOINING_JOBS
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_WORK ->
                        STAGE_RECOVERY_CONTROL_CLOSING_WORK
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_CLIENT_STREAMS ->
                        STAGE_RECOVERY_CONTROL_CLOSING_CLIENT_STREAMS
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_YAMUX ->
                        STAGE_RECOVERY_CONTROL_CLOSING_YAMUX
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_FAILED_ESTABLISHMENT ->
                        STAGE_RECOVERY_CONTROL_CLOSING_FAILED_ESTABLISHMENT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_TIMEOUT ->
                        STAGE_RECOVERY_CONTROL_LOGIN_TIMEOUT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_REJECTED ->
                        STAGE_RECOVERY_CONTROL_LOGIN_REJECTED
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_PROTOCOL ->
                        STAGE_RECOVERY_CONTROL_LOGIN_PROTOCOL
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO -> STAGE_RECOVERY_CONTROL_CRYPTO
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_INVALID_KEY ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_INVALID_KEY
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_INVALID_NONCE ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_INVALID_NONCE
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_TRUNCATED_NONCE ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_TRUNCATED_NONCE
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_TRUNCATED_HEADER ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_TRUNCATED_HEADER
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_TRUNCATED_RECORD ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_TRUNCATED_RECORD
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_INVALID_LENGTH ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_INVALID_LENGTH
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_INTEGRITY ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_INTEGRITY
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_NONCE_EXHAUSTED ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_NONCE_EXHAUSTED
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_RECORD_LIMIT ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_RECORD_LIMIT
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_UNSUPPORTED_ALGORITHM ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_UNSUPPORTED_ALGORITHM
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_IO -> STAGE_RECOVERY_CONTROL_CRYPTO_IO
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_WRITE ->
                        STAGE_RECOVERY_CONTROL_CRYPTO_WRITE
                    FrpcStcpRecoveryStage.CONTROL_FAILED -> STAGE_RECOVERY_CONTROL_FAILED
                    FrpcStcpRecoveryStage.VISITOR_NOT_READY -> STAGE_RECOVERY_VISITOR
                    FrpcStcpRecoveryStage.MANAGER_REFRESHING -> STAGE_RECOVERY_MANAGER_REFRESHING
                    FrpcStcpRecoveryStage.MANAGER_REFRESH_NOT_STARTED ->
                        STAGE_RECOVERY_MANAGER_REFRESH_NOT_STARTED
                    FrpcStcpRecoveryStage.MANAGER_NATIVE_TIMEOUT -> STAGE_RECOVERY_MANAGER_NATIVE_TIMEOUT
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_PROBING -> STAGE_RECOVERY_MANAGER_HEALTH_PROBING
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_ATTEMPT_TIMEOUT ->
                        STAGE_RECOVERY_MANAGER_HEALTH_ATTEMPT_TIMEOUT
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_CONNECT -> STAGE_RECOVERY_MANAGER_HEALTH_CONNECT
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_SOCKET_TIMEOUT ->
                        STAGE_RECOVERY_MANAGER_HEALTH_SOCKET_TIMEOUT
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_EOF -> STAGE_RECOVERY_MANAGER_HEALTH_EOF
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_SOCKET -> STAGE_RECOVERY_MANAGER_HEALTH_SOCKET
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_UNHEALTHY -> STAGE_RECOVERY_MANAGER_HEALTH_UNHEALTHY
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_HTTP_RETRYABLE ->
                        STAGE_RECOVERY_MANAGER_HEALTH_HTTP_RETRYABLE
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_OTHER_RETRYABLE ->
                        STAGE_RECOVERY_MANAGER_HEALTH_OTHER_RETRYABLE
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_NON_RETRYABLE ->
                        STAGE_RECOVERY_MANAGER_HEALTH_NON_RETRYABLE
                    FrpcStcpRecoveryStage.MANAGER_HEALTH_TOTAL_TIMEOUT ->
                        STAGE_RECOVERY_MANAGER_HEALTH_TOTAL_TIMEOUT
                    FrpcStcpRecoveryStage.MANAGER_NOT_READY -> STAGE_RECOVERY_MANAGER_NOT_READY
                        FrpcStcpRecoveryStage.MANAGER_READY -> progressStages.global
                    }
                }
                project.connectionStatus != SseConnectionStatus.Open -> progressStages.project
                project.projectDataRevision <= minimumRevision -> when (
                    container.openCodeEventClient.projectCalibrationStage(serverId, directory)
                ) {
                    ProjectCalibrationStage.None -> progressStages.revision
                    ProjectCalibrationStage.Requested -> STAGE_RECOVERY_SNAPSHOT_REQUESTED
                    ProjectCalibrationStage.Loading -> STAGE_RECOVERY_SNAPSHOT_LOADING
                    ProjectCalibrationStage.Applied -> STAGE_RECOVERY_SNAPSHOT_APPLIED
                    ProjectCalibrationStage.Failed -> STAGE_RECOVERY_SNAPSHOT_FAILED
                    ProjectCalibrationStage.TransportChanged -> STAGE_RECOVERY_SNAPSHOT_TRANSPORT_CHANGED
                    ProjectCalibrationStage.IdentityMismatch -> STAGE_RECOVERY_SNAPSHOT_IDENTITY_MISMATCH
                    ProjectCalibrationStage.Stale -> STAGE_RECOVERY_SNAPSHOT_STALE
                }
                else -> return@withTimeout
            }
            progressPollCount += 1
            if (reportedStage != currentStage || progressPollCount % PROGRESS_HEARTBEAT_POLL_INTERVAL == 0) {
                reportProgress(currentStage)
                reportedStage = currentStage
            }
            delay(POLL_MILLIS)
        }
    }

    private suspend fun awaitProject(
        flow: Flow<OpenCodeProjectState>,
        predicate: (OpenCodeProjectState) -> Boolean,
    ): OpenCodeProjectState = withTimeout(STATE_TIMEOUT_MILLIS) { flow.first(predicate) }

    private fun AppConnectionRecoverySnapshot.stageAfter(
        baseline: AppConnectionRecoverySnapshot,
    ): String? = when {
        followUpStartedCount > baseline.followUpStartedCount -> STAGE_RECOVERY_COORDINATOR_FOLLOW_UP_STARTED
        followUpQueuedCount > baseline.followUpQueuedCount -> STAGE_RECOVERY_COORDINATOR_FOLLOW_UP_QUEUED
        serverCheckFailureCount > baseline.serverCheckFailureCount -> STAGE_RECOVERY_COORDINATOR_CHECK_FAILED
        serverCheckStartedCount > baseline.serverCheckStartedCount -> STAGE_RECOVERY_COORDINATOR_CHECK_ACTIVE
        else -> null
    }

    private suspend fun awaitValidatedTransport(context: Context, transport: Int) =
        withTimeout(NETWORK_TIMEOUT_MILLIS) {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: throw CanaryExternalAssertionException()
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
                    return@withTimeout
                }
                delay(POLL_MILLIS)
            }
        }

    private suspend fun setWifiEnabled(instrumentation: Instrumentation, enabled: Boolean) {
        readShellOutput(instrumentation, "svc wifi ${if (enabled) "enable" else "disable"}")
        awaitToggleState(instrumentation, WIFI_SETTING, enabled)
    }

    private suspend fun setMobileDataEnabled(instrumentation: Instrumentation, enabled: Boolean) {
        readShellOutput(instrumentation, "svc data ${if (enabled) "enable" else "disable"}")
        awaitToggleState(instrumentation, MOBILE_DATA_SETTING, enabled)
    }

    private fun readToggleState(instrumentation: Instrumentation, setting: String): Boolean =
        when (readShellOutput(instrumentation, "settings get global $setting")) {
            "1" -> true
            "0" -> false
            else -> throw CanaryExternalAssertionException()
        }

    private suspend fun awaitToggleState(
        instrumentation: Instrumentation,
        setting: String,
        expected: Boolean,
    ) = withTimeout(TOGGLE_TIMEOUT_MILLIS) {
        while (true) {
            if (readToggleState(instrumentation, setting) == expected) return@withTimeout
            delay(POLL_MILLIS)
        }
    }

    private fun readShellOutput(instrumentation: Instrumentation, command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val output = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val buffer = ByteArray(SHELL_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAXIMUM_SHELL_BYTES) throw CanaryExternalAssertionException()
                output.write(buffer, 0, count)
            }
            buffer.fill(0)
        }
        return output.toString(Charsets.UTF_8.name()).trim()
    }

    private suspend fun awaitPortReleased(port: Int) = withTimeout(PORT_RELEASE_TIMEOUT_MILLIS) {
        while (true) {
            val released = withContext(Dispatchers.IO) {
                try {
                    ServerSocket().use { socket ->
                        socket.reuseAddress = true
                        socket.bind(InetSocketAddress(IPV4_LOOPBACK, port), 1)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (released) return@withTimeout
            delay(POLL_MILLIS)
        }
    }

    private fun OpenCodeProjectState.retentionFingerprint(): RetentionFingerprint = RetentionFingerprint(
        values = listOf(
            project,
            pathInfo,
            sessions,
            sessionListWindow,
            messagesBySession,
            partsByMessage,
            messageHistoryBySession,
            permissionsBySession,
            questionsBySession,
            statuses,
            providerCount,
            models,
            agents,
            commands,
            promptCapabilities,
            mcps,
            lsps,
            plugins,
            commandCount,
            permissionCount,
            questionCount,
        ),
    )

    private fun isValidPublicEndpointSyntax(value: String): Boolean {
        return PublicEndpointValidator.isValidPublicEndpointSyntax(value)
    }

    private suspend fun requirePublicResolutionOnActiveNetwork(context: Context, host: String, transport: Int) {
        if (IPV4_LITERAL.matches(host) || ':' in host) {
            if (!isValidPublicEndpointSyntax(host)) throw CanaryExternalAssertionException()
            return
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: throw CanaryExternalAssertionException()
        withTimeout(NETWORK_TIMEOUT_MILLIS) {
            while (true) {
                val network = connectivity.activeNetwork
                val capabilities = network?.let(connectivity::getNetworkCapabilities)
                if (network == null || capabilities == null ||
                    !capabilities.hasTransport(transport) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    delay(POLL_MILLIS)
                    continue
                }
                val addresses = try {
                    network.getAllByName(host)
                } catch (_: Exception) {
                    delay(POLL_MILLIS)
                    continue
                }
                if (!PublicEndpointValidator.areAllResolvedAddressesPublic(addresses)) {
                    throw CanaryExternalAssertionException()
                }
                return@withTimeout
            }
        }
    }

    private suspend fun requireTcpReachableOnActiveNetwork(
        context: Context,
        host: String,
        port: Int,
        transport: Int,
    ) {
        val reachable = withContext(Dispatchers.IO) {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
                ?: throw CanaryExternalAssertionException()
            val network = connectivity.activeNetwork ?: throw CanaryExternalAssertionException()
            val capabilities = connectivity.getNetworkCapabilities(network)
                ?: throw CanaryExternalAssertionException()
            if (!capabilities.hasTransport(transport) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                throw CanaryExternalAssertionException()
            }
            val addresses = try {
                network.getAllByName(host)
            } catch (_: Exception) {
                throw CanaryExternalAssertionException()
            }
            if (!PublicEndpointValidator.areAllResolvedAddressesPublic(addresses) ||
                addresses.size > MAXIMUM_TCP_PROBE_ADDRESSES
            ) {
                throw CanaryExternalAssertionException()
            }
            val attemptTimeoutMillis = (TCP_PROBE_TIMEOUT_MILLIS / addresses.size).coerceAtLeast(1)
            addresses.any { address ->
                try {
                    network.socketFactory.createSocket().use { socket ->
                        socket.connect(InetSocketAddress(address, port), attemptTimeoutMillis)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        if (!reachable) throw CanaryExternalPreconditionException(FAILURE_CELLULAR_TCP_UNREACHABLE)
    }

    private fun throwSelected(vararg failures: Throwable?) {
        failures.firstOrNull { it is Error }?.let { throw it }
        failures.firstOrNull { it is CancellationException }?.let { throw it }
        failures.firstOrNull()?.let { throw it }
    }

    private class CleanupCollector {
        var failure: Throwable? = null
            private set

        suspend fun run(block: suspend () -> Unit) {
            try {
                block()
            } catch (caught: Throwable) {
                val current = failure
                failure = when {
                    current is Error || current is CancellationException -> current
                    caught is Error || caught is CancellationException -> caught
                    current != null -> current
                    else -> caught
                }
            }
        }
    }

    private fun failedResult(
        stage: String,
        checks: Set<String>,
        failureCode: String,
    ): CanaryExternalResult = CanaryExternalResult(
        schemaVersion = SCHEMA_VERSION,
        status = STATUS_FAILED,
        checks = checks.toList(),
        failedStage = stage,
        failureCode = failureCode,
        topology = TOPOLOGY_EXTERNAL,
        networkSwitchProven = false,
        authorizesKotlinDefault = false,
    )

    @Serializable
    private data class CanaryExternalResult(
        val schemaVersion: Int,
        val status: String,
        val checks: List<String>,
        val initialGeneration: Long? = null,
        val initialControlEpoch: Long? = null,
        val cellularControlEpoch: Long? = null,
        val wifiControlEpoch: Long? = null,
        val topology: String,
        val networkSwitchProven: Boolean,
        val authorizesKotlinDefault: Boolean,
        val failedStage: String? = null,
        val failureCode: String? = null,
    )

    private data class RetentionFingerprint(val values: List<Any?>)

    private data class RecoveryProgressStages(
        val callback: String,
        val global: String,
        val project: String,
        val revision: String,
    )

    private class CanaryExternalAssertionException : RuntimeException()

    private class CanaryExternalPreconditionException(val code: String) : RuntimeException()

    private companion object {
        const val ARG_ENABLED = "ocdeck.canaryExternal.enabled"
        const val STATUS_RESULT_KEY = "ocdeckCanaryExternalResult"
        const val STATUS_STAGE_KEY = "ocdeckCanaryExternalStage"
        const val STATUS_CHECK_COUNT_KEY = "ocdeckCanaryExternalCheckCount"
        const val STATUS_HEARTBEAT_COUNT_KEY = "ocdeckCanaryExternalHeartbeatCount"
        const val PROGRESS_FILE_NAME = "canary-external-progress.json"
        const val SCHEMA_VERSION = 1
        const val STATUS_PASSED = "passed"
        const val STATUS_FAILED = "failed"
        const val TOPOLOGY_EXTERNAL = "external"

        const val FAILURE_TIMEOUT = "TIMEOUT"
        const val FAILURE_ASSERTION = "ASSERTION"
        const val FAILURE_PIN_UNAVAILABLE = "PIN_UNAVAILABLE"
        const val FAILURE_PIN_REQUIRED = "PIN_REQUIRED"
        const val FAILURE_PUBLIC_ENDPOINT_REQUIRED = "PUBLIC_ENDPOINT_REQUIRED"
        const val FAILURE_PUBLIC_ENDPOINT_AND_PIN_REQUIRED = "PUBLIC_ENDPOINT_AND_PIN_REQUIRED"
        const val FAILURE_STCP_CONFIG_NOT_UNIQUE = "STCP_CONFIG_NOT_UNIQUE"
        const val FAILURE_CELLULAR_TCP_UNREACHABLE = "CELLULAR_TCP_UNREACHABLE"
        const val FAILURE_UNEXPECTED = "UNEXPECTED"

        const val STAGE_CONFIGURATION = "configuration"
        const val STAGE_CONFIGURATION_WIFI = "configuration_wifi"
        const val STAGE_CONFIGURATION_SERVERS = "configuration_servers"
        const val STAGE_CONFIGURATION_RESOLUTION = "configuration_resolution"
        const val STAGE_STCP_READY = "stcp_ready"
        const val STAGE_SNAPSHOT = "snapshot"
        const val STAGE_INITIAL_STREAMS = "initial_streams"
        const val STAGE_CELLULAR_DISCONNECT = "cellular_disconnect"
        const val STAGE_CELLULAR_VALIDATION = "cellular_validation"
        const val STAGE_CELLULAR_RESOLUTION = "cellular_resolution"
        const val STAGE_CELLULAR_TCP = "cellular_tcp"
        const val STAGE_CELLULAR_STREAMS_UNAVAILABLE = "cellular_streams_unavailable"
        const val STAGE_CELLULAR_RECOVERY = "cellular_recovery"
        const val STAGE_CELLULAR_RECOVERY_CALLBACK = "cellular_recovery_callback"
        const val STAGE_CELLULAR_RECOVERY_GLOBAL = "cellular_recovery_global"
        const val STAGE_CELLULAR_RECOVERY_PROJECT = "cellular_recovery_project"
        const val STAGE_CELLULAR_RECOVERY_REVISION = "cellular_recovery_revision"
        const val STAGE_CELLULAR_RECOVERY_CONNECTION = "cellular_recovery_connection"
        const val STAGE_CELLULAR_RECOVERY_GENERATION = "cellular_recovery_generation"
        const val STAGE_CELLULAR_RECOVERY_EPOCH = "cellular_recovery_epoch"
        const val STAGE_CELLULAR_RECOVERY_IDENTITY = "cellular_recovery_identity"
        const val STAGE_WIFI_DISCONNECT = "wifi_disconnect"
        const val STAGE_WIFI_VALIDATION = "wifi_validation"
        const val STAGE_WIFI_RESOLUTION = "wifi_resolution"
        const val STAGE_WIFI_STREAMS_UNAVAILABLE = "wifi_streams_unavailable"
        const val STAGE_WIFI_RECOVERY = "wifi_recovery"
        const val STAGE_WIFI_RECOVERY_CALLBACK = "wifi_recovery_callback"
        const val STAGE_WIFI_RECOVERY_GLOBAL = "wifi_recovery_global"
        const val STAGE_WIFI_RECOVERY_PROJECT = "wifi_recovery_project"
        const val STAGE_WIFI_RECOVERY_REVISION = "wifi_recovery_revision"
        const val STAGE_WIFI_RECOVERY_CONNECTION = "wifi_recovery_connection"
        const val STAGE_WIFI_RECOVERY_GENERATION = "wifi_recovery_generation"
        const val STAGE_WIFI_RECOVERY_EPOCH = "wifi_recovery_epoch"
        const val STAGE_WIFI_RECOVERY_IDENTITY = "wifi_recovery_identity"
        const val STAGE_RECOVERY_GENERATION_CHANGED = "recovery_generation_changed"
        const val STAGE_RECOVERY_SAMPLE_UNAVAILABLE = "recovery_sample_unavailable"
        const val STAGE_RECOVERY_CONTROL_CONNECTING = "recovery_control_connecting"
        const val STAGE_RECOVERY_CONTROL_OLD_OPEN = "recovery_control_old_open"
        const val STAGE_RECOVERY_CONTROL_RETRYING = "recovery_control_retrying"
        const val STAGE_RECOVERY_CONTROL_TRANSPORT = "recovery_control_transport"
        const val STAGE_RECOVERY_CONTROL_CONNECT = "recovery_control_connect"
        const val STAGE_RECOVERY_CONTROL_CONNECT_TIMEOUT = "recovery_control_connect_timeout"
        const val STAGE_RECOVERY_CONTROL_TLS_SETUP = "recovery_control_tls_setup"
        const val STAGE_RECOVERY_CONTROL_TLS_HANDSHAKE = "recovery_control_tls_handshake"
        const val STAGE_RECOVERY_CONTROL_TLS_HANDSHAKE_TIMEOUT = "recovery_control_tls_handshake_timeout"
        const val STAGE_RECOVERY_CONTROL_TRANSPORT_READ = "recovery_control_transport_read"
        const val STAGE_RECOVERY_CONTROL_TRANSPORT_WRITE = "recovery_control_transport_write"
        const val STAGE_RECOVERY_CONTROL_TRANSPORT_CLOSED = "recovery_control_transport_closed"
        const val STAGE_RECOVERY_CONTROL_TRANSPORT_INVALID_CONFIGURATION =
            "recovery_control_transport_invalid_configuration"
        const val STAGE_RECOVERY_CONTROL_YAMUX_TRANSPORT = "recovery_control_yamux_transport"
        const val STAGE_RECOVERY_CONTROL_YAMUX_PROTOCOL = "recovery_control_yamux_protocol"
        const val STAGE_RECOVERY_CONTROL_YAMUX_STREAM_OPEN_TIMEOUT =
            "recovery_control_yamux_stream_open_timeout"
        const val STAGE_RECOVERY_CONTROL_YAMUX_TIMEOUT = "recovery_control_yamux_timeout"
        const val STAGE_RECOVERY_CONTROL_YAMUX = "recovery_control_yamux"
        const val STAGE_RECOVERY_CONTROL_CONNECTING_TRANSPORT = "recovery_control_connecting_transport"
        const val STAGE_RECOVERY_CONTROL_OPENING_CONTROL_STREAM = "recovery_control_opening_control_stream"
        const val STAGE_RECOVERY_CONTROL_WAITING_LOGIN = "recovery_control_waiting_login"
        const val STAGE_RECOVERY_CONTROL_WAITING_LOGIN_AFTER_NEW_EPOCH =
            "recovery_control_waiting_login_after_new_epoch"
        const val STAGE_RECOVERY_CONTROL_LOGIN_TIMER_SCHEDULED =
            "recovery_control_login_timer_scheduled"
        const val STAGE_RECOVERY_CONTROL_LOGIN_TIMER_FIRED =
            "recovery_control_login_timer_fired"
        const val STAGE_RECOVERY_CONTROL_ABORTING_LOGIN_TIMEOUT =
            "recovery_control_aborting_login_timeout"
        const val STAGE_RECOVERY_CONTROL_WAITING_LOGIN_AFTER_TIMEOUT =
            "recovery_control_waiting_login_after_timeout"
        const val STAGE_RECOVERY_CONTROL_CLOSING_TRANSPORT = "recovery_control_closing_transport"
        const val STAGE_RECOVERY_CONTROL_JOINING_JOBS = "recovery_control_joining_jobs"
        const val STAGE_RECOVERY_CONTROL_CLOSING_WORK = "recovery_control_closing_work"
        const val STAGE_RECOVERY_CONTROL_CLOSING_CLIENT_STREAMS =
            "recovery_control_closing_client_streams"
        const val STAGE_RECOVERY_CONTROL_CLOSING_YAMUX = "recovery_control_closing_yamux"
        const val STAGE_RECOVERY_CONTROL_CLOSING_FAILED_ESTABLISHMENT =
            "recovery_control_closing_failed_establishment"
        const val STAGE_RECOVERY_CONTROL_LOGIN_TIMEOUT = "recovery_control_login_timeout"
        const val STAGE_RECOVERY_CONTROL_LOGIN_REJECTED = "recovery_control_login_rejected"
        const val STAGE_RECOVERY_CONTROL_LOGIN_PROTOCOL = "recovery_control_login_protocol"
        const val STAGE_RECOVERY_CONTROL_CRYPTO = "recovery_control_crypto"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_INVALID_KEY = "recovery_control_crypto_invalid_key"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_INVALID_NONCE = "recovery_control_crypto_invalid_nonce"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_TRUNCATED_NONCE = "recovery_control_crypto_truncated_nonce"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_TRUNCATED_HEADER = "recovery_control_crypto_truncated_header"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_TRUNCATED_RECORD = "recovery_control_crypto_truncated_record"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_INVALID_LENGTH = "recovery_control_crypto_invalid_length"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_INTEGRITY = "recovery_control_crypto_integrity"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_NONCE_EXHAUSTED = "recovery_control_crypto_nonce_exhausted"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_RECORD_LIMIT = "recovery_control_crypto_record_limit"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_UNSUPPORTED_ALGORITHM =
            "recovery_control_crypto_unsupported_algorithm"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_IO = "recovery_control_crypto_io"
        const val STAGE_RECOVERY_CONTROL_CRYPTO_WRITE = "recovery_control_crypto_write"
        const val STAGE_RECOVERY_CONTROL_FAILED = "recovery_control_failed"
        const val STAGE_RECOVERY_VISITOR = "recovery_visitor"
        const val STAGE_RECOVERY_MANAGER_REFRESHING = "recovery_manager_refreshing"
        const val STAGE_RECOVERY_MANAGER_REFRESH_NOT_STARTED = "recovery_manager_refresh_not_started"
        const val STAGE_RECOVERY_MANAGER_NATIVE_TIMEOUT = "recovery_manager_native_timeout"
        const val STAGE_RECOVERY_MANAGER_HEALTH_PROBING = "recovery_manager_health_probing"
        const val STAGE_RECOVERY_MANAGER_HEALTH_ATTEMPT_TIMEOUT = "recovery_manager_health_attempt_timeout"
        const val STAGE_RECOVERY_MANAGER_HEALTH_CONNECT = "recovery_manager_health_connect"
        const val STAGE_RECOVERY_MANAGER_HEALTH_SOCKET_TIMEOUT = "recovery_manager_health_socket_timeout"
        const val STAGE_RECOVERY_MANAGER_HEALTH_EOF = "recovery_manager_health_eof"
        const val STAGE_RECOVERY_MANAGER_HEALTH_SOCKET = "recovery_manager_health_socket"
        const val STAGE_RECOVERY_MANAGER_HEALTH_UNHEALTHY = "recovery_manager_health_unhealthy"
        const val STAGE_RECOVERY_MANAGER_HEALTH_HTTP_RETRYABLE = "recovery_manager_health_http_retryable"
        const val STAGE_RECOVERY_MANAGER_HEALTH_OTHER_RETRYABLE = "recovery_manager_health_other_retryable"
        const val STAGE_RECOVERY_MANAGER_HEALTH_NON_RETRYABLE = "recovery_manager_health_non_retryable"
        const val STAGE_RECOVERY_MANAGER_HEALTH_TOTAL_TIMEOUT = "recovery_manager_health_total_timeout"
        const val STAGE_RECOVERY_MANAGER_NOT_READY = "recovery_manager_not_ready"
        const val STAGE_RECOVERY_COORDINATOR_CHECK_ACTIVE = "recovery_coordinator_check_active"
        const val STAGE_RECOVERY_COORDINATOR_CHECK_FAILED = "recovery_coordinator_check_failed"
        const val STAGE_RECOVERY_COORDINATOR_FOLLOW_UP_QUEUED = "recovery_coordinator_followup_queued"
        const val STAGE_RECOVERY_COORDINATOR_FOLLOW_UP_STARTED = "recovery_coordinator_followup_started"
        const val STAGE_RECOVERY_SNAPSHOT_REQUESTED = "recovery_snapshot_requested"
        const val STAGE_RECOVERY_SNAPSHOT_LOADING = "recovery_snapshot_loading"
        const val STAGE_RECOVERY_SNAPSHOT_APPLIED = "recovery_snapshot_applied"
        const val STAGE_RECOVERY_SNAPSHOT_FAILED = "recovery_snapshot_failed"
        const val STAGE_RECOVERY_SNAPSHOT_TRANSPORT_CHANGED = "recovery_snapshot_transport_changed"
        const val STAGE_RECOVERY_SNAPSHOT_IDENTITY_MISMATCH = "recovery_snapshot_identity_mismatch"
        const val STAGE_RECOVERY_SNAPSHOT_STALE = "recovery_snapshot_stale"
        const val STAGE_CLEANUP = "cleanup"
        const val STAGE_UNKNOWN = "unknown"

        val ALLOWED_PROGRESS_STAGES = setOf(
            STAGE_CONFIGURATION,
            STAGE_CONFIGURATION_WIFI,
            STAGE_CONFIGURATION_SERVERS,
            STAGE_CONFIGURATION_RESOLUTION,
            STAGE_STCP_READY,
            STAGE_SNAPSHOT,
            STAGE_INITIAL_STREAMS,
            STAGE_CELLULAR_DISCONNECT,
            STAGE_CELLULAR_VALIDATION,
            STAGE_CELLULAR_RESOLUTION,
            STAGE_CELLULAR_TCP,
            STAGE_CELLULAR_STREAMS_UNAVAILABLE,
            STAGE_CELLULAR_RECOVERY,
            STAGE_CELLULAR_RECOVERY_CALLBACK,
            STAGE_CELLULAR_RECOVERY_GLOBAL,
            STAGE_CELLULAR_RECOVERY_PROJECT,
            STAGE_CELLULAR_RECOVERY_REVISION,
            STAGE_CELLULAR_RECOVERY_CONNECTION,
            STAGE_CELLULAR_RECOVERY_GENERATION,
            STAGE_CELLULAR_RECOVERY_EPOCH,
            STAGE_CELLULAR_RECOVERY_IDENTITY,
            STAGE_WIFI_DISCONNECT,
            STAGE_WIFI_VALIDATION,
            STAGE_WIFI_RESOLUTION,
            STAGE_WIFI_STREAMS_UNAVAILABLE,
            STAGE_WIFI_RECOVERY,
            STAGE_WIFI_RECOVERY_CALLBACK,
            STAGE_WIFI_RECOVERY_GLOBAL,
            STAGE_WIFI_RECOVERY_PROJECT,
            STAGE_WIFI_RECOVERY_REVISION,
            STAGE_WIFI_RECOVERY_CONNECTION,
            STAGE_WIFI_RECOVERY_GENERATION,
            STAGE_WIFI_RECOVERY_EPOCH,
            STAGE_WIFI_RECOVERY_IDENTITY,
            STAGE_RECOVERY_GENERATION_CHANGED,
            STAGE_RECOVERY_SAMPLE_UNAVAILABLE,
            STAGE_RECOVERY_CONTROL_CONNECTING,
            STAGE_RECOVERY_CONTROL_OLD_OPEN,
            STAGE_RECOVERY_CONTROL_RETRYING,
            STAGE_RECOVERY_CONTROL_TRANSPORT,
            STAGE_RECOVERY_CONTROL_CONNECT,
            STAGE_RECOVERY_CONTROL_CONNECT_TIMEOUT,
            STAGE_RECOVERY_CONTROL_TLS_SETUP,
            STAGE_RECOVERY_CONTROL_TLS_HANDSHAKE,
            STAGE_RECOVERY_CONTROL_TLS_HANDSHAKE_TIMEOUT,
            STAGE_RECOVERY_CONTROL_TRANSPORT_READ,
            STAGE_RECOVERY_CONTROL_TRANSPORT_WRITE,
            STAGE_RECOVERY_CONTROL_TRANSPORT_CLOSED,
            STAGE_RECOVERY_CONTROL_TRANSPORT_INVALID_CONFIGURATION,
            STAGE_RECOVERY_CONTROL_YAMUX_TRANSPORT,
            STAGE_RECOVERY_CONTROL_YAMUX_PROTOCOL,
            STAGE_RECOVERY_CONTROL_YAMUX_STREAM_OPEN_TIMEOUT,
            STAGE_RECOVERY_CONTROL_YAMUX_TIMEOUT,
            STAGE_RECOVERY_CONTROL_YAMUX,
            STAGE_RECOVERY_CONTROL_CONNECTING_TRANSPORT,
            STAGE_RECOVERY_CONTROL_OPENING_CONTROL_STREAM,
            STAGE_RECOVERY_CONTROL_WAITING_LOGIN,
            STAGE_RECOVERY_CONTROL_WAITING_LOGIN_AFTER_NEW_EPOCH,
            STAGE_RECOVERY_CONTROL_LOGIN_TIMER_SCHEDULED,
            STAGE_RECOVERY_CONTROL_LOGIN_TIMER_FIRED,
            STAGE_RECOVERY_CONTROL_ABORTING_LOGIN_TIMEOUT,
            STAGE_RECOVERY_CONTROL_WAITING_LOGIN_AFTER_TIMEOUT,
            STAGE_RECOVERY_CONTROL_CLOSING_TRANSPORT,
            STAGE_RECOVERY_CONTROL_JOINING_JOBS,
            STAGE_RECOVERY_CONTROL_CLOSING_WORK,
            STAGE_RECOVERY_CONTROL_CLOSING_CLIENT_STREAMS,
            STAGE_RECOVERY_CONTROL_CLOSING_YAMUX,
            STAGE_RECOVERY_CONTROL_CLOSING_FAILED_ESTABLISHMENT,
            STAGE_RECOVERY_CONTROL_LOGIN_TIMEOUT,
            STAGE_RECOVERY_CONTROL_LOGIN_REJECTED,
            STAGE_RECOVERY_CONTROL_LOGIN_PROTOCOL,
            STAGE_RECOVERY_CONTROL_CRYPTO,
            STAGE_RECOVERY_CONTROL_CRYPTO_INVALID_KEY,
            STAGE_RECOVERY_CONTROL_CRYPTO_INVALID_NONCE,
            STAGE_RECOVERY_CONTROL_CRYPTO_TRUNCATED_NONCE,
            STAGE_RECOVERY_CONTROL_CRYPTO_TRUNCATED_HEADER,
            STAGE_RECOVERY_CONTROL_CRYPTO_TRUNCATED_RECORD,
            STAGE_RECOVERY_CONTROL_CRYPTO_INVALID_LENGTH,
            STAGE_RECOVERY_CONTROL_CRYPTO_INTEGRITY,
            STAGE_RECOVERY_CONTROL_CRYPTO_NONCE_EXHAUSTED,
            STAGE_RECOVERY_CONTROL_CRYPTO_RECORD_LIMIT,
            STAGE_RECOVERY_CONTROL_CRYPTO_UNSUPPORTED_ALGORITHM,
            STAGE_RECOVERY_CONTROL_CRYPTO_IO,
            STAGE_RECOVERY_CONTROL_CRYPTO_WRITE,
            STAGE_RECOVERY_CONTROL_FAILED,
            STAGE_RECOVERY_VISITOR,
            STAGE_RECOVERY_MANAGER_REFRESHING,
            STAGE_RECOVERY_MANAGER_REFRESH_NOT_STARTED,
            STAGE_RECOVERY_MANAGER_NATIVE_TIMEOUT,
            STAGE_RECOVERY_MANAGER_HEALTH_PROBING,
            STAGE_RECOVERY_MANAGER_HEALTH_ATTEMPT_TIMEOUT,
            STAGE_RECOVERY_MANAGER_HEALTH_CONNECT,
            STAGE_RECOVERY_MANAGER_HEALTH_SOCKET_TIMEOUT,
            STAGE_RECOVERY_MANAGER_HEALTH_EOF,
            STAGE_RECOVERY_MANAGER_HEALTH_SOCKET,
            STAGE_RECOVERY_MANAGER_HEALTH_UNHEALTHY,
            STAGE_RECOVERY_MANAGER_HEALTH_HTTP_RETRYABLE,
            STAGE_RECOVERY_MANAGER_HEALTH_OTHER_RETRYABLE,
            STAGE_RECOVERY_MANAGER_HEALTH_NON_RETRYABLE,
            STAGE_RECOVERY_MANAGER_HEALTH_TOTAL_TIMEOUT,
            STAGE_RECOVERY_MANAGER_NOT_READY,
            STAGE_RECOVERY_COORDINATOR_CHECK_ACTIVE,
            STAGE_RECOVERY_COORDINATOR_CHECK_FAILED,
            STAGE_RECOVERY_COORDINATOR_FOLLOW_UP_QUEUED,
            STAGE_RECOVERY_COORDINATOR_FOLLOW_UP_STARTED,
            STAGE_RECOVERY_SNAPSHOT_REQUESTED,
            STAGE_RECOVERY_SNAPSHOT_LOADING,
            STAGE_RECOVERY_SNAPSHOT_APPLIED,
            STAGE_RECOVERY_SNAPSHOT_FAILED,
            STAGE_RECOVERY_SNAPSHOT_TRANSPORT_CHANGED,
            STAGE_RECOVERY_SNAPSHOT_IDENTITY_MISMATCH,
            STAGE_RECOVERY_SNAPSHOT_STALE,
            STAGE_CLEANUP,
            STAGE_UNKNOWN,
        )

        val ALLOWED_PROGRESS_STATUSES = setOf(STATUS_PASSED, STATUS_FAILED)

        val ALLOWED_FAILURE_CODES = setOf(
            FAILURE_TIMEOUT,
            FAILURE_ASSERTION,
            FAILURE_PIN_UNAVAILABLE,
            FAILURE_PIN_REQUIRED,
            FAILURE_PUBLIC_ENDPOINT_REQUIRED,
            FAILURE_PUBLIC_ENDPOINT_AND_PIN_REQUIRED,
            FAILURE_STCP_CONFIG_NOT_UNIQUE,
            FAILURE_CELLULAR_TCP_UNREACHABLE,
            FAILURE_UNEXPECTED,
        )

        const val CHECK_KOTLIN_BACKEND = "kotlin_backend"
        const val CHECK_PINNED_PUBLIC_SERVER = "configured_pinned_public_server"
        const val CHECK_INITIAL_WIFI_VALIDATED = "initial_wifi_validated"
        const val CHECK_STCP_READY = "stcp_ready"
        const val CHECK_SNAPSHOT_STORE = "snapshot_store"
        const val CHECK_GLOBAL_SSE = "global_sse"
        const val CHECK_PROJECT_SSE = "project_sse"
        const val CHECK_CELLULAR_VALIDATED = "cellular_validated"
        const val CHECK_CELLULAR_DATA_RETAINED = "cellular_data_retained"
        const val CHECK_CELLULAR_EPOCH_ADVANCED = "cellular_epoch_advanced"
        const val CHECK_CELLULAR_RECONCILIATION = "cellular_reconciliation"
        const val CHECK_WIFI_VALIDATED = "wifi_restored_validated"
        const val CHECK_WIFI_DATA_RETAINED = "wifi_return_data_retained"
        const val CHECK_WIFI_EPOCH_ADVANCED = "wifi_return_epoch_advanced"
        const val CHECK_WIFI_RECONCILIATION = "wifi_return_reconciliation"
        const val CHECK_NETWORK_RESTORED = "network_restored"
        const val CHECK_STREAMS_CLOSED = "streams_closed"
        const val CHECK_VISITOR_CLOSED = "visitor_closed"
        const val CHECK_PORT_RELEASED = "port_released"

        val EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_INITIAL_WIFI_VALIDATED,
            CHECK_PINNED_PUBLIC_SERVER,
            CHECK_STCP_READY,
            CHECK_SNAPSHOT_STORE,
            CHECK_GLOBAL_SSE,
            CHECK_PROJECT_SSE,
            CHECK_CELLULAR_VALIDATED,
            CHECK_CELLULAR_DATA_RETAINED,
            CHECK_CELLULAR_EPOCH_ADVANCED,
            CHECK_CELLULAR_RECONCILIATION,
            CHECK_WIFI_VALIDATED,
            CHECK_WIFI_DATA_RETAINED,
            CHECK_WIFI_EPOCH_ADVANCED,
            CHECK_WIFI_RECONCILIATION,
            CHECK_NETWORK_RESTORED,
            CHECK_STREAMS_CLOSED,
            CHECK_VISITOR_CLOSED,
            CHECK_PORT_RELEASED,
        )

        const val WIFI_SETTING = "wifi_on"
        const val MOBILE_DATA_SETTING = "mobile_data"
        const val POLL_MILLIS = 500L
        const val PROGRESS_HEARTBEAT_POLL_INTERVAL = 10
        const val PROGRESS_HEARTBEAT_MILLIS = 5_000L
        const val PROGRESS_HEARTBEAT_STOP_TIMEOUT_MILLIS = 1_000L
        const val MAXIMUM_HEARTBEAT_COUNT = 1_000_000
        const val TOGGLE_TIMEOUT_MILLIS = 30_000L
        const val STATE_TIMEOUT_MILLIS = 60_000L
        const val NETWORK_TIMEOUT_MILLIS = 90_000L
        const val RECOVERY_TIMEOUT_MILLIS = 180_000L
        const val CLEANUP_TIMEOUT_MILLIS = 120_000L
        const val PORT_RELEASE_TIMEOUT_MILLIS = 30_000L
        const val SCENARIO_TIMEOUT_MILLIS = 720_000L
        const val SHELL_BUFFER_BYTES = 1_024
        const val MAXIMUM_SHELL_BYTES = 16 * 1_024
        const val MAXIMUM_RESULT_BYTES = 16 * 1_024
        const val MAXIMUM_PROGRESS_BYTES = 1_024
        const val MAXIMUM_TCP_PROBE_ADDRESSES = 16
        const val TCP_PROBE_TIMEOUT_MILLIS = 10_000
        val IPV4_LITERAL = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val JSON = Json { encodeDefaults = true }
    }
}
