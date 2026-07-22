# 仓库代理规则

[English](AGENTS.md)

本文档是英文 canonical 文件的完整简体中文便利翻译。中英文发生差异时，以 `AGENTS.md` 为准。

修改 `AGENTS.md` 或 `AGENTS.zh-CN.md` 时，包括用户明确要求只修改其中一个文件的情况，必须在同一次变更中同步更新两个文件，并保持语义一致。

## 适用范围与优先级

- 本仓库用于开发 OC Deck，即由社区独立维护的 OpenCode Server 原生 Android 客户端。OC Deck 不是由 OpenCode 项目或 Anomaly 开发、背书、赞助或关联的产品。
- 复刻 OpenCode 核心工作流时，不要机械照搬桌面 Web 的弹窗、布局或交互。优先保证移动端可用性、稳定连接、实时状态一致性和敏感信息安全。
- 需要参考 OpenCode 源码时，必须先从 `local.properties` 读取 `opencode.source.dir`，并在配置路径存在时使用该本地源码目录。只有该属性未配置或配置路径不存在时，才考虑联网搜索。
- 使用 Playwright 检查或测试 OpenCode Web 前，必须先从 `local.properties` 读取 `opencode.web.url` 和 `opencode.test.project.dir`。使用配置的本机 Web 根地址，并将可能修改项目文件、会话或项目状态的交互严格限制在配置的专用测试项目中。如果任一属性未配置或测试项目路径不存在，则不得执行会改变状态的 Playwright 交互。
- 修改实现前，先阅读[移动端交互设计](doc/architecture/mobile-interaction.zh-CN.md)和[项目框架](doc/architecture/project-framework.zh-CN.md)；对应英文文件是 canonical 来源。通过[中文文档索引](doc/README.zh-CN.md)查找更具体的规则。
- 实现、测试与文档不一致时，保持改动简单正确，验证预期行为，并在同一次变更中更新所有受影响的中英文文档配对。不得明知存在矛盾却不处理。

## 语言与沟通

- 使用用户当前请求的语言回复。请求混合或不明确时，沿用会话中已经建立的语言；用户明确指定语言时，以该要求为准。
- 创建子 agent 时，标题和提示词都必须使用用户当前请求的语言。
- 生成上下文压缩摘要、continuation prompt 或 handoff note 时，必须记录并保留当前会话语言，使恢复后的工作继续使用同一语言。
- 代码标识符、命令、文件路径、协议名称和 Git commit message 保持英文，除非它们本身是需要本地化的用户可见值。
- 实现结果应说明涉及的模块、为何选择这些边界、是否存在跨服务或跨配置影响，以及最相关的已执行验证。

## 唯一来源

- 应用版本：`gradle.properties` 中的 `VERSION_NAME` 与 `VERSION_CODE`。
- Android 与 Kotlin 依赖版本：`gradle/libs.versions.toml` 和当前生效的 Gradle 构建文件。
- bridge、Go、x/mobile、Android API、NDK 与 frp 版本：`frpc-stcp-visitor-go/bridge-versions.properties`。
- 架构、交互、API 完成度和当前能力状态：`doc/architecture/project-framework.md` 与 `doc/architecture/mobile-interaction.md`。
- 测试范围与命令：`doc/development/testing.md` 与 `.github/workflows/ci.yml`。
- 发布行为：`.github/workflows/release.yml` 与 `doc/release/github-actions.md`。
- 不要在本文件复制容易变化的版本表、端点矩阵或功能状态清单。应从 canonical 来源读取，并在行为变化时更新对应来源。
- 优先使用稳定依赖。除非用户明确要求预览软件，否则不要切换到 alpha/canary Android 依赖。禁止使用 `gomobile@latest` 等浮动 GoMobile 或 frp 版本。

## 工作与 Git 规则

- 仅当用户明确要求执行 `git commit` 时创建提交。
- Commit message 必须使用英文，并以 `feat:`、`fix:` 或 `refactor:` 开头。新增或改进能力使用 `feat:`，缺陷修复使用 `fix:`，只有不改变外部行为时才使用 `refactor:`。
- 只暂存和提交当前任务相关改动。除非用户明确要求，不要纳入用户已有的无关改动。
- 不得提交匹配 `.gitignore` 的文件，也不得使用 `git add -f` 或 `git add --force`，除非用户明确点名该被忽略文件。用户仅要求提交“所有改动”不构成充分授权。
- 不得还原、覆盖或清理无关的工作区改动。并发改动与当前任务直接冲突时，停止并询问如何处理。
- 不得提交、记录、粘贴或写入文档的真实敏感信息包括凭据、签名材料、私有项目内容、prompt 和敏感原始响应。

## 架构边界

- 业务实现保持在单一 `:app` 模块中，通过包结构划分职责。不要过早拆分 feature/data/domain 等业务 Gradle 模块。
- `:frpc-stcp-visitor` 是稳定 Kotlin client contract、共享 DTO、GoMobile adapter 与纯 Kotlin 实现的已批准 backend-neutral Android library 边界。保留 GoMobile backend 期间，`frpc-stcp-visitor-go` 继续作为其固定版本 Go 构建工程。两处都不得承载 App feature/data/domain 业务代码，也不能作为业务多模块迁移的先例。
- 通过 `AppContainer` 手动装配依赖，对 ViewModel 使用构造函数注入和集中 Factory。不要在 feature 代码中临时创建 `OkHttpClient`、Retrofit、Repository 或应用级全局单例。
- 使用 DataStore 保存轻量设置，Android Keystore 保存敏感凭据，内存 Store 保存运行态状态。
- 未经用户基于明确需求批准，不得引入 Room、Hilt、KSP、kapt 或拆分业务模块。明确需求包括离线缓存/搜索、冷启动快速恢复或依赖图已经难以维护。
- 包职责保持为：`app/` 负责应用装配；`core/network/` 负责 HTTP/SSE/认证/错误处理；`core/security/` 负责 Keystore 和脱敏；`core/store/` 负责运行态状态与 reducer；`core/navigation/` 负责集中路由；`core/util/` 负责共享规范化和工具；`data/` 负责 DTO 与 Repository 实现；`domain/` 负责模型、接口和 UseCase；`feature/` 负责 Screen 与 ViewModel；`ui/` 负责通用组件和主题。
- 新增路由优先使用 Navigation Compose typed routes，并逐步迁移现有字符串路由，不要继续扩张过渡期字符串方案。

## 移动端 UI 与本地化

- 使用 Single Activity、Jetpack Compose、Material 3 与 Navigation Compose。遵守项目既有模式，不要引入并行 UI 架构。
- 不要把桌面双栏 dialog 或无屏幕约束的 popover 搬到手机。设置和表单优先使用单列全屏页面；短内容使用受屏幕约束的 anchored popup；Composer 建议使用内联面板；较长、需要搜索或操作复杂的内容使用 `ModalBottomSheet`。
- 项目与会话壳层使用 `ModalNavigationDrawer`。Popup、sheet、Dock 和内联面板必须可聚焦、可由系统返回关闭、限制尺寸，并避开 IME 和底部 Composer。
- 底部 Composer 固定显示并正确处理 IME inset。所有可点击图标和 chip 操作至少提供 48 dp 真实触控目标，即使视觉图标更小。
- Tab、单选、多选和 Switch 必须通过匹配的 role 暴露 selected/checked 语义；可展开控件需要本地化的展开/折叠状态说明。
- 未读、错误、权限、连接等重要状态不得只依赖颜色。可排序服务器卡片除拖拽手势外，还必须提供 TalkBack 上移/下移自定义操作。
- 浅色与深色主题下，正文和辅助小文本的对比度目标至少为 4.5:1，必要的非文本状态指示和控件边界至少为 3:1。真实文本框边界使用 `ControlBorder`；语义化 Diff、Markdown、语法和图表颜色继续由 JVM 对比度测试覆盖。
- 文本布局优先自然测量和 `heightIn`，避免固定文本高度。Dialog、服务器状态、设置 sheet、建议面板和 Composer 附件必须受屏幕约束且可滚动，确保小屏、200% 字体和 IME 显示时关键操作仍可到达。
- 涉及颜色、背景、边框、阴影或状态样式的视觉改动必须同时适配浅色与深色主题。不得硬编码只适用于单一主题的颜色。
- 新增或修改用户可见文案时，不得在 Kotlin 中写死单一语言。简体中文放在 `app/src/main/res/values/strings.xml`，英文放在 `app/src/main/res/values-en/strings.xml`，每个 key 必须同时添加到两处。
- ViewModel 或 Store 中进入 UI 的 fallback 文案使用 `UiText.Resource`。非 composable helper 应返回语义数据或 `UiText`，不要直接调用 `stringResource`。服务端数据、用户输入、路径、文件名、会话标题、Provider/模型名称和命令输出保持原样。
- 修改 UI 文案后，运行 `rg '[\p{Han}]' app/src/main/java/io/github/ycfeng/ocdeck --glob '*.kt'`，并人工检查中英文 UI 硬编码；注释、技术标识和服务端/用户数据例外。

## 路径、API 与项目状态

- 所有 OpenCode `directory` 在进入 Repository 或网络边界前必须规范化：将 `\` 替换为 `/`，删除根路径以外的末尾 `/`，Windows 盘符路径比较时忽略大小写，最近项目按规范化 worktree path 去重，内部 key 使用规范化路径。
- 项目内相对文件路径单独使用 `ProjectFilePathNormalizer`。拒绝 NUL、绝对路径和任意 `..`；保持文档定义的 Windows/POSIX 分隔符语义，并验证服务端目录节点是请求目录的直接子项。
- 不得为了速度或方便绕过路径规范化。
- 普通 REST 使用 Retrofit + OkHttp + kotlinx.serialization，并保持 JSON 对未知字段容错。
- 每个 `OpenCodeApi` Retrofit 方法都必须声明显式入站响应策略。普通成功 JSON/object/list/Boolean/`JsonElement` 响应和 `/file/content` 同时具有 `Content-Encoding` 解码前 16 MiB encoded response-body 上限与解码后 16 MiB entity 上限；成功的 `Response<Unit>` body 直接丢弃。
- Retrofit 入站拦截器在策略缺失时 fail-closed，并为有界请求附加 encoded-body network interceptor 所需的 tag。两层限制都只把 `Content-Length` 用于提前拒绝，未知或低报长度在 `max + 1` 强制失败；所有非 2xx body 在 Retrofit 缓存前关闭并替换为空 body，同时保留状态码。没有 Retrofit `Invocation` 的请求不经过 Retrofit 拦截器，包括 session messages 的 direct transport，因此必须自行附加 encoded-body policy。
- 项目进入流程保持为：规范化 `directory`，并行获取项目 REST 快照，写入内存 Store，打开项目 SSE，将增量事件归约到 Store，并在 App 恢复前台后执行健康检查与快照校准。
- 完整端点矩阵和完成度位于 `doc/architecture/mobile-interaction.md`。调整端点时，应同步更新 DTO 容错、directory/workspace 参数测试和配对的交互文档。

## SSE 与连接一致性

- 同时支持全局与项目 SSE。生产 SSE 必须使用 OkHttp `Call` 和基于 `ResponseBody.source()` 的自定义流式 reader，不得使用先把 event `data` 完整分配为 `String` 的无界 parser。
- SSE 必须请求 `Accept-Encoding: identity`，并在打开或读取 body 前拒绝任意非 identity content encoding。单个 SSE 物理/逻辑行和累计 event data 的 UTF-8 identity response-body 字节上限均为 32 MiB。整行转 `String` 前检查行限制，完整拼接 event 前检查累计限制。超限时关闭 body、取消 Call，并报告不含 payload 的类型化失败。
- 只接受 HTTP 200 且 media type 为 `text/event-stream`，允许 charset 参数。非 200 或 Content-Type 无效时不得读取响应体。204 和不可重试 4xx 进入 `Failed`；408/409/425/429 与 5xx 保持可重试。
- 只有完整空行才能 dispatch event。EOF 时丢弃没有被该分隔符终止的 pending line/event。单个事件 JSON 或字段类型无效时只丢弃该事件，不关闭连接；JVM `Error` 仍必须传播。
- 显式取消导致的 I/O 失败不得转为重连回调。连接状态明确暴露 `Connecting`、`Open`、`Retrying`、`Failed` 与 `Closed`，并使用有界指数退避。
- 断线时不得清空已有 UI 数据。重连后使用 REST 快照校准。快照失败不得阻断 EventSource 重连；一次恢复最多启动一轮可合并校准，并只在 dirty 时追加一轮有界 follow-up。
- 打开、关闭、重试、回调和校准必须校验 owner lease、generation、source 与 transport identity。关闭后的迟到任务不得发布 `Open` 或重建已关闭连接。
- 当项目流在同一 transport identity 上处于 `Open` 时，它是该项目的权威来源；仅在项目流不可用时把 global 项目事件作为 fallback。校准事件缓冲必须同时限制数量与估算字节，禁止无界缓冲。
- SSE 与快照错误写入 Store 或 UI 前必须脱敏。前台健康/readiness 检查和必要快照刷新由应用级连接协调器统一负责。
- STCP 模式下，启动 REST 或 SSE 前必须先等待 visitor readiness 和 application health。control epoch 变化后重新校准，拒绝旧 generation 或 epoch 的结果。
- 启用 workspace 后，Store、SSE、ViewModel、operation gate 和校准 key 必须同时包含规范化 directory 与 workspace。

## Composer 与会话操作

- 新会话首次发送前没有后端 session。首次发送先调用 `POST /session`，普通 prompt 再使用 `POST /session/{sessionID}/prompt_async`。
- 只有 Slash command 匹配已加载 `/command` 数据时，才分流到 `POST /session/{sessionID}/command`。未知 `/xxx` 仍按普通 prompt 发送。Shell mode 实现后使用 `POST /session/{sessionID}/shell`；Stop 使用 `POST /session/{sessionID}/abort`。
- 文本、附件和上下文全部为空时禁用发送。如果当前会话正在工作且没有新输入，提交操作应变为 Stop。发送前校验 model 和 agent。
- 以当前项目 Store 的 `PromptCapabilities.revision` 和冻结能力快照为权威。UI revision 过期时拒绝发送，并保证 revision 检查与乐观用户消息插入原子完成。
- 请求失败时，只删除仍为 optimistic 的同一 message。若 SSE 已确认该消息，则保留消息并报告结果不确定，避免重复重试造成重复发送。
- `send`、`abort`、`revert` 与 `unrevert` 必须共享按 server/directory/workspace/session 建立的 fail-fast single-flight gate，不得只依赖按钮 disabled。新会话获得真实 ID 后，先把该 key 加入现有 lease，再发布 Store 或导航状态。
- Agent picker 只显示 Composer 支持的 `build` 和 `plan`。Model picker 按 Provider 分组并支持搜索和当前选择；“连接 Provider”和“管理模型”必须导航到真实设置路由。Variant picker 仅在模型提供 variants 时显示，增加本地化的“默认”选项，重置不再支持的选择，并使用“推理强度/Reasoning”文案。`/` 建议和 `@` agent mention 使用独立数据源；mention 不得复用文件搜索。

## 安全与凭据事务

- 敏感信息统一替换为严格一致的 `<redacted>`。敏感数据包括 API key、token、password、Authorization/cookie、Provider auth 与自定义 header、SSH password/private key/passphrase/host fingerprint、frp/STCP secret、环境变量、签名材料和含凭据的配置值。
- 使用统一 `Redactor`。已知 JSON、header、query、URL 与配置对象优先做结构化脱敏；正则只用于未知自由文本兜底。裸认证 scheme 值和 HTTP(S) URL userinfo/fragment 也必须脱敏。
- 网络日志仅允许在 debug 构建中开启，并必须经过脱敏拦截器。错误 UI、崩溃报告、测试、snapshot、调试面板和文档不得暴露原始 header、凭据、完整敏感 body 或真实 secret。Provider 设置只能显示连接状态或 masked 状态，不能明文回显 key。
- Repository、network 和 Store 层保存类型化语义失败原因，不得通过异常 `toString()` 或 `Throwable.message` 生成用户文案。UI 将这些原因映射到本地化资源，并为未知错误使用当前操作的 fallback；取消与 JVM `Error` 必须传播。
- Server base URL 只接受带有效 host、无 userinfo/query/fragment 的 HTTP(S) URL。保存、连接和展示使用同一校验；异常旧值不得原样回显。
- 保存同时使用非本机回环明文 HTTP 与有效 OpenCode Basic 认证的直连服务器前，必须显示明确但不阻断能力的确认弹窗。有效认证要求用户名非空白，并存在新密码或保留密码。Loopback 只做无 DNS 的纯语法分类；SSH/STCP 不使用此警告。选择“仍然保存”后必须正常保存并允许后续使用，取消则不得调用 Repository 写入。
- 固定 SSH host fingerprint 必须通过 `HostKeyRepository` 或等价机制在用户认证前校验。禁止关闭严格校验后连接，再在认证完成后补验。
- 新增或更新服务器凭据、保存 TOFU fingerprint 时，必须先把每个新 secret 写入用途明确且带随机 UUID 的候选 alias。候选构建完成后检查取消。配置写入、结果确认、connection config epoch 更新和 SSH/STCP 运行态失效必须放在 `NonCancellable` 提交段。
- 配置写入抛异常时重新读取目标：已经持久化则按成功继续；只有明确未提交时才清理候选并重新抛出；结果无法确认时保留候选，并抛出不含 secret 的类型化 unknown-outcome 错误。
- 提交成功后，在配置锁外重新读取全部服务器引用并 best-effort 删除旧 alias。清理失败不得回滚配置。`SecureCredentialStore` 写入/删除必须在 IO dispatcher 使用可报告失败的 SharedPreferences `commit()`，只捕获预期 `Exception`，并传播取消与 JVM `Error`。删除或轮换 alias 前，先确认其他服务器没有继续引用它。
- SSH host 或 port 变化时不得继承旧 endpoint 的 fingerprint alias。`AcceptNew` 清空 pin 并重新 TOFU；`Fingerprint` 必须提供新 pin。
- 敏感或可能很大的 value object 必须用结构化摘要覆盖 `toString()`，不得展开凭据、URL/endpoint、alias、路径、prompt、Base64、SSE payload 或 tool output；敏感字段严格使用 `<redacted>`。这些摘要不得改变 serialization、`copy`、equals 或 hashCode 语义。

## 文件、附件与有界输入

- 服务端项目文件使用 OpenCode `/file`、`/file/content` 与 `/find/file` 浏览，不得用 Android SAF 代替服务端文件系统。Android 本机附件可以使用系统选择器；UI 和数据模型必须明确区分本机文件与服务端项目文件。
- URI、`ResponseBody`、Base64、native bridge、图片或私钥数据必须在完整分配前执行流式硬上限检查。不得使用无界 `readBytes()`、`readText()`、完整 body 缓冲或先解码后检查的方式。
- `GET /session/{sessionID}/message` 必须绕过 Retrofit converter 和错误体缓存，使用 `ServerConnection` 提供且与 REST 共享认证、超时、重定向和脱敏配置的 OkHttp `Call.Factory`。非 2xx 不读取 body，直接关闭并抛出不含 body 的类型化 HTTP 错误。
- Session messages 的 2xx body 在 OkHttp callback 线程使用计数/限流 `InputStream` 与共享宽容 `Json.decodeFromStream` 解码。执行 Call 前附加 64 MiB encoded response-body policy，再在解码和 EOF 验证期间执行独立的 64 MiB decoded-entity 上限。`Content-Length` 只用于提前拒绝，未知或低报长度在 `max + 1` 失败。协程取消必须取消 Call 并关闭正在读取的 body，且不得用 I/O 错误替换 `CancellationException`。
- Retrofit/file 的 16 MiB 与 session messages 的 64 MiB 分别限制 HTTP transfer framing 之后、`Content-Encoding` 解码之前的 encoded response-body octets 和解码后的 entity bytes；SSE 的 32 MiB 限制 identity response-body representation。这些都不是 heap 使用保证：Retrofit converter 在限额内仍可能完整构建 `String`、DTO 图或 JSON tree，并发项目快照仍可能产生较高内存峰值；`/file/content` 继续保留 reader 层 decoded 防御。
- 附件必须同时限制单文件大小、文件数量和总 raw bytes。Sender 最终边界必须重新校验必填 metadata、data URL header、Base64 字符/空白/padding、encoded length 与声明 raw size。
- SSH 私钥文件最多 256 KiB，必须在 IO dispatcher 使用有界 buffer 读取，并在保存和 JSch 边界按 UTF-8 字节数复验。

## 测试与验证

- 为改动行为新增或更新聚焦测试。详细测试清单应维护在 `doc/development/testing.md`，不要复制到本文件。
- 核心链路应按改动范围覆盖路径规范化、脱敏、DTO/错误处理、prompt 状态/乐观回滚/single-flight、Store reducer、SSE 协议/生命周期/边界、凭据事务、SSH host key 时机、STCP readiness/epoch 和外部输入有界处理。
- 普通 Android 改动在仓库根目录运行：

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

Windows 使用 `gradlew.bat`。

- 修改 `frpc-stcp-visitor-go/`、`:frpc-stcp-visitor`、frp patch、bridge API/依赖或 bridge 版本时，还必须执行 CI 等价门禁：`go run ./cmd/preparefrp`；wrapper 与 patched frp client 的 `go test -race`；`build-aar.sh` 或 `build-aar.ps1`；`:frpc-stcp-visitor:frpcInteropTest`；`:frpc-stcp-visitor:checkGoMobileBridgeAar -PrequireGoMobileBridge=true`；`:frpc-stcp-visitor:testDebugUnitTest`；`:app:testDebugUnitTest`；`:app:testCanaryUnitTest`；`:app:testKotlinReleaseUnitTest`；`:app:assembleDebug`；`:app:assembleCanary`；`:app:assembleKotlinRelease`；以及 `:app:verifyPureKotlinPackaging`。打包门禁要求 Canary 与 Kotlin Release-Like 的 runtime classpath 及全部 ABI APK 都排除 GoMobile bridge 和 `libgojni.so`。互操作任务只下载仓库固定并经哈希校验的官方 frp 二进制，且必须在任何发布签名 environment 之外运行。仅修改 Kotlin bridge API 或失败处理时，生成 AAR 字节和 `BRIDGE_VERSION` 可以保持不变，但仍不能省略完整门禁；native/AAR 字节变化时必须递增 `BRIDGE_VERSION`。
- Bridge 门禁必须继续验证 checksum、Java API signature、bridge/frp provenance、预期 ABI、ELF machine、16 KiB `PT_LOAD` 对齐、stripped 状态和同输入可复现性。
- 仅修改文档或社区治理文件时应运行 `python .github/scripts/audit-community.py`；只有改动影响代码、构建、发布或第三方校验时才增加更广泛测试。

## 文档、发布与第三方规则

- 公开文档使用英文 canonical 文件和完整 `.zh-CN.md` 便利翻译。事实、命令、状态、安全约束或链接变化时，配对文件必须互链并在同一次变更中同步更新。
- 修改 `AGENTS.md` 或 `AGENTS.zh-CN.md` 中的任意一个文件时，即使用户只要求修改单一语言版本，也必须在同一次变更中同步修改两者，并保持等价要求和章节顺序。
- 架构、版本、接口或页面流程发生实质变化时，同步更新两份 project-framework 文档。交互、移动端适配、端点行为或新发现的 OpenCode Web 行为变化时，同步更新两份 mobile-interaction 文档。
- 社区入口、文档路径、Release Notes、兼容性或弃用策略变化时，更新所有受影响的配对文件，并运行 `.github/scripts/audit-community.py`。
- 新增安全规则或敏感字段时，同步更新两份 AGENTS、相关配对文档和实现测试。
- `gradle.properties` 是应用发布版本的唯一来源。稳定 tag 使用 `vMAJOR.MINOR.PATCH`，必须与 `VERSION_NAME` 一致，并递增 `VERSION_CODE`。默认分支为 `main`。
- GitHub Release 只保留三个受支持 ABI 的 APK 和 `SHA256SUMS`；除非发布策略明确变化，否则不要增加 AAB 或 universal APK。GitHub 与未来 Google Play 包必须使用同一个 app-signing certificate。不得提交、缓存或上传 JKS/password/Base64 keystore 材料；发布流程必须校验固定的证书 SHA-256 指纹。
- GoMobile bridge 字节变化时必须递增 `BRIDGE_VERSION`；不得在同一 Maven 坐标下发布不同字节。发布前除静态门禁外，还必须在设备上验证 native load、各目标 ABI、16 KiB page size 和真实 STCP 端到端连接。
- 新增或升级依赖、音效、Gradle wrapper、frp upstream/patch 或分发资产时，必须同步更新 `THIRD_PARTY_NOTICES.txt`、`third_party/components.toml`、相关 `third_party/sources/*` 和许可证文本，并从本地实际字节重新计算哈希。
- 不得把已移除的第三方 Provider 标志重新作为分发资产引入。新增品牌图形前必须审查来源、许可、再分发与商标影响；默认优先使用文本或通用图标。
