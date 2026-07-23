# Building OC Deck

[简体中文](building.zh-CN.md)

This English document is canonical. The Chinese document is a convenience translation.

## Repository and Scope

The canonical repository is <https://github.com/ycfeng/ocdeck-android>. OC Deck currently keeps application business code in `:app`, uses manual dependency injection, and isolates the GoMobile integration behind `:frpc-stcp-visitor`. Do not introduce Room, Hilt, KSP, kapt, or additional business Gradle modules merely to build the project.

OC Deck original code and documentation are provided under the repository's [MIT License](../../LICENSE). Third-party components remain under their respective licenses.

## Required Toolchain

| Component | Required version |
| --- | --- |
| JDK and Java/Kotlin toolchain | 21 |
| Android SDK | 36 |
| Android Build Tools | 36.0.0 |
| Android NDK for the STCP bridge | 27.1.12297006 |
| Go for the STCP bridge | 1.26.4 |
| PowerShell for `build-aar.ps1` | 7.3 or newer |

The fixed x/mobile revision, Android bridge API level, bridge version, Go version, and NDK version are defined only in `frpc-stcp-visitor-go/bridge-versions.properties`. Do not replace them with floating versions such as `latest`.

Set `JAVA_HOME` to JDK 21 and configure the Android SDK through `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or the usual untracked `local.properties`. Keep Android Studio's Gradle JDK aligned with the command line.

## Debug Build Without STCP

A Debug build can compile without a generated GoMobile AAR. Direct and SSH code can be built and tested, but STCP will report that the native bridge is unavailable at runtime.

Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

## Internal Kotlin Canary Build

The `canary` build type inherits Debug behavior but uses build-time App assembly to instantiate `KotlinFrpcStcpVisitorClient`. `debug` and `release` continue to instantiate `GoMobileFrpcStcpVisitorClient`. There is no user-selectable setting, persisted selector, or runtime fallback between these backends.

Windows:

```powershell
.\gradlew.bat :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleCanary :app:verifyCanaryPackaging
```

macOS/Linux:

```bash
./gradlew :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleCanary :app:verifyCanaryPackaging
```

Canary uses application ID `io.github.ycfeng.ocdeck.canary` and version name `<VERSION_NAME>-canary`, so it can be installed beside the formal app. Its ABI-split APKs are written under `app/build/outputs/apk/canary/` as:

- `OCDeck_<version>-canary_arm64-v8a.apk`
- `OCDeck_<version>-canary_armeabi-v7a.apk`
- `OCDeck_<version>-canary_x86_64.apk`

Canary is an internal verification identity, not a release-signed or published channel. It resolves the matching bridge-free `:frpc-stcp-visitor` Canary variant, so its runtime classpath and APKs exclude the GoMobile bridge and `libgojni.so` even when the generated AAR is present locally. `verifyCanaryPackaging` fails if either invariant is violated.

## Kotlin Release-Like Device Build

The `kotlinRelease` build type inherits the formal Release configuration but selects `KotlinFrpcStcpVisitorClient`. It uses application ID `io.github.ycfeng.ocdeck.kotlinrelease`, version name `<VERSION_NAME>-kotlin-release`, and the standard Android Debug test certificate so it can be installed beside both the formal app and Canary without requiring release-signing material.

Windows:

```powershell
.\gradlew.bat :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleKotlinRelease :app:verifyKotlinReleasePackaging
```

macOS/Linux:

```bash
./gradlew :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleKotlinRelease :app:verifyKotlinReleasePackaging
```

ABI-split APKs are written under `app/build/outputs/apk/kotlinRelease/` as:

- `OCDeck_<version>-kotlin-release_arm64-v8a.apk`
- `OCDeck_<version>-kotlin-release_armeabi-v7a.apk`
- `OCDeck_<version>-kotlin-release_x86_64.apk`

This variant resolves the matching bridge-free `:frpc-stcp-visitor` Kotlin Release-Like variant. Its runtime classpath and APKs therefore exclude the GoMobile bridge and `libgojni.so`; `verifyKotlinReleasePackaging` enforces both conditions. It is for physical-device Release-mode class loading, STCP recovery, performance, and soak validation only. Its Debug test signature is intentionally incompatible with the public application identity and certificate. CI and Release automation build it as a verification output but never stage or publish it. Formal `assembleRelease` remains GoMobile-default and is the only task eligible for release signing and publication.

## Fixed-frp Interoperability Harness

The explicit Kotlin STCP interoperability task is separate from ordinary unit tests:

```powershell
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcInteropTest
```

```bash
./gradlew --no-daemon :frpc-stcp-visitor:frpcInteropTest
```

It downloads only the current host's official frp `v0.69.1` archive pinned by URL and SHA-256 in `third_party/sources/frp.json`, verifies and safely extracts only `frpc`/`frps`, and stores the verified test-only files under the Gradle user cache. The task starts loopback subprocesses and synthetic credentials; it is not an App runtime path, adds nothing to APK/AAR/Release contents, and must run outside the protected release-signing Environment.

## Build the Fixed GoMobile Bridge

The bridge scripts prepare the pinned and patched frp source and install the pinned x/mobile tools. Before `gomobile bind`, `cmd/preparemoduleproxy` creates a stable, versioned local `GOPROXY` and bind module graph so the formal AAR does not record checkout-path replacements. The bind must produce both the AAR and its companion sources JAR; either one missing is a build failure. Both archives are normalized before the AAR, sources JAR, POM, checksum, API, provenance, and native metadata are written to the local Maven repository.

`cmd/checkaar` reads Go BuildInfo from all four `libgojni.so` files, verifies fixed module identities, versions, and sums, rejects local module identities and embedded repository/cache paths, and requires the canonical module-graph digest to match across ABIs. Schema-2 bridge provenance and native metadata bind this proof. Generated AARs and local build repositories are build outputs and must not be committed.

Windows:

```powershell
.\frpc-stcp-visitor-go\build-aar.ps1
```

macOS/Linux:

```bash
bash frpc-stcp-visitor-go/build-aar.sh
```

### Verify Cross-Checkout Reproducibility

Run the CI-equivalent reproducibility gate from the repository root in a clean checkout. The scripts reject any tracked or untracked worktree changes.

Windows:

```powershell
.\.github\scripts\verify-bridge-reproducibility.ps1
```

macOS/Linux:

```bash
bash .github/scripts/verify-bridge-reproducibility.sh
```

On the current host platform, the gate builds the current checkout and a detached worktree at a different absolute path. It gives each build isolated `GOCACHE`, `GOMODCACHE`, and `GOPATH` directories, then compares the AAR, required sources JAR, POM, checksum, API, bridge/frp provenance, and native sidecar byte-for-byte. This is a same-platform, cross-checkout guarantee; it does not claim byte identity between Windows and Linux. The temporary checkout and caches are removed, while the primary build outputs remain in the invoking checkout for the subsequent Gradle gate.

Then require the bridge during Android verification:

Windows:

```powershell
.\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleKotlinRelease :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true
```

macOS/Linux:

```bash
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleKotlinRelease :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true
```

The current immutable bridge coordinate is `io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:0.3.11-frp0.69.1-p1`. If bridge bytes change, `BRIDGE_VERSION` must change; never publish different bytes under the same coordinate.

## Release Builds

Application versions come only from root `gradle.properties`:

```properties
VERSION_CODE=8
VERSION_NAME=0.2.3
```

Local Release builds require a generated and verified bridge plus release-signing inputs. Use the keys documented by `signing.properties.example` or equivalent environment variables. Keep keystores, passwords, aliases, certificate material, and local paths out of Git, logs, shell history, screenshots, and artifacts. Release remains GoMobile-default. CI and Release automation build Canary and Kotlin Release-Like APKs only as verification variants without Release signing; only `assembleRelease` outputs receive Release signing, are staged, and are eligible for publication. The App packaging configuration preserves the already-stripped `libgojni.so` bytes verified in the GoMobile AAR; APK release checks independently revalidate native-byte binding, ELF metadata, 16KB alignment, and stripped state.

The public workflow produces only these signed APKs and `SHA256SUMS`:

- `OCDeck_<version>_arm64-v8a.apk`
- `OCDeck_<version>_armeabi-v7a.apk`
- `OCDeck_<version>_x86_64.apk`

It does not produce a universal APK, AAB, or Play upload artifact. See the [release process](../release/github-actions.md) and [release checklist](../release/checklist.md).

## Common Problems

- A JDK mismatch can cause Gradle or Kotlin toolchain failures. Confirm `java -version` and Gradle use JDK 21.
- A bridge build fails immediately if Go or NDK differs from `bridge-versions.properties`.
- An absent bridge is permitted only for builds that do not set `-PrequireGoMobileBridge=true`. In that case the GoMobile-backed Debug STCP path is unavailable, while Canary and Kotlin Release-Like still build and run without the bridge. The full CI-equivalent gate intentionally validates the AAR for Debug/GoMobile while separately proving that both Kotlin verification variants exclude it.
- Backend selection is verified through `BuildConfig`/factory tests. Packaging isolation is a separate invariant enforced by `verifyPureKotlinPackaging`, which checks runtime dependencies and APK ZIP entries rather than a brittle size threshold.
- A changed generated AAR under an unchanged bridge coordinate is rejected as an immutability violation.
- If APK native-byte binding fails while the AAR gate passes, confirm that App packaging still excludes the already-stripped `libgojni.so` from Android Gradle Plugin's native strip transform. Do not weaken the byte-binding check or replace the AAR hash.
- Release signing failures must be fixed in local or GitHub Environment configuration. Never weaken certificate checks or print secrets to diagnose them.
