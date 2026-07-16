# 支持

[English](SUPPORT.md)

本中文译本仅为阅读便利；如有歧义，以英文原文为准。

OC Deck 是一个尚未达到 1.0 版本的社区项目。支持仅以尽力而为的方式提供，不保证响应时间、兼容期限或服务级别协议。

## 报告问题前

1. 按[安装与更新](doc/user/installing.zh-CN.md)确认 Android API、APK 来源、设备 ABI、checksum 和更新兼容性。
2. 按[首次使用](doc/user/getting-started.zh-CN.md)检查失败阶段之前的每个步骤都已成功。
3. 确认已配置的 OpenCode Server 可访问，并通过相同的直连、SSH 或 STCP 路径运行“健康检查”。
4. 使用[排障](doc/user/troubleshooting.zh-CN.md)处理安装、401/403、TLS、SSH host key、STCP、项目、prompt、SSE 和通知失败。
5. 在可行时使用最新可用 OC Deck 构建复现，再查看[兼容性](doc/user/compatibility.zh-CN.md)中的证据与已知限制。

## 诊断信息

有用且不敏感的信息包括：

- OC Deck 版本或 commit
- Android 版本、设备型号和 ABI
- OpenCode Server 版本
- 连接模式：直连、SSH 或 STCP
- 失败阶段：安装、健康检查、项目加载、prompt、SSE 或通知
- 精确但不敏感的安装器、HTTP、TLS、隧道或 UI 错误文本
- 预期行为和实际行为
- 最小复现步骤
- 已脱敏的日志摘录和堆栈跟踪

请勿包含密码、API key、token、cookie、私钥、主机指纹、Provider 请求头、非公开服务器 URL、项目源代码、prompt 或完整的服务器响应。请将敏感值替换为 `<redacted>`。

## 咨询渠道

规范 issue tracker 为 [https://github.com/ycfeng/ocdeck-android/issues](https://github.com/ycfeng/ocdeck-android/issues)。请通过 GitHub issue chooser 或直接打开对应表单：

- [缺陷报告](https://github.com/ycfeng/ocdeck-android/issues/new?template=01-bug-report.yml)
- [功能建议](https://github.com/ycfeng/ocdeck-android/issues/new?template=02-feature-request.yml)
- [问题咨询](https://github.com/ycfeng/ocdeck-android/issues/new?template=03-question.yml)

问题咨询统一使用问题咨询 issue form；项目不承诺提供独立的实时支持渠道。完成用户指南后，如需核对实现范围，请查阅[移动端交互设计](doc/architecture/mobile-interaction.zh-CN.md)和[项目框架](doc/architecture/project-framework.zh-CN.md)。有关 OpenCode Server 行为、配置或上游 API 的问题，请查阅 [OpenCode 文档](https://opencode.ai/docs/) 和 [OpenCode 仓库](https://github.com/anomalyco/opencode)。有关独立于 OC Deck 的 frp 协议问题，请查阅 [frp 仓库](https://github.com/fatedier/frp)。

涉及安全的敏感报告必须遵循 [SECURITY.zh-CN.md](SECURITY.zh-CN.md)，并使用私密漏洞报告渠道。不得在公开 issue 中发布漏洞细节、利用步骤或秘密信息。
