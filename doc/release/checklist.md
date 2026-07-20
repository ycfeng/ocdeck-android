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
- [ ] `:app:testDebugUnitTest`, `:app:testCanaryUnitTest`, `:frpc-stcp-visitor:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleCanary`, and the required bridge gate passed; variant factory tests confirmed Debug/GoMobile and Canary/Kotlin selection.
- [ ] If this candidate is being evaluated for K7/Kotlin-default assembly, the manual `K6V Android STCP Interop` workflow ran against its exact full commit SHA; both API 26 and API 36 x86_64 lanes passed and the bilingual acceptance report is `Pass`. Otherwise this item is explicitly recorded as not applicable.
- [ ] The K6V report limitations were reviewed: emulator evidence does not replace physical target-ABI and 16KB native loading, Doze/network and foreground/background transitions, the full wire/payload/restart matrix, performance, resource-leak, or soak evidence required before a Kotlin-default decision.
- [ ] Canary APKs remained verification outputs only; Release signing and release staging used only the GoMobile-default `assembleRelease` outputs.
- [ ] Signed Release APK verification passed for metadata, one signer, expected certificate fingerprint, ABI isolation, `apksigner`, `zipalign -P 16`, native-byte binding, legal assets, filenames, and `SHA256SUMS`.

## Physical Device and Connection Gates

- [ ] Each physically available target ABI completed a first install of its signed ABI-specific APK and launched successfully.
- [ ] A compatible replacement update from the previous real public version preserved expected local data; for the first public version, this item is explicitly recorded as not applicable rather than inferred.
- [ ] An incompatible signer and a lower `versionCode` were rejected as expected, and the documented destructive rollback path was reviewed against the candidate.
- [ ] Uninstall removed app-private local data as documented, reinstall behaved as a new local installation, and no unsupported export/restore claim was made.
- [x] Native loading and app startup were tested on available physical `arm64-v8a` targets.
- [ ] Native loading was tested for other published ABIs on physical hardware where a release claim depends on them; any untested ABI limitation is disclosed.
- [x] Native loading and startup passed on a physical Android device using a 16KB page size.
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

- [ ] `workflow_dispatch` was run from `main` when a signed dry run was required.
- [ ] The dry run uploaded the signed release assets and final reviewable `release-notes.md` artifacts.
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
