# OC Deck Android Project Framework Plan

[简体中文](project-framework.zh-CN.md)

This document and the Chinese version are maintained in parallel. If the documents conflict, the current implementation, automated tests, and the [repository agent rules](../../AGENTS.md) take precedence.

## 1. Goals and Principles

This plan builds the OC Deck engineering framework from the interaction conclusions in `doc/architecture/mobile-interaction.md`. OC Deck is an independently maintained native Android client for OpenCode Server and is not an official product of or affiliated with OpenCode or Anomaly. The goal is not to mechanically copy the desktop Web UI, but to preserve the OpenCode information architecture and core workflows in a stable, evolvable, and verifiable Android project designed for phones.

The current project follows a conservative strategy: one business module, a separate STCP bridge library, manual dependency injection, and deferred Room/Hilt adoption. The primary paths for server connection, project selection, session list/details, ordinary prompts, SSE, and security redaction are implemented. Business code remains in `:app`; the GoMobile production client and pure Kotlin internal verification clients used by Canary and the non-publishable Kotlin Release-Like build are isolated behind the approved `:frpc-stcp-visitor` boundary. Database, Hilt, and business-module splitting are evaluated only against demonstrated needs.

Core principles:

- Mobile first: convert desktop popovers/dialogs into full-screen pages, screen-constrained anchored popups, bottom drawers, or bottom sheets according to content.
- Incremental construction: keep business implementation in a buildable, runnable, debuggable `:app` module. Do not prematurely split Gradle business modules beyond the approved native bridge.
- Server driven: OpenCode runs on the server; Android primarily expresses REST APIs, SSE state synchronization, and mobile interaction.
- Security first: API keys, tokens, passwords, headers, environment values, and sensitive configuration must not appear in logs, crash reports, debugging UI, or documentation examples.
- Path consistency: normalize every `directory` before it enters the network layer so `E:\\path` and `E:/path` cannot represent separate projects.

## 2. Technology Stack

Version selection is based on official Android documentation, Maven metadata, and official Gradle version interfaces.

| Category | Selection | Notes |
| --- | --- | --- |
| Language | Kotlin `2.4.0` | Stable language line; avoid `2.4.20-Beta1` |
| Build tool | Gradle `9.6.1` | Current final Gradle release satisfying AGP 9.2 minimum requirements |
| Android Gradle Plugin | `9.2.1` | Stable; avoids the R8 `RecordTag` issue fixed after `9.2.0` and avoids `9.4.0-alpha02` |
| JDK | JDK `21` | Use JDK 21 for Gradle and Java/Kotlin toolchains |
| SDK | minSdk `26`, compileSdk `36`, targetSdk `36` | Android 16/API 36 is stable; AGP support for API 37 does not justify a preview platform |
| Build Tools | `36.0.0` | AGP 9.2 default SDK Build Tools |
| UI | Jetpack Compose + Material 3 | Single Activity, declarative UI, efficient mobile adaptation |
| Compose BOM | `2026.06.01` | Manages Compose dependency versions |
| Compose Compiler | Kotlin Compose plugin `2.4.0` | Matches Kotlin |
| Navigation | `androidx.navigation:navigation-compose:2.9.8` | Stable; string routes are centrally wrapped, new routes prefer typed routes, and existing routes migrate gradually |
| Activity Compose | `1.13.0` | Stable |
| Lifecycle | `2.10.0` | ViewModel, runtime Compose, and lifecycle-aware state collection; `2.11.0` AAR metadata requires compileSdk 37, so the first version remains on SDK 36 |
| Network | Retrofit `3.0.0` + OkHttp `5.4.0` | Retrofit for ordinary REST; session messages use a narrow transport on the same OkHttpClient to bypass Retrofit error-body caching while sharing auth, redirects, redacted logging, and timeouts |
| SSE | OkHttp `Call` + custom bounded reader | Connects `/global/event` and `/event?directory=...`; retain `okhttp-sse:5.4.0` as a dependency but do not use its parser in production |
| JSON | kotlinx.serialization `1.11.0` | DTO serialization with unknown fields ignored by default |
| Concurrency | kotlinx.coroutines `1.11.0` | Repository, SSE, and ViewModel state flows |
| STCP clients | Go `1.26.4` + GoMobile/x-mobile `4dd8f1dbf5d2` + NDK `27.1.12297006` + frp `v0.69.1-p1`, plus a pure Kotlin client | `bridge-versions.properties` pins the GoMobile toolchain; formal `debug`/`release` select GoMobile, while internal `canary` and non-publishable `kotlinRelease` verification builds select Kotlin |
| SSH | JSch `2.28.3` + BouncyCastle `1.84` | SSH local forwarding, private keys, and host keys; fixed host fingerprints must be verified before user authentication |
| Markdown | Markwon `4.6.2` + Prism4j `2.0.0` | Renders agent/assistant responses; ordinary Markdown uses Markwon, inline code uses highlighted transparent text, and fenced code uses a native Compose block with a 1 dp border and Prism4j highlighting |
| Local settings | DataStore Preferences `1.2.1` | Server list, recent projects, and preferences |
| Device credentials | Android Keystore | OpenCode Basic passwords, SSH credentials and pins, and frp/STCP secrets; Provider auth is persisted by OpenCode Server and is not stored by Android |
| Images | Compose/Android platform decoding | Coil is not currently included; evaluate Coil 3 `3.5.0` only when project icons, remote images, or attachment thumbnails require it |
| Database | Room deferred | Add only after confirmed offline-cache/local-search needs and approval |
| DI | Manual DI | No Hilt/KSP at present, reducing toolchain complexity |

## 3. Gradle and JDK Configuration

Official Android JDK guidance recommends explicitly specifying a Java toolchain and keeping the Android Studio Gradle JDK aligned with command-line `JAVA_HOME`. Android Studio Panda 1+ recommends `GRADLE_LOCAL_JAVA_HOME` for new projects, which may map to `java.home` in `.gradle/config.properties`.

Constraints:

- Run Gradle with JDK 21.
- Use Java toolchain 21.
- Set `sourceCompatibility` and `targetCompatibility` to `JavaVersion.VERSION_21`.
- Configure Kotlin JVM target 21 through the modern `compilerOptions` API.
- Do not set `STUDIO_JDK` unless the local environment specifically requires it.

Use the root version catalog `gradle/libs.versions.toml` to centralize dependency versions.

### 3.1 Release Signing and JKS

Release builds use a custom JKS. Signing material and passwords must never enter Git. The project supports two input sources: CI prefers environment variables, while local development may copy `signing.properties.example` to root `signing.properties`. `.gitignore` covers `signing.properties`, `keystore.properties`, `keystore/`, `*.jks`, `*.keystore`, `*.p12`, and `*.pfx`.

Supported keys:

```properties
RELEASE_STORE_FILE=<absolute-path-outside-repository>
RELEASE_STORE_PASSWORD=<your-store-password>
RELEASE_KEY_ALIAS=<your-key-alias>
RELEASE_KEY_PASSWORD=<your-key-password>
```

Requirements:

- `RELEASE_STORE_FILE` should point outside the repository. If stored under the repository, it must be inside an ignored directory.
- Never place real passwords, aliases, or keystore paths in `gradle.properties`, commit messages, test snapshots, or documentation examples.
- Release packaging tasks such as `assembleRelease` and `bundleRelease` run `validateReleaseSigning` first and fail with missing-item diagnostics when configuration or the JKS is unavailable.
- If CI stores a JKS, prefer secret management for Base64 content, decode to a temporary directory during the build, and pass the path through environment variables.
- GitHub Actions keeps the four signing secrets in a protected `release` Environment and uses public variable `RELEASE_CERT_SHA256` to pin the certificate fingerprint. The signing build job has no repository write permission.
- GitHub APKs and future Google Play packages must replace one another, so both channels use the same app-signing certificate. A Play upload key cannot replace the app-signing key used for GitHub APKs.

Configuration direction:

```kotlin
val appVersionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
val appVersionName = providers.gradleProperty("VERSION_NAME").get()

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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}
```

### 3.2 Versioning and Automated Releases

Application versions are centralized in root `gradle.properties`:

```properties
VERSION_CODE=8
VERSION_NAME=0.2.3
```

`VERSION_NAME` is stable SemVer, and a stable tag must be exactly `v${VERSION_NAME}`. `VERSION_CODE` must exceed the previous stable tag. CI does not dynamically override source versions from tags or run numbers, preventing drift among local builds, Settings display, APK filenames, and release records.

Automation is split into three workflows. Ordinary CI has no release-signing secrets and runs a dedicated fixed-frp Kotlin STCP interoperability job alongside community/documentation and third-party/legal audits, Go race tests, GoMobile AAR gates, App unit tests for `debug`, `canary`, and `kotlinRelease`, bridge unit tests, and all three verification APK builds. Its manual dispatch can explicitly call the branch-local reusable K6V workflow to bootstrap that workflow's first introduction; push and pull-request runs never enable this path. The exact-SHA K6V workflow remains read-only and outside every Environment, supports direct manual dispatch once present on the default branch, and builds the real bridge for two fixed x86_64 lanes: API 26 runs the `compat` suite with only `success-v1-00`, while API 36 runs the 12-profile `full` suite spanning all wire/encryption/compression success combinations, three negative cases, and restart recovery. Preflight tests the report renderer, which fails closed unless the structured Host summaries prove the exact profiles, parameters, backend order, checks, and equivalence; reports always set `authorizesKotlinDefault=false`. That emulator evidence is a K7 input, not authorization to switch the default backend or a substitute for physical-device, 16KB, the App's real Store/snapshot/reconciliation path, Doze/network/foreground-background, performance, resource-leak, soak, or stable-release-cycle gates. The Release workflow uses `preflight`, `prepare-notes`, `frpc-interop`, `build-release`, and `publish` jobs. The external-process interop job remains read-only and outside the protected release-signing Environment; `build-release` depends on it, repeats the `debug`, `canary`, and `kotlinRelease` verification, and only then release-signs and stages the formal GoMobile-default `assembleRelease` outputs. `prepare-notes` renders an auditable notes artifact without signing secrets, the JKS-holding job has no repository write permission, and the `contents: write` publishing job cannot read signing secrets. Releases contain only the formal `arm64-v8a`, `armeabi-v7a`, and `x86_64` Release APKs plus `SHA256SUMS`; no Canary or Kotlin Release-Like APK, AAB, or universal APK is staged or published. See `doc/release/github-actions.md` for operating steps.

Target-ABI physical-device evidence and 16KB runtime evidence are independent lanes. Available physical ARM devices validate target-ABI installation, Release-mode class loading, and real App/STCP, lifecycle, and resource behavior. An official Android 16KB test image or physical device validates the runtime page size, app startup, and full interoperability, backed by the static 16KB alignment and packaging gates. These lanes do not need to run on the same physical device. Unavailable physical ARM 16KB hardware remains a disclosed residual combination risk and a desirable follow-up, but it does not block pre-K7 closure or K7 development. Exact-SHA, real App Store/snapshot/reconciliation, performance, resource-leak, soak, and stable-release-cycle gates remain required, and no individual lane authorizes Kotlin-default assembly.

### 3.3 STCP Backend Build Types

STCP backend selection is fixed at App build time through `BuildConfig`:

- `debug` and `release` set `USE_KOTLIN_FRPC_STCP_VISITOR=false` and instantiate `GoMobileFrpcStcpVisitorClient`.
- `canary` inherits `debug`, adds application ID suffix `.canary` and version-name suffix `-canary`, sets `USE_KOTLIN_FRPC_STCP_VISITOR=true`, and instantiates `KotlinFrpcStcpVisitorClient`.
- `kotlinRelease` inherits the formal `release` configuration, adds application ID suffix `.kotlinrelease` and version-name suffix `-kotlin-release`, uses the standard Android Debug test certificate, sets `USE_KOTLIN_FRPC_STCP_VISITOR=true`, and instantiates `KotlinFrpcStcpVisitorClient`. It exists only for Release-mode device validation and is never release-signed, staged, or published.
- This is not a user setting, creates no persistence/schema state, and has no runtime fallback from one backend to the other.
- Both implementations live in `:frpc-stcp-visitor`, but the library exposes exact Canary and Kotlin Release-Like variants without the GoMobile runtime dependency. Their runtime classpaths and APKs must exclude the bridge and `libgojni.so`; dedicated packaging gates enforce this separately from the `AppContainer` factory-selection tests.

## 4. Project Structure

Business code remains primarily in the single `:app` module. OpenCode API, state, and page boundaries are still being calibrated, so package structure enforces responsibility boundaries and leaves a future split path without incurring premature cross-module costs.

Exception: `frpc STCP visitor` uses the separate Android library module `:frpc-stcp-visitor` as the stable client/GoMobile AAR bridge boundary. It contains the shared Kotlin interface, the GoMobile adapter, and the pure Kotlin implementation, carries no feature/data/domain business code, and does not represent an early business multi-module split.

Recommended directories:

```text
app/src/main/java/io/github/ycfeng/ocdeck/
  OpenCodeApplication.kt
  MainActivity.kt

  app/
    AppContainer.kt
    AppViewModelProvider.kt
    OpenCodeApp.kt

  core/
    config/
    error/
    logging/
    model/
    navigation/
    network/
    security/
    store/
    util/

  data/
    agent/
    command/
    config/
    event/
    file/
    permission/
    project/
    provider/
    server/
    session/
    vcs/

  domain/
    model/
    repository/
    usecase/

  feature/
    file/
    projectpicker/
    projectshell/
    sessionlist/
    sessiondetail/
    composer/
    review/
    settings/
    server/
    provider/
    modelsettings/
    permission/
    question/

  ui/
    component/
    theme/
```

STCP bridge module structure:

```text
frpc-stcp-visitor/src/main/java/io/github/ycfeng/ocdeck/frpcstcpvisitor/
  FrpcStcpVisitorClient.kt
  GoMobileFrpcStcpVisitorClient.kt
  KotlinFrpcStcpVisitorClient.kt
  KotlinFrpcStcpVisitorFailure.kt
  UnavailableFrpcStcpVisitorClient.kt
  internal/
    control/
    crypto/
    protocol/
    runtime/
    transport/
    yamux/

frpc-stcp-visitor-go/
  bridge-versions.properties
  cmd/checkaar/
  cmd/preparefrp/
  cmd/preparemoduleproxy/
  cmd/normalizezip/
  cmd/writebridgeprovenance/
  downstream/frp-v0.69.1-p1/
  go.mod
  internal/anetcompat/
  internal/moduleproxy/
  internal/reprobuild/
  types.go
  visitor.go
  build-aar.ps1
  build-aar.sh
```

`frpc-stcp-visitor-go/` contains the actual frp GoMobile wrapper source. `bridge-versions.properties` pins bridge, Go, x/mobile, and Android API versions; build scripts must never use `gomobile@latest`. Base frp is pinned to `github.com/fatedier/frp@v0.69.1`. `cmd/preparefrp` verifies upstream module sum, zip SHA, and target-file SHA before applying the minimal patch under `downstream/frp-v0.69.1-p1/` without editing the Go module cache. The patch fixes the race between dynamic visitor configuration and Control installation, propagates actual listener-bind state, and exposes config revision, control epoch, and blocking `WaitVisitorReady`.

`build-aar.ps1` / `build-aar.sh` use the pinned toolchain and first run `cmd/preparemoduleproxy` to expose the wrapper, patched frp, and local compatibility code through stable, versioned module identities in a local `GOPROXY` and bind module graph. Formal `gomobile bind` therefore does not encode checkout-path replacements. The scripts use `cmd/normalizezip` to normalize AAR and sources-JAR ordering and timestamps plus embedded text metadata to LF line endings, and also publish normalized text sidecars. They require both archives to exist, output `frpc-stcp-visitor/libs/frpc-stcp-visitor.aar`, and publish the complete Maven artifact set to `frpc-stcp-visitor-go/build/repo/` under immutable coordinates `io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:0.3.11-frp0.69.1-p1`. Reject overwriting the same coordinates when bytes differ. The set includes the AAR, required sources JAR, POM, SHA-256, Java API signature, bridge provenance, frp patch provenance, and native-validation metadata.

`cmd/checkaar` reads Go BuildInfo from all four ABI libraries, verifies the fixed Go identity and stable module identities, versions, and sums, rejects local module identities and embedded repository/cache paths, and requires one canonical module-graph digest across ABIs. Schema-2 embedded bridge provenance and the external native sidecar bind the module-graph digest and local-path-free proof. `META-INF/OCDECK/` in the AAR also embeds project legal texts, each third-party license, the exact Java API, and bridge/frp provenance. The GoMobile linker uses a fixed 16 KB maximum page size and strips DWARF/static symbol tables; `cmd/checkaar` additionally verifies the four expected ABIs, ELF machine, every `PT_LOAD` alignment, and stripped state.

The canonical `.github/scripts/verify-bridge-reproducibility.sh` and `.ps1` gates require a clean checkout. On one host platform, they build the current checkout and a detached worktree at a different absolute path with separate `GOCACHE`, `GOMODCACHE`, and `GOPATH` directories, then compare the AAR, sources JAR, POM, checksum, API, bridge/frp provenance, and native sidecar byte-for-byte. The temporary checkout and caches are removed while primary outputs remain for the Gradle gate; CI and Release use the shell variant. This makes no cross-operating-system byte-identity claim. App packaging excludes `libgojni.so` from Android Gradle Plugin's later strip transform so Release APKs preserve those verified AAR bytes; APK gates still revalidate their hash, ELF metadata, alignment, and stripped state. The GoMobile `-javapkg` prefix maps to reflection entry `io.github.ycfeng.ocdeck.frpcstcpvisitor.gobridge.frpcstcpvisitor.Frpcstcpvisitor`. `internal/anetcompat` replaces `github.com/wlynxg/anet` with standard-library network-interface functions, and the main module explicitly inherits frp's yamux replacement.

Android Debug still compiles when the AAR has not been generated, but its GoMobile-backed STCP path returns a clear runtime unavailable error. The generated bridge is a runtime-only dependency of the library's Debug and Release variants. Canary and Kotlin Release-Like resolve matching bridge-free library variants, so they build without the AAR and must remain free of the bridge and `libgojni.so` even when it exists. Formal Release `preReleaseBuild` must run `checkGoMobileBridgeAar`, byte-for-byte verifying checksum, pinned API signature, internal/external bridge and frp provenance, legal texts, licenses, native metadata, and each ABI's `libgojni.so` hash. `-PrequireGoMobileBridge=true` applies the same AAR integrity gate to the full Debug/Canary/KotlinRelease CI invocation without adding the bridge to either Kotlin variant. `verifyPureKotlinPackaging` independently verifies both Kotlin runtime classpaths and every ABI APK. Passing static gates is not sufficient for formal release: GoMobile native load must still be verified on physically available target ABIs, 16 KB runtime behavior must be verified on an official Android 16KB test image or physical device, and a real STCP loop must pass. These may be separate device lanes. The non-publishable Kotlin Release-Like APK provides a Release-mode device-validation path without changing the formal backend default.

The GoMobile Kotlin adapter reports missing generated classes as `GoMobileBridgeUnavailableException` and incompatible classes, methods, or payloads as `GoMobileBridgeApiMismatchException`. Readiness and SSE classify these setup failures as permanent rather than retrying indefinitely. A Kotlin-only bridge API or failure-handling change still requires the complete bridge gate, but it may leave generated AAR bytes and `BRIDGE_VERSION` unchanged. Increment `BRIDGE_VERSION` whenever native or generated AAR bytes change.

The pure Kotlin runtime reports `KotlinFrpcStcpVisitorFailure` enum values through `KotlinFrpcStcpVisitorException`. App code maps those typed values into `OpenCodeFailure`; local-port conflict and predecessor bind retry decisions use `BindException` or the typed bind failure values and never parse exception messages. After a successful `NewVisitorConn` handshake, the Kotlin backend implements all four public `useEncryption`/`useCompression` combinations. Encryption uses the frp-compatible PBKDF2-HMAC-SHA1/AES-128-CFB stream, compression uses a bounded pure Kotlin Snappy framed codec, and the wire order is plaintext -> Snappy -> AES-CFB on write and the reverse on read. Both flags default to `false`; the current App server schema and UI do not persist or expose them, so product-created configurations continue to use those defaults.

If business modules are split later, possible directions are:

- `:core:network`
- `:core:ui`
- `:core:security`
- `:data:opencode`
- `:feature:session`
- `:feature:settings`
- `:feature:composer`

Keeping business code in one module today is not a reduction in architecture quality. It avoids cross-module change costs while APIs, state, and page boundaries are still unstable.

## 5. Manual Dependency Injection

The project currently uses ordinary Kotlin objects for dependency injection and does not include Hilt, Dagger, or KSP. `OpenCodeApplication` creates and owns the `AppContainer` entry point.

Responsibilities:

- `AppContainer` creates global dependencies: `Json`, `OkHttpClient`, `RetrofitFactory`, `SecureCredentialStore`, `ServerPreferencesStore`, `PathNormalizer`, `Redactor`, the build-selected `FrpcStcpVisitorClient`, `FrpcStcpVisitorManager`, `InMemoryOpenCodeStore`, `ProjectSnapshotCoordinator`, `OpenCodeEventClient`, and `AppConnectionCoordinator`.
- `AppContainer` creates API clients and Repositories for the current server.
- ViewModels receive Repositories, Stores, and UseCases through constructors.
- `AppViewModelProvider` centralizes ViewModel Factory boilerplate.

`AppContainer` makes exactly one STCP client selection from `BuildConfig`: GoMobile for `debug`/`release`, and Kotlin for `canary`/`kotlinRelease`. It passes that client to the single application `FrpcStcpVisitorManager`; no settings screen, DataStore field, persisted schema, or runtime error path changes the selected backend, and the manager does not fall back to the other implementation.

Dependency graph example:

```text
OpenCodeApplication
  -> AppContainer
    -> Json
    -> Redactor
    -> SecureCredentialStore
    -> ServerPreferencesStore
    -> OkHttpClient
    -> RetrofitFactory
    -> OpenCodeApi
    -> FrpcStcpVisitorClient (GoMobile or Kotlin, selected by build type)
    -> FrpcStcpVisitorManager
    -> ProjectSnapshotCoordinator
    -> OpenCodeEventClient
    -> AppConnectionCoordinator
    -> ProjectRepository
    -> SessionRepository
    -> ProviderRepository
    -> PermissionRepository
    -> InMemoryOpenCodeStore
```

Reasons for manual DI:

- The initial dependency graph is small and hand-written setup is manageable.
- Avoid Hilt/KSP/kapt generated-code complexity in the build chain.
- Validate compatibility among Kotlin `2.4.0`, KSP, and Hilt separately later.
- Constructor injection naturally supports future Hilt migration.
- Dependency origins remain explicit, simplifying startup and network-path debugging.

Constraints:

- Feature code must not create arbitrary `OkHttpClient`, `Retrofit`, or Repository instances.
- Do not scatter global singletons. Any required singleton is managed by `AppContainer`.
- ViewModels do not directly retain Android `Context` unless an `AndroidViewModel` is truly required.
- Repositories do not depend on Compose types.

## 6. Deferred Room/Hilt Strategy

### 6.1 Deferring Room

OC Deck is server driven: projects, sessions, messages, permissions, questions, and status primarily come from OpenCode Server REST APIs and SSE. The data that truly needs local persistence today is server configuration, recent projects, preferences, and secrets; a full relational database is not yet required.

Current storage:

- DataStore Preferences: server list, current server, recent projects, and UI preferences such as color scheme and app language. Each recent-project record has a numeric `sortOrder`; each server exposes at most 20 records in ascending order. Legacy records without that field keep their array order, and the next write continuously renumbers the retained server records from zero. A first add inserts at the top, while ensuring an existing record or updating its metadata preserves its position. Navigation-triggered add/upsert writes are auxiliary: an application-scope ordered `RecentProjectRecorder` performs them best-effort after navigation, so local persistence failure cannot block project entry. There is no separate last-opened-project or per-project last-session field.
- Android Keystore: OpenCode Basic passwords, SSH passwords/private keys/passphrases/host fingerprints, and frp/STCP secrets. Provider API keys and OAuth credentials are written to OpenCode Server's auth store and are not persisted by Android.
- In-memory Store: projects, sessions, messages, permissions, questions, and SSE state for the current process.

Conditions for adding Room:

- Offline session-history viewing is required.
- Local message search is required.
- Cold start must rapidly restore the last session details and messages.
- Incremental state must survive SSE outages reliably.
- Session, message, or diff volume requires paged caching.
- Multiple servers/projects require substantial local indexing.

Deferring Room avoids prematurely fixing Entity/DAO/schema design, migration policy, local/server consistency policy, and KSP integration.

### 6.2 Deferring Hilt

Hilt becomes more valuable as the dependency graph, module count, and test-substitution complexity grow. Current boundaries are still evolving, and Room is absent, so manual DI is more direct.

Conditions for adding Hilt:

- ViewModel Factory boilerplate grows materially.
- Repository, UseCase, and Store dependencies become error-prone to maintain manually.
- Business modules begin to split and require centralized wiring.
- Instrumentation tests or network/database replacement need systematic support.
- The Kotlin `2.4.0`, KSP, Hilt, and AGP `9.2.1` combination has been validated as stable.

Migration path:

1. Keep existing Repository/ViewModel constructor injection unchanged.
2. Add the Hilt plugin, KSP, and `@HiltAndroidApp`.
3. Gradually move creation logic from `AppContainer` to `@Module` + `@Provides`.
4. Replace ViewModel Factories feature by feature with `@HiltViewModel`.
5. Remove obsolete manual Factories.

## 7. Network-Layer Design

The network layer centralizes OpenCode REST APIs, authentication, error parsing, timeouts, SSE, and log redaction.

### 7.1 Basic Structure

Core classes:

- `OpenCodeApi`: Retrofit interface for REST APIs.
- `RetrofitInboundResponsePolicyInterceptor`: requires an explicit response mode on every `OpenCodeApi` method, tags bounded requests for the encoded-body network interceptor, bounds decoded successful entities, and discards bodies that must not reach Retrofit converters.
- `EncodedResponseLimitInterceptor`: a network interceptor that limits tagged response-body octets before OkHttp performs `Content-Encoding` decoding.
- `OpenCodeApiFactory`: creates owned API client bundles from `ServerConfig`; each bundle contains the shared REST/session-message OkHttp client plus a separately bounded ten-minute Provider OAuth callback client and supports explicit idempotent cleanup.
- `SessionMessagesTransport`: requests `limit=200` session-message pages directly, carries `before`/`X-Next-Cursor`, does not read non-2xx bodies, and combines a per-page 64 MiB encoded response-body policy with bounded 64 MiB streaming decoded JSON on the OkHttp callback thread for 2xx.
- `OpenCodeEventClient`: manages global and project event streams with OkHttp `Call` and a custom streaming SSE reader.
- `OpenCodeErrorParser`: central HTTP, network, and server-error parsing.
- `OpenCodeFailureClassifier`: converts transport, protocol, size, and operation failures into typed semantic reasons without deriving behavior from exception messages; UI maps those reasons through localized `UiText.Resource` values.
- `RedactingInterceptor`: redacts request/response logs.
- `AuthInterceptor`: attaches server credentials without exposing raw values to logs.
- `SshTunnelManager`: creates per-server SSH local forwarding and rewrites the remote OpenCode URL to `127.0.0.1:<localPort>` for shared REST/SSE use.
- `FrpcStcpVisitorManager`: starts a per-server frpc STCP visitor and rewrites the remote URL to `127.0.0.1:<bindPort>` for shared REST/SSE use. It consumes the common `FrpcStcpVisitorClient` interface; `AppContainer` supplies either the GoMobile adapter backed by the generated AAR for Debug/Release or the pure Kotlin client selected by Canary/KotlinRelease.
- Optional `DirectoryQueryInterceptor`: centrally verifies directory normalization.

Android connection addresses are environment-specific and no emulator, physical-device, or `adb reverse` mapping is a universal default. Direct, SSH local forwarding, and frpc STCP visitor are mutually exclusive. SSH and STCP both expose `127.0.0.1:<localPort/bindPort>` on the device for REST and SSE.

STCP uses two readiness layers:

1. Transport readiness: `StartSession -> EnsureVisitor -> WaitVisitorReady`. The transport is ready only when the current frps Control is logged in and the visitor for the specified config revision actually holds the local listener in the current control epoch. Configuration commit or temporary port bindability is not a readiness signal.
2. Application readiness: issue `GET /global/health` through that listener. Success proves the current path across local listener, STCP forwarding, server name/secret, target OpenCode, TLS/HTTP, and authentication.

An active listener retains exclusive ownership of its bind port. After the old generation has completely stopped, the Kotlin loopback listener enables address reuse, but not `SO_REUSEPORT`, so a new generation can quickly rebind the same port while completed relay connections remain in `TIME_WAIT`. During `stopVisitor`, the Kotlin runtime waits for the selected visitor's local listener and relay endpoints to become unavailable. Full relay-pump completion, delayed best-effort Yamux reset, and stream-permit release continue under a session-owned watcher. `stopSession` joins that watcher and observes late cancellation or JVM `Error` before terminal close. This does not permit overlapping backends or generations, make `stopVisitor` a full wire-reset barrier, or introduce a runtime fallback mechanism.

`FrpcStcpVisitorManager` tracks state by server config epoch and monotonic tunnel generation. The first REST request, health check, global SSE, and project SSE in one generation share a single-flight. The global state lock protects only state transitions; backend I/O, HTTP, retry delays, and stop operations run outside the lock. Cache success by generation only. Do not cache failure, and stop the exact session retained by that generation rather than closing a newer tunnel by `serverId`. A change in token/secret values from configuration or Keystore invalidates the old lease and requires a new generation even when the credential key is unchanged. These config leases, generations, readiness checks, health probes, cleanup rules, and REST/SSE barriers are shared unchanged by the GoMobile and Kotlin clients.

For an already Ready generation, `getConnection()` reads native runtime state. Reuse without another health call when the control epoch is unchanged and the listener remains ready. If frps reconnects, the listener rebinds, or control epoch changes, share one `WaitVisitorReady + /global/health` recovery inside the same generation. Before returning, read native state again and revalidate the exact generation/control epoch against manager state. This special barrier applies only to STCP; Direct and SSH add no equivalent check. Late results from an old epoch cannot overwrite a new generation. `ServerRepository.getConnection()` is the shared barrier for REST and SSE, so neither project snapshots nor EventSource may start before readiness completes.

`ServerRepository` owns one HTTP client bundle per server and reuses it across repeated and concurrent `getConnection()` calls after transport readiness is revalidated. The cache key includes the normalized server/auth snapshot, effective URL, repository config epoch, and STCP generation/control epoch. A configuration commit, uncertain mutation outcome, removed server, or newer STCP transport identity replaces and explicitly closes the old bundle. Bundles are never shared across server IDs or transport identities, even when loopback URLs match. Each call still returns a fresh lightweight `ServerConnection` view so one-time readiness health evidence is not cached and consumers cannot close shared clients.

Application readiness uses a separate short-timeout profile and an overall time budget. Retry only connection refused, reset, EOF, timeout, HTTP 408/425/429/5xx, and `healthy == false`. Fail fast on 401/403/404, TLS/hostname, serialization, and configuration errors. The first explicit health check reuses readiness health evidence to avoid consecutive `/global/health` calls; a manual health check on an already Ready generation still performs one fresh health request.

Retrofit JSON configuration:

```kotlin
Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}
```

Every `OpenCodeApi` method declares `@RetrofitInboundResponse` with either `BOUNDED` or `EMPTY_SUCCESS`. `BOUNDED` attaches a 16 MiB encoded response-body policy that is enforced by a network interceptor before OkHttp content decoding, then lazily enforces a separate 16 MiB decoded-entity limit. `Content-Length` is only an early rejection; unknown or understated lengths fail at `max + 1`. `EMPTY_SUCCESS` closes successful `Response<Unit>` bodies without reading them. Every non-2xx body is also closed and replaced with an empty entity while preserving the status code, so Retrofit cannot cache or convert a sensitive error body. A missing policy fails closed before the request proceeds. Calls without a Retrofit `Invocation`, including the direct session-message transport, pass outside the Retrofit interceptor and attach their own encoded-body policy.

### 7.2 Key API Groups

Primary path APIs:

- Health: `GET /global/health`
- Global events: `GET /global/event`
- Project path: `GET /path`
- Current project information and name edit: `GET /project/current`, `PATCH /project/{projectID}`; current implementation sends only `name`
- Session list: `GET /session?directory=...&workspace=...&roots=true&limit=...`, refetching an ordered prefix for the Store-backed session window.
- Session metadata: `GET /session/{sessionID}`, used to supplement a route outside the current window and its bounded parent chain.
- Session creation: `POST /session`
- Session messages: `GET /session/{sessionID}/message?limit=200&before={cursor?}`, using a specialized transport on the shared REST OkHttp client. It reads `X-Next-Cursor`, bypasses Retrofit converter/error-body caching, reads no non-2xx body, limits each page's encoded response-body octets to 64 MiB, and performs a separate per-page 64 MiB decoded-entity boundary plus EOF verification through a counting `InputStream` and lenient `Json.decodeFromStream` for 2xx.
- Prompt send: `POST /session/{sessionID}/prompt_async`
- Abort: `POST /session/{sessionID}/abort`
- Project events: `GET /event?directory=...&workspace=...`
- Provider/model base data: `GET /provider?directory=...&workspace=...`
- Agent: `GET /agent`
- Commands: `GET /command`
- Permissions/questions: `GET /permission`, `POST /session/{sessionID}/permissions/{permissionID}`, `GET /question`

Integrated and extension APIs:

- Diff: `GET /vcs/diff?mode=git&directory=...`, `GET /session/{sessionID}/diff?directory=...`
- Session management: rename, share, archive, delete, fork, summarize, children, todo. Revert/unrevert are integrated through legacy `POST /session/{sessionID}/revert|unrevert` using an encoded directory header.
- Files: `/file`, `/file/content`, `/find/file`. Project tree, search, and read-only preview are integrated. `/file/content` passes the common 16 MiB encoded response-body and decoded Retrofit boundaries, then retains a reader-level decoded `ResponseBody` check before deserialization so UI limits cannot be bypassed by an arbitrarily large response.
- Provider management: instance-scoped `GET /provider` and `GET /provider/auth`, plus stateful OAuth authorize/callback, accept optional `directory/workspace`. Server-global root-control operations are `PUT/DELETE /auth/{providerID}`, `GET/PATCH /global/config`, and `POST /global/dispose`. OAuth authorize/callback preserve the same scope and original method index, are not automatically retried, and long auto callbacks use the dedicated bounded client.
- Config, session status, MCP, LSP, and plugin status are integrated; PTY/terminal and worktree remain unimplemented.

See `doc/architecture/mobile-interaction.md` for the full endpoint matrix and completion state. Any API addition or adjustment must update tolerant DTO parsing, directory/workspace parameter tests, and the interaction document.

### 7.3 SSE Synchronization

Android needs two event streams:

- Global SSE: `GET /global/event` for server-level events.
- Project SSE: `GET /event?directory=...&workspace=...` for project, session, message, permission, question, and status changes.

SSE requirements:

- On project entry, fetch REST snapshots in parallel before establishing project SSE.
- SSE events update the in-memory Store and never mutate Compose state directly.
- Automatically reconnect with exponential backoff and expose `Connecting`, `Open`, `Retrying`, `Failed`, and `Closed`.
- On foreground resume, perform health checking and refresh the current project snapshot when necessary.
- Tolerate unknown event JSON fields so server upgrades do not crash the client.
- Production must not use an okhttp-sse parser that first allocates complete `data: String`. Parse `ResponseBody.source()` by bytes and cap each physical/logical line and cumulative event data at 32 MiB UTF-8. This accommodates Base64 echo of one maximum 20 MiB raw attachment plus framing.
- Accept only HTTP 200 and `text/event-stream` media type, allowing charset. For 204, every other non-200, and missing/incorrect Content-Type, close before `Open` and do not read the body. HTTP 204, non-transient 4xx, and Content-Type errors enter `Failed`; retry 408/409/425/429 and 5xx.
- Fail line overflow before converting a complete line to `String`, and fail event overflow before joining complete data. Close the body, cancel the Call, and report a typed failure without payload. Dispatch only on a complete blank line and discard unterminated pending line/event at EOF. An `IOException` caused by explicit cancellation does not trigger reconnect failure; clean EOF reports closed only. A JSON/field-type error in one event discards only that event.
- While disconnected, UI may show Reconnecting but must not clear existing session content.
- Open, close, and retry use generation/lease validation. Late tasks after close cannot republish `Open` or recreate a closed connection.
- Snapshot-refresh failure cannot block EventSource reconnect. Each recovery runs one coalesced REST calibration.
- Redact SSE and snapshot errors before writing Store/UI.
- Recalibrate after STCP control-epoch changes; results from old generation/epoch cannot overwrite the new connection.
- After workspace support is enabled, Store, SSE, and ViewModel project keys include both directory and workspace. Create `ProjectKey` only through a shared factory, preserve normalized request values, and compare through `PathNormalizer.comparisonKey`; only Windows drive-letter paths are case-insensitive.
- Page ViewModels retain idempotent owner leases only. The project shell and directly entered session detail both hold global leases, and multiple owners share one global source. After the last owner releases or force-close occurs, enter `Closed`; connections, callbacks, retries, and snapshots all require final generation/source/transport-identity validation.
- When the project source for the same project is `Open` and its transport identity matches the global listener, it is authoritative for project events; ignore matching global events carrying directory/workspace. Global is fallback only while the project source is not open, retrying, or failed.
- After project SSE open, reconnect, and foreground resume, application-level `ProjectSnapshotCoordinator` performs single-flight calibration by project key and lifecycle token. Calibration tokens distinguish project/global authority. While the project stream is not Open, retrying, failed, or absent, global-fallback LSP/MCP capability events may also start calibration. A later project Open cancels the old global flight. Buffer authoritative events within bounds during calibration. On success apply snapshot then replay in order; on failure keep SSE open and replay. Capability changes mark dirty only, causing at most one merged follow-up after current success, failure, or buffer overflow.
- Store maintains separate project-data and message-data revisions plus per-session history cursor state. Every first-page request atomically registers a Store-shared monotonically increasing generation together with its expected message revision; only the latest registered generation may apply messages, first-page IDs, or cursor state, so a response from an older ViewModel or recovery snapshot is stale even if it arrives last. The initial message page replaces at equal revision while preserving local optimistic messages and parts omitted by REST. Once cursor state exists, later first-page refreshes reconcile the covered latest window at an unchanged revision and merge only when the revision advanced during the request. If the refreshed page overlaps the previous first-page message IDs, the already loaded oldest cursor survives the pagination-window shift. If the pages are disjoint, Store adopts the refreshed cursor and resets the consumed chain so later older-page loads bridge the missing window, refetching already loaded pages when necessary. Older pages always merge by message/part ID, but apply only while the requested `before` still equals the current oldest cursor; consumed cursor cycles fail without mutating messages or cursor state. When revision changed, merging preserves realtime fields, deltas, historical completion, and deletion tombstones created during the request. Rebuild empty message text from final effective text parts. `session.deleted` removes that session's messages, parts, history cursor, and pending first-page generation, then advances deletion revision. Direct ViewModel REST project snapshots still use revision CAS and a latest-request gate; connection/loading/notification changes do not advance project-data revision.
- Message lists preserve the server's ascending `(createdAt, id)` order across REST pages, SSE upserts, optimistic confirmation, and message moves. When a first-page response applies at the captured message revision, Store reconciles the covered latest window: retain messages strictly older than the response boundary and local optimistic messages, replace returned messages and parts, and remove covered messages or parts absent from the response. If the revision advanced during the request, use merge semantics instead so concurrent SSE, optimistic data, and tombstones win.
- After REST/health I/O succeeds or fails without cancellation, `OpenCodeRepository` snapshots and `DefaultForegroundHealthChecker` reacquire the connection and validate transport identity. If an old connection fails while identity has advanced, retry against the new connection within bounds. If identity is unchanged, propagate the original error. Continuous identity churn returns a typed failure containing no sensitive information.
- On foreground resume, `AppConnectionCoordinator` merges health/readiness checks by server. If transport identity changes, rebuild every global/project stream still desired for that server. If unchanged, reuse streams and calibrate project snapshots. An open attempt explicitly older than current identity must atomically detach and enter standard retry; close or a new generation invalidates that retry.
- Global SSE state is isolated by `serverId`. Event buffering has both count and estimated-byte limits; on overflow, cancel the snapshot and replay immediately to avoid unbounded memory.

## 8. Path Normalization

Path normalization is a foundational capability. Web has represented the same project as `E:\\...` and `E:/...`; Android must resolve this in the data model and Repository layer.

Rules:

- Replace backslashes with `/`.
- Remove a trailing `/`, preserving root `/`.
- Compare Windows drive-letter paths case-insensitively.
- Deduplicate recent projects by normalized worktree path.
- Use normalized values for network `directory` parameters.
- UI may show a friendlier display path, but internal keys use normalized paths.

Project-relative file paths use a separate `ProjectFilePathNormalizer` rather than project-root rules. Reject NUL, absolute paths, and any `..`. Treat backslashes as separators in Windows projects and preserve legal backslashes in POSIX filenames. Before `/file` responses enter the domain layer, verify that each node is a direct child of the requested directory and deduplicate by normalized path.

Recommended model:

```kotlin
data class ProjectRef(
    val normalizedDirectory: String,
    val projectId: String?,
    val displayName: String,
    val vcs: String?,
    val icon: ProjectIcon?
)
```

## 9. State Management

The project currently uses Repository + UseCase + in-memory Store + ViewModel `StateFlow`.

State flow:

```text
REST snapshot + SSE events
  -> Repository
  -> InMemoryOpenCodeStore
  -> ViewModel StateFlow
  -> Compose UI
```

Suggested Store responsibilities:

- `ServerStore`: current server, health, and connection state.
- `ProjectStore`: project list, current project, and path mapping.
- `SessionStore`: session list, current session, messages, session state, and a per-ProjectKey root-session window with visible/requested limits, raw result count, loading/retry/end state, generation, and loaded-root identity.
- `ProviderStore`: providers, models, and connected state.
- `PermissionStore`: permission and question requests.
- `NotificationStore`: session-complete/error notifications and project/session unread state, with at most 500 items and 30-day TTL.
- `ComposerStore`: current draft, agent, model, variant, local-device attachments, project-file contexts, and command mode.

Project file browsing does not write into the session/SSE Store. A dedicated `ProjectFileBrowserViewModel` owns directory cache, expanded paths, search, and current file. It uses a project-specific `ViewModelStore`: page and session changes within a project can reuse successful directory state, while a project switch clears the store. Closing the panel cancels late requests, clears search and current text/Base64, and retains the valid directory/expansion cache so the Activity does not retain large file content. Search, file-content, and directory requests each use request ID/generation checks to prevent cancelled old results from overwriting new state. Composer picker selection is not stored in this project-scoped ViewModel; the shell owns a route/session-bound request and discards it on owner mismatch or route change.

Initial project synchronization:

1. Normalize `directory`.
2. Request `/project/current`, `/path`, `/provider`, `/mcp`, `/session?roots=true&limit=<current-window>`, `/agent`, `/config`, `/session/status`, `/vcs`, `/command`, `/permission`, and `/question` in parallel.
3. Write snapshots to the in-memory Store.
4. Establish project SSE.
5. Incrementally update the Store from SSE.
6. On foreground/background transitions, perform health checking and necessary snapshot refresh.

## 10. Navigation and Page Framework

Use Single Activity and Navigation Compose. Pages should not reproduce the desktop dialog hierarchy and instead should be reorganized for mobile.

Current primary routes:

- `ProjectPickerRoute(serverId)`: project selection and server-file browsing entry.
- `ProjectShellRoute(serverId, directory)`: project session shell containing drawer, top bar, and session area.
- `SessionDetailRoute(serverId, directory, sessionId)`: session details. A new session reuses the `"new"` state and creates its backend session on first send. Only project-home input and attachment entry may append lightweight initial agent/model/variant query values; existing sessions and other New Session entry points omit them. The session ViewModel accepts them only for `"new"` and revalidates them against loaded capabilities before use.
- `ServerListRoute`: server list.
- `AddServerRoute`: add server, then return to `ServerListRoute` after a successful save.

`ServerListRoute` is also the fixed startup entry. Once server data has loaded, an empty list shows a welcome empty state explaining that the Android app is a client and does not create or run OpenCode Server; no separate onboarding route is added. A successful add returns to this list and does not automatically navigate to `ProjectPickerRoute`; opening the new server remains explicit. The app does not automatically persist a `127.0.0.1:4096` server, and the add form leaves name and service URL empty. A one-time DataStore migration removes only direct records still exactly equal to the historical auto-generated `local / Localhost / loopback:4096` configuration; preserve every edited record.

Current child routes and placeholder capabilities:

- `ReviewRoute(serverId, directory, sessionId?)`: standalone route remains a placeholder; usable diff is currently in the session Changes tab.
- `SettingsRoute(serverId)`
- `BackgroundRunSettingsRoute(serverId)`: notification permission, ignore-battery-optimization, and recent-task retention checks. Battery optimization uses the direct system confirmation rather than the generic system list.
- `ProviderSettingsRoute(serverId, directory?, workspace?)`
- `CustomProviderFormRoute(serverId, directory?, workspace?, providerId?)`
- `ModelSettingsRoute(serverId)`
- `ProjectEditRoute(serverId, directory, projectId)`: reserved for full project icon, color, and startup-command editing; the current drawer menu offers only a project-name dialog.

Page adaptation requirements:

- The project shell uses Compose `ModalNavigationDrawer` to avoid off-screen DOM pointer interception.
- The project picker and left rail share the current server's persistent recent-project order. Paths remain normalized and deduplicated by server plus comparison key. If the active project is absent, the rail merges it at the top while keeping at most 20 projects; the reorder API can atomically incorporate only that explicitly identified project. Reorder uses the records current at commit time, retains concurrently added records omitted by a stale UI snapshot, and does not recreate stale submitted records that were deleted. Project buttons use a bounded list capped at 75% of the safe drawer content height, while Open Project and Settings remain fixed and excluded from ordering. Selecting a project enters its project home and reuses an existing matching back-stack destination when possible; recent-project records do not restore a last session.
- Both surfaces support long-press vertical drag, edge auto-scroll, optimistic ordering, persistence, and failure rollback. Only the project-picker card body starts dragging, so its delete button remains independent. While the drawer rail is being reordered, disable the outer horizontal drawer gesture.
- Project/session navigation submits successful opens to the application-scope `RecentProjectRecorder`, but an existing record stays in place. Returning to project home, opening an existing session directly, and project rename only ensure the record or update metadata. DataStore failure is reported safely but never rolls back navigation or a successful project rename.
- Session destinations hold a visibility lease only while the app is foreground and the destination lifecycle is at least `STARTED`; notification viewed state must not be inferred from ViewModel lifetime.
- The shell provides project files through a right-side overlay with Browse and Pick modes: full phone width, maximum 420 dp on large screens. The scrim covers only outside the panel, underlying semantics are hidden while open, and system Back returns from preview before closing. Pick mode confirms at most 10 whole files through a route/session-bound request; cancellation changes no draft state. Do not use a desktop two-column layout that compresses session content.
- Every full-screen `NavHost` route uses slide transitions: forward is right-in/left-out and Back is left-in/right-out.
- Settings, server management, providers, and models use single-column full-screen pages.
- Slash commands and `@` mentions may use inline suggestions above the composer. Short agent/model/variant lists and context/status summaries may use constrained anchored popups. Longer searchable or complex content uses `ModalBottomSheet`.
- Popups, sheets, and inline panels must be focusable, dismissible with system Back, bounded in width/height, and not obscured by the IME or bottom composer.
- The project-home composer preview owns temporary Agent and model/Variant overrides. Its three parameter controls open in place without navigation; input and attachment entry navigate to the `"new"` session with the current lightweight selection. Model and Variant continue to persist through the server-scoped DataStore preference, while Agent remains limited to the project-shell ViewModel lifetime.
- Permission and question requests use a blocking bottom Dock or sheet and support small screens. Permission confirmation reproduces the Web bottom Dock and hides the ordinary composer while pending.
- External help links use a browser or Custom Tabs.

## 11. UI Component Plan

Core capabilities and components:

- `BottomComposer`: input, local-device attachments, project-file context chips, agent, model, variant, send/stop, and command-mode chip.
- Shared Composer parameter UI and selection rules live in `feature/composer/` so the project-home preview and session composer reuse the same parameter button, Agent/Model/Variant pickers, supported-Agent filtering, and model/Variant fallback behavior without coupling project UI to session UI.
- Command suggestions: `/` commands, project commands, skills, and built-ins with search, preferably in an inline panel above the composer.
- Mention suggestions: agent mentions such as `@explore` and `@general`, with a data source separate from file search.
- Model picker: provider grouping, search, current selection, Connect Provider, and Manage Models; the management actions close the picker and navigate to the real Provider/Model settings routes while preserving the current project directory for Provider method discovery and OAuth. Use a constrained popup or sheet for long lists.
- Agent picker: show only Composer-supported Build and Plan while retaining IDs `build` and `plan`; do not expose every agent returned by `/agent`.
- Variant picker: dynamically show Default plus current-model `variants`; hide when the model has none. Capitalize dynamic labels and call the concept Reasoning Strength/Reasoning.
- `ProjectFilePanel`: lazy project tree, search, single-page tree/content navigation, text/image/binary states, and an explicit multi-select Pick mode with checkbox semantics, independent preview, and fixed confirmation.
- `OpenCodeCodeViewer`: shared Prism4j configuration with Markdown rendering, line numbers, text selection, and vertical virtualization. File preview limits are 500,000 characters, 20,000 lines, and 20,000 characters per line.
- `SessionListDrawerContent`: bounded same-server project rail with shared persistent ordering, long-press drag and edge auto-scroll, optimistic rollback, localized TalkBack Move Project Up/Down actions, full tab/selected/name/path/notification semantics, project title/path, overflow menu, new session, and the shared Store-backed session window. Open Project and Settings stay fixed outside the reorder list. Load More raises the root target by 20, refetches with 50 entries of headroom when needed, and exposes loading, retry, and end states.
- `SessionMessageCard`: message content, quoted-comment cards, standalone project-file context rows, agent/assistant Markdown, Compose highlighted code blocks, copy, reset/revert, and error states. Extract quoted comments from top-level `metadata.opencodeComment` on synthetic user-message text parts. REST and SSE use the same typed model, with fixed synthetic-text parsing as compatibility fallback. Paired `file://` comment parts are excluded from standalone context rows and never render as local-device attachments.
- `SessionRevertDock`: above the ordinary Composer, collapsed by default, showing reverted count and first preview with incremental restore, Restore All, and new-branch submission hints. It must not cover `PermissionDock`, question interaction, or a child-session read-only Dock.
- Context usage: token, usage, and cost summary, currently suited to an anchored popup.
- `PermissionDock`: tool description and patterns, replying `once`, `always`, or `reject` to a pending permission.
- Question interaction: single-select, multi-select, custom answer, and reject; use a Dock or sheet according to content length.
- `ServerStatusBanner`: server health, SSE connection, and reconnect state.

Touch requirements:

- Every clickable icon has a real touch target of at least 48 dp; the visual icon may be smaller.
- Composer mode-chip cancel/remove actions must have a 48 dp target.
- The bottom composer is fixed to the bottom and handles IME insets correctly.
- Settings rows and server cards must not depend on horizontal space on small screens.
- Tabs, radio choices, checkboxes, and switches expose the matching selected/checked role semantics. Expandable controls expose localized expanded/collapsed state descriptions.
- Unread, error, permission, connection, and selection states pair color with text, icons, borders, or state descriptions. Reorderable server cards and projects expose localized TalkBack Move Up and Move Down custom actions in addition to drag gestures; drawer project targets retain `Role.Tab`, selected state, full name/path, and notification semantics.
- Light and dark palettes target at least 4.5:1 contrast for body/supporting text and 3:1 for required non-text state indicators and control boundaries. Real text fields use `ControlBorder`; `OpenCodeContrastTest` covers text, semantic Diff/Markdown/syntax colors, indicators, and control borders.
- Dialogs, server status, settings sheets, suggestion panels, and Composer attachments use natural measurement plus bounded scrolling so actions remain reachable on compact screens, at 200% font scale, and with the IME open. Attachment rows scroll horizontally instead of forcing fixed-width compression.

## 12. Composer Send State Machine

Ordinary prompts are the core path, and their state machine and failure-recovery semantics must remain explicit.

Rules:

- Disable send when input, attachments, and context are all empty.
- If the current session is working and input/attachments/context are empty, submit becomes Stop and calls `POST /session/{sessionID}/abort`.
- A new-session page does not create a backend session until first send calls `POST /session`.
- Validate the current model and agent before send.
- Treat the current `ProjectKey`'s `PromptCapabilities.revision` in the Store as authoritative. The UI snapshot is used only for revision-consistency validation; model, agent, variant, and command are checked against a frozen Store copy.
- At the sender's final boundary, validate attachment count, required metadata, raw size, total budget, 4 KiB data URL header, Base64 whitespace/characters/padding, and encoded length rather than trusting the picker or `sizeBytes`.
- At the same boundary, independently validate at most 10 project-file contexts, nonblank IDs, OS-aware normalized relative paths, duplicate paths, and project-root containment. Construct each `file://` URL only from the normalized root plus validated relative path with per-segment percent encoding. These live references do not use the local-attachment byte/Base64 budget.
- Atomically insert the optimistic user message only while capability revision remains unchanged.
- Use the same normalized directory and workspace across one send, lazy session creation, message move, acceptance, and failure rollback. The Store changes only the matching workspace branch.
- After `prompt_async` succeeds, wait for SSE or a message refresh to supply the assistant response.
- On failure, conditionally remove only the same still-optimistic message. If SSE already confirmed it, retain the message and return an uncertain-result error to avoid duplicate sends.
- Send, abort, and revert/unrevert share a fail-fast operation gate keyed by server/directory/workspace/session and cannot rely only on disabled buttons. After a new session materializes, add the real session ID to the original lease before publishing Store and navigation state.

Request paths:

```text
No session id
  -> POST /session
  -> POST /session/{sessionID}/prompt_async

Existing session id
  -> POST /session/{sessionID}/prompt_async
```

Special modes:

- Only a slash command matching loaded `/command` data uses `POST /session/{sessionID}/command`; unknown `/xxx` input must not route directly to the command API.
- Ordinary prompts and loaded slash commands both carry selected project-file contexts; a context-only ordinary prompt is sendable.
- Shell mode, when implemented, uses `POST /session/{sessionID}/shell`.
- Stop uses `POST /session/{sessionID}/abort`.

### 12.1 Session Revert State

Session revert uses the server-persisted `OpenCodeSessionRevert(messageId, partId)` as the sole boundary and does not create a separate local Revert Store.

Rules:

- REST Session DTOs and `session.updated` SSE both map the `revert` marker. During revert, `InMemoryOpenCodeStore` keeps full messages; `SessionDetailUiState` derives `visibleMessages` and `revertedUserMessages` projections.
- While the marker exists, the primary timeline shows only `message.id < marker.messageId`; the target message is hidden too. OpenCode time-increasing message IDs compare lexicographically, matching Web.
- Resetting a working Session requires the coordinator to wait for abort success and then call revert within the same operation lease. Pending permissions/questions, child sessions, new sessions, optimistic messages, and duplicate mutations cannot trigger reset.
- Restore target ordinary text, `data:` local attachments, and standalone project-file contexts to Composer after reset, including attachment-only and context-only messages. A `file://` part never becomes a local attachment; only references inside the current project root become `ProjectFileContext`, and paired comment backing files are excluded. If historical recoverable content is corrupt, outside the root, over its applicable limit, or only partially restorable, do not change the marker and return a clear UI failure.
- To restore one Dock item, move the marker to the next user message if one follows. Restoring the last item or all items calls unrevert.
- When sending a new prompt in reverted state, ViewModel records the new optimistic message ID and temporarily permits that ID and later messages while the old marker remains until SSE clears it. On failure, clear the local branch start and show the Dock again. On success, wait for server cleanup events to remove the old branch.
- `message.removed` and `message.part.removed` are permanent cleanup signals for committing the new branch. Revert itself must not delete messages from the Store, or immediate unrevert would be impossible.

## 13. Security, Redaction, and Logging

Apply one redaction policy from the beginning rather than adding it at release time.

Sensitive fields:

- API key
- token
- password
- Authorization header
- cookie
- provider auth
- custom-provider headers
- SSH password
- SSH private key
- SSH private-key passphrase
- SSH host fingerprint
- frps auth token
- STCP secret key
- environment values
- configuration values that may contain credentials

Policy:

- `Redactor` provides one method that replaces secrets with `<redacted>`. Prefer structured handling for known JSON, headers, query parameters, and configuration objects; use regex only as a fallback for unknown text.
- Replace bare Bearer/Basic and other authentication values in free text, plus HTTP(S) URL userinfo and fragments, with `<redacted>`.
- Enable network logs only in debug builds and always pass through `RedactingInterceptor`.
- Error UI shows a server-error summary, never raw request headers or credentials.
- Crash reports must not upload complete request/response bodies.
- Provider settings never echo keys and show only connection or masked state.
- Raw `/provider` and `/global/config` responses may contain API keys, options, environment values, and Provider/model headers. Project them immediately to safe domain summaries and never retain the raw response in Compose state, the in-memory Store, logs, or structural summaries.
- Provider API keys and OAuth credentials are written to OpenCode Server's server-global auth store; Android keeps form secrets in memory only and clears them after committed, partial, or unknown outcomes. Sending a Provider API key or new custom header over Direct non-loopback HTTP requires a one-shot confirmation over a frozen validated request. Android Keystore does not protect that network hop.
- Provider OAuth browser URLs must be HTTP(S), have a valid host, and contain no userinfo. Authorize and callback retain the same optional `directory/workspace` and original method index. Auto callbacks are cancellable and bounded to ten minutes. A loopback URL is explicitly identified as conditional because a phone browser normally cannot reach a listener running in a remote OpenCode Server process.
- Custom-provider save stages a disabled server-global config, writes an optional auth value, then enables it. Credential failure leaves the config disabled; an unconfirmable final write is reported as unknown. Because global-config PATCH is a deep merge without reliable field deletion, persisted model IDs and header names cannot be removed or renamed, and disable first updates `disabled_providers` before best-effort auth cleanup instead of claiming physical deletion.
- Documentation, test snapshots, and debugging panels contain no real secrets.
- Repository, network, and Store layers preserve typed semantic failures such as `OpenCodeFailure`, `SseFailureReason`, and `ProjectSnapshotOutcome.Failure`. UI maps them to localized resources with an operation-specific fallback; cancellation and JVM `Error` propagate rather than becoming user-facing failures.
- A fixed SSH host fingerprint is verified before user authentication through `HostKeyRepository` or equivalent. Never disable strict checking and validate afterward.
- Server base URL must be HTTP(S) with a valid host and no userinfo/query/fragment. Use the same validation for save, connection, and UI display, and do not echo invalid old values.
- Before saving a Direct server with active OpenCode Basic authentication, classify the normalized URL without DNS. A non-loopback `http://` URL requires an explicit advisory confirmation when the username is nonblank and a new or retained password is available. `localhost` and reserved subdomains, IPv4 `127.0.0.0/8`, IPv6 `::1`, and mapped loopback literals are exempt. SSH/STCP saves and later connections are not gated. Confirmation consumes a frozen validated request exactly once and continues through the existing credential transaction; cancellation performs no repository write. Keystore protects local storage, not HTTP traffic.
- URI, network response, Base64, native bridge return, and private-key inputs require streaming hard limits before complete allocation. Ordinary Retrofit/file responses have separate 16 MiB encoded response-body and decoded-entity limits; direct session messages have separate 64 MiB limits; SSE requests identity encoding and limits lines/events to 32 MiB of that response-body representation. These limits are not peak-heap guarantees: Retrofit converters may still materialize a complete String, DTO graph, or JSON tree, and concurrent project snapshots may create a high memory peak. `/file/content` retains its reader-level decoded defense. This does not mean every REST endpoint is audited; continue endpoint-by-endpoint work. SSH private-key files are capped at 256 KiB, read on an IO dispatcher, and revalidated by UTF-8 bytes before persistence and JSch.
- `ServerRepository` persists server credentials through a narrow `CredentialStore` boundary. After candidate aliases are built, check cancellation. Put configuration write, result confirmation, epoch update, and SSH/STCP runtime invalidation in a `NonCancellable` commit section. After a write exception, reread the target configuration: continue as success when persisted, roll back only when non-commit is explicit, and retain candidates with a safe typed unknown-outcome error when confirmation is impossible.
- Clean old aliases outside the configuration lock after rereading every server reference, deleting best-effort without rolling back committed configuration and remaining compatible with historical shared aliases. `SecureCredentialStore` uses SharedPreferences `commit()` on an IO dispatcher so failures are reportable, wraps only expected `Exception`, and lets cancellation and JVM `Error` propagate. SSH pins may be inherited only when host/port endpoint is unchanged. GoMobile Kotlin configuration DTO `toString()` must redact tokens and secret keys.
- Sensitive or potentially large DTOs, domain models, Store states, transport identities, prompt values, and UI state objects override `toString()` with structural summaries. They omit credentials, URLs/endpoints, aliases, paths, prompts, Base64, SSE payloads, and tool output, use exactly `<redacted>` for sensitive fields, and leave serialization, `copy`, equality, and hashing unchanged.

## 14. Tests and Quality Gates

Current test priorities:

- `PathNormalizerTest`: Windows paths, roots, trailing slash, case, and duplicate-project deduplication.
- `ProjectDrawerModelTest`, `ProjectInitialTest`, `ProjectPickerViewModelTest`, `RecentProjectRecorderTest`, `RecentProjectStoreReducerTest`, and `VerticalLazyListReorderTest`: numeric and legacy ordering, continuous renumbering and the 20-project cap, new-project top insertion without existing-project movement, atomic reorder/explicit current-project incorporation, concurrent-add retention, stale-delete non-revival, Windows path-alias deduplication, optimistic reorder persistence and rollback callbacks, short-viewport edge-scroll direction, non-blocking navigation, ordered/retried best-effort recording, bounded drawer merging, project-home navigation decisions, and Unicode project initials.
- `SessionComposerAgentResolverTest`, `SessionModelPreferenceResolverTest`, and `SessionComposerRouteSelectionTest`: Build/Plan filtering and fallback, initial model/Variant validation and switching, and new-session-only decoding of lightweight project-home Composer selections.
- `ProjectFilePathNormalizerTest` and `ProjectFileUrlBuilderTest`: platform differences, NUL, absolute paths, `..`, direct-child validation, POSIX/drive/UNC URL construction, percent encoding, round trips, and project-root containment.
- `RedactorTest`: key/token/password/header/env/config redaction.
- `RetrofitInboundResponsePolicyTest` and `EncodedResponseLimitInterceptorTest`: every `OpenCodeApi` method has an explicit mode; missing policy fails closed; encoded and decoded known/unknown/understated lengths enforce `max + 1`; a real OkHttp gzip chain applies the encoded cap before Bridge decoding; non-2xx and successful Unit bodies are closed without reading; direct calls without `Invocation` remain outside the Retrofit policy.
- `SessionMessagesTransportTest`, `SessionMessagesResponseReaderTest`, `InMemoryOpenCodeStoreRevisionTest`, `SessionMessageJumpTest`, and `FileContentResponseReaderTest`: cursor query/header handling, older-page merge and cursor preservation, reverse-completion rejection for stale first-page generations, `(createdAt, id)` tie ordering, first-page covered-window deletion reconciliation, session-deletion pending-generation cleanup, empty projected timeline loading and jump-control availability, body-free HTTP failures, streaming decode, EOF verification, cancellation/close races, per-page 64 MiB direct-message limits, and the `/file/content` second defense.
- `OpenCodeFailureTest`, `ErrorUiTextTest`, and `OpenCodeRepositoryFailureHandlingTest`: semantic classification, including typed Kotlin runtime failures and local-port rejection, without exception-message parsing; localized resource mapping with operation fallbacks; Repository propagation; and cancellation/JVM `Error` behavior.
- `ProviderSettingsParsingTest` and `ProviderCapabilityRefreshTest`: safe projection of secret-capable Provider/auth payloads, authoritative `connected` handling, preservation of auth wire indexes and conditions, safe OAuth URL parsing, and capability refresh through the current project/global SSE authority lease.
- `ProviderSettingsViewModelTest`: project-scoped dynamic auth inputs, cleartext frozen confirmation, single-flight mutation, OAuth code/callback scope, secret-free state summaries, and post-mutation reload/calibration.
- `CustomProviderValidationTest`, `OpenCodeProviderRepositoryCustomTest`, and `CustomProviderFormViewModelTest`: Provider/model/header validation, staged disabled/auth/enable ordering, safe config projection, partial and unknown outcomes, disable-before-auth-cleanup, deep-merge no-fake-delete rules, secret clearing, and immutable persisted identifiers.
- `SensitiveValueToStringTest`: structural summaries omit synthetic credentials, URLs, aliases, paths, prompts, Base64, SSE payloads, and tool output across network, domain, Store, feature, and UI objects.
- `OpenCodeContrastTest`: light/dark 4.5:1 text and 3:1 graphical contrast for theme, semantic, syntax, status, attachment, and control-border colors.
- `FrpcStcpVisitorUrlResolverTest`: STCP local-port URL rewriting preserves scheme/path.
- `FrpcStcpVisitorClientFactoryTest`: runs in all three App verification variants and verifies that Debug `BuildConfig` selects GoMobile, Canary and Kotlin Release-Like select Kotlin, and the explicit factory can construct either backend without runtime fallback.
- `FrpcStcpVisitorManagerTest`: runs under the same three App unit-test tasks and verifies the backend-neutral manager contract: generation single-flight, native listener gate, application-health retry, configuration invalidation, exact cleanup, caller cancellation isolation, no cross-server blocking, control-epoch recovery, late old-epoch protection, typed bind-conflict conversion, and predecessor retry without message parsing.
- `FrpcStcpReadinessRetryClassifierTest`, `GoMobileFrpcStcpVisitorClientTest`, `KotlinFrpcStcpVisitorClientTest`, and `FrpcStcpVisitorClientDifferentialContractTest`: transient retry classification, permanent inbound/bridge failures, typed unavailable/API-mismatch/runtime errors, safe summaries, API v1/v2 behavior, revision/epoch, `WaitVisitorReady`, listener/relay lifecycle, delayed best-effort relay reset without blocking `stopVisitor` after local shutdown, session-owned permit release, all four payload-flag combinations, bounded Snappy framing, Go-oracle cross-language vectors, normalized host-JVM adapter/runtime semantics, and cancellation/JVM `Error` propagation. The differential suite does not execute native GoMobile or a real frps.
- `SocketFrpLocalListenerFactoryTest`: an active listener remains exclusive, while a fully stopped generation can immediately rebind its port after a completed relay leaves the connection in `TIME_WAIT`.
- `FrpcStcpVisitorAndroidInteropTest`, driven by `frpcAndroidInteropTest`: every selected `compat` or `full` profile runs the real GoMobile AAR and pure Kotlin client in separate Android instrumentation processes, reusing one bind port only after complete `Closed`/port-release verification. It covers all configured wire/payload success combinations with live global/project SSE and concurrent health/two-echo/two-larger-than-window traffic sharing one checkpoint, the three typed negative cases, restart/control-epoch recovery, and strict structured backend-equivalence evidence without active-generation fallback.
- Go wrapper/downstream tests: startup config updates are not lost, ready only after real bind, bind failure, closed manager, control epoch, superseded revision, Stop waits for port release, and sensitive errors are redacted. Run concurrency-related packages under `go test -race`.
- `PromptSendStateMachineTest`: empty input, attachments, project-context-only input, and working stop/send state.
- `OpenCodePromptSenderTest`: lazy session creation, authoritative Store capability revision, final attachment/project-context validation, optimistic `file://` parts, conditional rollback, SSE confirmation first, ordinary/loaded-command context propagation, real session alias, abort, and single-flight.
- `EventReducerTest`, `SessionListWindowCoordinatorTest`, and Store/Repository session-window tests: merging SSE events, ordered prefix replacement, shared visible targets, loading/retry/end state, stale generation/transport rejection, tombstones, workspace isolation, and bounded session/parent metadata backfill.
- `NotificationAlertPolicyTest`, `NotificationChannelMigrationPolicyTest`, `OpenCodeNotificationAudioAttributesTest`, and `SessionVisibilityRegistryTest`: one sound owner per event, v2 channel migration, explicit system mute/sound precedence, notification audio usage, and foreground destination visibility.
- `BoundedSseReaderTest`, `OkHttpSseEventSourceFactoryTest`, `OpenCodeEventClientLifecycleTest`, and `ProjectSnapshotCoordinatorTest`: identity response-body parsing, explicit `Accept-Encoding`, non-identity zero-read rejection, MIME/status/body-free protocol handling, overflow/cancellation, permanent versus retryable reasons, owner/generation/transport races, authoritative-source handoff, bounded replay, and snapshot failure/recovery.
- Repository unit tests: error parsing, directory parameters, tolerant provider/model DTOs, and server-configuration serialization without plaintext SSH/STCP credentials.
- `ServerRepositoryCredentialPersistenceTest`: second candidate write failure, configuration commit failure, late exception after persistence, cancellation before/during commit, unknown outcome, late delete exception, partial rotation, explicit removal, SSH endpoint pin, TOFU, post-commit old-alias delete failure, and shared-alias deletion protection.
- `ServerBaseUrlTest`: structural URL validation plus DNS-free classification of HTTPS, remote/LAN HTTP, localhost subdomains, IPv4 `127/8`, IPv6 loopback, mapped loopback, and lookalike domains.
- `AddServerViewModelTest`: save is atomic single-flight; Direct non-loopback HTTP with active Basic credentials waits for one advisory confirmation, cancel performs no write, confirm saves one frozen request, retained edit passwords are covered, and SSH/STCP bypass the warning.
- File unit tests: platform differences and traversal rejection for project-relative paths, project URL construction/containment, required/unknown-field handling for `/file/content`, bounded reads for declared/unknown lengths, tree depth/cycles/duplicate paths, text-preview complexity limits, standalone/comment-backing history classification, and reset projection.
- Inbound-payload tests: encoded/decoded identity and gzip bodies cover known/unknown/underreported lengths and `max + 1`; direct OkHttp session-message transport reads zero bytes from 4xx/5xx bodies and covers streaming decode, blocked-read cancellation, callback races, and close paths. SSE tests cover identity negotiation, non-identity rejection, LF/CRLF/CR, CRLF across chunks, BOM, fields, all EOF states, line/event limits, huge no-newline sources, 200 MIME, 204/HTTP classification, cancellation, and exceptional callback close.
- SSE lifecycle tests: close races, generation/lease, snapshot failure not blocking reconnect, one calibration per recovery, and foreground resume.
- SSH/external-input tests: pre-authentication host-key validation, server-URL structural constraints, data URL header/payload, and streaming limits for URI/network response/Base64/native returns/private keys.

The focused `app/src/androidTest` suite covers localized independent-window roots. Ordinary CI still has no App UI emulator/instrumentation job; the separate manual K6V workflow automates only STCP backend interoperability. Until broader UI device automation exists, manually validate these interaction paths on compact phone screens in both themes, with the IME open, at 200% font scale, and with TalkBack enabled:

- Project-picker and drawer ordering: long-press vertical drag, edge auto-scroll, cross-entry synchronization, ordinary click without reordering, independent picker deletion, optimistic failure rollback, current-project merge capped at 20, fixed drawer actions, and horizontal drawer-gesture coordination.
- Open session details from the session list.
- Composer input and send-button state.
- Project-file picker tree/search, up-to-10 selection, independent preview, confirm/cancel, preview-to-tree Back, route/session switching, context chips, and context-only send/reset.
- Model/agent/variant pickers.
- Provider search and loaded/available states, dynamic API/OAuth prompts, browser/code/cancel paths, loopback and cleartext warnings, disconnect, and multi-model/header Custom Provider create/edit/disable flows.
- Permission Dock and question sheet.
- Fresh install and legacy notification-channel migration on representative API 26, 29, 30+, and 33+ devices; verify default single playback, app None, explicit system sound, explicit channel mute/block/low importance, notification permission denial, and foreground/background current-session behavior.
- Selected/checked/expanded semantics, non-color status cues, server/project Move Up/Move Down custom actions, and drawer project tab/selected/full-name-path/notification semantics.

Build gates:

- `python .github/scripts/audit-community.py` verifies governance files, issue forms, CODEOWNERS, bilingual document pairs, relative links, release-note sections and labels, obsolete paths, synthetic fixtures, and repository hygiene.
- `python .github/scripts/audit-third-party.py` verifies versions, dependencies, the union of four Android Go targets, resource hashes, frp modified/added provenance, all six test-only official frp release-asset pins, legal texts, and release-script references.
- `./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug`
- `./gradlew --no-daemon :frpc-stcp-visitor:frpcInteropTest` explicitly runs fixed official frp v0.69.1 against wire v1/v2, all four payload modes, concurrent larger-than-window traffic, REST/SSE, typed negative cases, and restart/epoch recovery; ordinary unit tests never launch this harness.
- Android A/B validation first builds the GoMobile AAR in a separate invocation, then runs `./gradlew --no-daemon :frpc-stcp-visitor:frpcAndroidInteropTest -PrequireGoMobileBridge=true -Pocdeck.frp.androidInterop.deviceSerial=<serial> -Pocdeck.frp.androidInterop.suite=full`. The explicit suite is mandatory and must be `compat` or `full`; when an acceptance summary is needed, also pass `-Pocdeck.frp.androidInterop.summaryFile=<new-path>` with a destination that does not already exist. The harness reuses the pinned official-frp provisioner and writes synthetic credentials through bounded adb stdin into test-private files rather than Gradle or instrumentation arguments.
- The full bridge/CI Android gate is `./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleKotlinRelease :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true`.
- `go run ./cmd/preparefrp`, `go test -race -modfile=build/frp-patched.mod ./...`, and `go test -race ./client/...` inside the patched frp module.
- `.github/scripts/verify-bridge-reproducibility.sh`, or `.ps1` on Windows, must use the Go/x-mobile/Android API/NDK versions pinned in `bridge-versions.properties`; CI and Release use the shell variant.
- A Kotlin-only bridge API or failure-handling change still runs this complete gate even when generated AAR bytes and `BRIDGE_VERSION` remain unchanged. Any native or generated AAR byte change requires a `BRIDGE_VERSION` increment.
- On one host platform, the bridge gate must use a clean current checkout and a detached checkout at a different absolute path, isolate `GOCACHE`, `GOMODCACHE`, and `GOPATH`, and compare the AAR, required sources JAR, POM, checksum, API, bridge/frp provenance, and native sidecar byte-for-byte. Each ABI's `libgojni.so` in Release APKs must match the corresponding AAR entry hash, and ELF machine, every `PT_LOAD` 16 KB alignment, and stripped state must be rechecked.
- APKs must contain current `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.txt`, `TRADEMARKS.md`, the merged full-license text, and every individual license byte-for-byte. The AAR must embed matching legal/API/provenance metadata under `META-INF/OCDECK/`.
- Add ktlint/detekt to CI only after they are introduced. Add Android lint as an independent gate after existing errors are fixed.
- `.github/workflows/ci.yml` runs the fixed-frp host interop job plus Debug, Canary, and Kotlin Release-Like unit/APK gates without release-signing secrets on pushes/PRs to `main`; only a manual dispatch with `run_frpc_android_interop=true` invokes the branch-local reusable K6V workflow for first-introduction bootstrap. `.github/workflows/frpc-kotlin-android-interop.yml` is a read-only exact-SHA Android STCP gate with direct manual and reusable-call entry points, fixed API 26 x86_64 `compat` and API 36 x86_64 `full` lanes, renderer unit tests in preflight, and fail-closed bounded report artifacts that never authorize K7. `.github/workflows/release.yml` runs host interop in a separate read-only job outside the release Environment, reruns all three verification variants, and applies release signing, staging, and publication eligibility only to the formal GoMobile-default `assembleRelease` artifacts for stable tags.
- Before publishing, verify tag/source version, that the tagged commit is in `origin/main` history, monotonically increasing `VERSION_CODE`, three APK outputs, pinned signing certificate fingerprint, and `SHA256SUMS`. Release notes always state independence, user-provided OpenCode Server, and pre-1.0 risk. Current automation builds no AAB. Target-ABI physical native load, 16 KB runtime behavior on an official Android test image or physical device, and a real STCP loop still require manual verification; these may be separate lanes.

## 15. Current Implementation Status

### 15.1 Available or Substantially Available

- Android project, manual DI, DataStore/Keystore/in-memory Store, server list with client-explanation empty state, add/health-check, and mutually exclusive Direct/SSH/STCP modes; no localhost server is auto-created.
- Build-time STCP assembly selects GoMobile for formal `debug`/`release` and pure Kotlin for internal `canary` plus the non-publishable `kotlinRelease` Release-Like device-test build. All variants use the same manager lease/readiness contract, with no user setting, persisted selector, or runtime fallback. The Kotlin library implementation supports the public encryption/compression flags, while current App-created STCP configurations keep both defaulted to `false`; formal `assembleRelease` remains the only release-signable and publishable output.
- Path normalization, per-server `sortOrder` recent-project persistence with new-project top insertion and shared picker/drawer reordering, project picker/shell, shared Store-backed session window with network Load More, session drawer/details, lazy session creation, ordinary prompt, global/project SSE, and provider/model/agent base data. Session messages use cursor pagination and a specialized transport with separate per-page 64 MiB encoded and decoded limits.
- Every ordinary `OpenCodeApi` Retrofit method has explicit separate 16 MiB encoded and decoded boundaries or an empty-success policy; non-2xx and Unit bodies are discarded without reading, and `/file/content` retains a reader-level decoded boundary.
- Slash commands, `@` mentions, agent/model/variant pickers, phone-local attachments, project file tree/search/read-only preview and whole-file Composer contexts, permission/question, session Changes/diff, and context usage.
- Typed Repository/SSE/snapshot failures map to localized UI resources without parsing exception messages, while sensitive and large value objects use tested structural summaries.
- Mobile controls include 48 dp targets, selection roles, expanded/collapsed descriptions, non-color state cues, TalkBack server/project reordering, contrast-tested light/dark palettes, and screen-constrained scrolling for small screens, 200% text, and IME overlap.
- Session rename/archive/delete/revert/unrevert, project-name edit, language/color/notification/sound/background settings, three default-silent v2 notification channels with single-owner sound arbitration, foreground route visibility, local model-hidden preference, and MCP/LSP/plugin status.
- Provider management with safe catalog/config projection, search, dynamic API/OAuth authentication, disconnect, bounded/cancellable OAuth callback, active-project capability refresh, and staged multi-model/header Custom Provider persistence.
- Authoritative Store prompt-capability validation, sender final attachment/project-context checks, conditional optimistic rollback, shared send/abort/revert operation gate, attachment-only/context-only reset, and historical recoverable-content integrity errors.
- Server URL no-userinfo/query/fragment constraints, safe hiding of old invalid values, Direct cleartext HTTP credential confirmation, free-text URL/auth-scheme redaction, and bounded 256 KiB SSH private-key reading with double validation.

### 15.2 Partially Complete or Requiring Hardening

- Identity-only custom streaming SSE reader, 32 MiB line/event limit, owner leases, terminal close, project/global deduplication and fallback, generation/source/monotonic transport identity, revision-protected snapshots, concurrent message merge, dirty follow-up calibration, and application-level foreground recovery are implemented. Long real-server outages, OS foreground/background behavior, notification-channel upgrades, and STCP control-epoch switching still require device validation.
- Pure Kotlin payload encryption/compression, cross-language fixtures, the dedicated fixed official-frp host gate, and the expanded real Android A/B automation are implemented. The Android harness fixes API 26 to `compat` with only `success-v1-00` and API 36 to `full` with the exact 12-profile wire/payload/negative/restart matrix. For every profile it runs GoMobile, verifies complete stop/`Closed`/port release, then runs Kotlin in a separate instrumentation process on the same port; success profiles keep global/project SSE online during health, two echo requests, and two larger-than-Yamux-window downloads with a shared checkpoint, while restart verifies existing-SSE interruption, control unavailability, `frps` and provider recovery barriers, a strictly advanced control epoch, and recovered REST/SSE/large traffic. Strict Host summaries prove profile parameters, backend order, semantic outcomes, checks, and equivalence rather than treating Gradle success as evidence. Exact candidate `01ba5437276fb5074da5c654219668ac4cb69d48` passed the complete ordinary verification job, both Android lanes, and the fail-closed bilingual report in [run 29763640048](https://github.com/ycfeng/ocdeck-android/actions/runs/29763640048). A non-publishable Kotlin Release-Like APK is available for Release-mode device validation without changing formal release selection. Pre-K7 uses a split evidence matrix: available physical ARM devices cover target-ABI installation, class loading, real App/STCP, lifecycle, and resource behavior; an official Android 16KB test image or physical device covers runtime page size, startup, and full interoperability, backed by static 16KB alignment and packaging gates. These lanes need not run on the same physical device. Missing a physical ARM 16KB combination remains a disclosed residual risk and desirable follow-up, but does not block pre-K7 closure or K7 development. The App's real Store/snapshot/reconciliation path, Doze/network and foreground/background transitions, recovery, performance, resource-leak evidence, long-duration soak, and formal stable release cycles remain separate gates; neither the emulator report nor this verification variant alone authorizes Kotlin-default assembly.
- Standalone Review route remains a placeholder; usable diff is in the session Changes tab.
- Provider management still requires compatibility testing against supported real Server/provider versions, especially remote loopback OAuth topology, long-callback cancellation on devices, and partial/unknown custom-config outcomes. Global-config deep-merge semantics do not provide physical field/config deletion.
- Model enabled/hidden is a local per-server filter and does not modify OpenCode Server config.
- Settings includes General, Background, Servers, Providers, and Models, but complete shortcuts and other Web settings remain unfinished.

### 15.3 Not Implemented

- Shell mode, session share, workspace/worktree, and PTY/terminal.
- Room, Hilt, and business multi-module migration. Evaluate only after explicit needs for offline cache, local search, fast restoration, or dependency-graph complexity and after approval.

## 16. Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| OpenCode API field changes | DTO parsing failure or lost state | Ignore unknown fields with kotlinx.serialization and perform compatible parsing in Repository |
| SSE disconnect or event reordering | Inconsistent UI state | Refresh REST snapshots after foreground resume/reconnect; keep Store reducers idempotent |
| STCP configuration committed before listener ready | False first-health or project-snapshot failure | Patched frp exposes actual revision/epoch listener state; Repository performs single-flight `/global/health` at the shared connection boundary |
| frps reconnect or late old async work | Reuses invalid listener or closes a new tunnel | Per-generation identity/CAS cleanup; shared recovery after control-epoch change; old epoch results cannot publish |
| Non-reproducible GoMobile artifact or API drift | Release cannot be audited/rolled back or reflection fails at runtime | Pin Go/x-mobile/frp/patch, use a versioned local module graph and immutable Maven coordinates, validate BuildInfo/module digest with no local paths, and compare isolated-cache builds across distinct checkout paths |
| Inconsistent path formats | Duplicate projects or failed requests | Enforce `PathNormalizer` and normalize every Repository input |
| Secret leakage | Security incident | Redactor, logging interceptor, and no plaintext provider UI |
| Provider management API/version or OAuth-topology mismatch | Authentication fails or an uncertain server-global configuration remains | Tolerant safe projection, original method indexes and scope, bounded cancellable callbacks, loopback warnings, staged disabled writes, typed partial/unknown outcomes, and real-version/device validation |
| Encoded/decoded response limits mistaken for heap guarantees | High memory peaks during converter allocation or concurrent snapshots | Keep separate 16/64 MiB encoded and decoded boundaries plus 32 MiB identity-SSE boundaries, retain `/file/content` defense in depth, audit endpoints individually, and validate large inputs on devices |
| Premature Room/Hilt | More build complexity and migration cost | Manual DI + DataStore + memory Store; add only on trigger conditions and approval |
| Desktop interaction or inaccessible state styling copied to phone | Unusable small-screen or assistive-technology UI | Full-screen pages, constrained scrolling, 48 dp targets, semantic roles/state descriptions, non-color cues, contrast gates, TalkBack actions, and IME inset handling |
| Local JDK/SDK mismatch | Build failure | Require JDK 21, compileSdk 36, Build Tools 36.0.0, and aligned Studio/CLI JDKs |

## 17. Next-Phase Implementation Checklist

Address current gaps in this order:

1. Using the recorded exact-SHA API 26 `compat`/API 36 `full` K6V pass as the emulator baseline, complete the split device matrix: validate target-ABI installation, Release-mode class loading, and real App/STCP behavior on available physical ARM devices, and validate a `16384` runtime page size, startup, and full interoperability on an official Android 16KB test image or physical device. These lanes may use different devices; lack of a physical ARM 16KB device does not block the remaining App Store/snapshot/reconciliation, lifecycle, performance, resource-leak, and long-duration soak work. Continue formal stable-release-cycle validation separately; this verification APK is not eligible for publication and does not by itself authorize Kotlin-default assembly.
2. Validate long SSE outages, OS foreground/background behavior, global/project duplicate events, and STCP control-epoch switching against real services and devices, then harden according to results.
3. Validate Provider management against supported real Server/provider versions and devices, especially remote loopback OAuth, long-callback cancellation, and recovery from partial/unknown staged custom-config outcomes.
4. Validate the project-file picker and context-only send/reset flow on real compact devices with route changes, IME, 200% font scale, TalkBack, and both themes; add instrumentation coverage when the device test gate is introduced.
5. Continue endpoint-by-endpoint audits of potentially large REST responses beyond session messages, plus native returns, image decoding, and large-input failures on real devices. Bound every external input before parsing.
6. Decide the product value of a standalone Review route. Until then, the session Changes tab is the only completed review feature.
7. Re-evaluate Shell, share, workspace/worktree, and PTY/terminal. Start Room, Hilt, or business-module splitting only when trigger conditions are met and approved.

Historical P0/P1/P2 labels remain only as context for feature evolution and are no longer authoritative for current completion or project structure.
