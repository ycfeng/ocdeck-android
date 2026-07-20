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
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object FrpcAndroidInteropHost {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.isNotEmpty()) throw InteropFailure("frpcAndroidInteropTest does not accept arguments")
            FrpcAndroidInteropRunner().run()
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
        var selectedFailure: InteropFailure? = null
        val shutdownHook = Thread(
            { resources.cleanup() },
            "frpc-android-interop-shutdown-cleanup",
        ).apply { isDaemon = false }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            stage = "temporary workspace creation"
            val temporaryDirectory = resources.createTemporaryWorkspace()
            val cacheDirectory = configuredCacheDirectory()
            val secrets = AndroidInteropSecrets.create(secureRandom)
            redactor = InteropRedactor(
                temporaryDirectory.toString(),
                cacheDirectory.toString(),
                secrets.authToken,
                secrets.stcpSecret,
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

            stage = "frps startup"
            val frps = resources.startFrps(
                ManagedInteropProcess.start(
                    label = "android-frps",
                    executable = binaries.frps,
                    arguments = listOf("-c", frpsConfig.toString()),
                    workingDirectory = temporaryDirectory,
                    redactor = redactor,
                ),
            )
            awaitFrpsReady(frpsPort, frps)

            stage = "provider frpc startup"
            val provider = resources.startProvider(
                ManagedInteropProcess.start(
                    label = "android-provider-frpc",
                    executable = binaries.frpc,
                    arguments = listOf("-c", providerConfig.toString()),
                    workingDirectory = temporaryDirectory,
                    redactor = redactor,
                ),
            )
            provider.awaitOutput(PROVIDER_READY_PATTERN, PROCESS_READY_TIMEOUT_MILLIS, stage)

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
            stage = "GoMobile Android interoperability scenario"
            val goMobile = runBackend(
                resources = resources,
                adb = adb,
                syntheticServer = syntheticServer,
                runId = runId,
                backend = BACKEND_GO_MOBILE,
                serverPort = deviceFrpsPort,
                bindPort = 0,
                secrets = secrets,
            )
            stage = "Kotlin Android interoperability scenario"
            val kotlin = runBackend(
                resources = resources,
                adb = adb,
                syntheticServer = syntheticServer,
                runId = runId,
                backend = BACKEND_KOTLIN,
                serverPort = deviceFrpsPort,
                bindPort = goMobile.bindPort,
                secrets = secrets,
            )
            stage = "backend result comparison"
            requireEquivalent(goMobile, kotlin)
            syntheticServer.requireHealthy()
            frps.requireAlive(stage)
            provider.requireAlive(stage)
            println(
                "frpc Android interop passed: api=${device.apiLevel} abi=${device.abi} " +
                    "pageSize=${device.pageSize} order=gomobile,kotlin wire=v1 encryption=false compression=false",
            )
        } catch (failure: Exception) {
            selectedFailure = safeFailure(stage, failure, resources)
        } finally {
            stage = "resource cleanup"
            val cleanupFailure = resources.cleanup()
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
                // JVM shutdown has already started; the hook owns cleanup.
            }
            if (cleanupFailure is Error) throw cleanupFailure
            if (cleanupFailure != null) {
                val safeCleanup = InteropFailure("Android interoperability resource cleanup failed")
                selectedFailure = selectedFailure?.let { current ->
                    InteropFailure("${current.message.orEmpty()}\n${safeCleanup.message.orEmpty()}")
                } ?: safeCleanup
            }
        }
        selectedFailure?.let { throw it }
    }

    private fun runBackend(
        resources: AndroidInteropResourceOwner,
        adb: AdbCommandRunner,
        syntheticServer: SyntheticOpenCodeServer,
        runId: String,
        backend: String,
        serverPort: Int,
        bindPort: Int,
        secrets: AndroidInteropSecrets,
    ): AndroidInteropResult {
        val suffix = backend.replace('-', '_')
        val configFile = "frpc-ab-$suffix-config.json"
        val resultFile = "frpc-ab-$suffix-result.json"
        resources.registerPrivateFile(configFile)
        resources.registerPrivateFile(resultFile)
        val invocation = AndroidInteropInvocation(
            schemaVersion = SCHEMA_VERSION,
            runId = runId,
            backend = backend,
            serverPort = serverPort,
            authToken = secrets.authToken,
            stcpSecret = secrets.stcpSecret,
            bindPort = bindPort,
            wireProtocol = "v1",
            useEncryption = false,
            useCompression = false,
        )
        val configBytes = JSON.encodeToString(invocation).toByteArray(StandardCharsets.UTF_8)
        try {
            adb.run(
                label = "$backend private directory preparation",
                arguments = listOf("shell", "run-as", TEST_PACKAGE, "mkdir", "-p", "files"),
                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            )
            adb.run(
                label = "$backend private configuration write",
                arguments = listOf(
                    "shell",
                    "-T",
                    "run-as",
                    TEST_PACKAGE,
                    "dd",
                    "of=files/$configFile",
                    "bs=1",
                    "count=${configBytes.size}",
                ),
                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
                standardInput = configBytes,
            )
            adb.run(
                label = "$backend private configuration permission",
                arguments = listOf("shell", "run-as", TEST_PACKAGE, "chmod", "600", "files/$configFile"),
                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            )
        } finally {
            configBytes.fill(0)
        }

        var gate: ConcurrentTrafficGate? = null
        try {
            gate = syntheticServer.beginConcurrentTraffic(CONCURRENT_TRAFFIC_REQUESTS)
            val instrumentation = adb.run(
                label = "$backend instrumentation",
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
                    runId,
                    "-e",
                    "backend",
                    backend,
                    "-e",
                    "configFile",
                    configFile,
                    "-e",
                    "resultFile",
                    resultFile,
                    "$TEST_PACKAGE/$TEST_RUNNER",
                ),
                timeoutMillis = INSTRUMENTATION_TIMEOUT_MILLIS,
                requireSuccess = false,
            )
            val resultOutput = adb.run(
                label = "$backend result read",
                arguments = listOf("exec-out", "run-as", TEST_PACKAGE, "cat", "files/$resultFile"),
                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            ).standardOutput
            val result = try {
                JSON.decodeFromString<AndroidInteropResult>(resultOutput)
            } catch (_: Exception) {
                throw InteropFailure("$backend returned an invalid bounded result")
            }
            validateResult(result, runId, backend)
            if (result.status != STATUS_PASSED) {
                throw InteropFailure(
                    "$backend Android scenario failed at ${result.failedStage} with ${result.failureCode}",
                )
            }
            if (instrumentation.exitCode != 0 ||
                "INSTRUMENTATION_CODE: -1" !in instrumentation.standardOutput ||
                "FAILURES!!!" in instrumentation.standardOutput ||
                "INSTRUMENTATION_FAILED" in instrumentation.standardOutput
            ) {
                throw InteropFailure("$backend instrumentation did not complete successfully")
            }
            gate.requireSatisfied()
            syntheticServer.requireHealthy()
            return result
        } finally {
            gate?.let { activeGate ->
                try {
                    syntheticServer.endConcurrentTraffic(activeGate)
                } catch (_: Exception) {
                    // The primary scenario or final health check remains authoritative.
                }
            }
            resources.forceStopAndDeletePrivateFiles()
        }
    }

    private fun validateResult(result: AndroidInteropResult, runId: String, backend: String) {
        if (result.schemaVersion != SCHEMA_VERSION || result.runId != runId || result.backend != backend ||
            result.status !in setOf(STATUS_PASSED, STATUS_FAILED) || result.bindPort !in 0..65_535 ||
            (result.failedStage != null && !SAFE_RESULT_VALUE.matches(result.failedStage)) ||
            (result.failureCode != null && !SAFE_RESULT_VALUE.matches(result.failureCode))
        ) {
            throw InteropFailure("$backend returned an invalid structural result")
        }
    }

    private fun requireEquivalent(first: AndroidInteropResult, second: AndroidInteropResult) {
        val required = listOf(
            first.openStateVerified,
            first.healthVerified,
            first.globalSseVerified,
            first.projectSseVerified,
            first.concurrentTrafficVerified,
            first.closedVerified,
            first.portReleased,
            second.openStateVerified,
            second.healthVerified,
            second.globalSseVerified,
            second.projectSseVerified,
            second.concurrentTrafficVerified,
            second.closedVerified,
            second.portReleased,
        )
        if (first.bindPort !in 1..65_535 || second.bindPort != first.bindPort || required.any { !it }) {
            throw InteropFailure("GoMobile and Kotlin Android results were not semantically equivalent")
        }
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
        val abi = property("ro.product.cpu.abi").takeIf(SUPPORTED_ABIS::contains)
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

        fun startFrps(process: ManagedInteropProcess): ManagedInteropProcess = synchronized(lock) {
            requireActive()
            if (frps != null) throw InteropFailure("frps was already registered")
            process.also { frps = it }
        }

        fun startProvider(process: ManagedInteropProcess): ManagedInteropProcess = synchronized(lock) {
            requireActive()
            if (provider != null) throw InteropFailure("provider frpc was already registered")
            process.also { provider = it }
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

        fun registerPrivateFile(name: String) = synchronized(lock) {
            requireActive()
            if (!SAFE_PRIVATE_FILE.matches(name)) throw InteropFailure("private file name was invalid")
            privateFiles += name
        }

        fun forceStopAndDeletePrivateFiles() {
            val runner = synchronized(lock) {
                requireActive()
                adb
            } ?: throw InteropFailure("adb was not configured")
            runner.run(
                label = "instrumentation force stop",
                arguments = listOf("shell", "am", "force-stop", TEST_PACKAGE),
                timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
            )
            val files = synchronized(lock) { privateFiles.toList() }
            if (files.isNotEmpty()) {
                runner.run(
                    label = "instrumentation private file cleanup",
                    arguments = listOf("shell", "run-as", TEST_PACKAGE, "rm", "-f") +
                        files.map { "files/$it" },
                    timeoutMillis = ADB_SHORT_TIMEOUT_MILLIS,
                )
                synchronized(lock) { privateFiles.removeAll(files.toSet()) }
            }
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

    @Serializable
    private data class AndroidInteropInvocation(
        val schemaVersion: Int,
        val runId: String,
        val backend: String,
        val serverPort: Int,
        val authToken: String,
        val stcpSecret: String,
        val bindPort: Int,
        val wireProtocol: String,
        val useEncryption: Boolean,
        val useCompression: Boolean,
    ) {
        override fun toString(): String =
            "AndroidInteropInvocation(backend=$backend, authToken=<redacted>, stcpSecret=<redacted>)"
    }

    @Serializable
    private data class AndroidInteropResult(
        val schemaVersion: Int,
        val runId: String,
        val backend: String,
        val status: String,
        val failedStage: String? = null,
        val failureCode: String? = null,
        val bindPort: Int = 0,
        val openStateVerified: Boolean = false,
        val healthVerified: Boolean = false,
        val globalSseVerified: Boolean = false,
        val projectSseVerified: Boolean = false,
        val concurrentTrafficVerified: Boolean = false,
        val closedVerified: Boolean = false,
        val portReleased: Boolean = false,
    )

    private data class AndroidDeviceMetadata(
        val apiLevel: Int,
        val abi: String,
        val pageSize: Int,
    )

    private data class AndroidInteropSecrets(
        val authToken: String,
        val stcpSecret: String,
    ) {
        override fun toString(): String =
            "AndroidInteropSecrets(authToken=<redacted>, stcpSecret=<redacted>)"

        companion object {
            fun create(random: SecureRandom): AndroidInteropSecrets = AndroidInteropSecrets(
                authToken = randomValue(random, "auth"),
                stcpSecret = randomValue(random, "stcp"),
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
        val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val SAFE_TOML_VALUE = Regex("[A-Za-z0-9_-]{16,256}")
        val SAFE_DEVICE_SERIAL = Regex("[A-Za-z0-9._:-]{1,128}")
        val SAFE_PRIVATE_FILE = Regex("[A-Za-z0-9_-]{1,96}\\.json")
        val SAFE_RESULT_VALUE = Regex("[A-Za-z0-9_]{1,96}")
        val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        val FRPS_READY_PATTERN = Regex("(?m)frps started successfully")
        val PROVIDER_READY_PATTERN = Regex("(?m)login to server success")
        const val SCHEMA_VERSION = 1
        const val STATUS_PASSED = "passed"
        const val STATUS_FAILED = "failed"
        const val BACKEND_GO_MOBILE = "gomobile"
        const val BACKEND_KOTLIN = "kotlin"
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
        const val FRP_CACHE_PROPERTY = "ocdeck.frp.interop.cacheDir"
        const val MAXIMUM_APK_CANDIDATES = 8
        const val CONCURRENT_TRAFFIC_REQUESTS = 4
        const val PORT_PROBE_TIMEOUT_MILLIS = 250
        const val PROCESS_READY_TIMEOUT_MILLIS = 15_000L
        const val ADB_SHORT_TIMEOUT_MILLIS = 15_000L
        const val APK_INSTALL_TIMEOUT_MILLIS = 60_000L
        const val INSTRUMENTATION_TIMEOUT_MILLIS = 180_000L
        const val RESOURCE_CLEANUP_TIMEOUT_MILLIS = 45_000L
        const val PAGE_SIZE_SHELL =
            "while read key value unit rest; do " +
                "case \"\$key\" in KernelPageSize:|MMUPageSize:) " +
                "case \"\$unit\" in kB) echo \$((value * 1024)); exit 0;; " +
                "B) echo \"\$value\"; exit 0;; esac;; esac; " +
                "done < /proc/self/smaps; exit 1"
    }
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
