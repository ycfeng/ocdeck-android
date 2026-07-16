import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import java.net.URI
import java.util.Properties
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
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
