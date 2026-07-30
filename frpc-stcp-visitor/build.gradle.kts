import groovy.json.JsonSlurper
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.api.file.Directory

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

val bridgeVersionsFile = rootProject.file("frpc-stcp-visitor-go/bridge-versions.properties")
val bridgeVersions = Properties().apply {
    bridgeVersionsFile.inputStream().use(::load)
}
val goMobileBridgeVersion = bridgeVersions.getProperty("BRIDGE_VERSION")
val goMobileBridgeAar = rootProject.file(
    "frpc-stcp-visitor-go/build/repo/io/github/ycfeng/ocdeck/frpc-stcp-visitor-gobridge/" +
        "$goMobileBridgeVersion/frpc-stcp-visitor-gobridge-$goMobileBridgeVersion.aar",
)
val goMobileBridgeSha = rootProject.file("${goMobileBridgeAar.absolutePath}.sha256")
val goMobileBridgeApi = rootProject.file("${goMobileBridgeAar.absolutePath}.api.txt")
val goMobileBridgeProvenance = rootProject.file("${goMobileBridgeAar.absolutePath}.provenance.json")
val goMobileBridgeFrpProvenance = rootProject.file("${goMobileBridgeAar.absolutePath}.frp-provenance.json")
val goMobileBridgeNative = rootProject.file("${goMobileBridgeAar.absolutePath}.native.json")
val goMobileBridgePom = rootProject.file(
    "frpc-stcp-visitor-go/build/repo/io/github/ycfeng/ocdeck/frpc-stcp-visitor-gobridge/" +
        "$goMobileBridgeVersion/frpc-stcp-visitor-gobridge-$goMobileBridgeVersion.pom",
)
val expectedBridgeApi = rootProject.file("frpc-stcp-visitor-go/api/frpcstcpvisitor.txt")
val expectedFrpProvenance = rootProject.file("frpc-stcp-visitor-go/build/frp-patch-provenance.json")

fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    input.use {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun sha256(file: File): String = sha256(file.inputStream())

fun normalizeTextLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

fun parseJsonObject(file: File): Map<*, *> {
    val parsed = JsonSlurper().parse(file)
    check(parsed is Map<*, *>) { "Expected a JSON object in ${file.absolutePath}" }
    return parsed
}

android {
    namespace = "io.github.ycfeng.ocdeck.frpcstcpvisitor"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
        testApplicationId = "io.github.ycfeng.ocdeck.frpcstcpvisitor.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        targetSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        create("canary") {
            initWith(getByName("debug"))
        }
        create("kotlinRelease") {
            initWith(getByName("release"))
        }
        create("kotlinDefaultReleaseDryRun") {
            initWith(getByName("release"))
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.bcprov)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    if (goMobileBridgeAar.exists()) {
        debugRuntimeOnly("io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:$goMobileBridgeVersion")
        releaseRuntimeOnly("io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:$goMobileBridgeVersion")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

val frpcInteropTest = tasks.register<JavaExec>("frpcInteropTest") {
    group = "verification"
    description = "Runs the pinned official-frp interoperability harness for the Kotlin STCP visitor."
    dependsOn(
        "compileDebugUnitTestKotlin",
        "compileDebugUnitTestJavaWithJavac",
        "processDebugUnitTestJavaRes",
    )
    mainClass.set("io.github.ycfeng.ocdeck.frpcstcpvisitor.interop.FrpcInteropHarness")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    workingDir(rootProject.projectDir)
    systemProperty(
        "ocdeck.frp.interop.cacheDir",
        gradle.gradleUserHomeDir.resolve("caches/ocdeck/frp-interop/v0.69.1").absolutePath,
    )
    jvmArgs("-Dfile.encoding=UTF-8")
    maxHeapSize = "512m"
}

val adbExecutable = androidComponents.sdkComponents.adb
val androidInteropApkDirectory = layout.buildDirectory.dir("outputs/apk/androidTest/debug")
val appKotlinReleaseApkDirectory = rootProject.layout.projectDirectory.dir("app/build/outputs/apk/kotlinRelease")
val appKotlinReleaseTestApkDirectory =
    rootProject.layout.projectDirectory.dir("app/build/outputs/apk/androidTest/kotlinRelease")
val appGoMobileReleaseApkDirectory = rootProject.layout.projectDirectory.dir("app/build/outputs/apk/goMobileRelease")
val appGoMobileReleaseTestApkDirectory =
    rootProject.layout.projectDirectory.dir("app/build/outputs/apk/androidTest/goMobileRelease")

tasks.register<JavaExec>("frpcAndroidInteropTest") {
    group = "verification"
    description = "Runs the selected real GoMobile/Kotlin Android STCP differential suite."
    dependsOn(
        "checkGoMobileBridgeAar",
        "assembleDebugAndroidTest",
        "compileDebugUnitTestKotlin",
        "compileDebugUnitTestJavaWithJavac",
        "processDebugUnitTestJavaRes",
    )
    mainClass.set("io.github.ycfeng.ocdeck.frpcstcpvisitor.interop.FrpcAndroidInteropHost")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    workingDir(rootProject.projectDir)
    systemProperty(
        "ocdeck.frp.interop.cacheDir",
        gradle.gradleUserHomeDir.resolve("caches/ocdeck/frp-interop/v0.69.1").absolutePath,
    )
    jvmArgs("-Dfile.encoding=UTF-8")
    maxHeapSize = "512m"
    doFirst {
        systemProperty("ocdeck.frp.androidInterop.adb", adbExecutable.get().asFile.absolutePath)
        systemProperty(
            "ocdeck.frp.androidInterop.apkDirectory",
            androidInteropApkDirectory.get().asFile.absolutePath,
        )
        providers.gradleProperty("ocdeck.frp.androidInterop.deviceSerial").orNull?.let { serial ->
            systemProperty("ocdeck.frp.androidInterop.deviceSerial", serial)
        }
        providers.gradleProperty("ocdeck.frp.androidInterop.suite").orNull?.let { suite ->
            systemProperty("ocdeck.frp.androidInterop.suite", suite)
        }
        providers.gradleProperty("ocdeck.frp.androidInterop.summaryFile").orNull?.let { summaryFile ->
            systemProperty("ocdeck.frp.androidInterop.summaryFile", summaryFile)
        }
    }
}

fun registerAppE2eTask(
    name: String,
    backend: String,
    variantName: String,
    appApkDirectory: Directory,
    testApkDirectory: Directory,
    resourceMode: Boolean,
) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = if (resourceMode) {
        "Runs the $backend Release-Like App core workload with bounded process resource evidence."
    } else {
        "Runs the $backend Release-Like App Store/SSE/snapshot STCP end-to-end gate."
    }
    dependsOn(
        "checkGoMobileBridgeAar",
        ":app:assemble$variantName",
        ":app:assemble${variantName}AndroidTest",
        "compileDebugUnitTestKotlin",
        "compileDebugUnitTestJavaWithJavac",
        "processDebugUnitTestJavaRes",
    )
    mainClass.set("io.github.ycfeng.ocdeck.frpcstcpvisitor.interop.FrpcAppE2eHost")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    workingDir(rootProject.projectDir)
    systemProperty(
        "ocdeck.frp.interop.cacheDir",
        gradle.gradleUserHomeDir.resolve("caches/ocdeck/frp-interop/v0.69.1").absolutePath,
    )
    jvmArgs("-Dfile.encoding=UTF-8")
    maxHeapSize = "512m"
    doFirst {
        systemProperty("ocdeck.frp.appE2e.adb", adbExecutable.get().asFile.absolutePath)
        systemProperty(
            "ocdeck.frp.appE2e.appApkDirectory",
            appApkDirectory.asFile.absolutePath,
        )
        systemProperty(
            "ocdeck.frp.appE2e.testApkDirectory",
            testApkDirectory.asFile.absolutePath,
        )
        systemProperty("ocdeck.frp.appE2e.backend", backend)
        val configuredDeviceSerial = providers.gradleProperty("ocdeck.frp.appE2e.deviceSerial").orNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (resourceMode && configuredDeviceSerial == null) {
            throw GradleException(
                "ocdeck.frp.appE2e.deviceSerial is required for resource evidence tasks.",
            )
        }
        configuredDeviceSerial?.let { serial ->
            systemProperty("ocdeck.frp.appE2e.deviceSerial", serial)
        }
        providers.gradleProperty("ocdeck.frp.appE2e.runtimeDiagnostic.enabled").orNull?.let { enabled ->
            systemProperty("ocdeck.frp.appE2e.runtimeDiagnostic.enabled", enabled)
        }
        if (resourceMode) {
            environment("OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE", "")
            systemProperty("ocdeck.frp.appE2e.suite", "core")
            systemProperty(
                "ocdeck.frp.appE2e.profile",
                providers.gradleProperty("ocdeck.frp.appResource.profile").orNull ?: "baseline",
            )
            listOf(
                "iterations",
                "concurrency",
                "durationMillis",
                "reconnectEveryIterations",
                "sampleEveryIterations",
            ).forEach { propertyName ->
                providers.gradleProperty("ocdeck.frp.appResource.$propertyName").orNull?.let { value ->
                    systemProperty("ocdeck.frp.appE2e.$propertyName", value)
                }
            }
            val evidenceDirectory = providers.gradleProperty("ocdeck.frp.appResource.evidenceDirectory").orNull
                ?: throw GradleException(
                    "ocdeck.frp.appResource.evidenceDirectory is required for resource evidence tasks.",
                )
            val evidenceParent = rootProject.file(evidenceDirectory)
            check(evidenceParent.isDirectory) {
                "App resource evidence directory must already exist."
            }
            val evidenceFile = evidenceParent.resolve("$backend-app-resource.json")
            systemProperty("ocdeck.frp.appE2e.summaryFile", evidenceFile.absolutePath)
        } else {
            providers.gradleProperty("ocdeck.frp.appE2e.suite").orNull?.let { suite ->
                systemProperty("ocdeck.frp.appE2e.suite", suite)
            }
            providers.gradleProperty("ocdeck.frp.appE2e.outageMillis").orNull?.let { outageMillis ->
                systemProperty("ocdeck.frp.appE2e.outageMillis", outageMillis)
            }
            providers.gradleProperty("ocdeck.frp.appE2e.summaryFile").orNull?.let { summaryFile ->
                systemProperty("ocdeck.frp.appE2e.summaryFile", summaryFile)
            }
        }
    }
}

registerAppE2eTask(
    name = "frpcAppE2eTest",
    backend = "kotlin",
    variantName = "KotlinRelease",
    appApkDirectory = appKotlinReleaseApkDirectory,
    testApkDirectory = appKotlinReleaseTestApkDirectory,
    resourceMode = false,
)
registerAppE2eTask(
    name = "frpcGoMobileAppE2eTest",
    backend = "gomobile",
    variantName = "GoMobileRelease",
    appApkDirectory = appGoMobileReleaseApkDirectory,
    testApkDirectory = appGoMobileReleaseTestApkDirectory,
    resourceMode = false,
)
val frpcGoMobileAppResourceTest = registerAppE2eTask(
    name = "frpcGoMobileAppResourceTest",
    backend = "gomobile",
    variantName = "GoMobileRelease",
    appApkDirectory = appGoMobileReleaseApkDirectory,
    testApkDirectory = appGoMobileReleaseTestApkDirectory,
    resourceMode = true,
)
val frpcKotlinAppResourceTest = registerAppE2eTask(
    name = "frpcKotlinAppResourceTest",
    backend = "kotlin",
    variantName = "KotlinRelease",
    appApkDirectory = appKotlinReleaseApkDirectory,
    testApkDirectory = appKotlinReleaseTestApkDirectory,
    resourceMode = true,
)
val appResourceBackendOrder = providers.gradleProperty("ocdeck.frp.appResource.backendOrder").orNull
    ?.split(',')
    ?.map(String::trim)
    ?: listOf("gomobile", "kotlin")
check(appResourceBackendOrder == listOf("gomobile", "kotlin") ||
    appResourceBackendOrder == listOf("kotlin", "gomobile")
) {
    "ocdeck.frp.appResource.backendOrder must be gomobile,kotlin or kotlin,gomobile."
}
if (appResourceBackendOrder.first() == "gomobile") {
    frpcKotlinAppResourceTest.configure { mustRunAfter(frpcGoMobileAppResourceTest) }
} else {
    frpcGoMobileAppResourceTest.configure { mustRunAfter(frpcKotlinAppResourceTest) }
}
val frpcValidateAppResourceEvidence = tasks.register<JavaExec>("frpcValidateAppResourceEvidence") {
    group = "verification"
    description = "Validates paired App resource evidence against the versioned K7 policy."
    dependsOn(
        "compileDebugUnitTestKotlin",
        "compileDebugUnitTestJavaWithJavac",
        "processDebugUnitTestJavaRes",
    )
    mainClass.set("io.github.ycfeng.ocdeck.frpcstcpvisitor.interop.AppResourceEvidencePolicyKt")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    workingDir(rootProject.projectDir)
    jvmArgs("-Dfile.encoding=UTF-8")
    maxHeapSize = "256m"
    doFirst {
        val configuredDirectory = providers.gradleProperty("ocdeck.frp.appResource.evidenceDirectory").orNull
            ?: throw GradleException(
                "ocdeck.frp.appResource.evidenceDirectory is required for resource evidence validation.",
            )
        args(rootProject.file(configuredDirectory).absolutePath)
    }
}
frpcValidateAppResourceEvidence.configure {
    mustRunAfter(frpcGoMobileAppResourceTest, frpcKotlinAppResourceTest)
}
tasks.register("frpcAppResourceTest") {
    group = "verification"
    description = "Runs paired App resource evidence and validates it against the versioned K7 policy."
    dependsOn(frpcGoMobileAppResourceTest, frpcKotlinAppResourceTest, frpcValidateAppResourceEvidence)
}

tasks.register("checkGoMobileBridgeAar") {
    doLast {
        check(goMobileBridgeAar.exists()) {
            "GoMobile STCP visitor AAR is missing. Run frpc-stcp-visitor-go/build-aar.ps1 or build-aar.sh."
        }
        check(goMobileBridgeSha.isFile) { "GoMobile STCP visitor AAR checksum is missing." }
        check(goMobileBridgeApi.isFile) { "GoMobile STCP visitor API signature is missing." }
        check(goMobileBridgeProvenance.isFile) { "GoMobile STCP visitor provenance is missing." }
        check(goMobileBridgeFrpProvenance.isFile) { "GoMobile STCP visitor frp provenance is missing." }
        check(goMobileBridgeNative.isFile) { "GoMobile STCP visitor native validation metadata is missing." }
        check(goMobileBridgePom.isFile) { "GoMobile STCP visitor Maven POM is missing." }
        check(expectedBridgeApi.isFile) { "Expected GoMobile STCP visitor API signature is missing." }
        check(expectedFrpProvenance.isFile) { "Generated frp provenance is missing. Run preparefrp first." }
        val checksumParts = goMobileBridgeSha.readText().trim().split(Regex("\\s+"))
        check(checksumParts.size == 2 && checksumParts[1] == goMobileBridgeAar.name) {
            "GoMobile STCP visitor checksum sidecar has an invalid filename or format."
        }
        val expectedSha = checksumParts[0]
        check(expectedSha.matches(Regex("[0-9a-f]{64}"))) {
            "GoMobile STCP visitor checksum is not a lowercase SHA-256 value."
        }
        check(sha256(goMobileBridgeAar) == expectedSha) {
            "GoMobile STCP visitor AAR checksum does not match its immutable artifact metadata."
        }
        val publishedApi = normalizeTextLineEndings(goMobileBridgeApi.readText())
        val committedApi = normalizeTextLineEndings(expectedBridgeApi.readText())
        check(publishedApi == committedApi) {
            "GoMobile STCP visitor API does not exactly match the committed signature."
        }

        val provenance = parseJsonObject(goMobileBridgeProvenance)
        val expectedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        check(provenance["schemaVersion"] == 2 && provenance["bridgeApiVersion"] == 2) {
            "GoMobile STCP visitor provenance schema or API version is invalid."
        }
        check(provenance["bridgeVersion"] == goMobileBridgeVersion) {
            "GoMobile STCP visitor provenance bridge version is invalid."
        }
        check(provenance["goVersion"] == bridgeVersions.getProperty("GO_VERSION")) {
            "GoMobile STCP visitor provenance Go version is invalid."
        }
        check(provenance["xMobileVersion"] == bridgeVersions.getProperty("XMOBILE_VERSION")) {
            "GoMobile STCP visitor provenance x/mobile version is invalid."
        }
        check((provenance["androidApi"] as? Number)?.toInt() == bridgeVersions.getProperty("ANDROID_API").toInt()) {
            "GoMobile STCP visitor provenance Android API is invalid."
        }
        check(provenance["ndkVersion"] == bridgeVersions.getProperty("NDK_VERSION")) {
            "GoMobile STCP visitor provenance NDK version is invalid."
        }
        check(provenance["frpVersion"] == "v0.69.1" && provenance["frpPatch"] == "frp-v0.69.1-p5") {
            "GoMobile STCP visitor provenance frp version is invalid."
        }
        check((provenance["nativePageAlignment"] as? Number)?.toInt() == 16384 && provenance["nativeStripped"] == true) {
            "GoMobile STCP visitor provenance native requirements are invalid."
        }
        check(provenance["nativeAbis"] == expectedAbis && provenance["aarSha256"] == expectedSha) {
            "GoMobile STCP visitor provenance ABI list or AAR checksum is invalid."
        }
        val moduleGraphSha = provenance["moduleGraphSha256"] as? String
        check(moduleGraphSha?.matches(Regex("[0-9a-f]{64}")) == true && provenance["moduleGraphLocalPathFree"] == true) {
            "GoMobile STCP visitor provenance module graph proof is invalid."
        }
        check(goMobileBridgeFrpProvenance.readBytes().contentEquals(expectedFrpProvenance.readBytes())) {
            "GoMobile STCP visitor frp provenance does not match the generated patch provenance."
        }

        val frpProvenance = parseJsonObject(goMobileBridgeFrpProvenance)
        check(frpProvenance["downstream"] == "frp-v0.69.1-p5" && frpProvenance["version"] == "v0.69.1") {
            "GoMobile STCP visitor frp provenance version is invalid."
        }
        check(
            frpProvenance["patchedFiles"] == listOf(
                "client/connector.go",
                "client/connector_tls_config_customizer_test.go",
                "client/control.go",
                "client/control_pending_work_conn_test.go",
                "client/control_shutdown_test.go",
                "client/runtime_state.go",
                "client/runtime_state_test.go",
                "client/service.go",
                "client/visitor/stcp.go",
                "client/visitor/sudp.go",
                "client/visitor/visitor.go",
                "client/visitor/visitor_manager.go",
                "client/visitor/visitor_manager_runtime_test.go",
                "client/visitor/visitor_outbound_shutdown_test.go",
                "client/visitor/visitor_shutdown_test.go",
                "pkg/msg/handler.go",
                "pkg/msg/handler_test.go",
            ),
        ) { "GoMobile STCP visitor frp provenance does not cover the complete patch." }
        check(
            frpProvenance["addedFiles"] == listOf(
                "client/connector_tls_config_customizer_test.go",
                "client/control_pending_work_conn_test.go",
                "client/control_shutdown_test.go",
                "client/runtime_state.go",
                "client/runtime_state_test.go",
                "client/visitor/visitor_manager_runtime_test.go",
                "client/visitor/visitor_outbound_shutdown_test.go",
                "client/visitor/visitor_shutdown_test.go",
                "pkg/msg/handler_test.go",
            ),
        ) { "GoMobile STCP visitor frp provenance added-file list is invalid." }

        val nativeMetadata = parseJsonObject(goMobileBridgeNative)
        check(
            nativeMetadata["schemaVersion"] == 2 &&
                (nativeMetadata["pageAlignment"] as? Number)?.toInt() == 16384 &&
                nativeMetadata["stripped"] == true,
        ) {
            "GoMobile STCP visitor native libraries are not verified as stripped and 16KB aligned."
        }
        check(
            nativeMetadata["moduleGraphSha256"] == moduleGraphSha &&
                nativeMetadata["moduleGraphLocalPathFree"] == true &&
                nativeMetadata["moduleGraphConsistentAcrossAbis"] == true,
        ) { "GoMobile STCP visitor native module graph proof is invalid." }
        val libraries = (nativeMetadata["libraries"] as? List<*>)
            ?.map { it as? Map<*, *> ?: error("Invalid native library report entry.") }
            ?: error("GoMobile STCP visitor native validation is missing libraries.")
        check(libraries.map { it["abi"] } == expectedAbis) {
            "GoMobile STCP visitor native validation ABI order or contents are invalid."
        }
        val expectedMachines = mapOf(
            "arm64-v8a" to "EM_AARCH64",
            "armeabi-v7a" to "EM_ARM",
            "x86" to "EM_386",
            "x86_64" to "EM_X86_64",
        )

        ZipFile(goMobileBridgeAar).use { archive ->
            val expectedMetadataFiles = linkedMapOf(
                "META-INF/OCDECK/LICENSE.txt" to rootProject.file("LICENSE"),
                "META-INF/OCDECK/NOTICE.txt" to rootProject.file("NOTICE"),
                "META-INF/OCDECK/THIRD_PARTY_NOTICES.txt" to rootProject.file("THIRD_PARTY_NOTICES.txt"),
                "META-INF/OCDECK/TRADEMARKS.md" to rootProject.file("TRADEMARKS.md"),
                "META-INF/OCDECK/bridge-api.txt" to expectedBridgeApi,
                "META-INF/OCDECK/frp-patch-provenance.json" to expectedFrpProvenance,
            )
            rootProject.file("third_party/licenses").listFiles()
                ?.filter(File::isFile)
                ?.sortedBy(File::getName)
                ?.forEach { license ->
                    expectedMetadataFiles["META-INF/OCDECK/licenses/${license.name}"] = license
                }
            expectedMetadataFiles.forEach { (path, source) ->
                val entry = archive.getEntry(path) ?: error("GoMobile STCP visitor AAR is missing $path.")
                val embeddedText = archive.getInputStream(entry).bufferedReader().use { it.readText() }
                check(normalizeTextLineEndings(embeddedText) == normalizeTextLineEndings(source.readText())) {
                    "GoMobile STCP visitor AAR metadata does not match $source."
                }
            }
            val embeddedProvenanceEntry = archive.getEntry("META-INF/OCDECK/bridge-provenance.json")
                ?: error("GoMobile STCP visitor AAR is missing embedded bridge provenance.")
            val embeddedProvenance = JsonSlurper().parse(archive.getInputStream(embeddedProvenanceEntry)) as? Map<*, *>
                ?: error("GoMobile STCP visitor embedded provenance is invalid.")
            check(embeddedProvenance == provenance.filterKeys { it != "aarSha256" }) {
                "GoMobile STCP visitor embedded and external provenance differ."
            }
            libraries.forEach { library ->
                val abi = library["abi"] as? String ?: error("Native report ABI is invalid.")
                check(library["machine"] == expectedMachines.getValue(abi)) {
                    "GoMobile STCP visitor native machine is invalid for $abi."
                }
                check((library["minLoadAlignment"] as? Number)?.toLong() ?: 0L >= 16384L) {
                    "GoMobile STCP visitor native alignment is invalid for $abi."
                }
                val entry = archive.getEntry("jni/$abi/libgojni.so")
                    ?: error("GoMobile STCP visitor AAR is missing native library for $abi.")
                check(sha256(archive.getInputStream(entry)) == library["sha256"]) {
                    "GoMobile STCP visitor native hash does not match the AAR for $abi."
                }
            }
        }
        val pomText = goMobileBridgePom.readText()
        check("<licenses>" in pomText && "<name>MIT License</name>" in pomText) {
            "GoMobile STCP visitor Maven POM is missing license metadata."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn("checkGoMobileBridgeAar")
}

if (providers.gradleProperty("requireGoMobileBridge").map(String::toBoolean).getOrElse(false)) {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn("checkGoMobileBridgeAar")
    }
}
