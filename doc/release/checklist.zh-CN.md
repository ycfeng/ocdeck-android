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
- [ ] `:app:testDebugUnitTest`、`:app:testCanaryUnitTest`、`:app:testGoMobileReleaseUnitTest`、`:app:testKotlinReleaseUnitTest`、`:frpc-stcp-visitor:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleCanary`、`:app:assembleGoMobileRelease`、`:app:assembleGoMobileReleaseAndroidTest`、`:app:assembleKotlinRelease`、`:app:assembleKotlinReleaseAndroidTest`、`:app:verifyPureKotlinPackaging` 和强制 bridge 门禁已通过；variant factory 测试确认 Debug/GoMobile、Canary/Kotlin、GoMobile Release-Like/GoMobile 与 Kotlin Release-Like/Kotlin 选择，两个共享 Release-Like AndroidTest APK 均已编译，打包门禁确认两个 Kotlin runtime classpath 和全部 ABI APK 都排除 GoMobile bridge 与 `libgojni.so`。
- [ ] 如果该候选用于评估 K7/Kotlin 默认装配，已对其精确完整 commit SHA 运行 `K6V Android STCP Interop`：可直接手动触发，首次引入时也可通过人工显式启用的 CI reusable bootstrap。API 26 x86_64 `compat` lane 精确通过 `success-v1-00`；API 36 x86_64 `full` lane 通过全部八个 wire/加密/压缩成功 profile、错误 token、错误 STCP secret、bind 冲突和 `restart-v2-11`。Renderer preflight tests 已通过，fail-closed 双语报告与规范化 evidence 完整校验 suite/profile identity、参数、`gomobile,kotlin` 顺序、精确 checks、等价性和 `authorizesKotlinDefault=false`，而不是依赖 Gradle task success。否则本项明确记录为不适用。
- [ ] 已审查 K6V 报告限制：模拟器证据不能替代分离的目标 ABI 真机与 16KB runtime lane、App 的真实 Store/快照/reconciliation 链路、Doze/网络与前后台切换、性能、资源泄漏、长期 soak、正式稳定发布周期或发布设备 STCP 证据；这些仍是 Kotlin 默认决策前的必要条件。两个设备 lane 可以使用不同设备；无法获得物理 ARM 16KB 硬件记录为残余风险，而不是 K7 开发阻塞项。
- [ ] Canary 与 Kotlin Release-Like APK 只作为无 bridge 验证输出，GoMobile Release-Like 只作为带 suffix、使用 Debug 证书的对比输出；三者均未暂存或发布。
- [ ] 如果该候选用于评估 K7/Kotlin 默认装配，exact-candidate App E2E artifact 已绑定干净的小写 commit/tree、本地与已安装 target/test APK SHA-256 身份、清理成功和 `authorizesKotlinDefault=false`。External 证据精确包含 17 个有序 instrumentation checks，以及 Host 断言 `configured_pinned_public_server` 与 `network_restored`。否则本项明确记录为不适用。
- [ ] 如果该候选用于评估 K7/Kotlin 默认装配，已在批准的设备矩阵上运行对称 GoMobile/Kotlin `baseline`、重连/并发 `stress`、`leak` 与长期 `soak` profile，并使用 summary schema 10 / App result schema 11 有界证据通过 `k7-resource-v2`；历史 schema-9 summary 仍是 CPU No-Go 输入，但不满足 v2 生命周期契约。否则本项明确记录为不适用。
- [ ] 所选 allowlist profile 的签名制品校验已通过。公共检查覆盖 output metadata 与实际 APK manifest 的 application ID/版本、单 signer、预期证书指纹、ABI 隔离、`apksigner`、`zipalign -P 16`、全部 native library、法律资产、文件名和 `SHA256SUMS`；GoMobile 还要求 bridge class 与 sidecar 绑定的 `libgojni.so`，Kotlin-default 则拒绝二者。

## K7 资源验收

`k7-resource-v2` 是当前有版本号的 K7 资源策略。`:frpc-stcp-visitor:frpcValidateAppResourceEvidence` 可对已经生成的证据对执行该策略；`frpcAppResourceTest` 会先生成证据对，再应用同一策略。它只接受 summary schema 10（内嵌 App result schema 11）。每个通过的 non-functional result 必须包含有界的 `proc-task-lifecycle-v1` 16 桶 presence histogram，覆盖 `start`、首次 `visitorReady`、`postCleanup` 和 90 秒 `quiescence`，且各检查点线程总数必须与 histogram 精确一致。证据不含线程 ID、名称、hash、stack、等待通道、命令行或其他线程身份。历史 schema-9 summary 仍是有效 CPU No-Go 证据，但 v2 验收会 fail closed。策略通过仍只属于证据，绝不会把 `authorizesKotlinDefault` 设为 `true`。

- [ ] 每条物理性能 lane 都完整保留三组成对 `baseline`、`stress` 和 `leak`，不得只挑选有利结果。通过 `ocdeck.frp.appResource.backendOrder` 交替使用 `gomobile,kotlin`、`kotlin,gomobile`、`gomobile,kotlin` 顺序；记录并保持充电状态、thermal 状态、电源模式、无线网络、后台负载和测试服务器位置可比。受 thermal throttling 或无关负载影响的运行必须在查看结果前记录拒绝原因，并完整重跑。
- [ ] 批准的矩阵包含既有 API 31、4 KiB 物理 `arm64-v8a` 基准设备，以及维护者可获得的最新 Android API 物理 `arm64-v8a` 设备。官方 API 36 16KB x86_64 lane 负责 page-size/runtime 安全，但 emulator CPU 数值不能替代真机性能。公开 ABI 加载和 external-network 覆盖继续由各自独立门禁约束，并披露残余风险。
- [ ] 对任一 backend 持续至少 60 秒的 `stress`、`leak` 和 `soak` 证据对，Kotlin 进程 CPU 不超过 GoMobile 的 1.50 倍，wall duration 不超过 1.25 倍，最后四分之一窗口平均 PSS 不超过 GoMobile 的 1.25 倍再加 16 MiB。`baseline` 仍是短时结构/安全 profile，不能单独用于比较授权。
- [ ] 每份证据都使用 `head-tail-v1` 和计划 sample 的 1-based ordinal，证明精确保留最多最初 127 个与最新 127 个 sample；样本数量足够时两个窗口各精确包含 127 个，并带匹配的 observed/head/tail/dropped metadata。非 soak 运行完成全部配置 iterations，soak 完成正数且有界的前缀，reconnect 数精确，且成对 backend 的 workload result 完全一致。两个 backend 的最后四分之一窗口平均 FD 和线程数都不超过 256。对 `leak` 与 `soak`，最后四分之一减去最初四分之一的平均增长：PSS 不超过 8 MiB 或 15%，Java/native heap 各不超过 8 MiB 或 25%，FD 不超过 4，线程不超过 8。每个非 functional workload 还必须在清理后静默 90 秒并记录 snapshot；相对 workload 前 start，该 snapshot 的 FD 增量不超过 4、线程增量不超过 8。每份保留证据对都必须通过，不能用 median 或平均值掩盖失败运行。
- [ ] 每条必需物理 lane 都完成一组 8 小时成对 soak，主基准设备再完成一组 24 小时成对 soak。默认 `soak` profile 为 8 小时；24 小时运行显式设置 `ocdeck.frp.appResource.durationMillis=86400000`。
- [ ] K7 授权前，Kotlin 候选必须完成至少连续 14 天内部/Canary 观察，期间不触发资源、连接、安全或数据一致性回滚条件。Kotlin 成为正式默认后，K10 仍要求再完成一个完整稳定公开发布周期。
- [ ] 已构建更高 `versionCode` 的 GoMobile emergency build 并覆盖安装 Kotlin 候选，确认 DataStore、Keystore 支持的 credential reference、服务器配置和更新身份保持兼容。维护者已记录回滚触发条件、负责人、响应目标与构建/签名/发布流程；回滚不依赖 Android downgrade 安装。

## 设备与连接门禁

目标 ABI 真机 lane 与 16KB runtime lane 可以使用不同设备。官方 Android 16KB 测试镜像或真机可以提供 runtime page size、启动和适用互操作证据，但必须结合目标 ABI 真机加载以及静态对齐/打包门禁。物理 ARM 16KB 设备仍是理想的组合覆盖，而不是 K7 开发的前置条件。

- [ ] 每个可获得真机的目标 ABI 都已首次安装对应签名 ABI APK，并成功启动。
- [ ] 已从上一个真实公开版本执行兼容覆盖更新，并确认预期本地数据保留；对于首个公开版本，应明确记录为不适用，而不是推断通过。
- [ ] 已确认不兼容 signer 和较低 `versionCode` 会按预期被拒绝，并对照候选版本审查文档中的破坏性回退路径。
- [ ] 已确认卸载会按文档删除应用私有本地数据，重新安装表现为新的本地安装，并且没有作出不受支持的导出/恢复声明。
- [x] 已在可获得的 `arm64-v8a` 真机上测试 native load 和应用启动。
- [ ] 如果发布声明依赖其他公开 ABI，已在对应真机测试 native load；无法测试的 ABI 限制已经披露。
- [x] 已在使用 16KB page size 的 Android runtime 上通过 native load 和启动验证，可使用官方 Android 16KB 测试镜像或真机；已记录的 `0.1.0` 证据使用物理硬件。
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

- [ ] 需要签名 dry-run 时，从 `main` 运行 `workflow_dispatch`，并显式审查 `run_kotlin_default_release_dry_run` 的取值。
- [ ] 普通 GoMobile dry run 使用 `false`；K7 打包评估使用 `true` 时只生成不可发布的 Kotlin-default artifact profile。
- [ ] Dry-run 已上传所选签名制品和最终可审阅 `release-notes.md` artifact，并只保留三天。
- [ ] 选择 Kotlin-default dry run 时，制品使用正式 application ID/版本/证书，通过 `verifyKotlinDefaultReleaseDryRunPackaging`，保持 `authorizesKotlinDefault=false`，且未被视为稳定周期或 backend 切换授权。
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
