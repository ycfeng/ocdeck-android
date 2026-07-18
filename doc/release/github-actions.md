# OC Deck GitHub Actions Release Process

[简体中文](github-actions.zh-CN.md)

This English document is the public canonical release-process document. The Chinese document is a complete convenience translation.

## 1. Goals and Boundaries

The repository uses two GitHub Actions workflows:

- `.github/workflows/ci.yml` runs for pushes to `main`, pull requests targeting `main`, and manual dispatch. It does not read release-signing material.
- `.github/workflows/release.yml` runs for stable version tags or a manual dry run. It prepares release notes without signing secrets, then uses the protected `release` Environment to build and verify signed artifacts. Only a tag-triggered run creates a GitHub Release.

The first public artifact set is fixed to:

- `OCDeck_<version>_arm64-v8a.apk`
- `OCDeck_<version>_armeabi-v7a.apk`
- `OCDeck_<version>_x86_64.apk`
- `SHA256SUMS`

The workflow does not create a universal APK, AAB, or Play upload artifact. GitHub sideload users must download the APK matching their device ABI.

The application ID is `io.github.ycfeng.ocdeck`, version `0.1.3` has a minimum Android API level of 26, and OC Deck is an independent community client. Release notes must state that users provide their own reachable OpenCode Server and that pre-1.0 behavior and compatibility may change.

## 2. Version Rules

The only source of the application version is root `gradle.properties`:

```properties
VERSION_CODE=4
VERSION_NAME=0.1.3
```

Requirements:

- `VERSION_NAME` must be stable SemVer without leading zeroes, for example `1.2.3`. Prerelease and build metadata are not accepted.
- A formal release tag must exactly equal `v${VERSION_NAME}`, for example `v1.2.3`.
- `VERSION_CODE` must be an integer from `1` through `2100000000` and must exceed the value in the previous stable tag.
- CI does not rewrite versions. Update both properties in source before a release.
- A new tag must be the repository's highest stable version, must resolve to the checked-out commit, and that commit must be an ancestor of `origin/main`.

## 3. CI Gates

Normal CI pins Ubuntu 24.04, JDK 21, Go 1.26.4, Android SDK 36, Build Tools 36.0.0, and NDK 27.1.12297006. It performs these gates in order:

1. Audit community/documentation metadata, generate patched frp, and audit third-party/legal inventory, the union of dependencies for all four Android Go targets, resource hashes, and complete modified/added provenance.
2. Run the outer Go race tests and separately run `client/...` race tests in the generated frp module.
3. Run the canonical shell reproducibility gate on the Ubuntu runner. It builds the clean candidate checkout and a detached checkout at a different absolute path with isolated `GOCACHE`, `GOMODCACHE`, and `GOPATH`; validates legal/API/provenance/native metadata and the fixed, local-path-free Go BuildInfo module graph for four ABIs; and compares the AAR, required sources JAR, POM, checksum, API, bridge/frp provenance, and native sidecar byte-for-byte.
4. Run the bridge AAR gate, Debug unit tests for both Android modules, and the Debug APK build.

CI does not possess a JKS, passwords, or repository write permission. Physical-device native loading, a 16KB page-size device, and a real STCP end-to-end loop remain mandatory manual release gates. Static build checks do not replace them.

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
- Grant `prepare-notes` only `contents: write`, because GitHub's generate-notes REST endpoint requires that permission. This job does not enter the `release` Environment, receives no signing secrets, and does not create or modify a release or ref.
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
3. Complete the [release checklist](checklist.md), including physical-device native load, 16KB page-size, and real STCP validation. A missing external gate blocks a formal release.
4. Merge to `main` and confirm CI passes.
5. Optionally run the `Release` workflow manually from `main`. The dry run prepares final reviewable notes, rebuilds, signs, verifies, and retains artifacts for three days, but does not create a GitHub Release.
6. Create and push `vMAJOR.MINOR.PATCH` on the already validated commit.
7. `preflight` validates source versions, the tag, the `origin/main` ancestry relationship, the highest-version rule, and historical `VERSION_CODE` monotonicity.
8. `prepare-notes` builds and uploads the final `release-notes.md` without signing secrets. For a real tag, it appends GitHub-generated notes.
9. After Environment approval, `build-release` reruns all tests and uses the shell reproducibility gate to build the clean candidate checkout plus a detached checkout at a different absolute path on the same Ubuntu runner with isolated Go caches. It requires the complete bridge artifact/sidecar set to match byte-for-byte, then builds three signed APKs and checks the fixed certificate fingerprint.
10. `publish` downloads both verified artifact sets and uses `GITHUB_TOKEN` with `gh release create --verify-tag --notes-file`. It creates a prerelease for every `0.x` version and publishes a normal release beginning with `1.0.0`.

## 8. Artifact Checks

The release scripts also verify:

- APK metadata application ID, Release variant, `versionName`, `versionCode`, target ABI, and filename all agree.
- Each APK has exactly one signer, and the certificate fingerprint equals `RELEASE_CERT_SHA256`.
- Each APK passes `apksigner` and 16KB `zipalign`, contains only its target ABI, and includes `libgojni.so` whose SHA-256 exactly matches the corresponding AAR entry.
- APK native libraries pass ELF machine, all-`PT_LOAD` 16KB alignment, and stripped-state checks.
- App packaging preserves the already-stripped AAR `libgojni.so` bytes instead of applying Android Gradle Plugin's native strip transform a second time; the independent APK checks above prevent this exclusion from weakening native validation.
- `assets/legal/` in each APK is byte-identical to the current LICENSE, NOTICE, third-party notices, trademark statement, merged license text, and every individual license.
- `META-INF/OCDECK/` in the AAR contains the current legal texts, licenses, exact Java API, and bridge/frp provenance, including the schema-2 native module-graph digest and local-path-free proof.
- External checksum/API/provenance/native sidecars are bound to the embedded content and exact AAR bytes; schema-2 native metadata also proves fixed BuildInfo module identities, versions, and sums and one graph digest across all four ABIs.
- `SHA256SUMS` covers only the final three public APKs.

The bridge is statically produced for four GoMobile ABIs, while the public application release intentionally contains only `arm64-v8a`, `armeabi-v7a`, and `x86_64` APKs.

## 9. Failure Handling

- Version validation fails: compare the tag, `VERSION_NAME`, `VERSION_CODE`, stable tag ordering, checked-out commit, and `origin/main` ancestry.
- Release-notes preparation fails: add the required `release-notes/v${VERSION_NAME}.md`, remove prohibited placeholders, verify the preamble substitutions, and confirm the real tag exists before generate-notes is called.
- GoMobile build fails: confirm the runner has the exact Go and NDK versions, then inspect checksum, API signature, provenance, native metadata, or reproducibility failures.
- Signing fails: check the four Environment secrets, complete Base64 JKS content, alias, and passwords without printing any secret.
- Certificate fingerprint fails: stop the release and verify that the Environment JKS is the established app-signing key. Do not change the expected fingerprint merely to bypass continuity protection.
- APK native-byte binding fails after the AAR gate passes: confirm the App packaging exclusion for the already-stripped `libgojni.so`; do not accept transformed bytes, replace the AAR hash, or weaken the APK checks.
- A physical-device or real STCP gate fails or is incomplete: do not publish. Fix and repeat the gate or defer the release.
- The GitHub Release already exists: do not overwrite it or move the tag. Fix the issue, increment the version, and create a new tag.
