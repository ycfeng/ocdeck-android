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
| Android build | App unit tests and APK builds for Debug, the internal Kotlin Canary, and the GoMobile/Kotlin Release-Like device-test variants, plus `:frpc-stcp-visitor` unit tests. A separate signed Kotlin-default dry-run uses the formal identity and certificate but is never publishable. |
| Android instrumentation tests | Compose window localization across Popup and modal bottom-sheet roots, plus a test-only `:frpc-stcp-visitor` harness that runs the real GoMobile and Kotlin backends sequentially against one fixed-frp topology. |
| Manual UI/accessibility validation | Compact screens, 200% font scale, IME overlap, project ordering in the picker and drawer, project-file selection, Provider auth/OAuth/Custom Provider flows, TalkBack semantics/actions, both themes, and real model-settings navigation. |
| Release artifact validation | Profile-aware APK metadata, one signer, expected certificate fingerprint, ABI isolation, `zipalign -P 16`, backend class/native policy, embedded legal files, filenames, and checksums. GoMobile binds `libgojni.so` to the AAR sidecar; Kotlin-default rejects the bridge class and every `libgojni.so` while still validating other native libraries. |
| Physical-device validation | Maintainer-recorded `0.1.0` release-gate validation passed physical-device native loading/startup, 16KB page-size native operation, and a real STCP loop covering `/global/health`, representative REST, global/project SSE, and controlled reconnect. Exact environment details are not published; future candidates must repeat these gates. |

The explicitly requested K6V workflow provides fixed API 26 `compat` and API 36 `full` x86_64 emulator lanes for STCP backend interoperability, but ordinary CI still has no App UI emulator/instrumentation job. K6V can be dispatched directly after its workflow exists on the default branch; while the workflow is first being introduced, a manual CI dispatch can opt into the same branch-local reusable workflow. Push and pull-request CI never enables that bootstrap automatically. The `app/src/androidTest` suite covers localized independent-window roots. Recent-project drag ordering remains outside instrumentation coverage; its automated coverage is primarily the JVM reducer, recorder, ViewModel, and drawer-model tests listed below. Project selection, session navigation, broader Composer interactions and pickers, permission/question UI, large-text behavior, and TalkBack still need systematic device automation.

## Focused Test Inventory

- `RetrofitInboundResponsePolicyTest` and `EncodedResponseLimitInterceptorTest` verify that every `OpenCodeApi` method declares `BOUNDED` or `EMPTY_SUCCESS`; missing policy fails before network proceed; encoded and decoded declared/unknown/understated lengths enforce their 16 MiB boundaries at `max + 1`; a real OkHttp gzip chain applies the encoded cap before Bridge decoding; non-2xx and successful Unit bodies close without reading; `/file/content` remains lazy; and requests without Retrofit `Invocation` pass outside the Retrofit interceptor.
- `SessionMessagesTransportTest`, `SessionMessagesResponseReaderTest`, `InMemoryOpenCodeStoreRevisionTest`, `SessionMessageJumpTest`, and `FileContentResponseReaderTest` cover cursor query/header handling, older-page merge, overlapping-refresh cursor preservation, disjoint-refresh gap bridging, cursor compare-and-set and cycle rejection, latest-generation-wins behavior when first-page requests complete in reverse order, `(createdAt, id)` tie ordering, first-page covered-window deletion reconciliation, session-deletion cursor and pending-generation cleanup, empty projected timeline loading and jump-control availability, body-free non-2xx failures, direct OkHttp callback-thread decode, encoded and decoded exact/unknown/underreported lengths, EOF verification, cancellation and callback races, the separate per-page 64 MiB session-message limits, and `/file/content` reader-level decoded defense in depth.
- `OpenCodeFailureTest`, `ErrorUiTextTest`, and `OpenCodeRepositoryFailureHandlingTest` cover semantic classification without reading `Throwable.message`, including mapping `KotlinFrpcStcpVisitorFailure` enum values to App failures and local-port rejection; localized resource mapping with operation-specific fallback; Repository propagation; response-too-large behavior; and propagation of cancellation and JVM `Error`.
- `BoundedSseReaderTest`, `OkHttpSseEventSourceFactoryTest`, `OpenCodeEventClientLifecycleTest`, and `ProjectSnapshotCoordinatorTest` cover explicit identity encoding, non-identity zero-read rejection, all line endings and EOF states, 32 MiB line/event limits, body-free status/MIME failures, cancellation, retry classification, terminal close, per-connection authentication on clients that share only an unauthenticated transport dispatcher/pool, matching 64-call dispatcher and fail-fast admission bounds with reusable terminal slots, owner/generation/source/transport races, project/global authority handoff, bounded replay, and snapshot failure/recovery.
- `FrpcStcpReadinessRetryClassifierTest` and `GoMobileFrpcStcpVisitorClientTest` cover transient versus permanent readiness failures, inbound-policy failures, typed unavailable/API-mismatch bridge errors, safe bridge summaries, API v2 JSON, revision/control epoch, `WaitVisitorReady`, and reflection cancellation/JVM `Error` propagation.
- `FrpcStcpVisitorClientFactoryTest` runs in every enabled App unit-test variant and verifies that Debug and GoMobile Release-Like select `GoMobileFrpcStcpVisitorClient`, Canary, Kotlin Release-Like, and the signed Kotlin-default dry run select `KotlinFrpcStcpVisitorClient`, and the explicit factory can construct either backend without a runtime fallback.
- `FrpcStcpVisitorManagerTest` covers shared generation/lease/readiness behavior, separate 10-second-equivalent cold and longer same-generation epoch-refresh native budgets, retryable native/application-timeout generation preservation, readiness-health-probe owner closure on success, failure, timeout, and cancellation, conversion of `BindException` and typed Kotlin bind failures to `LocalPortInUse`, bounded predecessor bind retry, and rejection of non-bind typed failures even when their message contains bind-like text.
- `OpenCodeConnectionClientTest` and `ServerRepositoryConnectionCacheTest` cover idempotent owned-client and single-API health-client cleanup; repeated and concurrent per-server REST/OAuth/session-message client reuse; server isolation; committed versus rejected configuration changes; STCP control-epoch replacement without caching one-time readiness health evidence; and a maximum of three transport recaptures before typed continuous-churn failure.
- `DefaultNetworkMonitorTest` and `AppConnectionCoordinatorTest` cover default-network identity deduplication without logging or persistence, current-loss notification, stale-loss rejection, non-identifying callback/dispatch and recovery counters, coalesced backend invalidation, suppression of stale active-flight results, exactly one merged server follow-up after concurrent network changes, and at most one bounded follow-up after a current recovery check fails.
- Internal control and crypto tests also cover bounded cleanup when a reconnect acquires a mux but fails before control Open, publication of the resulting typed retry failure, a delayed same-RunID reconnect response that exceeds the initial-login budget but succeeds within the separate reconnect-login budget without a third dial, active reset of a control read that ignores thread interruption when the independent login-budget timer expires, persistent safe evidence that the next attempt follows a login timeout, preservation of typed Yamux failures through crypto wrappers, fixed secret-free crypto classification, and subsequent same-generation recovery.
- `KotlinFrpcStcpVisitorClientTest` and the internal control, crypto, protocol, transport, yamux, and compression tests cover typed runtime failures, revision/control-epoch readiness, explicit same-generation transport invalidation and new-epoch reconnect, invalidation of every Open session, Android route injection with bounded resolved-candidate fallback across TCP connect, TLS setup, handshake, and stream acquisition while the original configured endpoint remains the TLS identity, a reconnect-only watchdog covering every old-session cleanup barrier after physical close, forced abortion of stalled Yamux logical joins without premature termination completion, same-generation epoch advance, unchanged strict explicit-close semantics and cancellation/JVM-`Error` propagation, listener ownership and rebinding, v1/v2 visitor handshakes, all four `useEncryption`/`useCompression` combinations, coalesced handshake/payload reads, bounded relay lifecycle, delayed best-effort reset without blocking `stopVisitor` after local shutdown, session-owned permit release, Snappy framing and malformed-input limits, cleanup, cancellation, and secret-free diagnostics.
- `SocketFrpLocalListenerFactoryTest` opens and actively closes a real loopback relay, then verifies that a fully stopped Kotlin generation can immediately rebind the same port while an active listener still retains exclusive ownership.
- `FrpcStcpVisitorClientDifferentialContractTest` runs the same six public operations against a scripted GoMobile Kotlin adapter seam and an injectable pure Kotlin runtime fixture, comparing normalized phase/revision/epoch/listener/bind semantics, idempotency, replacement, typed bind conflict, cancellation identity, and safe diagnostics. It is a host-JVM adapter/runtime contract test: it neither loads the native AAR nor replaces real-frps/device interoperability testing.
- `FrpcStcpVisitorAndroidInteropTest`, launched through `frpcAndroidInteropTest`, runs every selected `compat` or `full` profile through the real generated GoMobile AAR and pure Kotlin backend in separate instrumentation processes. It requires complete `Closed`/port release before same-port rebinding; covers all configured wire/payload success combinations with live global/project SSE, concurrent health, two echoes, two larger-than-window downloads, and a shared checkpoint; exercises wrong token, wrong STCP secret, bind conflict, and restart/control-epoch recovery; and emits strict structured backend-equivalence evidence without an active-generation fallback.
- `FrpcStcpVisitorSerializationContractTest`, `FrpcStcpVisitorFixtureContractTest`, and `FrpcStcpVisitorManagerContractTest` cover the implementable suspend bridge API and stable DTO defaults, field names, `Long` values, tolerant JSON, shared Go/Kotlin bridge DTO JSON, and safe summaries; Kotlin loading and integrity validation of the versioned canonical STCP manifest and small wire/control/yamux/payload bytes, including Go Snappy raw/framed and AES-CFB-plus-Snappy cross-language vectors plus declared chunk-plan and mutation-recipe metadata; and manager validation of native-ready results, session identity, runtime and terminal recovery, control-epoch rollback, longer epoch-refresh native waiting, native/application timeout generation preservation, final ensured bind ports, cleanup/replacement, and secret-free diagnostics.
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

This remains the ordinary minimum for small App changes; every such change is not required to build the internal or Release-Like variants. Also run `:app:testCanaryUnitTest`, `:app:testGoMobileReleaseUnitTest`, `:app:testKotlinReleaseUnitTest`, `:app:assembleCanary`, `:app:assembleGoMobileRelease`, `:app:assembleGoMobileReleaseAndroidTest`, `:app:assembleKotlinRelease`, `:app:assembleKotlinReleaseAndroidTest`, and `:app:verifyPureKotlinPackaging` when changing STCP backend selection, the pure Kotlin backend, shared STCP manager integration, shared Release-Like instrumentation, or CI/Release variant validation. The AndroidTest assemblies compile and package the shared Release-Like test source and manifest for both backends. The packaging task resolves both Kotlin runtime classpaths and inspects every ABI APK, rejecting the GoMobile bridge module or any `libgojni.so` entry. The full bridge and CI-equivalent gate below always runs this expanded verification set.

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

The explicitly requested `.github/workflows/frpc-kotlin-android-interop.yml` runs the exact candidate SHA with API 26 x86_64 fixed to `compat` and API 36 x86_64 fixed to `full`. It supports direct `workflow_dispatch` and a read-only `workflow_call`; the latter is used only when a manual CI dispatch sets `run_frpc_android_interop=true`, which bootstraps the branch-local workflow before it exists on the default branch. The optional CI `candidate_sha` defaults to the dispatched ref SHA, and push/pull-request CI never invokes K6V. Preflight runs the report-renderer unit tests. The renderer fails closed unless both lane files and their Host summaries satisfy the exact lane/profile contract; it does not infer profile success from the Gradle outcome. Host and lane evidence first read the Android runtime ABI page size with `adb shell getconf PAGE_SIZE`. Only when a legacy Android shell reports that command as missing do they fall back to the first bounded `KernelPageSize` or `MMUPageSize` entry in `/proc/self/smaps`. This preserves API 26 coverage while avoiding misclassification of an x86_64 16 KiB Android test image whose MMU backing pages are still 4 KiB. Each lane also records the actual Android test APK and GoMobile bridge AAR SHA-256 values. The workflow uploads bounded English/Chinese acceptance reports, consolidated JSON evidence, and `SHA256SUMS`; it has read-only repository permission, no signing Environment or secrets, and every report keeps `authorizesKotlinDefault=false`.

Recorded expanded K6V evidence: exact candidate `01ba5437276fb5074da5c654219668ac4cb69d48` (tree `22a32b9027fb6731bad57e10a1793863f5348d87`) passed [workflow run 29763640048](https://github.com/ycfeng/ocdeck-android/actions/runs/29763640048), including the complete ordinary verification job that builds and tests the Kotlin Release-Like variant. API 26 Android 8.0.0 completed the one-profile x86_64 `compat` suite, while API 36 Android 16 completed the x86_64 12-profile `full` suite; both reported 4 KiB pages. The consolidated artifact `k6v-frpc-android-interop-01ba5437276fb5074da5c654219668ac4cb69d48` reported `Pass`; its GitHub artifact digest is `sha256:f045f29dec1b705d9058fdafeb07917a19365885611ed71cb706c90c34d5ebaf`. The report verifies the fixed backend order, semantic outcomes, exact checks, same-port rebinding, all wire/payload combinations, three negative cases, and restart/control-epoch recovery. It still sets `authorizesKotlinDefault=false` and does not by itself satisfy the split target-ABI physical ARM and 16KB runtime lanes, the App's real Store/snapshot/reconciliation path, Doze/network/foreground-background, performance, resource-leak, long-duration soak, or formal stable-release-cycle gates. The ARM and 16KB lanes may use different devices; missing physical ARM 16KB hardware is recorded as residual combination risk rather than a development blocker.

Earlier phase-one evidence remains recorded for exact candidate `459c2b57ebf465d6b933ea939f59fa739128ec59` (tree `a6ce1019adfa351bc15e09bc479220a967bdf323`) and [workflow run 29716724485](https://github.com/ycfeng/ocdeck-android/actions/runs/29716724485). It covered only the former single GoMobile-then-Kotlin v1/plain scenario and is superseded for current K6V scope by the expanded evidence above.

## Release-Like App STCP E2E

The dedicated App E2E tasks install a non-publishable Release-Like target APK and its test APK, then exercise the real application assembly through the selected STCP backend. `frpcAppE2eTest` uses `kotlinRelease`; `frpcGoMobileAppE2eTest` uses the symmetric `goMobileRelease` identity. Both variants use an application-ID suffix and the Android Debug test certificate, and neither is a release artifact. Build the generated GoMobile AAR first because the shared host-test configuration validates the retained bridge, then start a new Gradle invocation. The optional summary parent must exist and `<new-summary-path>` itself must not exist:

```powershell
.\frpc-stcp-visitor-go\build-aar.ps1
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcAppE2eTest `
  -PrequireGoMobileBridge=true `
  "-Pocdeck.frp.appE2e.deviceSerial=<serial>" `
  "-Pocdeck.frp.appE2e.suite=<core|lifecycle|process>" `
  "-Pocdeck.frp.appE2e.summaryFile=<new-summary-path>"
```

```bash
bash frpc-stcp-visitor-go/build-aar.sh
./gradlew --no-daemon :frpc-stcp-visitor:frpcAppE2eTest -PrequireGoMobileBridge=true \
  "-Pocdeck.frp.appE2e.deviceSerial=<serial>" \
  "-Pocdeck.frp.appE2e.suite=<core|lifecycle|process>" \
  "-Pocdeck.frp.appE2e.summaryFile=<new-summary-path>"
```

Omit `ocdeck.frp.appE2e.deviceSerial` only when exactly one eligible device is connected, omit `ocdeck.frp.appE2e.suite` to run the default `core` suite, and omit `ocdeck.frp.appE2e.summaryFile` for a non-acceptance local run. Use `frpcGoMobileAppE2eTest` instead of `frpcAppE2eTest` for the GoMobile `core`, `lifecycle`, or `process` path. The `network` suite is Kotlin-only because GoMobile cannot enforce the required leaf-pin contract and fails closed rather than weakening it. The `lifecycle` suite accepts an optional `ocdeck.frp.appE2e.outageMillis` in the bounded 60,000-300,000 ms range and defaults to 120,000 ms. The host provisions the pinned official frp tools, loopback-only TLS `frps`, provider `frpc`, a bounded synthetic OpenCode REST/SSE fixture, and one owned dynamic `adb reverse`. It installs the ABI-matching selected backend APK plus a test-only debuggable instrumentation APK; the target App remains Release-Like and nondebuggable. Per-run credentials are streamed into the test package's private storage through bounded stdin and `run-as`, never Gradle properties, instrumentation arguments, shared storage, or result evidence. Cleanup force-stops and removes both packages, private files, reverse mapping, processes, sockets, temporary material, and any forced device-idle state owned by the run.

The instrumentation uses the real `OpenCodeApplication`/`AppContainer`, `ServerRepository`, `FrpcStcpVisitorManager`, `OpenCodeRepository`, `OpenCodeEventClient`, `InMemoryOpenCodeStore`, and `ProjectSnapshotCoordinator`. The `core` suite retains its strict 13-check snapshot/SSE/restart/reconciliation contract. The 19-check `lifecycle` suite additionally drives the real `MainActivity` through repeated foreground/background transitions, verifies session visibility and retained Store data, enters and exits confirmed deep Doze, holds a multi-backoff outage, and keeps `frps` available while provider `frpc` remains unavailable. Kotlin recovery must preserve the visitor generation and strictly advance the control epoch; the retained GoMobile baseline may replace a failed generation, but its complete transport identity must advance monotonically. Manager cold native readiness remains 10 seconds, same-generation Kotlin epoch refresh receives 90 seconds to cover the 60-second reconnect-login window, and application health has a separate 15-second total budget. A retryable native or application-health timeout during nonterminal Kotlin recovery fails the current refresh flight without destroying the established generation, so a later real recovery can complete safely.

The two-phase `process` suite persists one STCP server and its Keystore-backed credentials, closes phase-one runtime resources, launches an ordinary target process, backgrounds it, requires `am kill` to remove the observed old PID, and then starts a second instrumentation process. The new `OpenCodeApplication` must reload the unique persisted server, rebuild STCP/REST/SSE state, and reach an explicit project/session route before deleting the persisted server and invoking the normal credential-cleanup path. It does not claim automatic navigation restoration, and generation/control-epoch values are not compared across processes. Its combined 19 checks include `process_terminated` plus the prepare and recovery contracts. When a summary is requested, the Host additionally requires a clean repository and binds the result to the lowercase candidate commit/tree plus local and installed target/test APK SHA-256 values. It publishes the bounded summary atomically only after cleanup succeeds. Every summary records the backend, suite, topology, fixed checks, byte-identity evidence, and `authorizesKotlinDefault=false`; it never records a PID, server ID, endpoint, credential, device serial, APK installation path, private path, or raw log.

The fail-closed `network` suite uses a different topology and must be invoked separately with an explicit USB physical-ARM serial and a private configuration file:

```powershell
$externalConfigRoot = Join-Path ([System.IO.Path]::GetTempPath()) "opencode"
$env:OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE = Join-Path $externalConfigRoot "<private-config-file>"
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcAppE2eTest `
  -PrequireGoMobileBridge=true `
  "-Pocdeck.frp.appE2e.deviceSerial=<usb-serial>" `
  "-Pocdeck.frp.appE2e.suite=network" `
  "-Pocdeck.frp.appE2e.summaryFile=<new-summary-path>"
Remove-Item Env:\OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE
```

```bash
OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE="${TMPDIR:-/tmp}/opencode/<private-config-file>" \
./gradlew --no-daemon :frpc-stcp-visitor:frpcAppE2eTest -PrequireGoMobileBridge=true \
  "-Pocdeck.frp.appE2e.deviceSerial=<usb-serial>" \
  "-Pocdeck.frp.appE2e.suite=network" \
  "-Pocdeck.frp.appE2e.summaryFile=<new-summary-path>"
```

The private file uses strict schema 3 JSON with fields `schemaVersion`, `frpsHost`, `frpsPort`, `authToken`, `tlsServerCertificateSha256`, and `tlsTrustedCaCertificatePem`. Structural placeholders such as `<public-ip-or-strict-dns-name>`, `<64-lowercase-hex>`, and `<single-pinned-leaf-certificate-pem>` describe the shape only and are intentionally not runnable values. The endpoint must be a public IPv4/IPv6 literal or a strict multi-label ASCII DNS name. The Host resolves DNS before installation and each device phase resolves it again through the active validated Android `Network`; every returned address must be public, with Android NAT64 results accepted only for per-network DNS resolution. DNS routing is not an identity boundary. The token is limited to 16-256 ASCII letters, digits, `_`, or `-`. The pin is the lowercase SHA-256 of the leaf certificate DER. The PEM contains exactly that one currently valid, non-CA leaf certificate, is at most 8 KiB, and the certificate must be valid for the configured IP address or DNS name. The complete config is at most 16 KiB. The file must be a direct child of the concrete, non-symlink `<java.io.tmpdir>/opencode` trusted root and is opened relative to a secure directory handle when supported, without following links. The root and file must be owned by the account running Gradle and use owner-only permissions: POSIX `0700`/`0600`, or a Windows DACL whose sensitive allow entries name only that exact owner. The harness also applies the same owner-only boundary to generated provider credentials. This boundary rejects access by other OS accounts but does not claim to defend against a malicious process already running as the same account. Never pass the path, token, pin, certificate, or endpoint through a Gradle property, instrumentation argument, shared storage, report, or documentation; provide only the path through `OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE` in the dedicated test environment and clear it afterward.

The controlled external `frps` must force TLS, use matching token authentication, present the configured non-CA leaf certificate, and be directly reachable from both Wi-Fi and cellular. Before sending the token, the pinned local provider `frpc` validates the bounded exact-leaf certificate file and the configured IP or DNS server name; the Kotlin visitor independently validates the leaf DER SHA-256 pin. GoMobile rejects a non-null pin rather than silently ignoring it. A persisted pin alias whose value is unavailable fails closed instead of falling back to trust-all TLS. A saved pin may be retained only while the frps address and port remain unchanged; changing either endpoint field requires a new pin. The Host owns the local provider and bounded synthetic OpenCode fixture, creates no `adb reverse`, rejects any pre-existing reverse mapping, and requires an explicitly selected USB physical-ARM device with validated Wi-Fi and cellular.

The complete 19-item `network` evidence consists of 17 ordered instrumentation checks plus two Host assertions: `configured_pinned_public_server` and `network_restored`. It requires an initially validated Wi-Fi default network and an available validated cellular network. For each direction it first stops provider `frpc`, changes the validated default transport while retaining the previous fixture generation, requires both SSE streams to become unavailable, and proves the exact old snapshot/global/project data remains in Store. Only after that retention barrier does the Host advance the fixture and restart the authenticated provider. Recovery uses the 90-second same-generation native-readiness budget, must keep the visitor generation, strictly advance the control epoch, and reconcile generation-2 data on cellular and generation-3 data after Wi-Fi returns. Per-network DNS retries transient active-network or resolver-not-ready states within a bounded window, but any returned empty or non-public result fails closed immediately. Cleanup restores the original Wi-Fi/mobile-data baseline on every owned failure path. A passing summary must report the exact 17+2 contract, `topology=external`, `networkSwitchProven=true`, clean candidate/APK byte identity, successful cleanup, and `authorizesKotlinDefault=false`, without endpoint, token, pin, certificate, network identity, device serial, installation path, or private path.

`CanaryConfiguredExternalNetworkTest` is a separate read-only gate for an already configured Canary installation. Use it only after confirming the locally built Canary APK is byte-identical to the installed target, then install only `app-canary-androidTest.apk`; do not replace or uninstall the target as part of the gate. A target-owned foreground Activity keeps the App process runnable while instrumentation performs the network switches. The test requires exactly one enabled STCP server, a public IP literal or strict public DNS endpoint, and a persisted TLS leaf pin. It resolves DNS through each active validated network, exercises Wi-Fi to cellular to Wi-Fi recovery against that real configuration, never serializes Store contents, and leaves the persisted server untouched. Progress is an atomically replaced, bounded file in target-private storage containing only an allowlisted granular stage, check count, status/failure code, and heartbeat count. Missing pin, unavailable pin alias, returned non-public resolution, or ambiguous server selection fails closed. Always uninstall only the test package, remove the progress file, restore Wi-Fi/mobile-data, verify no reverse mapping, and independently confirm the target remains installed.

This task is currently a dedicated manual/device gate rather than part of ordinary push or pull-request CI. Clean exact candidate `4b0df8af207b28846423902a61bf261501692101` passed `core`, `lifecycle`, and `process` on both an official Android 16KB x86_64 test image and a physical `arm64-v8a` device. Separately, a configured working-tree Canary build passed all 19 external `network` checks on an explicit USB physical-ARM device without `adb reverse`, proving validated Wi-Fi to cellular to Wi-Fi switching, exact disconnected Store retention, the same visitor generation, two strictly increasing control epochs, global/project SSE recovery, and reconciliation. Independent cleanup preserved the target, removed only the test package and private progress files, restored both network toggles, and left no reverse mapping. This uncommitted result is not represented as an exact-SHA candidate. Performance, resource-leak stress, soak, exact-candidate repetition, and a formal stable-release cycle remain separate gates, and no suite authorizes Kotlin-default assembly.

Clean exact candidate `a3b10394ad575c2895aeb9f8980b2b120dccf320` (tree `afad377b2ad910e95bbbe3e70379c8e894ab85ce`) subsequently passed the complete Kotlin Release-Like external contract on the same API 31 physical `arm64-v8a` 4 KiB device: all 17 instrumentation checks, both Host assertions, validated Wi-Fi to cellular to Wi-Fi switching, target/test APK byte identity, and post-cleanup atomic summary publication passed with `authorizesKotlinDefault=false`. The Windows Host was behind a proxy that returned a reserved `198.18.0.0/15` Fake-IP, so this run used an owner-only temporary JDK hosts file populated from HTTPS DNS-over-HTTPS public A records and deleted it afterward. That override affected only the Host pre-installation resolution; Android still resolved the configured name independently through each active validated Wi-Fi/cellular `Network`, required every result to be public, and retained the exact leaf pin as the identity boundary. This closes the single-device exact external execution gap, but not the required device matrix, resource-policy pass, longer soak, stable observation cycle, or explicit Kotlin-default authorization.

### Symmetric Resource, Stress, Leak, and Soak Harness

The Release-Like resource tasks run the shared `core` workload in one App/instrumentation process for either backend. `frpcGoMobileAppResourceTest` and `frpcKotlinAppResourceTest` run one backend; `frpcAppResourceTest` runs both serially and applies versioned policy `k7-resource-v2`. Resource tasks explicitly clear `OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE` so a private network-suite configuration inherited from the caller cannot enter the `core` evidence path. Every resource evidence task requires an explicit nonblank `ocdeck.frp.appE2e.deviceSerial`, so both backend runs are pinned to the same device without writing its serial into evidence. An evidence directory is also mandatory and must already exist. `ocdeck.frp.appResource.backendOrder` accepts only `gomobile,kotlin` or `kotlin,gomobile` and defaults to the former. The aggregate task requires both bounded summaries to identify the same clean candidate, device metadata, and workload before it succeeds:

```powershell
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcAppResourceTest `
  -PrequireGoMobileBridge=true `
  "-Pocdeck.frp.appE2e.deviceSerial=<serial>" `
  "-Pocdeck.frp.appResource.profile=baseline" `
  "-Pocdeck.frp.appResource.backendOrder=gomobile,kotlin" `
  "-Pocdeck.frp.appResource.evidenceDirectory=<existing-directory>"
```

```bash
./gradlew --no-daemon :frpc-stcp-visitor:frpcAppResourceTest -PrequireGoMobileBridge=true \
  "-Pocdeck.frp.appE2e.deviceSerial=<serial>" \
  "-Pocdeck.frp.appResource.profile=baseline" \
  "-Pocdeck.frp.appResource.backendOrder=gomobile,kotlin" \
  "-Pocdeck.frp.appResource.evidenceDirectory=<existing-directory>"
```

The allowlisted profiles are `baseline`, `stress`, `leak`, and `soak`. Optional bounded properties are `ocdeck.frp.appResource.iterations`, `concurrency`, `durationMillis`, `reconnectEveryIterations`, and `sampleEveryIterations`. Iterations are limited to 100,000, concurrency to 32, and soak duration to 60 seconds through 24 hours; the default soak is eight hours. A soak paces its bounded iteration budget across the requested duration so a fast device cannot exhaust an otherwise valid configuration early. Each workload performs bounded concurrent snapshots and optional explicit close/reconnect cycles while validating monotonic transport-identity replacement and global/project SSE recovery. Summary schema 10 embeds App result schema 9. Evidence contains wall and process-CPU time, PSS, Java/native heap, file-descriptor and thread counts, plus `head-tail-v1` retention metadata and a one-based ordinal on every scheduled sample. The Host derives the observed count from completed iterations and `sampleEveryIterations`, then requires the exact first 127 and latest 127 ordinals, retained-head, retained-tail, and dropped counts. Non-soak profiles must complete every configured iteration; soak must complete a positive bounded prefix, and every profile must report the exact reconnect count. The two backends must have identical workload results. After scenario cleanup, every non-functional workload remains idle for 90 seconds and records a quiescence snapshot; dynamic device and Host timeouts include this additional bounded delay. Output files are `gomobile-app-resource.json` and `kotlin-app-resource.json`, retain `authorizesKotlinDefault=false`, and use the same clean candidate/APK byte-identity and post-cleanup publication rules as acceptance summaries. A physical-ARM instrumentation regression test has also verified the producer retains the true first and last windows after 1,000 monotonic samples.

An existing pair can be reviewed without rebuilding or connecting a device:

```powershell
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcValidateAppResourceEvidence `
  "-Pocdeck.frp.appResource.evidenceDirectory=<existing-directory>"
```

`k7-resource-v2` accepts only summary schema 10 with App result schema 9 and treats `baseline` as a short structural/safety profile. Every passed non-functional result captures identities on-device as `(tid, /proc/self/task/<tid>/stat starttime)` at `start`, first successful `visitorReady` before snapshot/SSE/workload, `postCleanup`, and 90-second `quiescence`. Only a fixed 16-bucket presence histogram is emitted; no TID, thread name, hash, stack, wait channel, or command line leaves the device. The identity union is capped at 16,384, bucket zero is zero, and every checkpoint resource `threadCount` must equal the corresponding bit-derived total. Diagnostics recompute baseline survivors, warmup-created survivors, and post-warmup-created survivors; the offline validator prints those aggregate counts for both backends without changing the existing +8-thread policy or exempting GoMobile. For `stress`, `leak`, and `soak` pairs where either backend lasts at least 60 seconds, Kotlin CPU must be at most 1.50x GoMobile, wall duration at most 1.25x, and last-quarter mean PSS at most 1.25x plus 16 MiB. Last-quarter mean FD/thread counts must remain at most 256. For `leak` and `soak`, first-to-last-quarter growth is limited to 8 MiB or 15% PSS, 8 MiB or 25% per Java/native heap, four FDs, and eight threads. At quiescence, each backend must also be within four FDs and eight threads of its pre-workload start. The complete device matrix, repetition, environment-control, eight-hour/24-hour soak, observation, and rollback requirements are canonical in the paired [release checklist](../release/checklist.md). Any later paragraph referring to a "current" v1 policy records its historical evaluation context; v2 is the current contract.

The harness, Host validation, and both backend instrumentation variants are implemented and compile-tested. On one API 31 physical `arm64-v8a` device with 4 KiB pages, the working-tree smoke first passed GoMobile and Kotlin `core`, `lifecycle`, and `process`, plus same-device bounded `baseline`, `stress`, `leak`, and 60-second `soak` runs without acceptance summaries. Clean exact candidate `e429f360f38331875edb1483dc80a3e6062f5cc0` (tree `9df9e5def5c921b58813e68739444c81c5b91fc3`) then produced and retained paired GoMobile/Kotlin schema-8 JSON summaries on that same device for the default five-iteration `baseline`; 100-iteration, concurrency-eight `stress` with ten reconnects; 200-iteration `leak` with a reconnect every iteration; and a one-hour `soak` with concurrency four and reconnect/sample cadence 25. All eight summaries reported the exact clean candidate, local/installed target and test APK byte identity, the expected completed-iteration/reconnect/sample counts, successful cleanup, and `authorizesKotlinDefault=false`; the original structural cross-summary validator passed each pair. Applying the quantitative policy later passed `baseline`, but failed `stress` at 2.801x Kotlin/GoMobile CPU, `leak` at 2.309x CPU, and one-hour `soak` at 2.524x CPU. Those full-scenario CPU totals remain valid historical No-Go input, while the old retention algorithm did not preserve a true tail after more than 254 samples, so its long-soak thread/PSS trend is not final acceptance evidence. Current `k7-resource-v1` therefore rejects legacy schema 8 and requires a new exact schema-9 pair with true head/tail and quiescence evidence. Kotlin PSS remained lower and wall ratios remained within policy. The CPU results are an explicit K7 resource No-Go until a new exact candidate passes the policy on the required matrix; they must not be waived by selecting a wider threshold after seeing the result. Protected Release workflow [run 30320443107](https://github.com/ycfeng/ocdeck-android/actions/runs/30320443107) separately passed the formally signed, backend-verified three-ABI Kotlin-default dry run on `main` commit `7d52ec6b8a17b92ffa76dea913cc1b7f269cf5f5` and skipped publication. Signed packaging is therefore proven, but does not override the resource No-Go or authorize Kotlin-default assembly.

Clean exact candidate `88bd868316ace2ad9b569db8324ec5c2ed3e11c5` (tree `a3833a372ff8d1cd7743b7d5073c94a189fa2e36`) subsequently produced the first retained schema-9 `stress` and `leak` pairs on the same device. Both backends completed the exact configured workloads, matched local/installed APK bytes, completed cleanup, and retained `authorizesKotlinDefault=false`. `stress` failed at 2.987x Kotlin/GoMobile CPU while wall and PSS ratios remained within policy at 1.180x and 0.713x; `leak` failed at 2.289x CPU with wall 1.000x and PSS 0.711x. Kotlin's post-cleanup thread count returned within the eight-thread allowance in both runs, while GoMobile remained 17 threads above its pre-workload start after the 90-second quiescence period in both runs. The CPU results remain an explicit K7 resource No-Go, and the GoMobile residue is a separate resource-lifecycle failure that must be diagnosed rather than hidden by widening the policy.

Historical schema-9 summaries remain valid CPU No-Go inputs, including the ratios above, but do not satisfy `k7-resource-v2`. Passing current acceptance requires a new exact summary-schema-10 pair carrying App-schema-9 four-checkpoint lifecycle evidence and passing the policy.

Clean exact candidate `fe278cfa72fac09b4e32bc4f1ef3eb36ae9ce19c` (tree `b0cd3d44d2963e0c2de3f9297ca5a02db72d7760`) then produced schema-10 `stress` and `leak` pairs on the same API 31 physical ARM device. `stress` failed at 2.632x Kotlin/GoMobile CPU with wall 1.110x and PSS 0.722x; GoMobile quiescence survivors were `baseline=17`, `warmup=10`, and `postWarmup=17`, while Kotlin reported `15/4/6`. `leak` failed at 2.071x CPU with wall 0.992x and PSS 0.694x; GoMobile reported `17/12/12`, while Kotlin again reported `15/4/6`. Kotlin returned to its starting total thread count after `stress` and remained only one thread above start after `leak`. GoMobile remained 19 and 16 threads above start respectively. Its nonzero post-warmup cohorts prove that the residue is not exclusively a first-readiness runtime warmup, but the 200-reconnect `leak` run did not amplify that cohort beyond `stress`, so this evidence identifies workload-created resident/residue candidates rather than proving a per-connection leak. The Kotlin CPU ratios and the unchanged +8-thread policy keep K7 at No-Go; no long soak is justified yet.

With an emulator or device connected, run the instrumentation suite:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Full Bridge and CI-Equivalent Gate

Prepare patched frp and run the two Go race-test scopes:

```bash
cd frpc-stcp-visitor-go

go run ./cmd/preparefrp
python3 ../.github/scripts/check-go-race-environment.py
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
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testGoMobileReleaseUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleGoMobileRelease :app:assembleGoMobileReleaseAndroidTest :app:assembleKotlinRelease :app:assembleKotlinReleaseAndroidTest :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true
```

The Go race detector requires CGO and a supported C compiler. If that toolchain is unavailable on Windows, run both Go race scopes under WSL2, native Linux, or the GitHub Actions Ubuntu runner; ordinary Windows Go tests do not replace the race gate. WSL1 is not supported for this gate because its socket translation can allow duplicate loopback listener binds and invalidate the listener-ownership/bind-conflict assertions. The environment check fails WSL1 explicitly rather than weakening or skipping those tests. PowerShell also does not stop on a failing native process by default, so the sequence below uses a small fail-fast wrapper.

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
Invoke-NativeChecked { python ..\.github\scripts\check-go-race-environment.py }
Invoke-NativeChecked { go test -race '-modfile=build/frp-patched.mod' ./... }
Push-Location .\build\frp-v0.69.1-p1
Invoke-NativeChecked { go test -race ./client/... }
Pop-Location
Pop-Location

Invoke-NativeChecked { python .github/scripts/audit-community.py }
Invoke-NativeChecked { python .github/scripts/audit-third-party.py }
Invoke-NativeChecked { .\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcInteropTest }
Invoke-NativeChecked { .\.github\scripts\verify-bridge-reproducibility.ps1 }
Invoke-NativeChecked { .\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testGoMobileReleaseUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleGoMobileRelease :app:assembleGoMobileReleaseAndroidTest :app:assembleKotlinRelease :app:assembleKotlinReleaseAndroidTest :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true }
```

The reproducibility scripts require a clean checkout. On the current host platform, they build the current checkout plus a detached worktree at a different absolute path, isolate `GOCACHE`, `GOMODCACHE`, and `GOPATH` for each build, and compare the complete AAR, required sources JAR, POM, checksum, API, bridge/frp provenance, and native-sidecar set byte-for-byte. They remove the temporary checkout and caches but leave the primary outputs in the current checkout for the Gradle gate. This is not a Windows-versus-Linux byte-identity claim. CI and Release use the shell script; Windows developers may run the PowerShell counterpart.

The pinned Go, x/mobile, Android API, and NDK versions must come from `bridge-versions.properties`.

Changes to the Go wrapper, downstream frp patch, Android bridge module, either STCP backend, App backend selection, bridge API, failure handling, or version metadata require the fixed-frp interoperability task and complete bridge gate rather than only Android unit tests. The Android gate validates Debug/GoMobile selection and assembly, Canary/Kotlin, GoMobile Release-Like/GoMobile, and Kotlin Release-Like/Kotlin selection, compiles and packages both shared Release-Like AndroidTest variants, and verifies bridge-free runtime/APK packaging for both ordinary Kotlin variants. A Kotlin-only bridge API or failure-handling change may leave generated AAR bytes and `BRIDGE_VERSION` unchanged, but it does not waive any gate above. Increment `BRIDGE_VERSION` whenever native or generated AAR bytes change.

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
- Native load, app startup, and applicable interoperability on an official Android 16KB test image or a physical device reporting `16384` from `getconf PAGE_SIZE`.
- A real frps/STCP visitor path through `/global/health`, REST, and SSE.
- Direct and SSH smoke tests through the same path used by the app.
- First installation and startup for each physically available signed ABI-specific APK.
- Replacement update from the previous real public version with expected local-data retention; record the first public version as not applicable rather than claiming untested upgrade evidence.
- Rejection of incompatible signing and lower-`versionCode` replacement attempts.
- Destructive rollback and uninstall/reinstall behavior, including app-private local-data loss and the absence of a supported export/restore workflow.
- Re-download from the public Release and execution of both the complete `SHA256SUMS` check and the documented one-APK checksum procedure.

The target-ABI physical-device lane and the 16KB runtime lane may use different devices. A physical ARM 16KB device remains desirable combined coverage, but its absence does not block development when target-ABI physical loading, the official 16KB runtime lane, and static alignment/packaging gates all pass.

For the `0.1.0` candidate, maintainers recorded the native-loading, 16KB page-size, and real STCP checks above as passing. Exact device and deployment details are not published, so the evidence applies only to this candidate and does not establish universal Android or OpenCode Server coverage. Other unchecked manual items remain independent, and every future candidate must repeat the applicable gates. See the [compatibility matrix](../user/compatibility.md).
