# OC Deck GitHub Actions Release Process

[简体中文](github-actions.zh-CN.md)

This English document is the public canonical release-process document. The Chinese document is a complete convenience translation.

## 1. Goals and Boundaries

The repository uses three GitHub Actions workflows:

- `.github/workflows/ci.yml` runs for pushes to `main`, pull requests targeting `main`, and manual dispatch. It does not read release-signing material. A manual dispatch may explicitly enable the branch-local reusable K6V workflow; push and pull-request runs never enable that bootstrap automatically.
- `.github/workflows/frpc-kotlin-android-interop.yml` is an explicitly requested, exact-SHA K6V gate for the Android STCP backend. It supports direct manual dispatch after the workflow exists on the default branch and a read-only reusable call for first-introduction bootstrap. Its fixed x86_64 matrix runs API 26 with the one-profile `compat` suite and API 36 with the 12-profile `full` suite, executing the real GoMobile AAR before the pure Kotlin client and producing a bounded bilingual acceptance-report artifact without signing material.
- `.github/workflows/release.yml` runs for stable version tags or a manual dry run. It prepares release notes and runs fixed-frp interoperability without signing secrets, then uses the protected `release` Environment to build and verify signed artifacts. Manual dispatch may set `run_kotlin_default_release_dry_run=true` to build the formally identified and signed but non-publishable Kotlin profile. Stable tags always force the GoMobile profile, and only a tag-triggered GoMobile run can create a GitHub Release.

The first public artifact set is fixed to:

- `OCDeck_<version>_arm64-v8a.apk`
- `OCDeck_<version>_armeabi-v7a.apk`
- `OCDeck_<version>_x86_64.apk`
- `SHA256SUMS`

The workflow does not create a universal APK, AAB, or Play upload artifact. GitHub sideload users must download the APK matching their device ABI.

The internal `canary`, `kotlinRelease`, and `goMobileRelease` builds are verification variants only. Canary inherits Debug and selects pure Kotlin. The two Release-Like variants inherit Release configuration, use `.kotlinrelease`/`-kotlin-release` or `.gomobilerelease`/`-gomobile-release`, select Kotlin or GoMobile respectively, and use the Android Debug test certificate for symmetric device testing. CI and Release automation may build these variants, but never apply Release signing or stage or publish their APKs. `kotlinDefaultReleaseDryRun` is separate: it uses the formal application ID, stable version, Release certificate, and pure Kotlin backend, but remains a manual, short-lived, non-publishable artifact. Formal `release` remains GoMobile-default.

The application ID is `io.github.ycfeng.ocdeck`, version `0.2.3` has a minimum Android API level of 26, and OC Deck is an independent community client. Release notes must state that users provide their own reachable OpenCode Server and that pre-1.0 behavior and compatibility may change.

## 2. Version Rules

The only source of the application version is root `gradle.properties`:

```properties
VERSION_CODE=8
VERSION_NAME=0.2.3
```

Requirements:

- `VERSION_NAME` must be stable SemVer without leading zeroes, for example `1.2.3`. Prerelease and build metadata are not accepted.
- A formal release tag must exactly equal `v${VERSION_NAME}`, for example `v1.2.3`.
- `VERSION_CODE` must be an integer from `1` through `2100000000` and must exceed the value in the previous stable tag.
- CI does not rewrite versions. Update both properties in source before a release.
- A new tag must be the repository's highest stable version, must resolve to the checked-out commit, and that commit must be an ancestor of `origin/main`.

## 3. CI Gates

Normal CI pins Ubuntu 24.04, JDK 21, Go 1.26.4, Android SDK 36, Build Tools 36.0.0, and NDK 27.1.12297006. It includes these gates:

1. Audit community/documentation metadata, generate patched frp, and audit third-party/legal inventory, the union of dependencies for all four Android Go targets, resource hashes, and complete modified/added provenance.
2. In a dedicated read-only job, run `:frpc-stcp-visitor:frpcInteropTest`. It downloads only the host's repository-pinned and hash-verified official frp `v0.69.1` archive, safely extracts `frpc`/`frps`, and covers wire v1/v2, four payload modes, concurrent bidirectional larger-than-window traffic, REST plus live global/project SSE, typed negative cases, TLS, and restart/control-epoch recovery.
3. Run the outer Go race tests and separately run `client/...` race tests in the generated frp module.
4. Run the canonical shell reproducibility gate on the Ubuntu runner. It builds the clean candidate checkout and a detached checkout at a different absolute path with isolated `GOCACHE`, `GOMODCACHE`, and `GOPATH`; validates legal/API/provenance/native metadata and the fixed, local-path-free Go BuildInfo module graph for four ABIs; and compares the AAR, required sources JAR, POM, checksum, API, bridge/frp provenance, and native sidecar byte-for-byte.
5. Before generating the bridge, build and test Canary and Kotlin Release-Like and run `verifyPureKotlinPackaging` to prove a clean checkout needs no GoMobile AAR for either ordinary Kotlin variant. After generating the bridge, run the bridge AAR gate, App Debug, Canary, GoMobile Release-Like, and Kotlin Release-Like unit tests, `:frpc-stcp-visitor` unit tests, all four ordinary verification APK builds, both Release-Like AndroidTest APK assemblies, and the same packaging gate again. Compiling both AndroidTest variants keeps the shared Release-Like source and test manifest inside the automatic gate. The signed Kotlin-default dry run is not part of ordinary CI; its workflow path runs `verifyKotlinDefaultReleaseDryRunPackaging` separately after protected signing inputs are available.

The Debug factory test confirms the GoMobile default, while the Canary and Kotlin Release-Like factory tests confirm pure Kotlin selection. Both Kotlin variants resolve matching bridge-free library variants. The packaging gate rejects the GoMobile bridge on either runtime classpath and rejects `libgojni.so` in every Kotlin verification APK, including after the generated AAR is available.

The K6V workflow requires its lowercase 40-character `candidate_sha`, `github.sha`, `github.workflow_sha`, and checked-out `HEAD` to identify the same commit. Once the workflow exists on the default branch, maintainers may dispatch it directly. During first introduction, manually dispatch `CI` with `run_frpc_android_interop=true` and an optional exact `candidate_sha`; an empty value uses the dispatched ref SHA and calls the same branch-local reusable workflow. Ordinary push and pull-request CI never invokes this job. Each emulator lane builds the real GoMobile bridge first, then runs `:frpc-stcp-visitor:frpcAndroidInteropTest` in a fresh Gradle invocation with an explicit suite and a new summary destination. The matrix is fixed: API 26 x86_64 uses `compat` with only `success-v1-00`; API 36 x86_64 uses `full` with eight wire v1/v2 x encryption off/on x compression off/on success profiles, wrong token, wrong STCP secret, bind conflict, and `restart-v2-11`.

For every profile, GoMobile and Kotlin execute in separate instrumentation processes in that order. GoMobile must complete `stopVisitor`/`stopSession`, report `Closed`, and release the listener before Kotlin reuses the same bind port. Success profiles keep global/project SSE live while health, two echo requests, and two larger-than-Yamux-window downloads run concurrently, and both streams must read the same checkpoint. The restart profile verifies existing-SSE disconnection and control unavailability, waits for both restarted `frps` readiness and provider reconnection, requires a strictly greater control epoch, and reruns REST, SSE, and larger-than-window traffic after recovery.

Preflight runs the report-renderer unit tests. The renderer fails closed unless both lane evidence files contain strict Host summaries with the exact suite, device metadata, ordered profiles, scenario/wire/encryption/compression parameters, `gomobile,kotlin` order, exact checks, and equivalence. A successful Gradle task cannot substitute for profile evidence. Report status is bound to this complete contract; every report sets `authorizesKotlinDefault=false`, and each lane records the actual Android test APK and GoMobile bridge AAR SHA-256 values. The resulting artifact contains English and Chinese reports, normalized evidence JSON, and `SHA256SUMS`, but no credentials, endpoints, private paths, raw process logs, or test configuration.

Neither ordinary CI nor the K6V workflow possesses a release JKS, passwords, or repository write permission. Debug, Canary, Kotlin Release-Like, and GoMobile Release-Like APKs remain verification outputs only; the Release-Like comparison variants use the Android Debug test certificate. The host interop job and the API 36 K6V `full` lane independently cover the complete wire/payload, negative, and restart matrix at different execution boundaries. The fixed x86_64 emulator evidence does not replace the split target-ABI physical-device and 16KB runtime lanes, the App's real Store/snapshot/reconciliation path, Doze or network-transition checks, foreground/background behavior, performance, resource-leak and long-duration soak evidence, a formal stable release cycle, or release-device STCP validation. The two device lanes may use different devices: physical ARM covers target-ABI installation/class loading and real App/STCP behavior, while an official Android 16KB test image or physical device covers runtime page size, startup, and applicable interoperability with static alignment/packaging gates. Missing physical ARM 16KB hardware is a disclosed residual risk, not a K7 development blocker. A passing K6V artifact or signed Kotlin dry run is evidence for evaluating K7; neither authorizes Kotlin-default assembly.

## 4. GitHub Repository Configuration

### 4.1 Release Environment

Create a GitHub Environment named `release` and configure it as follows:

- Allow deployment only from `main` and protected `v*` tags. `main` is required for a manually dispatched signed dry run.
- Configure required reviewers.
- Where the GitHub plan supports it, prevent the initiator from approving their own deployment.

Environment secrets:

| Name | Content |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64 content of the release JKS |
| `RELEASE_STORE_PASSWORD` | JKS password |
| `RELEASE_KEY_ALIAS` | Release key alias |
| `RELEASE_KEY_PASSWORD` | Release key password |

Environment variable:

| Name | Content |
| --- | --- |
| `RELEASE_CERT_SHA256` | SHA-256 fingerprint of the release certificate, with or without colons |

Base64 is encoding, not encryption. Keep the JKS in controlled offline backups. Never commit it, cache it, or upload it as a workflow artifact. The workflow decodes it only to `$RUNNER_TEMP/release.jks` and removes it in an `always()` cleanup step.

Use an interactive command to inspect the certificate fingerprint without putting a password on the command line:

```bash
keytool -list -v -keystore /secure/path/release.jks -alias your-alias
```

### 4.2 Repository Protection and Permissions

Recommended repository settings:

- Set `main` as the default branch and require pull requests and passing CI through branch protection or a ruleset.
- Use a tag ruleset to restrict creation, update, and deletion of `v*` tags.
- Keep the workflow default `GITHUB_TOKEN` permission at `contents: read`.
- Keep both the direct K6V entry and the CI reusable bootstrap at `contents: read`, outside every Environment, and without repository or Environment secrets. The CI bootstrap must remain explicit manual opt-in and must never run on push or pull-request events. Require an exact candidate SHA and retain its report artifact as candidate-specific evidence; do not treat it as an ordinary required check while it remains manually requested and path-independent.
- Grant `prepare-notes` only `contents: write`, because GitHub's generate-notes REST endpoint requires that permission. This job does not enter the `release` Environment, receives no signing secrets, and does not create or modify a release or ref.
- Keep `frpc-interop` at `contents: read` and outside the `release` Environment. It may download only the hash-pinned official frp test asset and must finish before the signing job starts.
- Grant `publish` only `contents: write`. This job receives no signing secrets.
- Keep `build-release` at `contents: read`. It receives signing inputs only after `release` Environment approval and cannot write repository contents.
- Enable immutable releases so tags and assets cannot be replaced after publication.
- Allow only trusted actions. Repository actions are pinned to full commit SHAs and should be updated through reviewed dependency changes.

The automatically provided `GITHUB_TOKEN` used by `prepare-notes` is not a repository or Environment secret. It is scoped to the workflow run. No personal access token is required.

## 5. Signing and Google Play Continuity

If GitHub APKs and future Google Play packages must update each other, both channels must use the same app-signing certificate:

- GitHub Release APKs always use the current release app-signing key.
- When enabling Play App Signing for the first time, provide the existing app-signing key. Do not let Google generate a different app-signing key for the same application ID.
- If a separate Play publication flow is added later, its upload key must not be used as the GitHub APK signing key.
- Android will not allow an update across channels when certificates differ, even if the application ID is identical.

Losing the app-signing key generally prevents further updates to directly distributed APKs. Keep at least two controlled offline backups and record the certificate fingerprint and expiry separately from the secrets.

## 6. Release Notes Sources

Release notes are assembled once by the `prepare-notes` job in this order:

1. `.github/release-notes-preamble.md`, with `{{REPOSITORY_URL}}` and `{{RELEASE_TAG}}` replaced.
2. `release-notes/v${VERSION_NAME}.md`, the curated bilingual notes for that version.
3. GitHub-generated notes for a real tag-triggered publication.

The preamble uses tag-pinned links for versioned documentation and legal/support context, except that the security policy may point to the latest reporting policy. The curated file is a single bilingual file; do not create separate English and Chinese release-note files.

For `workflow_dispatch`, the release tag may not exist. The job still builds an auditable `release-notes.md`, uploads it as an artifact, and explicitly states that the automatic change list is appended only during real tag publication. It must not call generate-notes for a nonexistent tag.

For a tag push, `prepare-notes` calls GitHub generate-notes for the existing validated tag and appends the returned body directly. It does not add another `## Changes` heading, avoiding duplicate change headings. The assembled file is rejected if it contains `TODO`, `TBD`, or unresolved `{{...}}` placeholders.

The `publish` job downloads the release-notes artifact from the same workflow run and passes that exact file to `gh release create`. It does not check out the repository and does not regenerate or edit the notes.

## 7. Release Steps

1. Update `VERSION_NAME` and the incremented `VERSION_CODE` in `gradle.properties`.
2. Update the changelog and `release-notes/v${VERSION_NAME}.md`. Synchronize README, privacy, security, support, trademark, `THIRD_PARTY_NOTICES.txt`, and `third_party/` when applicable. Recheck dependencies, sound assets, the Gradle wrapper, frp patches, and local SHA-256 values.
3. Complete the [release checklist](checklist.md), including target-ABI physical-device native load, 16KB runtime validation on an official Android test image or physical device, and real STCP validation. These dimensions may use different devices; a missing required dimension still blocks a formal release.
4. Merge to `main` and confirm CI passes.
5. Optionally run the `Release` workflow manually from `main`. Leave `run_kotlin_default_release_dry_run=false` for the normal signed GoMobile dry run, or set it to `true` for the formally identified and signed Kotlin-default packaging dry run. Either path prepares reviewable notes, rebuilds, signs, verifies, and retains artifacts for three days without creating a GitHub Release. The Kotlin path is explicitly non-publishable.
   - Manual dry-run version validation does not require `main` HEAD to equal the current stable tag. When `v<VERSION_NAME>` already exists, that tag must be the highest stable tag, must be an ancestor of the checked-out `main` commit, and its `VERSION_NAME` and `VERSION_CODE` must exactly match the current files. Tag-triggered publication remains stricter and still requires the tag to resolve to the exact checked-out commit.
   - Protected [run 30320443107](https://github.com/ycfeng/ocdeck-android/actions/runs/30320443107) validated this Kotlin path on `main` commit `7d52ec6b8a17b92ffa76dea913cc1b7f269cf5f5`: all pre-signing gates, formal signing, three-ABI backend-aware artifact verification, upload, and temporary-keystore removal passed, while `publish` was skipped as designed. This is packaging evidence only and does not authorize a backend-default change.
6. Create and push `vMAJOR.MINOR.PATCH` on the already validated commit.
7. `preflight` validates source versions, the tag, the `origin/main` ancestry relationship, the highest-version rule, and historical `VERSION_CODE` monotonicity.
8. `prepare-notes` builds and uploads the final `release-notes.md` without signing secrets. For a real tag, it appends GitHub-generated notes.
9. `frpc-interop` runs the fixed official-frp host matrix in a read-only job with no release Environment or signing material.
10. After both `preflight` and `frpc-interop` pass and Environment approval is granted, `build-release` reruns all local tests, including Debug/GoMobile, Canary/Kotlin, GoMobile Release-Like/GoMobile, and Kotlin Release-Like/Kotlin App verification, and assembles both shared Release-Like AndroidTest APKs. The gate is divided into separate no-daemon, non-parallel Gradle invocations with a bounded worker count: ordinary tests plus Debug/Canary first, then GoMobile Release-Like packaging, then Kotlin Release-Like packaging. This preserves the complete gate while preventing concurrent Kotlin compilation, ART-profile, dex, and packaging work for multiple variants from exhausting the runner heap. The job uses the shell reproducibility gate to build the clean candidate checkout plus a detached checkout at a different absolute path on the same Ubuntu runner with isolated Go caches. It requires the complete bridge artifact/sidecar set to match byte-for-byte, then builds the allowlisted profile: `assembleRelease` for `gomobile-release`, or `assembleKotlinDefaultReleaseDryRun` plus its bridge-free packaging gate for `kotlin-default-release-dry-run`. Both use the formal identity, version, signer, and certificate check.
11. `publish` runs only for a stable tag whose profile is `gomobile-release`. It downloads both verified artifact sets and uses `GITHUB_TOKEN` with `gh release create --verify-tag --notes-file`. It creates a prerelease for every `0.x` version and publishes a normal release beginning with `1.0.0`. The Kotlin dry-run artifact can never enter this job.

## 8. Artifact Checks

The release scripts accept only the profiles `gomobile-release` and `kotlin-default-release-dry-run`. Debug, Canary, and both suffixed Release-Like outputs are never copied into the signed artifact set. GoMobile reads `release`; Kotlin dry-run reads `kotlinDefaultReleaseDryRun`. Common checks verify:

- APK metadata application ID `io.github.ycfeng.ocdeck`, the profile's exact variant, stable `versionName`, `versionCode`, target ABI, and filename all agree; suffixed verification identities and versions are rejected.
- Each APK has exactly one signer, and the certificate fingerprint equals `RELEASE_CERT_SHA256`.
- Each APK passes `apksigner` and 16KB `zipalign`, contains native libraries only for its target ABI, and has every native library checked with a bounded 256 MiB per-library reader for ELF machine, all-`PT_LOAD` 16KB alignment, and stripped state.
- The GoMobile profile requires the generated bridge definition class and exactly one target-ABI `libgojni.so` whose SHA-256 matches the AAR native sidecar. The Kotlin profile requires that bridge definition class and every `libgojni.so` be absent; other native libraries remain fully checked.
- `assets/legal/` in each APK is byte-identical to the current LICENSE, NOTICE, third-party notices, trademark statement, merged license text, and every individual license.
- `META-INF/OCDECK/` in the AAR contains the current legal texts, licenses, exact Java API, and bridge/frp provenance, including the schema-2 native module-graph digest and local-path-free proof.
- External checksum/API/provenance/native sidecars are bound to the embedded content and exact AAR bytes; schema-2 native metadata also proves fixed BuildInfo module identities, versions, and sums and one graph digest across all four ABIs.
- `SHA256SUMS` covers only the three signed APKs in the selected profile. Only a tag-triggered GoMobile profile may publish them.

The bridge is statically produced for four GoMobile ABIs, while the public application release intentionally contains only `arm64-v8a`, `armeabi-v7a`, and `x86_64` APKs.

## 9. Failure Handling

- Version validation fails: compare the tag, `VERSION_NAME`, `VERSION_CODE`, stable tag ordering, checked-out commit, and `origin/main` ancestry.
- Release-notes preparation fails: add the required `release-notes/v${VERSION_NAME}.md`, remove prohibited placeholders, verify the preamble substitutions, and confirm the real tag exists before generate-notes is called.
- GoMobile build fails: confirm the runner has the exact Go and NDK versions, then inspect checksum, API signature, provenance, native metadata, or reproducibility failures.
- Fixed-frp interoperability fails: inspect the typed scenario and redacted bounded log tail, verify the pinned asset inventory, and reproduce with `:frpc-stcp-visitor:frpcInteropTest`; do not move the task into the signing job or weaken its hash/process/input limits.
- Android K6V interoperability fails: verify exact-SHA identity, the pinned API 26 `compat`/API 36 `full` x86_64 system images, bridge generation, emulator API/ABI, renderer tests, and the structured lane/Host-summary result. Prefer reproducing the full lane with `:frpc-stcp-visitor:frpcAndroidInteropTest -PrequireGoMobileBridge=true -Pocdeck.frp.androidInterop.deviceSerial=<serial> -Pocdeck.frp.androidInterop.suite=full -Pocdeck.frp.androidInterop.summaryFile=<new-path>`, where the summary destination does not already exist; use `compat` only for the API 26 compatibility lane. Do not pass credentials through Gradle or instrumentation arguments, relax private-file transport or ownership-aware cleanup, treat Gradle task success as profile evidence, or accept a partial/missing lane as a passing report.
- Signing fails: check the four Environment secrets, complete Base64 JKS content, alias, and passwords without printing any secret.
- Certificate fingerprint fails: stop the release and verify that the Environment JKS is the established app-signing key. Do not change the expected fingerprint merely to bypass continuity protection.
- APK native-byte binding fails after the AAR gate passes: confirm the App packaging exclusion for the already-stripped `libgojni.so`; do not accept transformed bytes, replace the AAR hash, or weaken the APK checks.
- A required target-ABI physical-device, 16KB runtime, or real STCP gate fails or is incomplete: do not publish. The lanes may use different devices; fix and repeat the affected gate or defer the release.
- The GitHub Release already exists: do not overwrite it or move the tag. Fix the issue, increment the version, and create a new tag.
