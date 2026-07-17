# 测试

[English](testing.md)

本文档是英文 canonical 测试文档的便利翻译。中英文发生差异时，以英文文档为准。

## 测试层级

OC Deck 使用多个相互独立的门禁。通过一个层级不代表其他层级也已通过。

| 层级 | 当前覆盖 |
| --- | --- |
| Kotlin/JVM 单元测试 | 路径与项目文件 URL、最近项目记录、会话窗口、通知/channel 策略、脱敏、Retrofit/direct encoded/decoded 与 identity-SSE 入站边界、类型化失败与本地化 UI 映射、安全 value 摘要、DTO 容错、Store revision、prompt/项目上下文状态与恢复、Provider auth/OAuth 与分阶段 custom-config 事务、服务器凭据事务、SSH/STCP 协调、对比度和 feature helper。 |
| Go race tests | GoMobile wrapper 与生成的 patched frp client 包。 |
| 第三方与法律审计 | 固定版本、依赖清单、哈希、provenance、许可证和发布脚本引用。 |
| Bridge 校验 | AAR checksum、Java API signature、bridge/frp provenance、预期 ABI、ELF machine、16KB `PT_LOAD` 对齐、stripped 状态和可复现性。 |
| Android 构建 | 两个 Android 模块的单元测试和 Debug APK 构建。 |
| 人工 UI/无障碍验证 | 紧凑屏幕、200% 字体、IME 遮挡、项目文件选择、Provider auth/OAuth/Custom Provider 流程、TalkBack 语义/操作、浅色/深色主题和真实模型设置导航。 |
| Release 制品校验 | APK metadata、单 signer、预期证书指纹、ABI 隔离、`zipalign -P 16`、AAR native 字节绑定、内嵌法律文件、文件名和 checksum。 |
| 真机验证 | 维护者已记录 `0.1.0` 发布门禁通过真机 native load/启动、16KB page-size native 运行，以及覆盖 `/global/health`、代表性 REST、全局/项目 SSE 和受控重连的真实 STCP 闭环。具体环境信息未公开；后续候选版本仍须重复执行这些门禁。 |

当前没有 `app/src/androidTest` 测试集，CI 也没有 emulator/instrumentation job。项目选择、会话导航、Composer 交互、picker、permission/question UI、大字体行为与 TalkBack 仍需系统化设备自动化测试。

## 聚焦 JVM 测试清单

- `RetrofitInboundResponsePolicyTest` 与 `EncodedResponseLimitInterceptorTest` 验证每个 `OpenCodeApi` 方法声明 `BOUNDED` 或 `EMPTY_SUCCESS`；缺少策略时在网络请求前失败；encoded/decoded 已声明、未知和低报长度都在 `max + 1` 执行各自 16 MiB 边界；真实 OkHttp gzip chain 在 Bridge 解码前执行 encoded 上限；非 2xx 与成功 Unit body 不读取即关闭；`/file/content` 保持延迟读取；没有 Retrofit `Invocation` 的请求绕过 Retrofit interceptor。
- `SessionMessagesTransportTest`、`SessionMessagesResponseReaderTest`、`FileContentResponseReaderTest` 覆盖无 body 的非 2xx 失败、OkHttp callback 线程 direct decode、encoded/decoded 精确/未知/低报长度、EOF 验证、取消与 callback race、session messages 独立 64 MiB 边界，以及 `/file/content` reader 层 decoded 纵深防御。
- `OpenCodeFailureTest`、`ErrorUiTextTest`、`OpenCodeRepositoryFailureHandlingTest` 覆盖不读取 `Throwable.message` 的语义分类、带操作 fallback 的本地化资源映射、Repository 传播、response-too-large 行为，以及取消与 JVM `Error` 传播。
- `BoundedSseReaderTest`、`OkHttpSseEventSourceFactoryTest`、`OpenCodeEventClientLifecycleTest`、`ProjectSnapshotCoordinatorTest` 覆盖显式 identity encoding、非 identity 零读取拒绝、所有换行与 EOF 状态、32 MiB 行/event 边界、无 body status/MIME 失败、取消、重试分类、关闭终态、owner/generation/source/transport 竞态、project/global 权威切换、有界重放和快照失败/恢复。
- `FrpcStcpReadinessRetryClassifierTest` 与 `GoMobileFrpcStcpVisitorClientTest` 覆盖 readiness 瞬时/永久失败、入站策略失败、typed unavailable/API mismatch bridge 错误、安全 bridge 摘要、API v2 JSON、revision/control epoch、`WaitVisitorReady`，以及反射取消/JVM `Error` 传播。
- `SensitiveValueToStringTest` 验证 network、domain、Store、feature 和 UI value 摘要不暴露人工凭据、URL/endpoint、alias、路径、prompt、Base64、SSE payload 或 tool output，同时保持普通 value object 行为。
- `OpenCodeContrastTest` 对浅色/深色主题执行 4.5:1 文字与 3:1 图形对比度门禁，覆盖主题文字、语义化 Diff/Markdown/语法/图表颜色、状态指示、附件遮罩、选择边框和 `ControlBorder`。
- `ProjectDrawerModelTest`、`ProjectInitialTest`、`ProjectPickerViewModelTest`、`RecentProjectRecorderTest` 与 `RecentProjectStoreReducerTest` 覆盖当前项目补入、MRU 顺序保持、Windows 路径别名去重、不等待 DataStore 的导航、有序重试、本地失败分类、项目首页导航决策和 Unicode 项目首字。
- `SessionListWindowCoordinatorTest`、`InMemoryOpenCodeStoreSessionWindowTest` 与 `OpenCodeRepositorySessionWindowTest` 覆盖共享 20 条目标、50 条请求余量、本地展开与网络加载、重试/末尾状态、有序前缀替换、快速点击合并、旧 generation/transport 拒绝、tombstone、项目/workspace 隔离和有界 metadata/父链补取。
- `NotificationAlertPolicyTest`、`NotificationChannelMigrationPolicyTest`、`OpenCodeNotificationAudioAttributesTest` 与 `SessionVisibilityRegistryTest` 覆盖单事件唯一声音所有者、App/系统设置独立、系统显式声音/静音优先级、legacy 到 v2 迁移决策、notification audio usage 和前台 destination 可见性。
- `SessionComposerAgentResolverTest`、`SessionModelPreferenceResolverTest` 与 `SessionComposerRouteSelectionTest` 覆盖按服务端顺序过滤 Build/Plan 及回退、初始模型/Variant 校验与切换回退，以及只对新会话接受项目首页轻量 Composer 路由选择。
- `ComposerParameterPickerScrollTest` 覆盖跨 provider 标题的模型 lazy-list 索引、带“默认”前缀的 Variant 索引、选择不存在时的行为，以及基于实测 item 与 viewport 的居中偏移。
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

开发期间可按测试类运行聚焦测试，例如：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.ycfeng.ocdeck.core.security.RedactorTest"
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

返回仓库根目录后，审计社区/文档与第三方/法律 metadata、构建 AAR 并运行 Android 门禁：

```bash
python3 .github/scripts/audit-community.py
python3 .github/scripts/audit-third-party.py
bash frpc-stcp-visitor-go/build-aar.sh
./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug -PrequireGoMobileBridge=true
```

在 Windows PowerShell 中，从仓库根目录运行等价序列：

```powershell
Push-Location .\frpc-stcp-visitor-go
go run ./cmd/preparefrp
go test -race -modfile=build/frp-patched.mod ./...
Push-Location .\build\frp-v0.69.1-p1
go test -race ./client/...
Pop-Location
Pop-Location

python .github/scripts/audit-community.py
python .github/scripts/audit-third-party.py
.\frpc-stcp-visitor-go\build-aar.ps1
.\gradlew.bat :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug -PrequireGoMobileBridge=true
```

固定 Go、x/mobile、Android API 与 NDK 版本必须来自 `bridge-versions.properties`。

Release workflow 会连续构建两次 bridge，并拒绝不可复现输出。修改 Go wrapper、downstream frp patch、Android bridge 模块、bridge API、失败处理或版本 metadata 时，必须执行完整 bridge 门禁，不能只运行 Android 单元测试。仅修改 Kotlin bridge API 或失败处理时，生成 AAR 字节与 `BRIDGE_VERSION` 可以保持不变，但不能跳过上述任何门禁；native 或生成 AAR 字节发生变化时必须递增 `BRIDGE_VERSION`。

## 安全与边界测试

- 使用人工合成凭据，并断言异常、alias、`toString()`、日志和 UI 安全文案都不会泄露它们。
- 断言 Repository、SSE 和快照失败保留语义类型，UI 文案来自本地化资源而非异常字符串，并且取消与 JVM `Error` 原样传播。
- 对 Retrofit 方法验证显式入站模式、独立 encoded/decoded 精确上限、`max + 1`、未知与低报长度、真实 gzip/Bridge 顺序、body 关闭行为、非 2xx 零读取、成功 Unit body 丢弃和无 `Invocation` 绕过。
- 覆盖直连明文凭据矩阵：规范化后的远端/LAN `http://` 地址同时具备非空白 OpenCode 用户名和新密码或保留密码时需要提示确认；HTTPS、纯语法 loopback、不完整 Basic 凭据、SSH 和 STCP 不触发。测试确认、取消、重复确认、冻结请求语义和无 DNS 分类。
- 单独覆盖 Provider secret 提交：通过 Direct 非本机回环 HTTP 发送新 API key 或自定义 Header value 需要一次冻结确认；HTTPS、纯语法 loopback、SSH 和 STCP 不触发。断言 API key/Header value 不进入 Android 持久化、公开 UI state、原始响应日志或 `toString()`。
- Provider auth method 解析测试应在受支持项前放入未知项，确保原始 wire index 不被重编号。OAuth 测试保持 directory/workspace 与 method index，拒绝不安全浏览器 URL，覆盖 code 与可取消 auto callback，且不自动重试有状态 authorize/callback。
- 测试 Custom Provider 的 disabled 暂存、可选凭据写入、最终启用、先停用后清理 auth、partial/unknown outcome 与 deep-merge 删除限制；config 投影只保留安全身份和 Header 名。
- 有界 reader 应测试精确上限和 `max + 1`；不要提交巨大的 fixture 文件。
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
| 包含 20 个最近项目的项目抽屉 | Rail 滚动、项目列表 75% 高度上限、当前项目选择、“打开项目”、“设置”、项目首页切换 | 项目按钮可滚动且不裁切；固定操作始终可达；仅一个项目被选中；切换会关闭抽屉且不堆叠重复项目首页 |
| 超过 20 条根会话的项目 | 项目主页和抽屉中的加载更多、共享状态、网络加载、重试、末尾状态、直接打开旧会话 route | 两个界面展示相同目标；失败时保留已有行；重试可达；末尾状态稳定；窗口外会话及有界父链可补取且窗口不缩小 |
| API 26/29/30+/33+ fresh install 与旧通知升级 | 默认提醒、App 内“无”、系统显式声音、channel 显式静音/阻止/低 importance、权限拒绝、当前会话前后台 | 每个事件最多一个声音所有者；Android 系统选择优先；App 播放不绕过低打扰设置；后台会话不会误标已查看 |
| IME 打开 | 固定底部 Composer、建议面板、model/agent/variant popup、附件条、系统返回 | Inset 保持 Composer 可见；浮层不被遮挡；系统返回先关闭聚焦浮层，再离开页面 |
| TalkBack 启用 | 项目 Tab 与路径、可点击图标标签、Tab/RadioButton/Checkbox/Switch 状态、展开/折叠控件、未读/错误/权限/连接提示、服务器排序 | 项目 Tab 播报唯一完整标签和选中状态；其他 role 与状态正确播报；重要含义不只依赖颜色；服务器卡片提供本地化上移/下移自定义操作 |
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
