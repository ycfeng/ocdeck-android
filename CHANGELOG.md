# Changelog / 变更日志

All notable changes to OC Deck are documented in this file. This project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and uses three-component [Semantic Versioning](https://semver.org/) version names, while all `0.x` versions remain prereleases.

OC Deck 的重要变化记录在本文件中。项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 结构并使用三段式 [Semantic Versioning](https://semver.org/lang/zh-CN/) 版本号；所有 `0.x` 版本仍视为 prerelease。

## Responsibilities / 职责

- This changelog records notable repository changes, deprecations, removals, security changes, and migration obligations over time. / 本变更日志持续记录重要仓库变化、弃用、移除、安全变化和迁移义务。
- `release-notes/vX.Y.Z.md` is the curated bilingual user-facing description for one version, including compatibility and known issues. / `release-notes/vX.Y.Z.md` 是单个版本面向用户的双语策划说明，包含兼容性和已知问题。
- GitHub-generated notes append the pull-request list at publication time. They supplement, but do not replace, this changelog or the curated version notes. / GitHub 自动说明在发布时追加 Pull Request 列表，只作为补充，不能替代本变更日志或版本策划说明。
- A version section without a release date is not evidence that a formal GitHub Release exists. / 没有发布日期的版本章节不代表已经存在正式 GitHub Release。

## [Unreleased] / 未发布

No changes have been assigned beyond the `0.1.3` release. / 尚无超出 `0.1.3` 发布范围的变更。

## [0.1.3] - 2026-07-17

### Added / 新增

- Added reviewed English and Simplified Chinese README screenshots for both light and dark themes using constructed demonstration data. / 新增经过审查的英文与简体中文 README 截图，覆盖浅色和深色主题，并仅使用构造的演示数据。
- Added Android instrumentation coverage for localized Popup and modal bottom-sheet roots, including live language changes while a Popup remains open. / 新增 Android instrumentation 测试，覆盖本地化 Popup 与 modal bottom sheet 独立窗口根，以及 Popup 保持打开时的实时语言切换。

### Changed / 变更

- Repository automation now prefers the configured local OpenCode source and dedicated local Web test project before considering online references or state-changing browser inspection. / 仓库自动化现在优先使用已配置的本地 OpenCode 源码和专用本地 Web 测试项目，再考虑联网参考或会改变状态的浏览器检查。
- Incremented the immutable GoMobile bridge coordinate to `0.3.6-frp0.69.1-p1` because the AAR embeds the `0.1.3` legal inventory; Go, x/mobile, frp, Android API, and NDK versions are unchanged. / 由于 AAR 内嵌 `0.1.3` 法律清单，将不可变 GoMobile bridge 坐标提升为 `0.3.6-frp0.69.1-p1`；Go、x/mobile、frp、Android API 和 NDK 版本保持不变。

### Fixed / 修复

- Independent Compose Popup, dialog, and modal bottom-sheet windows now follow the selected app language, including an in-place language change while open. / 独立 Compose Popup、dialog 与 modal bottom sheet 窗口现在会跟随所选应用语言，并支持窗口保持打开时原地切换语言。
- Preserved the Activity Result registry owner under the localized composition root so activity-result launchers such as file selection remain available. / 在本地化组合根中保留 Activity Result registry owner，使文件选择等 activity-result launcher 保持可用。
- Aligned the project-path input text consistently within its field. / 修正项目路径输入文字在输入框内的对齐。

## [0.1.2] - 2026-07-17

### Changed / 变更

- Reopening the Composer Model and Reasoning pickers now brings the current selection into view and centers it when bounds allow, without overriding search or manual scrolling. / 重新打开 Composer 的模型与 Reasoning 选择器时，当前选项会自动滚动到可见区域并在边界允许时居中，同时不会覆盖搜索或手动滚动。
- Incremented the immutable GoMobile bridge coordinate to `0.3.5-frp0.69.1-p1` because the AAR embeds the `0.1.2` legal inventory; Go, x/mobile, frp, Android API, and NDK versions are unchanged. / 由于 AAR 内嵌 `0.1.2` 法律清单，将不可变 GoMobile bridge 坐标提升为 `0.3.5-frp0.69.1-p1`；Go、x/mobile、frp、Android API 和 NDK 版本保持不变。

### Fixed / 修复

- Restored the animated running-state indicator across project sessions, the session drawer, and subagent cards while preserving semantic colors and accessible contrast. / 恢复项目会话、会话抽屉和 subagent 卡片中的运行状态动画，同时保留语义颜色和无障碍对比度。
- Improved responsive alignment, multiline titles, scrollable attachment content, and the compact subagent read-only dock for small screens and large text. / 改善小屏和大字号下的对齐、多行标题、附件内容滚动及紧凑型 subagent 只读底栏。

## [0.1.1] - 2026-07-16

### Fixed / 修复

- Preserve each AAR-verified `libgojni.so` byte-for-byte in Release APKs instead of allowing Android Gradle Plugin to strip the already-stripped GoMobile library again. Existing release gates continue to verify native-byte binding, ELF machine, 16KB alignment, and stripped state. / Release APK 逐字节保留 AAR 已验证的 `libgojni.so`，不再允许 Android Gradle Plugin 对已经 stripped 的 GoMobile library 再次 strip；现有发布门禁继续校验 native 字节绑定、ELF machine、16KB 对齐和 stripped 状态。

### Changed / 变更

- Incremented the application version to `0.1.1` (`VERSION_CODE=2`) after the prepared `v0.1.0` tag did not produce a formal GitHub Release. / 在预备的 `v0.1.0` tag 未产出正式 GitHub Release 后，将应用版本提升为 `0.1.1`（`VERSION_CODE=2`）。
- Incremented the immutable GoMobile bridge coordinate to `0.3.4-frp0.69.1-p1` because the AAR now embeds the `0.1.1` legal inventory; the ABI-specific native library bytes remain unchanged. / 由于 AAR 现内嵌 `0.1.1` 法律清单，将不可变 GoMobile bridge 坐标提升为 `0.3.4-frp0.69.1-p1`；各 ABI 的 native library 字节保持不变。

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
- Recorded passing external release-gate validation for `0.1.0` physical-device native loading, 16KB page-size operation, and a real frps/STCP path covering health, REST, global/project SSE, and controlled reconnect, without broadening the result into a universal compatibility claim. / 已记录 `0.1.0` 真机 native load、16KB page-size 运行，以及覆盖健康检查、REST、全局/项目 SSE 和受控重连的真实 frps/STCP 路径通过外部发布门禁验证，但不将该结果扩大为普遍兼容性声明。

### Security / 安全

- Added structured and fallback redaction, Android Keystore-backed credential references, strict server URL validation, SSH host-key verification, bounded private-key input, and transactional credential rotation rules. / 新增结构化与兜底脱敏、Android Keystore 凭据引用、严格服务器 URL 校验、SSH host key 校验、有界私钥输入和事务化凭据轮换规则。
- Added bounded session-message and SSE transports for the specifically audited endpoints, without claiming that every network endpoint is bounded. / 为已专项审计的 session messages 和 SSE 增加有界 transport，不概括为所有网络端点都已封闭。
- Added release gates for signer continuity, ABI isolation, native-byte binding, embedded legal material, and public checksums. / 新增 signer 连续性、ABI 隔离、native 字节绑定、内嵌法律材料和公开 checksum 发布门禁。

### Deprecated / 弃用

- None / 无。

### Removed / 移除

- None / 无。
