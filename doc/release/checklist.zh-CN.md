# 发布检查清单

[English](checklist.md)

本文档是英文 canonical 发布检查清单的完整便利翻译。勾选项只代表一个候选版本的证据；应保留相关 workflow run、设备记录和审查上下文。

## 候选版本范围

- [ ] `gradle.properties` 中的 `VERSION_NAME` 是稳定 SemVer，`VERSION_CODE` 已递增。
- [ ] `release-notes/v${VERSION_NAME}.md` 以单个双语文件存在，并包含模板要求的全部章节。
- [ ] `CHANGELOG.md` 已记录重要变化、迁移、弃用、移除和安全变化。
- [ ] 已实现、部分完成、占位和未实现功能的描述准确。
- [ ] 兼容性结论使用约定状态，没有把观察结果提升为支持声明。
- [ ] 每项正常 pre-1.0 弃用都至少提前一个公开版本公告；如使用安全/法律紧急例外，已记录原因。

## 文档与法律

- [ ] 英文 canonical 文档与完整 `.zh-CN.md` 便利翻译已经同步并互相链接。
- [ ] README、隐私、安全、支持、商标、通知和第三方清单按实际候选版本保持一致。
- [ ] 首次使用、安装、连接、排障和兼容性指南与候选版本一致，并已在所述平台审查其中的命令。
- [ ] 下载说明区分 workflow 上传的 APK、`SHA256SUMS` 与 GitHub 自动源码归档，完整性措辞没有夸大发布者身份验证能力。
- [ ] 已审查依赖、音效、Gradle wrapper、frp upstream/patch、provenance、源码归档、许可证和哈希。
- [ ] 原创文档继续使用仓库 MIT License；第三方材料保留自身署名和许可证。
- [ ] 最终发布说明不含 `TODO`、`TBD`、未解析 `{{...}}`、secret、私有数据、测试证书细节或未经审查的截图。

## 自动门禁

- [ ] 精确候选 commit 的 `main` CI 已通过。
- [ ] 使用固定输入完成 `go run ./cmd/preparefrp`。
- [ ] 外层 wrapper 与生成的 patched frp `client/...` race tests 已通过。
- [ ] 社区/文档与第三方/法律清单审计均已通过。
- [ ] 精确候选版本的 `:frpc-stcp-visitor:frpcInteropTest` 已使用固定官方 frp asset 通过，包括 wire v1/v2、四种 payload 模式、并发双向超窗口流、长期 global/project SSE、负例和重启/control epoch 恢复。
- [ ] Bridge 可复现门禁已在同一主机平台构建干净的候选 checkout，以及位于不同绝对路径的 detached checkout，并隔离 `GOCACHE`、`GOMODCACHE` 与 `GOPATH`；AAR、必需的 sources JAR、POM、checksum、API、bridge/frp provenance 和 native sidecar 逐字节一致。
- [ ] AAR checksum、精确 Java API、bridge/frp provenance、四 ABI BuildInfo module identity/version/sum、无本地路径与跨 ABI graph digest 证明、预期 ABI、ELF machine、16KB `PT_LOAD` 对齐、stripped 状态、内嵌法律文件和 sidecar 已通过。
- [ ] `:app:testDebugUnitTest`、`:app:testCanaryUnitTest`、`:frpc-stcp-visitor:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleCanary` 和强制 bridge 门禁已通过；variant factory 测试确认 Debug/GoMobile 与 Canary/Kotlin 选择。
- [ ] 如果该候选用于评估 K7/Kotlin 默认装配，已对其精确完整 commit SHA 运行 `K6V Android STCP Interop`：可直接手动触发，首次引入时也可通过人工显式启用的 CI reusable bootstrap。API 26 x86_64 `compat` lane 精确通过 `success-v1-00`；API 36 x86_64 `full` lane 通过全部八个 wire/加密/压缩成功 profile、错误 token、错误 STCP secret、bind 冲突和 `restart-v2-11`。Renderer preflight tests 已通过，fail-closed 双语报告与规范化 evidence 完整校验 suite/profile identity、参数、`gomobile,kotlin` 顺序、精确 checks、等价性和 `authorizesKotlinDefault=false`，而不是依赖 Gradle task success。否则本项明确记录为不适用。
- [ ] 已审查 K6V 报告限制：模拟器证据不能替代物理目标 ABI 与 16KB native load、App 的真实 Store/快照/reconciliation 链路、Doze/网络与前后台切换、性能、资源泄漏、长期 soak、正式稳定发布周期或发布设备 STCP 证据；这些仍是 Kotlin 默认决策前的必要条件。
- [ ] Canary APK 仅作为验证输出；Release 签名与发布暂存只使用 GoMobile 默认的 `assembleRelease` 输出。
- [ ] 签名 Release APK 的 metadata、单 signer、预期证书指纹、ABI 隔离、`apksigner`、`zipalign -P 16`、native 字节绑定、法律资产、文件名和 `SHA256SUMS` 校验已通过。

## 真机与连接门禁

- [ ] 每个可获得真机的目标 ABI 都已首次安装对应签名 ABI APK，并成功启动。
- [ ] 已从上一个真实公开版本执行兼容覆盖更新，并确认预期本地数据保留；对于首个公开版本，应明确记录为不适用，而不是推断通过。
- [ ] 已确认不兼容 signer 和较低 `versionCode` 会按预期被拒绝，并对照候选版本审查文档中的破坏性回退路径。
- [ ] 已确认卸载会按文档删除应用私有本地数据，重新安装表现为新的本地安装，并且没有作出不受支持的导出/恢复声明。
- [x] 已在可获得的 `arm64-v8a` 真机上测试 native load 和应用启动。
- [ ] 如果发布声明依赖其他公开 ABI，已在对应真机测试 native load；无法测试的 ABI 限制已经披露。
- [x] 已在使用 16KB page size 的 Android 真机上通过 native load 和启动验证。
- [x] 已通过真实 frps/STCP visitor 路径达到 listener readiness，并经隧道完成 `/global/health`。
- [x] 同一真实 STCP 路径已覆盖代表性 REST、全局/项目 SSE，并在可行时验证重连或 control epoch 恢复。
- [ ] 已通过 App 实际连接路径对 HTTP(S) 直连和 SSH 进行 smoke test。
- [x] 受控断开/重连 smoke test 期间已有 UI 数据保持可用。

维护者已记录上述勾选的 `0.1.0` native load、16KB page-size 和真实 STCP 门禁具有通过的外部证据。具体设备与部署信息未公开。该候选版本记录不能覆盖其他未勾选项目，也不能免除后续候选版本的同类门禁。

## 签名与仓库控制

- [ ] 受保护的 `release` Environment 已配置 required reviewers，并且只包含四个签名 secret 和 `RELEASE_CERT_SHA256`。
- [ ] 发布 JKS 有受控离线备份，从未被提交、缓存、记录日志或上传为 artifact。
- [ ] 预期证书是用于保持 GitHub/Google Play 更新连续性的既有 app-signing certificate。
- [ ] 分支保护、`v*` tag 保护、可信且固定 SHA 的 action、默认只读权限和 immutable releases 已启用。
- [ ] Tag commit 是精确候选 commit，并且是 `origin/main` 的祖先。

## Dry Run

- [ ] 需要签名 dry-run 时，从 `main` 运行 `workflow_dispatch`。
- [ ] Dry-run 已上传签名发布制品和最终可审阅 `release-notes.md` artifact。
- [ ] Dry-run 说明明确写明 GitHub 自动变更列表只在真实 tag 发布时追加。
- [ ] 维护者已审查精确 APK 文件名、checksum、策划说明、兼容性声明、已知问题和法律/安全链接。
- [ ] Dry-run 没有创建 GitHub Release。

## Tag 发布

- [ ] 只有全部阻断门禁通过后才创建并推送精确的 `v${VERSION_NAME}`。
- [ ] Tag 触发的 `preflight`、`prepare-notes`、`frpc-interop` 和 `build-release` 均已成功，且互操作 job 始终位于受保护的签名 Environment 之外。
- [ ] 发布说明 artifact 按顺序包含双语前言、版本策划文件和 GitHub 自动说明，并且没有重复 `## Changes` 标题。
- [ ] `publish` 下载准备好的说明与已验证制品，没有重新生成任一内容。
- [ ] `0.x` 版本标记为 prerelease；`1.0.0` 及以后遵循普通 release 规则。
- [ ] Workflow 上传到 GitHub Release 的 asset 精确为三个 ABI APK 和 `SHA256SUMS`，没有上传 universal APK、AAB、JKS 或签名材料；GitHub 自动源码归档没有被描述为可安装 asset。

## 发布后

- [ ] 从 GitHub 下载全部由 workflow 上传的公开制品并独立验证 `SHA256SUMS`，同时运行文档中的单 APK checksum 流程。
- [ ] 在匹配设备上安装重新从公开 Release 下载的 APK，并确认公开安装与首次使用链接可以访问。
- [ ] 确认固定到 tag 的文档、隐私、支持、许可证、通知和商标链接可以访问。
- [ ] 确认最新 security policy 链接指向预期的私密报告方式。
- [ ] 确认 Release 不可变，不需要替换 asset 或移动 tag。
- [ ] 如发现发布缺陷，不覆盖 Release、不移动 tag，而是准备新版本。
