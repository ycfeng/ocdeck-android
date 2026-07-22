import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.api.variant.HostTestBuilder
import java.net.URI
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val apkApplicationName = "OCDeck"
val stableVersionPattern = Regex("""(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)""")
val appVersionName = providers.gradleProperty("VERSION_NAME").orNull
    ?.trim()
    ?.takeIf(stableVersionPattern::matches)
    ?: throw GradleException("VERSION_NAME must be a stable semantic version such as 1.0.0")
val appVersionCode = providers.gradleProperty("VERSION_CODE").orNull
    ?.trim()
    ?.toIntOrNull()
    ?.takeIf { it in 1..2_100_000_000 }
    ?: throw GradleException("VERSION_CODE must be an integer between 1 and 2100000000")
val releaseSigningPropertiesFile = rootProject.file("signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: releaseSigningProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val releaseSigningValues = mapOf(
    "RELEASE_STORE_FILE" to releaseSigningValue("RELEASE_STORE_FILE"),
    "RELEASE_STORE_PASSWORD" to releaseSigningValue("RELEASE_STORE_PASSWORD"),
    "RELEASE_KEY_ALIAS" to releaseSigningValue("RELEASE_KEY_ALIAS"),
    "RELEASE_KEY_PASSWORD" to releaseSigningValue("RELEASE_KEY_PASSWORD"),
)
val releaseSigningConfigured = releaseSigningValues.values.all { !it.isNullOrBlank() }
val projectUrl = providers.gradleProperty("PROJECT_URL").orNull
    ?.trim()
    .orEmpty()
    .also { value ->
        if (value.isNotEmpty()) {
            val uri = runCatching { URI(value) }.getOrNull()
            if (uri?.scheme != "https" || uri.host != "github.com" || uri.userInfo != null ||
                uri.query != null || uri.fragment != null
            ) {
                throw GradleException(
                    "PROJECT_URL must be an https://github.com URL without userinfo, query, or fragment",
                )
            }
        }
    }
val escapedProjectUrl = projectUrl.replace("\\", "\\\\").replace("\"", "\\\"")
val generatedLegalTextDirectory = layout.buildDirectory.dir("generated/legalText")
val generatedLegalAssetsDirectory = layout.buildDirectory.dir("generated/legalAssets")
val thirdPartyLicenseDirectory = rootProject.layout.projectDirectory.dir("third_party/licenses")
val pureKotlinBuildTypes = listOf("canary", "kotlinRelease")
val goMobileBridgeGroup = "io.github.ycfeng.ocdeck"
val goMobileBridgeModule = "frpc-stcp-visitor-gobridge"
val generateThirdPartyLicenseBundle = tasks.register("generateThirdPartyLicenseBundle") {
    val outputFile = generatedLegalTextDirectory.map { it.file("THIRD_PARTY_LICENSES.txt") }
    inputs.dir(thirdPartyLicenseDirectory)
    outputs.file(outputFile)
    doLast {
        val licenses = thirdPartyLicenseDirectory.asFile.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        if (licenses.isEmpty()) {
            throw GradleException("No third-party license files found in ${thirdPartyLicenseDirectory.asFile}")
        }
        val content = buildString {
            appendLine("OC DECK THIRD-PARTY LICENSE TEXTS")
            licenses.forEach { license ->
                appendLine()
                appendLine("================================================================================")
                appendLine(license.name)
                appendLine("================================================================================")
                append(license.readText(Charsets.UTF_8).trimEnd())
                appendLine()
            }
        }
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(content, Charsets.UTF_8)
    }
}
val generateLegalAssets = tasks.register<Sync>("generateLegalAssets") {
    dependsOn(generateThirdPartyLicenseBundle)
    into(generatedLegalAssetsDirectory)
    from(rootProject.file("LICENSE")) {
        into("legal")
        rename { "LICENSE.txt" }
    }
    from(rootProject.file("NOTICE")) {
        into("legal")
        rename { "NOTICE.txt" }
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.txt")) {
        into("legal")
    }
    from(rootProject.file("TRADEMARKS.md")) {
        into("legal")
    }
    from(thirdPartyLicenseDirectory) {
        into("legal/licenses")
    }
    from(generatedLegalTextDirectory) {
        into("legal")
    }
}

android {
    namespace = "io.github.ycfeng.ocdeck"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.ycfeng.ocdeck"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "PROJECT_URL", "\"$escapedProjectUrl\"")
        buildConfigField("boolean", "USE_KOTLIN_FRPC_STCP_VISITOR", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main").assets.directories.add(generatedLegalAssetsDirectory.get().asFile.absolutePath)

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            // GoMobile output is already stripped; preserve its AAR-verified bytes.
            keepDebugSymbols += "**/libgojni.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseSigningValues.getValue("RELEASE_STORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("RELEASE_STORE_PASSWORD")!!
                keyAlias = releaseSigningValues.getValue("RELEASE_KEY_ALIAS")!!
                keyPassword = releaseSigningValues.getValue("RELEASE_KEY_PASSWORD")!!
            }
        }
    }

    buildTypes {
        create("canary") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            applicationIdSuffix = ".canary"
            versionNameSuffix = "-canary"
            buildConfigField("boolean", "USE_KOTLIN_FRPC_STCP_VISITOR", "true")
        }
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("kotlinRelease") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".kotlinrelease"
            versionNameSuffix = "-kotlin-release"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "USE_KOTLIN_FRPC_STCP_VISITOR", "true")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateLegalAssets)
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        val missingKeys = releaseSigningValues.filterValues { it.isNullOrBlank() }.keys
        if (missingKeys.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing: ${missingKeys.joinToString()}. " +
                    "Set environment variables or create ignored root signing.properties from signing.properties.example.",
            )
        }

        val storeFile = rootProject.file(releaseSigningValues.getValue("RELEASE_STORE_FILE")!!)
        if (!storeFile.isFile) {
            throw GradleException("Release signing store file does not exist: ${storeFile.absolutePath}")
        }
    }
}

tasks.matching {
    it.name == "assembleRelease" || it.name == "bundleRelease" ||
        it.name == "packageRelease" || it.name == "signReleaseBundle"
}.configureEach {
    dependsOn(validateReleaseSigning)
}

androidComponents {
    pureKotlinBuildTypes.forEach { buildType ->
        beforeVariants(selector().withBuildType(buildType)) { variantBuilder ->
            variantBuilder.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
        }
    }
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == ABI }?.identifier
            output.outputFileName.set(
                output.versionName.map { versionName ->
                    if (abi == null) {
                        "${apkApplicationName}_$versionName.apk"
                    } else {
                        "${apkApplicationName}_${versionName}_$abi.apk"
                    }
                },
            )
        }
    }
}

val pureKotlinPackagingTasks = pureKotlinBuildTypes.map { buildType ->
    val variantName = buildType.replaceFirstChar(Char::uppercase)
    tasks.register("verify${variantName}Packaging") {
        group = "verification"
        description = "Verifies that $buildType APKs contain no GoMobile runtime artifacts."
        dependsOn("assemble$variantName")

        doLast {
            val bridgeComponents = configurations.getByName("${buildType}RuntimeClasspath")
                .incoming.resolutionResult.allComponents
                .map { it.id }
                .filterIsInstance<ModuleComponentIdentifier>()
                .filter { it.group == goMobileBridgeGroup && it.module == goMobileBridgeModule }
            check(bridgeComponents.isEmpty()) {
                "$buildType runtime classpath contains the GoMobile bridge: " +
                    bridgeComponents.joinToString { it.displayName }
            }

            val apkDirectory = layout.buildDirectory.dir("outputs/apk/$buildType").get().asFile
            val apks = apkDirectory.listFiles { file -> file.isFile && file.extension == "apk" }
                ?.sortedBy(File::getName)
                .orEmpty()
            check(apks.size == 3) {
                "Expected three ABI-split $buildType APKs in ${apkDirectory.absolutePath}, found ${apks.size}."
            }

            val nativeEntries = buildList {
                apks.forEach { apk ->
                    ZipFile(apk).use { archive ->
                        val entries = archive.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.endsWith("/libgojni.so")) {
                                add("${apk.name}!/${entry.name}")
                            }
                        }
                    }
                }
            }
            check(nativeEntries.isEmpty()) {
                "$buildType APKs contain GoMobile native libraries: ${nativeEntries.joinToString()}"
            }
        }
    }
}

tasks.register("verifyPureKotlinPackaging") {
    group = "verification"
    description = "Verifies that Canary and Kotlin Release-Like APKs exclude the GoMobile runtime."
    dependsOn(pureKotlinPackagingTasks)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":frpc-stcp-visitor"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.bcprov)
    implementation(libs.jsch)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markwon.core)
    implementation(libs.markwon.recycler.table)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.prism4j) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    compileOnly(libs.prism4j.bundler)
    annotationProcessor(libs.prism4j.bundler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
