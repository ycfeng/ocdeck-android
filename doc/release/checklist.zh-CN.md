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
- [ ] 社区/文档与静态第三方/法律清单审计已在不依赖 Go generated input 的情况下通过。
- [ ] 精确候选版本的 `:frpc-stcp-visitor:frpcInteropTest` 已使用固定官方 frp asset 通过，包括 wire v1/v2、四种 payload 模式、并发双向超窗口流、长期 global/project SSE、负例和重启/control epoch 恢复。
- [ ] 普通纯 Kotlin 任务已通过：`:app:testDebugUnitTest`、`:app:testCanaryUnitTest`、`:app:testKotlinReleaseUnitTest`、`:app:testReleaseUnitTest`、`:frpc-stcp-visitor:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleCanary`、`:app:assembleKotlinRelease`、`:app:assembleKotlinReleaseAndroidTest`、`:app:assembleRelease`、`:app:verifyPureKotlinPackaging` 与 `:app:verifyReleasePackaging`。Variant factory 测试确认 Debug/Kotlin、Canary/Kotlin、Kotlin Release-Like/Kotlin 与正式 Release/Kotlin 选择；每个 Kotlin runtime classpath 和 ABI APK 都排除 GoMobile bridge 与 `libgojni.so`。
- [ ] 如果 bridge/frp/GoMobile adapter/build 路径发生变化，或该候选使用 `gomobile-emergency-release`，GoMobile 保留 workflow 或等价显式运行已通过：固定 `preparefrp`、完整第三方 Go module 审计、外层与 patched-frp `go test -race`、不同 checkout 路径可复现性、AAR checksum/API/provenance/BuildInfo/ABI/ELF/法律检查、`testGoMobileReleaseUnitTest`、`testGoMobileEmergencyReleaseUnitTest`、`assembleGoMobileRelease`、`assembleGoMobileReleaseAndroidTest` 和强制 bridge 门禁。否则本项明确记录为不适用。
- [ ] 如果该候选使用 `gomobile-emergency-release`，受保护签名 profile 已通过 `:app:testGoMobileEmergencyReleaseUnitTest`、`:app:assembleGoMobileEmergencyRelease` 和 GoMobile 专用制品检查。否则本项明确记录为不适用。
- [ ] 如果该候选用于评估 K7/Kotlin 默认装配，已对其精确完整 commit SHA 运行 `K6V Android STCP Interop`：可直接手动触发，首次引入时也可通过人工显式启用的 CI reusable bootstrap。API 26 x86_64 `compat` lane 精确通过 `success-v1-00`；API 36 x86_64 `full` lane 通过全部八个 wire/加密/压缩成功 profile、错误 token、错误 STCP secret、bind 冲突和 `restart-v2-11`。Renderer preflight tests 已通过，fail-closed 双语报告与规范化 evidence 完整校验 suite/profile identity、参数、`gomobile,kotlin` 顺序、精确 checks、等价性和 `authorizesKotlinDefault=false`，而不是依赖 Gradle task success。否则本项明确记录为不适用。
- [ ] 已审查 K6V 报告限制：模拟器证据不能替代分离的目标 ABI 真机与 16KB runtime lane、App 的真实 Store/快照/reconciliation 链路、Doze/网络与前后台切换、性能、资源泄漏、长期 soak、正式稳定发布周期或发布设备 STCP 证据；这些仍是 Kotlin 默认决策前的必要条件。两个设备 lane 可以使用不同设备；无法获得物理 ARM 16KB 硬件记录为残余风险，而不是 K7 开发阻塞项。
- [ ] Canary 与 Kotlin Release-Like APK 只作为无 bridge 验证输出，GoMobile Release-Like 只作为带 suffix、使用 Debug 证书的对比输出；三者均未暂存或发布，正式 Debug 与 Release 保持无 bridge 的 Kotlin 构建。
- [ ] 如果该候选用于评估 K7/Kotlin 默认装配，exact-candidate App E2E artifact 已绑定干净的小写 commit/tree、本地与已安装 target/test APK SHA-256 身份、清理成功和 `authorizesKotlinDefault=false`。External 证据精确包含 17 个有序 instrumentation checks，以及 Host 断言 `configured_pinned_public_server` 与 `network_restored`。否则本项明确记录为不适用。
- [ ] 如果该候选用于评估 K7/Kotlin 默认装配，已在批准的设备矩阵上运行对称 GoMobile/Kotlin `baseline`、重连/并发 `stress`、`leak` 与长期 `soak` profile，并使用 Host 校验 App result schema 12 后生成的 summary schema 10 有界证据通过 `k7-resource-v4`；历史 schema-9 summary 仍可作为诊断输入，但不满足 v4 生命周期契约。否则本项明确记录为不适用。
- [ ] 所选 allowlist profile 的签名制品校验已通过。公共检查覆盖 output metadata 与实际 APK manifest 的 application ID/版本、单 signer、预期证书指纹、ABI 隔离、`apksigner`、`zipalign -P 16`、全部 native library、法律资产、文件名和 `SHA256SUMS`；`gomobile-emergency-release` 还要求 bridge class 与 sidecar 绑定的 `libgojni.so`，`kotlin-release` 则拒绝二者。

## K7 资源验收

`k7-resource-v4` 是当前有版本号的 K7 资源策略。`:frpc-stcp-visitor:frpcValidateAppResourceEvidence` 可对已经生成的证据对执行该策略；`frpcAppResourceTest` 会先生成证据对，再应用同一策略。它只接受 Host 校验 App result schema 12 后生成的 summary schema 10；App schema 版本属于生成前提，不会作为 summary 字段重复写入。每个通过的 non-functional result 必须包含有界的 `proc-task-lifecycle-v1` 16 桶 presence histogram，覆盖 `start`、首次 `visitorReady`、`postCleanup` 和 90 秒 `quiescence`，且各检查点线程总数必须与 histogram 精确一致。证据不含线程 ID、名称、hash、stack、等待通道、命令行或其他线程身份。显式 runtime 诊断还可保留固定的 FD 匿名类别与聚合计数，但绝不保留 descriptor 编号、路径、endpoint 或原始符号链接目标；Host 会拒绝未知 runtime-count key 和负数值。历史 schema-9 summary 仍可作为诊断输入，但 v4 验收会 fail closed。策略通过仍只属于证据，绝不会把 `authorizesKotlinDefault` 设为 `true`。

- [x] 维护者已显式授权 K7，并将正式 Debug 与 Release 装配切换为纯 Kotlin backend。这是审查后的人工决定，不是任何 evidence artifact 的自动授权；全部历史证据仍保留 `authorizesKotlinDefault=false`，且不会因本清单记录而被追改。下文独立的不兼容 signer 与较低 `versionCode` 负向安装门禁仍然必须执行。

- [x] 既有 API 31、4 KiB 物理 `arm64-v8a` 性能设备完整保留一组 `baseline`，以及 `stress`、`leak` 各三组成对证据，不得只挑选有利结果。重复 profile 通过 `ocdeck.frp.appResource.backendOrder` 依次使用 `gomobile,kotlin`、`kotlin,gomobile`、`gomobile,kotlin`。记录并保持充电状态、thermal 状态、电源模式、无线网络、后台负载和测试服务器位置可比。受 thermal throttling 或无关负载影响的运行必须在查看结果前记录拒绝原因，并完整重跑。
- [x] 独立的官方 API 36 16KB x86_64 lane 覆盖 runtime 与 page-size 安全。它不能用 emulator CPU 数值替代 API 31 真机性能证据。公开 ABI 加载和 external-network 覆盖继续由各自独立门禁约束；无法获得物理 ARM 16KB 覆盖继续作为披露的残余风险，而不是额外 K7 门禁。
- [x] 对任一 backend 持续至少 60 秒的 `stress`、`leak` 和 `soak` 证据对，Kotlin 进程 CPU 不超过 GoMobile 1.75 倍时正常通过。CPU 大于 1.75 倍且不超过 2.00 倍硬上限时，只有 wall duration 不超过 1.05 倍且纯最后四分之一窗口平均 PSS 不超过 GoMobile 1.00 倍才通过。独立的普通硬上限仍为 wall 1.25 倍、最后四分之一窗口平均 PSS 1.25 倍再加 16 MiB。`baseline` 仍是短时结构/安全 profile，不能单独用于比较授权。
- [x] 每份证据都使用 `head-tail-v1` 和计划 sample 的 1-based ordinal，证明精确保留最多最初 127 个与最新 127 个 sample；样本数量足够时两个窗口各精确包含 127 个，并带匹配的 observed/head/tail/dropped metadata。非 soak 运行完成全部配置 iterations，soak 完成正数且有界的前缀，reconnect 数精确，且成对 backend 的 workload result 完全一致。两个 backend 的最后四分之一窗口平均 FD 和线程数都不超过 256。对 `leak` 与 `soak`，最后四分之一减去最初四分之一的平均增长：PSS 不超过 8 MiB 或 15%，Java/native heap 各不超过 8 MiB 或 25%，FD 不超过 4，线程不超过 8。每个 non-functional workload 还必须在清理后静默 90 秒并记录 snapshot。相对 workload 前 start，两个 backend 的 FD 增量都不超过 8，其中超过 4 的增量必须作为 warning 保留并审查；Kotlin 线程增量不超过 8；GoMobile 线程增量不超过 16，其中超过 8 的增量必须作为 warning 保留并审查。每份保留证据对都必须通过，warning 必须审查，不能用 median 或平均值掩盖失败运行。
- [x] API 31 物理性能设备完成一组 8 小时成对 soak。harness 仍允许最长 24 小时诊断，但 `k7-resource-v4` 不要求 24 小时验收运行。
- [x] 已构建更高 `versionCode` 的 GoMobile emergency build 并覆盖安装 Kotlin 候选，确认 DataStore、Keystore 支持的 credential reference、服务器配置和更新身份保持兼容。维护者已记录回滚触发条件、负责人、响应目标与构建/签名/发布流程；回滚不依赖 Android downgrade 安装。

Kotlin 成为正式默认后，`v0.3.0` 已完成 K10 稳定周期条件，`v0.4.0` 又完成了一轮稳定周期。GoMobile 保留验证不再阻塞普通 CI 或稳定 Kotlin 发布。

## 设备与连接门禁

目标 ABI 真机 lane 与 API 36 16KB runtime lane 可以使用不同设备。官方 Android 16KB 测试镜像或真机可以提供 runtime page size、启动和适用互操作证据，但必须结合目标 ABI 真机加载以及静态对齐/打包门禁。缺少物理 ARM 16KB 覆盖继续作为披露的残余风险，而不是 K7 门禁。

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

## 手动签名运行

- [ ] 需要手动签名运行时，从 `main` 运行 `workflow_dispatch`，并显式审查 `artifact_profile` choice：正式纯 Kotlin profile 使用 `kotlin-release`，`gomobile-emergency-release` 只用于受保护的 emergency 演练。
- [ ] 所选 profile 使用正式 application ID、稳定版本、Release signer 与证书；`kotlin-release` 通过 `verifyReleasePackaging`，`gomobile-emergency-release` 使用 `goMobileEmergencyRelease` 及 GoMobile 专用制品检查。
- [ ] 手动运行已上传所选签名制品和最终可审阅 `release-notes.md` artifact，并只保留三天。
- [ ] 历史 evidence 仍记录 `authorizesKotlinDefault=false`；当前 K7 切换只由上文记录的维护者显式决定授权，不追改或重新解释 evidence artifact。
- [ ] 手动运行说明明确写明 GitHub 自动变更列表只在真实 tag 发布时追加。
- [ ] 维护者已审查精确 APK 文件名、checksum、策划说明、兼容性声明、已知问题和法律/安全链接。
- [ ] 手动运行没有创建 GitHub Release。

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
