# 安装与更新 OC Deck

[English](installing.md)

本文档是英文 canonical 文档的完整便利翻译。中英文发生差异时，以英文文档为准。

本指南覆盖 Release 下载、ABI 选择、APK 校验、侧载、更新、回退和卸载。受支持环境与当前证据见[兼容性](compatibility.zh-CN.md)。

## 1. 查找真实存在的 Release

只使用 canonical [OC Deck Releases 页面](https://github.com/ycfeng/ocdeck-android/releases)。存在预备发布说明或源码中存在某个版本号，不代表 APK 已经发布。只有对应 Git tag、GitHub Release 和 Release 中的 APK asset 都真实存在时，才算存在公开版本。

如果 Releases 页面没有 OC Deck APK asset，当前就没有可公开安装的构建。不要从非官方镜像下载。

GitHub 可能显示自动生成的 `Source code (zip)` 和 `Source code (tar.gz)`。它们是源码归档，不是 Android 安装包，无法安装。当前发布 workflow 只上传：

- `OCDeck_<version>_arm64-v8a.apk`
- `OCDeck_<version>_armeabi-v7a.apk`
- `OCDeck_<version>_x86_64.apk`
- `SHA256SUMS`

不提供 universal APK 或 AAB。每个 ABI APK 都是完整应用，只安装其中一个。

## 2. 查询 Android 版本和 ABI

OC Deck 要求 Android 8.0/API 26 或更高版本。安装 Android Platform Tools 并启用 USB 或无线调试后，查询设备信息：

```text
adb devices
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
```

如果 `ro.product.cpu.abilist` 异常为空，使用：

```text
adb shell getprop ro.product.cpu.abi
```

从左到右读取 ABI 列表，选择第一个与已发布 ABI 完全匹配的值：

| 设备查询结果开头 | 下载文件 |
| --- | --- |
| `arm64-v8a,armeabi-v7a,...` | `OCDeck_<version>_arm64-v8a.apk` |
| `armeabi-v7a,...` | `OCDeck_<version>_armeabi-v7a.apk` |
| `x86_64,x86,...` | `OCDeck_<version>_x86_64.apk` |
| 只有 `x86`、`riscv64` 或其他值 | 当前没有发布兼容 APK |

不要使用 `adb install-multiple`；这三个文件是同一应用的替代构建，不是 split APK。

## 3. 下载与校验

从同一个 GitHub Release 下载选定的 APK 和 `SHA256SUMS`。将下方的 `X.Y.Z` 和 ABI 替换为实际发布值。

Linux：

```bash
sha256sum OCDeck_X.Y.Z_arm64-v8a.apk
grep 'OCDeck_X.Y.Z_arm64-v8a.apk' SHA256SUMS
```

macOS：

```bash
shasum -a 256 OCDeck_X.Y.Z_arm64-v8a.apk
grep 'OCDeck_X.Y.Z_arm64-v8a.apk' SHA256SUMS
```

Windows PowerShell：

```powershell
Get-FileHash -Algorithm SHA256 .\OCDeck_X.Y.Z_arm64-v8a.apk
Select-String -LiteralPath .\SHA256SUMS -SimpleMatch "OCDeck_X.Y.Z_arm64-v8a.apk"
```

把计算得到的十六进制值与同名文件所在行比较。只有 `SHA256SUMS` 中列出的三个 APK 都已下载时，才适合直接使用 `sha256sum -c SHA256SUMS`；否则它还会报告另外两个 APK 缺失。

如果已经安装 Android SDK Build Tools，可用以下命令确认 APK 签名结构有效并输出证书信息：

```text
apksigner verify --verbose --print-certs OCDeck_X.Y.Z_arm64-v8a.apk
```

`SHA256SUMS` 能证明 APK 与同一 Release 内的校验文件一致，但不能单独、独立证明发布者身份。仓库当前也没有发布可供用户比较的 Release 证书指纹，因此仅运行 `apksigner` 同样不能确认 canonical 发布者身份。

## 4. 通过 Android 侧载

不同厂商的菜单名称可能不同，标准流程是：

1. 下载并校验匹配的 APK。
2. 从下载 APK 的浏览器或文件管理器打开它。
3. 如果 Android 阻止安装，为该浏览器或文件管理器允许“安装未知应用”。
4. 返回安装界面并确认安装。
5. 安装完成后，可撤销该来源的“安装未知应用”权限。

Android 8.0 及以上按来源授予此权限。OC Deck 不申请安装其他应用的权限，也没有应用内更新器。

## 5. 通过 ADB 安装

在 APK 所在目录运行：

```text
adb install "OCDeck_X.Y.Z_arm64-v8a.apk"
```

应用 ID 是 `io.github.ycfeng.ocdeck`。

## 6. 更新已有安装

OC Deck 当前不会在应用内检查或下载更新。请先阅读 Release 说明，下载新的匹配 ABI APK，完成校验后使用 Android 安装器打开。通过 ADB 更新时使用：

```text
adb install -r "OCDeck_X.Y.Z_arm64-v8a.apk"
```

只有相关条件全部满足时，Android 才允许正常覆盖更新：

- 已安装 APK 和新 APK 的应用 ID 都是 `io.github.ycfeng.ocdeck`。
- 两者签名证书具备更新兼容性。
- 新 APK 的 `versionCode` 不低于已安装版本。
- APK 与设备 ABI 匹配，并且要求的 Android 版本不高于设备版本。

对于兼容的覆盖更新，Android 通常会保留应用私有数据，但维护者仍需验证每个签名版本的升级路径。Pre-1.0 的存储和行为可能变化，更新前应阅读对应版本的迁移说明。

## 7. 回退

不要把 `adb install -r -d` 作为普通 OC Deck Release APK 的回退方案。Android 对该降级路径有限制，Release 构建也不应是 debuggable 构建。

实际可行的回退具有破坏性：

```text
adb uninstall io.github.ycfeng.ocdeck
adb install "OCDeck_OLD.VERSION_<abi>.apk"
```

这会删除应用私有本地数据，包括本地保存的服务器配置和偏好。OC Deck 关闭了 Android 备份，当前也没有受支持的导出/恢复流程。卸载不会删除 OpenCode Server、模型 Provider、SSH 服务器、frps 或其他远端系统中已经保存的数据。旧 Release 仍可下载，不代表它仍受支持或兼容当前服务端。

## 8. 卸载

在 Android 应用信息页选择“卸载”，或运行：

```text
adb uninstall io.github.ycfeng.ocdeck
```

后续重新安装应视为新的本地安装。不要把 `adb uninstall -k` 当作备份方案；OC Deck 未将其验证为凭据或设置的受支持恢复路径。本地与远端数据边界见[隐私](../../PRIVACY.zh-CN.md)。

## 9. 常见安装错误

| 错误 | 常见原因 | 处理方式 |
| --- | --- | --- |
| `INSTALL_FAILED_OLDER_SDK` | 设备 API 低于 26 | 使用 Android 8.0/API 26 或更高版本 |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | APK ABI 不在设备支持列表中 | 查询 ABI 列表并下载正确 APK |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 应用 ID 相同但 signer 不兼容 | 安装同一 canonical 签名链的 APK，或先卸载并接受本地数据丢失 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | APK 的 `versionCode` 更低 | 必须回退时使用破坏性回退流程 |
| `INSTALL_FAILED_ALREADY_EXISTS` | 已存在该 package，但 ADB 命令没有要求替换 | 对兼容更新使用 `adb install -r` |

厂商安装器可能只显示笼统的“应用未安装”。条件允许时通过 ADB 复现，以获得具体错误。

## 后续步骤

- 继续阅读[首次使用](getting-started.zh-CN.md)。
- 配置直连、SSH 或 STCP 前阅读[连接方式](connections.zh-CN.md)。
- 安装或启动失败时使用[排障](troubleshooting.zh-CN.md)。
