# 文档维护策略

[English](documentation-policy.md)

本文档是英文 canonical 文档维护策略的便利翻译。中英文发生差异时，以英文文档为准。

## 语言模式

- 公开文档以英文作为 canonical 来源。
- 配对的 `.zh-CN.md` 文件必须是完整的简体中文便利翻译，不能只是摘要。中文文件必须注明便利翻译身份并链接回英文文件。
- 事实、命令、状态、安全约束或链接发生变化时，配对文件必须在同一变更中更新并互相链接。
- 中文翻译与英文来源不一致时，以英文为准，但仍必须尽快修复差异。
- 有意采用单文件双语的例外只有根目录 `CHANGELOG.md`、`.github/release-notes-template.md` 和 `release-notes/` 下的文件。
- 根目录 `AGENTS.md` 与 `AGENTS.zh-CN.md` 遵循同一 canonical/翻译模式。修改任一文件时，包括用户只要求修改一种语言的情况，必须在同一次变更中同步更新两者并保持语义一致。

## 信息架构

文档索引严格使用五个公开分类：

- User
- Development
- Architecture
- Maintainers
- Release

在 `doc/README.md` 与 `doc/README.zh-CN.md` 中把新文档放入适用范围最窄的分类。不要创建互相竞争的索引或重复 canonical 指南。

## 状态与准确性

- 已实现、部分完成、占位和未实现能力必须分别描述。
- 不得把一次观察提升为支持声明。兼容性文档必须使用 `Supported`、`Tested`、`Observed only`、`Known incompatible` 或 `Unknown`，并明确证据。
- 按适用情况持续公开已知缺口，包括 Provider 持久化、服务端文件加入 Composer、独立 Review、Shell、分享、workspace/worktree、PTY/terminal，以及缺少真机 STCP/16KB 验证。
- 版本必须来自 `gradle.properties`、`gradle/libs.versions.toml` 和 `frpc-stcp-visitor-go/bridge-versions.properties` 等唯一来源，不得估算。
- 只有已审计端点具备硬上限时，不得概括为所有入站网络数据都已封闭。

## 链接与版本化

- 同一 revision 内的仓库文件使用相对链接。
- 公开文档使用 <https://github.com/ycfeng/ocdeck-android> 作为 canonical 仓库 URL。
- 发布说明中的版本文档、隐私、支持、许可证、通知和商标文件必须固定链接到 release tag，避免后续修改重写历史上下文。
- 安全策略可以链接到仓库最新版本，因为发布后的报告方式仍可能变化。
- 移动文档时，应在同一集成变更中更新全部入站链接并删除旧路径，除非确实需要兼容重定向。

## 安全与隐私

- 文档、示例、截图、fixture、日志和发布说明不得包含真实 API key、token、密码、cookie、私钥、passphrase、provider header、环境变量、host fingerprint、私有服务器 URL、项目内容、prompt 或敏感原始响应。
- 敏感示例值使用 `<redacted>`，路径、ID、host 和 payload 使用明显的合成值。
- 不得发布签名 keystore、证书材料、密码、alias、Base64 keystore 或测试证书细节。
- 截图与文字接受同等审查，并且必须反映当前 Android UI。

## 发布文档职责

- `CHANGELOG.md` 持续记录重要仓库变更、弃用、移除、安全相关变化和迁移义务。
- `release-notes/vX.Y.Z.md` 是单个版本面向用户的双语策划说明，包含兼容性与已知问题。
- GitHub 自动生成说明在发布时追加 Pull Request 列表，不能替代版本策划文件或变更日志。
- `.github/release-notes-template.md` 定义必需章节。空章节必须明确写 `None / 无`。
- 最终发布说明不得残留 `TODO`、`TBD` 或未解析的 `{{...}}` 占位符。

## 许可证

OC Deck 原创文档使用与仓库原创代码相同的 [MIT License](../../LICENSE)。引用或改编的第三方材料必须保留适用的署名、许可证与通知要求。

## 审查清单

- 事实与当前代码、workflow 和版本唯一来源一致。
- 中英文配对文档完整且互相链接。
- 命令可在所述工作目录和平台运行。
- 相对链接可解析，移动路径没有陈旧入站引用，release 链接使用正确 tag。
- 兼容性措辞不超过现有证据。
- 安全、隐私、法律、商标和第三方声明与发布内容一致。
- 不含 secret、私有数据、未解析占位符或未经审查的截图。
