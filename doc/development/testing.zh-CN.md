# 测试

[English](testing.md)

本文档是英文 canonical 测试文档的便利翻译。中英文发生差异时，以英文文档为准。

## 测试层级

OC Deck 使用多个相互独立的门禁。通过一个层级不代表其他层级也已通过。

| 层级 | 当前覆盖 |
| --- | --- |
| Kotlin/JVM 单元测试 | 路径与项目文件 URL、最近项目顺序/记录/重排回滚模型、会话窗口、通知/channel 策略、脱敏、Retrofit/direct encoded/decoded 与 identity-SSE 入站边界、类型化失败与本地化 UI 映射、STCP backend factory 选择、类型化 bind 重试、安全 value 摘要、DTO 容错、Store revision、prompt/项目上下文状态与恢复、Provider auth/OAuth 与分阶段 custom-config 事务、服务器凭据事务、SSH/STCP 协调及 Kotlin/GoMobile bridge 契约、对比度和 feature helper。 |
| Go race tests | GoMobile wrapper、canonical STCP fixture oracle/check 与生成的 patched frp client 包。 |
| 第三方与法律审计 | 固定版本、依赖清单、哈希、provenance、许可证和发布脚本引用。 |
| Bridge 校验 | AAR 与必需的 sources JAR、checksum、Java API signature、bridge/frp provenance、四 ABI Go BuildInfo/module graph 证明、ELF machine、16KB `PT_LOAD` 对齐、stripped 状态，以及完整制品/sidecar 集合在同一平台跨 checkout 的可复现性。 |
| Android 构建 | App 的 Debug 与内部 Kotlin Canary 单元测试和 APK 构建，以及 `:frpc-stcp-visitor` 单元测试。 |
| Android instrumentation 测试 | 覆盖 Popup 与 modal bottom sheet 独立 Compose 窗口根的本地化，以及 `:frpc-stcp-visitor` 中依次对真实 GoMobile 与 Kotlin backend 运行同一固定 frp 拓扑的 test-only harness。 |
| 人工 UI/无障碍验证 | 紧凑屏幕、200% 字体、IME 遮挡、项目选择页与 Drawer 排序、项目文件选择、Provider auth/OAuth/Custom Provider 流程、TalkBack 语义/操作、浅色/深色主题和真实模型设置导航。 |
| Release 制品校验 | APK metadata、单 signer、预期证书指纹、ABI 隔离、`zipalign -P 16`、AAR native 字节绑定、内嵌法律文件、文件名和 checksum。 |
| 真机验证 | 维护者已记录 `0.1.0` 发布门禁通过真机 native load/启动、16KB page-size native 运行，以及覆盖 `/global/health`、代表性 REST、全局/项目 SSE 和受控重连的真实 STCP 闭环。具体环境信息未公开；后续候选版本仍须重复执行这些门禁。 |

显式请求的 K6V workflow 已为 STCP backend 互操作提供 x86_64 emulator 门禁，但普通 CI 仍没有 App UI emulator/instrumentation job。K6V workflow 进入默认分支后可直接手动触发；首次引入该 workflow 时，可通过手动 CI dispatch 显式启用同一个分支内 reusable workflow。Push 与 Pull Request CI 绝不会自动启用该 bootstrap。`app/src/androidTest` 测试集覆盖独立窗口根的本地化。最近项目拖拽排序仍不在 instrumentation 覆盖范围内；其自动化覆盖主要来自下文列出的 JVM reducer、recorder、ViewModel 和 Drawer model 测试。项目选择、会话导航、更广泛的 Composer 交互与 picker、permission/question UI、大字体行为与 TalkBack 仍需系统化设备自动化测试。

## 聚焦测试清单

- `RetrofitInboundResponsePolicyTest` 与 `EncodedResponseLimitInterceptorTest` 验证每个 `OpenCodeApi` 方法声明 `BOUNDED` 或 `EMPTY_SUCCESS`；缺少策略时在网络请求前失败；encoded/decoded 已声明、未知和低报长度都在 `max + 1` 执行各自 16 MiB 边界；真实 OkHttp gzip chain 在 Bridge 解码前执行 encoded 上限；非 2xx 与成功 Unit body 不读取即关闭；`/file/content` 保持延迟读取；没有 Retrofit `Invocation` 的请求绕过 Retrofit interceptor。
- `SessionMessagesTransportTest`、`SessionMessagesResponseReaderTest`、`FileContentResponseReaderTest` 覆盖无 body 的非 2xx 失败、OkHttp callback 线程 direct decode、encoded/decoded 精确/未知/低报长度、EOF 验证、取消与 callback race、session messages 独立 64 MiB 边界，以及 `/file/content` reader 层 decoded 纵深防御。
- `OpenCodeFailureTest`、`ErrorUiTextTest`、`OpenCodeRepositoryFailureHandlingTest` 覆盖不读取 `Throwable.message` 的语义分类，包括将 `KotlinFrpcStcpVisitorFailure` enum 映射为 App failure 和本地端口拒绝；带操作 fallback 的本地化资源映射、Repository 传播、response-too-large 行为，以及取消与 JVM `Error` 传播。
- `BoundedSseReaderTest`、`OkHttpSseEventSourceFactoryTest`、`OpenCodeEventClientLifecycleTest`、`ProjectSnapshotCoordinatorTest` 覆盖显式 identity encoding、非 identity 零读取拒绝、所有换行与 EOF 状态、32 MiB 行/event 边界、无 body status/MIME 失败、取消、重试分类、关闭终态、owner/generation/source/transport 竞态、project/global 权威切换、有界重放和快照失败/恢复。
- `FrpcStcpReadinessRetryClassifierTest` 与 `GoMobileFrpcStcpVisitorClientTest` 覆盖 readiness 瞬时/永久失败、入站策略失败、typed unavailable/API mismatch bridge 错误、安全 bridge 摘要、API v2 JSON、revision/control epoch、`WaitVisitorReady`，以及反射取消/JVM `Error` 传播。
- `FrpcStcpVisitorClientFactoryTest` 在两个 App variant 中运行，验证 Debug `BuildConfig` 选择 `GoMobileFrpcStcpVisitorClient`、Canary 选择 `KotlinFrpcStcpVisitorClient`，且显式 factory 可构造任一 backend，不存在运行时 fallback。
- `FrpcStcpVisitorManagerTest` 覆盖共享 generation/lease/readiness 行为，以及将 `BindException` 和类型化 Kotlin bind failure 转换为 `LocalPortInUse`、有界前序 generation bind 重试，并确认非 bind 类型即使 message 含有类似 bind 的文字也不会被误判。
- `KotlinFrpcStcpVisitorClientTest` 及内部 control、crypto、protocol、transport、yamux、compression 测试覆盖类型化 runtime failure、revision/control epoch readiness、listener 所有权与重绑、v1/v2 visitor handshake、`useEncryption`/`useCompression` 四种组合、握手与 payload 合并读取、有界 relay 生命周期、本地停止后延迟的 best-effort reset 不阻塞 `stopVisitor`、session-owned permit 释放、Snappy framing 与损坏输入边界、清理、取消和无 secret 诊断。
- `SocketFrpLocalListenerFactoryTest` 建立并主动关闭真实 loopback relay，随后验证完整停止的 Kotlin generation 可立即重绑同一端口，而活动 listener 仍保持独占所有权。
- `FrpcStcpVisitorClientDifferentialContractTest` 对脚本化 GoMobile Kotlin adapter seam 和可注入纯 Kotlin runtime fixture 执行相同六个公共操作，比较归一化的 phase/revision/epoch/listener/bind 语义、幂等、替换、类型化 bind conflict、取消身份和安全诊断。它是 host-JVM adapter/runtime 契约测试，不加载 native AAR，也不替代真实 frps/设备互操作测试。
- `FrpcStcpVisitorAndroidInteropTest` 由 `frpcAndroidInteropTest` 启动，在相互独立的 instrumentation 进程中运行真实生成的 GoMobile AAR 与纯 Kotlin backend。第一条 session 完整停止后，第二个 backend 复用同一 bind port；测试通过公共契约验证 readiness/state、`/global/health`、全局/项目 SSE、两条并发 echo、两条超窗口下载、终态关闭和端口释放，不存在活动 generation 内 fallback。
- `FrpcStcpVisitorSerializationContractTest`、`FrpcStcpVisitorFixtureContractTest` 与 `FrpcStcpVisitorManagerContractTest` 覆盖可实现的 suspend bridge API，以及稳定的 DTO 默认值、字段名、`Long` 值、容错 JSON、Go/Kotlin 共享 bridge DTO JSON 和安全摘要；Kotlin 对带版本 canonical STCP manifest 及小型 wire/control/yamux/payload 字节的加载与完整性校验，包括 Go Snappy raw/framed、AES-CFB 加 Snappy 跨语言向量以及声明的分块方案和 mutation recipe metadata；以及 manager 对 native-ready 结果、session 身份、运行时与终态恢复、control epoch 回退、最终 ensure bind port、清理/替换和无 secret 诊断的校验。
- `SensitiveValueToStringTest` 验证 network、domain、Store、feature 和 UI value 摘要不暴露人工凭据、URL/endpoint、alias、路径、prompt、Base64、SSE payload 或 tool output，同时保持普通 value object 行为。
- `OpenCodeContrastTest` 对浅色/深色主题执行 4.5:1 文字与 3:1 图形对比度门禁，覆盖主题文字、语义化 Diff/Markdown/语法/图表颜色、状态指示、附件遮罩、选择边框和 `ControlBorder`。
- `SessionRunningIndicatorTest` 覆盖 4×4 四角遮罩、每个点独立的 1–2 秒节奏、有界相位偏移、满足无障碍要求的透明度与缩放范围、可见帧变化、差异化初始帧和公共循环的无缝连续性。
- `RecentProjectStoreReducerTest` 覆盖数值 `sortOrder`、旧数组顺序保留、连续重编号、新项目置顶、已有项目 metadata 更新时位置稳定、比较 key 去重、原子重排且只显式纳入当前项目、保留未提交/并发新增项目、不复活并发删除的陈旧记录、服务器间隔离和每服务器 20 项上限。`RecentProjectRecorderTest`、`ProjectPickerViewModelTest`、`ProjectDrawerModelTest`、`ProjectInitialTest` 与 `VerticalLazyListReorderTest` 覆盖已有项目不移动的串行/重试 best-effort 记录、不等待 DataStore 的导航、展示顺序持久化与失败回滚 callback、有界当前项目合并、项目首页导航决策、Unicode 项目首字和短视口边缘滚动方向。
- `SessionListWindowCoordinatorTest`、`InMemoryOpenCodeStoreSessionWindowTest` 与 `OpenCodeRepositorySessionWindowTest` 覆盖共享 20 条目标、50 条请求余量、本地展开与网络加载、重试/末尾状态、有序前缀替换、快速点击合并、旧 generation/transport 拒绝、tombstone、项目/workspace 隔离和有界 metadata/父链补取。
- `NotificationAlertPolicyTest`、`NotificationChannelMigrationPolicyTest`、`OpenCodeNotificationAudioAttributesTest` 与 `SessionVisibilityRegistryTest` 覆盖单事件唯一声音所有者、App/系统设置独立、系统显式声音/静音优先级、legacy 到 v2 迁移决策、notification audio usage 和前台 destination 可见性。
- `SessionComposerAgentResolverTest`、`SessionModelPreferenceResolverTest` 与 `SessionComposerRouteSelectionTest` 覆盖按服务端顺序过滤 Build/Plan 及回退、初始模型/Variant 校验与切换回退，以及只对新会话接受项目首页轻量 Composer 路由选择。
- `ComposerParameterPickerScrollTest` 覆盖跨 provider 标题的模型 lazy-list 索引、带“默认”前缀的 Variant 索引、选择不存在时的行为，以及基于实测 item 与 viewport 的居中偏移。
- `LocalizedWindowTest` 是 Android instrumentation 测试：它为父组合提供与 Activity 不同的 locale，验证 Popup 与 modal bottom sheet 的资源使用父组合 locale，并验证已打开的 Popup 会随语言切换更新。
- `ProjectFilePathNormalizerTest` 与 `ProjectFileUrlBuilderTest` 覆盖相对路径平台语义、遍历/绝对路径拒绝、POSIX/Windows 盘符/UNC `file://` 构造、UTF-8 百分号编码、往返和项目根包含关系。
- `PromptSendStateMachineTest`、`OpenCodePromptSenderTest` 与 `PromptRequestDtoSerializationTest` 覆盖纯项目上下文发送、上下文终检与去重、普通/已加载命令透传、乐观 `file://` parts、新 session 消息移动和线缆序列化。
- `UserMessagePartsTest` 与 `SessionRevertProjectionTest` 区分本地 `data:` 附件、独立项目上下文和评论 backing file，并只在数量上限内恢复当前项目上下文。
- `ProviderSettingsParsingTest` 与 `ProviderCapabilityRefreshTest` 覆盖可能含密钥的 Provider/auth payload 的即时安全投影、权威已加载状态、原始 auth method index 与条件、安全 OAuth URL，以及通过当前 SSE authority lease 刷新 capability。
- `ProviderSettingsViewModelTest` 覆盖项目感知动态 API/OAuth 输入、明文 HTTP 冻结确认、mutation single-flight、OAuth code/callback scope、无 secret 状态摘要、reload 与 capability calibration。
- `CustomProviderValidationTest`、`OpenCodeProviderRepositoryCustomTest` 与 `CustomProviderFormViewModelTest` 覆盖模型/Header 精确上限与超限校验、添加操作容量状态与 no-op 反馈保留、安全 global-config 投影、disabled/auth/enable 事务顺序、partial/unknown outcome、先停用后清理 auth、deep-merge 防伪删除、持久化标识不可变和 secret 清除。

## 标准 Android 验证

Windows：

```powershell
.\gradlew.bat :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

macOS/Linux：

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

这仍是普通小型 App 改动的最低门禁，并不要求每项此类改动都构建 Canary。修改 STCP backend 选择、纯 Kotlin backend、共享 STCP manager 集成或 CI/Release variant 验证时，还需运行 `:app:testCanaryUnitTest` 与 `:app:assembleCanary`。下方完整 bridge/等价 CI 门禁始终运行两个 App variant。

开发期间可按测试类运行聚焦测试，例如：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.ycfeng.ocdeck.core.security.RedactorTest"
```

## 固定 frp STCP 互操作

修改任一 STCP backend、共享协议/runtime 代码或其 CI 集成时，运行显式 host-JVM 互操作 harness：

```powershell
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcInteropTest
```

```bash
./gradlew --no-daemon :frpc-stcp-visitor:frpcInteropTest
```

该任务刻意与 `testDebugUnitTest` 分离：普通单元测试不会下载或执行外部程序。任务会为 Linux、Windows 或 macOS 的 amd64/arm64 选择仓库固定的官方 frp `v0.69.1` asset，每次解压前校验 SHA-256，执行有界且防路径穿越/link 的安全解压，校验 `frpc --version` 与 `frps --version`，并只在 Gradle user home 中缓存已校验的 archive 与 executable。这些测试专用二进制不会提交、打进 APK/AAR、暂存或发布。

Harness 会启动仅监听 loopback 的官方 `frps`、官方 provider `frpc` 和有界合成 OpenCode HTTP/SSE server，并为每次运行生成一次性凭据与 TLS 材料。它覆盖 wire v1/v2 与 encryption/compression 四种组合；两条长期 SSE 与 REST、多条不可压缩且超过 yamux 初始窗口的上下行大流并发；错误 token、错误 STCP secret、bind 冲突；以及 frps 重启时既有 SSE 中断、control epoch 前进和并发 REST/SSE 恢复。日志、临时配置、进程生命周期、archive 输入、socket 与清理均有界且脱敏。该 host 门禁不能替代 Android 真机验证。

## Android STCP A/B 互操作

先生成 GoMobile AAR，再启动新的 Gradle invocation，使配置期 bridge 依赖可见。连接恰好一个已授权 emulator/设备，或显式指定 serial 后运行：

```powershell
.\frpc-stcp-visitor-go\build-aar.ps1
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcAndroidInteropTest -PrequireGoMobileBridge=true "-Pocdeck.frp.androidInterop.deviceSerial=<serial>"
```

```bash
bash frpc-stcp-visitor-go/build-aar.sh
./gradlew --no-daemon :frpc-stcp-visitor:frpcAndroidInteropTest -PrequireGoMobileBridge=true \
  "-Pocdeck.frp.androidInterop.deviceSerial=<serial>"
```

Host coordinator 与 `frpcInteropTest` 共用经哈希固定的官方 frp v0.69.1 工具，启动仅监听 loopback 的 TLS `frps`、provider `frpc` 与有界合成 server，并创建一条由本次运行拥有的动态 `adb reverse` 映射。它会拒绝设备集合不明确、远程 adb-server 路由以及预先安装的测试 package。人工合成凭据通过有界 stdin 写入测试 package 私有 files 目录，绝不作为 Gradle property 或 instrumentation argument 传递。结果只包含固定结构字段；清理只移除本次运行拥有的 package、私有文件与 reverse 映射。

GoMobile 首先在新的 instrumentation 进程中运行。完成 `stopVisitor`、`stopSession`、终态校验与 listener 端口释放后，Kotlin 在第二个进程中运行并必须重绑同一端口。第一阶段设备场景固定为 `wire=v1`、关闭加密和关闭压缩；wire v1/v2、四种 payload 模式、类型化负例与重启恢复仍由 host JVM harness 负责。设备证据必须记录 API level、ABI 和 page size。该门禁不覆盖性能、soak、Doze、前后台切换、网络切换、arm 真机或 16KB page-size 硬件。

显式请求的 `.github/workflows/frpc-kotlin-android-interop.yml` 会在 API 26 与 API 36 x86_64 emulator 上验证精确候选 SHA。它同时支持直接 `workflow_dispatch` 与只读 `workflow_call`；后者仅在手动 CI dispatch 设置 `run_frpc_android_interop=true` 时使用，用于该 workflow 尚未进入默认分支时调用分支内版本。CI 的可选 `candidate_sha` 默认采用所触发 ref 的 SHA，push/PR CI 绝不会调用 K6V。报告状态绑定完整 matrix 结果，每个 lane 还会记录实际 Android test APK 与 GoMobile bridge AAR 的 SHA-256。Workflow 会上传有界的中英文验收报告、合并 JSON 证据和 `SHA256SUMS`；它只有仓库只读权限，不使用签名 Environment 或 secret，也不授权切换正式默认 backend。真机与长期 K6V 证据仍是独立要求。

已记录的第一阶段证据：精确候选 `459c2b57ebf465d6b933ea939f59fa739128ec59`（tree `a6ce1019adfa351bc15e09bc479220a967bdf323`）已通过 [workflow run 29716724485](https://github.com/ycfeng/ocdeck-android/actions/runs/29716724485)。API 26 Android 8.0.0 与 API 36 Android 16 的 x86_64 emulator lane 均报告 4 KiB page，并完成 GoMobile 后 Kotlin 的场景。合并 artifact `k6v-frpc-android-interop-459c2b57ebf465d6b933ea939f59fa739128ec59` 状态为“通过”，其 GitHub artifact digest 为 `sha256:6c074ac7d7d6a7a5584f8bd9e6e90fa1746bbe5ff5ef226b1acfc8f7bc532b28`。该历史记录仅适用于这一精确候选及第一阶段范围；它不能满足物理 ARM/16KB、扩展 Android wire/payload/restart、Doze/网络/前后台、性能、资源泄漏或 soak 门禁，也不授权 Kotlin 默认装配。

连接 emulator 或设备后，运行 instrumentation 测试集：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## 完整 Bridge 与等价 CI 门禁

准备 patched frp，并运行两组 Go race tests：

```bash
cd frpc-stcp-visitor-go
go run ./cmd/preparefrp
go test -race -modfile=build/frp-patched.mod ./...
cd build/frp-v0.69.1-p1
go test -race ./client/...
```

第一组 Go race 范围从 `frpc-stcp-visitor-go/` 运行，通过 `frpc-stcp-visitor-go/internal/contractfixture/` 中的固定 oracle，自动对 `frpc-stcp-visitor/src/test/resources/io/github/ycfeng/ocdeck/frpcstcpvisitor/contract/v1/` 执行 canonical fixture check。当前 `k0-go-oracle-v5` manifest 包含 34 个条目，包括 v1 `LoginResp`、v1/v2 work/visitor 消息、Go Snappy raw/framed 输出、65,536/65,537 字节 framing 边界，以及 AES-CFB 加 Snappy payload 顺序向量。必须像上面所示先运行 `go run ./cmd/preparefrp`。现有根级 race-test 命令继续作为 CI 门禁；不要增加独立 fixture-check 命令。

协议 fixture 不替代运行时生命周期测试。首登失败清理、重连时传递先前 RunID、断线后使旧 readiness 失效，以及 stop timeout 后重试，继续由上述两组 race-test 范围中的现有 Go wrapper 与 patched frp 测试覆盖。固定的 runtime tracker 还会忽略 epoch 不等于当前活动 control epoch 的 visitor callback；若修改这项 guard，必须新增聚焦的 downstream 回归测试。

返回仓库根目录后，审计社区/文档与第三方/法律 metadata，运行跨 checkout bridge 可复现门禁，再运行 Android 门禁：

```bash
python3 .github/scripts/audit-community.py
python3 .github/scripts/audit-third-party.py
bash ./gradlew --no-daemon :frpc-stcp-visitor:frpcInteropTest
bash .github/scripts/verify-bridge-reproducibility.sh
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary -PrequireGoMobileBridge=true
```

Go race detector 需要 CGO 与受支持的 C 编译器。如果 Windows 上没有该工具链，必须在 WSL 或 Linux 中运行两组 Go race tests；Windows 普通 Go 测试不能替代 race 门禁。PowerShell 默认也不会在 native 进程失败时停止，因此下面使用一个小型 fail-fast wrapper。

在 Windows PowerShell 中，从仓库根目录运行等价序列：

```powershell
$ErrorActionPreference = 'Stop'
function Invoke-NativeChecked {
    param([scriptblock]$Command)
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Native command failed with exit code $LASTEXITCODE"
    }
}

Push-Location .\frpc-stcp-visitor-go
Invoke-NativeChecked { go run ./cmd/preparefrp }
Invoke-NativeChecked { go test -race '-modfile=build/frp-patched.mod' ./... }
Push-Location .\build\frp-v0.69.1-p1
Invoke-NativeChecked { go test -race ./client/... }
Pop-Location
Pop-Location

Invoke-NativeChecked { python .github/scripts/audit-community.py }
Invoke-NativeChecked { python .github/scripts/audit-third-party.py }
Invoke-NativeChecked { .\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcInteropTest }
Invoke-NativeChecked { .\.github\scripts\verify-bridge-reproducibility.ps1 }
Invoke-NativeChecked { .\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary -PrequireGoMobileBridge=true }
```

可复现脚本要求干净 checkout。它们会在当前主机平台构建当前 checkout，以及位于不同绝对路径的 detached worktree；两个构建分别隔离 `GOCACHE`、`GOMODCACHE` 与 `GOPATH`，并逐字节比较完整的 AAR、必需 sources JAR、POM、checksum、API、bridge/frp provenance 和 native sidecar 集合。脚本会删除临时 checkout 与 cache，但保留当前 checkout 中的主构建输出供 Gradle 门禁使用。这不代表 Windows 与 Linux 之间的字节一致性声明。CI 与 Release 使用 shell 脚本；Windows 开发者可运行对应 PowerShell 脚本。

固定 Go、x/mobile、Android API 与 NDK 版本必须来自 `bridge-versions.properties`。

修改 Go wrapper、downstream frp patch、Android bridge 模块、任一 STCP backend、App backend 选择、bridge API、失败处理或版本 metadata 时，必须执行固定 frp 互操作任务与完整 bridge 门禁，不能只运行 Android 单元测试。Android 门禁会同时验证 Debug/GoMobile 与 Canary/Kotlin 的选择和装配。仅修改 Kotlin bridge API 或失败处理时，生成 AAR 字节与 `BRIDGE_VERSION` 可以保持不变，但不能跳过上述任何门禁；native 或生成 AAR 字节发生变化时必须递增 `BRIDGE_VERSION`。

## 安全与边界测试

- 使用人工合成凭据，并断言异常、alias、`toString()`、日志和 UI 安全文案都不会泄露它们。
- 断言 Repository、SSE 和快照失败保留语义类型，Kotlin runtime failure 根据 enum 而非异常文本映射，bind conflict 重试绝不解析 message，UI 文案来自本地化资源而非异常字符串，并且取消与 JVM `Error` 原样传播。
- 对 Retrofit 方法验证显式入站模式、独立 encoded/decoded 精确上限、`max + 1`、未知与低报长度、真实 gzip/Bridge 顺序、body 关闭行为、非 2xx 零读取、成功 Unit body 丢弃和无 `Invocation` 绕过。
- 覆盖直连明文凭据矩阵：规范化后的远端/LAN `http://` 地址同时具备非空白 OpenCode 用户名和新密码或保留密码时需要提示确认；HTTPS、纯语法 loopback、不完整 Basic 凭据、SSH 和 STCP 不触发。测试确认、取消、重复确认、冻结请求语义和无 DNS 分类。
- 单独覆盖 Provider secret 提交：通过 Direct 非本机回环 HTTP 发送新 API key 或自定义 Header value 需要一次冻结确认；HTTPS、纯语法 loopback、SSH 和 STCP 不触发。断言 API key/Header value 不进入 Android 持久化、公开 UI state、原始响应日志或 `toString()`。
- Provider auth method 解析测试应在受支持项前放入未知项，确保原始 wire index 不被重编号。OAuth 测试保持 directory/workspace 与 method index，拒绝不安全浏览器 URL，覆盖 code 与可取消 auto callback，且不自动重试有状态 authorize/callback。
- 测试 Custom Provider 的 disabled 暂存、可选凭据写入、最终启用、先停用后清理 auth、partial/unknown outcome 与 deep-merge 删除限制；config 投影只保留安全身份和 Header 名。
- 精确上限和 `max + 1` body 应在测试代码中生成。已提交的 canonical STCP 契约文件必须保持小型、合成且确定性；不要创建巨大的 fixture 文件。
- 网络和凭据状态机应覆盖取消、callback race、迟到结果、generation 变化和 unknown commit outcome。
- 对规定不能读取 body 的 HTTP 错误，应测试 body 确实未被消费。
- DTO 容错测试应加入未知字段和损坏的可选子树，同时保留周围有效数据。
- 不得把真实服务端响应、项目数据、prompt、凭据、私有路径或 host fingerprint 保存为 fixture。

Fixture 规则见[测试夹具](test-fixtures.zh-CN.md)。

## 人工 UI 与无障碍矩阵

在设备 instrumentation 成为 CI 门禁前，相关 UI 改动需记录所用设备/API level、主题、字体缩放和输入法，并验证：

| 环境 | 检查项 | 预期结果 |
| --- | --- | --- |
| 200% 字体的紧凑竖屏手机 | 二次确认 dialog、服务器状态、设置 sheet、内联建议、权限/问题 UI、Composer 附件和参数行 | 内容保持有界且可滚动；标题、字段和主操作可达，不因固定高度裁切 |
| 项目选择页和最多 20 个最近项目的 Drawer | 长按纵向拖拽、边缘自动滚动、两个入口同步、普通项目点击、项目选择页删除图标、模拟持久化失败、当前项目不在持久列表、Drawer 列表 75% 高度上限、“打开项目”、“设置”、项目首页切换和抽屉水平手势 | 两个入口展示同一已保存顺序；新项目默认置顶；点击已有项目不重排；只有项目选择页卡片正文启动拖拽且删除保持独立；乐观移动失败后回滚；当前项目合入后仍最多 20 项且可通过重排持久化；固定操作始终可达且不移动；仅 rail 拖拽期间禁用水平抽屉手势；切换会关闭抽屉且不堆叠重复项目首页 |
| 超过 20 条根会话的项目 | 项目主页和抽屉中的加载更多、共享状态、网络加载、重试、末尾状态、直接打开旧会话 route | 两个界面展示相同目标；失败时保留已有行；重试可达；末尾状态稳定；窗口外会话及有界父链可补取且窗口不缩小 |
| API 26/29/30+/33+ fresh install 与旧通知升级 | 默认提醒、App 内“无”、系统显式声音、channel 显式静音/阻止/低 importance、权限拒绝、当前会话前后台 | 每个事件最多一个声音所有者；Android 系统选择优先；App 播放不绕过低打扰设置；后台会话不会误标已查看 |
| IME 打开 | 固定底部 Composer、建议面板、model/agent/variant popup、附件条、系统返回 | Inset 保持 Composer 可见；浮层不被遮挡；系统返回先关闭聚焦浮层，再离开页面 |
| TalkBack 启用 | 项目 Tab 与路径、可点击图标标签、Tab/RadioButton/Checkbox/Switch 状态、展开/折叠控件、未读/错误/权限/连接提示、服务器排序和两个入口的项目排序 | 项目 Tab 播报唯一完整名称/路径标签、选中状态和通知状态；其他 role 与状态正确播报；重要含义不只依赖颜色；服务器卡片以及项目选择页/Drawer 项目都提供本地化上移/下移自定义操作 |
| 浅色和深色主题 | 正文/辅助文字、文本框和边框、选中/错误/状态指示、Diff/Markdown/代码、附件遮罩 | 文字达到 4.5:1 目标，必要非文本控件/指示达到 3:1；状态在两种主题下都可理解 |
| Composer 模型与 Variant 选择器 | 选中视野外项目后重新打开、搜索、provider 分组、当前 check、连接 Provider、管理模型 | 重新打开时当前选择会自动滚入视野，并在滚动边界允许时居中；搜索和手动滚动仍由用户控制；行和图标操作保持 48dp 目标；管理操作关闭 picker 并导航到真实 Provider/模型设置路由 |
| Provider 设置与认证 | 搜索和已加载/可连接分组；动态 text/select prompt；API key 键盘；OAuth 浏览器、code、auto callback 与取消；明文/loopback/断开确认 | 浮层关闭后 secret 消失；系统返回先关闭聚焦 sheet；IME 与 200% 字体下操作仍可达；已加载、disabled、pending、错误均有非颜色提示；取消不会静默重试 |
| Custom Provider 创建/编辑 | 多模型/Header 行、数量/上限状态、持久化标识限制、密码字段、保存、disabled/unknown outcome、停用与凭据清理 | 行和操作保持可达且至少 48dp；满额/超限状态明确，移除新增行后可重新添加；已保存值不回显；partial/unknown 状态明确；停用不宣称物理删除；两种主题和 TalkBack 表达相同状态 |
| 项目首页 Composer 预览 | Agent、模型、Variant 控件；输入区与附件入口；系统返回；紧凑屏幕和 200% 字体 | 参数 picker 原地打开和更新且不导航；返回先关闭聚焦 popup；Provider/模型操作进入真实设置路由；输入区和附件入口携带当前已校验选择进入 `"new"` |
| 项目文件 picker | 树与搜索、最多 10 个选择、独立预览、确认/取消、预览返回文件树、route/session 变化、上下文 chip、纯上下文发送/reset | 选择具备 Checkbox 语义和非颜色提示；预览不切换选择；取消不修改草稿；旧路由不接收结果；IME、200% 字体、TalkBack 和两种主题下操作均可达 |

缺少 instrumentation 门禁必须作为测试缺口报告；人工检查只证明已测试设备/配置，不代表全部 Android 环境。

## 发布前人工验证

静态检查不能证明 Android 能在所有目标设备加载 native library，也不能证明真实隧道可用。正式发布前，必须完成并记录[发布检查清单](../release/checklist.zh-CN.md)中的人工项目，包括：

- 在可获得的目标发布 ABI 真机上执行 native load。
- 在 16KB page-size 真机上执行 native load 和应用启动。
- 通过真实 frps/STCP visitor 路径完成 `/global/health`、REST 和 SSE。
- 使用 App 实际路径完成直连与 SSH smoke test。
- 对每个可获得真机的目标签名 ABI APK执行首次安装和启动。
- 从上一个真实公开版本执行覆盖更新并确认预期本地数据保留；首个公开版本应记录为不适用，不能宣称存在未经验证的升级证据。
- 确认 signer 不兼容和较低 `versionCode` 的覆盖安装会被拒绝。
- 验证破坏性回退与卸载/重装行为，包括应用私有本地数据丢失，以及不存在受支持导出/恢复流程的限制。
- 从公开 Release 重新下载，并执行完整 `SHA256SUMS` 校验和文档中的单 APK checksum 流程。

对于 `0.1.0` 候选版本，维护者已记录上述 native load、16KB page-size 和真实 STCP 检查通过。具体设备与部署信息未公开，因此该证据只适用于此候选版本，不构成对全部 Android 环境或 OpenCode Server 版本的覆盖。其他未勾选人工项目仍相互独立，后续每个候选版本都必须重复执行适用门禁。详见[兼容性矩阵](../user/compatibility.zh-CN.md)。
