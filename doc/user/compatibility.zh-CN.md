# 兼容性

[English](compatibility.md)

本文档是英文公开 canonical 兼容性声明的便利翻译。中英文发生差异时，以英文文档为准。

OC Deck 是独立社区维护的 pre-1.0 OpenCode Server Android 客户端。它不包含、不安装、也不运行 OpenCode Server。下列兼容性结论只适用于 OC Deck，以及当前仓库中确实存在的验证证据。

用户操作流程见[安装与更新](installing.zh-CN.md)、[连接方式](connections.zh-CN.md)、[首次使用](getting-started.zh-CN.md)和[排障](troubleshooting.zh-CN.md)。

## 状态定义

| 状态 | 含义 |
| --- | --- |
| Supported | 明确的产品目标。维护者接受相关兼容性问题报告，但 pre-1.0 行为仍可能按弃用策略变化。 |
| Tested | 已在所列环境中进行重复自动验证或有记录的验证。这是证据，不代表更广泛的支持承诺。 |
| Observed only | 只有一次观察记录，不构成兼容性保证，也不是持续维护的测试矩阵条目。 |
| Known incompatible | 当前构建或设计明确不能在该环境运行。 |
| Unknown | 缺少足够的可重复证据，无法作出兼容性结论。 |

不要把 `Observed only` 或 `Tested` 理解为 `Supported`，也不要根据表中两个版本推断其间版本一定兼容。

## 兼容性矩阵

| 范围 | 环境或版本 | 状态 | 证据与限制 |
| --- | --- | --- | --- |
| Android 系统 | Android 8.0 / API 26 及以上 | Supported | `minSdk` 为 26。当前尚未形成覆盖所有设备、厂商和 Android 版本的完整人工矩阵。 |
| Android 系统 | 低于 Android 8.0 / API 26 | Known incompatible | 低于 `minSdk`，APK 无法安装。 |
| 发布 ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64` | Supported | Release workflow 为每个 ABI 构建独立 APK，并静态校验 ABI 内容。 |
| 发布 ABI | `x86`、`riscv64` 和其他未列出的 ABI | Known incompatible | 当前发布流程不提供匹配 APK，也不提供 universal APK。 |
| 构建环境 | Ubuntu 24.04、JDK 21、Go 1.26.4、SDK 36、Build Tools 36.0.0、NDK 27.1.12297006 | Tested | GitHub Actions CI 与 Release workflow 固定使用这套工具链。 |
| OpenCode Server | `1.17.7` | Observed only | 编写 Web UI 交互文档时曾观察到健康检查返回该版本。这不是服务端支持声明，也不是端到端回归套件结果。 |
| OpenCode Server | 其他任意版本 | Unknown | API 兼容性仍在演进，当前没有持续维护的服务端版本矩阵。 |
| HTTP(S) 直连 | URL 有效且可访问的服务端，可选 Basic 认证 | Supported | 客户端已实现该连接模式；网络、TLS、认证和服务端行为仍取决于实际部署。 |
| SSH 转发 | 受支持 Android 设备与兼容 SSH 端点 | Supported | 客户端已实现本地转发、host key 校验、密码/私钥模式及相应状态单测；尚无广泛设备和服务端互操作记录。 |
| STCP bridge 制品 | 固定 GoMobile bridge `0.3.2-frp0.69.1-p1` | Tested | 自动门禁校验 checksum、Java API、provenance、ABI、ELF machine、16KB `PT_LOAD` 对齐、stripped 状态和可复现性。 |
| Native 加载 | 每个发布 ABI 对应的目标真机 | Unknown | 尚未完成要求的真机 native load 验证。静态 ELF 和 APK 检查不能替代真机测试。 |
| 16KB page size | 使用 16KB page size 的 Android 真机 | Unknown | 已有静态对齐检查，但尚未完成真机验证。 |
| STCP 端到端 | Android 真机经真实 frps/STCP 连接 OpenCode Server | Unknown | readiness 和 control epoch 逻辑已实现并有单测，但要求的真实 STCP 闭环尚未完成。 |

## 服务端兼容性建议

- 应通过 OC Deck 实际使用的直连、SSH 或 STCP 路径检查服务端 `GET /global/health`。
- 健康检查成功只能证明可达，不能证明 OC Deck 使用的全部 API 都兼容。
- 已设计为前向兼容的 DTO 会容忍未知 JSON 字段，但端点行为、必填字段和事件语义仍可能变化。
- ABI 选择、侧载、更新要求、破坏性回退和卸载数据影响应遵循安装指南。
- 升级任意一端前，请检查[变更日志](../../CHANGELOG.md)、对应版本发布说明和[弃用策略](../maintainers/deprecation-policy.zh-CN.md)。

## 报告兼容性结果

请通过 canonical 仓库 <https://github.com/ycfeng/ocdeck-android> 报告不含敏感信息的结果。应包含 OC Deck 版本或 commit、Android 版本、设备型号与 ABI、OpenCode Server 版本、连接方式和已脱敏复现步骤。不得包含凭据、私钥、host fingerprint、私有服务器 URL、项目内容、prompt 或未脱敏日志。
