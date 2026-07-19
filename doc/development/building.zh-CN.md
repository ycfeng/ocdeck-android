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

## 不带 STCP 的 Debug 构建

未生成 GoMobile AAR 时 Debug 仍可编译。直连和 SSH 代码可以构建与测试，但 STCP 会在运行时明确报告 native bridge 不可用。

Windows：

```powershell
.\gradlew.bat :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

macOS/Linux：

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

## 内部 Kotlin Canary 构建

`canary` build type 继承 Debug 行为，但 App 在构建时会装配并实例化 `KotlinFrpcStcpVisitorClient`；`debug` 与 `release` 继续实例化 `GoMobileFrpcStcpVisitorClient`。两种 backend 之间没有用户可选设置、持久化 selector 或运行时 fallback。

Windows：

```powershell
.\gradlew.bat :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleCanary
```

macOS/Linux：

```bash
./gradlew :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleCanary
```

Canary 使用 application ID `io.github.ycfeng.ocdeck.canary` 与 version name `<VERSION_NAME>-canary`，可与正式 App 并存安装。按 ABI 拆分的 APK 位于 `app/build/outputs/apk/canary/`：

- `OCDeck_<version>-canary_arm64-v8a.apk`
- `OCDeck_<version>-canary_armeabi-v7a.apk`
- `OCDeck_<version>-canary_x86_64.apk`

Canary 是内部验证身份，不是接受 Release 签名或公开发布的渠道。两种 backend 实现都位于 `:frpc-stcp-visitor`，因此 AAR 存在时 Canary APK 仍可能包含 Go native library。Build type 契约保证的是 `AppContainer` 选择的 client 实例，而不是安装包中没有 Go native 字节。

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
.\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary -PrequireGoMobileBridge=true
```

macOS/Linux：

```bash
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary -PrequireGoMobileBridge=true
```

当前不可变 bridge 坐标为 `io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:0.3.7-frp0.69.1-p1`。Bridge 字节变化时必须修改 `BRIDGE_VERSION`；不得在同一坐标下发布不同字节。

## Release 构建

应用版本只来自根目录 `gradle.properties`：

```properties
VERSION_CODE=4
VERSION_NAME=0.1.3
```

本地 Release 构建要求先生成并校验 bridge，同时提供发布签名输入。使用 `signing.properties.example` 记录的配置键或等价环境变量。Keystore、密码、alias、证书材料和本机路径不得进入 Git、日志、shell 历史、截图或 artifact。Release 仍默认使用 GoMobile；CI 与 Release 自动化只把 Canary 构建为不使用 Release 签名的验证 variant，只有 `assembleRelease` 输出接受 Release 签名、被暂存并具备发布资格。App 打包配置会保留 GoMobile AAR 中已经 stripped 且通过校验的 `libgojni.so` 原始字节；APK 发布门禁仍会独立复核 native 字节绑定、ELF metadata、16KB 对齐和 stripped 状态。

公开工作流只生成以下签名 APK 和 `SHA256SUMS`：

- `OCDeck_<version>_arm64-v8a.apk`
- `OCDeck_<version>_armeabi-v7a.apk`
- `OCDeck_<version>_x86_64.apk`

当前不生成 universal APK、AAB 或 Play 上传制品。详见[发布流程](../release/github-actions.zh-CN.md)和[发布检查清单](../release/checklist.zh-CN.md)。

## 常见问题

- JDK 不一致可能导致 Gradle 或 Kotlin toolchain 失败。确认 `java -version` 和 Gradle 都使用 JDK 21。
- Go 或 NDK 与 `bridge-versions.properties` 不一致时，bridge 构建会立即失败。
- 只有未设置 `-PrequireGoMobileBridge=true` 的构建允许缺少 bridge。此时 GoMobile Debug 的 STCP 路径不可用，而 Canary 仍选择 Kotlin backend；完整等价 CI 门禁会有意要求并对两个验证构建校验 AAR。
- 不要根据 APK 内容推断 backend 选择。两种实现共用一个 library，Canary 仍可能打包 `libgojni.so`；应通过 Canary `BuildConfig`/factory 测试验证选择结果。
- Bridge 坐标未变化但生成 AAR 字节变化时，会因不可变性违规而被拒绝。
- AAR 门禁通过但 APK native 字节绑定失败时，应确认 App 打包仍将已经 stripped 的 `libgojni.so` 排除在 Android Gradle Plugin 的 native strip transform 之外；不得降低字节绑定校验或替换 AAR hash。
- Release 签名失败必须修复本机或 GitHub Environment 配置。不得为排错降低证书校验，也不得打印 secret。
