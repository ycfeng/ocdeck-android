package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AndroidInteropScenario(
    val kind: String,
)

@Serializable
internal data class AndroidInteropRestartMarkers(
    val deviceReadyFile: String,
    val frpsStoppedFile: String,
    val deviceDisconnectedFile: String,
    val frpsRestartedFile: String,
) {
    val fileNames: List<String>
        get() = listOf(deviceReadyFile, frpsStoppedFile, deviceDisconnectedFile, frpsRestartedFile)

    override fun toString(): String = "AndroidInteropRestartMarkers(configured=true)"
}

@Serializable
internal data class AndroidInteropInvocation(
    val schemaVersion: Int,
    val runId: String,
    val backend: String,
    val caseId: String,
    val scenario: AndroidInteropScenario,
    val serverPort: Int,
    val authToken: String,
    val stcpSecret: String,
    val bindPort: Int,
    val wireProtocol: String,
    val useEncryption: Boolean,
    val useCompression: Boolean,
    val restartMarkers: AndroidInteropRestartMarkers? = null,
) {
    override fun toString(): String =
        "AndroidInteropInvocation(caseId=$caseId, backend=$backend, scenario=${scenario.kind}, " +
            "wireProtocol=$wireProtocol, useEncryption=$useEncryption, " +
            "useCompression=$useCompression, authToken=<redacted>, stcpSecret=<redacted>, " +
            "restartMarkersPresent=${restartMarkers != null})"
}

@Serializable
internal data class AndroidInteropResult(
    val schemaVersion: Int,
    val runId: String,
    val backend: String,
    val caseId: String,
    val scenario: AndroidInteropScenario,
    val wireProtocol: String,
    val useEncryption: Boolean,
    val useCompression: Boolean,
    val status: String,
    val failedStage: String? = null,
    val failureCode: String? = null,
    val semanticOutcome: String? = null,
    val bindPort: Int = 0,
    val checks: List<String> = emptyList(),
) {
    override fun toString(): String =
        "AndroidInteropResult(caseId=$caseId, backend=$backend, status=$status, " +
            "semanticOutcomePresent=${semanticOutcome != null}, checkCount=${checks.size}, " +
            "failedStagePresent=${failedStage != null}, " +
            "failureCodePresent=${failureCode != null})"
}

@Serializable
internal data class AndroidInteropSuiteSummary(
    val schemaVersion: Int,
    val suite: String,
    val device: AndroidInteropSummaryDevice,
    val backendOrder: List<String>,
    val profiles: List<AndroidInteropProfileSummary>,
    val authorizesKotlinDefault: Boolean,
)

@Serializable
internal data class AndroidInteropSummaryDevice(
    val apiLevel: Int,
    val abi: String,
    val pageSize: Int,
)

@Serializable
internal data class AndroidInteropProfileSummary(
    val caseId: String,
    val scenario: String,
    val wireProtocol: String,
    val useEncryption: Boolean,
    val useCompression: Boolean,
    val backends: List<AndroidInteropBackendSummary>,
    val equivalent: Boolean,
)

@Serializable
internal data class AndroidInteropBackendSummary(
    val backend: String,
    val semanticOutcome: String,
    val checks: List<String>,
)

internal data class AndroidInteropCompletedProfile(
    val profile: AndroidInteropProfile,
    val goMobile: AndroidInteropResult,
    val kotlin: AndroidInteropResult,
)

@Serializable
internal data class AndroidInteropRestartMarker(
    val schemaVersion: Int,
    val runId: String,
    val caseId: String,
    val backend: String,
    val step: String,
) {
    override fun toString(): String = "AndroidInteropRestartMarker(step=$step)"
}

internal fun awaitAndroidInteropRestartMarker(
    expected: AndroidInteropRestartMarker,
    readMarker: () -> AdbCommandResult,
    instrumentationIsDone: () -> Boolean,
    observeInstrumentation: () -> Unit,
    timeoutMillis: Long,
    pollMillis: Long,
    sleep: (Long) -> Unit = ::sleepBounded,
) {
    if (timeoutMillis <= 0L || pollMillis <= 0L) {
        throw InteropFailure("Android restart marker polling configuration was invalid")
    }
    val deadline = InteropDeadline.afterMillis(timeoutMillis)
    while (!deadline.isExpired()) {
        val actual = decodeAndroidInteropRestartMarker(readMarker())
        if (actual != null) {
            if (actual != expected) throw InteropFailure("Android restart marker did not match its invocation")
            return
        }
        if (instrumentationIsDone()) {
            observeInstrumentation()
            throw InteropFailure("Android instrumentation completed before its restart marker")
        }
        sleep(minOf(pollMillis, deadline.remainingMillis()))
    }
    throw InteropFailure("Android restart marker did not become readable within its deadline")
}

private fun decodeAndroidInteropRestartMarker(result: AdbCommandResult): AndroidInteropRestartMarker? {
    if (result.exitCode != 0) return null
    val byteCount = result.standardOutput.toByteArray(StandardCharsets.UTF_8).size
    if (byteCount !in 1..MAXIMUM_RESTART_MARKER_BYTES) return null
    return try {
        ANDROID_INTEROP_JSON.decodeFromString(result.standardOutput)
    } catch (_: Exception) {
        null
    }
}

internal data class AndroidInteropProfile(
    val caseId: String,
    val scenario: AndroidInteropScenario,
    val wireProtocol: String,
    val useEncryption: Boolean,
    val useCompression: Boolean,
    val expectedChecks: List<String>,
) {
    val usesConcurrentTraffic: Boolean
        get() = scenario.kind == SCENARIO_SUCCESS || scenario.kind == SCENARIO_RESTART

    val isRestart: Boolean
        get() = scenario.kind == SCENARIO_RESTART

    val expectedSemanticOutcome: String
        get() = semanticOutcomeForScenario(scenario.kind)
}

internal data class AndroidInteropResultExpectation(
    val runId: String,
    val backend: String,
    val profile: AndroidInteropProfile,
    val expectedBindPort: Int? = null,
)

internal fun androidInteropProfiles(suite: String): List<AndroidInteropProfile> = when (suite) {
    SUITE_COMPAT -> listOf(ALL_ANDROID_INTEROP_PROFILES.first())
    SUITE_FULL -> ALL_ANDROID_INTEROP_PROFILES.toList()
    else -> throw InteropFailure("Android interoperability suite must be compat or full")
}

internal fun validateAndroidInteropResult(
    result: AndroidInteropResult,
    expectation: AndroidInteropResultExpectation,
) {
    val profile = expectation.profile
    if (result.schemaVersion != ANDROID_INTEROP_SCHEMA_VERSION ||
        result.runId != expectation.runId || !SAFE_RUN_ID.matches(result.runId) ||
        result.backend != expectation.backend ||
        result.caseId != profile.caseId || result.scenario != profile.scenario ||
        result.wireProtocol != profile.wireProtocol ||
        result.useEncryption != profile.useEncryption ||
        result.useCompression != profile.useCompression ||
        result.status != STATUS_PASSED || result.failedStage != null || result.failureCode != null ||
        result.semanticOutcome != profile.expectedSemanticOutcome ||
        result.bindPort !in 1..65_535 ||
        (expectation.expectedBindPort != null && result.bindPort != expectation.expectedBindPort) ||
        result.checks != profile.expectedChecks
    ) {
        throw InteropFailure("${expectation.backend} returned an invalid semantic result")
    }
}

internal fun requireEquivalentAndroidInteropResults(
    goMobile: AndroidInteropResult,
    kotlin: AndroidInteropResult,
) {
    val profile = ANDROID_INTEROP_PROFILES_BY_CASE[goMobile.caseId]
        ?: throw InteropFailure("GoMobile and Kotlin Android results were not semantically equivalent")
    validateAndroidInteropResult(
        goMobile,
        AndroidInteropResultExpectation(
            runId = goMobile.runId,
            backend = BACKEND_GO_MOBILE,
            profile = profile,
        ),
    )
    validateAndroidInteropResult(
        kotlin,
        AndroidInteropResultExpectation(
            runId = goMobile.runId,
            backend = BACKEND_KOTLIN,
            profile = profile,
            expectedBindPort = goMobile.bindPort,
        ),
    )
}

internal fun buildAndroidInteropSuiteSummary(
    suite: String,
    apiLevel: Int,
    abi: String,
    pageSize: Int,
    completedProfiles: List<AndroidInteropCompletedProfile>,
): AndroidInteropSuiteSummary {
    val expectedProfiles = androidInteropProfiles(suite)
    if (apiLevel < 26 || abi !in ANDROID_INTEROP_SUPPORTED_ABIS || pageSize <= 0 ||
        completedProfiles.map { it.profile } != expectedProfiles
    ) {
        throw InteropFailure("Android interoperability summary inputs were invalid")
    }
    val profiles = completedProfiles.map { completed ->
        requireEquivalentAndroidInteropResults(completed.goMobile, completed.kotlin)
        if (completed.goMobile.caseId != completed.profile.caseId) {
            throw InteropFailure("Android interoperability summary profile identity was invalid")
        }
        AndroidInteropProfileSummary(
            caseId = completed.profile.caseId,
            scenario = completed.profile.scenario.kind,
            wireProtocol = completed.profile.wireProtocol,
            useEncryption = completed.profile.useEncryption,
            useCompression = completed.profile.useCompression,
            backends = listOf(
                AndroidInteropBackendSummary(
                    BACKEND_GO_MOBILE,
                    completed.goMobile.semanticOutcome ?: throw InteropFailure("GoMobile semantic outcome was missing"),
                    completed.goMobile.checks,
                ),
                AndroidInteropBackendSummary(
                    BACKEND_KOTLIN,
                    completed.kotlin.semanticOutcome ?: throw InteropFailure("Kotlin semantic outcome was missing"),
                    completed.kotlin.checks,
                ),
            ),
            equivalent = true,
        )
    }
    return AndroidInteropSuiteSummary(
        schemaVersion = ANDROID_INTEROP_SUMMARY_SCHEMA_VERSION,
        suite = suite,
        device = AndroidInteropSummaryDevice(apiLevel, abi, pageSize),
        backendOrder = listOf(BACKEND_GO_MOBILE, BACKEND_KOTLIN),
        profiles = profiles,
        authorizesKotlinDefault = false,
    )
}

internal fun writeAndroidInteropSuiteSummary(path: Path, summary: AndroidInteropSuiteSummary) {
    val target = path.toAbsolutePath().normalize()
    val parent = target.parent ?: throw InteropFailure("Android interoperability summary path was invalid")
    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent) ||
        Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    ) {
        throw InteropFailure("Android interoperability summary destination was invalid")
    }
    val bytes = ANDROID_INTEROP_JSON.encodeToString(summary).toByteArray(StandardCharsets.UTF_8)
    if (bytes.size !in 1..MAXIMUM_SUMMARY_BYTES) {
        bytes.fill(0)
        throw InteropFailure("Android interoperability summary exceeded its bounded schema")
    }
    val temporary = parent.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
    try {
        writePrivateFile(temporary, bytes)
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target) ||
            Files.size(target) !in 1..MAXIMUM_SUMMARY_BYTES
        ) {
            throw InteropFailure("Android interoperability summary was not published safely")
        }
    } catch (failure: InteropFailure) {
        throw failure
    } catch (_: Exception) {
        throw InteropFailure("Android interoperability summary could not be published atomically")
    } finally {
        bytes.fill(0)
        Files.deleteIfExists(temporary)
    }
}

private const val ANDROID_INTEROP_SCHEMA_VERSION = 2
private const val ANDROID_INTEROP_SUMMARY_SCHEMA_VERSION = 1
private const val MAXIMUM_SUMMARY_BYTES = 64 * 1024
private const val MAXIMUM_RESTART_MARKER_BYTES = 2 * 1024
private const val RESTART_MARKER_SCHEMA_VERSION = 1
private const val SUITE_COMPAT = "compat"
private const val SUITE_FULL = "full"
private const val BACKEND_GO_MOBILE = "gomobile"
private const val BACKEND_KOTLIN = "kotlin"
private const val STATUS_PASSED = "passed"
private const val STATUS_FAILED = "failed"
private const val SCENARIO_SUCCESS = "success"
private const val SCENARIO_WRONG_TOKEN = "wrong_token"
private const val SCENARIO_WRONG_SECRET = "wrong_secret"
private const val SCENARIO_BIND_CONFLICT = "bind_conflict"
private const val SCENARIO_RESTART = "restart"
private const val OUTCOME_SUCCESS = "success"
private const val OUTCOME_CONTROL_FAILURE = "control_failure"
private const val OUTCOME_STCP_SECRET_REJECTED = "stcp_secret_rejected"
private const val OUTCOME_BIND_CONFLICT = "bind_conflict"
private const val OUTCOME_RECOVERED = "recovered"
private val ALL_SEMANTIC_OUTCOMES = setOf(
    OUTCOME_SUCCESS,
    OUTCOME_CONTROL_FAILURE,
    OUTCOME_STCP_SECRET_REJECTED,
    OUTCOME_BIND_CONFLICT,
    OUTCOME_RECOVERED,
)

private fun semanticOutcomeForScenario(scenario: String): String = when (scenario) {
    SCENARIO_SUCCESS -> OUTCOME_SUCCESS
    SCENARIO_WRONG_TOKEN -> OUTCOME_CONTROL_FAILURE
    SCENARIO_WRONG_SECRET -> OUTCOME_STCP_SECRET_REJECTED
    SCENARIO_BIND_CONFLICT -> OUTCOME_BIND_CONFLICT
    SCENARIO_RESTART -> OUTCOME_RECOVERED
    else -> throw InteropFailure("Android interoperability scenario was invalid")
}

private val ANDROID_INTEROP_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}
private val ANDROID_INTEROP_SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
private val SAFE_RUN_ID = Regex("[a-f0-9-]{36}")
private val SUCCESS_CHECKS = listOf(
    "open_state",
    "health",
    "global_sse",
    "project_sse",
    "concurrent_rest_sse",
    "closed",
    "port_released",
)
private val WRONG_TOKEN_CHECKS = listOf(
    "control_rejected",
    "session_failed",
    "listener_not_bound",
    "closed",
    "port_released",
)
private val WRONG_SECRET_CHECKS = listOf(
    "open_state",
    "health_rejected",
    "listener_consistent",
    "closed",
    "port_released",
)
private val BIND_CONFLICT_CHECKS = listOf(
    "bind_rejected",
    "visitor_failed",
    "listener_not_bound",
    "closed",
    "port_released",
)
private val RESTART_CHECKS = listOf(
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
)
private val ALL_ANDROID_INTEROP_PROFILES = listOf(
    AndroidInteropProfile(
        "success-v1-00",
        AndroidInteropScenario(SCENARIO_SUCCESS),
        "v1",
        false,
        false,
        SUCCESS_CHECKS,
    ),
    AndroidInteropProfile(
        "success-v1-01",
        AndroidInteropScenario(SCENARIO_SUCCESS),
        "v1",
        false,
        true,
        SUCCESS_CHECKS,
    ),
    AndroidInteropProfile(
        "success-v1-10",
        AndroidInteropScenario(SCENARIO_SUCCESS),
        "v1",
        true,
        false,
        SUCCESS_CHECKS,
    ),
    AndroidInteropProfile(
        "success-v1-11",
        AndroidInteropScenario(SCENARIO_SUCCESS),
        "v1",
        true,
        true,
        SUCCESS_CHECKS,
    ),
    AndroidInteropProfile(
        "success-v2-00",
        AndroidInteropScenario(SCENARIO_SUCCESS),
        "v2",
        false,
        false,
        SUCCESS_CHECKS,
    ),
    AndroidInteropProfile(
        "success-v2-01",
        AndroidInteropScenario(SCENARIO_SUCCESS),
        "v2",
        false,
        true,
        SUCCESS_CHECKS,
    ),
    AndroidInteropProfile(
        "success-v2-10",
        AndroidInteropScenario(SCENARIO_SUCCESS),
        "v2",
        true,
        false,
        SUCCESS_CHECKS,
    ),
    AndroidInteropProfile(
        "success-v2-11",
        AndroidInteropScenario(SCENARIO_SUCCESS),
        "v2",
        true,
        true,
        SUCCESS_CHECKS,
    ),
    AndroidInteropProfile(
        "negative-wrong-token-v2-00",
        AndroidInteropScenario(SCENARIO_WRONG_TOKEN),
        "v2",
        false,
        false,
        WRONG_TOKEN_CHECKS,
    ),
    AndroidInteropProfile(
        "negative-wrong-secret-v2-00",
        AndroidInteropScenario(SCENARIO_WRONG_SECRET),
        "v2",
        false,
        false,
        WRONG_SECRET_CHECKS,
    ),
    AndroidInteropProfile(
        "negative-bind-conflict-v2-00",
        AndroidInteropScenario(SCENARIO_BIND_CONFLICT),
        "v2",
        false,
        false,
        BIND_CONFLICT_CHECKS,
    ),
    AndroidInteropProfile(
        "restart-v2-11",
        AndroidInteropScenario(SCENARIO_RESTART),
        "v2",
        true,
        true,
        RESTART_CHECKS,
    ),
)
private val ANDROID_INTEROP_PROFILES_BY_CASE = ALL_ANDROID_INTEROP_PROFILES.associateBy(AndroidInteropProfile::caseId)

object FrpcAndroidInteropHost {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.isNotEmpty()) throw InteropFailure("frpcAndroidInteropTest does not accept arguments")
            FrpcAndroidInteropRunner().run()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: InteropFailure) {
            System.err.println("frpc Android interop failed: ${failure.message.orEmpty()}")
            exitProcess(1)
        } catch (_: Exception) {
            System.err.println("frpc Android interop failed: unexpected host coordinator failure")
            exitProcess(1)
        }
    }
}

private class FrpcAndroidInteropRunner {
    private val secureRandom = SecureRandom()
    private var stage = "initialization"
    private var redactor = InteropRedactor()

    fun run() {
        if (!System.getenv("ADB_SERVER_SOCKET").isNullOrBlank()) {
            throw InteropFailure("remote adb servers are not supported by the Android interoperability gate")
        }
        val resources = AndroidInteropResourceOwner()
        var selectedFailure: Throwable? = null
        val shutdownHook = Thread(
            { resources.cleanup() },
            "frpc-android-interop-shutdown-cleanup",
        ).apply { isDaemon = false }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            stage = "Android interoperability suite selection"
            val suite = configuredSuite()
            val profiles = androidInteropProfiles(suite)
            val summaryFile = configuredSummaryFile()

            stage = "temporary workspace creation"
            val temporaryDirectory = resources.createTemporaryWorkspace()
            val cacheDirectory = configuredCacheDirectory()
            val secrets = AndroidInteropSecrets.create(secureRandom)
            redactor = InteropRedactor(
                temporaryDirectory.toString(),
                cacheDirectory.toString(),
                secrets.authToken,
                secrets.stcpSecret,
                secrets.wrongAuthToken,
                secrets.wrongStcpSecret,
            )

            stage = "official frp provisioning"
            val binaries = FrpReleaseProvisioner(cacheDirectory).provision()

            stage = "synthetic OpenCode server startup"
            val syntheticServer = resources.createSyntheticServer()
            val frpsPort = allocateLoopbackPort()
            val frpsConfig = temporaryDirectory.resolve("frps-android.toml")
            val providerConfig = temporaryDirectory.resolve("frpc-provider-android.toml")
            stage = "temporary TLS material creation"
            val tlsMaterial = InteropTlsMaterial.create(temporaryDirectory, secureRandom)
            writePrivateFile(frpsConfig, frpsToml(frpsPort, secrets.authToken, tlsMaterial))
            writePrivateFile(
                providerConfig,
                providerToml(frpsPort, syntheticServer.port, secrets.authToken, secrets.stcpSecret),
            )
            redactor = redactor.including(
                binaries.frpc.toString(),
                binaries.frps.toString(),
                frpsConfig.toString(),
                providerConfig.toString(),
                tlsMaterial.certificatePath.toString(),
                tlsMaterial.privateKeyPath.toString(),
            )
            val frpsLaunch = AndroidInteropProcessLaunch(
                label = "android-frps",
                executable = binaries.frps,
                arguments = listOf("-c", frpsConfig.toString()),
                workingDirectory = temporaryDirectory,
                redactor = redactor,
            )
            val providerLaunch = AndroidInteropProcessLaunch(
                label = "android-provider-frpc",
                executable = binaries.frpc,
                arguments = listOf("-c", providerConfig.toString()),
                workingDirectory = temporaryDirectory,
                redactor = redactor,
            )

            stage = "frps startup"
            val frps = resources.startFrps(frpsLaunch::start)
            awaitFrpsReady(frpsPort, frps)

            stage = "provider frpc startup"
            val provider = resources.startProvider(providerLaunch::start)
            provider.awaitOutput(PROVIDER_PROXY_READY_PATTERN, PROCESS_READY_TIMEOUT_MILLIS, stage)

            stage = "Android test APK discovery"
            val testApk = configuredTestApk()
            redactor = redactor.including(testApk.toString())

            stage = "Android device discovery"
            val adbExecutable = configuredAdbExecutable()
            val discovery = AdbCommandRunner(adbExecutable, serial = null, redactor = redactor)
            val serial = selectDevice(discovery)
            val adb = AdbCommandRunner(adbExecutable, serial, redactor)
            resources.configureAdb(adb)
            val device = readDeviceMetadata(adb)

            stage = "instrumentation package preflight"
            requirePackageAbsent(adb)

            stage = "instrumentation APK installation"
            resources.markPackageInstallAttempted()
            val install = adb.run(
                label = "instrumentation APK installation",
                arguments = listOf("install", "--no-streaming", "-t", testApk.toString()),
                timeoutMillis = APK_INSTALL_TIMEOUT_MILLIS,
            )
            if (!install.standardOutput.lineSequence().any { it.trim() == "Success" }) {
                throw InteropFailure("instrumentation APK installation did not report success")
            }
            requirePackageInstalled(adb)
            resources.markPackageInstalled()

            stage = "adb reverse creation"
            val reverseBaseline = adb.run(
                label = "adb reverse ownership preflight",
                arguments = listOf("reverse", "--list"),
                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            )
            resources.registerReverseTarget(
                frpsPort,
                reversePortsForTarget(reverseBaseline.standardOutput, frpsPort).toSet(),
            )
            val reverse = adb.run(
                label = "adb reverse creation",
                arguments = listOf("reverse", "tcp:0", "tcp:$frpsPort"),
                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            )
            val deviceFrpsPort = parseReversePort(reverse.standardOutput)
            resources.registerReverse(deviceFrpsPort)

            val runId = UUID.randomUUID().toString()
            val completedProfiles = ArrayList<AndroidInteropCompletedProfile>(profiles.size)
            profiles.forEach { profile ->
                stage = "${profile.caseId} GoMobile Android interoperability scenario"
                val goMobile = runBackend(
                    resources = resources,
                    adb = adb,
                    syntheticServer = syntheticServer,
                    runId = runId,
                    profile = profile,
                    backend = BACKEND_GO_MOBILE,
                    serverPort = deviceFrpsPort,
                    bindPort = 0,
                    secrets = secrets,
                    frpsPort = frpsPort,
                    frpsLaunch = frpsLaunch,
                    provider = provider,
                )
                stage = "${profile.caseId} Kotlin Android interoperability scenario"
                val kotlin = runBackend(
                    resources = resources,
                    adb = adb,
                    syntheticServer = syntheticServer,
                    runId = runId,
                    profile = profile,
                    backend = BACKEND_KOTLIN,
                    serverPort = deviceFrpsPort,
                    bindPort = goMobile.bindPort,
                    secrets = secrets,
                    frpsPort = frpsPort,
                    frpsLaunch = frpsLaunch,
                    provider = provider,
                )
                stage = "${profile.caseId} backend result comparison"
                requireEquivalentAndroidInteropResults(goMobile, kotlin)
                completedProfiles += AndroidInteropCompletedProfile(profile, goMobile, kotlin)
                syntheticServer.requireHealthy()
                resources.requireFrpsAlive(stage)
                provider.requireAlive(stage)
                println("frpc Android interop profile passed: case=${profile.caseId} order=gomobile,kotlin")
            }
            syntheticServer.requireHealthy()
            resources.requireFrpsAlive(stage)
            provider.requireAlive(stage)
            stage = "Android interoperability suite summary"
            summaryFile?.let { output ->
                writeAndroidInteropSuiteSummary(
                    output,
                    buildAndroidInteropSuiteSummary(
                        suite = suite,
                        apiLevel = device.apiLevel,
                        abi = device.abi,
                        pageSize = device.pageSize,
                        completedProfiles = completedProfiles,
                    ),
                )
            }
            println(
                "frpc Android interop passed: api=${device.apiLevel} abi=${device.abi} " +
                    "pageSize=${device.pageSize} suite=$suite profiles=${profiles.size} order=gomobile,kotlin",
            )
        } catch (failure: Throwable) {
            selectedFailure = when (failure) {
                is Error, is CancellationException -> failure
                is Exception -> safeFailure(stage, failure, resources)
                else -> failure
            }
        } finally {
            stage = "resource cleanup"
            val cleanupFailure = resources.cleanup()
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
                // JVM shutdown has already started; the hook owns cleanup.
            }
            if (cleanupFailure != null) {
                val candidate = when (cleanupFailure) {
                    is Error, is CancellationException -> cleanupFailure
                    else -> InteropFailure("Android interoperability resource cleanup failed")
                }
                selectedFailure = preferFailure(selectedFailure, candidate)
            }
        }
        selectedFailure?.let { throw it }
    }

    private fun runBackend(
        resources: AndroidInteropResourceOwner,
        adb: AdbCommandRunner,
        syntheticServer: SyntheticOpenCodeServer,
        runId: String,
        profile: AndroidInteropProfile,
        backend: String,
        serverPort: Int,
        bindPort: Int,
        secrets: AndroidInteropSecrets,
        frpsPort: Int,
        frpsLaunch: AndroidInteropProcessLaunch,
        provider: ManagedInteropProcess,
    ): AndroidInteropResult {
        val files = AndroidInteropPrivateFiles.create(profile, backend, runId)
        resources.registerPrivateFiles(files.allNames)
        val authToken = if (profile.scenario.kind == SCENARIO_WRONG_TOKEN) {
            secrets.wrongAuthToken
        } else {
            secrets.authToken
        }
        val stcpSecret = if (profile.scenario.kind == SCENARIO_WRONG_SECRET) {
            secrets.wrongStcpSecret
        } else {
            secrets.stcpSecret
        }
        val invocation = AndroidInteropInvocation(
            schemaVersion = ANDROID_INTEROP_SCHEMA_VERSION,
            runId = runId,
            backend = backend,
            caseId = profile.caseId,
            scenario = profile.scenario,
            serverPort = serverPort,
            authToken = authToken,
            stcpSecret = stcpSecret,
            bindPort = bindPort,
            wireProtocol = profile.wireProtocol,
            useEncryption = profile.useEncryption,
            useCompression = profile.useCompression,
            restartMarkers = files.restartMarkers,
        )
        var gate: ConcurrentTrafficGate? = null
        var result: AndroidInteropResult? = null
        var selectedFailure: Throwable? = null
        try {
            val configBytes = ANDROID_INTEROP_JSON.encodeToString(invocation).toByteArray(StandardCharsets.UTF_8)
            try {
                writeDevicePrivateFile(adb, backend, files.configFile, configBytes)
            } finally {
                configBytes.fill(0)
            }
            if (profile.usesConcurrentTraffic) {
                gate = syntheticServer.beginConcurrentTraffic(
                    CONCURRENT_TRAFFIC_REQUESTS,
                    publishSseCheckpointOnSuccess = true,
                )
            }
            val instrumentation = if (profile.isRestart) {
                runRestartInstrumentation(
                    resources = resources,
                    adb = adb,
                    invocation = invocation,
                    files = files,
                    frpsPort = frpsPort,
                    frpsLaunch = frpsLaunch,
                    provider = provider,
                )
            } else {
                runInstrumentation(adb, invocation, files)
            }
            val decoded = readResult(adb, backend, files.resultFile)
            validateResultEnvelope(decoded, invocation, profile)
            if (decoded.status == STATUS_FAILED) {
                throw InteropFailure(
                    "$backend Android scenario failed at ${decoded.failedStage} with ${decoded.failureCode}",
                )
            }
            validateAndroidInteropResult(
                decoded,
                AndroidInteropResultExpectation(
                    runId = runId,
                    backend = backend,
                    profile = profile,
                    expectedBindPort = bindPort.takeIf { it != 0 },
                ),
            )
            requireInstrumentationSucceeded(instrumentation, backend)
            gate?.requireSatisfied()
            syntheticServer.requireHealthy()
            result = decoded
        } catch (failure: Throwable) {
            selectedFailure = failure
        }
        gate?.let { activeGate ->
            try {
                syntheticServer.endConcurrentTraffic(activeGate)
            } catch (failure: Throwable) {
                selectedFailure = preferFailure(selectedFailure, failure)
            }
        }
        try {
            resources.forceStopAndDeletePrivateFiles()
        } catch (failure: Throwable) {
            selectedFailure = preferFailure(selectedFailure, failure)
        }
        selectedFailure?.let { throw it }
        return result ?: throw InteropFailure("$backend did not return a semantic result")
    }

    private fun runInstrumentation(
        adb: AdbCommandRunner,
        invocation: AndroidInteropInvocation,
        files: AndroidInteropPrivateFiles,
    ): AdbCommandResult = adb.run(
        label = "${invocation.backend} instrumentation",
        arguments = listOf(
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            "$TEST_CLASS#$TEST_METHOD",
            "-e",
            "frpcAbEnabled",
            "true",
            "-e",
            "runId",
            invocation.runId,
            "-e",
            "backend",
            invocation.backend,
            "-e",
            "configFile",
            files.configFile,
            "-e",
            "resultFile",
            files.resultFile,
            "$TEST_PACKAGE/$TEST_RUNNER",
        ),
        timeoutMillis = INSTRUMENTATION_TIMEOUT_MILLIS,
        requireSuccess = false,
    )

    private fun runRestartInstrumentation(
        resources: AndroidInteropResourceOwner,
        adb: AdbCommandRunner,
        invocation: AndroidInteropInvocation,
        files: AndroidInteropPrivateFiles,
        frpsPort: Int,
        frpsLaunch: AndroidInteropProcessLaunch,
        provider: ManagedInteropProcess,
    ): AdbCommandResult {
        val markers = invocation.restartMarkers
            ?: throw InteropFailure("restart Android scenario did not configure markers")
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "frpc-android-interop-instrumentation").apply { isDaemon = true }
        }
        val asyncFailure = AtomicReference<Throwable?>(null)
        val future = try {
            executor.submit<AdbCommandResult> {
                try {
                    runInstrumentation(adb, invocation, files)
                } catch (failure: Throwable) {
                    asyncFailure.compareAndSet(null, failure)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            executor.shutdownNow()
            throw failure
        }
        val deadline = InteropDeadline.afterMillis(INSTRUMENTATION_TIMEOUT_MILLIS)
        var result: AdbCommandResult? = null
        var selectedFailure: Throwable? = null
        try {
            stage = "${invocation.caseId} ${invocation.backend} restart device ready"
            awaitRestartMarker(
                adb = adb,
                fileName = markers.deviceReadyFile,
                expected = restartMarker(invocation, MARKER_DEVICE_READY),
                instrumentation = future,
            )
            val providerReadyRevision = provider.outputRevision()

            stage = "${invocation.caseId} ${invocation.backend} frps stop"
            resources.stopFrps()
            writeRestartMarker(
                adb,
                invocation.backend,
                markers.frpsStoppedFile,
                restartMarker(invocation, MARKER_FRPS_STOPPED),
            )

            stage = "${invocation.caseId} ${invocation.backend} device disconnect"
            awaitRestartMarker(
                adb = adb,
                fileName = markers.deviceDisconnectedFile,
                expected = restartMarker(invocation, MARKER_DEVICE_DISCONNECTED),
                instrumentation = future,
            )

            stage = "${invocation.caseId} ${invocation.backend} frps restart"
            val restarted = resources.startFrps(frpsLaunch::start)
            awaitFrpsReady(frpsPort, restarted)
            provider.awaitOutputAfter(
                PROVIDER_PROXY_READY_PATTERN,
                providerReadyRevision,
                PROVIDER_RECONNECT_TIMEOUT_MILLIS,
                stage,
            )
            writeRestartMarker(
                adb,
                invocation.backend,
                markers.frpsRestartedFile,
                restartMarker(invocation, MARKER_FRPS_RESTARTED),
            )

            stage = "${invocation.caseId} ${invocation.backend} instrumentation completion"
            result = awaitInstrumentation(future, deadline.remainingMillis())
        } catch (failure: Throwable) {
            selectedFailure = failure
        }
        if (selectedFailure != null) {
            stopAsyncInstrumentation(resources, future)?.let { cleanupFailure ->
                selectedFailure = preferFailure(selectedFailure, cleanupFailure)
            }
        }
        shutdownInstrumentationExecutor(executor, future, selectedFailure != null)?.let { cleanupFailure ->
            selectedFailure = preferFailure(selectedFailure, cleanupFailure)
        }
        asyncFailure.get()?.let { failure ->
            selectedFailure = preferFailure(selectedFailure, failure)
        }
        selectedFailure?.let { throw it }
        return result ?: throw InteropFailure("restart instrumentation did not return a result")
    }

    private fun awaitRestartMarker(
        adb: AdbCommandRunner,
        fileName: String,
        expected: AndroidInteropRestartMarker,
        instrumentation: Future<AdbCommandResult>,
    ) {
        awaitAndroidInteropRestartMarker(
            expected = expected,
            readMarker = {
                adb.run(
                    label = "restart marker read",
                    arguments = listOf("exec-out", "run-as", TEST_PACKAGE, "cat", "files/$fileName"),
                    timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
                    requireSuccess = false,
                )
            },
            instrumentationIsDone = { instrumentation.isDone },
            observeInstrumentation = {
                awaitInstrumentation(instrumentation, ASYNC_OBSERVE_TIMEOUT_MILLIS)
            },
            timeoutMillis = MARKER_WAIT_TIMEOUT_MILLIS,
            pollMillis = MARKER_POLL_MILLIS,
        )
    }

    private fun writeRestartMarker(
        adb: AdbCommandRunner,
        backend: String,
        fileName: String,
        marker: AndroidInteropRestartMarker,
    ) {
        val bytes = ANDROID_INTEROP_JSON.encodeToString(marker).toByteArray(StandardCharsets.UTF_8)
        if (bytes.size !in 1..MAXIMUM_RESTART_MARKER_BYTES) {
            bytes.fill(0)
            throw InteropFailure("host restart marker exceeded its bounded schema")
        }
        val temporaryFile = temporaryPrivateFileName(fileName)
        try {
            writeDevicePrivateFile(adb, "$backend restart marker", temporaryFile, bytes)
            adb.run(
                label = "$backend restart marker publish",
                arguments = listOf(
                    "shell",
                    "run-as",
                    TEST_PACKAGE,
                    "mv",
                    "files/$temporaryFile",
                    "files/$fileName",
                ),
                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun restartMarker(
        invocation: AndroidInteropInvocation,
        step: String,
    ): AndroidInteropRestartMarker = AndroidInteropRestartMarker(
        schemaVersion = RESTART_MARKER_SCHEMA_VERSION,
        runId = invocation.runId,
        caseId = invocation.caseId,
        backend = invocation.backend,
        step = step,
    )

    private fun writeDevicePrivateFile(
        adb: AdbCommandRunner,
        label: String,
        fileName: String,
        bytes: ByteArray,
    ) {
        if (!SAFE_PRIVATE_FILE.matches(fileName) || bytes.size !in 1..MAXIMUM_PRIVATE_FILE_BYTES) {
            throw InteropFailure("Android private file write was invalid")
        }
        adb.run(
            label = "$label directory preparation",
            arguments = listOf("shell", "run-as", TEST_PACKAGE, "mkdir", "-p", "files"),
            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
        )
        adb.run(
            label = "$label write",
            arguments = listOf(
                "shell",
                "-T",
                "run-as",
                TEST_PACKAGE,
                "dd",
                "of=files/$fileName",
                "bs=1",
                "count=${bytes.size}",
            ),
            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            standardInput = bytes,
        )
        adb.run(
            label = "$label permission",
            arguments = listOf("shell", "run-as", TEST_PACKAGE, "chmod", "600", "files/$fileName"),
            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
        )
    }

    private fun readResult(
        adb: AdbCommandRunner,
        backend: String,
        resultFile: String,
    ): AndroidInteropResult {
        val output = adb.run(
            label = "$backend result read",
            arguments = listOf("exec-out", "run-as", TEST_PACKAGE, "cat", "files/$resultFile"),
            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
        ).standardOutput
        val byteCount = output.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount == 0) {
            throw InteropFailure("$backend returned an empty result")
        }
        if (byteCount > MAXIMUM_RESULT_BYTES) {
            throw InteropFailure("$backend result exceeded its bounded size")
        }
        return try {
            ANDROID_INTEROP_JSON.decodeFromString(output)
        } catch (_: Exception) {
            throw InteropFailure("$backend result did not match the bounded JSON schema ($byteCount bytes)")
        }
    }

    private fun validateResultEnvelope(
        result: AndroidInteropResult,
        invocation: AndroidInteropInvocation,
        profile: AndroidInteropProfile,
    ) {
        val failed = result.status == STATUS_FAILED
        if (result.schemaVersion != ANDROID_INTEROP_SCHEMA_VERSION ||
            result.runId != invocation.runId || result.backend != invocation.backend ||
            result.caseId != profile.caseId || result.scenario != profile.scenario ||
            result.wireProtocol != profile.wireProtocol ||
            result.useEncryption != profile.useEncryption ||
            result.useCompression != profile.useCompression ||
            result.status !in setOf(STATUS_PASSED, STATUS_FAILED) || result.bindPort !in 0..65_535 ||
            (failed && (result.failedStage == null || result.failureCode == null)) ||
            (!failed && (result.failedStage != null || result.failureCode != null)) ||
            (failed && result.semanticOutcome != null) ||
            (!failed && result.semanticOutcome !in ALL_SEMANTIC_OUTCOMES) ||
            (result.failedStage != null && !SAFE_RESULT_VALUE.matches(result.failedStage)) ||
            (result.failureCode != null && !SAFE_RESULT_VALUE.matches(result.failureCode)) ||
            !isOrderedSubset(result.checks, profile.expectedChecks)
        ) {
            throw InteropFailure("${invocation.backend} returned an invalid structural result")
        }
    }

    private fun isOrderedSubset(actual: List<String>, expected: List<String>): Boolean {
        if (actual.size > expected.size || actual.distinct().size != actual.size) return false
        var expectedIndex = 0
        actual.forEach { check ->
            val matchingIndex = expected.indexOfFirstFrom(expectedIndex) { it == check }
            if (matchingIndex < 0) return false
            expectedIndex = matchingIndex + 1
        }
        return true
    }

    private inline fun <T> List<T>.indexOfFirstFrom(startIndex: Int, predicate: (T) -> Boolean): Int {
        for (index in startIndex until size) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private fun requireInstrumentationSucceeded(result: AdbCommandResult, backend: String) {
        if (result.exitCode != 0 ||
            "INSTRUMENTATION_CODE: -1" !in result.standardOutput ||
            "FAILURES!!!" in result.standardOutput ||
            "INSTRUMENTATION_FAILED" in result.standardOutput
        ) {
            throw InteropFailure("$backend instrumentation did not complete successfully")
        }
    }

    private fun awaitInstrumentation(
        future: Future<AdbCommandResult>,
        timeoutMillis: Long,
    ): AdbCommandResult {
        if (timeoutMillis <= 0L) throw InteropFailure("Android instrumentation exceeded its deadline")
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw InteropFailure("Android instrumentation exceeded its deadline")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InteropFailure("Android instrumentation wait was interrupted")
        } catch (failure: ExecutionException) {
            throw normalizedAsyncFailure(failure.cause)
        } catch (failure: CancellationException) {
            throw failure
        }
    }

    private fun stopAsyncInstrumentation(
        resources: AndroidInteropResourceOwner,
        future: Future<AdbCommandResult>,
    ): Throwable? {
        var selectedFailure: Throwable? = null
        try {
            resources.forceStopInstrumentation()
        } catch (failure: Throwable) {
            selectedFailure = failure
        }
        try {
            future.get(ASYNC_INSTRUMENTATION_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
        } catch (_: CancellationException) {
            // Cancellation is expected only after the host has force-stopped the instrumentation.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            selectedFailure = preferFailure(
                selectedFailure,
                InteropFailure("Android instrumentation cleanup wait was interrupted"),
            )
        } catch (failure: ExecutionException) {
            selectedFailure = preferFailure(selectedFailure, normalizedAsyncFailure(failure.cause))
        }
        if (!future.isDone) future.cancel(true)
        return selectedFailure
    }

    private fun shutdownInstrumentationExecutor(
        executor: ExecutorService,
        future: Future<AdbCommandResult>,
        failed: Boolean,
    ): Throwable? {
        if (failed) {
            future.cancel(true)
            executor.shutdownNow()
        } else {
            executor.shutdown()
        }
        return try {
            if (executor.awaitTermination(ASYNC_EXECUTOR_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                null
            } else {
                future.cancel(true)
                executor.shutdownNow()
                if (executor.awaitTermination(ASYNC_EXECUTOR_FORCE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    null
                } else {
                    InteropFailure("Android instrumentation executor did not terminate")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            executor.shutdownNow()
            InteropFailure("Android instrumentation executor cleanup was interrupted")
        }
    }

    private fun normalizedAsyncFailure(failure: Throwable?): Throwable = when (failure) {
        is Error -> failure
        is CancellationException -> failure
        is InteropFailure -> failure
        else -> InteropFailure("Android instrumentation failed unexpectedly")
    }

    private fun preferFailure(current: Throwable?, candidate: Throwable): Throwable = when {
        current == null -> candidate
        current is Error -> current
        candidate is Error -> candidate
        current is CancellationException -> current
        candidate is CancellationException -> candidate
        else -> current
    }

    private fun selectDevice(discovery: AdbCommandRunner): String {
        val output = discovery.run(
            label = "Android device discovery",
            arguments = listOf("devices", "-l"),
            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
        ).standardOutput
        val authorized = output.lineSequence()
            .drop(1)
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val fields = line.split(Regex("\\s+"), limit = 3)
                fields.takeIf { it.size >= 2 && it[1] == "device" }?.first()
            }
            .toList()
        val requested = System.getProperty(DEVICE_SERIAL_PROPERTY)?.takeIf(String::isNotBlank)
        if (requested != null) {
            if (!SAFE_DEVICE_SERIAL.matches(requested) || requested !in authorized) {
                throw InteropFailure("configured Android device was not uniquely authorized")
            }
            return requested
        }
        if (authorized.size != 1) {
            throw InteropFailure("exactly one authorized Android device is required")
        }
        return authorized.single()
    }

    private fun readDeviceMetadata(adb: AdbCommandRunner): AndroidDeviceMetadata {
        fun property(name: String): String = adb.run(
            label = "Android device metadata",
            arguments = listOf("shell", "getprop", name),
            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
        ).standardOutput.trim()

        val api = property("ro.build.version.sdk").toIntOrNull()
            ?.takeIf { it >= 26 }
            ?: throw InteropFailure("Android device API level was unsupported")
        val abi = property("ro.product.cpu.abi").takeIf(ANDROID_INTEROP_SUPPORTED_ABIS::contains)
            ?: throw InteropFailure("Android device ABI was unsupported by the retained GoMobile bridge")
        val pageSize = adb.run(
            label = "Android device page size",
            arguments = listOf("shell", "-T", "sh"),
            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            standardInput = PAGE_SIZE_SHELL.toByteArray(StandardCharsets.UTF_8),
        ).standardOutput.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: throw InteropFailure("Android device page size was invalid")
        return AndroidDeviceMetadata(api, abi, pageSize)
    }

    private fun requirePackageAbsent(adb: AdbCommandRunner) {
        val result = adb.run(
            label = "instrumentation package preflight",
            arguments = listOf("shell", "pm", "path", TEST_PACKAGE),
            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            requireSuccess = false,
        )
        if (result.standardOutput.lineSequence().any { it.trim().startsWith("package:") }) {
            throw InteropFailure("instrumentation package was already installed; refusing to overwrite it")
        }
    }

    private fun requirePackageInstalled(adb: AdbCommandRunner) {
        val result = adb.run(
            label = "instrumentation package verification",
            arguments = listOf("shell", "pm", "path", TEST_PACKAGE),
            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
        )
        if (result.standardOutput.lineSequence().none { it.trim().startsWith("package:") }) {
            throw InteropFailure("instrumentation package verification did not find the expected package")
        }
    }

    private fun parseReversePort(output: String): Int {
        val normalized = output.trim().removePrefix("tcp:")
        return normalized.toIntOrNull()?.takeIf { it in 1..65_535 }
            ?: throw InteropFailure("adb reverse did not return a valid dynamic device port")
    }

    private fun configuredSuite(): String {
        val suite = System.getProperty(SUITE_PROPERTY)
            ?: throw InteropFailure("Android interoperability suite was not configured")
        androidInteropProfiles(suite)
        return suite
    }

    private fun configuredSummaryFile(): Path? {
        val configured = System.getProperty(SUMMARY_FILE_PROPERTY)?.takeIf(String::isNotBlank) ?: return null
        return try {
            Path.of(configured).toAbsolutePath().normalize()
        } catch (_: Exception) {
            throw InteropFailure("configured Android interoperability summary path was invalid")
        }
    }

    private fun configuredAdbExecutable(): Path = try {
        System.getProperty(ADB_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: throw InteropFailure("Gradle did not provide the Android SDK adb executable")
    } catch (failure: InteropFailure) {
        throw failure
    } catch (_: Exception) {
        throw InteropFailure("configured adb executable path was invalid")
    }

    private fun configuredTestApk(): Path {
        val directory = try {
            System.getProperty(APK_DIRECTORY_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.toAbsolutePath()
                ?.normalize()
                ?: throw InteropFailure("Gradle did not provide the Android test APK directory")
        } catch (failure: InteropFailure) {
            throw failure
        } catch (_: Exception) {
            throw InteropFailure("configured Android test APK directory was invalid")
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw InteropFailure("Android test APK directory was missing")
        }
        val candidates = ArrayList<Path>()
        Files.newDirectoryStream(directory, "*.apk").use { entries: DirectoryStream<Path> ->
            entries.forEach { path ->
                if (candidates.size >= MAXIMUM_APK_CANDIDATES) {
                    throw InteropFailure("Android test APK directory contained too many entries")
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    candidates.add(path.toAbsolutePath().normalize())
                }
            }
        }
        return candidates.singleOrNull { it.fileName.toString() == TEST_APK_FILE_NAME }
            ?: throw InteropFailure("the expected Android test APK was missing or ambiguous")
    }

    private fun configuredCacheDirectory(): Path = try {
        val configured = System.getProperty(FRP_CACHE_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: Path.of(
                System.getProperty("user.home"),
                ".gradle",
                "caches",
                "ocdeck",
                "frp-interop",
                "v0.69.1",
            ).toString()
        Path.of(configured).toAbsolutePath().normalize()
    } catch (_: Exception) {
        throw InteropFailure("frp interoperability cache configuration was invalid")
    }

    private fun allocateLoopbackPort(): Int = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(IPV4_LOOPBACK, 0))
            socket.localPort.takeIf { it in 1..65_535 }
                ?: throw InteropFailure("host loopback port allocation failed")
        }
    } catch (failure: InteropFailure) {
        throw failure
    } catch (_: Exception) {
        throw InteropFailure("host loopback port allocation failed")
    }

    private fun awaitFrpsReady(port: Int, process: ManagedInteropProcess) {
        process.awaitOutput(FRPS_READY_PATTERN, PROCESS_READY_TIMEOUT_MILLIS, stage)
        val deadline = InteropDeadline.afterMillis(PROCESS_READY_TIMEOUT_MILLIS)
        while (!deadline.isExpired()) {
            process.requireAlive(stage)
            val connected = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(IPV4_LOOPBACK, port), PORT_PROBE_TIMEOUT_MILLIS)
                }
                true
            } catch (_: Exception) {
                false
            }
            if (connected) return
            sleepBounded(minOf(25L, deadline.remainingMillis()))
        }
        throw InteropFailure("frps did not bind its loopback port within the deadline")
    }

    private fun frpsToml(port: Int, authToken: String, tls: InteropTlsMaterial): String = """
        bindAddr = "127.0.0.1"
        bindPort = $port
        proxyBindAddr = "127.0.0.1"
        detailedErrorsToClient = false

        log.to = "console"
        log.level = "info"
        log.maxDays = 1
        log.disablePrintColor = true

        transport.tcpMux = true
        transport.tls.force = true
        transport.tls.certFile = "${tls.certificatePath.fileName}"
        transport.tls.keyFile = "${tls.privateKeyPath.fileName}"

        auth.method = "token"
        auth.additionalScopes = ["HeartBeats", "NewWorkConns"]
        auth.token = "${tomlValue(authToken)}"
    """.trimIndent() + "\n"

    private fun providerToml(
        frpsPort: Int,
        syntheticPort: Int,
        authToken: String,
        stcpSecret: String,
    ): String = """
        user = "$PROVIDER_USER"
        serverAddr = "127.0.0.1"
        serverPort = $frpsPort
        loginFailExit = true

        log.to = "console"
        log.level = "info"
        log.maxDays = 1
        log.disablePrintColor = true

        auth.method = "token"
        auth.additionalScopes = ["HeartBeats", "NewWorkConns"]
        auth.token = "${tomlValue(authToken)}"

        transport.protocol = "tcp"
        transport.wireProtocol = "v2"
        transport.tcpMux = true
        transport.tls.enable = true
        transport.tls.disableCustomTLSFirstByte = true

        [[proxies]]
        name = "$PROVIDER_PROXY_NAME"
        type = "stcp"
        secretKey = "${tomlValue(stcpSecret)}"
        localIP = "127.0.0.1"
        localPort = $syntheticPort
        allowUsers = ["$VISITOR_USER"]
        transport.useEncryption = false
        transport.useCompression = false
    """.trimIndent() + "\n"

    private fun tomlValue(value: String): String = value.takeIf(SAFE_TOML_VALUE::matches)
        ?: throw InteropFailure("runtime credential generation was invalid")

    private fun safeFailure(
        currentStage: String,
        failure: Exception,
        resources: AndroidInteropResourceOwner,
    ): InteropFailure {
        val summary = when (failure) {
            is InteropFailure -> "$currentStage: ${redactor.redact(failure.message.orEmpty())}"
            else -> "$currentStage failed with ${failure.javaClass.simpleName}"
        }
        val diagnostics = resources.processDiagnostics()
        return InteropFailure(
            if (diagnostics.isBlank()) summary else "$summary\n${redactor.redact(diagnostics)}",
        )
    }

    private inner class AndroidInteropResourceOwner {
        private val lock = Any()
        private val cleanupComplete = CountDownLatch(1)
        private var cleanupStarted = false
        @Volatile
        private var cleanupFailure: Throwable? = null
        private var temporaryDirectory: Path? = null
        private var syntheticServer: SyntheticOpenCodeServer? = null
        private var frps: ManagedInteropProcess? = null
        private var provider: ManagedInteropProcess? = null
        private var adb: AdbCommandRunner? = null
        private var packageInstallAttempted = false
        private var packageInstalled = false
        private var reversePort: Int? = null
        private var reverseTargetPort: Int? = null
        private var reverseBaselinePorts = emptySet<Int>()
        private val privateFiles = LinkedHashSet<String>()

        fun createTemporaryWorkspace(): Path = synchronized(lock) {
            requireActive()
            Files.createTempDirectory("ocdeck-frp-android-interop-").also { created ->
                try {
                    createPrivateDirectory(created)
                } catch (failure: Throwable) {
                    deleteTreeBestEffort(created)
                    throw failure
                }
                temporaryDirectory = created
            }
        }

        fun createSyntheticServer(): SyntheticOpenCodeServer = synchronized(lock) {
            requireActive()
            SyntheticOpenCodeServer().also { syntheticServer = it }
        }

        fun startFrps(factory: () -> ManagedInteropProcess): ManagedInteropProcess = synchronized(lock) {
            requireActive()
            if (frps != null) throw InteropFailure("frps was already registered")
            factory().also { frps = it }
        }

        fun stopFrps() = synchronized(lock) {
            requireActive()
            val current = frps ?: throw InteropFailure("frps was not running")
            current.stop()
            frps = null
        }

        fun requireFrpsAlive(currentStage: String) = synchronized(lock) {
            requireActive()
            frps?.requireAlive(currentStage) ?: throw InteropFailure("$currentStage: frps was not running")
        }

        fun startProvider(factory: () -> ManagedInteropProcess): ManagedInteropProcess = synchronized(lock) {
            requireActive()
            if (provider != null) throw InteropFailure("provider frpc was already registered")
            factory().also { provider = it }
        }

        fun configureAdb(runner: AdbCommandRunner) = synchronized(lock) {
            requireActive()
            if (adb != null) throw InteropFailure("adb was already registered")
            adb = runner
        }

        fun markPackageInstallAttempted() = synchronized(lock) {
            requireActive()
            packageInstallAttempted = true
        }

        fun markPackageInstalled() = synchronized(lock) {
            requireActive()
            check(packageInstallAttempted)
            packageInstalled = true
        }

        fun registerReverseTarget(port: Int, baselinePorts: Set<Int>) = synchronized(lock) {
            requireActive()
            if (reverseTargetPort != null) throw InteropFailure("adb reverse target was already registered")
            reverseTargetPort = port
            reverseBaselinePorts = baselinePorts.toSet()
        }

        fun registerReverse(port: Int) = synchronized(lock) {
            requireActive()
            check(reverseTargetPort != null)
            if (reversePort != null) throw InteropFailure("adb reverse was already registered")
            reversePort = port
        }

        fun registerPrivateFiles(names: List<String>) = synchronized(lock) {
            requireActive()
            if (names.isEmpty() || names.distinct().size != names.size ||
                names.any { !SAFE_PRIVATE_FILE.matches(it) || it in privateFiles }
            ) {
                throw InteropFailure("private file names were invalid or not unique")
            }
            privateFiles.addAll(names)
        }

        fun forceStopInstrumentation() {
            val runner = synchronized(lock) {
                requireActive()
                adb
            } ?: throw InteropFailure("adb was not configured")
            runner.run(
                label = "instrumentation force stop",
                arguments = listOf("shell", "am", "force-stop", TEST_PACKAGE),
                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            )
        }

        fun forceStopAndDeletePrivateFiles() {
            var selectedFailure: Throwable? = null
            try {
                forceStopInstrumentation()
            } catch (failure: Throwable) {
                selectedFailure = failure
            }
            val runner = synchronized(lock) {
                requireActive()
                adb
            } ?: throw InteropFailure("adb was not configured")
            val files = synchronized(lock) { privateFiles.toList() }
            if (files.isNotEmpty()) {
                try {
                    runner.run(
                        label = "instrumentation private file cleanup",
                        arguments = listOf("shell", "run-as", TEST_PACKAGE, "rm", "-f") +
                            files.map { "files/$it" },
                        timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
                    )
                    synchronized(lock) { privateFiles.removeAll(files.toSet()) }
                } catch (failure: Throwable) {
                    selectedFailure = preferFailure(selectedFailure, failure)
                }
            }
            selectedFailure?.let { throw it }
        }

        fun processDiagnostics(): String = synchronized(lock) {
            listOfNotNull(
                frps?.diagnosticSummary()?.takeIf(String::isNotBlank),
                provider?.diagnosticSummary()?.takeIf(String::isNotBlank),
            ).joinToString("\n")
        }

        fun cleanup(): Throwable? {
            val owner = synchronized(lock) {
                if (cleanupStarted) false else {
                    cleanupStarted = true
                    true
                }
            }
            if (!owner) return awaitCleanupCompletion()

            var failure: Throwable? = null
            try {
                val runner = synchronized(lock) { adb }
                if (runner != null && packageInstalled) {
                    failure = cleanupStep(failure) {
                        runner.run(
                            label = "instrumentation cleanup force stop",
                            arguments = listOf("shell", "am", "force-stop", TEST_PACKAGE),
                            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
                        )
                    }
                    val files = synchronized(lock) { privateFiles.toList() }
                    if (files.isNotEmpty()) {
                        failure = cleanupStep(failure) {
                            runner.run(
                                label = "instrumentation cleanup private files",
                                arguments = listOf("shell", "run-as", TEST_PACKAGE, "rm", "-f") +
                                    files.map { "files/$it" },
                                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
                            )
                        }
                    }
                }
                val (ownedReverse, ownedReverseTarget, baselineReversePorts) = synchronized(lock) {
                    Triple(reversePort, reverseTargetPort, reverseBaselinePorts)
                }
                if (runner != null && ownedReverse != null) {
                    failure = cleanupStep(failure) {
                        runner.run(
                            label = "adb reverse cleanup",
                            arguments = listOf("reverse", "--remove", "tcp:$ownedReverse"),
                            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
                        )
                    }
                } else if (runner != null && ownedReverseTarget != null) {
                    failure = cleanupStep(failure) {
                        val listed = runner.run(
                            label = "adb reverse ownership recovery",
                            arguments = listOf("reverse", "--list"),
                            timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
                        )
                        recoverOwnedReversePort(
                            listed.standardOutput,
                            ownedReverseTarget,
                            baselineReversePorts,
                        )?.let { port ->
                            runner.run(
                                label = "adb reverse cleanup",
                                arguments = listOf("reverse", "--remove", "tcp:$port"),
                                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
                            )
                        }
                    }
                }
                if (runner != null && packageInstallAttempted) {
                    failure = cleanupStep(failure) {
                        runner.run(
                            label = "instrumentation APK uninstall",
                            arguments = listOf("uninstall", TEST_PACKAGE),
                            timeoutMillis = APK_INSTALL_TIMEOUT_MILLIS,
                            requireSuccess = packageInstalled,
                        )
                    }
                }
                failure = cleanupStep(failure) { provider?.stop() }
                failure = cleanupStep(failure) { frps?.stop() }
                failure = cleanupStep(failure) { syntheticServer?.close() }
                failure = cleanupStep(failure) { deleteTreeChecked(temporaryDirectory) }
                return failure
            } finally {
                cleanupFailure = failure
                cleanupComplete.countDown()
            }
        }

        private fun cleanupStep(current: Throwable?, block: () -> Unit): Throwable? = try {
            block()
            current
        } catch (candidate: Throwable) {
            when {
                current == null -> candidate
                current is Error -> current
                candidate is Error -> candidate
                current is CancellationException -> current
                candidate is CancellationException -> candidate
                else -> current
            }
        }

        private fun awaitCleanupCompletion(): Throwable? = try {
            if (!cleanupComplete.await(RESOURCE_CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                InteropFailure("Android interoperability cleanup did not complete within its deadline")
            } else {
                cleanupFailure
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            InteropFailure("Android interoperability cleanup wait was interrupted")
        }

        private fun requireActive() {
            if (cleanupStarted) throw InteropFailure("Android interoperability cleanup had already started")
        }
    }

    private data class AndroidDeviceMetadata(
        val apiLevel: Int,
        val abi: String,
        val pageSize: Int,
    )

    private data class AndroidInteropProcessLaunch(
        val label: String,
        val executable: Path,
        val arguments: List<String>,
        val workingDirectory: Path,
        val redactor: InteropRedactor,
    ) {
        fun start(): ManagedInteropProcess = ManagedInteropProcess.start(
            label = label,
            executable = executable,
            arguments = arguments,
            workingDirectory = workingDirectory,
            redactor = redactor,
        )

        override fun toString(): String = "AndroidInteropProcessLaunch(label=$label)"
    }

    private data class AndroidInteropPrivateFiles(
        val configFile: String,
        val resultFile: String,
        val temporaryResultFile: String,
        val restartMarkers: AndroidInteropRestartMarkers?,
        val allNames: List<String>,
    ) {
        override fun toString(): String =
            "AndroidInteropPrivateFiles(count=${allNames.size}, restart=${restartMarkers != null})"

        companion object {
            fun create(
                profile: AndroidInteropProfile,
                backend: String,
                runId: String,
            ): AndroidInteropPrivateFiles {
                if (backend !in setOf(BACKEND_GO_MOBILE, BACKEND_KOTLIN) || !SAFE_RUN_ID.matches(runId)) {
                    throw InteropFailure("Android private file identity was invalid")
                }
                val stem = "frpc-ab-${profile.caseId}-$backend-$runId"
                val configFile = "$stem-config.json"
                val resultFile = "$stem-result.json"
                val temporaryResultFile = temporaryPrivateFileName(resultFile)
                val restartMarkers = if (profile.isRestart) {
                    AndroidInteropRestartMarkers(
                        deviceReadyFile = "$stem-device-ready.marker",
                        frpsStoppedFile = "$stem-frps-stopped.marker",
                        deviceDisconnectedFile = "$stem-device-disconnected.marker",
                        frpsRestartedFile = "$stem-frps-restarted.marker",
                    )
                } else {
                    null
                }
                val names = buildList {
                    add(configFile)
                    add(resultFile)
                    add(temporaryResultFile)
                    restartMarkers?.fileNames?.forEach { marker ->
                        add(marker)
                        add(temporaryPrivateFileName(marker))
                    }
                }
                if (names.distinct().size != names.size || names.any { !SAFE_PRIVATE_FILE.matches(it) }) {
                    throw InteropFailure("Android private file names were invalid or not unique")
                }
                return AndroidInteropPrivateFiles(
                    configFile = configFile,
                    resultFile = resultFile,
                    temporaryResultFile = temporaryResultFile,
                    restartMarkers = restartMarkers,
                    allNames = names,
                )
            }
        }
    }

    private data class AndroidInteropSecrets(
        val authToken: String,
        val stcpSecret: String,
        val wrongAuthToken: String,
        val wrongStcpSecret: String,
    ) {
        override fun toString(): String =
            "AndroidInteropSecrets(authToken=<redacted>, stcpSecret=<redacted>, " +
                "wrongAuthToken=<redacted>, wrongStcpSecret=<redacted>)"

        companion object {
            fun create(random: SecureRandom): AndroidInteropSecrets = AndroidInteropSecrets(
                authToken = randomValue(random, "auth"),
                stcpSecret = randomValue(random, "stcp"),
                wrongAuthToken = randomValue(random, "wrong-auth"),
                wrongStcpSecret = randomValue(random, "wrong-stcp"),
            )

            private fun randomValue(random: SecureRandom, prefix: String): String {
                val bytes = ByteArray(32)
                random.nextBytes(bytes)
                return try {
                    "$prefix-${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
                } finally {
                    bytes.fill(0)
                }
            }
        }
    }

    private companion object {
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val SAFE_TOML_VALUE = Regex("[A-Za-z0-9_-]{16,256}")
        val SAFE_DEVICE_SERIAL = Regex("[A-Za-z0-9._:-]{1,128}")
        val SAFE_PRIVATE_FILE = Regex("[A-Za-z0-9_-]{1,112}\\.(json|marker)")
        val SAFE_RESULT_VALUE = Regex("[A-Za-z0-9_]{1,96}")
        val FRPS_READY_PATTERN = Regex("(?m)frps started successfully")
        val PROVIDER_PROXY_READY_PATTERN = Regex("(?m)\\[$PROVIDER_PROXY_NAME] start proxy success")
        const val TEST_PACKAGE = "io.github.ycfeng.ocdeck.frpcstcpvisitor.test"
        const val TEST_APK_FILE_NAME = "frpc-stcp-visitor-debug-androidTest.apk"
        const val TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
        const val TEST_CLASS =
            "io.github.ycfeng.ocdeck.frpcstcpvisitor.interop.FrpcStcpVisitorAndroidInteropTest"
        const val TEST_METHOD = "runConfiguredBackend"
        const val PROVIDER_USER = "interop-server"
        const val VISITOR_USER = "interop-visitor"
        const val PROVIDER_PROXY_NAME = "interop-opencode"
        const val ADB_PROPERTY = "ocdeck.frp.androidInterop.adb"
        const val APK_DIRECTORY_PROPERTY = "ocdeck.frp.androidInterop.apkDirectory"
        const val DEVICE_SERIAL_PROPERTY = "ocdeck.frp.androidInterop.deviceSerial"
        const val SUITE_PROPERTY = "ocdeck.frp.androidInterop.suite"
        const val SUMMARY_FILE_PROPERTY = "ocdeck.frp.androidInterop.summaryFile"
        const val FRP_CACHE_PROPERTY = "ocdeck.frp.interop.cacheDir"
        const val MARKER_DEVICE_READY = "device_ready"
        const val MARKER_FRPS_STOPPED = "frps_stopped"
        const val MARKER_DEVICE_DISCONNECTED = "device_disconnected"
        const val MARKER_FRPS_RESTARTED = "frps_restarted"
        const val MAXIMUM_APK_CANDIDATES = 8
        const val CONCURRENT_TRAFFIC_REQUESTS = 5
        const val MAXIMUM_PRIVATE_FILE_BYTES = 16 * 1024
        const val MAXIMUM_RESULT_BYTES = 16 * 1024
        const val PORT_PROBE_TIMEOUT_MILLIS = 250
        const val PROCESS_READY_TIMEOUT_MILLIS = 15_000L
        const val PROVIDER_RECONNECT_TIMEOUT_MILLIS = 30_000L
        const val ADB_SHORT_TIMEOUT_MILLIS = 15_000L
        const val APK_INSTALL_TIMEOUT_MILLIS = 60_000L
        const val INSTRUMENTATION_TIMEOUT_MILLIS = 360_000L
        const val MARKER_WAIT_TIMEOUT_MILLIS = 90_000L
        const val MARKER_POLL_MILLIS = 100L
        const val ASYNC_OBSERVE_TIMEOUT_MILLIS = 1L
        const val ASYNC_INSTRUMENTATION_STOP_TIMEOUT_MILLIS = 15_000L
        const val ASYNC_EXECUTOR_STOP_TIMEOUT_MILLIS = 15_000L
        const val ASYNC_EXECUTOR_FORCE_TIMEOUT_MILLIS = 5_000L
        const val RESOURCE_CLEANUP_TIMEOUT_MILLIS = 45_000L
        const val PAGE_SIZE_SHELL =
            "while read key value unit rest; do " +
                "case \"\$key\" in KernelPageSize:|MMUPageSize:) " +
                "case \"\$unit\" in kB) echo \$((value * 1024)); exit 0;; " +
                "B) echo \"\$value\"; exit 0;; esac;; esac; " +
                "done < /proc/self/smaps; exit 1"
    }
}

private fun temporaryPrivateFileName(name: String): String {
    val separator = name.lastIndexOf('.')
    if (separator <= 0 || separator == name.lastIndex) {
        throw InteropFailure("Android private file name was invalid")
    }
    return name.substring(0, separator) + "-tmp" + name.substring(separator)
}

internal fun recoverOwnedReversePort(
    output: String,
    targetPort: Int,
    baselinePorts: Set<Int>,
): Int? {
    val added = reversePortsForTarget(output, targetPort).filterNot(baselinePorts::contains)
    if (added.size > 1) throw InteropFailure("adb reverse ownership recovery was ambiguous")
    return added.singleOrNull()
}

private fun reversePortsForTarget(output: String, targetPort: Int): List<Int> = output.lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .mapNotNull { line ->
        val columns = line.split(Regex("\\s+"))
        if (columns.size != 3 || columns[2] != "tcp:$targetPort") return@mapNotNull null
        columns[1].removePrefix("tcp:").toIntOrNull()?.takeIf { it in 1..65_535 }
            ?: throw InteropFailure("adb reverse ownership output was invalid")
    }
    .distinct()
    .toList()
