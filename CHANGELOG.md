# Changelog / 变更日志

All notable changes to OC Deck are documented in this file. This project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and uses three-component [Semantic Versioning](https://semver.org/) version names, while all `0.x` versions remain prereleases.

OC Deck 的重要变化记录在本文件中。项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 结构并使用三段式 [Semantic Versioning](https://semver.org/lang/zh-CN/) 版本号；所有 `0.x` 版本仍视为 prerelease。

## Responsibilities / 职责

- This changelog records notable repository changes, deprecations, removals, security changes, and migration obligations over time. / 本变更日志持续记录重要仓库变化、弃用、移除、安全变化和迁移义务。
- `release-notes/vX.Y.Z.md` is the curated bilingual user-facing description for one version, including compatibility and known issues. / `release-notes/vX.Y.Z.md` 是单个版本面向用户的双语策划说明，包含兼容性和已知问题。
- GitHub-generated notes append the pull-request list at publication time. They supplement, but do not replace, this changelog or the curated version notes. / GitHub 自动说明在发布时追加 Pull Request 列表，只作为补充，不能替代本变更日志或版本策划说明。
- A version section without a release date is not evidence that a formal GitHub Release exists. / 没有发布日期的版本章节不代表已经存在正式 GitHub Release。

## [Unreleased] / 未发布

No changes have been assigned beyond the prepared `0.1.0` scope. / 尚无超出预备 `0.1.0` 范围的变更。

## [0.1.0] - Not yet formally released / 尚未正式发布

This section records the prepared first-release scope. It intentionally has no fabricated release date. A `v0.1.0` tag and GitHub Release are the formal publication record when they exist.

本章节记录预备的首个发布范围，不虚构发布日期。正式发布记录以实际存在的 `v0.1.0` tag 和 GitHub Release 为准。

### Added / 新增

- Community contribution guides, Contributor Covenant 2.1, CODEOWNERS, bilingual issue forms, a pull-request template, and GitHub-generated release-note categories. / 新增贡献指南、Contributor Covenant 2.1、CODEOWNERS、双语 issue forms、PR 模板和 GitHub 自动发布说明分类。
- Bilingual documentation hub and complete English guides for interaction design, architecture, compatibility, building, testing, fixtures, documentation maintenance, deprecation, and release operations. / 新增双语文档中心，以及交互设计、架构、兼容性、构建、测试、fixture、文档维护、弃用和 Release 操作的完整英文文档。
- One bilingual release-notes template and a prepared `0.1.0` release-notes file. / 新增单文件双语发布说明模板和预备的 `0.1.0` 发布说明。
- Native Android client shell for user-provided OpenCode Server connections on Android 8.0/API 26 and newer. / 面向用户自备 OpenCode Server 的原生 Android 客户端框架，最低 Android 8.0/API 26。
- Direct HTTP(S), SSH local forwarding, and embedded frpc STCP visitor server profiles. / 支持 HTTP(S) 直连、SSH 本地转发和内置 frpc STCP visitor 的服务器配置。
- Project selection, recent projects, session lists/details, lazy session creation, asynchronous prompts, rename/archive/delete, and revert/unrevert flows. / 支持项目选择、最近项目、会话列表/详情、懒创建会话、异步 prompt、重命名/归档/删除和 revert/unrevert。
- Global and project SSE synchronization with reconnect, snapshot reconciliation, generation/lease guards, and bounded event parsing. / 支持全局与项目 SSE、重连、快照校准、generation/lease 防护和有界事件解析。
- Slash commands, agent mentions, agent/model/variant pickers, local-device attachments, server project-file contexts, permission/question handling, context usage, and session Changes diff. / 支持 Slash command、agent mention、agent/model/variant picker、手机本地附件、服务端项目文件上下文、permission/question、上下文用量和会话 Changes diff。
- Read-only server project file browsing, search, bounded text/image preview, up-to-10 whole-file context selection, English and Simplified Chinese UI, themes, notifications, sounds, and background-run guidance. / 支持服务端项目文件只读浏览、搜索、有界文本/图片预览、最多 10 个完整文件上下文选择、中英文 UI、主题、通知、音效和后台运行引导。
- Fixed GoMobile bridge coordinate `io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:0.3.2-frp0.69.1-p1` with pinned toolchain, auditable frp patching, provenance, API checks, native checks, and reproducibility gates. / 使用固定 GoMobile bridge 坐标 `io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:0.3.2-frp0.69.1-p1`，具备固定工具链、可审计 frp patch、provenance、API、native 和可复现性门禁。

### Changed / 变更

- Unified launcher, splash, notification, and in-app identity surfaces around the new OC Deck console-control-panel logo, with Android-specific monochrome derivatives where required. / 使用新的 OC Deck 控制台面板 Logo 统一启动器、启动画面、通知和应用内身份标识界面，并在 Android 平台要求的场景使用对应的单色派生图标。
- Release automation now renders a reviewable bilingual notes artifact for both tag runs and manual dry runs; publication consumes the same artifact without regeneration. / Release 自动化现在会为 tag 与手动 dry-run 生成可审阅的双语说明 artifact，发布过程直接消费同一 artifact，不再重新生成。

### Security / 安全

- Added structured and fallback redaction, Android Keystore-backed credential references, strict server URL validation, SSH host-key verification, bounded private-key input, and transactional credential rotation rules. / 新增结构化与兜底脱敏、Android Keystore 凭据引用、严格服务器 URL 校验、SSH host key 校验、有界私钥输入和事务化凭据轮换规则。
- Added bounded session-message and SSE transports for the specifically audited endpoints, without claiming that every network endpoint is bounded. / 为已专项审计的 session messages 和 SSE 增加有界 transport，不概括为所有网络端点都已封闭。
- Added release gates for signer continuity, ABI isolation, native-byte binding, embedded legal material, and public checksums. / 新增 signer 连续性、ABI 隔离、native 字节绑定、内嵌法律材料和公开 checksum 发布门禁。

### Deprecated / 弃用

- None / 无。

### Removed / 移除

- None / 无。
