# OC Deck Android 项目框架搭建方案

[English](project-framework.md)

本文档与英文版本并行维护；如文档之间存在冲突，以当前实现、自动化测试和[仓库代理规则](../../AGENTS.zh-CN.md)为准。

## 1. 目标与原则

本方案基于 `doc/architecture/mobile-interaction.zh-CN.md` 的交互设计结论，为 OC Deck 规划工程框架。OC Deck 是独立社区维护的 OpenCode Server 原生 Android 客户端，不是 OpenCode 项目或 Anomaly 官方产品。目标不是机械复刻桌面 Web，而是在保留 OpenCode 信息架构和核心工作流的基础上，构建适合手机端的稳定、可迭代、可验证 Android 工程。

当前工程采用“单业务模块、独立 native bridge、手动 DI、Room/Hilt 延后”的保守方案。服务器连接、项目选择、会话列表、会话详情、普通 prompt、SSE 和安全脱敏主路径已经落地；业务代码继续集中在 `:app`，仅将 frpc STCP GoMobile 集成隔离到已批准的 `:frpc-stcp-visitor` bridge 边界，并按真实需求评估数据库、Hilt 和业务多模块。

核心原则：

- 移动端优先：桌面端 popover/dialog 在 Android 上按内容改为全屏页面、受屏幕约束的 anchored popup、底部抽屉或底部弹层。
- 小步搭建：业务实现保持在可构建、可运行、可调试的 `:app` 模块；除已批准的 native bridge 外，不提前拆业务 Gradle 多模块。
- 服务端驱动：OpenCode 运行在服务端，Android 端主要负责 REST API、SSE 状态同步和移动端交互表达。
- 安全优先：API key、token、password、headers、env、配置值等敏感内容不得出现在日志、崩溃报告、调试 UI 或文档示例中。
- 路径一致性：所有 `directory` 参数进入网络层前必须规范化，避免 `E:\\path` 与 `E:/path` 被当成两个项目。

## 2. 技术栈选型

版本选型以 Android 官方文档、Maven metadata 和 Gradle 官方版本接口为依据。

| 类别 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Kotlin `2.4.0` | 当前稳定语言版本线，避免使用 `2.4.20-Beta1` |
| 构建工具 | Gradle `9.6.1` | 当前 Gradle final 版本，满足 AGP 9.2 最低要求 |
| Android Gradle Plugin | `9.2.1` | 使用稳定版，规避 `9.2.0` 已修复的 R8 `RecordTag` 问题，避免 `9.4.0-alpha02` |
| JDK | JDK `21` | 项目统一使用 JDK 21 运行 Gradle 与 Java/Kotlin toolchain |
| SDK | minSdk `26`, compileSdk `36`, targetSdk `36` | Android 16/API 36 已稳定，不因 AGP 支持 API 37 就使用预览平台 |
| Build Tools | `36.0.0` | AGP 9.2 默认 SDK Build Tools |
| UI | Jetpack Compose + Material 3 | 单 Activity、声明式 UI、移动端适配效率高 |
| Compose BOM | `2026.06.01` | 使用 BOM 管理 Compose 依赖版本 |
| Compose Compiler | Kotlin Compose plugin `2.4.0` | 与 Kotlin 版本保持一致 |
| Navigation | `androidx.navigation:navigation-compose:2.9.8` | 使用稳定版本；当前集中封装字符串路由，新增路由优先 typed routes，并逐步迁移现有路由 |
| Activity Compose | `1.13.0` | 稳定版本 |
| Lifecycle | `2.10.0` | ViewModel、runtime compose、生命周期感知状态收集；`2.11.0` 的 AAR metadata 要求 compileSdk 37，首版继续坚持 SDK 36 |
| 网络 | Retrofit `3.0.0` + OkHttp `5.4.0` | 常规 REST 使用 Retrofit；session messages 使用同一个 OkHttpClient 的窄 transport 绕过 Retrofit 错误体缓存，共享认证、重定向、日志脱敏和超时策略 |
| SSE | OkHttp `Call` + 自定义有界 reader | 对接 `/global/event` 和 `/event?directory=...`；保留 `okhttp-sse:5.4.0` 依赖但生产路径不使用其 parser |
| JSON | kotlinx.serialization `1.11.0` | DTO 序列化，默认忽略未知字段 |
| 并发 | kotlinx.coroutines `1.11.0` | Repository、SSE、ViewModel 状态流 |
| STCP 原生桥接 | Go `1.26.4` + GoMobile/x-mobile `4dd8f1dbf5d2` + NDK `27.1.12297006` + frp `v0.69.1-p1` | 版本统一由 `bridge-versions.properties` 固定；`p1` 是仓库内可审计的最小 downstream patch stack |
| SSH | JSch `2.28.3` + BouncyCastle `1.84` | SSH 本地端口转发、私钥与主机密钥支持；固定 host fingerprint 必须在用户认证前校验 |
| Markdown | Markwon `4.6.2` + Prism4j `2.0.0` | 渲染会话区域 agent/助手回复；普通 Markdown 使用 Markwon，行内代码使用透明背景高亮文字，fenced code block 使用 Compose 原生代码块、1dp 边框和 Prism4j 高亮 |
| 本地设置 | DataStore Preferences `1.2.1` | 保存服务器列表、最近项目、偏好设置 |
| 设备凭据 | Android Keystore | 保存 OpenCode Basic 密码、SSH 凭据与 pin、frp/STCP secret；Provider auth 由 OpenCode Server 持久化，Android 不保存 |
| 图片 | Compose/Android 平台解码 | 当前未引入 Coil；只有项目 icon、远程图片或附件缩略图明确需要时再评估 Coil 3 `3.5.0` |
| 数据库 | Room 延后 | 当前不引入，待离线缓存/本地搜索需求明确并经确认后再加 |
| DI | 手动 DI | 当前不引入 Hilt/KSP，降低工具链复杂度 |

## 3. Gradle 与 JDK 配置建议

Android 官方 JDK 文档建议为构建显式指定 Java toolchain，并保持 Android Studio Gradle JDK 与命令行 `JAVA_HOME` 一致。Android Studio Panda 1+ 新项目默认推荐 `GRADLE_LOCAL_JAVA_HOME`，可映射到 `.gradle/config.properties` 中的 `java.home`。

建议约束：

- Gradle 运行 JDK 使用 21。
- Java toolchain 使用 21。
- `sourceCompatibility` 和 `targetCompatibility` 使用 `JavaVersion.VERSION_21`。
- Kotlin 使用新版 `compilerOptions` 配置 JVM target 21。
- 不设置 `STUDIO_JDK`，除非本机环境确有特殊要求。

根工程建议使用版本目录 `gradle/libs.versions.toml` 管理依赖，减少散落版本号。

### 3.1 Release 签名与 JKS

Release 包使用自定义 JKS 签名时，签名材料和密码不得进入 Git。当前工程采用两类输入源：CI 优先使用环境变量，本地开发机可复制 `signing.properties.example` 为根目录 `signing.properties`。`signing.properties`、`keystore.properties`、`keystore/`、`*.jks`、`*.keystore`、`*.p12`、`*.pfx` 已加入 `.gitignore`。

支持的配置键：

```properties
RELEASE_STORE_FILE=<absolute-path-outside-repository>
RELEASE_STORE_PASSWORD=<your-store-password>
RELEASE_KEY_ALIAS=<your-key-alias>
RELEASE_KEY_PASSWORD=<your-key-password>
```

要求：

- `RELEASE_STORE_FILE` 建议指向仓库外路径；如放在仓库内，只能放在被忽略目录中。
- 不要把真实密码、alias 或 keystore 路径写入 `gradle.properties`、提交说明、测试快照或文档示例。
- `assembleRelease`、`bundleRelease` 等 release 打包任务会先执行 `validateReleaseSigning`，缺少配置或 JKS 文件不存在时直接失败并提示缺失项。
- CI 如需保存 JKS，优先使用密钥管理能力保存 base64 内容，构建时解码到临时目录，并通过环境变量传入 Gradle。
- GitHub Actions 使用受保护的 `release` Environment 保存四项签名 secret，并使用公开变量 `RELEASE_CERT_SHA256` 固定校验证书指纹；签名构建 job 不持有仓库写权限。
- GitHub APK 与未来 Google Play 安装包需要互相覆盖，因此两条渠道必须使用同一个 app-signing certificate。Play upload key 不能替代 GitHub APK 使用的 app-signing key。

示例配置方向：

```kotlin
val appVersionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
val appVersionName = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.github.ycfeng.ocdeck"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.ycfeng.ocdeck"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}
```

### 3.2 版本与自动发布

应用版本集中在根目录 `gradle.properties`：

```properties
VERSION_CODE=5
VERSION_NAME=0.2.0
```

`VERSION_NAME` 使用稳定 SemVer，正式 tag 必须精确为 `v${VERSION_NAME}`；`VERSION_CODE` 必须高于上一稳定 tag。CI 不从 tag 或 run number 动态覆盖源码版本，避免本地构建、设置页展示、APK文件名和发布记录出现漂移。

自动化分为两条工作流：普通 CI 无签名 secret，执行社区/文档与第三方/法律清单审计、Go race tests、GoMobile AAR 门禁、Android 单元测试和 Debug 构建；Release workflow 使用 `preflight`、`prepare-notes`、`build-release`、`publish` 四个 job。`prepare-notes` 在不读取签名 secret 的情况下生成可审阅说明 artifact，持有 JKS 的 job 没有仓库写权限，持有 `contents: write` 的发布 job 不读取签名 secret。当前只发布 `arm64-v8a`、`armeabi-v7a`、`x86_64` 三个 ABI APK 和 `SHA256SUMS`，不构建 AAB 或 universal APK。详细操作见 `doc/release/github-actions.zh-CN.md`。

## 4. 工程结构

当前业务代码仍以 `:app` 单模块为主，不急于拆业务多模块。OpenCode API、状态和页面边界仍在持续校准，通过包结构保持职责边界并预留未来拆分路径。

例外：`frpc STCP visitor` 使用独立 Android library 模块 `:frpc-stcp-visitor` 作为 GoMobile AAR bridge 边界。该模块只暴露稳定 Kotlin 接口和 value object，不承载 feature/data/domain 业务代码，不代表提前拆分业务多模块。

推荐目录：

```text
app/src/main/java/io/github/ycfeng/ocdeck/
  OpenCodeApplication.kt
  MainActivity.kt

  app/
    AppContainer.kt
    AppViewModelProvider.kt
    OpenCodeApp.kt

  core/
    config/
    error/
    logging/
    model/
    navigation/
    network/
    security/
    store/
    util/

  data/
    agent/
    command/
    config/
    event/
    file/
    permission/
    project/
    provider/
    server/
    session/
    vcs/

  domain/
    model/
    repository/
    usecase/

  feature/
    file/
    projectpicker/
    projectshell/
    sessionlist/
    sessiondetail/
    composer/
    review/
    settings/
    server/
    provider/
    modelsettings/
    permission/
    question/

  ui/
    component/
    theme/
```

STCP bridge 模块结构：

```text
frpc-stcp-visitor/src/main/java/io/github/ycfeng/ocdeck/frpcstcpvisitor/
  FrpcStcpVisitorClient.kt
  GoMobileFrpcStcpVisitorClient.kt
  UnavailableFrpcStcpVisitorClient.kt

frpc-stcp-visitor-go/
  bridge-versions.properties
  cmd/preparefrp/
  cmd/normalizezip/
  downstream/frp-v0.69.1-p1/
  go.mod
  internal/anetcompat/
  types.go
  visitor.go
  build-aar.ps1
  build-aar.sh
```

`frpc-stcp-visitor-go/` 保存真实 frp GoMobile wrapper 源码。`bridge-versions.properties` 固定 bridge、Go、x/mobile 和 Android API 版本；禁止在构建脚本中使用 `gomobile@latest`。基础 frp 固定为 `github.com/fatedier/frp@v0.69.1`，`cmd/preparefrp` 校验上游 module sum、zip SHA 和待修改文件 SHA 后应用 `downstream/frp-v0.69.1-p1/` 中的最小补丁，不直接修改 Go module cache。补丁修复动态 visitor 配置与 Control 安装的竞态、传播真实 listener bind 状态，并暴露 config revision、control epoch 和阻塞式 `WaitVisitorReady`。

`build-aar.ps1` / `build-aar.sh` 使用固定工具链生成 AAR，经过 `cmd/normalizezip` 规范化 ZIP 顺序和时间戳后，输出到 `frpc-stcp-visitor/libs/frpc-stcp-visitor.aar`，并以不可变坐标 `io.github.ycfeng.ocdeck:frpc-stcp-visitor-gobridge:0.3.7-frp0.69.1-p1` 发布到本地 Maven 仓库 `frpc-stcp-visitor-go/build/repo/`。同一坐标若字节变化必须拒绝覆盖；制品同时生成 sources、SHA-256、Java API signature、bridge provenance、frp patch provenance 和 native validation metadata。AAR 的 `META-INF/OCDECK/` 内嵌项目法律文本、逐份第三方许可证、精确 Java API 和 bridge/frp provenance，外部 sidecar 用于 Gradle 与发布门禁复核。GoMobile linker 固定使用 16KB max page size 并移除 DWARF/静态符号表，`cmd/checkaar` 校验四个预期 ABI、ELF machine、全部 `PT_LOAD` 对齐和 stripped 状态。App 打包会将 `libgojni.so` 排除在 Android Gradle Plugin 后续 strip transform 之外，使 Release APK 保留这些已验证的 AAR 字节；APK 门禁仍会重新校验 hash、ELF metadata、对齐和 stripped 状态。GoMobile 的 `-javapkg` 前缀对应反射入口 `io.github.ycfeng.ocdeck.frpcstcpvisitor.gobridge.frpcstcpvisitor.Frpcstcpvisitor`。`internal/anetcompat` 用标准库网络接口函数替换 `github.com/wlynxg/anet`，并在主 module 显式继承 frp 的 yamux replacement。

未生成 AAR 时 Android Debug 仍可编译，STCP 连接运行时返回明确不可用错误；Release 的 `preReleaseBuild` 必须执行 `checkGoMobileBridgeAar`，逐字节校验 AAR checksum、固定 API signature、内外 bridge/frp provenance、法律文本、许可证、native metadata 和 AAR 内各 ABI `libgojni.so` 哈希。`-PrequireGoMobileBridge=true` 可将相同门禁应用到 Debug/CI 构建。静态门禁通过后，发布前仍必须在目标 ABI 和 16KB page-size 设备上完成真实 native load 与 STCP 闭环验证，不能只以 ELF/JVM 检查代替设备测试。

Kotlin bridge 使用 `GoMobileBridgeUnavailableException` 表示生成类缺失，使用 `GoMobileBridgeApiMismatchException` 表示类、方法或 payload 不兼容。Readiness 与 SSE 将这些启动失败归类为永久失败，不做无限重试。仅修改 Kotlin bridge API 或失败处理时仍需执行完整 bridge 门禁，但生成 AAR 字节与 `BRIDGE_VERSION` 可以保持不变；native 或生成 AAR 字节发生变化时必须递增 `BRIDGE_VERSION`。

未来拆分多模块时，可按以下方向迁移：

- `:core:network`
- `:core:ui`
- `:core:security`
- `:data:opencode`
- `:feature:session`
- `:feature:settings`
- `:feature:composer`

当前不拆业务模块的目的不是降低架构质量，而是避免在 API、状态和页面边界尚未稳定时制造跨模块改动成本。

## 5. 手动 DI 方案

当前使用普通 Kotlin 对象完成依赖注入，不引入 Hilt/Dagger/KSP。入口是 `AppContainer`，由 `OpenCodeApplication` 创建并持有。

职责划分：

- `AppContainer` 创建全局依赖：`Json`、`OkHttpClient`、`RetrofitFactory`、`SecureCredentialStore`、`ServerPreferencesStore`、`PathNormalizer`、`Redactor`、`InMemoryOpenCodeStore`、`ProjectSnapshotCoordinator`、`OpenCodeEventClient`、`AppConnectionCoordinator`。
- `AppContainer` 按当前服务器创建 API 客户端和 Repository。
- ViewModel 通过构造函数接收 Repository、Store、UseCase。
- `AppViewModelProvider` 提供 ViewModel Factory，集中处理样板代码。

示例依赖图：

```text
OpenCodeApplication
  -> AppContainer
    -> Json
    -> Redactor
    -> SecureCredentialStore
    -> ServerPreferencesStore
    -> OkHttpClient
    -> RetrofitFactory
    -> OpenCodeApi
    -> ProjectSnapshotCoordinator
    -> OpenCodeEventClient
    -> AppConnectionCoordinator
    -> ProjectRepository
    -> SessionRepository
    -> ProviderRepository
    -> PermissionRepository
    -> InMemoryOpenCodeStore
```

采用手动 DI 的原因：

- 初期依赖图较小，手写成本可控。
- 避免 Hilt/KSP/kapt 生成代码带来的构建链复杂度。
- Kotlin `2.4.0`、KSP、Hilt 的兼容性可在后续独立验证。
- 构造函数注入方式天然兼容未来迁移到 Hilt。
- 出问题时依赖来源清晰，便于调试启动和网络链路。

约束：

- 禁止在 feature 内部随意创建 `OkHttpClient`、`Retrofit`、Repository。
- 禁止散落全局单例。确需单例时必须由 `AppContainer` 管理。
- ViewModel 不直接持有 Android `Context`，除非使用 `AndroidViewModel` 的必要场景。
- Repository 不直接依赖 Compose 类型。

## 6. Room/Hilt 延后策略

### 6.1 Room 延后

OC Deck 是服务端驱动应用：项目、会话、消息、权限、问题、状态主要来自 OpenCode Server 的 REST API 与 SSE。当前本地真正需要持久化的是服务器配置、最近项目、偏好设置和敏感凭据，不需要立即设计完整关系型数据库。

当前使用：

- DataStore Preferences：保存服务器列表、当前服务器、最近项目和 UI 偏好（配色方案、应用语言等）。每条最近项目记录都有数值 `sortOrder`，每个服务器最多按升序展示 20 条；旧记录缺少该字段时保留数组顺序，后续写入会把该服务器保留的记录从零开始连续重编号。首次新增插入顶部，确保已有记录或更新 metadata 时保留原位置。导航触发的 add/upsert 属于辅助写入，由应用级有序 `RecentProjectRecorder` 在导航后 best-effort 执行，本地持久化失败不能阻止进入项目。当前没有独立的上次打开项目字段，也没有按项目保存最近 session。
- Android Keystore：保存 OpenCode Basic 密码、SSH 密码/私钥/passphrase/host fingerprint，以及 frp/STCP secret。Provider API key 与 OAuth 凭据写入 OpenCode Server auth store，Android 不持久化。
- 内存 Store：保存当前运行期间的项目、会话、消息、权限、问题、SSE 状态。

Room 引入条件：

- 需要离线查看会话历史。
- 需要本地消息搜索。
- 需要冷启动快速恢复上次会话详情和消息列表。
- 需要在 SSE 断线期间可靠保留增量状态。
- 会话、消息、diff 等数据量较大，需要分页缓存。
- 多服务器、多项目之间存在明显的本地索引需求。

延后 Room 可以避免过早确定 Entity/DAO/schema、迁移策略、本地与服务端一致性策略，也避免初期引入 KSP。

### 6.2 Hilt 延后

Hilt 的价值在依赖图增长、多模块增多、测试替身复杂时会更明显。当前依赖边界仍在演进，且未引入 Room，使用手动 DI 更直接。

Hilt 引入条件：

- ViewModel Factory 样板明显增多。
- Repository、UseCase、Store 依赖图复杂到手动维护易错。
- 开始拆分多模块，需要统一依赖装配。
- 需要更系统地做 instrumentation test 或替换网络/数据库实现。
- 已验证 Kotlin `2.4.0`、KSP、Hilt、AGP `9.2.1` 的组合稳定。

迁移路径：

1. 保持现有 Repository/ViewModel 构造函数注入不变。
2. 增加 Hilt plugin、KSP 和 `@HiltAndroidApp`。
3. 将 `AppContainer` 中的创建逻辑逐步迁移为 `@Module` + `@Provides`。
4. 按 feature 逐步替换 ViewModel Factory 为 `@HiltViewModel`。
5. 删除不再需要的手动 Factory。

## 7. 网络层设计

网络层统一封装 OpenCode REST API、认证、错误解析、超时、SSE 和日志脱敏。

### 7.1 基础结构

建议核心类：

- `OpenCodeApi`：Retrofit interface，承载 REST 接口。
- `RetrofitInboundResponsePolicyInterceptor`：要求每个 `OpenCodeApi` 方法显式声明响应模式，为有界请求附加 encoded-body network interceptor tag，对 decoded 成功实体施加边界，并丢弃不应进入 Retrofit converter 的 body。
- `EncodedResponseLimitInterceptor`：network interceptor，在 OkHttp 执行 `Content-Encoding` 解码前限制 tagged response-body octets。
- `OpenCodeApiFactory`：根据 `ServerConfig` 创建 API 实例，并为 `ServerConnection` 同时提供共享 OkHttp client 的窄 `SessionMessagesTransport`，以及单独有界为十分钟的 Provider OAuth callback client。
- `SessionMessagesTransport`：直接请求会话消息；非 2xx 不读取 body，2xx 同时使用 64 MiB encoded response-body policy，并在 OkHttp callback 线程执行 64 MiB 有界 decoded 流式 JSON decode。
- `OpenCodeEventClient`：基于 OkHttp `Call` 和自定义流式 SSE reader 管理全局和项目事件流。
- `OpenCodeErrorParser`：统一解析 HTTP 错误、网络错误、服务端错误体。
- `OpenCodeFailureClassifier`：不依赖异常 message，将 transport、协议、大小和操作失败转换为类型化语义原因；UI 再通过本地化 `UiText.Resource` 映射这些原因。
- `RedactingInterceptor`：请求/响应日志脱敏。
- `AuthInterceptor`：附加服务器认证信息，不向日志暴露原始凭据。
- `SshTunnelManager`：按服务器建立 SSH 本地端口转发，将远端 OpenCode 服务地址改写为 `127.0.0.1:<localPort>`，供 REST 与 SSE 共用。
- `FrpcStcpVisitorManager`：按服务器启动 frpc STCP visitor，将远端 OpenCode 服务地址改写为 `127.0.0.1:<bindPort>`，供 REST 与 SSE 共用；真实 frp client 由 `:frpc-stcp-visitor` 模块通过 GoMobile AAR 适配，AAR 由 `frpc-stcp-visitor-go/build-aar.ps1` 或 `build-aar.sh` 本地/CI 生成。
- `DirectoryQueryInterceptor` 可选：集中校验 directory 规范化。

Android 端连接地址必须按运行环境配置，不把某个模拟器、真机或 `adb reverse` 端口映射当作所有设备的默认行为。连接方式分为直连、SSH 本地端口转发、frpc STCP visitor 三种互斥模式。SSH 和 STCP 都使用应用在设备侧打开的 `127.0.0.1:<localPort/bindPort>`，供 REST 与 SSE 共用。

STCP 连接使用双层就绪判据：

1. transport readiness：`StartSession -> EnsureVisitor -> WaitVisitorReady`。只有当前 frps Control 已登录，且指定 config revision 的 visitor 在当前 control epoch 下真实持有本地 listener，才视为 transport ready。配置提交成功或临时端口可绑定都不能作为就绪信号。
2. application readiness：通过该 listener 请求 `GET /global/health`。成功后才能证明本地 listener、STCP 转发、server name/secret、目标 OpenCode、TLS/HTTP 和认证组成的当前通路可用。

`FrpcStcpVisitorManager` 按 server config epoch 和单调 tunnel generation 管理状态。首次进入同一 generation 的 REST、健康检查、全局 SSE 和项目 SSE 共享一个 single-flight；全局状态锁只保护状态转换，JNI、HTTP、重试 delay 和 stop 均在锁外。成功只按 generation 缓存，失败不缓存并精确停止该 generation 保存的 session，不能按 serverId 误关后来建立的新隧道。配置或 Keystore 中 token/secret 的值变化会使旧 lease 失效，即使 credential key 未变化也必须新建 generation。

已 Ready 的 generation 在后续 `getConnection()` 时读取 native runtime state。相同 control epoch 且 listener 仍 ready 时直接复用，不重复 health；frps 重连、listener 重绑或 control epoch 变化时，同一 generation 内共享一次 `WaitVisitorReady + /global/health` 恢复。返回连接前再次读取 native state，并复验精确 generation/control epoch 与 manager 当前状态；该复验只作用于 STCP，直连和 SSH 不增加专用屏障。旧 epoch 的迟到结果不能覆盖新 generation。`ServerRepository.getConnection()` 是 REST 与 SSE 的统一屏障，因此项目快照和 EventSource 都不得在 readiness 完成前启动。

application readiness 使用独立短超时 profile 和整体时间预算。仅重试连接拒绝、reset、EOF、timeout、HTTP 408/425/429/5xx 和 `healthy == false`；401/403/404、TLS/hostname、序列化和配置错误快速失败。首次显式健康检查复用 readiness 的 health evidence，避免连续请求两次 `/global/health`；已经 Ready 的 generation 上手动健康检查仍执行一次 fresh health。

Retrofit JSON 配置：

```kotlin
Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}
```

每个 `OpenCodeApi` 方法都通过 `@RetrofitInboundResponse` 声明 `BOUNDED` 或 `EMPTY_SUCCESS`。`BOUNDED` 附加 16 MiB encoded response-body policy，由 network interceptor 在 OkHttp content decoding 前执行，再延迟实施独立的 16 MiB decoded-entity 上限。`Content-Length` 只用于提前拒绝，未知或低报长度在 `max + 1` 失败。`EMPTY_SUCCESS` 不读取成功 `Response<Unit>` body，直接关闭。所有非 2xx body 也会关闭并替换为空实体，同时保留状态码，避免 Retrofit 缓存或转换敏感错误体。缺少策略时在请求发出前 fail-closed。没有 Retrofit `Invocation` 的调用不经过 Retrofit interceptor，包括 session-message direct transport，因此必须自行附加 encoded-body policy。

### 7.2 关键 API 分组

当前主链路 API：

- 健康检查：`GET /global/health`
- 全局事件：`GET /global/event`
- 项目路径：`GET /path`
- 项目当前信息与名称编辑：`GET /project/current`、`PATCH /project/{projectID}`（当前仅发送 `name`）
- 会话列表：`GET /session?directory=...&workspace=...&roots=true&limit=...`，为 Store 会话窗口重新获取有序前缀。
- 会话 metadata：`GET /session/{sessionID}`，用于补取当前窗口外路由目标及其有界父链。
- 会话创建：`POST /session`
- 会话消息：`GET /session/{sessionID}/message`，使用共享 REST OkHttp client 的专用 transport，绕过 Retrofit converter/错误体缓存；非 2xx 不读取 body，encoded response-body octets 限制为 64 MiB，2xx 再使用计数 InputStream + 宽容 `Json.decodeFromStream` 执行独立 64 MiB decoded-entity 边界和 EOF 验证。
- 发送 prompt：`POST /session/{sessionID}/prompt_async`
- 中止：`POST /session/{sessionID}/abort`
- 项目事件：`GET /event?directory=...&workspace=...`
- Provider/模型基础数据：`GET /provider?directory=...&workspace=...`
- Agent：`GET /agent`
- 命令：`GET /command`
- 权限/问题：`GET /permission`、`POST /session/{sessionID}/permissions/{permissionID}`、`GET /question`

已接入与后续扩展 API：

- Diff：`GET /vcs/diff?mode=git&directory=...`、`GET /session/{sessionID}/diff?directory=...`
- Session 管理：rename、share、archive、delete、fork、summarize、children、todo；revert/unrevert 已接入 legacy `POST /session/{sessionID}/revert|unrevert`，使用编码后的 directory header。
- 文件：`/file`、`/file/content`、`/find/file`。当前已接入项目文件树、搜索和只读预览；`/file/content` 先经过通用的 16 MiB encoded response-body 和 decoded Retrofit 边界，再在反序列化前保留 reader 层 decoded `ResponseBody` 复验，避免在 UI 限制生效前加载任意大响应。
- Provider 管理：instance-scoped `GET /provider`、`GET /provider/auth` 以及有状态 OAuth authorize/callback 可带可选 `directory/workspace`；server-global root-control 操作为 `PUT/DELETE /auth/{providerID}`、`GET/PATCH /global/config` 和 `POST /global/dispose`。OAuth authorize/callback 保持相同 scope 与原始 method index，不自动重试；长 auto callback 使用专用有界 client。
- config、session status、MCP、LSP、plugin 状态已接入；PTY/terminal、worktree 等仍待实现。

完整端点与完成度以 `doc/architecture/mobile-interaction.zh-CN.md` 为准。新增或调整接口时必须同步更新 DTO 容错、directory/workspace 参数测试和交互文档。

### 7.3 SSE 同步

Android 端需要两类事件流：

- 全局 SSE：`GET /global/event`，用于服务器级事件。
- 项目 SSE：`GET /event?directory=...&workspace=...`，用于项目、会话、消息、权限、问题、状态变化。

SSE 设计要求：

- 进入项目后先并行拉取 REST 快照，再建立项目 SSE。
- SSE 事件只更新内存 Store，不直接操作 Compose 状态。
- 使用指数退避自动重连，并保留明确的连接状态：`Connecting`、`Open`、`Retrying`、`Failed`、`Closed`。
- App 前台恢复时执行健康检查，必要时重新同步当前项目快照。
- 对事件 JSON 使用未知字段容忍，避免服务端升级导致客户端崩溃。
- 生产路径不得使用先完整分配 `data: String` 的 okhttp-sse parser；使用 `ResponseBody.source()` 按字节解析，单个物理/逻辑行与累计 event data UTF-8 字节分别限制为 32 MiB。32 MiB 用于兼容单条最大 20 MiB raw 附件的 Base64 回显及 framing。
- SSE 只接受 HTTP 200 与 `text/event-stream` media type（允许 charset）；204、其他非 200、缺失/错误 Content-Type 均在 `Open` 前关闭且不读取 body。204、非瞬时 4xx 和 Content-Type 错误进入 `Failed`，408/409/425/429 与 5xx 重试。
- 行超限必须在整行转 `String` 前失败，event 超限必须在完整 data 拼接前失败；超限关闭 body、取消 Call 并报告不含 payload 的类型化失败。只有完整空行 dispatch event，EOF 丢弃未终止 pending line/event；显式 cancel 引发的 `IOException` 不得触发重连 failure，clean EOF 只回调 closed。单事件 JSON/字段类型异常只丢弃该事件，不能杀死连接。
- 断线期间允许 UI 展示“正在重连”，但不应清空已有会话内容。
- 打开、关闭和重试使用 generation/lease 校验；关闭后的迟到任务不得重新发布 `Open` 或重建已关闭连接。
- 快照刷新失败不得阻断 EventSource 重连；一次恢复只执行一次可合并的 REST 校准。
- SSE 和快照错误进入 Store/UI 前必须经过 `Redactor`。
- STCP control epoch 变化后重新校准，旧 generation/epoch 结果不得覆盖新连接。
- workspace 能力启用后，Store、SSE 和 ViewModel 的项目 key 必须同时包含 directory 与 workspace。`ProjectKey` 只能通过统一工厂创建，directory/workspace 保留规范化请求值，并使用 `PathNormalizer.comparisonKey` 比较；仅 Windows 盘符路径大小写不敏感。
- 页面 ViewModel 只持有幂等 owner lease；项目壳层和直接进入的会话详情都持有 global lease，多 owner 共享一个 global source。最后一个 owner 释放或强制关闭后进入 `Closed`，连接、回调、重试和快照都必须通过 generation/source/transport identity 最终校验。
- 同一项目的 project source 为 `Open` 且 transport identity 与 global listener 一致时，project source 是项目事件权威来源，global 中带 directory/workspace 的对应事件直接忽略；project 尚未打开、重试或失败时，global 才作为 fallback 归约事件。
- 项目 SSE 打开、重连和前台恢复后，由应用级 `ProjectSnapshotCoordinator` 按项目 key 和生命周期 token 执行 single-flight 校准；校准 token 区分 project/global 权威来源。项目流未 Open、重试、失败或不存在时，global fallback 的 LSP/MCP 能力事件也可发起校准；project 随后 Open 会取消旧 global flight。校准期间有界缓冲权威来源事件，成功时先快照后顺序重放，失败时保持 SSE 打开并重放；能力变更只标记 dirty，当前轮成功、失败或缓冲溢出后最多合并启动一轮 follow-up。
- Store 分别维护项目数据和消息数据 revision。ViewModel 直接 REST 项目快照使用 revision CAS 和 latest-request gate，不能覆盖更晚的 SSE/协调器状态或更新后的刷新；消息 GET 在 revision 未变化时替换但保留 REST 缺失的本地乐观消息及 parts，变化时按 message/part id 合并并保留实时字段、delta、历史补全和请求期间的删除 tombstone。合并后的空 message text 从最终有效 text parts 重建，`session.deleted` 同步删除该 session 的 messages/parts 并推进删除 revision。connection/loading/通知变化不推进项目数据 revision。
- `OpenCodeRepository` 项目快照和 `DefaultForegroundHealthChecker` 在 REST/health I/O 成功或非取消失败后重新获取 connection 校验 transport identity；旧连接失败而 identity 已前进时以新连接有限重试，identity 未变时传播原异常，连续变化返回不含敏感信息的类型化失败。
- `AppConnectionCoordinator` 在应用前台恢复时按 server 合并 health/readiness 检查；transport identity 改变时整体重建该服务器仍需保持的全局/项目流，不变时复用流并校准项目快照。明确 older-than 当前 identity 的迟到 open attempt 必须原子解绑当前 attempt 并进入标准 retry，close 或新 generation 使该 retry 失效。
- 全局 SSE 状态按 serverId 隔离；事件缓冲同时限制数量和估算字节数，溢出时取消快照并立即重放，避免无界内存增长。

## 8. 路径规范化

路径规范化是当前基础能力。Web 端已出现 `E:\\...` 与 `E:/...` 重复项目问题，Android 端必须在数据模型和 Repository 层解决。

规则：

- 反斜杠统一替换为 `/`。
- 删除尾部 `/`，但根路径 `/` 保持不变。
- Windows 盘符路径比较时大小写不敏感。
- 最近项目按规范化 worktree path 去重。
- 网络请求 `directory` 参数统一使用规范化结果。
- UI 可以保留更友好的 display path，但内部 key 使用 normalized path。

项目内文件相对路径使用独立的 `ProjectFilePathNormalizer`，不能直接套用项目根路径规则：拒绝 NUL、绝对路径和任意 `..`；Windows 项目把反斜杠作为目录分隔符，POSIX 项目保留文件名中的合法反斜杠。`/file` 响应进入领域层前还需验证节点是请求目录的直接子项，并按规范化 path 去重。

建议模型：

```kotlin
data class ProjectRef(
    val normalizedDirectory: String,
    val projectId: String?,
    val displayName: String,
    val vcs: String?,
    val icon: ProjectIcon?
)
```

## 9. 状态管理

当前使用 Repository + UseCase + 内存 Store + ViewModel `StateFlow`。

状态流向：

```text
REST snapshot + SSE events
  -> Repository
  -> InMemoryOpenCodeStore
  -> ViewModel StateFlow
  -> Compose UI
```

Store 建议拆分：

- `ServerStore`：当前服务器、健康状态、连接状态。
- `ProjectStore`：项目列表、当前项目、路径映射。
- `SessionStore`：会话列表、当前会话、消息、会话状态，以及按 ProjectKey 隔离的根会话窗口；窗口包含可见/请求 limit、原始结果数、加载/重试/末尾状态、generation 和已加载根会话 identity。
- `ProviderStore`：providers、models、connected 状态。
- `PermissionStore`：权限请求、问题请求。
- `NotificationStore`：会话完成和错误通知、项目/会话未读状态、最多 500 条和 30 天 TTL 保留策略。
- `ComposerStore`：当前输入草稿、agent、model、variant、手机本地附件、项目文件上下文、命令模式。

项目文件浏览不写入现有会话/SSE Store，而由独立 `ProjectFileBrowserViewModel` 管理目录缓存、展开集合、搜索和当前文件。该 ViewModel 使用当前项目专属 `ViewModelStore`：项目内页面和 session 切换可复用已成功目录状态，切换项目时清理 Store；面板关闭时取消迟到请求、清除搜索和当前文本/Base64，同时保留有效目录/展开缓存，避免 Activity 级长期持有大文件内容。搜索、文件内容和目录请求均使用 request id/generation 防止取消后的旧结果覆盖新状态。Composer picker 选择态不存入该项目级 ViewModel，而由壳层持有绑定 route/session 的请求，并在 owner 不匹配或路由变化时丢弃。

首进项目同步流程：

1. 规范化 `directory`。
2. 并行请求 `/project/current`、`/path`、`/provider`、`/mcp`、`/session?roots=true&limit=<current-window>`、`/agent`、`/config`、`/session/status`、`/vcs`、`/command`、`/permission`、`/question`。
3. 将快照写入内存 Store。
4. 建立项目 SSE。
5. 根据 SSE 增量更新 Store。
6. 前后台切换时做健康检查和必要的快照刷新。

## 10. 导航与页面框架

使用 Single Activity + Navigation Compose。页面不复刻 Web 的桌面弹窗层级，而按移动端重新组织。

当前主导航路由：

- `ProjectPickerRoute(serverId)`：项目选择和服务器文件浏览入口。
- `ProjectShellRoute(serverId, directory)`：项目会话壳层，包含抽屉、顶部栏、会话区域。
- `SessionDetailRoute(serverId, directory, sessionId)`：会话详情；新会话复用该页面的 `"new"` 状态，首次发送后再创建后端 session。只有从项目首页输入区或附件入口进入时可附加轻量级初始 agent/model/variant query；已有会话和其他“新建会话”入口不携带这些值。会话 ViewModel 只在 `"new"` 状态接收，并在使用前根据已加载 capability 重新校验。
- `ServerListRoute`：服务器列表。
- `AddServerRoute`：新增服务器，保存成功后返回 `ServerListRoute`。

`ServerListRoute` 也是固定启动入口。服务器数据加载完成且列表为空时直接在该页展示欢迎空态，说明 Android 应用只是客户端、不会创建或运行 OpenCode Server；不增加独立 onboarding 路由。新增成功后返回该列表，不自动导航到 `ProjectPickerRoute`，打开新服务器仍由用户明确触发。应用不自动持久化 `127.0.0.1:4096` 服务器，新增表单的名称和服务地址默认留空。一次性 DataStore 迁移只清理仍与历史自动生成配置完全一致的 `local / Localhost / loopback:4096` 直连记录，任何已修改配置均保留。

当前子路由与占位能力：

- `ReviewRoute(serverId, directory, sessionId?)`：独立路由仍为占位；可用 diff 当前位于会话详情 Changes tab。
- `SettingsRoute(serverId)`
- `BackgroundRunSettingsRoute(serverId)`：通知权限、忽略电池优化和最近任务保留检查；电池优化使用系统确认弹窗直接申请，不跳转系统列表页。
- `ProviderSettingsRoute(serverId, directory?, workspace?)`
- `CustomProviderFormRoute(serverId, directory?, workspace?, providerId?)`
- `ModelSettingsRoute(serverId)`
- `ProjectEditRoute(serverId, directory, projectId)`：后续用于完整编辑项目图标、颜色和启动命令；当前抽屉菜单内仅提供项目名称编辑弹窗。

页面适配要求：

- 项目壳层使用 Compose `ModalNavigationDrawer`，避免 Web offscreen DOM 指针拦截问题。
- 项目选择页和左侧 rail 共享当前服务器持久化的最近项目顺序。路径继续按 server 加比较 key 规范化去重。活动项目缺失时，rail 将其合入顶部并保持最多 20 项；重排 API 只能在同一次原子持久化中纳入这个被显式标识的项目。重排以提交时的当前记录为准，保留陈旧界面快照遗漏的并发新增记录，也不会重新创建已删除的陈旧提交记录。项目按钮使用最多占抽屉安全内容高度 75% 的有界列表，“打开项目”和“设置”保持固定且不参与排序；选择项目进入其项目首页，并尽量复用返回栈中已有的匹配目的地，最近项目记录不恢复最近 session。
- 两处都支持长按纵向拖拽、边缘自动滚动、乐观排序、持久化和失败回滚。项目选择页仅卡片正文启动拖拽，因此删除按钮保持独立；Drawer rail 重排期间禁用外层水平抽屉手势。
- 项目/会话导航成功后向应用级 `RecentProjectRecorder` 提交打开记录，但已有记录保持原位置。返回项目首页、直接打开已有会话和项目重命名只确保记录或更新 metadata。DataStore 失败只做安全报告，绝不回滚导航或已经成功的项目改名。
- 只有 App 在前台且会话 destination lifecycle 至少为 `STARTED` 时才持有可见性 lease；通知已查看状态不得根据 ViewModel 存活推断。
- 项目文件由壳层提供带浏览/选择模式的右侧覆盖面板：手机占满宽度，大屏最大 420dp；遮罩只覆盖面板外区域，面板打开时隐藏底层语义，系统返回先从预览回到文件树再关闭。选择模式通过绑定 route/session 的请求确认最多 10 个完整文件，取消不修改草稿；不采用压缩会话内容的桌面双栏。
- 所有全屏页面之间的 `NavHost` 路由跳转统一使用滑入滑出动画：前进右进左出，返回左进右出。
- 设置、服务器管理、Provider、模型管理使用单列全屏页面。
- Slash command 和 `@` mention 可使用 composer 上方内联建议；短列表的 agent/model/variant、上下文和状态信息可使用受屏幕约束的 anchored popup；内容较长、需要搜索或包含复杂操作时使用 `ModalBottomSheet`。
- Popup、sheet 和内联面板必须可聚焦、可通过系统返回关闭、限制宽高，并避免被 IME 或底部 composer 遮挡。
- 项目首页 Composer 预览持有临时 Agent 与模型/Variant override。三个参数控件在当前页面原地打开且不导航；输入区和附件入口携带当前轻量选择进入 `"new"` 会话。模型和 Variant 继续通过按服务器划分的 DataStore 偏好持久化，Agent 只保留在项目壳层 ViewModel 生命周期内。
- 权限和问题请求用可阻塞的底部 Dock 或 sheet，并适配小屏；权限确认复刻 Web 底部 Dock，pending 时隐藏普通 composer。
- 外部帮助链接使用浏览器或 Custom Tabs。

## 11. UI 组件规划

核心能力与组件：

- `BottomComposer`：输入框、手机本地附件、项目文件上下文 chip、agent、model、variant、发送/停止按钮、命令模式 chip。
- 共享 Composer 参数 UI 与选择规则位于 `feature/composer/`，项目首页预览和会话 Composer 复用同一套参数按钮、Agent/Model/Variant picker、受支持 Agent 过滤和模型/Variant 回退规则，避免项目 UI 依赖会话 UI。
- 命令建议：`/` 命令、项目命令、skill、内置命令搜索，可使用 composer 上方内联面板。
- Mention 建议：`@explore`、`@general` 等 agent mention，数据源与文件搜索分离。
- Model picker：按 provider 分组、搜索、当前选择、连接 provider、管理模型；管理入口会先关闭 picker，再导航到真实 Provider/模型设置路由，并为 Provider 方法发现和 OAuth 保留当前项目 directory。列表较长时使用受约束 popup 或 sheet。
- Agent picker：只展示 composer 支持的 `Build`、`Plan`，内部 id 保持 `build`、`plan`，不要直接暴露 `/agent` 返回的全部 7 个 agent。
- Variant picker：根据当前模型 `variants` 动态展示 `默认` + 模型支持项，模型无 variants 时不展示入口；动态项展示文案首字母大写，并使用“推理强度/Reasoning”避免误解。
- `ProjectFilePanel`：项目文件懒加载树、搜索、树/内容单页切换、文本/图片/二进制状态，以及带 Checkbox 语义、独立预览和固定确认栏的显式多选模式。
- `OpenCodeCodeViewer`：复用 Markdown 的 Prism4j 高亮配置，提供行号、文本选择和纵向虚拟列表；文件预览限制 500,000 字符、20,000 行和单行 20,000 字符。
- `SessionListDrawerContent`：共享持久顺序、支持长按拖拽与边缘自动滚动、乐观失败回滚、本地化 TalkBack“上移项目/下移项目”操作，并保留完整 Tab/selected/名称/路径/通知语义的同服务器有界项目 rail，以及项目标题/路径、更多菜单、新会话和 Store 共享会话窗口；“打开项目”和“设置”固定在重排列表外。加载更多每次增加 20 条根会话目标，需要网络时带 50 条余量重取，并展示加载、重试和末尾状态。
- `SessionMessageCard`：消息内容、引用评论卡片、独立项目文件上下文行、agent/助手 Markdown 渲染、Compose 代码块高亮、复制、reset/revert、错误状态。引用评论由用户消息 synthetic text part 的顶层 `metadata.opencodeComment` 提取，REST 与 SSE 使用同一 typed 模型，并以固定 synthetic 文本解析作为兼容 fallback；`file://` 评论配对 part 从独立上下文行排除，也不按手机本地附件展示。
- `SessionRevertDock`：位于普通 Composer 上方，默认折叠，展示回滚数量和首条预览，支持逐步恢复、全部恢复与新分支提交提示；不覆盖 `PermissionDock`、问题交互或子会话只读 Dock。
- 上下文用量：token、使用率和成本等摘要，当前适合使用 anchored popup。
- `PermissionDock`：显示工具说明、patterns，并用 `once`、`always`、`reject` 回复 pending permission。
- 问题交互：单选、多选、自定义答案、拒绝；可按内容长度使用 Dock 或 sheet。
- `ServerStatusBanner`：服务器健康、SSE 连接、重连状态。

触控要求：

- 所有可点击图标的真实触控目标最小 48dp，视觉图标可以更小。
- Composer 模式 chip 的取消/删除按钮必须达到 48dp 触控目标。
- 底部 composer 固定在屏幕底部，并正确处理 IME inset。
- 小屏下设置项和服务器列表不得依赖横向空间。
- Tab、单选、多选和 Switch 通过匹配的 role 暴露 selected/checked 语义；可展开控件提供本地化的展开/折叠状态说明。
- 未读、错误、权限、连接和选择状态通过文字、图标、边框或状态说明配合颜色表达。可排序服务器卡片和项目除拖拽手势外，还提供本地化的 TalkBack 上移/下移自定义操作；Drawer 项目目标继续保留 `Role.Tab`、selected 状态、完整名称/路径和通知语义。
- 浅色和深色主题下，正文/辅助文字目标至少 4.5:1，必要非文本状态指示和控件边界目标至少 3:1。真实文本框使用 `ControlBorder`；`OpenCodeContrastTest` 覆盖文字、语义化 Diff/Markdown/语法颜色、状态指示和控件边界。
- Dialog、服务器状态、设置 sheet、建议面板和 Composer 附件使用自然测量与有界滚动，确保紧凑屏幕、200% 字体和 IME 打开时关键操作仍可到达；附件行使用横向滚动，不依赖固定宽度挤压内容。

## 12. Composer 发送状态机

普通 prompt 是核心主链路，必须保持状态机和失败恢复语义清晰。

状态规则：

- 空输入、无附件、无上下文时禁用发送。
- 如果当前会话正在工作，并且输入/附件/上下文为空，提交按钮变为停止，对应 `POST /session/{sessionID}/abort`。
- 新会话页面不立即创建后端 session，首次发送时才 `POST /session`。
- 发送前校验当前 model 和 agent。
- 发送前以 Store 中当前 `ProjectKey` 的 `PromptCapabilities.revision` 为权威，UI snapshot 只用于 revision 一致性校验；model、agent、variant 和 command 必须基于 Store 冻结副本验证。
- 附件在 sender 最终边界校验 count、必填 metadata、raw size、总预算、4 KiB data URL header、Base64 空白/字符/padding 和 encoded length，不能只信任 picker 或 `sizeBytes`。
- 同一边界独立校验项目文件上下文最多 10 个、非空 id、按操作系统语义规范化的相对路径、重复路径和项目根包含关系。每个 `file://` URL 只能由规范化项目根与已校验相对路径逐段百分号编码构造；这类实时引用不套用手机本地附件的字节/Base64 预算。
- capability revision 未变化时才原子乐观插入用户消息。
- directory 与 workspace 在一次发送、懒创建 session、消息移动、接受和失败回滚中使用同一规范化值，Store 只修改对应 workspace 分支。
- `prompt_async` 成功后等待 SSE 或消息刷新补齐 assistant 响应。
- 失败时只条件删除仍为 optimistic 的同一 message id；SSE 已确认时保留消息并返回结果不确定错误，避免重复发送。
- send、abort、revert/unrevert 共享按 server/directory/workspace/session 建立的 fail-fast operation gate，不能只依赖按钮 disabled。新会话 materialize 后先把真实 session id 加入原 lease，再发布 Store 和导航状态。

请求路径：

```text
No session id
  -> POST /session
  -> POST /session/{sessionID}/prompt_async

Existing session id
  -> POST /session/{sessionID}/prompt_async
```

特殊模式：

- 只有匹配已加载 `/command` 数据的 Slash command 才使用 `POST /session/{sessionID}/command`；未知 `/xxx` 不得直接分流到 command API。
- 普通 prompt 与已加载 Slash command 都携带所选项目文件上下文；允许仅含项目上下文的普通 prompt。
- 实现 Shell mode 时使用 `POST /session/{sessionID}/shell`。
- Stop 使用 `POST /session/{sessionID}/abort`。

### 12.1 会话回滚状态

会话回滚使用服务端持久化的 `OpenCodeSessionRevert(messageId, partId)` 作为唯一边界，不单独建立本地 Revert Store。

状态规则：

- REST Session DTO 和 `session.updated` SSE 都映射 `revert` marker；revert 时 `InMemoryOpenCodeStore` 保留完整消息，只在 `SessionDetailUiState` 生成 `visibleMessages` 与 `revertedUserMessages` 投影。
- marker 存在时，主时间线只显示 `message.id < marker.messageId`；目标消息本身也隐藏。OpenCode 时间递增 message ID 按与 Web 一致的字典序比较。
- 重置工作中的 Session 时，协调器必须在同一个 operation lease 内先等待 abort 成功，再调用 revert。pending permission/question、子会话、新会话、乐观消息和重复 mutation 不允许触发重置。
- 重置后恢复目标普通文本、`data:` 本机附件和独立项目文件上下文到 Composer，支持纯附件和纯上下文用户消息。`file://` part 永不成为本机附件；只有位于当前项目根内的引用才恢复为 `ProjectFileContext`，评论配对 backing file 被排除。历史可恢复内容损坏、超出项目根、超过对应上限或只能部分恢复时拒绝改变 marker，并向 UI 返回明确失败原因。
- Dock 逐步恢复某项时，如果后面仍有用户消息，则将 marker 移到下一条用户消息；恢复最后一项或全部恢复时调用 unrevert。
- 回滚状态发送新 prompt 时，ViewModel 记录新乐观消息 ID，并在旧 marker 尚未被 SSE 清除期间额外放行该 ID 及其后续消息。请求失败清除本地分支起点并重新显示 Dock；成功后等待服务端 cleanup 事件清除旧分支。
- `message.removed` 和 `message.part.removed` 是永久提交新分支的清理信号。revert 阶段不得主动从 Store 删除消息，否则无法即时 unrevert。

## 13. 安全、脱敏与日志

敏感信息始终使用统一规则脱敏，不得等到 release 阶段再补处理。

敏感字段：

- API key
- token
- password
- authorization header
- cookie
- provider auth
- custom provider headers
- SSH password
- SSH private key
- SSH private key passphrase
- SSH host fingerprint
- frps auth token
- STCP secret key
- env
- config 中可能包含凭据的值

策略：

- `Redactor` 提供统一方法，将敏感值替换为 `<redacted>`；已知 JSON、header、query 和配置对象优先结构化脱敏，正则只作为未知文本兜底。
- 自由文本中的裸 Bearer/Basic 等认证值，以及 HTTP(S) URL 的 userinfo 和 fragment，也必须统一替换为 `<redacted>`。
- 网络日志只在 debug 构建开启，且必须经过 `RedactingInterceptor`。
- 错误 UI 显示服务端错误摘要，不显示原始请求头和凭据。
- 崩溃报告禁止上传完整请求/响应体。
- Provider 设置页不回显 key，只显示连接状态或 masked 状态。
- `/provider` 与 `/global/config` 原始响应可能包含 API key、options、环境值及 Provider/模型 Header；必须立即投影为安全领域摘要，禁止把原始响应保留在 Compose state、内存 Store、日志或结构化摘要中。
- Provider API key 与 OAuth 凭据写入 OpenCode Server 的 server-global auth store；Android 只在编辑期间将 secret 保留于内存，并在已提交、部分提交或结果未知后清除。通过 Direct 非本机回环 HTTP 发送 Provider API key 或新自定义 Header 前，必须对冻结的已校验请求做一次性确认；Android Keystore 不保护该网络链路。
- Provider OAuth 浏览器 URL 只允许带有效 host 且无 userinfo 的 HTTP(S)。Authorize/callback 保持相同可选 `directory/workspace` 与原始 method index；auto callback 可取消且最长十分钟。Loopback URL 必须明确标为条件可用，因为手机浏览器通常无法访问运行在远程 OpenCode Server 进程中的 listener。
- 自定义 Provider 保存先暂存 disabled 的 server-global config，再写可选 auth，最后启用；凭据失败时保留 disabled 配置，最终写入无法确认时报告 unknown。global-config PATCH 是无法可靠删除字段的 deep merge，因此已保存 model ID 与 Header 名不可移除或改名；停用先更新 `disabled_providers`，再 best-effort 清理 auth，不能宣称物理删除。
- 文档、测试快照、调试面板中不得出现真实密钥。
- Repository、network 和 Store 层保留 `OpenCodeFailure`、`SseFailureReason`、`ProjectSnapshotOutcome.Failure` 等类型化语义失败；UI 映射为本地化资源，并为当前操作提供 fallback，取消与 JVM `Error` 不得转成用户错误。
- 固定 SSH host fingerprint 必须通过 `HostKeyRepository` 或等价机制在用户认证前校验，禁止关闭严格校验并在认证后补验。
- Server base URL 只允许带有效 host 且不含 userinfo/query/fragment 的 HTTP(S) URL；保存、连接和 UI 展示都执行同一校验，异常旧值不得原样显示。
- 保存带有效 OpenCode Basic 认证的直连服务器前，对规范化 URL 做无 DNS 的纯语法分类。用户名非空白且存在新密码或保留密码时，非本机回环 `http://` 地址必须显示明确但不阻断能力的确认弹窗；`localhost` 及保留子域、IPv4 `127.0.0.0/8`、IPv6 `::1` 和 mapped loopback 字面量豁免。SSH/STCP 保存和后续连接不增加门禁。确认只消费一次冻结的已校验请求，并继续使用原有凭据事务；取消不调用 Repository。Keystore 只保护本机存储，不保护 HTTP 流量。
- URI、网络响应、Base64、native bridge 返回值和私钥必须在完整分配内存前执行流式硬上限检查。普通 Retrofit/file 响应分别限制 16 MiB encoded response-body 与 decoded entity，session messages direct transport 分别限制 64 MiB；SSE 请求 identity encoding，并对该 response-body representation 的行/event 限制 32 MiB。这些上限不等于 heap 峰值：Retrofit converter 在限额内仍可能完整构建 String、DTO 图或 JSON tree，并发项目快照也可能产生较高内存峰值；`/file/content` 保留 reader 层 decoded 防御。这不代表所有 REST endpoint 已完成审计，其他潜在大响应仍需逐端点改造。SSH 私钥文件固定最多 256 KiB，在 IO dispatcher 读取，并在保存与 JSch 前按 UTF-8 字节数复验。
- `ServerRepository` 通过窄 `CredentialStore` 边界持久化服务器凭据。候选 alias 构建完成后检查取消，配置写入、结果确认、epoch 更新与 SSH/STCP 运行态失效放入 `NonCancellable` 提交段；写入异常后重新读取目标配置，已持久化则按成功继续，明确未提交才回滚，结果未知时保留候选并抛安全类型化异常。
- 配置提交后的旧 alias 清理移到配置锁外，重新读取全部服务器引用后 best-effort 删除，兼容历史共享 alias且不回滚已提交配置。`SecureCredentialStore` 在 IO dispatcher 使用 SharedPreferences `commit()` 报告失败，只包装预期 `Exception` 并让取消及 JVM `Error` 原样传播。SSH pin 仅可在 host/port endpoint 未变时继承；GoMobile Kotlin 配置 DTO 的 `toString()` 必须脱敏 token 和 secret key。
- 敏感或可能很大的 DTO、领域模型、Store 状态、transport identity、prompt value 与 UI state 使用结构化摘要覆盖 `toString()`；摘要不展开凭据、URL/endpoint、alias、路径、prompt、Base64、SSE payload 或 tool output，敏感字段严格使用 `<redacted>`，且不改变 serialization、`copy`、equals 或 hashCode。

## 14. 测试与质量门禁

当前测试重点：

- `PathNormalizerTest`：Windows 路径、根路径、尾斜杠、大小写、重复项目去重。
- `ProjectDrawerModelTest`、`ProjectInitialTest`、`ProjectPickerViewModelTest`、`RecentProjectRecorderTest`、`RecentProjectStoreReducerTest` 与 `VerticalLazyListReorderTest`：数值与旧记录顺序、连续重编号和每服务器 20 项上限、新项目置顶且已有项目不移动、原子重排/显式纳入当前项目、并发新增保留、陈旧删除不复活、Windows 路径别名去重、乐观重排持久化与回滚 callback、短视口边缘滚动方向、非阻塞导航、有序/重试 best-effort 记录、有界 Drawer 合并、项目首页导航决策和 Unicode 项目首字。
- `SessionComposerAgentResolverTest`、`SessionModelPreferenceResolverTest` 与 `SessionComposerRouteSelectionTest`：Build/Plan 过滤和回退、初始模型/Variant 校验与切换，以及仅对新会话解码项目首页的轻量 Composer 选择。
- `ProjectFilePathNormalizerTest` 与 `ProjectFileUrlBuilderTest`：平台差异、NUL、绝对路径、`..`、直接子项校验、POSIX/盘符/UNC URL 构造、百分号编码、往返和项目根包含关系。
- `RedactorTest`：key/token/password/header/env/config 脱敏。
- `RetrofitInboundResponsePolicyTest` 与 `EncodedResponseLimitInterceptorTest`：所有 `OpenCodeApi` 方法显式声明模式；缺少策略时 fail-closed；encoded/decoded 已知、未知和低报长度均执行 `max + 1`；真实 OkHttp gzip chain 在 Bridge 解码前执行 encoded 上限；非 2xx 与成功 Unit body 不读取即关闭；无 `Invocation` 的 direct call 不进入 Retrofit 策略。
- `SessionMessagesTransportTest`、`SessionMessagesResponseReaderTest`、`FileContentResponseReaderTest`：无 body HTTP 失败、流式 decode、EOF 验证、取消/关闭竞态、session messages 64 MiB 边界和 `/file/content` 第二道防御。
- `OpenCodeFailureTest`、`ErrorUiTextTest`、`OpenCodeRepositoryFailureHandlingTest`：不解析异常 message 的语义分类、本地化资源与操作 fallback 映射、Repository 传播，以及取消/JVM `Error` 行为。
- `ProviderSettingsParsingTest` 与 `ProviderCapabilityRefreshTest`：对可能含密钥的 Provider/auth payload 做安全投影、以 `connected` 为权威、保留 auth wire index 与条件、安全解析 OAuth URL，并通过当前 project/global SSE authority lease 刷新 capability。
- `ProviderSettingsViewModelTest`：项目作用域动态 auth 输入、明文 HTTP 冻结确认、mutation single-flight、OAuth code/callback scope、无 secret 状态摘要和 mutation 后 reload/calibration。
- `CustomProviderValidationTest`、`OpenCodeProviderRepositoryCustomTest` 与 `CustomProviderFormViewModelTest`：Provider/model/Header 校验、disabled/auth/enable 分阶段顺序、安全 config 投影、partial/unknown outcome、先停用后清理 auth、deep-merge 防伪删除、secret 清除和持久化标识不可变。
- `SensitiveValueToStringTest`：跨 network、domain、Store、feature 和 UI 对象验证结构化摘要不暴露人工凭据、URL、alias、路径、prompt、Base64、SSE payload 与 tool output。
- `OpenCodeContrastTest`：浅色/深色主题的 4.5:1 文字和 3:1 图形对比度，覆盖主题、语义、语法、状态、附件和控件边界颜色。
- `FrpcStcpVisitorUrlResolverTest`：STCP 本地端口 URL 改写保留 scheme/path。
- `FrpcStcpVisitorManagerTest`：generation single-flight、native listener gate、application health 重试、配置失效、精确清理、调用者取消隔离、不同服务器互不阻塞、control epoch 恢复和旧 epoch 迟到保护。
- `FrpcStcpReadinessRetryClassifierTest` 与 `GoMobileFrpcStcpVisitorClientTest`：瞬时重试分类、永久 inbound/bridge 失败、typed unavailable/API mismatch、安全摘要、API v2 JSON、revision/epoch、`WaitVisitorReady`，以及反射取消/JVM `Error` 传播。
- Go wrapper/downstream tests：startup config 更新不丢失、真实 bind 后才 ready、bind failure、closed manager、control epoch、superseded revision、Stop 等待端口释放和敏感错误脱敏；并发相关包使用 `go test -race`。
- `PromptSendStateMachineTest`：空输入、附件、纯项目上下文输入和工作中 stop/send 状态。
- `OpenCodePromptSenderTest`：懒创建 session、Store 权威 capability revision、附件/项目上下文终检、乐观 `file://` parts、条件回滚、SSE 先确认、普通/已加载命令上下文透传、真实 session alias、abort 和 single-flight。
- `EventReducerTest`、`SessionListWindowCoordinatorTest` 与 Store/Repository 会话窗口测试：SSE 事件合并、有序前缀替换、共享可见目标、加载/重试/末尾状态、旧 generation/transport 拒绝、tombstone、workspace 隔离和有界会话/父链 metadata 补取。
- `NotificationAlertPolicyTest`、`NotificationChannelMigrationPolicyTest`、`OpenCodeNotificationAudioAttributesTest` 与 `SessionVisibilityRegistryTest`：单事件唯一声音所有者、v2 channel 迁移、系统显式静音/声音优先级、notification audio usage 和前台 destination 可见性。
- `BoundedSseReaderTest`、`OkHttpSseEventSourceFactoryTest`、`OpenCodeEventClientLifecycleTest`、`ProjectSnapshotCoordinatorTest`：identity response-body 解析、显式 `Accept-Encoding`、非 identity 零读取拒绝、MIME/status/无 body 协议处理、超限/取消、永久与可重试原因、owner/generation/transport 竞态、权威源切换、有界重放和快照失败/恢复。
- Repository 单元测试：错误解析、directory 参数、provider/model DTO 容错、服务器配置序列化不包含明文 SSH/STCP 凭据。
- `ServerRepositoryCredentialPersistenceTest`：第二个候选写入失败、配置提交失败、持久化后迟到异常、提交前/提交段取消、unknown outcome、delete 迟到异常、部分轮换、明确移除、SSH endpoint pin、TOFU、提交后旧 alias 删除失败和共享 alias 删除保护。
- `ServerBaseUrlTest`：URL 结构校验，以及 HTTPS、远端/LAN HTTP、localhost 子域、IPv4 `127/8`、IPv6 loopback、mapped loopback 与伪装域名的无 DNS 分类。
- `AddServerViewModelTest`：保存操作使用原子 single-flight；带有效 Basic 凭据的直连非本机回环 HTTP 等待一次提示确认，取消不写入，确认只保存一份冻结请求，覆盖编辑保留密码语义，且 SSH/STCP 绕过警告。
- 文件单元测试：项目文件相对路径的平台差异与越界拒绝、项目 URL 构造/包含关系、`/file/content` 必填字段和未知字段容忍、已声明/未知长度响应的有界读取、树深度/循环/重复 path、文本预览复杂度限制、独立上下文/评论 backing 历史分类和 reset 投影。
- 入站载荷测试：encoded/decoded identity 与 gzip body 覆盖已知/未知/低报长度和 `max + 1`；session messages 直接 OkHttp transport 的 4xx/5xx body 零读取、流式 decode、阻塞读取取消、callback race 和关闭路径；SSE 覆盖 identity 协商、非 identity 拒绝、LF/CRLF/CR、CRLF 跨 chunk、BOM、字段、EOF 三态、行/event 边界、巨大无换行源、200 MIME、204/HTTP 分类、取消和回调异常关闭。
- SSE 生命周期测试：关闭竞态、generation/lease、快照失败不阻断重连、每次恢复一次校准和前台恢复。
- SSH/外部输入测试：host key 认证前校验、Server URL 结构约束、data URL header/payload、URI/网络响应/Base64/native 返回值和私钥的流式上限。

现有 `app/src/androidTest` 只覆盖本地化独立窗口根，CI 仍没有 emulator/instrumentation job。在扩大设备自动化前，需要在紧凑手机屏幕、浅色/深色主题、IME 打开、200% 字体和 TalkBack 启用条件下人工验证以下交互：

- 项目选择页与 Drawer 排序：长按纵向拖拽、边缘自动滚动、两个入口同步、普通点击不重排、项目选择页删除保持独立、乐观失败回滚、当前项目合入后最多 20 项、Drawer 固定操作，以及水平抽屉手势协调。
- 会话列表打开详情。
- Composer 输入和发送按钮状态。
- 项目文件 picker 的树/搜索、最多 10 个选择、独立预览、确认/取消、预览返回文件树、route/session 切换、上下文 chip，以及纯上下文发送/reset。
- 模型/agent/variant picker。
- Provider 搜索与已加载/可连接状态、动态 API/OAuth prompt、浏览器/code/取消路径、loopback 与明文警告、断开，以及多模型/Header 的 Custom Provider 创建/编辑/停用流程。
- 权限 Dock、问题 sheet。
- 在代表性的 API 26、29、30+、33+ 设备验证 fresh install 与旧通知 channel 升级：默认单次播放、App 内“无”、系统显式声音、channel 显式静音/阻止/低 importance、通知权限拒绝以及当前会话前后台行为。
- selected/checked/expanded 语义、非颜色状态提示、服务器/项目上移/下移自定义操作，以及 Drawer 项目的 Tab/selected/完整名称路径/通知语义。

构建门禁：

- `python .github/scripts/audit-community.py` 校验治理文件、issue forms、CODEOWNERS、中英文文档配对、相对链接、Release Notes 章节与标签、废弃路径、合成 fixture 和仓库卫生。
- `python .github/scripts/audit-third-party.py` 校验版本、依赖、四个 Android Go 目标并集、资源哈希、frp modified/added provenance、法律文本和发布脚本引用。
- `./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug`
- `./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar -PrequireGoMobileBridge=true`
- `go run ./cmd/preparefrp`、`go test -race -modfile=build/frp-patched.mod ./...`，以及在 patched frp module 中执行 `go test -race ./client/...`。
- `build-aar.sh` 或 Windows 下的 `build-aar.ps1` 必须使用 `bridge-versions.properties` 的固定 Go/x-mobile/Android API/NDK 版本。
- 仅修改 Kotlin bridge API 或失败处理时，即使生成 AAR 字节和 `BRIDGE_VERSION` 不变，也必须运行该完整门禁；native 或生成 AAR 字节发生任何变化时必须递增 `BRIDGE_VERSION`。
- AAR 构建需验证同输入连续生成的 SHA-256 一致；Release APK 中每个 ABI 的 `libgojni.so` 必须与该 AAR 对应 entry 哈希一致，并重新检查 ELF machine、全部 `PT_LOAD` 16KB 对齐和 stripped 状态。
- APK 必须逐字节包含当前 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.txt`、`TRADEMARKS.md`、合并许可证全文和全部单独许可证；AAR 必须在 `META-INF/OCDECK/` 内嵌同源法律/API/provenance 元数据。
- 后续增加 ktlint/detekt 时再纳入 CI；Android lint 应在修复现有错误后作为独立门禁接入。
- `.github/workflows/ci.yml` 在 `main` push/PR 上执行无签名门禁；`.github/workflows/release.yml` 在稳定 tag 上重新执行全部门禁后构建签名制品。
- 发布前校验 tag/源码版本、tag commit 属于 `origin/main` 历史、`VERSION_CODE` 单调递增、三个 APK输出、固定签名证书指纹和 `SHA256SUMS`。发布说明固定包含独立性、自备 OpenCode Server 和 pre-1.0 风险声明。当前自动发布流程不构建 AAB。设备上的 native load、16KB page-size 和真实 STCP闭环仍需人工完成。

## 15. 当前实现状态

### 15.1 已具备或主体已具备

- Android 工程、手动 DI、DataStore/Keystore/内存 Store、带客户端说明空态的服务器列表、新增/健康检查和直连/SSH/STCP 三种互斥连接模式；不自动创建 localhost 服务器。
- 路径规范化、按服务器 `sortOrder` 持久化且新项目置顶并由项目选择页/Drawer 共享重排的最近项目、项目选择与壳层、支持网络加载更多的 Store 共享会话窗口、会话 drawer/详情、懒创建 session、普通 prompt、全局/项目 SSE、provider/model/agent 基础数据；session messages 具备独立 64 MiB encoded 与 decoded 上限。
- 所有普通 `OpenCodeApi` Retrofit 方法都具备显式且独立的 16 MiB encoded/decoded 边界或 empty-success 策略；非 2xx 与 Unit body 不读取即丢弃，`/file/content` 保留 reader 层 decoded 边界。
- Slash command 与 `@` mention UI、agent/model/variant picker、手机本地附件、项目文件树/搜索/只读预览及完整文件 Composer 上下文、permission/question、会话内 Changes/diff 和 context usage。
- Repository/SSE/快照类型化失败映射为本地化 UI 资源，不解析异常 message；敏感与大型 value object 使用经过测试的结构化摘要。
- 移动控件已覆盖 48dp 目标、选择 role、展开/折叠说明、非颜色状态提示、TalkBack 服务器/项目排序、对比度测试过的浅色/深色 palette，以及面向小屏、200% 字体和 IME 遮挡的受约束滚动。
- Session rename/archive/delete/revert/unrevert、项目名称编辑、语言/配色/通知/音效/后台设置、三个默认静音 v2 notification channel 与单一声音所有者仲裁、前台 route 可见性、本机模型隐藏偏好和 MCP/LSP/plugin 状态展示。
- Provider 管理已具备安全 catalog/config 投影、搜索、动态 API/OAuth 认证、断开、有界且可取消的 OAuth callback、活动项目 capability 刷新，以及多模型/Header 的分阶段 Custom Provider 持久化。
- Store 权威 prompt capability 校验、附件/项目上下文 sender 终检、条件乐观回滚、send/abort/revert 共享 operation gate、纯附件/纯上下文 reset 与历史可恢复内容完整性失败提示。
- Server URL 无 userinfo/query/fragment 约束、旧值安全隐藏、直连明文 HTTP 凭据确认、自由文本 URL/认证 scheme 脱敏，以及 SSH 私钥 256 KiB 有界读取与双重校验。

### 15.2 部分完成或仍需加固

- identity-only SSE 自定义流式 reader、32 MiB 行/event 上限、owner lease、关闭终态、project/global 去重与 fallback、generation/source/单调 transport identity、revision 快照防覆盖、消息并发合并、dirty follow-up 校准和应用级前台恢复已实现；仍需真实服务长时间断网、系统前后台、通知 channel 升级和 STCP control epoch 切换的设备验证。
- 独立 Review route 仍为占位；当前可用 diff 位于会话详情 Changes tab。
- Provider 管理仍需在支持的真实 Server/provider 版本上做兼容验证，重点包括远程 loopback OAuth 拓扑、真机长 callback 取消，以及 custom-config partial/unknown outcome；global-config deep merge 不提供字段或配置的物理删除。
- 模型 enabled/hidden 仅是按 server 保存的本机过滤偏好，不代表修改 OpenCode server config。
- Settings 已包含通用、后台、服务器、Provider 和模型子页，但完整快捷键与其他 Web 设置仍未完成。

### 15.3 尚未完成

- Shell mode、session share、workspace/worktree、PTY/terminal。
- Room、Hilt 和业务多模块迁移；仅在离线缓存、本地搜索、快速恢复或依赖图复杂度明确需要并经确认后评估。

## 16. 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| OpenCode API 字段变化 | DTO 解析失败或状态丢失 | kotlinx.serialization 忽略未知字段，Repository 做兼容解析 |
| SSE 断线或事件乱序 | UI 状态不一致 | 前台恢复和重连后刷新 REST 快照，Store reducer 保持幂等 |
| STCP 配置提交早于 listener ready | 首次健康检查或项目快照误报失败 | patched frp 暴露真实 revision/epoch listener 状态，Repository 在统一连接边界执行 single-flight `/global/health` |
| frps 重连或旧异步任务迟到 | 复用失效 listener、旧任务误关新隧道 | per-generation identity/CAS 清理；control epoch 变化后共享恢复，旧 epoch 结果不得发布 |
| GoMobile 制品不可复现或 API 漂移 | Release 难以审计、回滚或运行时反射失败 | 固定 Go/x-mobile/frp/patch，使用不可变 Maven 坐标、规范化 ZIP、SHA/API/provenance 和 Release 强制门禁 |
| 路径格式混乱 | 项目重复、请求失败 | 强制 `PathNormalizer`，所有 Repository 入参统一规范化 |
| 敏感信息泄露 | 安全事故 | Redactor、日志拦截器、Provider UI 禁止明文回显 |
| Provider 管理 API/版本或 OAuth 拓扑不匹配 | 认证失败或留下结果不确定的 server-global 配置 | 容错安全投影、保留原 method index 与 scope、有界可取消 callback、loopback 警告、disabled 分阶段写入、类型化 partial/unknown outcome，以及真实版本/设备验证 |
| 把 encoded/decoded 响应上限误当成 heap 保证 | Converter 分配或并发快照产生高内存峰值 | 保持独立 16/64 MiB encoded/decoded 边界和 32 MiB identity-SSE 边界、保留 `/file/content` 纵深防御、逐端点审计并在真机验证大输入 |
| 过早引入 Room/Hilt | 构建复杂、迁移成本高 | 手动 DI + DataStore + memory store，按触发条件并经确认后再引入 |
| 桌面交互或不可访问的状态样式照搬到手机 | 小屏或辅助技术不可用 | 全屏页面、受约束滚动、48dp 目标、语义 role/状态说明、非颜色提示、对比度门禁、TalkBack 操作和 IME inset 适配 |
| 本地 JDK/SDK 不一致 | 构建失败 | 明确 JDK 21、compileSdk 36、buildTools 36.0.0，统一 Android Studio 和命令行 JDK |

## 17. 下一阶段落地清单

建议按以下顺序推进当前缺口：

1. 在真实服务与设备上验证 SSE 长时间断网、系统前后台、全局/项目重复事件和 STCP control epoch 切换，并根据结果继续加固。
2. 在支持的真实 Server/provider 版本与设备上验证 Provider 管理，重点覆盖远程 loopback OAuth、长 callback 取消，以及 custom-config 分阶段提交出现 partial/unknown outcome 后的恢复。
3. 在真实紧凑设备上验证项目文件 picker 与纯上下文发送/reset，包括 route 切换、IME、200% 字体、TalkBack 和两种主题；设备测试门禁建立后补充 instrumentation 覆盖。
4. 继续逐端点审计 session messages 之外的潜在大 REST 响应，以及 native 返回值、图片解码和真实设备大输入失败路径，保持所有外部输入先有界后解析。
5. 决定独立 Review route 的产品价值；在此之前以会话详情 Changes tab 为唯一已完成功能。
6. 再评估 Shell、share、workspace/worktree 和 PTY/terminal；Room、Hilt、业务多模块仅在触发条件成立且经确认后启动。

历史 P0/P1/P2 仅用于理解功能演进，不再作为当前完成度或工程结构的权威描述。
