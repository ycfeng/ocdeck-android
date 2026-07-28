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
| Android 构建 | App 的 Debug、内部 Kotlin Canary 与 GoMobile/Kotlin Release-Like 真机测试 variant 的单元测试和 APK 构建，以及 `:frpc-stcp-visitor` 单元测试。另有独立的签名 Kotlin-default dry-run 使用正式身份与证书，但永不可发布。 |
| Android instrumentation 测试 | 覆盖 Popup 与 modal bottom sheet 独立 Compose 窗口根的本地化，以及 `:frpc-stcp-visitor` 中依次对真实 GoMobile 与 Kotlin backend 运行同一固定 frp 拓扑的 test-only harness。 |
| 人工 UI/无障碍验证 | 紧凑屏幕、200% 字体、IME 遮挡、项目选择页与 Drawer 排序、项目文件选择、Provider auth/OAuth/Custom Provider 流程、TalkBack 语义/操作、浅色/深色主题和真实模型设置导航。 |
| Release 制品校验 | 按 profile 校验 APK metadata、单 signer、预期证书指纹、ABI 隔离、`zipalign -P 16`、backend class/native 策略、内嵌法律文件、文件名和 checksum。GoMobile 将 `libgojni.so` 绑定到 AAR sidecar；Kotlin-default 拒绝 bridge class 和全部 `libgojni.so`，同时仍校验其他 native library。 |
| 真机验证 | 维护者已记录 `0.1.0` 发布门禁通过真机 native load/启动、16KB page-size native 运行，以及覆盖 `/global/health`、代表性 REST、全局/项目 SSE 和受控重连的真实 STCP 闭环。具体环境信息未公开；后续候选版本仍须重复执行这些门禁。 |

显式请求的 K6V workflow 已为 STCP backend 互操作提供固定的 API 26 `compat` 与 API 36 `full` x86_64 emulator lane，但普通 CI 仍没有 App UI emulator/instrumentation job。K6V workflow 进入默认分支后可直接手动触发；首次引入该 workflow 时，可通过手动 CI dispatch 显式启用同一个分支内 reusable workflow。Push 与 Pull Request CI 绝不会自动启用该 bootstrap。`app/src/androidTest` 测试集覆盖独立窗口根的本地化。最近项目拖拽排序仍不在 instrumentation 覆盖范围内；其自动化覆盖主要来自下文列出的 JVM reducer、recorder、ViewModel 和 Drawer model 测试。项目选择、会话导航、更广泛的 Composer 交互与 picker、permission/question UI、大字体行为与 TalkBack 仍需系统化设备自动化测试。

## 聚焦测试清单

- `RetrofitInboundResponsePolicyTest` 与 `EncodedResponseLimitInterceptorTest` 验证每个 `OpenCodeApi` 方法声明 `BOUNDED` 或 `EMPTY_SUCCESS`；缺少策略时在网络请求前失败；encoded/decoded 已声明、未知和低报长度都在 `max + 1` 执行各自 16 MiB 边界；真实 OkHttp gzip chain 在 Bridge 解码前执行 encoded 上限；非 2xx 与成功 Unit body 不读取即关闭；`/file/content` 保持延迟读取；没有 Retrofit `Invocation` 的请求绕过 Retrofit interceptor。
- `SessionMessagesTransportTest`、`SessionMessagesResponseReaderTest`、`InMemoryOpenCodeStoreRevisionTest`、`SessionMessageJumpTest`、`FileContentResponseReaderTest` 覆盖 cursor query/header、旧页合并、重叠刷新保留 cursor、不相交刷新补齐断层、cursor compare-and-set 与循环拒绝、第一页请求逆序完成时最后 generation 获胜、`(createdAt, id)` 同时间排序、第一页覆盖窗口删除校准、session 删除后的 cursor 与待处理 generation 清理、空投影时间线加载与跳转控件可用性、无 body 的非 2xx 失败、OkHttp callback 线程 direct decode、encoded/decoded 精确/未知/低报长度、EOF 验证、取消与 callback race、session messages 每页独立 64 MiB 边界，以及 `/file/content` reader 层 decoded 纵深防御。
- `OpenCodeFailureTest`、`ErrorUiTextTest`、`OpenCodeRepositoryFailureHandlingTest` 覆盖不读取 `Throwable.message` 的语义分类，包括将 `KotlinFrpcStcpVisitorFailure` enum 映射为 App failure 和本地端口拒绝；带操作 fallback 的本地化资源映射、Repository 传播、response-too-large 行为，以及取消与 JVM `Error` 传播。
- `BoundedSseReaderTest`、`OkHttpSseEventSourceFactoryTest`、`OpenCodeEventClientLifecycleTest`、`ProjectSnapshotCoordinatorTest` 覆盖显式 identity encoding、非 identity 零读取拒绝、所有换行与 EOF 状态、32 MiB 行/event 边界、无 body status/MIME 失败、取消、重试分类、关闭终态、每条连接独立认证但仅共享无认证 transport dispatcher/pool 的 client、匹配的 64-call dispatcher 与 fail-fast 准入上限及终态名额复用、owner/generation/source/transport 竞态、project/global 权威切换、有界重放和快照失败/恢复。
- `FrpcStcpReadinessRetryClassifierTest` 与 `GoMobileFrpcStcpVisitorClientTest` 覆盖 readiness 瞬时/永久失败、入站策略失败、typed unavailable/API mismatch bridge 错误、安全 bridge 摘要、API v2 JSON、revision/control epoch、`WaitVisitorReady`，以及反射取消/JVM `Error` 传播。
- `FrpcStcpVisitorClientFactoryTest` 在每个已启用的 App 单元测试 variant 中运行，验证 Debug 与 GoMobile Release-Like 选择 `GoMobileFrpcStcpVisitorClient`，Canary、Kotlin Release-Like 与正式签名 Kotlin-default dry run 选择 `KotlinFrpcStcpVisitorClient`，且显式 factory 可构造任一 backend，不存在运行时 fallback。
- `FrpcStcpVisitorManagerTest` 覆盖共享 generation/lease/readiness 行为、分离的冷启动 10 秒等价预算与更长同 generation epoch-refresh native 预算、可重试 native/application timeout 的 generation 保留、成功/失败/超时/取消时 readiness health-probe owner 均被关闭，以及将 `BindException` 和类型化 Kotlin bind failure 转换为 `LocalPortInUse`、有界前序 generation bind 重试，并确认非 bind 类型即使 message 含有类似 bind 的文字也不会被误判。
- `OpenCodeConnectionClientTest` 与 `ServerRepositoryConnectionCacheTest` 覆盖自持有 client 和 single-API health client 的幂等清理；按服务器重复和并发复用 REST/OAuth/session-message client；服务器间隔离；已提交与被拒绝的配置变更；STCP control epoch 替换且不缓存一次性 readiness health evidence；以及 transport 最多重新捕获三次后返回类型化持续抖动失败。
- `DefaultNetworkMonitorTest` 与 `AppConnectionCoordinatorTest` 覆盖默认网络 identity 去重且不记录或持久化、当前网络丢失通知、陈旧丢失拒绝、不含身份的 callback/dispatch 与恢复计数、backend 失效合并、活动 flight 陈旧结果抑制、并发切网后精确一次合并 server follow-up，以及当前恢复检查失败后最多一次有界 follow-up。
- 内部 control 与 crypto 测试还覆盖：重连已经取得 mux、但在 control Open 前失败时的有界清理、随后发布的类型化 retry failure、使用相同 RunID 的延迟重连响应超过首次登录预算但仍在独立重连登录预算内成功且不产生第三次拨号、独立登录预算定时器到期时主动 reset 一个忽略线程中断的 control read、下一次尝试保留“此前已发生登录超时”的安全证据、Yamux 类型化失败穿过 crypto wrapper 时保持不变、固定且不含 secret 的 crypto 分类，以及同 generation 后续恢复。
- `KotlinFrpcStcpVisitorClientTest` 及内部 control、crypto、protocol、transport、yamux、compression 测试覆盖类型化 runtime failure、revision/control epoch readiness、显式同 generation transport 失效与新 epoch 重连、每个 Open session 的失效、Android route 注入中跨 TCP 连接、TLS 创建、握手和流获取的有界解析候选回退且原始配置 endpoint 保持为 TLS 身份、物理关闭后由重连专用 watchdog 覆盖全部旧会话清理屏障、强制中止卡住的 Yamux 逻辑 join 但不提前完成终态、同 generation epoch 推进、保持不变的严格显式关闭语义及取消/JVM `Error` 传播、listener 所有权与重绑、v1/v2 visitor handshake、`useEncryption`/`useCompression` 四种组合、握手与 payload 合并读取、有界 relay 生命周期、本地停止后延迟的 best-effort reset 不阻塞 `stopVisitor`、session-owned permit 释放、Snappy framing 与损坏输入边界、清理、取消和无 secret 诊断。
- `SocketFrpLocalListenerFactoryTest` 建立并主动关闭真实 loopback relay，随后验证完整停止的 Kotlin generation 可立即重绑同一端口，而活动 listener 仍保持独占所有权。
- `FrpcStcpVisitorClientDifferentialContractTest` 对脚本化 GoMobile Kotlin adapter seam 和可注入纯 Kotlin runtime fixture 执行相同六个公共操作，比较归一化的 phase/revision/epoch/listener/bind 语义、幂等、替换、类型化 bind conflict、取消身份和安全诊断。它是 host-JVM adapter/runtime 契约测试，不加载 native AAR，也不替代真实 frps/设备互操作测试。
- `FrpcStcpVisitorAndroidInteropTest` 由 `frpcAndroidInteropTest` 启动，对每个选中的 `compat` 或 `full` profile，分别在独立 instrumentation 进程中运行真实生成的 GoMobile AAR 与纯 Kotlin backend。它要求完整 `Closed`/端口释放后才同端口重绑；覆盖所有配置的 wire/payload 成功组合，并保持 global/project SSE 在线，同时并发执行 health、两次 echo、两次超窗口下载且读取同一 checkpoint；覆盖错误 token、错误 STCP secret、bind 冲突和重启/control epoch 恢复；还会生成严格的结构化 backend 等价证据，不存在活动 generation 内 fallback。
- `FrpcStcpVisitorSerializationContractTest`、`FrpcStcpVisitorFixtureContractTest` 与 `FrpcStcpVisitorManagerContractTest` 覆盖可实现的 suspend bridge API，以及稳定的 DTO 默认值、字段名、`Long` 值、容错 JSON、Go/Kotlin 共享 bridge DTO JSON 和安全摘要；Kotlin 对带版本 canonical STCP manifest 及小型 wire/control/yamux/payload 字节的加载与完整性校验，包括 Go Snappy raw/framed、AES-CFB 加 Snappy 跨语言向量以及声明的分块方案和 mutation recipe metadata；以及 manager 对 native-ready 结果、session 身份、运行时与终态恢复、control epoch 回退、更长 epoch-refresh native 等待、native/application timeout generation 保留、最终 ensure bind port、清理/替换和无 secret 诊断的校验。
- `SensitiveValueToStringTest` 验证 network、domain、Store、feature 和 UI value 摘要不暴露人工凭据、URL/endpoint、alias、路径、prompt、Base64、SSE payload 或 tool output，同时保持普通 value object 行为。
- `OpenCodeContrastTest` 对浅色/深色主题执行 4.5:1 文字与 3:1 图形对比度门禁，覆盖主题文字、语义化 Diff/Markdown/语法/图表颜色、状态指示、附件遮罩、选择边框和 `ControlBorder`。
- `SessionRunningIndicatorTest` 覆盖 4×4 四角遮罩、每个点独立的 1–2 秒节奏、有界相位偏移、满足无障碍要求的透明度与缩放范围、可见帧变化、差异化初始帧和公共循环的无缝连续性。
- `RecentProjectStoreReducerTest` 覆盖数值 `sortOrder`、旧数组顺序保留、连续重编号、新项目置顶、已有项目 metadata 更新时位置稳定、比较 key 去重、原子重排且只显式纳入当前项目、保留未提交/并发新增项目、不复活并发删除的陈旧记录、服务器间隔离和每服务器 20 项上限。`RecentProjectRecorderTest`、`ProjectPickerViewModelTest`、`ProjectDrawerModelTest`、`ProjectInitialTest` 与 `VerticalLazyListReorderTest` 覆盖已有项目不移动的串行/重试 best-effort 记录、不等待 DataStore 的导航、展示顺序持久化与失败回滚 callback、有界当前项目合并、项目首页导航决策、Unicode 项目首字和短视口边缘滚动方向。
- `SessionListWindowCoordinatorTest`、`InMemoryOpenCodeStoreSessionWindowTest` 与 `OpenCodeRepositorySessionWindowTest` 覆盖共享 20 条目标、50 条请求余量、本地展开与网络加载、重试/末尾状态、有序前缀替换、快速点击合并、旧 generation/transport 拒绝、tombstone、项目/workspace 隔离和有界 metadata/父链补取。
- `NotificationAlertPolicyTest`、`NotificationChannelMigrationPolicyTest`、`OpenCodeNotificationAudioAttributesTest` 与 `SessionVisibilityRegistryTest` 覆盖单事件唯一声音所有者、App/系统设置独立、系统显式声音/静音优先级、legacy 到 v2 迁移决策、notification audio usage 和前台 destination 可见性。
- `SessionComposerAgentResolverTest`、`SessionModelPreferenceResolverTest` 与 `SessionComposerRouteSelectionTest` 覆盖按服务端顺序过滤 Build/Plan 及回退、初始模型/Variant 校验与切换回退，以及只对新会话接受项目首页轻量 Composer 路由选择。
- `ComposerParameterPickerScrollTest` 覆盖跨 provider 标题的模型 lazy-list 索引、带“默认”前缀的 Variant 索引、选择不存在时的行为，以及基于实测 item 与 viewport 的居中偏移。
- `LocalizedWindowTest` 是 Android instrumentation 测试：它为父组合提供与 Activity 不同的 locale，验证 Popup 与 modal bottom sheet 的资源使用父组合 locale，并验证已打开的 Popup 会随语言切换更新。
- `ProjectFilePathNormalizerTest`、`ProjectFileUrlBuilderTest` 与 `ProjectFileAbsolutePathTest` 覆盖相对路径平台语义、遍历/绝对路径拒绝、POSIX/Windows 盘符/UNC `file://` 与展示路径构造、UTF-8 百分号编码、往返和项目根包含关系。`ProjectFilePanelCopyMenuTest` 覆盖文件/目录长按菜单、三种复制值以及选择模式长按不改变选择。
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

这仍是普通小型 App 改动的最低门禁，并不要求每项此类改动都构建内部或 Release-Like variant。修改 STCP backend 选择、纯 Kotlin backend、共享 STCP manager 集成、共享 Release-Like instrumentation 或 CI/Release variant 验证时，还需运行 `:app:testCanaryUnitTest`、`:app:testGoMobileReleaseUnitTest`、`:app:testKotlinReleaseUnitTest`、`:app:assembleCanary`、`:app:assembleGoMobileRelease`、`:app:assembleGoMobileReleaseAndroidTest`、`:app:assembleKotlinRelease`、`:app:assembleKotlinReleaseAndroidTest` 与 `:app:verifyPureKotlinPackaging`。两个 AndroidTest assembly 会分别为两种 backend 编译并打包共享 Release-Like 测试源码与 manifest。打包任务会解析两个 Kotlin runtime classpath 并检查每个 ABI APK，拒绝 GoMobile bridge module 或任何 `libgojni.so` 条目。下方完整 bridge/等价 CI 门禁始终运行这组扩展验证。

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

先生成 GoMobile AAR，再启动新的 Gradle invocation，使配置期 bridge 依赖可见。`ocdeck.frp.androidInterop.suite` 属性为必填项，只接受 `compat` 或 `full`。以下示例优先展示 `full` 并请求验收 summary；其父目录必须存在，且 `<new-summary-path>` 目标本身在运行前必须不存在：

```powershell
.\frpc-stcp-visitor-go\build-aar.ps1
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcAndroidInteropTest `
  -PrequireGoMobileBridge=true `
  "-Pocdeck.frp.androidInterop.deviceSerial=<serial>" `
  "-Pocdeck.frp.androidInterop.suite=full" `
  "-Pocdeck.frp.androidInterop.summaryFile=<new-summary-path>"
```

```bash
bash frpc-stcp-visitor-go/build-aar.sh
./gradlew --no-daemon :frpc-stcp-visitor:frpcAndroidInteropTest -PrequireGoMobileBridge=true \
  "-Pocdeck.frp.androidInterop.deviceSerial=<serial>" \
  "-Pocdeck.frp.androidInterop.suite=full" \
  "-Pocdeck.frp.androidInterop.summaryFile=<new-summary-path>"
```

普通本地运行如果不需要验收 summary，只省略 `ocdeck.frp.androidInterop.summaryFile`；显式 suite 仍为必填项。

Host coordinator 与 `frpcInteropTest` 共用经哈希固定的官方 frp v0.69.1 工具，启动仅监听 loopback 的 TLS `frps`、provider `frpc` 与有界合成 server，并创建一条由本次运行拥有的动态 `adb reverse` 映射。它会拒绝设备集合不明确、远程 adb-server 路由以及预先安装的测试 package。人工合成凭据通过有界 stdin 写入测试 package 私有 files 目录，绝不作为 Gradle property 或 instrumentation argument 传递。结果只包含固定结构字段；清理只移除本次运行拥有的 package、私有文件与 reverse 映射。

`compat` suite 只包含 `success-v1-00`。`full` suite 精确包含 12 个 profile：`success-v1-00`、`success-v1-01`、`success-v1-10`、`success-v1-11`、`success-v2-00`、`success-v2-01`、`success-v2-10`、`success-v2-11`、`negative-wrong-token-v2-00`、`negative-wrong-secret-v2-00`、`negative-bind-conflict-v2-00` 和 `restart-v2-11`。其中八个成功 profile 是 wire v1/v2、加密关闭/开启、压缩关闭/开启的笛卡尔积。

每个选中 profile 都先在新的 instrumentation 进程中运行 GoMobile。只有完成 `stopVisitor`、`stopSession`、`Closed` 校验和 listener 端口释放后，Kotlin 才在另一个 instrumentation 进程中运行并重绑同一端口。每个成功 profile 都保持 global/project SSE 在线，同时并发执行 health、两次 echo 和两次大于 Yamux 窗口的下载；两条 SSE 必须读取同一个正数 checkpoint。重启 profile 还要求既有 SSE 断开、control 变为 unavailable、Host 等待重启后的 `frps` ready 与 provider `frpc` 重连两个屏障、恢复后的 control epoch 严格大于初始 epoch，并通过恢复后的 REST、SSE 与超窗口流量。

Host summary 是严格的结构化证据，不是 task 状态的替代品。它记录 suite、设备 API/ABI/page size、精确且有序的 profile、每个 profile 的 scenario/wire/encryption/compression 参数、`gomobile,kotlin` backend 顺序、每个 backend 的精确 checks、语义等价性以及 `authorizesKotlinDefault=false`。字段缺失、乱序、不完整或不一致都会校验失败；不能只用 Gradle task success 充当 profile 证据。

显式请求的 `.github/workflows/frpc-kotlin-android-interop.yml` 会对精确候选 SHA 固定运行 API 26 x86_64 `compat` 与 API 36 x86_64 `full`。它同时支持直接 `workflow_dispatch` 与只读 `workflow_call`；后者仅在手动 CI dispatch 设置 `run_frpc_android_interop=true` 时使用，用于该 workflow 尚未进入默认分支时调用分支内版本。CI 的可选 `candidate_sha` 默认采用所触发 ref 的 SHA，push/PR CI 绝不会调用 K6V。Preflight 会运行报告 renderer 单元测试。Renderer 只有在两份 lane 文件及其 Host summary 都满足精确 lane/profile 契约时才通过，否则 fail closed；它不会从 Gradle outcome 推断 profile 成功。Host 与 lane 证据优先通过 `adb shell getconf PAGE_SIZE` 读取 Android runtime ABI page size；只有旧版 Android shell 报告该命令不存在时，才回退到 `/proc/self/smaps` 中首个有界的 `KernelPageSize` 或 `MMUPageSize` 条目。这样既保留 API 26 覆盖，也避免把 MMU backing page 仍为 4 KiB 的 x86_64 16 KiB Android 测试镜像错误分类。每个 lane 还会记录实际 Android test APK 与 GoMobile bridge AAR 的 SHA-256。Workflow 会上传有界的中英文验收报告、合并 JSON 证据和 `SHA256SUMS`；它只有仓库只读权限，不使用签名 Environment 或 secret，并且每份报告都保持 `authorizesKotlinDefault=false`。

已记录的扩展 K6V 证据：精确候选 `01ba5437276fb5074da5c654219668ac4cb69d48`（tree `22a32b9027fb6731bad57e10a1793863f5348d87`）已通过 [workflow run 29763640048](https://github.com/ycfeng/ocdeck-android/actions/runs/29763640048)，其中普通完整验证 job 也构建并测试了 Kotlin Release-Like variant。API 26 Android 8.0.0 完成单 profile x86_64 `compat` suite，API 36 Android 16 完成 x86_64 12-profile `full` suite；两者均报告 4 KiB page。合并 artifact `k6v-frpc-android-interop-01ba5437276fb5074da5c654219668ac4cb69d48` 状态为“通过”，GitHub artifact digest 为 `sha256:f045f29dec1b705d9058fdafeb07917a19365885611ed71cb706c90c34d5ebaf`。报告验证了固定 backend 顺序、语义结果、精确 checks、同端口重绑、全部 wire/payload 组合、三个负例以及 restart/control-epoch 恢复。它仍设置 `authorizesKotlinDefault=false`，且不能单独满足分离的目标 ABI 物理 ARM 与 16KB runtime lane、App 的真实 Store/快照/reconciliation 链路、Doze/网络/前后台、性能、资源泄漏、长期 soak 或正式稳定发布周期门禁。ARM 与 16KB lane 可以使用不同设备；缺少物理 ARM 16KB 硬件记录为残余组合覆盖风险，而不是开发阻塞项。

更早的第一阶段证据仍记录于精确候选 `459c2b57ebf465d6b933ea939f59fa739128ec59`（tree `a6ce1019adfa351bc15e09bc479220a967bdf323`）和 [workflow run 29716724485](https://github.com/ycfeng/ocdeck-android/actions/runs/29716724485)。它只覆盖旧的单一 GoMobile 后 Kotlin v1/plain 场景；对于当前 K6V 范围，已由上述扩展证据取代。

## Release-Like App STCP E2E

专用 App E2E 任务会安装不可发布的 Release-Like target APK 及其 test APK，并通过选定 STCP backend 验证真实应用装配。`frpcAppE2eTest` 使用 `kotlinRelease`；`frpcGoMobileAppE2eTest` 使用对称的 `goMobileRelease` 身份。两个 variant 都使用 application-ID suffix 与 Android Debug 测试证书，都不是发布制品。共享 Host test 配置会校验保留的 bridge，因此先生成 GoMobile AAR，再启动新的 Gradle invocation。可选 summary 的父目录必须存在，且 `<new-summary-path>` 在运行前必须不存在：

```powershell
.\frpc-stcp-visitor-go\build-aar.ps1
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcAppE2eTest `
  -PrequireGoMobileBridge=true `
  "-Pocdeck.frp.appE2e.deviceSerial=<serial>" `
  "-Pocdeck.frp.appE2e.suite=<core|lifecycle|process>" `
  "-Pocdeck.frp.appE2e.summaryFile=<new-summary-path>"
```

```bash
bash frpc-stcp-visitor-go/build-aar.sh
./gradlew --no-daemon :frpc-stcp-visitor:frpcAppE2eTest -PrequireGoMobileBridge=true \
  "-Pocdeck.frp.appE2e.deviceSerial=<serial>" \
  "-Pocdeck.frp.appE2e.suite=<core|lifecycle|process>" \
  "-Pocdeck.frp.appE2e.summaryFile=<new-summary-path>"
```

只有恰好连接一台合格设备时才可省略 `ocdeck.frp.appE2e.deviceSerial`；省略 `ocdeck.frp.appE2e.suite` 时运行默认 `core` suite；普通本地运行不需要验收 summary 时可省略 `ocdeck.frp.appE2e.summaryFile`。GoMobile 的 `core`、`lifecycle` 或 `process` 路径使用 `frpcGoMobileAppE2eTest`。`network` suite 只允许 Kotlin，因为 GoMobile 无法执行所需 leaf-pin 契约，必须 fail closed，不能降低验证。`lifecycle` suite 可选传入范围为 60,000-300,000 ms 的 `ocdeck.frp.appE2e.outageMillis`，默认 120,000 ms。Host 会准备固定版本的官方 frp 工具、仅监听 loopback 的 TLS `frps`、provider `frpc`、有界合成 OpenCode REST/SSE fixture 和一条由本次运行拥有的动态 `adb reverse`。它安装匹配设备 ABI 的选定 backend APK 及仅 test APK 可调试的 instrumentation APK；target App 仍保持 Release-Like 且不可调试。每次运行的一次性凭据通过有界 stdin 和 `run-as` 写入 test package 私有存储，绝不进入 Gradle property、instrumentation argument、共享存储或结果证据。清理会 force-stop 并移除本次运行拥有的两个 package、私有文件、reverse mapping、进程、socket、临时材料和强制 device-idle 状态。

Instrumentation 使用真实 `OpenCodeApplication`/`AppContainer`、`ServerRepository`、`FrpcStcpVisitorManager`、`OpenCodeRepository`、`OpenCodeEventClient`、`InMemoryOpenCodeStore` 与 `ProjectSnapshotCoordinator`。`core` suite 保留严格的 13 项 snapshot/SSE/restart/reconciliation 契约。19 项 `lifecycle` suite 还会真实驱动 `MainActivity` 多轮前后台切换，验证会话可见性和 Store 数据保留，进入并退出已确认的 deep Doze，保持跨多轮退避的断连窗口，并在 provider `frpc` 尚未恢复时先让 `frps` 可用。Kotlin 恢复必须保持 visitor generation 不变并严格增加 control epoch；保留的 GoMobile 基线可以替换已经失败的 generation，但完整 transport identity 必须单调前进。manager 冷启动 native readiness 保持 10 秒，Kotlin 同 generation epoch refresh 使用 90 秒以覆盖 60 秒重连登录窗口，application health 另有 15 秒总预算。Kotlin 非终态恢复中的可重试 native 或 application health timeout 只让当前 refresh flight 失败，不再销毁已建立 generation，从而允许后续真实恢复安全完成。

两阶段 `process` suite 会持久化一条 STCP server 及其 Keystore 凭据，关闭第一阶段 runtime 资源，再启动普通 target 进程、切到后台，并要求 `am kill` 清除已观测到的旧 PID；随后启动第二个 instrumentation 进程。新的 `OpenCodeApplication` 必须重新加载唯一持久化 server，重建 STCP/REST/SSE 状态，并通过显式 project/session route 后删除持久化 server、调用正常凭据清理链路。它不声称自动恢复导航，也不跨进程比较 generation/control epoch。聚合的 19 项 checks 包含 `process_terminated` 以及 prepare/recovery 契约。请求 summary 时，Host 还要求仓库完全干净，并把结果绑定到小写 candidate commit/tree，以及本地和已安装 target/test APK 的 SHA-256。只有清理成功后才原子发布有界 summary。每份 summary 记录 backend、suite、topology、固定 checks、字节身份和 `authorizesKotlinDefault=false`；绝不记录 PID、server ID、endpoint、凭据、设备 serial、APK 安装路径、私有路径或原始日志。

Fail-closed 的 `network` suite 使用不同拓扑，必须显式指定 USB 物理 ARM 设备和私有配置文件，单独运行：

```powershell
$externalConfigRoot = Join-Path ([System.IO.Path]::GetTempPath()) "opencode"
$env:OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE = Join-Path $externalConfigRoot "<private-config-file>"
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcAppE2eTest `
  -PrequireGoMobileBridge=true `
  "-Pocdeck.frp.appE2e.deviceSerial=<usb-serial>" `
  "-Pocdeck.frp.appE2e.suite=network" `
  "-Pocdeck.frp.appE2e.summaryFile=<new-summary-path>"
Remove-Item Env:\OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE
```

```bash
OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE="${TMPDIR:-/tmp}/opencode/<private-config-file>" \
./gradlew --no-daemon :frpc-stcp-visitor:frpcAppE2eTest -PrequireGoMobileBridge=true \
  "-Pocdeck.frp.appE2e.deviceSerial=<usb-serial>" \
  "-Pocdeck.frp.appE2e.suite=network" \
  "-Pocdeck.frp.appE2e.summaryFile=<new-summary-path>"
```

私有文件采用严格 schema 3 JSON，字段为 `schemaVersion`、`frpsHost`、`frpsPort`、`authToken`、`tlsServerCertificateSha256` 与 `tlsTrustedCaCertificatePem`。`<public-ip-or-strict-dns-name>`、`<64-lowercase-hex>`、`<single-pinned-leaf-certificate-pem>` 等结构占位符只描述形状，刻意不是可运行值。endpoint 必须是公网 IPv4/IPv6 literal 或严格的多 label ASCII DNS 名称。Host 在安装前解析 DNS，设备每个阶段再通过当前 active validated Android `Network` 解析；所有返回地址都必须是公网地址，仅设备逐 network DNS 允许 Android NAT64 结果。DNS 路由不是身份边界。token 只允许 16-256 个 ASCII 字母、数字、`_` 或 `-`。pin 是 leaf certificate DER 的小写 SHA-256。PEM 必须只包含同一张仍在有效期内的非 CA leaf certificate、最大 8 KiB，且证书必须对配置 IP 或 DNS 名称有效。完整配置最大 16 KiB；文件必须是具体非 symlink 的 `<java.io.tmpdir>/opencode` 受信根的直接子文件，在平台支持时相对安全目录 handle 打开，且不跟随链接。受信根和文件必须由运行 Gradle 的账户拥有，并采用 owner-only 权限：POSIX 为 `0700`/`0600`，Windows DACL 的敏感 allow 条目只能指向该精确 owner。harness 对生成的 provider 凭据也应用相同的 owner-only 边界。该边界会拒绝其他 OS 账户访问，但不声称防御已经以同一账户运行的恶意进程。不得通过 Gradle property、instrumentation argument、共享存储、报告或文档传递 path、token、pin、证书或 endpoint；只在专用测试 environment 中通过 `OCDECK_FRP_APP_E2E_EXTERNAL_CONFIG_FILE` 提供路径，并在结束后清除。

受控 external `frps` 必须强制 TLS、使用匹配的 token auth、提供配置中的非 CA leaf certificate，并可从 Wi-Fi 与蜂窝直接访问。发送 token 前，固定版本的本地 provider `frpc` 会使用有界的精确 leaf 证书文件和配置的 IP 或 DNS server name 校验服务端；Kotlin visitor 独立校验 leaf DER SHA-256 pin。GoMobile 收到非空 pin 时会 fail closed，不会静默忽略。持久化配置仍引用 pin alias 但对应值不可用时也会 fail closed，不会退回 trust-all TLS。只有 frps 地址和端口均未变化时才可保留已保存 pin；任一 endpoint 字段变化都必须输入新 pin。Host 拥有本地 provider 与有界合成 OpenCode fixture，不创建 `adb reverse`，拒绝任何预存 reverse mapping，并要求显式选择具备 validated Wi-Fi/蜂窝的 USB 物理 ARM 设备。

完整 19 项 `network` 证据由 17 个有序 instrumentation checks，加上 Host 断言 `configured_pinned_public_server` 与 `network_restored` 组成。它要求初始默认网络为 validated Wi-Fi，同时存在 validated 蜂窝网络。每个切换方向都会先停止 provider `frpc`，在保持旧 fixture generation 的同时切换 validated 默认 transport，要求两条 SSE 都不再 Open，并精确证明旧 snapshot/global/project 数据仍保留在 Store。只有通过该断线保留屏障后，Host 才推进 fixture 并重启已认证的 provider。恢复使用 90 秒同 generation native readiness 预算，必须保持 visitor generation，严格增加 control epoch，在蜂窝上校准 generation-2 数据，并在返回 Wi-Fi 后校准 generation-3 数据。逐 network DNS 会在有界窗口内重试短暂 active-network 或 resolver 未就绪，但一旦返回空结果或任一非公网地址就立即 fail closed。所有由本次运行拥有的失败路径都会恢复原 Wi-Fi/mobile-data baseline。通过的 summary 必须报告精确 17+2 契约、`topology=external`、`networkSwitchProven=true`、clean candidate/APK 字节身份、清理成功和 `authorizesKotlinDefault=false`，且不包含 endpoint、token、pin、证书、网络标识、设备 serial、安装路径或私有路径。

`CanaryConfiguredExternalNetworkTest` 是面向已配置 Canary 安装的独立只读门禁。只有确认本地构建的 Canary APK 与设备已安装 target 字节完全一致后，才只安装 `app-canary-androidTest.apk`；门禁不得替换或卸载 target。目标应用自有的前台 Activity 会在 instrumentation 切网期间保持 App 进程可运行。测试要求恰好一条启用的 STCP server、公网 IP literal 或严格公网 DNS endpoint，以及已持久化的 TLS leaf pin。它会通过每个 active validated network 解析 DNS，使用真实配置执行 Wi-Fi→蜂窝→Wi-Fi 恢复，绝不序列化 Store 内容，并保持持久化 server 不变。进度通过 target 私有存储中的原子替换有界文件发布，只包含 allowlist 粒度 stage、check count、status/failure code 与 heartbeat count。缺少 pin、pin alias 不可用、返回非公网解析或 server 选择不唯一时均 fail closed。外层清理始终只卸载 test package、删除进度文件、恢复 Wi-Fi/mobile-data、确认无 reverse mapping，并独立确认 target 仍已安装。

该任务目前是独立的手动/设备门禁，不属于普通 push 或 Pull Request CI。Clean exact 候选 `4b0df8af207b28846423902a61bf261501692101` 已在官方 Android 16KB x86_64 测试镜像和物理 `arm64-v8a` 设备上分别通过 `core`、`lifecycle` 与 `process`。另有一份已配置的 working-tree Canary 构建在显式 USB 物理 ARM 上、无 `adb reverse` 地通过全部 19 项 external `network` 检查，证明 validated Wi-Fi→蜂窝→Wi-Fi、精确断线 Store 保留、同一 visitor generation、两次严格增加的 control epoch、global/project SSE 恢复和 reconciliation。独立清理保留 target，只移除 test package 与私有进度文件，恢复两个网络开关，并确认无 reverse mapping。该未提交结果不冒充 exact-SHA 候选。性能、资源泄漏压力、soak、exact-candidate 重复与正式稳定发布周期仍是独立门禁，任何 suite 都不授权 Kotlin 默认装配。

随后，clean exact 候选 `a3b10394ad575c2895aeb9f8980b2b120dccf320`（tree `afad377b2ad910e95bbbe3e70379c8e894ab85ce`）在同一台 API 31、4 KiB page 的物理 `arm64-v8a` 设备上通过完整 Kotlin Release-Like external 契约：17 个 instrumentation checks、两个 Host 断言、validated Wi-Fi→蜂窝→Wi-Fi 切换、target/test APK 字节一致，以及清理后原子发布 summary 均通过，并保持 `authorizesKotlinDefault=false`。Windows Host 所在代理会返回保留的 `198.18.0.0/15` Fake-IP，因此本次运行使用了 owner-only 临时 JDK hosts 文件，以 HTTPS DNS-over-HTTPS 获得的公网 A 记录填充，并在运行后删除。该覆盖只影响 Host 安装前解析；Android 仍通过每个 active validated Wi-Fi/蜂窝 `Network` 独立解析配置名称，要求所有结果均为公网地址，并继续以精确 leaf pin 作为身份边界。这关闭了单设备 exact external 执行缺口，但所需设备矩阵、资源策略通过、更长时长 soak、稳定观察周期和 Kotlin-default 显式授权仍待完成。

### 对称资源、压力、泄漏与 Soak Harness

Release-Like 资源任务会在同一个 App/instrumentation 进程中，针对任一 backend 运行共享 `core` workload。`frpcGoMobileAppResourceTest` 与 `frpcKotlinAppResourceTest` 分别运行单一 backend；`frpcAppResourceTest` 会串行运行两者并应用版本化策略 `k7-resource-v1`。每个资源证据任务都要求显式提供非空 `ocdeck.frp.appE2e.deviceSerial`，从而把两次 backend 运行锁定到同一设备，同时不把 serial 写入证据。证据目录同样是必填项且必须已存在。`ocdeck.frp.appResource.backendOrder` 只接受 `gomobile,kotlin` 或 `kotlin,gomobile`，默认使用前者。聚合任务还会要求两份有界 summary 使用相同的干净候选、设备 metadata 与 workload 后才会成功：

```powershell
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcAppResourceTest `
  -PrequireGoMobileBridge=true `
  "-Pocdeck.frp.appE2e.deviceSerial=<serial>" `
  "-Pocdeck.frp.appResource.profile=baseline" `
  "-Pocdeck.frp.appResource.backendOrder=gomobile,kotlin" `
  "-Pocdeck.frp.appResource.evidenceDirectory=<existing-directory>"
```

```bash
./gradlew --no-daemon :frpc-stcp-visitor:frpcAppResourceTest -PrequireGoMobileBridge=true \
  "-Pocdeck.frp.appE2e.deviceSerial=<serial>" \
  "-Pocdeck.frp.appResource.profile=baseline" \
  "-Pocdeck.frp.appResource.backendOrder=gomobile,kotlin" \
  "-Pocdeck.frp.appResource.evidenceDirectory=<existing-directory>"
```

Allowlist profile 为 `baseline`、`stress`、`leak` 与 `soak`。可选有界属性包括 `ocdeck.frp.appResource.iterations`、`concurrency`、`durationMillis`、`reconnectEveryIterations` 与 `sampleEveryIterations`。iterations 上限为 100,000，concurrency 上限为 32，soak 时长范围为 60 秒到 24 小时；默认 soak 为 8 小时。soak 会把有界迭代预算按比例铺满请求时长，因此快速设备不会提前耗尽一个原本合法的配置。每个 workload 会执行有界并发 snapshot 和可选的显式 close/reconnect 循环，同时验证 transport identity 单调替换以及 global/project SSE 恢复。Summary schema 9 内嵌 App result schema 8。证据包含 wall time、进程 CPU、PSS、Java/native heap、文件描述符和线程计数，并通过 `head-tail-v1` retention metadata 及每个计划 sample 的 1-based ordinal 证明真实首尾窗口。Host 会根据已完成 iterations 与 `sampleEveryIterations` 推导 observed 数，再严格校验最初 127 个与最新 127 个 ordinal、retained-head、retained-tail 与 dropped count。非 soak profile 必须完成全部配置 iterations；soak 必须完成正数且有界的前缀，每个 profile 都必须报告精确 reconnect 数，两个 backend 的 workload result 必须完全相同。Scenario 清理完成后，每个非 functional workload 还会静默 90 秒并记录 quiescence snapshot；设备端与 Host 动态 timeout 包含这段额外的有界等待。输出文件为 `gomobile-app-resource.json` 与 `kotlin-app-resource.json`，保持 `authorizesKotlinDefault=false`，并采用与验收 summary 相同的 clean candidate/APK 字节身份及清理后发布规则。物理 ARM instrumentation 回归测试也已使用 1,000 个单调 sample 验证 producer 确实保留真实首尾窗口。

已有证据对无需重新构建或连接设备即可复核：

```powershell
.\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcValidateAppResourceEvidence `
  "-Pocdeck.frp.appResource.evidenceDirectory=<existing-directory>"
```

`k7-resource-v1` 只接受 summary schema 9，并把 `baseline` 视为短时结构/安全 profile。对于任一 backend 持续至少 60 秒的 `stress`、`leak` 与 `soak` 证据对，Kotlin CPU 不得超过 GoMobile 的 1.50 倍，wall duration 不得超过 1.25 倍，最后四分之一窗口平均 PSS 不得超过 1.25 倍再加 16 MiB。最后四分之一窗口平均 FD/线程数不得超过 256。对 `leak` 与 `soak`，最初到最后四分之一窗口的增长上限为：PSS 8 MiB 或 15%，Java/native heap 各 8 MiB 或 25%，FD 4 个，线程 8 个。在 90 秒 quiescence snapshot 中，每个 backend 相对 workload 前 start 的 FD 还不得增加超过 4，线程不得增加超过 8。完整设备矩阵、重复执行、环境控制、8 小时/24 小时 soak、观察与回滚要求以成对的[发布检查清单](../release/checklist.zh-CN.md)为 canonical 依据。

Harness、Host 校验和两个 backend instrumentation variant 已实现并通过编译验证。在一台 API 31、4 KiB page 的物理 `arm64-v8a` 设备上，working-tree smoke 先让 GoMobile 与 Kotlin 均通过 `core`、`lifecycle`、`process`，以及同机有界 `baseline`、`stress`、`leak` 和 60 秒 `soak`，但不生成验收 summary。随后，clean exact 候选 `e429f360f38331875edb1483dc80a3e6062f5cc0`（tree `9df9e5def5c921b58813e68739444c81c5b91fc3`）在同一设备上为两个 backend 分别生成并保留了成对 schema-8 JSON summary，覆盖默认 5 轮 `baseline`；100 轮、concurrency 8、共 10 次重连的 `stress`；200 轮且每轮重连的 `leak`；以及 concurrency 4、每 25 轮重连和采样的 1 小时 `soak`。8 份 summary 均报告精确 clean 候选、本地与已安装 target/test APK 字节一致、预期的完成轮数/重连数/sample 数、清理成功和 `authorizesKotlinDefault=false`；原结构 cross-summary validator 对每一对都通过。后续应用量化策略时，`baseline` 通过，但 `stress` 因 Kotlin/GoMobile CPU 2.801 倍失败，`leak` 因 CPU 2.309 倍失败，1 小时 `soak` 因 CPU 2.524 倍失败。这些覆盖完整 scenario 的 CPU 总量仍是有效的历史 No-Go 输入；但旧 retention 算法在超过 254 个 sample 后没有保留真实尾窗口，因此旧长 soak 的 thread/PSS 趋势不能作为最终验收证据。当前 `k7-resource-v1` 会拒绝旧 schema 8，并要求新的 exact schema-9 证据对包含真实 head/tail 与 quiescence 证据。Kotlin PSS 仍较低，wall ratio 仍在策略范围内。在新 exact 候选通过所需矩阵前，CPU 结果构成明确的 K7 资源 No-Go；不得在看到结果后通过放宽阈值绕过。受保护 Release workflow [run 30320443107](https://github.com/ycfeng/ocdeck-android/actions/runs/30320443107) 另行在 `main` commit `7d52ec6b8a17b92ffa76dea913cc1b7f269cf5f5` 上通过正式签名、经 backend 校验的三 ABI Kotlin-default dry run，并跳过发布。因此签名打包已得到证明，但不能覆盖资源 No-Go，也不授权 Kotlin 默认装配。

连接 emulator 或设备后，运行 instrumentation 测试集：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## 完整 Bridge 与等价 CI 门禁

准备 patched frp，并运行两组 Go race tests：

```bash
cd frpc-stcp-visitor-go
go run ./cmd/preparefrp
python3 ../.github/scripts/check-go-race-environment.py
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
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testGoMobileReleaseUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleGoMobileRelease :app:assembleGoMobileReleaseAndroidTest :app:assembleKotlinRelease :app:assembleKotlinReleaseAndroidTest :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true
```

Go race detector 需要 CGO 与受支持的 C 编译器。如果 Windows 上没有该工具链，必须在 WSL2、原生 Linux 或 GitHub Actions Ubuntu runner 中运行两组 Go race tests；Windows 普通 Go 测试不能替代 race 门禁。WSL1 的 socket 翻译可能允许重复绑定 loopback listener，使 listener ownership/bind conflict 断言失真，因此不支持作为该门禁环境。环境检查会明确拒绝 WSL1，而不是弱化或跳过这些测试。PowerShell 默认也不会在 native 进程失败时停止，因此下面使用一个小型 fail-fast wrapper。

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
Invoke-NativeChecked { python ..\.github\scripts\check-go-race-environment.py }
Invoke-NativeChecked { go test -race '-modfile=build/frp-patched.mod' ./... }
Push-Location .\build\frp-v0.69.1-p1
Invoke-NativeChecked { go test -race ./client/... }
Pop-Location
Pop-Location

Invoke-NativeChecked { python .github/scripts/audit-community.py }
Invoke-NativeChecked { python .github/scripts/audit-third-party.py }
Invoke-NativeChecked { .\gradlew.bat --no-daemon :frpc-stcp-visitor:frpcInteropTest }
Invoke-NativeChecked { .\.github\scripts\verify-bridge-reproducibility.ps1 }
Invoke-NativeChecked { .\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testGoMobileReleaseUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleGoMobileRelease :app:assembleGoMobileReleaseAndroidTest :app:assembleKotlinRelease :app:assembleKotlinReleaseAndroidTest :app:verifyPureKotlinPackaging -PrequireGoMobileBridge=true }
```

可复现脚本要求干净 checkout。它们会在当前主机平台构建当前 checkout，以及位于不同绝对路径的 detached worktree；两个构建分别隔离 `GOCACHE`、`GOMODCACHE` 与 `GOPATH`，并逐字节比较完整的 AAR、必需 sources JAR、POM、checksum、API、bridge/frp provenance 和 native sidecar 集合。脚本会删除临时 checkout 与 cache，但保留当前 checkout 中的主构建输出供 Gradle 门禁使用。这不代表 Windows 与 Linux 之间的字节一致性声明。CI 与 Release 使用 shell 脚本；Windows 开发者可运行对应 PowerShell 脚本。

固定 Go、x/mobile、Android API 与 NDK 版本必须来自 `bridge-versions.properties`。

修改 Go wrapper、downstream frp patch、Android bridge 模块、任一 STCP backend、App backend 选择、bridge API、失败处理或版本 metadata 时，必须执行固定 frp 互操作任务与完整 bridge 门禁，不能只运行 Android 单元测试。Android 门禁会验证 Debug/GoMobile、Canary/Kotlin、GoMobile Release-Like/GoMobile 与 Kotlin Release-Like/Kotlin 的选择和装配，编译并打包两个共享 Release-Like AndroidTest variant，并验证两个普通 Kotlin variant 的无 bridge runtime/APK 打包。仅修改 Kotlin bridge API 或失败处理时，生成 AAR 字节与 `BRIDGE_VERSION` 可以保持不变，但不能跳过上述任何门禁；native 或生成 AAR 字节发生变化时必须递增 `BRIDGE_VERSION`。

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
| 项目文件 picker | 树与搜索、文件/目录长按复制菜单、最多 10 个选择、独立预览、确认/取消、预览返回文件树、route/session 变化、上下文 chip、纯上下文发送/reset | 长按不打开、不展开且不切换选择；文件/目录名和规范化相对/绝对路径复制正确；选择具备 Checkbox 语义和非颜色提示；预览不切换选择；取消不修改草稿；旧路由不接收结果；IME、200% 字体、TalkBack 和两种主题下操作均可达 |

缺少 instrumentation 门禁必须作为测试缺口报告；人工检查只证明已测试设备/配置，不代表全部 Android 环境。

## 发布前人工验证

静态检查不能证明 Android 能在所有目标设备加载 native library，也不能证明真实隧道可用。正式发布前，必须完成并记录[发布检查清单](../release/checklist.zh-CN.md)中的人工项目，包括：

- 在可获得的目标发布 ABI 真机上执行 native load。
- 在通过 `getconf PAGE_SIZE` 报告 `16384` 的官方 Android 16KB 测试镜像或真机上执行 native load、应用启动和适用的互操作验证。
- 通过真实 frps/STCP visitor 路径完成 `/global/health`、REST 和 SSE。
- 使用 App 实际路径完成直连与 SSH smoke test。
- 对每个可获得真机的目标签名 ABI APK执行首次安装和启动。
- 从上一个真实公开版本执行覆盖更新并确认预期本地数据保留；首个公开版本应记录为不适用，不能宣称存在未经验证的升级证据。
- 确认 signer 不兼容和较低 `versionCode` 的覆盖安装会被拒绝。
- 验证破坏性回退与卸载/重装行为，包括应用私有本地数据丢失，以及不存在受支持导出/恢复流程的限制。
- 从公开 Release 重新下载，并执行完整 `SHA256SUMS` 校验和文档中的单 APK checksum 流程。

目标 ABI 真机 lane 与 16KB runtime lane 可以使用不同设备。物理 ARM 16KB 设备仍是理想的组合覆盖，但在目标 ABI 真机加载、官方 16KB runtime lane 以及静态对齐/打包门禁全部通过时，其缺失不阻塞开发。

对于 `0.1.0` 候选版本，维护者已记录上述 native load、16KB page-size 和真实 STCP 检查通过。具体设备与部署信息未公开，因此该证据只适用于此候选版本，不构成对全部 Android 环境或 OpenCode Server 版本的覆盖。其他未勾选人工项目仍相互独立，后续每个候选版本都必须重复执行适用门禁。详见[兼容性矩阵](../user/compatibility.zh-CN.md)。
