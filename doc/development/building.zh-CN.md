# 构建 OC Deck

[English](building.md)

本文档是英文 canonical 构建文档的便利翻译。中英文发生差异时，以英文文档为准。

## 仓库与范围

Canonical 仓库为 <https://github.com/ycfeng/ocdeck-android>。OC Deck 当前将应用业务代码保留在 `:app`，使用手动依赖注入，并通过 `:frpc-stcp-visitor` 隔离 GoMobile 集成。不要仅为构建项目而引入 Room、Hilt、KSP、kapt 或新的业务 Gradle 模块。

OC Deck 原创代码和文档使用仓库的 [MIT License](../../LICENSE)。第三方组件继续受各自许可证约束。

## 必需工具链

| 组件 | 必需版本 |
| --- | --- |
| JDK 与 Java/Kotlin toolchain | 21 |
| Android SDK | 36 |
| Android Build Tools | 36.0.0 |
| STCP bridge 使用的 Android NDK | 27.1.12297006 |
| STCP bridge 使用的 Go | 1.26.4 |
| 运行 `build-aar.ps1` 的 PowerShell | 7.3 或更高 |

固定的 x/mobile revision、Android bridge API level、bridge 版本、Go 版本和 NDK 版本只以 `frpc-stcp-visitor-go/bridge-versions.properties` 为准。不要改用 `latest` 等浮动版本。

将 `JAVA_HOME` 指向 JDK 21，并通过 `ANDROID_SDK_ROOT`、`ANDROID_HOME` 或常规且不提交的 `local.properties` 配置 Android SDK。Android Studio 的 Gradle JDK 应与命令行保持一致。

## 正式 Debug 构建

正式 `debug` 构建选择 `KotlinFrpcStcpVisitorClient`。它不含 bridge，不需要生成 GoMobile AAR，并通过纯 Kotlin 实现支持 Direct、SSH 与 STCP。

Windows：

```powershell
.\gradlew.bat :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

macOS/Linux：

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

## 内部 Kotlin Canary 构建

正式 `debug` 与 `release` 构建实例化 `KotlinFrpcStcpVisitorClient`。`canary` build type 继承 Debug 行为，并继续作为带 suffix 的 Kotlin 验证 variant。`kotlinRelease` 提供对应的 Release-Like Kotlin 验证身份；GoMobile 只保留在下文的对比与 emergency variant 中。两种 backend 之间没有用户可选设置、持久化 selector 或运行时 fallback。

Windows：

```powershell
.\gradlew.bat :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleCanary :app:verifyCanaryPackaging
```

macOS/Linux：

```bash
./gradlew :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleCanary :app:verifyCanaryPackaging
```

Canary 使用 application ID `io.github.ycfeng.ocdeck.canary` 与 version name `<VERSION_NAME>-canary`，可与正式 App 并存安装。按 ABI 拆分的 APK 位于 `app/build/outputs/apk/canary/`：

- `OCDeck_<version>-canary_arm64-v8a.apk`
- `OCDeck_<version>-canary_armeabi-v7a.apk`
- `OCDeck_<version>-canary_x86_64.apk`

Canary 是内部验证身份，不是接受 Release 签名或公开发布的渠道。它精确解析无 bridge 的 `:frpc-stcp-visitor` Canary variant，因此即使本地存在生成 AAR，其 runtime classpath 与 APK 也会排除 GoMobile bridge 和 `libgojni.so`。`verifyCanaryPackaging` 会在任一约束被破坏时失败。

## Kotlin Release-Like 真机构建

`kotlinRelease` build type 继承正式 Release 配置，但选择 `KotlinFrpcStcpVisitorClient`。它使用 application ID `io.github.ycfeng.ocdeck.kotlinrelease`、version name `<VERSION_NAME>-kotlin-release` 和标准 Android Debug 测试证书，因此无需发布签名材料即可与正式 App、Canary 并存安装。

Windows：

```powershell
.\gradlew.bat :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleKotlinRelease :app:verifyKotlinReleasePackaging
```

macOS/Linux：

```bash
./gradlew :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleKotlinRelease :app:verifyKotlinReleasePackaging
```

按 ABI 拆分的 APK 位于 `app/build/outputs/apk/kotlinRelease/`：

- `OCDeck_<version>-kotlin-release_arm64-v8a.apk`
- `OCDeck_<version>-kotlin-release_armeabi-v7a.apk`
- `OCDeck_<version>-kotlin-release_x86_64.apk`

该 variant 精确解析无 bridge 的 `:frpc-stcp-visitor` Kotlin Release-Like variant，因此其 runtime classpath 与 APK 会排除 GoMobile bridge 和 `libgojni.so`；`verifyKotlinReleasePackaging` 会强制执行这两项约束。该 variant 仅用于真机 Release 模式 class loading、STCP 恢复、性能与 soak 验证。其 Debug 测试签名有意与公开 App 身份和证书不兼容。CI 与 Release 自动化会把它构建为验证输出，但绝不暂存或发布。正式 `assembleRelease` 独立使用纯 Kotlin backend、公开身份、稳定版本和 Release signer。

## GoMobile Release-Like 真机构建

对称的 `goMobileRelease` build type 同样继承 Release 配置，但继续使用 `GoMobileFrpcStcpVisitorClient`。它使用 application ID `io.github.ycfeng.ocdeck.gomobilerelease`、version name `<VERSION_NAME>-gomobile-release` 和 Android Debug 测试证书。它不可发布，只用于让 App E2E、性能、资源、压力、泄漏与 soak workload 在等价 Release-Like 配置下比较两个 backend。其 APK 与 Android-test 装配由[测试](testing.zh-CN.md)中记录的专用 App E2E/resource 任务驱动。

## GoMobile Emergency Release 构建

`goMobileEmergencyRelease` 是受保护的 emergency build type。它没有 application-ID 或 version suffix，使用 `io.github.ycfeng.ocdeck`、稳定版本和正式 Release 证书，并选择 `GoMobileFrpcStcpVisitorClient`。Release 签名配置缺失或不完整时会 fail closed，绝不回退到 Debug 证书。

该 variant 只允许通过受保护的 Release workflow 手动 dispatch，并选择 `artifact_profile=gomobile-emergency-release`。稳定 tag 始终选择 `kotlin-release`，且只有该 profile 可自动发布。Emergency profile 仅用于维护者控制的回滚或 emergency 演练，绝不会由 tag 触发运行选中。

## 固定 frp 互操作 Harness

显式 Kotlin STCP 互操作任务与普通单元测试分离：

```powershell
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcInteropTest
```

```bash
./gradlew --no-daemon :frpc-stcp-visitor:frpcInteropTest
```

任务只会下载 `third_party/sources/frp.json` 以 URL 与 SHA-256 固定的当前主机官方 frp `v0.69.1` archive，校验后仅安全解压 `frpc`/`frps`，并把已验证的测试专用文件保存在 Gradle user cache。任务会启动 loopback 子进程并使用合成凭据；它不是 App runtime 路径，不会向 APK/AAR/Release 增加任何内容，且必须在受保护的发布签名 Environment 之外运行。

## 构建固定 GoMobile Bridge

Bridge 脚本会准备固定并打过补丁的 frp 源码，并安装固定 x/mobile 工具。在执行 `gomobile bind` 前，`cmd/preparemoduleproxy` 会创建稳定、版本化的本地 `GOPROXY` 与 bind module graph，使正式 AAR 不记录 checkout 路径 replacement。Bind 必须同时生成 AAR 及配套 sources JAR；缺少任一制品都会使构建失败。两个 archive 都会先规范化，再将 AAR、sources JAR、POM、checksum、API、provenance 与 native metadata 写入本地 Maven 仓库。

`cmd/checkaar` 会读取四个 `libgojni.so` 的 Go BuildInfo，校验固定 module identity、version 与 sum，拒绝本地 module identity 以及内嵌的仓库/cache 路径，并要求 canonical module graph digest 在各 ABI 间一致。Schema 2 bridge provenance 与 native metadata 会绑定这项证明。生成的 AAR 和本地构建仓库属于构建输出，不得提交。

Windows：

```powershell
.\frpc-stcp-visitor-go\build-aar.ps1
```

macOS/Linux：

```bash
bash frpc-stcp-visitor-go/build-aar.sh
```

### 验证跨 Checkout 可复现性

从仓库根目录的干净 checkout 运行等价 CI 可复现门禁。脚本会拒绝存在任何 tracked 或 untracked 工作树变更的 checkout。

Windows：

```powershell
.\.github\scripts\verify-bridge-reproducibility.ps1
```

macOS/Linux：

```bash
bash .github/scripts/verify-bridge-reproducibility.sh
```

门禁会在当前主机平台分别构建当前 checkout，以及位于不同绝对路径的 detached worktree。两个构建分别使用隔离的 `GOCACHE`、`GOMODCACHE` 与 `GOPATH` 目录，再逐字节比较 AAR、必需的 sources JAR、POM、checksum、API、bridge/frp provenance 和 native sidecar。这只保证同一平台跨 checkout 的可复现性，不声称 Windows 与 Linux 之间字节一致。临时 checkout 与 cache 会被删除，主 checkout 中的构建输出会保留，供后续 Gradle 门禁使用。

随后在 Android 验证中强制要求 bridge：

Windows：

```powershell
.\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testGoMobileReleaseUnitTest :app:testGoMobileEmergencyReleaseUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleGoMobileRelease :app:assembleGoMobileReleaseAndroidTest :app:assembleKotlinRelease :app:assembleKotlinReleaseAndroidTest :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true
```

macOS/Linux：

```bash
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testGoMobileReleaseUnitTest :app:testGoMobileEmergencyReleaseUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleGoMobileRelease :app:assembleGoMobileReleaseAndroidTest :app:assembleKotlinRelease :app:assembleKotlinReleaseAndroidTest :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true
```

当前不可变 bridge 坐标为 `io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:0.3.19-frp0.69.1-p6`。Bridge 字节变化时必须修改 `BRIDGE_VERSION`；不得在同一坐标下发布不同字节。

## Release 构建

应用版本只来自根目录 `gradle.properties`：

```properties
VERSION_CODE=10
VERSION_NAME=0.4.0
```

本地正式 Release 构建需要发布签名输入，但不需要 GoMobile AAR。使用 `signing.properties.example` 记录的配置键或等价环境变量。Keystore、密码、alias、证书材料和本机路径不得进入 Git、日志、shell 历史、截图或 artifact。正式 Debug 与 Release 使用纯 Kotlin 且不含 bridge。Canary 与 Kotlin Release-Like 是 Kotlin 验证 variant；GoMobile Release-Like 是带 suffix、使用 Debug 签名的对称对比 variant。受保护的手动 workflow 还可以使用正式身份、稳定版本与 Release signer 构建 `goMobileEmergencyRelease`。稳定 tag 只选择 `kotlin-release` artifact profile 并运行 `verifyReleasePackaging`；`gomobile-emergency-release` 仅允许手动运行。backend-aware release verifier 除了 output metadata 外，还会直接读取每个 APK manifest 中的 application ID 与版本，对两个签名 profile 执行公共签名/ABI/对齐/法律/checksum 校验；emergency GoMobile 会把 `libgojni.so` 绑定到 AAR sidecar，正式 Kotlin profile 会拒绝 bridge class 与 `libgojni.so`，并继续校验所有其他 native library。

公开工作流只生成以下签名 APK 和 `SHA256SUMS`：

- `OCDeck_<version>_arm64-v8a.apk`
- `OCDeck_<version>_armeabi-v7a.apk`
- `OCDeck_<version>_x86_64.apk`

当前不生成 universal APK、AAB 或 Play 上传制品。详见[发布流程](../release/github-actions.zh-CN.md)和[发布检查清单](../release/checklist.zh-CN.md)。

## 常见问题

- JDK 不一致可能导致 Gradle 或 Kotlin toolchain 失败。确认 `java -version` 和 Gradle 都使用 JDK 21。
- Go 或 NDK 与 `bridge-versions.properties` 不一致时，bridge 构建会立即失败。
- 正式 Debug、Release 与 Kotlin 验证 variant 允许缺少 bridge；只有 GoMobile Release-Like 与 GoMobile Emergency Release 需要 bridge。完整等价 CI 门禁会为 GoMobile 路径校验 AAR，同时单独证明 Debug、Canary、Kotlin Release-Like 与正式 Release 都排除该依赖。
- Backend 选择由 `BuildConfig`/factory 测试验证，其中 Debug 预期选择 Kotlin。打包隔离是另一项独立约束：普通 Kotlin variant 使用 `verifyPureKotlinPackaging`，正式签名 Kotlin Release 使用 `verifyReleasePackaging`。
- Bridge 坐标未变化但生成 AAR 字节变化时，会因不可变性违规而被拒绝。
- AAR 门禁通过但 APK native 字节绑定失败时，应确认 App 打包仍将已经 stripped 的 `libgojni.so` 排除在 Android Gradle Plugin 的 native strip transform 之外；不得降低字节绑定校验或替换 AAR hash。
- Release 签名失败必须修复本机或 GitHub Environment 配置。不得为排错降低证书校验，也不得打印 secret。
