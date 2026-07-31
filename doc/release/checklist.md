# Release Checklist

[简体中文](checklist.zh-CN.md)

This English checklist is canonical. The Chinese document is a complete convenience translation. A checked box is evidence for one candidate only; retain the relevant workflow run, device record, and review context.

## Candidate Scope

- [ ] `VERSION_NAME` is stable SemVer and `VERSION_CODE` is incremented in `gradle.properties`.
- [ ] `release-notes/v${VERSION_NAME}.md` exists as one bilingual file and follows the required template sections.
- [ ] `CHANGELOG.md` records notable changes, migrations, deprecations, removals, and security changes.
- [ ] Implemented, partial, placeholder, and unimplemented features are described accurately.
- [ ] Compatibility claims use the documented statuses and do not promote observations to support claims.
- [ ] Every normal pre-1.0 deprecation was announced at least one public release before removal, or an urgent security/legal exception is documented.

## Documentation and Legal

- [ ] English canonical documents and complete `.zh-CN.md` convenience translations are synchronized and mutually linked.
- [ ] README, privacy, security, support, trademark, notices, and third-party inventory match the actual candidate where applicable.
- [ ] Getting-started, installation, connection, troubleshooting, and compatibility guides match the candidate and their documented commands were reviewed on the stated platforms.
- [ ] Download guidance distinguishes workflow-uploaded APKs and `SHA256SUMS` from GitHub's automatic source archives, and integrity wording does not overstate publisher-identity verification.
- [ ] Dependencies, sound assets, Gradle wrapper, frp upstream/patches, provenance, source archives, licenses, and hashes were reviewed.
- [ ] Original documentation remains under the repository MIT license; third-party material retains its own attribution and license.
- [ ] Final release notes contain no `TODO`, `TBD`, unresolved `{{...}}`, secrets, private data, test certificate details, or unreviewed screenshots.

## Automated Gates

- [ ] The `main` CI run passed on the exact candidate commit.
- [ ] `go run ./cmd/preparefrp` completed with pinned inputs.
- [ ] Outer wrapper and generated patched frp `client/...` race tests passed.
- [ ] Community/documentation and third-party/legal inventory audits passed.
- [ ] `:frpc-stcp-visitor:frpcInteropTest` passed on the exact candidate with the pinned official frp asset, including wire v1/v2, all four payload modes, concurrent bidirectional larger-than-window traffic, live global/project SSE, negative cases, and restart/control-epoch recovery.
- [ ] On one host platform, the bridge reproducibility gate built the clean candidate checkout and a detached checkout at a different absolute path with isolated `GOCACHE`, `GOMODCACHE`, and `GOPATH`; the AAR, required sources JAR, POM, checksum, API, bridge/frp provenance, and native sidecar matched byte-for-byte.
- [ ] AAR checksum, exact Java API, bridge/frp provenance, four-ABI BuildInfo module identities/versions/sums, local-path-free and cross-ABI graph-digest proof, expected ABIs, ELF machine, 16KB `PT_LOAD` alignment, stripped state, embedded legal files, and sidecars passed.
- [ ] `:app:testDebugUnitTest`, `:app:testCanaryUnitTest`, `:app:testGoMobileReleaseUnitTest`, `:app:testKotlinReleaseUnitTest`, `:frpc-stcp-visitor:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleCanary`, `:app:assembleGoMobileRelease`, `:app:assembleGoMobileReleaseAndroidTest`, `:app:assembleKotlinRelease`, `:app:assembleKotlinReleaseAndroidTest`, `:app:verifyPureKotlinPackaging`, and the required bridge gate passed; variant factory tests confirmed Debug/GoMobile, Canary/Kotlin, GoMobile Release-Like/GoMobile, and Kotlin Release-Like/Kotlin selection, both shared Release-Like AndroidTest APKs compiled, and the packaging gate confirmed both Kotlin runtime classpaths and all ABI APKs exclude the GoMobile bridge and `libgojni.so`.
- [ ] If this candidate is being evaluated for K7/Kotlin-default assembly, `K6V Android STCP Interop` ran against its exact full commit SHA, either by direct manual dispatch or by the explicitly enabled manual CI reusable bootstrap during first introduction. The API 26 x86_64 `compat` lane passed exactly `success-v1-00`; the API 36 x86_64 `full` lane passed all eight wire/encryption/compression success profiles, wrong token, wrong STCP secret, bind conflict, and `restart-v2-11`. Renderer preflight tests passed, and the fail-closed bilingual report plus normalized evidence completely validated suite/profile identity, parameters, `gomobile,kotlin` order, exact checks, equivalence, and `authorizesKotlinDefault=false` rather than relying on Gradle task success. Otherwise this item is explicitly recorded as not applicable.
- [ ] The K6V report limitations were reviewed: emulator evidence does not replace the split target-ABI physical-device and 16KB runtime lanes, the App's real Store/snapshot/reconciliation path, Doze/network and foreground/background transitions, performance, resource-leak, long-duration soak, formal stable-release-cycle, or release-device STCP evidence required before a Kotlin-default decision. The two device lanes may use different devices; unavailable physical ARM 16KB hardware is recorded as residual risk rather than a K7 development blocker.
- [ ] Canary and Kotlin Release-Like APKs remained bridge-free verification outputs only, and GoMobile Release-Like remained a suffixed Debug-certificate comparison output. None was staged or published.
- [ ] If this candidate is being evaluated for K7/Kotlin-default assembly, the exact-candidate App E2E artifact bound a clean lowercase commit/tree, local and installed target/test APK SHA-256 identity, successful cleanup, and `authorizesKotlinDefault=false`. External evidence contained exactly 17 ordered instrumentation checks plus Host assertions `configured_pinned_public_server` and `network_restored`. Otherwise this item is explicitly recorded as not applicable.
- [ ] If this candidate is being evaluated for K7/Kotlin-default assembly, symmetric GoMobile/Kotlin `baseline`, reconnect/concurrency `stress`, `leak`, and long-duration `soak` profiles ran on the approved device matrix and passed `k7-resource-v3` with summary schema 10 bounded evidence generated after Host validation of App result schema 12. Historical schema-9 summaries remain diagnostic inputs but do not satisfy the v3 lifecycle contract. Otherwise this item is explicitly recorded as not applicable.
- [ ] Signed artifact verification passed for the selected allowlisted profile. Common checks covered output metadata plus the actual APK manifest application ID/version, one signer, expected certificate fingerprint, ABI isolation, `apksigner`, `zipalign -P 16`, all native libraries, legal assets, filenames, and `SHA256SUMS`; GoMobile additionally required the bridge class and sidecar-bound `libgojni.so`, while Kotlin-default rejected both.

## K7 Resource Acceptance

`k7-resource-v3` is the current versioned K7 resource policy. `:frpc-stcp-visitor:frpcValidateAppResourceEvidence` applies it to an already generated pair, while `frpcAppResourceTest` generates the pair and then applies the same policy. It accepts only summary schema 10 generated by the Host after validating App result schema 12; the App schema version is a generation prerequisite and is not repeated as a summary field. Every passed non-functional result must include a bounded `proc-task-lifecycle-v1` 16-bucket presence histogram for `start`, first `visitorReady`, `postCleanup`, and 90-second `quiescence`; checkpoint thread totals must exactly match the histogram. Evidence contains no thread ID, name, hash, stack, wait channel, command line, or other thread identity. Historical schema-9 summaries remain diagnostic inputs but fail closed for v3 acceptance. A policy pass remains evidence only and never sets `authorizesKotlinDefault=true`.

- [ ] The primary API 31 physical performance device retained one complete `baseline` pair and three complete pairs each for `stress` and `leak`, without selecting only favorable runs. Backend order for the repeated profiles was `gomobile,kotlin`, `kotlin,gomobile`, `gomobile,kotlin` through `ocdeck.frp.appResource.backendOrder`. The newest physical ARM Android API available to maintainers retained one confirming pair each for `baseline`, `stress`, and `leak`. Charging state, thermal status, power mode, radios, background workload, and test-server placement were recorded and kept comparable. A run affected by thermal throttling or unrelated load was rejected with its reason before results were reviewed, then rerun in full.
- [ ] The approved matrix used the established API 31, 4 KiB physical `arm64-v8a` device as the primary performance device and a physical `arm64-v8a` device on the newest Android API available to maintainers as the confirming lane. The official API 36 16KB x86_64 lane covered page-size/runtime safety but did not substitute emulator CPU numbers for physical-device performance. Published ABI loading and external-network coverage remained subject to their separate gates and disclosed residual risks.
- [ ] For `stress`, `leak`, and `soak` pairs where either backend lasted at least 60 seconds, Kotlin process CPU at or below 1.75x GoMobile passed normally. A ratio above 1.75x and at or below the 2.00x hard limit passed only when wall duration was at most 1.05x and pure last-quarter mean PSS was at most 1.00x GoMobile. Independently, wall duration remained capped at 1.25x and last-quarter mean PSS at 1.25x GoMobile plus 16 MiB. `baseline` remained a short structural/safety profile and was not used alone for comparative authorization.
- [ ] Each evidence file used `head-tail-v1` retention with one-based scheduled-sample ordinals proving up to the exact first 127 and latest 127 samples, with both windows containing exactly 127 when enough samples were observed, plus matching observed/head/tail/dropped metadata. Non-soak runs completed every configured iteration; soak runs completed a positive bounded prefix; reconnect counts were exact; and paired backend workload results matched. For both backends, last-quarter mean FD and thread counts were at most 256. For `leak` and `soak`, last-quarter minus first-quarter mean growth was at most 8 MiB or 15% for PSS, at most 8 MiB or 25% for each Java/native heap, at most four FDs, and at most eight threads. Every non-functional workload also recorded a snapshot after 90 seconds of post-cleanup quiescence. Relative to pre-workload start, each backend added at most four FDs; Kotlin added at most eight threads; GoMobile added at most sixteen threads, with deltas above eight retained as a required warning for review. Every retained pair passed; warnings were reviewed, and medians or averages were not used to hide a failing run.
- [ ] The primary API 31 performance device completed one eight-hour paired soak. The harness may still run diagnostics for up to 24 hours, but `k7-resource-v3` does not require a 24-hour acceptance run or an additional soak on the confirming lane.
- [ ] Before K7 authorization, the Kotlin candidate completed at least seven consecutive days of internal/canary observation without a resource, connectivity, security, or data-consistency rollback trigger. K10 still requires a full stable public release cycle after Kotlin becomes the formal default.
- [ ] A GoMobile emergency build with a higher `versionCode` was installed over the Kotlin candidate and preserved DataStore, Keystore-backed credential references, server configuration, and update identity. Maintainers recorded rollback triggers, owner, response target, and the build/sign/publish procedure; rollback does not rely on Android downgrade installation.

## Device and Connection Gates

The target-ABI physical-device lane and the 16KB runtime lane may use different devices. An official Android 16KB test image or physical device is accepted for runtime page-size, startup, and applicable interoperability evidence when combined with target-ABI physical loading and static alignment/packaging gates. A physical ARM 16KB device remains desirable combined coverage, not a prerequisite for K7 development.

- [ ] Each physically available target ABI completed a first install of its signed ABI-specific APK and launched successfully.
- [ ] A compatible replacement update from the previous real public version preserved expected local data; for the first public version, this item is explicitly recorded as not applicable rather than inferred.
- [ ] An incompatible signer and a lower `versionCode` were rejected as expected, and the documented destructive rollback path was reviewed against the candidate.
- [ ] Uninstall removed app-private local data as documented, reinstall behaved as a new local installation, and no unsupported export/restore claim was made.
- [x] Native loading and app startup were tested on available physical `arm64-v8a` targets.
- [ ] Native loading was tested for other published ABIs on physical hardware where a release claim depends on them; any untested ABI limitation is disclosed.
- [x] Native loading and startup passed in an Android runtime using a 16KB page size, on an official Android 16KB test image or physical device; the recorded `0.1.0` evidence used physical hardware.
- [x] A real frps/STCP visitor path reached listener readiness and completed `/global/health` through the tunnel.
- [x] The same real STCP path exercised representative REST and both global/project SSE behavior, including reconnect or control-epoch recovery where practical.
- [ ] Direct HTTP(S) and SSH paths received smoke testing through the app's actual connection path.
- [x] Existing UI data remained available during a controlled disconnect/reconnect smoke test.

Maintainers recorded passing external evidence for the checked `0.1.0` native-loading, 16KB page-size, and real STCP gates above. Exact device and deployment details are not published. This candidate-specific record does not satisfy other unchecked items or waive the same gates for future candidates.

## Signing and Repository Controls

- [ ] The protected `release` Environment has required reviewers and only the four signing secrets plus `RELEASE_CERT_SHA256`.
- [ ] The release JKS has controlled offline backups and was never committed, cached, logged, or uploaded as an artifact.
- [ ] The expected certificate is the established app-signing certificate intended to preserve GitHub/Google Play update continuity.
- [ ] Branch protection, `v*` tag protection, trusted pinned actions, default read permissions, and immutable releases are enabled.
- [ ] The tag commit is the exact candidate commit and an ancestor of `origin/main`.

## Dry Run

- [ ] `workflow_dispatch` was run from `main` when a signed dry run was required, with `run_kotlin_default_release_dry_run` explicitly reviewed.
- [ ] The normal GoMobile dry run used `false`, or the K7 packaging evaluation used `true` and produced only the non-publishable Kotlin-default artifact profile.
- [ ] The dry run uploaded the selected signed artifacts and final reviewable `release-notes.md` artifact for three-day retention.
- [ ] A Kotlin-default dry run, when selected, used the formal application ID/version/certificate, passed `verifyKotlinDefaultReleaseDryRunPackaging`, remained `authorizesKotlinDefault=false`, and was not treated as stable-cycle or backend-switch authorization.
- [ ] The dry-run notes explicitly state that GitHub's automatic change list is appended only during a real tag publication.
- [ ] Maintainers reviewed the exact APK names, checksums, curated notes, compatibility statements, known issues, and legal/security links.
- [ ] No GitHub Release was created by the dry run.

## Tag Publication

- [ ] Create and push exactly `v${VERSION_NAME}` only after all blocking gates pass.
- [ ] `preflight`, `prepare-notes`, `frpc-interop`, and `build-release` succeeded for the tag-triggered run; the interop job remained outside the protected signing Environment.
- [ ] The prepared notes artifact contains the bilingual preamble, curated version file, and GitHub-generated notes in that order without a duplicate `## Changes` heading.
- [ ] `publish` downloaded the prepared notes and verified assets instead of regenerating either.
- [ ] A `0.x` version was marked prerelease; `1.0.0` and later follow the normal release rule.
- [ ] The workflow-uploaded GitHub Release assets are exactly three ABI-specific APKs and `SHA256SUMS`, with no uploaded universal APK, AAB, JKS, or signing material; automatic GitHub source archives are not described as installable assets.

## Post-Publication

- [ ] Download every workflow-uploaded public asset from GitHub and independently verify `SHA256SUMS`; also run the documented one-APK checksum procedure.
- [ ] Install a freshly downloaded public APK on a matching device and confirm the public installation and first-use links resolve.
- [ ] Confirm tag-pinned documentation, privacy, support, license, notice, and trademark links resolve.
- [ ] Confirm the latest security-policy link resolves to the intended private reporting instructions.
- [ ] Confirm the release is immutable and no asset replacement or tag movement is needed.
- [ ] If a publication defect is found, do not overwrite the release or move the tag; prepare a new version.
