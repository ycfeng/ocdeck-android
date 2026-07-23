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

## 构建固定 GoMobile Bridge

Bridge 脚本会准备固定并打过补丁的 frp 源码，安装固定 x/mobile 工具，构建和规范化 AAR，校验 API 与 native metadata，并写入本地 Maven 制品。生成的 AAR 和本地构建仓库属于构建输出，不得提交。

Windows：

```powershell
.\frpc-stcp-visitor-go\build-aar.ps1
```

macOS/Linux：

```bash
bash frpc-stcp-visitor-go/build-aar.sh
```

随后在 Android 验证中强制要求 bridge：

Windows：

```powershell
.\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug -PrequireGoMobileBridge=true
```

macOS/Linux：

```bash
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug -PrequireGoMobileBridge=true
```

当前不可变 bridge 坐标为 `io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:0.3.10-frp0.69.1-p1`。Bridge 字节变化时必须修改 `BRIDGE_VERSION`；不得在同一坐标下发布不同字节。

## Release 构建

应用版本只来自根目录 `gradle.properties`：

```properties
VERSION_CODE=8
VERSION_NAME=0.2.3
```

本地 Release 构建要求先生成并校验 bridge，同时提供发布签名输入。使用 `signing.properties.example` 记录的配置键或等价环境变量。Keystore、密码、alias、证书材料和本机路径不得进入 Git、日志、shell 历史、截图或 artifact。App 打包配置会保留 GoMobile AAR 中已经 stripped 且通过校验的 `libgojni.so` 原始字节；APK 发布门禁仍会独立复核 native 字节绑定、ELF metadata、16KB 对齐和 stripped 状态。

公开工作流只生成以下签名 APK 和 `SHA256SUMS`：

- `OCDeck_<version>_arm64-v8a.apk`
- `OCDeck_<version>_armeabi-v7a.apk`
- `OCDeck_<version>_x86_64.apk`

当前不生成 universal APK、AAB 或 Play 上传制品。详见[发布流程](../release/github-actions.zh-CN.md)和[发布检查清单](../release/checklist.zh-CN.md)。

## 常见问题

- JDK 不一致可能导致 Gradle 或 Kotlin toolchain 失败。确认 `java -version` 和 Gradle 都使用 JDK 21。
- Go 或 NDK 与 `bridge-versions.properties` 不一致时，bridge 构建会立即失败。
- 只有未设置 `-PrequireGoMobileBridge=true` 的构建允许缺少 bridge；这类构建中的 STCP 仍不可用。
- Bridge 坐标未变化但生成 AAR 字节变化时，会因不可变性违规而被拒绝。
- AAR 门禁通过但 APK native 字节绑定失败时，应确认 App 打包仍将已经 stripped 的 `libgojni.so` 排除在 Android Gradle Plugin 的 native strip transform 之外；不得降低字节绑定校验或替换 AAR hash。
- Release 签名失败必须修复本机或 GitHub Environment 配置。不得为排错降低证书校验，也不得打印 secret。
