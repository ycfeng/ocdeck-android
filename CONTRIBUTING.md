# Contributing to OC Deck

[简体中文](CONTRIBUTING.zh-CN.md)

Thank you for your interest in OC Deck, an independent community-maintained native Android client for OpenCode Server. OC Deck is not built by, endorsed by, sponsored by, or affiliated with the OpenCode project or Anomaly.

## External Contribution Status

External code and documentation contributions are welcome. By participating, contributors agree to follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Maintainers review contributions on their merits and may request changes or decline work that does not fit the project's scope, quality, security, or maintenance requirements.

Opening a pull request does not guarantee acceptance. Search existing issues first and consider opening a feature request before investing in a large change.

## Scope and Design References

Before changing implementation or user-facing behavior, read:

- [Repository agent rules](AGENTS.md)
- [Mobile interaction design](doc/architecture/mobile-interaction.md)
- [Project framework](doc/architecture/project-framework.md)

Keep changes focused on the native Android client. Do not mechanically copy desktop web dialogs or layouts when a mobile-native screen, constrained popup, inline panel, or bottom sheet is more appropriate.

## Issue and Security Routing

- Bugs: use the [bug report form](https://github.com/ycfeng/ocdeck-android/issues/new?template=01-bug-report.yml).
- Feature requests: use the [feature request form](https://github.com/ycfeng/ocdeck-android/issues/new?template=02-feature-request.yml).
- Questions: use the [question form](https://github.com/ycfeng/ocdeck-android/issues/new?template=03-question.yml).
- Security vulnerabilities: follow [SECURITY.md](SECURITY.md) and use [GitHub Private Vulnerability Reporting](https://github.com/ycfeng/ocdeck-android/security/advisories/new). Never put vulnerability details in a public issue.
- Conduct complaints: follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) and use [GitHub Private Vulnerability Reporting](https://github.com/ycfeng/ocdeck-android/security/advisories/new) as the project's shared private intake. Start the report title with `[Code of Conduct]` so it can be routed separately from security reports. Never post complaint details publicly.

Search existing issues before opening a new one. Keep reports minimal and remove private project data and credentials.

## Development Environment

The current baseline is Android API 26+, compile/target SDK 36, JDK 21, Kotlin 2.4.0, Gradle 9.6.1, and Android Gradle Plugin 9.2.1. Keep the Android Studio Gradle JDK and command-line `JAVA_HOME` on JDK 21.

For ordinary app work, install Android SDK 36 and Build Tools 36.0.0. Changes to the GoMobile STCP bridge also require the pinned Go, x/mobile, NDK, and frp versions recorded in `frpc-stcp-visitor-go/bridge-versions.properties`; do not use floating tool or dependency versions.

Run the normal verification from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

On Unix-like systems:

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

This is the ordinary baseline and does not require every documentation, UI, or small App change to build Canary or Kotlin Release-Like. If a change affects STCP backend selection, the pure Kotlin backend, `AppContainer` STCP assembly, shared manager integration, or CI/Release variant validation, also run `:app:testCanaryUnitTest`, `:app:testKotlinReleaseUnitTest`, `:app:assembleCanary`, `:app:assembleKotlinRelease`, and `:app:verifyPureKotlinPackaging`.

## Architecture Rules

- Keep business code in the single `:app` module. `:frpc-stcp-visitor` is the approved native bridge boundary, not a precedent for splitting business features into Gradle modules.
- Use manual dependency injection through `AppContainer` and constructor-injected ViewModels. Do not introduce Hilt, Room, KSP, or kapt without prior project approval based on a concrete need.
- Keep networking, repositories, and application-scoped state out of feature composables. Do not create ad hoc `OkHttpClient`, Retrofit, repository, or global singleton instances in feature code.
- Normalize every OpenCode `directory` value before it enters repository or network code. Keep server project files distinct from Android local attachments.
- Preserve bounded streaming and redaction guarantees for external input, REST, SSE, attachments, private keys, Base64, and native bridge data.

## UI and Localization

- Put all new or changed user-visible text in Android string resources. Update both `app/src/main/res/values/strings.xml` and `app/src/main/res/values-en/strings.xml`.
- Verify every visual change in light and dark themes. Do not hard-code colors that only work in one theme.
- Maintain at least a 48 dp real touch target for every clickable icon and chip action.
- Prefer mobile-native layouts and ensure content remains usable with the IME, system insets, constrained screen sizes, focus navigation, and the system back action.
- Include screenshots or recordings for meaningful UI changes, covering both languages and both themes when affected.

## Testing

Add or update focused unit tests for changed behavior, especially path normalization, redaction, prompt sending, SSE generation and bounds, credentials, SSH host-key verification, STCP epochs, and bounded input handling.

The normal minimum gate is:

```text
:app:testDebugUnitTest
:frpc-stcp-visitor:testDebugUnitTest
:app:assembleDebug
```

Canary and Kotlin Release-Like tasks are conditional for ordinary work, not a universal minimum. They are required for STCP backend/selection changes and are always part of the complete bridge/CI-equivalent gate.

If a change touches `frpc-stcp-visitor-go/`, `frpc-stcp-visitor/`, the frp patch, bridge API, bridge dependency, or bridge version, also run the CI-equivalent bridge gates:

```text
frpc-stcp-visitor-go: go run ./cmd/preparefrp
repository root: python3 .github/scripts/audit-third-party.py
repository root: python3 .github/scripts/audit-community.py
repository root: python3 .github/scripts/check-go-race-environment.py
frpc-stcp-visitor-go: go test -race -modfile=build/frp-patched.mod ./...
frpc-stcp-visitor-go/build/frp-v0.69.1-p1: go test -race ./client/...
repository root: bash frpc-stcp-visitor-go/build-aar.sh
repository root: ./gradlew :frpc-stcp-visitor:frpcInteropTest
repository root: ./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleKotlinRelease :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true
```

On Windows, use `frpc-stcp-visitor-go/build-aar.ps1` and `gradlew.bat` as appropriate. The Go race gates require a supported CGO toolchain on native Windows, WSL2, native Linux, or the Ubuntu CI runner. WSL1 is not a valid substitute because its socket translation can allow duplicate loopback listener binds; the environment check fails before those tests with an explicit diagnostic. `frpcInteropTest` explicitly downloads only the hash-pinned official frp release asset for the host, keeps it in the Gradle cache, and runs outside release-signing environments; ordinary unit tests do not launch it. The complete Android gate validates Debug/GoMobile assembly plus bridge-free Canary/Kotlin and Kotlin Release-Like/Kotlin runtime classpaths and APKs; neither Kotlin verification APK is a publication artifact. Bridge validation must continue to cover checksum, Java API signature, provenance, expected ABIs, ELF machine type, 16 KiB `PT_LOAD` alignment, stripped status, and reproducibility. A bridge byte change requires a new bridge version; never publish different bytes under the same Maven coordinate.

## Sensitive Data and Fixtures

Never commit or paste real API keys, tokens, passwords, cookies, authorization headers, provider authentication, custom provider headers, environment values, SSH passwords, SSH private keys, passphrases, host fingerprints, private URLs, project source, prompts, or sensitive server responses.

Use synthetic fixtures and disposable values. Replace sensitive values with `<redacted>` in logs, tests, snapshots, screenshots, issue text, and documentation. Fixtures must not be copied from a private project or production response merely because obvious credentials were removed.

## Third-Party Material

Before adding or upgrading a dependency, asset, sound, icon, Gradle wrapper, GoMobile component, frp source, or patch:

- Verify its source, version, license, copyright, redistribution terms, and trademark implications.
- Update `THIRD_PARTY_NOTICES.txt`, `third_party/components.toml`, the relevant `third_party/sources/*` record, and license text when applicable.
- Recompute hashes from the actual local bytes.
- Do not introduce provider branding or other third-party marks without source, license, and trademark review.

## Pull Requests

Pull requests must be focused, reviewable, and linked to an issue when one exists. Use [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) and include:

- The user-visible and technical impact.
- Tests run and their results, including every applicable bridge gate.
- UI evidence and localization/theme/accessibility checks when relevant.
- Security, privacy, migration, compatibility, and third-party notice impacts.
- Documentation or release-note changes, or an explicit explanation of why they are not applicable.

Do not mix unrelated cleanup with a behavioral change. Do not commit generated secrets, signing material, local machine configuration, or private fixtures.

## License for Contributions

OC Deck is licensed under the [MIT License](LICENSE). Unless explicitly agreed otherwise in writing by the maintainers, every contribution is submitted under the same MIT terms as the project (`inbound = outbound`). The project does not require a Contributor License Agreement or Developer Certificate of Origin sign-off.
