# Testing

[简体中文](testing.zh-CN.md)

This English document is canonical. The Chinese document is a convenience translation.

## Test Layers

OC Deck uses several independent gates. Passing one layer does not imply that the others passed.

| Layer | Current coverage |
| --- | --- |
| Kotlin/JVM unit tests | Paths and project-file URLs, recent-project ordering/recording/reorder rollback models, session windows, notification/channel policy, redaction, encoded/decoded Retrofit/direct and identity-SSE inbound boundaries, typed failures and localized UI mapping, STCP backend factory selection, typed bind retry, safe value summaries, DTO tolerance, Store revisions, prompt/project-context state and recovery, Provider auth/OAuth and staged custom-config transactions, server credential transactions, SSH/STCP coordination and Kotlin/GoMobile bridge contracts, contrast, and feature helpers. |
| Go race tests | The GoMobile wrapper, canonical STCP fixture oracle/check, and generated patched frp client packages. |
| Third-party and legal audit | Pinned versions, dependency inventory, hashes, provenance, licenses, and release-script references. |
| Bridge validation | AAR and required sources JAR, checksum, Java API signature, bridge/frp provenance, four-ABI Go BuildInfo/module-graph proof, ELF machine, 16KB `PT_LOAD` alignment, stripped state, and same-platform cross-checkout reproducibility of the complete artifact/sidecar set. |
| Android build | App unit tests and APK builds for Debug, the internal Kotlin Canary, and the Kotlin Release-Like device-test variant, plus `:frpc-stcp-visitor` unit tests. |
| Android instrumentation tests | Compose window localization across Popup and modal bottom-sheet roots, plus a test-only `:frpc-stcp-visitor` harness that runs the real GoMobile and Kotlin backends sequentially against one fixed-frp topology. |
| Manual UI/accessibility validation | Compact screens, 200% font scale, IME overlap, project ordering in the picker and drawer, project-file selection, Provider auth/OAuth/Custom Provider flows, TalkBack semantics/actions, both themes, and real model-settings navigation. |
| Release artifact validation | APK metadata, one signer, expected certificate fingerprint, ABI isolation, `zipalign -P 16`, AAR native-byte binding, embedded legal files, filenames, and checksums. |
| Physical-device validation | Maintainer-recorded `0.1.0` release-gate validation passed physical-device native loading/startup, 16KB page-size native operation, and a real STCP loop covering `/global/health`, representative REST, global/project SSE, and controlled reconnect. Exact environment details are not published; future candidates must repeat these gates. |

The explicitly requested K6V workflow provides fixed API 26 `compat` and API 36 `full` x86_64 emulator lanes for STCP backend interoperability, but ordinary CI still has no App UI emulator/instrumentation job. K6V can be dispatched directly after its workflow exists on the default branch; while the workflow is first being introduced, a manual CI dispatch can opt into the same branch-local reusable workflow. Push and pull-request CI never enables that bootstrap automatically. The `app/src/androidTest` suite covers localized independent-window roots. Recent-project drag ordering remains outside instrumentation coverage; its automated coverage is primarily the JVM reducer, recorder, ViewModel, and drawer-model tests listed below. Project selection, session navigation, broader Composer interactions and pickers, permission/question UI, large-text behavior, and TalkBack still need systematic device automation.

## Focused Test Inventory

- `RetrofitInboundResponsePolicyTest` and `EncodedResponseLimitInterceptorTest` verify that every `OpenCodeApi` method declares `BOUNDED` or `EMPTY_SUCCESS`; missing policy fails before network proceed; encoded and decoded declared/unknown/understated lengths enforce their 16 MiB boundaries at `max + 1`; a real OkHttp gzip chain applies the encoded cap before Bridge decoding; non-2xx and successful Unit bodies close without reading; `/file/content` remains lazy; and requests without Retrofit `Invocation` pass outside the Retrofit interceptor.
- `SessionMessagesTransportTest`, `SessionMessagesResponseReaderTest`, `InMemoryOpenCodeStoreRevisionTest`, `SessionMessageJumpTest`, and `FileContentResponseReaderTest` cover cursor query/header handling, older-page merge, overlapping-refresh cursor preservation, disjoint-refresh gap bridging, cursor compare-and-set and cycle rejection, latest-generation-wins behavior when first-page requests complete in reverse order, `(createdAt, id)` tie ordering, first-page covered-window deletion reconciliation, session-deletion cursor and pending-generation cleanup, empty projected timeline loading and jump-control availability, body-free non-2xx failures, direct OkHttp callback-thread decode, encoded and decoded exact/unknown/underreported lengths, EOF verification, cancellation and callback races, the separate per-page 64 MiB session-message limits, and `/file/content` reader-level decoded defense in depth.
- `OpenCodeFailureTest`, `ErrorUiTextTest`, and `OpenCodeRepositoryFailureHandlingTest` cover semantic classification without reading `Throwable.message`, including mapping `KotlinFrpcStcpVisitorFailure` enum values to App failures and local-port rejection; localized resource mapping with operation-specific fallback; Repository propagation; response-too-large behavior; and propagation of cancellation and JVM `Error`.
- `BoundedSseReaderTest`, `OkHttpSseEventSourceFactoryTest`, `OpenCodeEventClientLifecycleTest`, and `ProjectSnapshotCoordinatorTest` cover explicit identity encoding, non-identity zero-read rejection, all line endings and EOF states, 32 MiB line/event limits, body-free status/MIME failures, cancellation, retry classification, terminal close, owner/generation/source/transport races, project/global authority handoff, bounded replay, and snapshot failure/recovery.
- `FrpcStcpReadinessRetryClassifierTest` and `GoMobileFrpcStcpVisitorClientTest` cover transient versus permanent readiness failures, inbound-policy failures, typed unavailable/API-mismatch bridge errors, safe bridge summaries, API v2 JSON, revision/control epoch, `WaitVisitorReady`, and reflection cancellation/JVM `Error` propagation.
- `FrpcStcpVisitorClientFactoryTest` runs in all three App verification variants and verifies that Debug `BuildConfig` selects `GoMobileFrpcStcpVisitorClient`, Canary and Kotlin Release-Like select `KotlinFrpcStcpVisitorClient`, and the explicit factory can construct either backend without a runtime fallback.
- `FrpcStcpVisitorManagerTest` covers shared generation/lease/readiness behavior plus conversion of `BindException` and typed Kotlin bind failures to `LocalPortInUse`, bounded predecessor bind retry, and rejection of non-bind typed failures even when their message contains bind-like text.
- `OpenCodeConnectionClientTest` and `ServerRepositoryConnectionCacheTest` cover idempotent owned-client cleanup; repeated and concurrent per-server REST/OAuth/session-message client reuse; server isolation; committed versus rejected configuration changes; and STCP control-epoch replacement without caching one-time readiness health evidence.
- `KotlinFrpcStcpVisitorClientTest` and the internal control, crypto, protocol, transport, yamux, and compression tests cover typed runtime failures, revision/control-epoch readiness, listener ownership and rebinding, v1/v2 visitor handshakes, all four `useEncryption`/`useCompression` combinations, coalesced handshake/payload reads, bounded relay lifecycle, delayed best-effort reset without blocking `stopVisitor` after local shutdown, session-owned permit release, Snappy framing and malformed-input limits, cleanup, cancellation, and secret-free diagnostics.
- `SocketFrpLocalListenerFactoryTest` opens and actively closes a real loopback relay, then verifies that a fully stopped Kotlin generation can immediately rebind the same port while an active listener still retains exclusive ownership.
- `FrpcStcpVisitorClientDifferentialContractTest` runs the same six public operations against a scripted GoMobile Kotlin adapter seam and an injectable pure Kotlin runtime fixture, comparing normalized phase/revision/epoch/listener/bind semantics, idempotency, replacement, typed bind conflict, cancellation identity, and safe diagnostics. It is a host-JVM adapter/runtime contract test: it neither loads the native AAR nor replaces real-frps/device interoperability testing.
- `FrpcStcpVisitorAndroidInteropTest`, launched through `frpcAndroidInteropTest`, runs every selected `compat` or `full` profile through the real generated GoMobile AAR and pure Kotlin backend in separate instrumentation processes. It requires complete `Closed`/port release before same-port rebinding; covers all configured wire/payload success combinations with live global/project SSE, concurrent health, two echoes, two larger-than-window downloads, and a shared checkpoint; exercises wrong token, wrong STCP secret, bind conflict, and restart/control-epoch recovery; and emits strict structured backend-equivalence evidence without an active-generation fallback.
- `FrpcStcpVisitorSerializationContractTest`, `FrpcStcpVisitorFixtureContractTest`, and `FrpcStcpVisitorManagerContractTest` cover the implementable suspend bridge API and stable DTO defaults, field names, `Long` values, tolerant JSON, shared Go/Kotlin bridge DTO JSON, and safe summaries; Kotlin loading and integrity validation of the versioned canonical STCP manifest and small wire/control/yamux/payload bytes, including Go Snappy raw/framed and AES-CFB-plus-Snappy cross-language vectors plus declared chunk-plan and mutation-recipe metadata; and manager validation of native-ready results, session identity, runtime and terminal recovery, control-epoch rollback, final ensured bind ports, cleanup/replacement, and secret-free diagnostics.
- `SensitiveValueToStringTest` verifies that network, domain, Store, feature, and UI value summaries omit synthetic credentials, URLs/endpoints, aliases, paths, prompts, Base64, SSE payloads, and tool output while preserving normal value-object behavior.
- `OpenCodeContrastTest` enforces light/dark 4.5:1 text contrast and 3:1 graphical contrast across theme text, semantic Diff/Markdown/syntax/chart colors, status indicators, attachment scrims, selection borders, and `ControlBorder`.
- `SessionRunningIndicatorTest` covers the 4-by-4 corner mask, independent 1-2 second dot timing, bounded phase offsets, accessible alpha and scale ranges, visible frame changes, varied initial frames, and seamless common-loop continuity.
- `RecentProjectStoreReducerTest` covers numeric `sortOrder`, legacy array-order preservation, continuous renumbering, top insertion for new projects, stable position for existing-project metadata updates, comparison-key deduplication, atomic reorder with only an explicitly ensured current project, retention of omitted/concurrently added projects, non-revival of concurrently deleted stale entries, per-server isolation, and the 20-project cap. `RecentProjectRecorderTest`, `ProjectPickerViewModelTest`, `ProjectDrawerModelTest`, `ProjectInitialTest`, and `VerticalLazyListReorderTest` cover serialized/retried best-effort recording without existing-project movement, non-blocking navigation, displayed-order persistence and failure rollback callbacks, bounded current-project merging, project-home navigation decisions, Unicode project initials, and short-viewport edge-scroll direction.
- `SessionListWindowCoordinatorTest`, `InMemoryOpenCodeStoreSessionWindowTest`, and `OpenCodeRepositorySessionWindowTest` cover shared 20-item targets, 50-item request headroom, local expansion versus network loading, retry/end state, ordered prefix replacement, rapid-click merging, stale generation/transport rejection, tombstones, project/workspace isolation, and bounded metadata/parent backfill.
- `NotificationAlertPolicyTest`, `NotificationChannelMigrationPolicyTest`, `OpenCodeNotificationAudioAttributesTest`, and `SessionVisibilityRegistryTest` cover one sound owner per event, app/system setting independence, explicit channel sound/mute precedence, legacy-to-v2 migration decisions, notification audio usage, and foreground destination visibility.
- `SessionComposerAgentResolverTest`, `SessionModelPreferenceResolverTest`, and `SessionComposerRouteSelectionTest` cover server-ordered Build/Plan filtering and fallback, initial model/Variant validation and switching fallback, and new-session-only acceptance of lightweight project-home Composer route selections.
- `ComposerParameterPickerScrollTest` covers model lazy-list indexes across provider headers, Default-prefixed Variant indexes, missing selections, and measured item-to-viewport center offsets.
- `LocalizedWindowTest` is an Android instrumentation test that gives the parent composition a locale different from the Activity, verifies Popup and modal bottom-sheet resources use the parent locale, and verifies an open Popup updates when the language changes.
- `ProjectFilePathNormalizerTest`, `ProjectFileUrlBuilderTest`, and `ProjectFileAbsolutePathTest` cover relative-path platform semantics, traversal/absolute rejection, POSIX/Windows-drive/UNC `file://` and display-path construction, UTF-8 percent encoding, round trips, and project-root containment. `ProjectFilePanelCopyMenuTest` covers file/directory long-press menus, all three copy values, and Pick-mode long press without selection changes.
- `PromptSendStateMachineTest`, `OpenCodePromptSenderTest`, and `PromptRequestDtoSerializationTest` cover project-context-only submission, final context validation and deduplication, ordinary/loaded-command propagation, optimistic `file://` parts, new-session message moves, and wire serialization.
- `UserMessagePartsTest` and `SessionRevertProjectionTest` distinguish local `data:` attachments, standalone project contexts, and comment backing files, then restore only current-project contexts under the count limit.
- `ProviderSettingsParsingTest` and `ProviderCapabilityRefreshTest` cover immediate safe projection of secret-capable Provider/auth payloads, authoritative loaded state, original auth method indexes and conditions, safe OAuth URLs, and capability refresh through current SSE authority leases.
- `ProviderSettingsViewModelTest` covers project-aware dynamic API/OAuth inputs, frozen cleartext confirmation, single-flight mutation, OAuth code/callback scope, secret-free state summaries, reload, and capability calibration.
- `CustomProviderValidationTest`, `OpenCodeProviderRepositoryCustomTest`, and `CustomProviderFormViewModelTest` cover exact and overflowing model/header limits, add-action capacity state and no-op feedback preservation, safe global-config projection, disabled/auth/enable transaction ordering, partial and unknown outcomes, disable-before-auth-cleanup, deep-merge no-fake-delete behavior, immutable persisted identifiers, and secret clearing.

## Standard Android Verification

Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

This remains the ordinary minimum for small App changes; every such change is not required to build Canary or Kotlin Release-Like. Also run `:app:testCanaryUnitTest`, `:app:testKotlinReleaseUnitTest`, `:app:assembleCanary`, `:app:assembleKotlinRelease`, and `:app:verifyPureKotlinPackaging` when changing STCP backend selection, the pure Kotlin backend, shared STCP manager integration, or CI/Release variant validation. The packaging task resolves both Kotlin runtime classpaths and inspects every ABI APK, rejecting the GoMobile bridge module or any `libgojni.so` entry. The full bridge and CI-equivalent gate below always runs all three App verification variants.

Run focused tests during development by naming a test class, for example:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.ycfeng.ocdeck.core.security.RedactorTest"
```

## Fixed-frp STCP Interoperability

Run the explicit host-JVM interoperability harness when either STCP backend, shared protocol/runtime code, or its CI integration changes:

```powershell
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcInteropTest
```

```bash
./gradlew --no-daemon :frpc-stcp-visitor:frpcInteropTest
```

The task is intentionally separate from `testDebugUnitTest`: ordinary unit tests do not download or execute external programs. It selects the repository-pinned official frp `v0.69.1` asset for Linux, Windows, or macOS on amd64/arm64, verifies its SHA-256 before every extraction, applies bounded traversal/link-safe extraction, verifies `frpc --version` and `frps --version`, and caches only the verified archive and executables under the Gradle user home. These test-only binaries are not committed, packaged into an APK/AAR, staged, or published.

The harness starts loopback-only official `frps`, an official provider `frpc`, and a bounded synthetic OpenCode HTTP/SSE server with per-run credentials and TLS material. It covers wire v1/v2 and all four encryption/compression combinations; two live SSE streams concurrent with REST plus multiple incompressible uploads and downloads larger than the yamux initial window; wrong token, wrong STCP secret, and bind conflict; and frps restart with existing SSE interruption, control-epoch advance, and concurrent REST/SSE recovery. Logs, temporary configuration, process lifetime, archive input, sockets, and cleanup are bounded and redacted. This host gate does not replace Android-device validation.

## Android STCP A/B Interoperability

Build the generated GoMobile AAR first, then start a new Gradle invocation so the configuration-time bridge dependency is present. The `ocdeck.frp.androidInterop.suite` property is required and accepts only `compat` or `full`. The examples below prefer `full` and request an acceptance summary; its parent directory must exist and `<new-summary-path>` itself must not exist before the run:

```powershell
.\frpc-stcp-visitor-go\build-aar.ps1
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcAndroidInteropTest `
  -PrequireGoMobileBridge=true `
  "-Pocdeck.frp.androidInterop.deviceSerial=<serial>" `
  "-Pocdeck.frp.androidInterop.suite=full" `
  "-Pocdeck.frp.androidInterop.summaryFile=<new-summary-path>"
```

```bash
bash frpc-stcp-visitor-go/build-aar.sh
./gradlew --no-daemon :frpc-stcp-visitor:frpcAndroidInteropTest -PrequireGoMobileBridge=true \
  "-Pocdeck.frp.androidInterop.deviceSerial=<serial>" \
  "-Pocdeck.frp.androidInterop.suite=full" \
  "-Pocdeck.frp.androidInterop.summaryFile=<new-summary-path>"
```

For an ordinary local run that does not need an acceptance summary, omit only `ocdeck.frp.androidInterop.summaryFile`; the explicit suite remains mandatory.

The host coordinator provisions the same hash-pinned official frp v0.69.1 tools as `frpcInteropTest`, starts loopback-only TLS `frps`, provider `frpc`, and the bounded synthetic server, then creates one owned dynamic `adb reverse` mapping. It refuses an ambiguous device set, remote adb-server routing, and a pre-existing test package. Synthetic credentials are streamed over bounded stdin into the test package's private files directory and are never passed as Gradle properties or instrumentation arguments. Results contain only fixed structural fields; cleanup removes only the package, private files, and reverse mapping owned by that run.

The `compat` suite contains exactly `success-v1-00`. The `full` suite contains exactly 12 profiles: `success-v1-00`, `success-v1-01`, `success-v1-10`, `success-v1-11`, `success-v2-00`, `success-v2-01`, `success-v2-10`, `success-v2-11`, `negative-wrong-token-v2-00`, `negative-wrong-secret-v2-00`, `negative-bind-conflict-v2-00`, and `restart-v2-11`. The eight success profiles are the Cartesian product of wire v1/v2, encryption off/on, and compression off/on.

For every selected profile, GoMobile runs first in a fresh instrumentation process. Only after `stopVisitor`, `stopSession`, `Closed` verification, and listener-port release does Kotlin run in another instrumentation process and rebind the same port. Every success profile keeps global and project SSE open while health, two echo requests, and two downloads larger than the Yamux window run concurrently; both SSE streams must read the same positive checkpoint. The restart profile additionally requires the existing SSE streams to disconnect, control to become unavailable, the host to wait for both restarted `frps` readiness and provider `frpc` reconnection, the recovered control epoch to be strictly greater than the initial epoch, and post-recovery REST, SSE, and larger-than-window traffic to pass.

The Host summary is strict structured evidence, not a task-status proxy. It records the suite, device API/ABI/page size, exact ordered profiles, each profile's scenario/wire/encryption/compression parameters, backend order `gomobile,kotlin`, exact per-backend checks, semantic equivalence, and `authorizesKotlinDefault=false`. Missing, reordered, partial, or inconsistent fields fail validation; Gradle task success alone cannot stand in for profile evidence.

The explicitly requested `.github/workflows/frpc-kotlin-android-interop.yml` runs the exact candidate SHA with API 26 x86_64 fixed to `compat` and API 36 x86_64 fixed to `full`. It supports direct `workflow_dispatch` and a read-only `workflow_call`; the latter is used only when a manual CI dispatch sets `run_frpc_android_interop=true`, which bootstraps the branch-local workflow before it exists on the default branch. The optional CI `candidate_sha` defaults to the dispatched ref SHA, and push/pull-request CI never invokes K6V. Preflight runs the report-renderer unit tests. The renderer fails closed unless both lane files and their Host summaries satisfy the exact lane/profile contract; it does not infer profile success from the Gradle outcome. Each lane also records the actual Android test APK and GoMobile bridge AAR SHA-256 values. The workflow uploads bounded English/Chinese acceptance reports, consolidated JSON evidence, and `SHA256SUMS`; it has read-only repository permission, no signing Environment or secrets, and every report keeps `authorizesKotlinDefault=false`.

Recorded expanded K6V evidence: exact candidate `01ba5437276fb5074da5c654219668ac4cb69d48` (tree `22a32b9027fb6731bad57e10a1793863f5348d87`) passed [workflow run 29763640048](https://github.com/ycfeng/ocdeck-android/actions/runs/29763640048), including the complete ordinary verification job that builds and tests the Kotlin Release-Like variant. API 26 Android 8.0.0 completed the one-profile x86_64 `compat` suite, while API 36 Android 16 completed the x86_64 12-profile `full` suite; both reported 4 KiB pages. The consolidated artifact `k6v-frpc-android-interop-01ba5437276fb5074da5c654219668ac4cb69d48` reported `Pass`; its GitHub artifact digest is `sha256:f045f29dec1b705d9058fdafeb07917a19365885611ed71cb706c90c34d5ebaf`. The report verifies the fixed backend order, semantic outcomes, exact checks, same-port rebinding, all wire/payload combinations, three negative cases, and restart/control-epoch recovery. It still sets `authorizesKotlinDefault=false` and does not satisfy physical ARM/16KB, the App's real Store/snapshot/reconciliation path, Doze/network/foreground-background, performance, resource-leak, long-duration soak, or formal stable-release-cycle gates.

Earlier phase-one evidence remains recorded for exact candidate `459c2b57ebf465d6b933ea939f59fa739128ec59` (tree `a6ce1019adfa351bc15e09bc479220a967bdf323`) and [workflow run 29716724485](https://github.com/ycfeng/ocdeck-android/actions/runs/29716724485). It covered only the former single GoMobile-then-Kotlin v1/plain scenario and is superseded for current K6V scope by the expanded evidence above.

With an emulator or device connected, run the instrumentation suite:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Full Bridge and CI-Equivalent Gate

Prepare patched frp and run the two Go race-test scopes:

```bash
cd frpc-stcp-visitor-go

go run ./cmd/preparefrp
go test -race -modfile=build/frp-patched.mod ./...
cd build/frp-v0.69.1-p1
go test -race ./client/...
```

The first Go race scope, run from `frpc-stcp-visitor-go/`, automatically executes the canonical fixture check against `frpc-stcp-visitor/src/test/resources/io/github/ycfeng/ocdeck/frpcstcpvisitor/contract/v1/` through the fixed oracle in `frpc-stcp-visitor-go/internal/contractfixture/`. The current `k0-go-oracle-v5` manifest has 34 entries, including v1 `LoginResp`, v1/v2 work/visitor messages, Go Snappy raw/framed output, the 65,536/65,537-byte framing boundary, and AES-CFB-plus-Snappy payload-order vectors. `go run ./cmd/preparefrp` must run first, as shown above. The existing root race-test command remains the CI gate; do not add a separate fixture-check command.

The protocol fixtures do not replace runtime lifecycle tests. Initial-login failure cleanup, reconnect propagation of the prior RunID, invalidation of stale readiness after disconnect, and retry after a stop timeout remain covered by the existing Go wrapper and patched frp tests in the two race-test scopes above. The pinned runtime tracker also ignores visitor callbacks whose epoch is not the active control epoch; any change to that guard must add a focused downstream regression test.

Return to the repository root, audit community/documentation and third-party/legal metadata, run the cross-checkout bridge reproducibility gate, and run the Android gate:

```bash
python3 .github/scripts/audit-community.py
python3 .github/scripts/audit-third-party.py
bash ./gradlew --no-daemon :frpc-stcp-visitor:frpcInteropTest
bash .github/scripts/verify-bridge-reproducibility.sh
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleKotlinRelease :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true
```

The Go race detector requires CGO and a supported C compiler. If that toolchain is unavailable on Windows, run both Go race scopes under WSL or Linux; ordinary Windows Go tests do not replace the race gate. PowerShell also does not stop on a failing native process by default, so the sequence below uses a small fail-fast wrapper.

On Windows PowerShell, run the equivalent sequence from the repository root:

```powershell
$ErrorActionPreference = 'Stop'
function Invoke-NativeChecked {
    param([scriptblock]$Command)
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Native command failed with exit code $LASTEXITCODE"
    }
}

Push-Location .\frpc-stcp-visitor-go
Invoke-NativeChecked { go run ./cmd/preparefrp }
Invoke-NativeChecked { go test -race '-modfile=build/frp-patched.mod' ./... }
Push-Location .\build\frp-v0.69.1-p1
Invoke-NativeChecked { go test -race ./client/... }
Pop-Location
Pop-Location

Invoke-NativeChecked { python .github/scripts/audit-community.py }
Invoke-NativeChecked { python .github/scripts/audit-third-party.py }
Invoke-NativeChecked { .\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcInteropTest }
Invoke-NativeChecked { .\.github\scripts\verify-bridge-reproducibility.ps1 }
Invoke-NativeChecked { .\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleKotlinRelease :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true }
```

The reproducibility scripts require a clean checkout. On the current host platform, they build the current checkout plus a detached worktree at a different absolute path, isolate `GOCACHE`, `GOMODCACHE`, and `GOPATH` for each build, and compare the complete AAR, required sources JAR, POM, checksum, API, bridge/frp provenance, and native-sidecar set byte-for-byte. They remove the temporary checkout and caches but leave the primary outputs in the current checkout for the Gradle gate. This is not a Windows-versus-Linux byte-identity claim. CI and Release use the shell script; Windows developers may run the PowerShell counterpart.

The pinned Go, x/mobile, Android API, and NDK versions must come from `bridge-versions.properties`.

Changes to the Go wrapper, downstream frp patch, Android bridge module, either STCP backend, App backend selection, bridge API, failure handling, or version metadata require the fixed-frp interoperability task and complete bridge gate rather than only Android unit tests. The Android gate validates Debug/GoMobile selection and assembly, Canary/Kotlin and Kotlin Release-Like/Kotlin selection, and bridge-free runtime/APK packaging for both Kotlin variants. A Kotlin-only bridge API or failure-handling change may leave generated AAR bytes and `BRIDGE_VERSION` unchanged, but it does not waive any gate above. Increment `BRIDGE_VERSION` whenever native or generated AAR bytes change.

## Security and Boundary Tests

- Use synthetic credentials and assert that thrown errors, aliases, `toString()` output, logs, and UI-safe messages do not reveal them.
- Assert that Repository, SSE, and snapshot failures retain semantic types, that Kotlin runtime failures are mapped from their enum rather than exception text, that bind-conflict retry never parses messages, that UI copy comes from localized resources rather than exception strings, and that cancellation and JVM `Error` propagate.
- For Retrofit methods, verify the explicit inbound mode, separate encoded and decoded exact limits, `max + 1`, unknown and understated lengths, real gzip/Bridge ordering, body close behavior, non-2xx zero-read handling, successful Unit-body discard, and no-`Invocation` bypass.
- Cover the Direct cleartext-credential matrix: normalized remote/LAN `http://` plus a nonblank OpenCode username and new or retained password requires advisory confirmation; HTTPS, syntactic loopback, incomplete Basic credentials, SSH, and STCP do not. Test confirm, cancel, duplicate confirmation, frozen-request behavior, and classification without DNS.
- Cover Provider secret submission separately: a new API key or custom-header value over Direct non-loopback HTTP requires one frozen confirmation, while HTTPS, syntactic loopback, SSH, and STCP do not. Assert that API keys and header values never enter Android persistence, public UI state, raw response logs, or `toString()` output.
- Test Provider auth method parsing with unknown entries before supported entries so original wire indexes cannot be renumbered. OAuth tests retain directory/workspace and method index, reject unsafe browser URLs, cover code and cancellable auto callbacks, and never automatically retry stateful authorize/callback operations.
- Test Custom Provider's disabled-stage, optional credential write, final enable, disable-before-auth-cleanup, partial/unknown outcomes, and deep-merge deletion restrictions. Config projections retain only safe identities and header names.
- Generate exact-limit and `max + 1` bodies in test code. Keep committed canonical STCP contract files small, synthetic, and deterministic; do not create giant fixture files.
- Exercise cancellation, callback races, late results, generation changes, and unknown commit outcomes for network and credential state machines.
- For HTTP errors that must not consume a body, test that the body remains unread.
- For tolerant DTOs, include unknown fields and malformed optional subtrees while preserving valid surrounding data.
- Never record real server responses, project data, prompts, credentials, private paths, or host fingerprints as fixtures.

See [Test fixtures](test-fixtures.md) for fixture rules.

## Manual UI and Accessibility Matrix

Until device instrumentation becomes a CI gate, record the device/API level, theme, font scale, and input method used for relevant UI changes, then verify:

| Environment | Checks | Expected result |
| --- | --- | --- |
| Compact portrait phone at 200% font scale | Confirmation dialogs, server status, settings sheets, inline suggestions, permission/question UI, Composer attachment and parameter rows | Content remains bounded and scrollable; titles, fields, and primary actions remain reachable without fixed-height clipping |
| Project picker and drawer with up to 20 recent projects | Long-press vertical drag, edge auto-scroll, picker/drawer synchronization, ordinary project click, picker delete icon, forced persistence failure, current project absent from persistence, 75% drawer-list height cap, Open Project, Settings, project-home switching, and the drawer's horizontal gesture | Both entries show the same saved order; a new project starts at the top; clicking an existing project does not reorder; only the picker card body starts drag and Delete remains independent; optimistic movement rolls back on failure; current-project merge remains capped at 20 and can persist through reorder; fixed actions remain reachable and never move; horizontal drawer gestures are disabled only during rail drag; switching closes the drawer without stacking duplicate project homes |
| Project with more than 20 root sessions | Load More in project home and drawer, shared state, network loading, retry, end state, direct old-session route | Both surfaces show the same target; existing rows remain on failure; retry is reachable; end state is stable; an out-of-window session and bounded parent chain load without shrinking the window |
| Fresh install and legacy notification upgrade on API 26/29/30+/33+ | Default alert, app None, explicit system sound, explicit channel mute/block/low importance, permission denial, foreground/background current session | Each event has at most one sound owner; Android system choices take precedence; app playback does not bypass low-interruption settings; a background session is not incorrectly marked viewed |
| IME open | Fixed bottom Composer, suggestion panel, model/agent/variant popup, attachment strip, system Back | Insets keep the Composer visible; overlays are not obscured; Back dismisses the focused overlay before navigating away |
| TalkBack enabled | Project tabs and paths, clickable icon labels, Tab/RadioButton/Checkbox/Switch state, expanded/collapsed controls, unread/error/permission/connection cues, server ordering, and project ordering in both entries | Project tabs announce a unique full name/path label, selected state, and notification state; other roles and states are announced; important meaning is not color-only; server cards plus picker and drawer projects expose localized Move Up/Move Down custom actions |
| Light and dark themes | Body/supporting text, fields and borders, selected/error/status indicators, Diff/Markdown/code, attachment scrim | Text meets the 4.5:1 target and required non-text controls/indicators meet 3:1; state remains understandable in both themes |
| Composer model and Variant pickers | Reopen after selecting an off-screen item, search, provider grouping, current check, Connect Provider, Manage Models | Reopening scrolls the current selection into view and centers it when scroll bounds permit; search and manual scrolling remain under user control; rows and icon actions retain 48 dp targets; management actions close the picker and navigate to the real provider/model settings routes |
| Provider settings and authentication | Search and loaded/available groups; dynamic text/select prompts; API-key keyboard; OAuth browser, code, auto callback, and cancel; cleartext/loopback/disconnect confirmations | Secrets disappear when the surface closes; Back dismisses the focused sheet; controls remain reachable with IME and 200% text; loaded, disabled, pending, and error states have non-color cues; cancellation does not silently retry |
| Custom Provider create/edit | Multiple model/header rows, count/limit states, persisted identifier restrictions, password fields, save, disabled/unknown outcomes, disable and credential cleanup | Rows and actions remain reachable and at least 48 dp; full/overflow limits are explicit and addition re-enables after removing a new row; persisted values are never echoed; partial/unknown states are explicit; disabling does not claim physical deletion; both themes and TalkBack expose the same state |
| Project-home Composer preview | Agent, Model, and Variant controls; input and attachment entry; system Back; compact screen and 200% font scale | Parameter pickers open and update in place without navigation; Back closes the focused popup; provider/model actions use real settings routes; input and attachment entry open `"new"` with the current validated selection |
| Project-file picker | Tree and search, file/directory long-press copy menus, up to 10 selections, independent preview, confirm/cancel, preview-to-tree Back, route/session change, context chips, context-only send/reset | Long press does not open, expand, or toggle selection; file/directory names and normalized relative/absolute paths copy correctly; selection has Checkbox semantics and non-color cues; preview does not toggle selection; cancel leaves the draft unchanged; stale routes receive no result; controls remain reachable with IME, 200% text, TalkBack, and both themes |

The absence of an instrumentation gate must be reported as a testing gap; manual checks are evidence for the tested device/configuration only, not universal Android coverage.

## Manual Release Validation

Static checks cannot prove that Android can load a native library on every target device or that a real tunnel works. Before a formal release, complete and record the manual items in the [release checklist](../release/checklist.md), including:

- Native load on the target release ABIs that can be physically tested.
- Native load and app startup on a physical 16KB page-size device.
- A real frps/STCP visitor path through `/global/health`, REST, and SSE.
- Direct and SSH smoke tests through the same path used by the app.
- First installation and startup for each physically available signed ABI-specific APK.
- Replacement update from the previous real public version with expected local-data retention; record the first public version as not applicable rather than claiming untested upgrade evidence.
- Rejection of incompatible signing and lower-`versionCode` replacement attempts.
- Destructive rollback and uninstall/reinstall behavior, including app-private local-data loss and the absence of a supported export/restore workflow.
- Re-download from the public Release and execution of both the complete `SHA256SUMS` check and the documented one-APK checksum procedure.

For the `0.1.0` candidate, maintainers recorded the native-loading, 16KB page-size, and real STCP checks above as passing. Exact device and deployment details are not published, so the evidence applies only to this candidate and does not establish universal Android or OpenCode Server coverage. Other unchecked manual items remain independent, and every future candidate must repeat the applicable gates. See the [compatibility matrix](../user/compatibility.md).
