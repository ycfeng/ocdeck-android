# OC Deck GitHub Actions 发布流程

[English](github-actions.md)

本文档是英文公开 canonical 发布流程的完整便利翻译。中英文发生差异时，以英文文档为准。

## 1. 目标与边界

仓库使用两条 GitHub Actions 工作流：

- `.github/workflows/ci.yml`：在 `main` push、面向 `main` 的 Pull Request 和手动触发时运行，不读取发布签名材料。
- `.github/workflows/release.yml`：稳定版本 tag 或手动 dry-run 触发。它先在不读取签名 secret 的情况下准备发布说明并运行固定 frp 互操作，再使用受保护的 `release` Environment 构建并校验签名制品；只有 tag 触发时才创建 GitHub Release。

首版公开制品固定为：

- `OCDeck_<version>_arm64-v8a.apk`
- `OCDeck_<version>_armeabi-v7a.apk`
- `OCDeck_<version>_x86_64.apk`
- `SHA256SUMS`

工作流不生成 universal APK、AAB 或 Play 上传制品。GitHub 侧载用户应下载与设备 ABI 对应的 APK。

内部 `canary` 构建仅是验证 variant。它继承 Debug，使用 application ID suffix `.canary` 与 version name suffix `-canary`，并通过 `BuildConfig` 选择纯 Kotlin STCP backend。CI 与 Release 自动化会测试并 assemble Canary，但绝不应用 Release 签名，也不会暂存或发布 Canary APK；正式 `release` 继续默认使用 GoMobile。

应用 ID 为 `io.github.ycfeng.ocdeck`，版本 `0.2.3` 最低支持 Android API 26。OC Deck 是独立社区客户端。发布说明必须明确用户需要自行提供可访问的 OpenCode Server，而且 pre-1.0 行为和兼容性仍可能变化。

## 2. 版本规则

应用版本的唯一源码位于根目录 `gradle.properties`：

```properties
VERSION_CODE=8
VERSION_NAME=0.2.3
```

要求：

- `VERSION_NAME` 必须是无前导零的稳定 SemVer，例如 `1.2.3`，不接受 prerelease 或 build metadata。
- 正式 tag 必须精确等于 `v${VERSION_NAME}`，例如 `v1.2.3`。
- `VERSION_CODE` 必须是 `1..2100000000` 的整数，并且高于上一个稳定 tag 中的值。
- CI 不动态改写版本号；发布前必须先在源码中更新这两个属性。
- 新 tag 必须是仓库中最高的稳定版本，必须指向工作流检出的 commit，且该 commit 必须是 `origin/main` 的祖先。

## 3. CI 门禁

普通 CI 固定使用 Ubuntu 24.04、JDK 21、Go 1.26.4、Android SDK 36、Build Tools 36.0.0 和 NDK 27.1.12297006，并包含以下门禁：

1. 审计社区/文档 metadata，生成 patched frp，并审计第三方/法律清单、四个 Android Go 目标依赖并集、资源哈希和完整 modified/added provenance。
2. 在独立只读 job 中运行 `:frpc-stcp-visitor:frpcInteropTest`。任务只下载当前主机对应、由仓库固定并经哈希校验的官方 frp `v0.69.1` archive，安全解压 `frpc`/`frps`，并覆盖 wire v1/v2、四种 payload 模式、并发双向超窗口流、REST 与长期 global/project SSE、类型化负例、TLS 以及重启/control epoch 恢复。
3. 运行外层 Go race tests，并在生成的 frp module 中单独运行 `client/...` race tests。
4. 在 Ubuntu runner 上运行 canonical shell 可复现门禁。它会构建干净的候选 checkout，以及位于不同绝对路径的 detached checkout，隔离 `GOCACHE`、`GOMODCACHE` 与 `GOPATH`；校验法律/API/provenance/native metadata 和四 ABI 固定且无本地路径的 Go BuildInfo module graph；并逐字节比较 AAR、必需的 sources JAR、POM、checksum、API、bridge/frp provenance 和 native sidecar。
5. 执行 bridge AAR 门禁、App 的 Debug 与 Canary 单元测试、`:frpc-stcp-visitor` 单元测试，以及 Debug 与 Canary APK 构建。

Debug factory 测试确认 GoMobile 默认选择，Canary factory 测试确认纯 Kotlin 选择。由于两种实现位于同一个 Android library，Canary 仍可能打包 Go native 字节；门禁验证的是 App 实际装配，而不是断言安装包不存在 native 字节。

CI 不持有 JKS、密码或仓库写权限。Debug 与 Canary APK 仅作为验证输出。固定 frp host job 是可执行互操作门禁，但目标真机 native load、16KB page-size 真机和 App/设备 STCP 端到端闭环仍是正式发布前的强制人工门禁。

## 4. GitHub 仓库配置

### 4.1 Release Environment

创建名为 `release` 的 GitHub Environment，并配置：

- 仅允许 `main` 和受保护的 `v*` tag 部署。`main` 用于人工触发签名 dry-run。
- 配置 required reviewers。
- 在 GitHub 套餐支持时，禁止发起者自行审批部署。

Environment secrets：

| 名称 | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | 发布 JKS 的 Base64 内容 |
| `RELEASE_STORE_PASSWORD` | JKS 密码 |
| `RELEASE_KEY_ALIAS` | 发布 key alias |
| `RELEASE_KEY_PASSWORD` | 发布 key 密码 |

Environment variable：

| 名称 | 内容 |
| --- | --- |
| `RELEASE_CERT_SHA256` | 发布证书 SHA-256 指纹，可带或不带冒号 |

Base64 只是编码，不是加密。JKS 必须保存在受控离线备份中，不得提交、缓存或上传为 workflow artifact。工作流只将其解码到 `$RUNNER_TEMP/release.jks`，并在 `always()` 清理步骤中删除。

可使用以下交互式命令查看证书指纹，避免把密码写入命令行：

```bash
keytool -list -v -keystore /secure/path/release.jks -alias your-alias
```

### 4.2 仓库保护与权限

建议同时配置：

- 默认分支设为 `main`，通过分支保护或 ruleset 要求 Pull Request 和 CI 通过。
- 使用 tag ruleset 限制 `v*` tag 的创建、更新和删除。
- 工作流默认 `GITHUB_TOKEN` 权限保持 `contents: read`。
- `prepare-notes` 只授予 `contents: write`，因为 GitHub generate-notes REST endpoint 要求该权限。此 job 不进入 `release` Environment、不读取签名 secret，也不创建或修改 release/ref。
- `frpc-interop` 保持 `contents: read` 且位于 `release` Environment 之外；它只能下载经哈希固定的官方 frp 测试 asset，并且必须在签名 job 开始前完成。
- `publish` 只授予 `contents: write`，且不读取签名 secret。
- `build-release` 保持 `contents: read`。它只在 `release` Environment 审批后接收签名输入，不能写仓库内容。
- 开启 immutable releases，禁止发布后替换 tag 或 assets。
- 仅允许可信 action；仓库 action 固定到完整 commit SHA，并通过经过审查的依赖变更更新。

`prepare-notes` 使用的自动 `GITHUB_TOKEN` 不是仓库或 Environment secret，只在当前 workflow run 中生效。不需要 Personal Access Token。

## 5. 签名与 Google Play 连续性

如果 GitHub APK 与未来 Google Play 安装包需要互相覆盖，两条渠道必须使用同一个 app-signing certificate：

- GitHub Release APK 始终使用当前发布 app-signing key 签名。
- 未来首次启用 Play App Signing 时，必须提供现有 app-signing key，不能让 Google 为相同 application ID 生成不同 app-signing key。
- 未来如增加独立 Play 发布流程，其 upload key 不得用作 GitHub APK 签名 key。
- 即使 application ID 相同，只要证书不同，Android 就不允许跨渠道覆盖安装。

丢失 app-signing key 通常意味着无法继续更新直接分发的 APK。至少保留两份受控离线备份，并将证书指纹和有效期与 secret 分开记录。

## 6. 发布说明来源

`prepare-notes` job 只组装一次发布说明，顺序固定为：

1. `.github/release-notes-preamble.md`，替换 `{{REPOSITORY_URL}}` 与 `{{RELEASE_TAG}}`。
2. `release-notes/v${VERSION_NAME}.md`，即该版本的策划双语说明。
3. 真实 tag 发布时追加 GitHub 自动生成说明。

固定前言中的版本化文档以及法律/支持上下文使用 tag 固定链接；安全策略可以指向最新报告策略。版本策划说明只使用一个双语文件，不得拆成英文和中文两个发布说明文件。

`workflow_dispatch` 触发时 release tag 可能不存在。Job 仍会生成可审计的最终 `release-notes.md` 并上传 artifact，同时明确自动变更列表只在真实 tag 发布时追加；它不得对不存在的 tag 调用 generate-notes。

Tag push 触发时，`prepare-notes` 对已经通过校验且确实存在的 tag 调用 GitHub generate-notes，并直接追加返回 body。它不额外添加 `## Changes` 标题，从而避免重复 change heading。组装结果如包含 `TODO`、`TBD` 或未解析的 `{{...}}` 占位符，会被拒绝。

`publish` 从同一次 workflow run 下载 release-notes artifact，并把这个文件原样传给 `gh release create`。它不 checkout 仓库，也不重新生成或编辑说明。

## 7. 发布步骤

1. 更新 `gradle.properties` 中的 `VERSION_NAME` 和递增后的 `VERSION_CODE`。
2. 更新变更日志和 `release-notes/v${VERSION_NAME}.md`。按实际变化同步 README、隐私、安全、支持、商标、`THIRD_PARTY_NOTICES.txt` 和 `third_party/`，重新核对依赖、音效、Gradle wrapper、frp patch 及本地 SHA-256。
3. 完成[发布检查清单](checklist.zh-CN.md)，包括真机 native load、16KB page-size 和真实 STCP 验证。缺少外部门禁时必须阻止正式发布。
4. 合并到 `main`，确认 CI 通过。
5. 可先从 `main` 手动运行 `Release` workflow。Dry-run 会准备最终可审阅说明，重新构建、签名、校验并保留三天 artifact，但不会创建 GitHub Release。
6. 在已经验证的 commit 上创建并推送 `vMAJOR.MINOR.PATCH` tag。
7. `preflight` 校验源码版本、tag、`origin/main` 祖先关系、最高版本规则和历史 `VERSION_CODE` 单调递增。
8. `prepare-notes` 在不读取签名 secret 的情况下生成并上传最终 `release-notes.md`。真实 tag 会追加 GitHub 自动说明。
9. `frpc-interop` 在无 release Environment 或签名材料的只读 job 中运行固定官方 frp host 矩阵。
10. `preflight` 与 `frpc-interop` 均通过且 Environment 获批后，`build-release` 重新运行全部本地测试，包括 Debug/GoMobile 与 Canary/Kotlin App 验证，并使用 shell 可复现门禁，在同一个 Ubuntu runner 上构建干净的候选 checkout 和位于不同绝对路径的 detached checkout，同时隔离 Go cache。完整 bridge 制品/sidecar 集合必须逐字节一致，随后单独运行 `assembleRelease`，仅对三个 GoMobile 默认的 Release APK 应用 Release 签名并核对固定证书指纹。
11. `publish` 下载两组已验证 artifact，使用 `GITHUB_TOKEN` 和 `gh release create --verify-tag --notes-file`。所有 `0.x` 版本创建为 prerelease，从 `1.0.0` 开始创建普通 release。

## 8. 制品检查

发布脚本只对 `release` 制品执行签名与发布校验；Debug 与 Canary 输出不会复制到发布 asset 集合。它还会校验：

- APK metadata 的 application ID `io.github.ycfeng.ocdeck`、Release variant、稳定 `versionName`、`versionCode`、目标 ABI 和文件名必须一致；公开 asset 集合会拒绝 `.canary` 身份与 `-canary` 版本。
- 每个 APK 只有一个 signer，证书指纹与 `RELEASE_CERT_SHA256` 一致。
- 每个 APK 通过 `apksigner` 和 16KB `zipalign`，只包含对应 ABI；其中 `libgojni.so` 的 SHA-256 与 AAR 对应 entry 完全一致。
- APK native library 重新通过 ELF machine、全部 `PT_LOAD` 16KB 对齐和 stripped 状态检查。
- App 打包会保留 AAR 中已经 stripped 的 `libgojni.so` 原始字节，不再重复应用 Android Gradle Plugin 的 native strip transform；上述独立 APK 校验确保该排除规则不会降低 native 门禁。
- 每个 APK 中的 `assets/legal/` 与当前 LICENSE、NOTICE、第三方通知、商标声明、合并许可证和每份单独许可证逐字节一致。
- AAR 的 `META-INF/OCDECK/` 包含当前法律文本、许可证、精确 Java API 和 bridge/frp provenance，其中包括 schema 2 native module graph digest 与无本地路径证明。
- 外部 checksum/API/provenance/native sidecar 与内嵌内容及精确 AAR 字节相互绑定；schema 2 native metadata 还证明固定 BuildInfo module identity/version/sum，以及四个 ABI 使用同一个 graph digest。
- `SHA256SUMS` 只覆盖最终公开的三个 APK。

Bridge 静态生成四个 GoMobile ABI，但公开应用发布只包含 `arm64-v8a`、`armeabi-v7a` 和 `x86_64` APK。

## 9. 故障处理

- 版本校验失败：核对 tag、`VERSION_NAME`、`VERSION_CODE`、稳定 tag 顺序、检出 commit 和 `origin/main` 祖先关系。
- 发布说明准备失败：补充所需的 `release-notes/v${VERSION_NAME}.md`，移除禁止占位符，检查前言替换，并确认只有真实 tag 存在时才调用 generate-notes。
- GoMobile 构建失败：确认 runner 安装精确 Go/NDK 版本，并检查 checksum、API signature、provenance、native metadata 或可复现性门禁。
- 固定 frp 互操作失败：检查类型化场景与脱敏、有界日志尾部，核对固定 asset 清单，并使用 `:frpc-stcp-visitor:frpcInteropTest` 复现；不得把任务移进签名 job，也不得降低其哈希、进程或输入限制。
- 签名失败：检查四个 Environment secret、JKS Base64 是否完整以及 alias/密码是否匹配；不得在日志中输出 secret。
- 证书指纹失败：停止发布并确认 Environment 中的 JKS 是既有 app-signing key；不得通过修改预期指纹绕过连续性保护。
- AAR 门禁通过后 APK native 字节绑定仍失败：确认 App 打包保留已经 stripped 的 `libgojni.so`；不得接受 transform 后的字节、替换 AAR hash 或降低 APK 校验。
- 真机或真实 STCP 门禁失败或未完成：不得发布。修复并重做门禁，或推迟版本。
- GitHub Release 已存在：不要覆盖 Release 或移动同名 tag；修复问题后递增版本并创建新 tag。
