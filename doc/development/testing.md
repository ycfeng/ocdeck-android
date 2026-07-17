# Testing

[简体中文](testing.zh-CN.md)

This English document is canonical. The Chinese document is a convenience translation.

## Test Layers

OC Deck uses several independent gates. Passing one layer does not imply that the others passed.

| Layer | Current coverage |
| --- | --- |
| Kotlin/JVM unit tests | Paths and project-file URLs, recent-project recording, session windows, notification/channel policy, redaction, encoded/decoded Retrofit/direct and identity-SSE inbound boundaries, typed failures and localized UI mapping, safe value summaries, DTO tolerance, Store revisions, prompt/project-context state and recovery, Provider auth/OAuth and staged custom-config transactions, server credential transactions, SSH/STCP coordination, contrast, and feature helpers. |
| Go race tests | The GoMobile wrapper and the generated patched frp client packages. |
| Third-party and legal audit | Pinned versions, dependency inventory, hashes, provenance, licenses, and release-script references. |
| Bridge validation | AAR checksum, Java API signature, bridge/frp provenance, expected ABIs, ELF machine, 16KB `PT_LOAD` alignment, stripped state, and reproducibility. |
| Android build | Unit tests for both Android modules and the Debug APK build. |
| Manual UI/accessibility validation | Compact screens, 200% font scale, IME overlap, project-file selection, Provider auth/OAuth/Custom Provider flows, TalkBack semantics/actions, both themes, and real model-settings navigation. |
| Release artifact validation | APK metadata, one signer, expected certificate fingerprint, ABI isolation, `zipalign -P 16`, AAR native-byte binding, embedded legal files, filenames, and checksums. |
| Physical-device validation | Maintainer-recorded `0.1.0` release-gate validation passed physical-device native loading/startup, 16KB page-size native operation, and a real STCP loop covering `/global/health`, representative REST, global/project SSE, and controlled reconnect. Exact environment details are not published; future candidates must repeat these gates. |

There is currently no `app/src/androidTest` suite and no emulator/instrumentation job in CI. Project selection, session navigation, Composer interactions, pickers, permission/question UI, large-text behavior, and TalkBack still need systematic device automation.

## Focused JVM Test Inventory

- `RetrofitInboundResponsePolicyTest` and `EncodedResponseLimitInterceptorTest` verify that every `OpenCodeApi` method declares `BOUNDED` or `EMPTY_SUCCESS`; missing policy fails before network proceed; encoded and decoded declared/unknown/understated lengths enforce their 16 MiB boundaries at `max + 1`; a real OkHttp gzip chain applies the encoded cap before Bridge decoding; non-2xx and successful Unit bodies close without reading; `/file/content` remains lazy; and requests without Retrofit `Invocation` pass outside the Retrofit interceptor.
- `SessionMessagesTransportTest`, `SessionMessagesResponseReaderTest`, and `FileContentResponseReaderTest` cover body-free non-2xx failures, direct OkHttp callback-thread decode, encoded and decoded exact/unknown/underreported lengths, EOF verification, cancellation and callback races, the separate 64 MiB session-message limits, and `/file/content` reader-level decoded defense in depth.
- `OpenCodeFailureTest`, `ErrorUiTextTest`, and `OpenCodeRepositoryFailureHandlingTest` cover semantic classification without reading `Throwable.message`, localized resource mapping with operation-specific fallback, Repository propagation, response-too-large behavior, and propagation of cancellation and JVM `Error`.
- `BoundedSseReaderTest`, `OkHttpSseEventSourceFactoryTest`, `OpenCodeEventClientLifecycleTest`, and `ProjectSnapshotCoordinatorTest` cover explicit identity encoding, non-identity zero-read rejection, all line endings and EOF states, 32 MiB line/event limits, body-free status/MIME failures, cancellation, retry classification, terminal close, owner/generation/source/transport races, project/global authority handoff, bounded replay, and snapshot failure/recovery.
- `FrpcStcpReadinessRetryClassifierTest` and `GoMobileFrpcStcpVisitorClientTest` cover transient versus permanent readiness failures, inbound-policy failures, typed unavailable/API-mismatch bridge errors, safe bridge summaries, API v2 JSON, revision/control epoch, `WaitVisitorReady`, and reflection cancellation/JVM `Error` propagation.
- `SensitiveValueToStringTest` verifies that network, domain, Store, feature, and UI value summaries omit synthetic credentials, URLs/endpoints, aliases, paths, prompts, Base64, SSE payloads, and tool output while preserving normal value-object behavior.
- `OpenCodeContrastTest` enforces light/dark 4.5:1 text contrast and 3:1 graphical contrast across theme text, semantic Diff/Markdown/syntax/chart colors, status indicators, attachment scrims, selection borders, and `ControlBorder`.
- `ProjectDrawerModelTest`, `ProjectInitialTest`, `ProjectPickerViewModelTest`, `RecentProjectRecorderTest`, and `RecentProjectStoreReducerTest` cover current-project insertion, MRU-order preservation, Windows path-alias deduplication, navigation that does not wait for DataStore, ordered retry behavior, local failure classification, project-home navigation decisions, and Unicode project initials.
- `SessionListWindowCoordinatorTest`, `InMemoryOpenCodeStoreSessionWindowTest`, and `OpenCodeRepositorySessionWindowTest` cover shared 20-item targets, 50-item request headroom, local expansion versus network loading, retry/end state, ordered prefix replacement, rapid-click merging, stale generation/transport rejection, tombstones, project/workspace isolation, and bounded metadata/parent backfill.
- `NotificationAlertPolicyTest`, `NotificationChannelMigrationPolicyTest`, `OpenCodeNotificationAudioAttributesTest`, and `SessionVisibilityRegistryTest` cover one sound owner per event, app/system setting independence, explicit channel sound/mute precedence, legacy-to-v2 migration decisions, notification audio usage, and foreground destination visibility.
- `SessionComposerAgentResolverTest`, `SessionModelPreferenceResolverTest`, and `SessionComposerRouteSelectionTest` cover server-ordered Build/Plan filtering and fallback, initial model/Variant validation and switching fallback, and new-session-only acceptance of lightweight project-home Composer route selections.
- `ComposerParameterPickerScrollTest` covers model lazy-list indexes across provider headers, Default-prefixed Variant indexes, missing selections, and measured item-to-viewport center offsets.
- `ProjectFilePathNormalizerTest` and `ProjectFileUrlBuilderTest` cover relative-path platform semantics, traversal/absolute rejection, POSIX/Windows-drive/UNC `file://` construction, UTF-8 percent encoding, round trips, and project-root containment.
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

Run focused tests during development by naming a test class, for example:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.ycfeng.ocdeck.core.security.RedactorTest"
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

Return to the repository root, audit community/documentation and third-party/legal metadata, build the AAR, and run the Android gate:

```bash
python3 .github/scripts/audit-community.py
python3 .github/scripts/audit-third-party.py
bash frpc-stcp-visitor-go/build-aar.sh
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug -PrequireGoMobileBridge=true
```

On Windows PowerShell, run the equivalent sequence from the repository root:

```powershell
Push-Location .\frpc-stcp-visitor-go
go run ./cmd/preparefrp
go test -race -modfile=build/frp-patched.mod ./...
Push-Location .\build\frp-v0.69.1-p1
go test -race ./client/...
Pop-Location
Pop-Location

python .github/scripts/audit-community.py
python .github/scripts/audit-third-party.py
.\frpc-stcp-visitor-go\build-aar.ps1
.\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug -PrequireGoMobileBridge=true
```

The pinned Go, x/mobile, Android API, and NDK versions must come from `bridge-versions.properties`.

The Release workflow builds the bridge twice and rejects non-reproducible output. Changes to the Go wrapper, downstream frp patch, Android bridge module, bridge API, failure handling, or version metadata require the complete bridge gate rather than only Android unit tests. A Kotlin-only bridge API or failure-handling change may leave generated AAR bytes and `BRIDGE_VERSION` unchanged, but it does not waive any gate above. Increment `BRIDGE_VERSION` whenever native or generated AAR bytes change.

## Security and Boundary Tests

- Use synthetic credentials and assert that thrown errors, aliases, `toString()` output, logs, and UI-safe messages do not reveal them.
- Assert that Repository, SSE, and snapshot failures retain semantic types, that UI copy comes from localized resources rather than exception strings, and that cancellation and JVM `Error` propagate.
- For Retrofit methods, verify the explicit inbound mode, separate encoded and decoded exact limits, `max + 1`, unknown and understated lengths, real gzip/Bridge ordering, body close behavior, non-2xx zero-read handling, successful Unit-body discard, and no-`Invocation` bypass.
- Cover the Direct cleartext-credential matrix: normalized remote/LAN `http://` plus a nonblank OpenCode username and new or retained password requires advisory confirmation; HTTPS, syntactic loopback, incomplete Basic credentials, SSH, and STCP do not. Test confirm, cancel, duplicate confirmation, frozen-request behavior, and classification without DNS.
- Cover Provider secret submission separately: a new API key or custom-header value over Direct non-loopback HTTP requires one frozen confirmation, while HTTPS, syntactic loopback, SSH, and STCP do not. Assert that API keys and header values never enter Android persistence, public UI state, raw response logs, or `toString()` output.
- Test Provider auth method parsing with unknown entries before supported entries so original wire indexes cannot be renumbered. OAuth tests retain directory/workspace and method index, reject unsafe browser URLs, cover code and cancellable auto callbacks, and never automatically retry stateful authorize/callback operations.
- Test Custom Provider's disabled-stage, optional credential write, final enable, disable-before-auth-cleanup, partial/unknown outcomes, and deep-merge deletion restrictions. Config projections retain only safe identities and header names.
- Test bounded readers at the exact limit and at `max + 1`; do not create giant committed fixture files.
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
| Project drawer with 20 recent projects | Rail scrolling, 75% project-list height cap, current-project selection, Open Project, Settings, project-home switching | Project buttons scroll without clipping; fixed actions remain reachable; one project is selected; switching closes the drawer and does not stack duplicate project homes |
| Project with more than 20 root sessions | Load More in project home and drawer, shared state, network loading, retry, end state, direct old-session route | Both surfaces show the same target; existing rows remain on failure; retry is reachable; end state is stable; an out-of-window session and bounded parent chain load without shrinking the window |
| Fresh install and legacy notification upgrade on API 26/29/30+/33+ | Default alert, app None, explicit system sound, explicit channel mute/block/low importance, permission denial, foreground/background current session | Each event has at most one sound owner; Android system choices take precedence; app playback does not bypass low-interruption settings; a background session is not incorrectly marked viewed |
| IME open | Fixed bottom Composer, suggestion panel, model/agent/variant popup, attachment strip, system Back | Insets keep the Composer visible; overlays are not obscured; Back dismisses the focused overlay before navigating away |
| TalkBack enabled | Project tabs and paths, clickable icon labels, Tab/RadioButton/Checkbox/Switch state, expanded/collapsed controls, unread/error/permission/connection cues, server ordering | Project tabs announce a unique full label and selected state; other roles and states are announced; important meaning is not color-only; server cards expose localized Move Up/Move Down custom actions |
| Light and dark themes | Body/supporting text, fields and borders, selected/error/status indicators, Diff/Markdown/code, attachment scrim | Text meets the 4.5:1 target and required non-text controls/indicators meet 3:1; state remains understandable in both themes |
| Composer model and Variant pickers | Reopen after selecting an off-screen item, search, provider grouping, current check, Connect Provider, Manage Models | Reopening scrolls the current selection into view and centers it when scroll bounds permit; search and manual scrolling remain under user control; rows and icon actions retain 48 dp targets; management actions close the picker and navigate to the real provider/model settings routes |
| Provider settings and authentication | Search and loaded/available groups; dynamic text/select prompts; API-key keyboard; OAuth browser, code, auto callback, and cancel; cleartext/loopback/disconnect confirmations | Secrets disappear when the surface closes; Back dismisses the focused sheet; controls remain reachable with IME and 200% text; loaded, disabled, pending, and error states have non-color cues; cancellation does not silently retry |
| Custom Provider create/edit | Multiple model/header rows, count/limit states, persisted identifier restrictions, password fields, save, disabled/unknown outcomes, disable and credential cleanup | Rows and actions remain reachable and at least 48 dp; full/overflow limits are explicit and addition re-enables after removing a new row; persisted values are never echoed; partial/unknown states are explicit; disabling does not claim physical deletion; both themes and TalkBack expose the same state |
| Project-home Composer preview | Agent, Model, and Variant controls; input and attachment entry; system Back; compact screen and 200% font scale | Parameter pickers open and update in place without navigation; Back closes the focused popup; provider/model actions use real settings routes; input and attachment entry open `"new"` with the current validated selection |
| Project-file picker | Tree and search, up to 10 selections, independent preview, confirm/cancel, preview-to-tree Back, route/session change, context chips, context-only send/reset | Selection has Checkbox semantics and non-color cues; preview does not toggle selection; cancel leaves the draft unchanged; stale routes receive no result; controls remain reachable with IME, 200% text, TalkBack, and both themes |

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
