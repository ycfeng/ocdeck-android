# OC Deck

[English](README.md)

OC Deck 是一款 OpenCode 原生 Android 客户端，用于连接你自行运行或信任的 [OpenCode Server](https://opencode.ai/docs/server/)。注意：OC Deck 不是 OpenCode 项目或 Anomaly 官方开发、背书或关联的产品。

> **Pre-1.0 提醒：** 当前版本为 `0.1.0`，仍在快速开发中。功能、存储格式、网络行为和兼容性可能变化。请备份重要工作，不要把 OC Deck 作为访问服务器的唯一方式。

## 使用要求

- Android 8.0 或更高版本，即 API 26+
- 可访问的 [OpenCode Server](https://opencode.ai/docs/server/)；OC Deck 不负责安装或运行服务端
- 按需在服务端配置 Provider 和模型访问能力

## 当前功能

- 支持直连 HTTP(S)、SSH 本地转发和内置 frpc STCP visitor 的多服务器配置
- 支持项目选择、最近项目、会话列表与详情、重命名/归档/删除、revert/unrevert 和异步 prompt
- 支持全局与项目 SSE、自动重连和 REST 快照校准
- 支持 Provider 目录与搜索、动态 API/OAuth 认证、断开、capability 刷新，以及在 OpenCode Server 上分阶段持久化自定义 Provider
- 支持 Slash command、agent mention、agent/model/variant 选择器、手机本地附件和服务端项目文件上下文选择
- 支持服务端项目文件只读浏览、搜索、文本高亮和图片预览
- 支持 permission/question、上下文用量，以及会话 Changes tab 中的 Git/session diff
- 支持英文与简体中文、主题偏好、Android 通知、通知音效和后台运行引导

## 尚未完成

- Provider 管理仍需真实 Server/Provider 兼容性和设备验证，重点包括远程 loopback OAuth、长 callback 取消，以及自定义 Provider 的 partial/unknown outcome
- 独立 Review route 仍为占位；当前可用 diff 位于会话 Changes tab
- 模型 enabled/hidden 只是本机过滤偏好，不会修改 OpenCode Server 配置
- Shell mode、session share、workspace/worktree 和 PTY/terminal 尚未实现

详细状态见[移动端交互设计](doc/architecture/mobile-interaction.zh-CN.md)、[项目框架方案](doc/architecture/project-framework.zh-CN.md)和[文档索引](doc/README.zh-CN.md)。

## 安装与首次使用

公开可安装构建只存在于 canonical [GitHub Releases 页面](https://github.com/ycfeng/ocdeck-android/releases)。如果该页面没有 OC Deck APK asset，当前就没有可公开安装的构建。预备发布说明、源码版本或 GitHub 源码归档都不是 APK。

发布 workflow 只生成按 ABI 拆分的 APK，不提供 universal APK 或 AAB。

| 设备 ABI | 下载文件 |
| --- | --- |
| 大多数当前 Android 手机和平板 | `OCDeck_<version>_arm64-v8a.apk` |
| 较旧的 32 位 ARM 设备 | `OCDeck_<version>_armeabi-v7a.apk` |
| x86_64 模拟器及兼容设备 | `OCDeck_<version>_x86_64.apk` |

不确定时请先查询设备 ABI。不要从不可信镜像安装 APK；应使用同一 Release 中的 `SHA256SUMS` 校验选定 APK。

- 下载、ABI 查询、checksum、侧载、更新、回退和卸载：[安装与更新](doc/user/installing.zh-CN.md)
- 添加服务器、打开项目并发送首条 prompt：[首次使用](doc/user/getting-started.zh-CN.md)
- 直连、SSH 和 STCP 配置：[连接方式](doc/user/connections.zh-CN.md)
- 401/403、TLS、SSH host key、STCP、SSE 和通知失败：[排障](doc/user/troubleshooting.zh-CN.md)

## 安全提醒

- 只连接你信任的服务器和隧道端点。
- 优先使用 HTTPS 或可信 SSH/STCP 链路；将 OpenCode Server 暴露到可信网络之外前应启用认证。
- 直连配置同时使用非本机回环 `http://` 地址和有效 OpenCode Basic 认证时，OC Deck 会在保存前显示明确的提示确认。Android Keystore 只保护本机保存的密码，不会加密 HTTP 流量；选择“仍然保存”后仍可保存配置并正常使用。
- 服务端运营者、模型 Provider 和网络中间方可能接收项目内容、prompt、附件和元数据。
- 不要在 issue、讨论或日志中公开 API key、密码、私钥、token、cookie、含凭据的服务器 URL 或其他敏感信息。

报告问题前请阅读 [SECURITY.zh-CN.md](SECURITY.zh-CN.md)、[PRIVACY.zh-CN.md](PRIVACY.zh-CN.md) 和 [SUPPORT.zh-CN.md](SUPPORT.zh-CN.md)。

## 构建

前置环境：

- JDK 21
- Android SDK 36 与 Build Tools 36.0.0
- 构建 STCP bridge 时还需要 Go 1.26.4 与 Android NDK 27.1.12297006

Windows：

```powershell
.\gradlew.bat :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

macOS/Linux：

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

没有生成 GoMobile AAR 时 Debug 仍可编译，但 STCP 运行时不可用。需要完整 STCP 能力时，先在 Windows 运行 `frpc-stcp-visitor-go/build-aar.ps1`，或在 macOS/Linux 运行 `frpc-stcp-visitor-go/build-aar.sh`，再执行[仓库代理规则](AGENTS.zh-CN.md)中的 bridge 门禁。

## 截图

`0.1.0` 项目门面暂不放置截图。后续只应加入与当前 Android UI 一致且经过审查的截图，截图中不得包含服务器地址、项目路径、会话内容、凭据或其他敏感信息。

## 支持与贡献

欢迎外部代码和文档贡献。所有参与者都必须遵守[行为准则](CODE_OF_CONDUCT.zh-CN.md)。私密行为投诉与安全报告共用 GitHub private advisory 入口，提交时必须明确标识为行为准则报告；不得公开发布投诉细节。

- 支持范围与诊断信息：[SUPPORT.zh-CN.md](SUPPORT.zh-CN.md)
- 安全问题报告：[SECURITY.zh-CN.md](SECURITY.zh-CN.md)
- 贡献指南：[CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md)
- 行为准则：[CODE_OF_CONDUCT.zh-CN.md](CODE_OF_CONDUCT.zh-CN.md)
- 文档中心：[doc/README.zh-CN.md](doc/README.zh-CN.md)
- 仓库代理规则：[AGENTS.zh-CN.md](AGENTS.zh-CN.md)
- 发布流程：[doc/release/github-actions.zh-CN.md](doc/release/github-actions.zh-CN.md)

贡献应保持移动端优先，遵守手动 DI 和单业务模块策略，同步维护中英文 UI 文案并补充相关测试。任何提交都不得包含真实凭据或私有项目数据。

## 许可证与声明

OC Deck 原创代码使用 [MIT License](LICENSE)。第三方组件和资产继续受各自许可证约束，详见 [THIRD_PARTY_NOTICES.zh-CN.txt](THIRD_PARTY_NOTICES.zh-CN.txt)、[NOTICE](NOTICE) 和 [`third_party/`](third_party/) 审计清单。

产品名称和标识说明见 [TRADEMARKS.zh-CN.md](TRADEMARKS.zh-CN.md)。

## 上游项目

- [OpenCode 源码](https://github.com/anomalyco/opencode)
- [OpenCode Server 文档](https://opencode.ai/docs/server/)
- [frp](https://github.com/fatedier/frp)
- [Gradle](https://github.com/gradle/gradle)
