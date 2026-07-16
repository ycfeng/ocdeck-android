# OC Deck：OpenCode Web UI 原生 Android 客户端交互设计

[English](mobile-interaction.md)

本文档与英文版本并行维护；如文档之间存在冲突，以当前实现、自动化测试和[仓库代理规则](../../AGENTS.zh-CN.md)为准。

## 1. 文档范围

本文基于 Playwright 在 `http://127.0.0.1:4096/` 的实际探索结果整理，视口设置为 `390 x 844`，用于近似 iPhone 12 Pro 纵屏移动端。文中的观察数据已改写为通用 fixture；测试项目使用 `X:/workspace/sample-project`，POSIX 示例使用 `/workspace/sample-project`。

目标是为 OC Deck 复刻 OpenCode Web UI 核心工作流提供可落地的页面、交互、状态、接口和移动端适配设计。OC Deck 是独立社区客户端，不是 OpenCode 项目或 Anomaly 官方产品；应用 `0.1.1` 使用 `io.github.ycfeng.ocdeck`，最低支持 Android API 26，并要求用户自行提供可访问的 OpenCode Server。本文不包含任何真实 provider API key、token、密码或本机凭据；所有配置接口中的敏感字段都必须在日志、文档、崩溃上报和调试 UI 中脱敏为 `<redacted>`。

探索过程中没有执行发送 prompt、归档、删除、关闭项目、断开 provider、保存项目编辑、添加服务器等有副作用操作。发送和管理类接口通过前端静态 JS 客户端与网络记录推断。

## 2. 观察环境

| 项目 | 值 |
| --- | --- |
| Web 地址 | `http://127.0.0.1:4096/` |
| OpenCode Web 标题 | `OpenCode` |
| 健康检查版本 | `/global/health` 返回 fixture 版本 `<fixture-server-version>` |
| Desktop 设置页版本展示 | `<fixture-desktop-version>` |
| 测试项目路径 | `X:/workspace/sample-project` |
| 测试项目 VCS | `git` |
| 测试项目分支 | `main`，UI 显示 `主分支（main）` |
| 测试项目 id | `project_fixture_001` |
| 移动视口 | `390 x 844` |

## 3. 信息架构总览

原生 Android 建议不要机械复刻 Web 的桌面弹窗布局，而是按移动设备重新组织为以下主模块。

| 模块 | Web 表现 | Android 建议 |
| --- | --- | --- |
| 服务器/项目选择 | Logo、服务器、最近项目、打开项目按钮 | 服务器列表选择后直接进入项目选择页，最近项目列表 + 打开项目入口 |
| 项目会话 Shell | 顶部 40px header + 主内容 + 底部 composer + 左侧抽屉 | 单 Activity 多 Screen，TopAppBar + ModalNavigationDrawer + 固定 Bottom Composer |
| 会话列表 | 移动抽屉内显示项目 rail、会话列表、加载更多 | ModalNavigationDrawer 或独立“项目与会话”页，列表项右滑/更多菜单归档 |
| 会话详情 | Tab：会话/更改，消息流，底部输入框 | SessionDetailScreen，TabRow + LazyColumn + BottomComposer |
| 更改审查 | Tab 中空态或 diff；底部审查区域在移动端几乎不可见 | 当前由会话详情 Changes tab 承载；独立 Review route 仍为占位，不放在屏幕外 |
| 状态/服务器 | 右上 popover + 服务器管理弹窗 | 短状态使用受约束 anchored popup，服务器管理使用全屏页面 |
| 设置 | 600px 高桌面双栏 dialog，移动端极窄 | SettingsScreen 单列分组，不使用桌面双栏 |
| Provider/模型 | 设置内 provider 卡片和模型开关，composer 内模型选择器 | Provider/模型设置使用全屏页；模型选择按内容使用受约束 popup 或 bottom sheet |
| Composer | 文本、附件、agent、model、variant、slash、mention | 自定义 Compose 输入组件，支持 chip、附件、命令面板、状态恢复 |
| 帮助 | 打开 Discord 外链新 tab | 使用 Android Custom Tabs 或浏览器 Intent |

## 4. 路由与路径规范化

Web 路由将项目路径编码进 URL，例如：

`/<base64(normalized-directory)>/session`

会话详情路由：

`/<base64(normalized-directory)>/session/ses_fixture_001`

重要发现：Web 同时出现了 `X:\workspace\sample-project` 和 `X:/workspace/sample-project` 两种形式，导致 rail 中出现两个同名 `sample-project` 项目图标，且 route base64 不同。

Android 必须做路径规范化：

| 输入 | 规范化建议 |
| --- | --- |
| `X:\workspace\sample-project` | 转为 `X:/workspace/sample-project` |
| `X:/workspace/sample-project/` | 去除末尾 `/` |
| 大小写不敏感文件系统 | Windows 盘符和路径比较时建议大小写归一 |
| 最近项目去重 | 以规范化 worktree 路径作为唯一 key |

原生端不必复刻 Web route 编码，但网络请求必须带 `directory` query。推荐全局维护 `ProjectRef(normalizedDirectory, projectId, displayName, vcs, icon)`。

## 5. 项目选择与最近项目

### 5.1 页面结构

Web 欢迎页移动视口顶部是 40px header，左侧是“切换菜单”按钮。主区域包含 OpenCode 标志、服务器按钮 `127.0.0.1:4096`、最近项目列表和“打开项目”按钮。当前原生实现不保留独立欢迎路由；服务器列表数据加载完成且为空时，该页显示 OpenCode 标志和客户端说明，明确 App 不会创建或运行 OpenCode Server，并引导用户先启动可访问的服务再添加连接。选择已有服务器后直接进入 `ProjectPickerScreen(serverId)`；新增服务器成功后返回 `ServerListScreen`，由用户明确选择是否打开该服务器。

最近项目探索到以下项：

| 最近项目 |
| --- |
| `/` |
| `X:\workspace\sample-project` |
| `X:\workspace\sample-library` |
| `/workspace/sample-service` |
| `/workspace/sample-cli` |

点击最近项目 `X:\workspace\sample-project` 后进入项目会话页。

### 5.2 打开项目弹窗

Web 弹窗字段：

| 元素 | 行为 |
| --- | --- |
| 标题 `打开项目` | 右上关闭 |
| 搜索框 `搜索文件夹` | 输入路径或关键字筛选 |
| 最近项目区 | 展示最近路径，目标项目拆为 `X:/workspace/` + `sample-project` + `/` |
| `清除筛选` | 输入后出现，清空搜索 |
| 打开项目目录列表 | 展示路径下文件夹，例如 `doc/`、`.git/`、`src/`、`tests/`、`build/` |

输入 `X:/workspace/sample-project` 时，弹窗展示匹配的最近项目和该目录下的目录列表。点击最近项目会关闭弹窗并路由到目标项目最近会话。

Android 建议：使用全屏 ProjectPickerScreen 或 ModalBottomSheet。文件夹浏览不应依赖系统 SAF，因为 OpenCode server 读取的是服务端文件系统；应调用后端 `/find/file` 和 `/file` 接口展示服务端目录。

当前原生实现：`ProjectPickerScreen` 顶部保留标题 `打开项目`，左上角 `服务器` 按钮进入服务器管理页，右上角 `设置` 按钮进入设置页；顶部不展示返回按钮或打开按钮，打开动作保留在页面主体，页面底部不再提供额外“返回”按钮。路径候选目录项点击会将规范化路径补全到输入框、自动追加 `/` 并继续查询该目录下的候选路径，光标自动移到补全路径末尾，但不直接进入项目；用户需点击主体“打开项目”按钮进入。最近项目卡片正文点击打开项目，右侧删除图标只移除本机最近项目记录；删除前使用 OpenCode 风格二次确认弹窗，文案需明确不会删除服务端项目文件。

## 6. 项目主页

进入目标项目但未选择具体会话时，主区展示：

| 区域 | 文案/状态 |
| --- | --- |
| 标题 | `构建任何东西` |
| 路径 | `X:/workspace/sample-project` |
| 分支 | `主分支（main）` |
| 时间 | `最后修改 <fixture-relative-time>` 等相对时间 |
| 底部 composer | 固定在底部，空输入时发送按钮 disabled |

Android 当前的项目首页 Composer 预览中，Agent、模型和 Variant 控件在当前页面原地打开 picker，绝不导航到新会话。Agent 只展示已加载且 Composer 支持的 Build、Plan；模型和 Variant 复用会话 Composer 的受约束 picker，包括搜索、provider 分组、当前选择、默认 Variant，以及真实的连接 Provider/管理模型路由。点击输入区或附件操作才进入 `"new"` 会话页，并携带当前轻量级 agent/model/variant 选择；会话 ViewModel 会基于已加载的 capability snapshot 重新校验，陈旧或不支持的选择按正常模型选择规则回退。

点击抽屉中的“新建会话”只把 URL 切换为项目 `/session`，没有立即创建后端 session id，也没有 POST 请求。首次发送消息时才调用 `POST /session` 创建 session。

Android 当前实现：新会话复用 `SessionDetailScreen`，内部使用 `"new"` 表示无后端 session 状态，不额外创建独立 `NewSessionScreen`。只有首次发送时创建 session，并在成功后替换为真实 session id。

## 7. 顶部栏与导航抽屉

### 7.1 顶部栏

移动端顶部栏高度约 40px。

| 元素 | 行为 |
| --- | --- |
| `切换菜单` | 打开/关闭项目与会话抽屉 |
| `状态` | 打开服务器状态 popover |
| `切换审查` | 切换审查/更改区域状态，移动端可见效果有限 |

### 7.2 项目与会话抽屉

点击 `切换菜单` 后，`navigation "项目和会话"` 从 `x=-390` 滑入 `x=0`，宽约 390，高约 804。

抽屉结构：

| 区域 | 内容 |
| --- | --- |
| 左侧 rail | 同服务器最近项目按钮、打开项目、底部设置；Android 端不展示 Web 帮助问号按钮 |
| 项目标题区 | `sample-project`、路径、项目更多选项 |
| 操作区 | `新建会话` |
| 会话列表 | 每行会话 link + 右侧 `归档` 按钮 |
| 分页 | `加载更多` |

Android rail 订阅当前服务器持久化的最近项目，其中最多包含 20 条 MRU 记录；当前活动项目缺失时会合并补入。所有目录都使用平台感知的路径比较 key 进行规范化和去重。项目导航绝不等待这项辅助 DataStore 写入：导航成功后由应用级有序 recorder best-effort 更新 MRU，本地持久化失败不能阻止进入本来可访问的项目。进入项目、返回已有项目首页和直接打开会话都会刷新同一 MRU 顺序。活动项目的实时 metadata 会替换匹配的旧最近记录，不会产生重复项。

项目按钮放在有界 `LazyColumn` 中，可见高度最多为抽屉安全内容高度的 75%；超出后纵向滚动，“打开项目”和“设置”固定保持可达。每个项目都有 48dp 触控目标，显示第一个 Unicode 字符簇，提供包含完整项目名和路径的本地化标签，并在同一 `selectableGroup` 中使用 `Role.Tab`，且仅有一个选中项。只有活动项目显示当前可获得的通知状态；对尚未加载的非活动项目，不伪造未读、权限或工作中标记。

选择 rail 项目时先关闭抽屉。若当前已位于该项目首页，不增加重复目的地；若位于该项目会话详情，则返回项目首页。选择其他项目时优先弹回栈内已有的匹配项目首页，否则打开新的 `ProjectShellRoute`。Rail 进入项目首页，不恢复 Web 客户端的最近会话，因为最近项目持久化记录不包含 session ID。

项目主页和抽屉按规范化 server/directory/workspace key 共享 Store 中的会话窗口。初始展示 20 条根会话，并请求 `/session?roots=true&limit=70`，额外保留 50 条原始结果余量以容纳 archived 过滤。点击 `加载更多` 后共享可见目标增加 20；本地已加载足够根会话时立即展开，否则使用 `limit = target + 50` 重新获取有序前缀。原始响应少于请求 limit 时进入末尾状态，失败则保留现有列表并提供内联重试。快照校准保持当前请求窗口，不回落固定上限。路由指向窗口外会话时通过 `GET /session/{sessionID}` 补取，并带循环检测地最多追溯 16 层父链。

会话列表当前可见项：

| 标题 |
| --- |
| `示例会话 1` |
| `示例会话 2` |
| `示例会话 3` |
| `示例会话 4` |
| `示例会话 5` |

移动端问题：抽屉关闭后 DOM 仍在 `x=-390`，但偶发 pointer event 拦截底部参数条点击，需要等待关闭动画完成或调整层级。Android 不应复刻 offscreen DOM 拦截方式，应使用标准 `ModalNavigationDrawer`，关闭后不参与触摸命中。

### 7.3 项目文件面板

桌面视口补充探索使用 `1440 x 1000`，测试项目为 `/workspace/sample-project`。Web 的 `审查和文件` 区域在宽度至少 768px 时挂载于右侧；`所有文件` 树默认约 200px，可拖拽到 200–480px，目录按需调用 `/file` 加载直接子项，点击文件后在左侧增加文件 tab 并调用 `/file/content`。该双栏在 800px 宽度下已明显挤压文件内容，767px 时完全不挂载，因此不适合直接搬到手机。

当前 Android 实现：

- `ProjectShellScreen` 和 `SessionDetailScreen` 顶部栏均提供项目文件入口，状态由项目壳层统一持有，项目内页面切换不会创建两套文件树。
- 手机使用从右侧覆盖的项目文件面板，不压缩会话和底部 composer；面板宽度占满小屏，大屏最大 420dp。点击面板外遮罩、系统返回或关闭按钮均可关闭；预览页按返回先回到文件树，再关闭面板。关闭后取消进行中的请求并释放当前文本/Base64 内容，同时保留项目级已成功目录缓存。
- 面板显式区分浏览和选择模式。树页和内容页在同一面板内单页切换，不复刻桌面多文件 tab。目录按需展开并缓存，目录优先排序；搜索与树数据分离，非空搜索调用 `/find/file?type=file`。
- Composer 的“从项目选择”会以当前草稿文件作为预选项打开选择模式。文件主行切换 Checkbox 选中状态，独立 48dp 操作打开预览，固定底栏确认最多 10 个完整文件；取消或返回不修改草稿。临时选择请求绑定发起它的 server、规范化 directory、workspace、session route 和 request，路由变化时关闭并丢弃迟到交付，而浏览缓存仍按项目复用。
- 文本文件使用 Prism4j 语法高亮、行号、等宽字体和纵向虚拟列表。网络响应在解析前限制为 16MiB；文本预览另限制为 500,000 字符、20,000 行和单行 20,000 字符，超过后显示明确状态，避免超大文件阻塞或耗尽内存。
- `image/*` 二进制内容可从 Base64 解码预览，解码前检查尺寸并采样到最多约 4MP；未知二进制、过大图片和解码失败均显示独立状态。首版不支持编辑、保存、音频、PDF、行评论或将行/范围选区加入 prompt。
- 文件相对路径独立于项目根路径规范化：拒绝 NUL、绝对路径和任何 `..`；Windows 项目将反斜杠视为分隔符，POSIX 项目保留文件名中的合法反斜杠。Repository 还会验证 `/file` 节点是请求目录的直接子项。Prompt 的 `file://` URL 只能由规范化项目根和已校验相对路径构造，每个 UTF-8 路径段分别百分号编码，绝不信任或转发 server DTO 中的 absolute path。

## 8. 项目更多菜单与编辑项目

项目更多菜单项：

| 菜单项 | 观察 |
| --- | --- |
| `编辑` | 打开编辑项目弹窗 |
| `启用工作区` | 未执行，预计与 worktree/workspace 相关 |
| `清除通知` | 无未读时 disabled；有未读时 enabled，点击后将该项目通知标记为已读 |
| `关闭` | Android 端二次确认后退出当前项目视图，不删除服务端项目或最近项目记录 |

编辑项目弹窗字段：

| 字段 | 说明 |
| --- | --- |
| 名称 | 文本框，值/placeholder 为 `sample-project` |
| 图标 | 点击或拖拽图片，建议 128x128px |
| 颜色 | `pink`、`mint`、`orange`、`purple`、`cyan`、`lime`，当前 `lime` pressed |
| 工作区启动脚本 | 多行文本框，placeholder `例如 bun install`，说明“在创建新的工作区 (worktree) 后运行。” |
| 操作 | `取消`、`保存` |

保存接口：`PATCH /project/{projectID}`，body 包含 `name`、`icon`、`commands`，query 包含 `directory`、`workspace`。

当前 Android 实现：抽屉项目更多菜单复刻 Web 菜单尺寸和视觉，`编辑` 仅提供项目名称弹窗并只发送 `{ name }`，`清除通知` 在有未读通知时可用并调用内存通知 Store 标记项目已读，项目信息通过 `GET /project/current` 写入内存 Store；不展示 `启用工作区`；完整图标、颜色、启动脚本编辑留给后续 `ProjectEditRoute`。

Android 建议：用 `EditProjectScreen` 或 `ModalBottomSheet`，颜色做横向 chip，图标上传使用 Android Photo Picker 或文件选择后转发给后端能力。保存前展示 dirty state，返回时提示未保存。

## 9. 会话详情页

### 9.1 顶部 tab 与标题

会话详情 URL：

`/.../session/ses_fixture_001`

页面结构：

| 区域 | 内容 |
| --- | --- |
| TabList | `会话` selected、`更改` |
| 标题 | `示例会话` |
| 标题操作 | `查看上下文用量`、`更多选项` |
| 消息区域 | 可滚动内容 |
| Composer | 固定底部 |

### 9.2 消息流

示例消息：

| 角色 | 展示 |
| --- | --- |
| 用户 | 右侧序号气泡 `1`，底部 meta `Build · model-standard · <fixture-time>`，按钮 `重置到此点`、`复制消息` |
| 助手 | 文本 `这是 fixture 助手回复。`，按钮 `复制回复`，meta `Build · model-standard · <fixture-duration>` |

助手消息 meta 中的耗时需与 Web 口径一致：按同一 user turn 计算，从 user message 的 `time.created` 到该 user message parent 下所有 assistant message 的最大 `time.completed`；秒数格式化按四舍五入。无法关联 user turn 时，才回退为单条 assistant message 的 `completed - created`。

Android 建议：消息列表使用 `LazyColumn(reverseLayout = false)`，消息 action 在长按、更多菜单或正文下方操作区中展示，复制使用 Android Clipboard。会话正文按“逐条消息”启用文字选择：用户消息正文用 Compose 文本选择，助手 Markdown 段落用可选择 `TextView`，代码块用 Compose 选择容器；角色、模型、状态和时间不进入选择范围，避免复制噪声。异常提示文本使用独立选择容器，且当消息正文为空时“复制回复”复制异常提示。保留“复制消息/复制回复”整条复制作为兜底。用户消息的 `重置到此点` 对应 `POST /session/{sessionID}/revert`。agent/助手回复正文使用 Markdown 渲染，至少覆盖段落、列表、链接、行内代码和代码块；行内代码按 Web 端使用透明背景的青色等宽高亮文字，不做灰底 chip；fenced code block 使用接近 Web 的浅色背景、1dp 边框、等宽字体、横向滚动和语法高亮；用户消息仍按普通文本展示，避免输入内容被误解释。

会话历史 `GET /session/{sessionID}/message` 不经过 Retrofit converter 或 Retrofit 的错误体交付路径，而由 `ServerConnection` 中与普通 REST 共享同一个认证、超时、重定向、脱敏和 encoded-body 限制配置的 OkHttp client 直接构造请求。非 2xx 响应不读取 body，立即关闭并抛出只包含状态码的类型化 HTTP 异常。Network interceptor 对 HTTP transfer framing 之后、`Content-Encoding` 解码之前的 encoded response-body octets 执行 64 MiB 上限；2xx 响应再在 OkHttp callback 线程使用独立的 64 MiB decoded-entity 计数/限流 `InputStream`，通过应用统一的宽容 `Json.decodeFromStream` 直接解码 DTO，并在解码结束后继续经同一 limiter 消费到 EOF。未知长度、低报长度、尾随数据和无限流都在对应上限的第 `max + 1` 字节失败；协程取消会立即取消 Call 并关闭正在阻塞读取的 body，取消导致的 I/O 异常不能替换 `CancellationException`。这些上限不等于 DTO、字符串、Base64 和 UI 映射的实际 heap 峰值，也不代表其他 REST 大响应已完成审计。

#### 9.2.1 重置与恢复

Android 当前按 Web 的 legacy revert 语义实现：

- `重置到此点` 的真实语义是回到目标用户消息发送之前，目标消息本身及其后的 assistant/tool 消息立即从主时间线隐藏；操作不弹确认框。
- 服务端通过 `session.revert.messageID/partID` 保存可逆边界。REST 返回和 `session.updated` SSE 都更新同一 Session marker；回滚期间内存 Store 继续保留完整历史，不提前删除消息。
- 目标用户消息的普通文本、`data:` 手机本地附件和独立项目文件上下文会回填到 Composer，纯附件或纯项目上下文消息也可重置。项目 `file://` 引用只有在解析后位于当前规范化项目根内时才恢复；评论配对 backing file 被排除，任何 `file://` 引用都不会伪装成本机附件。恢复前重新检查本地附件 data URL 和字节预算，以及项目上下文 10 个上限与项目路径包含关系；任一可恢复内容损坏或超限时拒绝改变服务端 revert marker，并显示明确错误。Session marker 可跨刷新恢复，但 Composer 草稿是客户端状态，不能依赖 marker 自动重建。
- Session 正在工作时，在同一个 session mutation lease 内先调用 `POST /session/{sessionID}/abort`，再调用 revert，避免 abort 与 send/revert 之间重新插入竞态；被恢复的原 turn 仍保持已中止状态，不自动续跑。
- Composer 上方显示默认折叠的恢复 Dock，包含回滚数量、首条预览、逐条“恢复消息”和“全部恢复”。点击某项表示恢复到并包含该项：如果后面仍有用户消息，则把 revert 边界移动到下一条；最后一项或“全部恢复”调用 unrevert。
- 在回滚状态发送新的 prompt/command/shell 会提交新分支。提交期间新乐观消息必须保持可见；服务端随后清除 marker，并通过 `message.removed`、`message.part.removed` 永久移除旧分支，Dock 随之消失。
- 移动端不复制桌面 hover-only 的 24px 操作区。重置、复制、展开和恢复操作都使用至少 48dp 触控目标，并同时适配浅色、深色主题 token。

#### 9.2.2 引用评论回显

Web 的代码行评论不是独立评论资源，而是保存在用户消息的两个相邻 part 中：一个 `synthetic=true` 的 `text` part 通过顶层 `metadata.opencodeComment` 保存路径、行列选区、评论正文、preview 和 origin；另一个 `file://...?start=&end=` file part 提供模型读取的文件范围。历史消息回显优先读取结构化 metadata，metadata 缺失或损坏时再解析固定格式的 synthetic 文本。

Android 当前实现按以下规则回显已有评论：

- REST DTO 和项目 SSE 都保留并映射顶层 `metadata.opencodeComment`，不能与 tool state 内部的 `state.metadata` 混用。
- 用户消息右侧以独立评论卡片纵向堆叠，显示文件 basename、`:N` 或 `:start-end` 和完整评论正文；评论正文支持选择和整条消息复制。
- 仅含评论、没有普通正文的用户消息仍然可见，且不生成空白正文气泡。
- 多条评论保持 message parts 的顺序。移动端不复刻 Web 的横向评论条，避免小屏横向滚动。
- 只有 `data:` file part 作为手机本地附件卡片展示；独立项目 `file://` part 使用安全文件名显示为项目文件上下文行，评论配对的 `file://` part 继续隐藏，避免重复回显。
- 历史和实时用户消息中的 `data:image/*;base64,...` 附件显示约 64dp 缩略图，点击缩略图后使用近全宽弹窗适屏查看大图；非图片附件继续显示文件图标、文件名和 MIME。
- 附件图片在后台线程解码，解码前限制 Base64 长度、原图尺寸和像素数，并按缩略图或大图目标采样；加载中或解码失败时安全回退为文件图标或独立状态。首版不加载 HTTP 图片，不支持动图播放、缩放、下载或分享。
- 当前范围只覆盖 Web 已发送评论的历史和实时回显；Android 端代码行选择、评论编辑删除和评论 context 发送仍作为后续独立能力实现。

### 9.3 上下文用量

Playwright 观察到 Web 端在不同视口下行为不同：

| 视口 | 行为 |
| --- | --- |
| 移动视口 | 点击 `查看上下文用量` 显示 132px 宽 tooltip，顺序为 `成本`、`使用率`、`Token` |
| 桌面视口 | 点击同一按钮打开右侧 `审查和文件` 侧栏，并选中 `上下文` tab |

移动 tooltip 示例：

| 指标 | 示例 |
| --- | --- |
| 成本 | `<fixture-cost>` |
| 使用率 | `<fixture-percent>` |
| Token | `<fixture-token-count>` |

桌面上下文 tab 展示会话、消息数、提供商、模型、上下文限制、总 token、使用率、输入/输出/推理/cache token、用户/助手消息数、总成本、创建时间、最后活动、上下文拆分和原始消息列表。

Android 建议：标题区使用 Web 同款 24dp progress-circle 图标按钮；点击后使用 anchored popup 承载移动摘要和桌面详情面板的核心字段，避免直接复刻桌面右侧栏，也避免被系统键盘和底部 composer 遮挡。

### 9.4 会话更多菜单

菜单项：

| 菜单项 | 后端行为 |
| --- | --- |
| `重命名` | `PATCH /session/{sessionID}`，body `title` |
| `分享` | `POST /session/{sessionID}/share`；已有链接时复制 |
| `归档` | `PATCH /session/{sessionID}`，body 可能更新 `time.archived` |
| `删除` | `DELETE /session/{sessionID}`，必须二次确认 |

探索未执行归档或删除的最终确认。Android 必须对破坏性操作做确认 dialog，并在列表和详情同时处理乐观更新。

### 9.5 二次确认 dialog

二次确认 dialog 是移动端“不机械复刻桌面弹窗”原则的例外：删除、归档、关闭、断开连接等需要用户再次确认的操作，全部按 OpenCode Web 端确认弹窗视觉复刻，而不是使用系统默认 `AlertDialog` 样式。

以删除会话为例，Web 端确认弹窗在移动视口下保持居中卡片：

| 元素 | Web 样式 | Android 复刻要求 |
| --- | --- | --- |
| 遮罩 | 灰色半透明遮罩，背景整体压暗 | Dialog 使用 dim 遮罩，`dimAmount` 约 0.3 |
| 卡片 | 白底、10px 圆角、浅灰 1px 边框感和柔和阴影 | `Panel` 白底、10dp 圆角、`Border` 1dp、轻阴影 |
| 宽度 | 桌面最大约 640px；移动端左右约 8px 边距 | `maxWidth = 640dp`，小屏左右 8dp |
| 标题行 | 左侧 16px medium 标题，右侧 24px 关闭按钮 | 标题 16sp medium；关闭图标视觉 24dp/16dp |
| 正文 | 13px 文本，例如 `删除会话 "示例会话"？` | 正文 13sp，文案包含操作对象名称 |
| 按钮 | 右下角，32px 高，`取消` 透明，确认按钮黑底白字 | 右对齐、32dp 高、8dp 间距，主按钮使用 `BorderStrong` 黑底 |

删除会话文案使用：标题 `删除会话`，正文 `删除会话 "<会话标题>"？`，确认按钮 `删除会话`。破坏性确认按钮不使用红色文本，按 Web 端使用黑底主按钮；红色仍可用于菜单项或图标提示危险性。

## 10. 更改审查页

点击 `更改` tab 后显示：

| 元素 | 观察 |
| --- | --- |
| 下拉 | `Git changes` selected，选项含 `Git changes` 和 `上一轮变更` |
| 空态 | `No uncommitted changes yet` |
| 网络 | `GET /vcs/diff?mode=git&directory=...` 和 `GET /session/{sessionID}/diff?directory=...` |

当前项目没有未提交变更，因此未观察到具体 diff 展开。

Android 当前实现：可用 diff 位于 `SessionDetailScreen` 的 Changes tab，支持 Git changes 与会话 diff 数据；独立 `ReviewRoute` 仍为占位。若后续独立页面能明显改善跨会话审查，再迁移为 `LazyColumn` 文件/hunk 页面；无论采用 tab、全屏页还是 bottom sheet，都不得把审查区域压到屏幕 y=843 之外。

## 11. 服务器状态与服务器管理

### 11.1 状态 popover

点击顶部 `状态` 打开“服务器配置”浮层。移动端宽约 350，靠右上。

Tabs：

| Tab | 内容 |
| --- | --- |
| `1 服务器` | `127.0.0.1:4096`、`<fixture-server-version>`、`管理服务器` |
| `MCP` | `未配置 MCPs` |
| `LSP` | `已从文件类型自动检测到 LSPs` |
| `1 插件` | `@example/opencode-plugin@1.0.0` |

### 11.2 服务器管理弹窗

点击 `管理服务器` 打开“服务器”弹窗：

| 元素 | 内容 |
| --- | --- |
| 标题 | `服务器` |
| 搜索框 | `搜索服务器` |
| 服务器 row | `127.0.0.1:4096 <fixture-server-version> 无用户名` |
| 底部按钮 | `添加服务器` |

点击 `添加服务器` 进入子页：

| 字段 | placeholder/默认 |
| --- | --- |
| 服务器 URL | `http://127.0.0.1:4096` |
| 服务器名称（可选） | `Localhost` |
| 用户名（可选） | `opencode` |
| 密码（可选） | `密码` |
| 提交 | `添加服务器` |

Android 建议：服务器短状态使用受屏幕约束的 anchored popup，服务器管理使用 `ServerListScreen`。服务器列表使用全宽卡片；长按卡片正文信息区域可触发拖拽排序并持久化到本机服务器列表顺序，编辑、删除、健康检查、打开等可点击控件区域不触发拖拽。服务器为空时在列表页显示欢迎空态，不新增独立路由，并保留底部“添加服务器”入口。添加服务器用完整表单页，添加成功后返回服务器列表，不自动进入项目选择页。名称和直连模式的 OpenCode 服务地址默认留空，不自动创建或暗示设备本机存在 OpenCode Server；用户选择 SSH 转发或 frpc STCP visitor 时，如果服务地址仍为空，则自动填入隧道远端常用地址 `http://127.0.0.1:4096`，但不覆盖用户已输入或编辑页已有的地址。密码用 password field，连接测试失败要显示具体错误。OpenCode 服务地址只允许带有效 host 的 HTTP(S) URL，并拒绝 userinfo、query 和 fragment；旧配置如果不符合约束，列表、状态面板、设置页和编辑页都不得回显原始 URL 或可能由其派生的名称。

Android 端连接地址必须按运行环境配置，不把某个模拟器、真机或 `adb reverse` 端口映射当作所有设备的默认行为。升级迁移只删除仍完全符合历史自动生成值的 `local / Localhost / loopback:4096` 直连记录；用户修改过名称、地址、认证或隧道配置的记录必须保留。

所有普通 URL 和连接方式表单校验都应先于安全确认。仅在直连模式下，保存“规范化后的非本机回环 `http://` 地址 + 有效 OpenCode Basic 认证”时显示明确但不阻断能力的确认弹窗。用户名非空白，且输入了非空白新密码或编辑页保留已有密码 alias 时，视为 Basic 认证有效。Loopback 只做纯语法分类且不查询 DNS：`localhost` 及其保留子域、IPv4 `127.0.0.0/8`、IPv6 `::1` 和 mapped loopback 字面量豁免；LAN 地址、模拟器别名、通配地址和伪装域名不豁免。弹窗说明 Android Keystore 只保护设备本地保存的凭据，不会加密 HTTP 流量。确认后把冻结的已校验表单交给原有凭据事务保存，并允许后续正常使用；取消不调用 Repository 且保留表单。SSH 和 STCP 模式不使用此警告，连接已有配置时也不增加运行时门禁。

### 11.3 连接方式与本地端口连接

Android 端添加/编辑服务器表单使用互斥连接方式：`直连`、`SSH 转发`、`frpc STCP visitor`。直连直接访问 `OpenCode 服务地址`；SSH 和 STCP 都先在 App 所在设备侧建立 `127.0.0.1:<本地端口>`，再将 REST 与 SSE 统一改写到同一个本地端口地址。

SSH 转发启用后，`OpenCode 服务地址` 表示 SSH 远端机器可访问的 OpenCode 地址，例如 `http://127.0.0.1:4096`；App 实际连接地址改写为 `http://127.0.0.1:<本地转发端口>`。

frpc STCP visitor 首版只支持本地端口模式：App 启动 STCP visitor，绑定 `127.0.0.1:<本地绑定端口>`，再访问该本地地址。真实 frp 协议实现通过 `:frpc-stcp-visitor` 模块封装 GoMobile AAR；仓库保存 `frpc-stcp-visitor-go/` wrapper、固定版本和可审计 downstream patch，AAR 由本地脚本或 CI 以不可变版本生成且不提交，并通过本地 Maven 仓库接入 Android 构建。Release 必须校验 AAR checksum、API signature、provenance、预期 ABI、stripped 状态和 16KB ELF 对齐；AAR 未集成时，UI 可保存配置，但连接时返回明确不可用错误。

生成 bridge class 缺失时抛出 `GoMobileBridgeUnavailableException`，bridge class、方法或 payload 不兼容时抛出 `GoMobileBridgeApiMismatchException`；两者都是类型化且不含 payload 的启动失败。STCP readiness 与 SSE 将其视为永久失败，不做反复重试；UI 映射为本地化语义错误，不显示反射细节。

STCP 不能把“配置已提交”当成“连接已就绪”。App 必须先等待当前 frps control epoch 下的真实 visitor listener bind，再通过本地隧道完成一次 `/global/health`，之后才允许项目 REST 和 SSE 使用该连接。首次健康检查、项目快照、全局 SSE 和项目 SSE 并发触发时共享同一个就绪流程，不应各自启动 visitor 或重复探针。

用户点击健康检查时，冷启动中的 STCP 允许在有限预算内等待和重试瞬时 connection refused/reset/timeout；成功后本次 readiness 的 health 结果直接用于显示版本，不再连续请求第二次。认证、证书、地址或配置错误应快速失败并显示明确错误。frps 重连产生新 control epoch 后，在下一次 REST/SSE 连接前重新等待 listener 并校准 `/global/health`；断线期间保留已有 UI 数据，旧 epoch 的迟到结果不得覆盖新连接。

添加服务器表单字段：

| 分组 | 字段 | 默认 | 说明 |
| --- | --- | --- | --- |
| 基础连接 | 连接名称 | 自动生成 | 可选 |
| 基础连接 | OpenCode 服务地址 | 直连为空；选择 SSH/STCP 时若为空则填入 `http://127.0.0.1:4096` | 直连地址，或隧道远端可访问的服务地址；不覆盖已有输入 |
| OpenCode 认证 | OpenCode 用户名/密码 | 空 | HTTP Basic Auth，和 SSH 账号分开 |
| 基础连接 | 连接方式 | 直连 | `直连`、`SSH 转发`、`frpc STCP visitor` 互斥 |
| SSH | SSH 主机 | 空 | 开启后必填 |
| SSH | SSH 端口 | `22` | 短字段可和 SSH 用户名同一行 |
| SSH | SSH 用户名 | 空 | 短字段可和 SSH 端口同一行 |
| SSH | SSH 认证方式 | 私钥 | 支持密码、私钥、密码+私钥 |
| SSH | SSH 密码 | 空 | 认证方式包含密码时必填，存 Keystore |
| SSH | 私钥来源 | 粘贴文本 | 支持粘贴纯文本或选择本地文件 |
| SSH | 私钥文本/文件 | 空 | 私钥内容读取后存 Keystore，只显示文件名；UTF-8 内容最多 256 KiB |
| SSH | 私钥 passphrase | 空 | 可选，存 Keystore |
| 转发设置 | 本地转发端口 | `4096` | 默认不自动改端口；被占用时提示用户修改 |
| 转发设置 | 连接超时 | `10` 秒 | 暴露给用户 |
| 转发设置 | KeepAlive 间隔 | `30` 秒 | 暴露给用户 |
| Host Key | Host key 验证 | 首次信任并保存 | 首次保存指纹到 Keystore，后续不匹配拒绝连接 |
| Host Key | 主机指纹 | 空 | 可选择手动指定，存 Keystore |
| STCP | frps 地址 | 空 | STCP visitor 连接的 frps 地址，开启后必填 |
| STCP | frps 端口 | `7000` | 开启后必须在 1-65535 |
| STCP | frps token | 空 | 可选；填写时存 Keystore，不明文回显 |
| STCP | frpc user | 空 | 可选，传给 frp session |
| STCP | STCP server user | 空 | 可选，对应服务端 STCP proxy user |
| STCP | STCP server name | 空 | 开启后必填 |
| STCP | STCP secret key | 空 | 存 Keystore，不明文回显 |
| STCP | 本地绑定端口 | `4096` | 绑定 `127.0.0.1:<port>`，供 REST/SSE 共用 |
| STCP | frp wire protocol | `v1` | 支持 `v1`/`v2`，transport 首版固定 `tcp` |

移动端布局：输入内容较少的字段可一行展示两个，例如 OpenCode 用户名/密码、SSH 端口/SSH 用户名、本地转发端口/连接超时、frps 端口/STCP 本地绑定端口。私钥文本使用多行输入框；本地文件选择使用 Android 文件选择器，在 IO dispatcher 中以固定缓冲最多读取 256 KiB，不长期保存文件路径；保存边界和 JSch 认证边界再次按 UTF-8 字节数校验，不能只信任文档 provider 的声明大小。STCP token 和 secret key 使用 password field，保存后编辑页留空表示保留原 Keystore 值。

服务器保存采用配置与凭据两阶段切换：新值先写入用途明确且带随机 UUID 的候选 alias，候选构建后先检查协程取消，再在不可取消提交段写入 `ServerConfig`、重新读取确认结果、更新 connection config epoch 并失效 SSH/STCP 运行态。写入抛异常但目标配置已持久化时按成功完成；明确未提交时才回滚候选；确认读取也失败时保守保留候选并报告 unknown outcome，避免删除可能已被配置引用的 secret。提交后的旧 alias 在配置锁外重新读取全部服务器引用后 best-effort 清理。编辑页现有留空语义保持不变：普通密码及 SSH/STCP 密钥字段留空表示保留旧值，关闭对应认证方式或连接方式才移除其配置引用；文件按钮使用“取消选择”而非暗示删除已存私钥。SSH host 或 port 改变后不继承旧 pin：`AcceptNew` 重新 TOFU，`Fingerprint` 要求新 fingerprint；TOFU 指纹使用同一事务规则。

## 12. 设置页

Web 设置在移动端仍使用桌面双栏 dialog，约 `x=16,y=122,w=358,h=600`。左侧 tablist 宽约 150，右侧 panel 宽约 208，导致所有内容被挤成窄列，移动端体验不适合直接复刻。

Android 建议设置为单列 SettingsScreen：顶层列表展示 `通用`、`后台运行设置`、`快捷键`、`服务器`、`提供商`、`模型`。每项进入独立 screen。底部显示版本信息。

### 12.1 通用

| 分组 | 控件 |
| --- | --- |
| 语言 | 单行设置项，点击后用底部 sheet 选择 `跟随系统`、`English`、`简体中文` |
| 权限 | switch `自动接受权限` |
| Terminal Shell | 下拉 `pwsh` |
| 显示 | switch `显示推理摘要`、`展开 shell 工具部分`、`展开编辑工具部分`、`显示会话进度条` checked、`New layout and designs` |
| 外观 | 配色方案 `系统`、主题 `OC-2`、界面字体、代码字体、Terminal Font |
| 系统通知 | `智能体` checked、`权限` checked、`错误` unchecked |
| 音效 | 智能体 `斯泰普博普斯 01`、权限 `斯泰普博普斯 02`、错误 `否 03` |
| 更新 | `发行说明` checked、`立即检查` disabled |

Android 端语言偏好保存到 DataStore，当前支持跟随系统、英文、简体中文。切换后通过共享 localized `Context` 触发 Compose 文案立即刷新，并使用同一语言上下文更新系统通知标题、正文以及通知渠道名称和描述；项目路径、会话标题、文件名、模型/provider 名、命令输出和服务端返回内容保持原样，不做翻译。

Android 端系统通知开关保存到 DataStore，默认与 Web 一致：`智能体` 开、`权限` 开、`错误` 关。通知正文使用本地化资源并对错误摘要做脱敏；智能体/问题、权限、错误分别使用三个稳定且默认静音的 v2 channel。通知点击跳转到对应项目或会话；只有 App 在前台且该会话 destination 实际可见时，才把会话标记为已查看。

Android 端音效设置保存到 DataStore，并复用 Web 端 45 个 `.aac` 音效资源。`智能体`、`权限`、`错误` 三类音效分别使用底部 sheet 选择 `无` 或具体音效；选择具体音效会立即保存并用 notification audio attributes 播放一次预览，选择 `无` 会关闭 OC Deck 自己对该类别的播放。音效与系统通知开关仍互相独立，但每个事件最多只有一个声音所有者：channel 保持 App 创建的静音基线时由 App 播放所选音效；用户在 Android channel 中显式选择系统声音后，以系统设置为准并抑制 App 播放。被阻止、低 importance 或显式静音的 channel 不会被 App 音效绕过。旧 `ocdeck_sessions` 设置 best-effort 迁移到三个 v2 channel，且不覆盖已经存在的 v2 channel。

### 12.1.1 后台运行设置

Android 端新增 `后台运行设置` 子页，用于帮助用户检查会话通知、后台网络和最近任务保留，不照搬截图中的站点文案或绿色视觉，而采用 OC Deck 的浅色卡片风格。

| 项目 | 行为 |
| --- | --- |
| 允许会话通知 | Android 13+ 使用运行时通知权限申请；低版本展示系统自动允许。通知正文不得展示密钥、token、请求头或完整敏感响应。 |
| 忽略电池优化 | 点击 `申请忽略电池优化` 直接调用系统 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 确认弹窗，Data 为 `package:<当前包名>`；不跳转到系统电池优化列表让用户自行查找 App。若系统不支持该弹窗，只展示提示。 |
| 最近任务保留 | 说明不同厂商的锁定/固定最近任务入口不一致，Android 无统一授权弹窗，只提供操作建议。 |

页面应在前台恢复时重新检查通知权限和电池优化状态，底部提供 `重新检查` 与 `完成`。该页面只改变 Android 系统对本机 App 的调度，不修改 OpenCode server 配置。

### 12.2 快捷键

标题 `键盘快捷键`，有 disabled `重置为默认值` 和搜索框 `搜索快捷键`。

分组：`通用`、`会话`、`导航`、`模型与智能体`、`终端`、`提示`。

重要快捷键：

| 功能 | 快捷键 |
| --- | --- |
| 打开设置 | `Ctrl+,` |
| 打开项目 | `Ctrl+O` |
| 返回 | `Ctrl+[` |
| 关闭标签页 | `Ctrl+W` |
| 添加所选内容到上下文 | `Ctrl+Shift+L` |
| 聚焦输入框 | `Ctrl+L` |
| 命令面板 | `Ctrl+Shift+P` |
| 切换侧边栏 | `Ctrl+B` |
| 新建工作区 | `Ctrl+Shift+W` |
| 归档会话 | `Ctrl+Shift+退格` |
| 切换审查 | `Ctrl+Shift+R` |
| 上/下会话 | `Alt+↑/↓` |
| 上/下消息 | `Ctrl+Alt+[/]` |
| 新建会话 | `Ctrl+Shift+S` |
| 自动接受权限 | `Ctrl+Shift+A` |
| 打开文件 | `Ctrl+K` |
| 附加文件 | `Ctrl+U` |
| 切换 MCPs | `Ctrl+;` |
| 切换智能体 | `Ctrl+.` |
| 选择模型 | `Ctrl+'` |
| 切换终端 | ``Ctrl+` `` |
| 新建终端 | `Ctrl+Alt+T` |
| Prompt | `Ctrl+Shift+E` |
| Shell | `Ctrl+Shift+X` |

Android 端快捷键主要用于外接键盘。建议保留只读快捷键列表，并为可编辑快捷键预留后续能力。

### 12.3 服务器

与状态 popover 中服务器管理一致。移动端 Web 中服务器地址、版本、用户名被挤压成竖排或断行。Android 应用全宽卡片展示服务器信息，并允许在卡片正文信息区域长按拖拽调整服务器顺序；按钮和图标操作区保持独立点击行为。

### 12.4 提供商

Provider 设置读取真实服务端目录，支持搜索，并分为已加载与可连接两组。服务端 `connected` 数组是已加载状态的权威来源，但不代表凭据有效、网络可达或模型请求已成功。环境变量和配置托管的 Provider 会说明其外部管理来源，不提供误导性的断开操作；自定义 Provider 可从卡片进入编辑页。

自定义提供商表单：

| 字段 | placeholder/说明 |
| --- | --- |
| 提供商 ID | `myprovider`，小写字母、数字、连字符或下划线 |
| 显示名称 | `我的 AI 提供商` |
| 基础 URL | `https://provider-api.example.test/v1` |
| API 密钥 | 可选，不记录真实值 |
| 模型 | ID `model-id`、名称 `显示名称`、添加/移除模型 |
| 请求头（可选） | `Header-Name`、`value`、添加/移除请求头 |
| 保存 | 分阶段写入服务端 |

表单说明链接：`https://opencode.ai/docs/providers/#custom-provider`。

Web 移动端问题：点击自定义 provider 表单的“返回”后，设置弹窗整体关闭而不是回到 provider 列表。Android 应显式维护设置子页栈。

当前原生实现：

- `/provider` 与 `/global/config` 会立即投影为安全领域摘要。原始 `key`、`env`、`options`、Provider/模型 Header 和其他可能含密钥的值不会进入 Compose state 或内存 Store。
- 连接操作从 `/provider/auth` 按项目作用域加载认证方式。API/OAuth 方法保留原始数组 wire index，渲染服务端返回的 text/select prompt 与 `when.eq/neq` 条件，只提交当前可见且通过校验的输入。
- API key 写入 OpenCode Server 的 auth store，Android 客户端不持久化。直连非本机回环 `http://` 上传密钥前必须明确确认；确认只消费一次冻结的已校验请求，取消不写入。
- OAuth authorize/callback 保持相同的可选 `directory/workspace` 作用域。Code 模式支持手工粘贴 code；auto 模式使用可取消且总时长限制为十分钟的专用 client。浏览器 URL 只允许带有效 host、无 userinfo 的 HTTP(S)。Loopback 授权 URL 会明确提示：手机浏览器通常无法访问运行在远程 OpenCode Server 进程中的回调 listener。
- 自定义 Provider 最多支持 100 个模型和 50 个 Header。每个添加操作旁持续显示数量，达到或超过上限后禁用，并区分合法满额与外部载入的超限状态，不会静默截断已有行。保存时先把 Provider 以 disabled 状态暂存，再写入可选 API key，最后启用；凭据失败会保留安全的 disabled 配置，最终写入无法确认时报告 unknown outcome，而不是伪装成明确失败。API key 和 Header value 仅在编辑期间保留于内存，并在已提交、部分提交或结果未知后清除。
- OpenCode 的 global-config PATCH 是 deep merge，无法可靠删除字段。因此编辑页不允许移除或重命名已保存的模型 ID 和 Header 名。“停用并清理凭据”会先加入 `disabled_providers`，再 best-effort 删除 auth，并保留 disabled 配置；它不是物理删除。
- Provider 修改成功或部分提交后，客户端会尽力 dispose 服务端实例，并通过现有 generation/source/transport 校验的 SSE 校准链路刷新受影响活动项目的 capability。

Provider 目录、认证方法和 OAuth endpoint 属于 instance scope，可带 `directory/workspace`；凭据写入与 global-config 属于 server-global root control。项目/会话入口保留当前 directory 用于方法发现与 OAuth，顶层 Settings 入口没有项目作用域。

Provider 相关接口：

| 操作 | 接口 |
| --- | --- |
| 列表 | `GET /provider?directory=...&workspace=...` |
| 认证方法 | `GET /provider/auth?directory=...&workspace=...` |
| 写入 API auth | `PUT /auth/{providerID}`，server-global |
| 删除 auth | `DELETE /auth/{providerID}`，server-global |
| OAuth 开始 | `POST /provider/{providerID}/oauth/authorize?directory=...&workspace=...` |
| OAuth 回调 | `POST /provider/{providerID}/oauth/callback?directory=...&workspace=...` |
| 读取/更新自定义 Provider 配置 | `GET/PATCH /global/config`，server-global 且可能含密钥 |
| 重载 Provider 实例 | `POST /global/dispose` |

### 12.5 模型

设置页“模型”用于启用/隐藏模型，不是选择当前会话模型。

| Provider | 可见模型和开关 |
| --- | --- |
| provider-alpha | `model-standard` checked、`model-fast` off、`model-reasoning` checked |
| provider-beta | `model-small` checked、`model-large` off |

Android 建议：模型设置页为 provider 分组列表，开关状态立即保存或显示“保存”按钮需产品确认。当前原生端通过 `/provider` 读取真实 provider/model 列表，模型启用/隐藏仅保存到 App 本地 DataStore，并在设置页与 composer 模型列表中统一生效，不读写 OpenCode server 的 config；模型设置页服务器信息只展示服务器 URL。敏感 provider 配置不展示密钥明文。

## 13. Composer 交互设计

### 13.1 基础结构

底部 composer 由输入框、发送按钮、附加文件、agent、模型、推理强度组成。空输入时发送按钮 disabled。输入 fixture 文本后发送按钮 enabled，但探索未点击发送。

当前 Web 中还出现过 `Shell` chip 和 `取消` 按钮。该 chip 可能由 `/shell` 或快捷键切换触发。移动布局中 `取消` 按钮被同层元素拦截，普通点击失败，说明底部参数条触摸层级存在问题。Android 要保证 chip 的删除按钮有独立 48dp 触摸区域。

### 13.2 Slash command

输入 `/` 后，composer 上方弹出命令/技能建议面板，约从 `y=368` 到 composer 顶部，高约 320，覆盖消息流下部。

列表包含：

| 类型 | 示例 |
| --- | --- |
| 项目命令 | `/init`、`/review`、`/example-command` |
| Skill | `/example-command` |
| 内置命令 | `/new`、`/undo`、`/compact`、`/fork`、`/share`、`/open`、`/terminal`、`/model`、`/mcp`、`/agent`、`/workspace` |

Android 建议：命令面板优先使用 composer 上方内联建议面板，支持搜索、键盘上下选择和触摸选择；候选较多或需要复杂筛选时再使用受屏幕约束的 bottom sheet。选择命令后填入 chip 或文本，只有匹配已加载 `/command` 数据的命令才可分流到 command API。

### 13.3 @ mention

输入 `@` 后只显示 agent mention 候选：`@explore`、`@general`。这不是文件引用。文件上下文主要通过“附加文件”或 `/open` 命令进入。

Android 建议：mention panel 独立数据源，和文件搜索分开。Agent mention 选择后插入 mention token。

### 13.4 附加文件

点击 `附加文件` 触发系统文件选择器，而非 Web 内部文件列表。选择 `X:\workspace\sample-project\example.txt` 后，composer 变高，上方出现 64x64 附件卡片，包含文件图标、文件名 `example.txt` 和 `移除附件` 按钮；发送按钮 enabled。点击移除附件后未发送。

Android 复刻有两种模式：

| 模式 | 适用 |
| --- | --- |
| 本机文件选择器 | 选择手机本地图片/文件，转为 data URL 或上传给后端 |
| 服务端项目文件选择器 | 选择 OpenCode server 所在项目文件，调用 `/file`、`/file/content`、`/find/file` |

Web 当前“附加文件”更像本机文件选择器。若 Android app 连接的是桌面 OpenCode server，推荐同时提供“从项目选择”和“从手机选择”。

当前原生实现明确区分两类来源：手机本地附件是受字节预算约束的 `data:` part；服务端项目文件上下文是独立草稿值，只保存经过校验的相对路径，最多选择 10 个文件。仅选择文件不会调用 `/file/content`，只有用户主动预览时才读取；Android 不会仅为加入上下文而读取或 Base64 编码该文件。Sender 最终边界会从规范化项目根和相对路径重新构造逐段百分号编码的 `file://` URL，以 `text/plain` file part 发送，并由 OpenCode Server 读取和展开。允许仅项目上下文发送，普通 prompt 与已加载 Slash command 都携带所选上下文。

### 13.5 Agent 下拉

Composer agent 下拉只显示：

| 选项显示 | 内部 id | 状态 |
| --- | --- | --- |
| `Build` | `build` | selected |
| `Plan` | `plan` | 可选 |

这与 `/agent` 接口返回的 7 个 agent 不完全一致；composer 可能只展示 primary/prompt 可用 agent。Android 应按 Web 过滤逻辑展示，而非直接展示全部 agent。

### 13.6 模型选择器

点击 `model-standard` 打开 `选择模型` 浮层，约 `x=94,y=470,w=288,h=320`。

元素：

| 元素 | 行为 |
| --- | --- |
| 搜索框 | `搜索模型` |
| `连接提供商` | 进入 provider 连接 |
| `管理模型` | 进入模型设置 |
| 分组模型列表 | 按 provider 分组 |

可见模型：

| Provider | 模型 |
| --- | --- |
| provider-alpha | `model-standard` selected、`model-fast`、`model-reasoning` |
| provider-beta | `model-small`、`model-large` |

Android 建议：模型选择器按 provider 分组并支持搜索，当前模型右侧显示 check。内容能在小屏内受约束展示时可使用 anchored popup；列表较长、搜索或管理入口较复杂时使用 `ModalBottomSheet`。`连接提供商` 和 `管理模型` 使用至少 48dp 真实触控目标及本地化 contentDescription。

当前 Android 实现：两个管理入口都会先关闭 picker；`连接提供商` 导航到 `ProviderSettingsRoute(serverId, directory?, workspace?)`，并在可用时保留当前项目作用域；`管理模型` 导航到 `ModelSettingsRoute(serverId)`。两者均不是占位操作。

Android 端应按 `serverId` 使用 DataStore 记住 Composer 上一次选择的模型和该模型对应的推理强度。应用重启后进入最近会话页或任意项目会话时自动恢复；恢复前必须校验模型仍来自已连接 provider，且缓存的 variant 仍在当前模型 `variants` 中。模型不存在时不恢复；variant 不再支持时恢复模型但使用 `默认`。

### 13.7 推理强度/variant 下拉

点击底部推理强度按钮出现 listbox。Web 端不会写死固定强度，而是从当前选中模型的 `variants` object keys 动态生成列表，并在 UI 层追加 `默认` 选项。当前模型没有 `variants` 或 `variants` 为空时，不展示推理强度入口。

| 模型示例 | 动态选项 |
| --- | --- |
| provider-alpha / `model-standard` | `默认`、`none`、`low`、`medium`、`high`、`xhigh` |
| provider-alpha / `model-reasoning` | `默认`、`low`、`medium`、`high`、`max` |
| provider-beta / `model-small` | `默认`、`none`、`high` |
| provider-beta / `model-basic` | 无可选 variants，不展示入口 |

它不是权限模式，而是模型 variant/推理强度选择。Android 文案建议明确为“推理强度”或“Reasoning”，避免误解；动态 variant 的展示文案首字母大写，例如 `none` 展示为 `None`、`max` 展示为 `Max`、`xhigh` 展示为 `Xhigh`。切换模型时，如果旧 variant 不在新模型 `variants` 中，必须重置为 `默认`，避免发送服务端不支持的 variant。

### 13.8 无障碍、对比度与大字体行为

当前 Android 交互与验收规则：

- 所有可点击图标、chip 操作、picker 行、Dock 操作和短状态控件都提供至少 48dp 真实触控目标，即使视觉图标更小。
- Tab 暴露 `Role.Tab` 和 selected 状态；单选、多选与 Switch 暴露匹配 role 及 selected/checked 状态。可展开的状态、目录、问题和回滚控件提供本地化展开/折叠说明。
- 未读、错误、权限、连接、选中和工作中状态不只依赖颜色，使用文字、图标、边框、标记或状态说明表达相同含义。可排序服务器卡片除长按拖拽外，还提供本地化 TalkBack“上移服务器/下移服务器”自定义操作。
- 浅色和深色 palette 下，正文/辅助文字目标至少 4.5:1，必要非文本状态指示和控件边界至少 3:1。真实文本框和模型搜索框使用 `ControlBorder`；JVM 对比度测试覆盖语义化 Diff、Markdown、语法、图表/上下文、状态、附件和控件颜色。
- 文本布局优先自然测量和 `heightIn`，避免固定文本高度。Dialog、服务器状态、设置 sheet、建议面板、权限/问题内容和 Composer 附件在紧凑手机、200% 字体及 IME 可见时仍受屏幕约束且可滚动；附件和参数行使用横向滚动，避免把操作挤出可达区域。
- Popup 保持可聚焦，并可通过系统返回关闭；颜色、背景、边框、阴影与状态样式必须在两种主题下都能理解。

## 14. 发送与 composer 状态机

前端发送逻辑来自 `session-composer-state`。

### 14.1 提交流程

1. `handleSubmit` 阻止默认提交。
2. 读取当前 prompt 文本、手机本地附件、项目文件上下文和 composer mode。
3. 如果文本、附件和上下文全部为空，且当前 session 正在工作，则执行 abort；否则直接返回。
4. 以当前 `ProjectKey` 的内存 Store capability snapshot 为权威来源，校验 UI 携带的 revision、当前 model、agent、variant 和已加载 command；revision 已变化时要求用户基于最新选择重试。
5. 在后台线程对附件做最终边界检查：数量、单文件和总 raw bytes、必填 metadata、data URL header、Base64 字符/空白/padding、encoded length 与声明 `sizeBytes` 必须一致。项目上下文独立校验最多 10 个、非空 id、按操作系统语义规范化和去重的相对路径、项目根包含关系，以及确定性的 `file://` 构造。
6. 仅当 capability revision 仍未变化时，原子插入乐观消息。
7. 如果当前没有 session id，说明是新会话。可选创建 worktree，然后调用 `POST /session` 创建 session；拿到真实 id 后先把真实 session key 加入同一 operation lease，再发布 session、移动消息和导航回调。
8. 根据 mode 和输入内容选择接口。
9. 普通 prompt 使用乐观消息。请求失败只删除仍标记为 optimistic 的同一消息；如果 SSE 已把该 message id 确认为服务端消息，则保留消息并提示“结果不确定”，避免重试造成重复发送。

### 14.2 新会话创建

接口：`POST /session`

Query：`directory`、`workspace`

Body 字段：`parentID`、`title`、`agent`、`model`、`metadata`、`permission`、`workspaceID`

### 14.3 普通 prompt

接口：`POST /session/{sessionID}/prompt_async`

Query：`directory`、`workspace`

Body 字段：`messageID`、`model`、`agent`、`noReply`、`tools`、`format`、`system`、`variant`、`parts`

前端会先生成 `messageID`，构造本地 user message 和 parts，乐观插入消息流。Android 的 Store 插入同时比较 capability revision；失败回滚使用 message id 与 `isOptimistic` 条件删除，不能覆盖或删除先到达的 SSE 确认版本。

普通 prompt 的 parts 依次包含 text part、项目文件上下文 parts 和手机本地附件 parts。每个项目上下文沿用稳定 part id，使用 `type: "file"`、`mime: "text/plain"`、安全 basename，以及从当前项目根构造的 `file://` URL。OpenCode Server 已支持该 part 形式，不需要新增 endpoint。

### 14.4 Slash command

当输入以 `/` 开头且命令存在于 `/command` 返回值中，调用：

`POST /session/{sessionID}/command`

Body 字段：`messageID`、`agent`、`model`、`arguments`、`command`、`variant`、`parts`

其中 `model` 为字符串 `${providerID}/${modelID}`。已加载命令同时携带项目文件上下文和手机本地附件。本地附件 part 形如：

```json
{
  "id": "part_...",
  "type": "file",
  "mime": "...",
  "url": "data:...",
  "filename": "..."
}
```

项目上下文使用相同 file-part 结构，`mime` 为 `text/plain`，URL 例如 `file:///E:/repo/src/Main.kt`。

### 14.5 Shell mode

当 composer mode 为 `shell`，调用：

`POST /session/{sessionID}/shell`

Body 字段：`messageID`、`agent`、`model`、`command`

探索中观察到输入区出现 `Shell` chip，但未发送。Android 应把 prompt mode 作为明确状态显示，并可一键取消。

### 14.6 Abort

接口：`POST /session/{sessionID}/abort`

空输入且 session working 时，提交按钮/行为会转为 abort。Android 可将发送按钮变为 stop 按钮。

### 14.7 Worktree

如果新会话选择创建 worktree，调用：

`POST /experimental/worktree`

Body 为 `worktreeCreateInput`。

还有 `DELETE /experimental/worktree` 和 `POST /experimental/worktree/reset`。

## 15. 权限、问题与实时事件

### 15.1 SSE

全局事件：`GET /global/event`

响应类型：`text/event-stream`

Headers 包含：`cache-control: no-cache, no-transform`、`x-accel-buffering: no`、`x-content-type-options: nosniff`。

项目级事件客户端也存在：`GET /event?directory=...&workspace=...`。

Android 生产 SSE 路径使用 OkHttp `Call` 与 `ResponseBody.source()`，由自定义有界 reader 直接解析字节流，不再使用 okhttp-sse 的无界事件 parser。请求显式携带 `Accept-Encoding: identity`，任意非 identity `Content-Encoding` 都在 `onOpen` 前零读取拒绝。只接受 HTTP 200 且 Content-Type media type 为 `text/event-stream`（允许 charset 参数）；204、其他非 200 和错误/缺失 Content-Type 在 `onOpen` 前关闭且不读取 body，使用不含 body 的类型化协议失败。204、非瞬时 4xx、content-encoding 和 Content-Type 错误进入 `Failed`，408/409/425/429 与 5xx 使用现有有界退避重试。单个 SSE 物理/逻辑行和完整 event data 的 UTF-8 identity response-body 数据分别限制为 32 MiB；32 MiB 可容纳单条最大 20 MiB raw 附件的 Base64 回显及 SSE/JSON framing。reader 在整行转为 `String` 前限制行字节，在拼接完整 event data 前限制累计字节，支持 LF、CRLF、CR、BOM、comments 和 `data/event/id`；只有完整空行才 dispatch，EOF 丢弃未终止的 pending line/event，服务端 `retry` 不得覆盖客户端有界退避策略。超限立即关闭响应、取消 Call 并报告无 payload 的类型化失败，显式 handle cancel 引发的 I/O 失败不回调为可重试 failure。单条事件的 JSON 语法或字段类型异常只丢弃该事件，不终止连接；JVM `Error` 仍传播。

全局流和项目流由应用级 owner lease 管理；项目壳层和直接深链进入的会话详情共享同一个 global source，最后一个 owner 释放或强制关闭后进入 `Closed`，旧 generation/source 的连接结果、回调和重试不得恢复已关闭流。同一 ProjectKey 的项目流已 `Open` 且 transport identity 与 global listener 一致时，项目流是项目事件权威来源，global 中对应 directory/workspace 的事件不再归约或缓冲；项目流尚未打开、正在重试或失败时 global 仍可 fallback。fallback 收到 LSP/MCP 能力变化时，global listener 可携带自己的 generation/source/transport token 发起安全快照校准；项目流随后 `Open` 会取消该 global 校准并以项目流校准替换，旧结果不得覆盖。项目流打开、重连和前台恢复后通过应用级 single-flight 协调器拉取 REST 快照，校准期间按到达顺序有界缓冲权威来源事件，快照成功时先应用快照再重放，失败时保留现有数据并直接重放；校准中出现 LSP/MCP 能力变化时合并为当前轮结束后最多一次 follow-up。STCP 模式下 EventSource 和快照创建前必须经过统一 connection readiness；`getConnection()` 返回前复验 native generation/control epoch，项目快照和前台 health 在 I/O 成功或失败后再次校验 connection identity，变化时丢弃结果并有限重试。frps control epoch 或服务器配置 epoch 变化时，同一服务器的全局/项目流按 transport identity 整体重建，明确旧于当前 identity 的 open attempt 原子解绑并进入受 generation/desired 状态保护的标准重试，不能停留在 `Connecting`。

### 15.2 Permission

| 操作 | 接口 |
| --- | --- |
| 列表 | `GET /permission?directory=...` |
| 旧式 reply | `POST /permission/{requestID}/reply`，body `reply`、`message` |
| session permission respond | `POST /session/{sessionID}/permissions/{permissionID}`，body `response` |

Web 实测确认：权限确认不是居中 dialog，而是会话底部 composer 区域的阻塞式 Dock。pending permission 出现时普通 composer 隐藏，Dock 显示 warning 图标、标题 `需要权限`、工具说明、pattern 列表和三个操作：`拒绝`、`始终允许`、`允许一次`。按钮提交到 `POST /session/{sessionID}/permissions/{permissionID}`，body 为 `{ "response": "reject|always|once" }`，成功返回 `true`。当前可见 Dock 仍消费旧 `/permission` 列表和 `permission.asked` / `permission.replied` SSE 事件形状，典型 request 字段为 `id`、`sessionID`、`permission`、`patterns`、`always`、`tool`。

Android 建议：`SessionDetailScreen` bottomBar 中按 Question 优先、Permission 次之显示阻塞式 `PermissionDock`，与 `QuestionDock` 同级替换普通 composer。Dock 使用浅/深色 token，不硬编码单一主题；patterns 可滚动、可选择复制；三按钮保持至少 48dp 触控目标。前端自动接受权限会调用 `permission.respond`，response 示例为 `once`；Android 后续若支持自动接受，应先过滤已自动响应请求，避免 UI 闪现。

### 15.3 Question

| 操作 | 接口 |
| --- | --- |
| 列表 | `GET /question?directory=...` |
| 回复 | `POST /question/{requestID}/reply`，body `answers` |
| 拒绝 | `POST /question/{requestID}/reject` |

当前 `/question` 返回空数组。Android 端建议用 bottom sheet 呈现问题，支持单选、多选、自定义答案和拒绝。

## 16. 网络接口总表

每个 `OpenCodeApi` Retrofit 方法都显式声明入站响应策略。普通成功 JSON/object/list/Boolean/`JsonElement` 响应和 `/file/content` 使用两层独立的 16 MiB 边界：request tag 驱动 network interceptor 在 `Content-Encoding` 解码前限制 encoded response-body octets，Retrofit interceptor 再延迟限制 decoded entity。`Content-Length` 只用于提前拒绝，未知或低报长度在 `max + 1` 失败。成功的 `Response<Unit>` body 不读取即关闭丢弃；所有非 2xx body 也会关闭并替换为空实体，同时保留状态码，避免 Retrofit 缓存或转换敏感错误内容。缺少策略时在请求发出前 fail-closed。没有 Retrofit `Invocation` 的请求（包括 session-message direct transport）必须显式附加自己的 encoded-body policy。

### 16.1 启动与全局

| 方法 | 路径 | 用途 | 备注 |
| --- | --- | --- | --- |
| GET | `/global/config` | 全局配置 | 包含敏感 provider 配置，必须脱敏 |
| PATCH | `/global/config` | 更新全局配置 | body 为裸 partial config，不包裹 `config` |
| GET | `/provider` | provider 和模型列表 | 可带 `directory` |
| GET | `/provider/auth` | Provider 认证方法 | 可选 `directory/workspace`；方法数组下标属于协议值 |
| PUT/DELETE | `/auth/{providerID}` | 写入/删除 Provider 凭据 | server-global root-control API |
| POST | `/provider/{providerID}/oauth/authorize` | 启动 Provider OAuth | 可选 `directory/workspace`；有状态且不自动重试 |
| POST | `/provider/{providerID}/oauth/callback` | 完成 Provider OAuth | 与 authorize 保持相同 scope 和方法下标；auto 长回调使用有界专用 client |
| GET | `/path` | home/state/config/worktree/directory | 可带 `directory` |
| GET | `/project` | 最近/已知项目列表 | 项目含 `id/worktree/vcs/icon/time/sandboxes` |
| GET | `/global/health` | 健康检查 | 周期性请求 |
| GET | `/global/event` | 全局 SSE | 实时同步 |
| POST | `/global/dispose` | dispose | 设置服务器管理相关，谨慎使用 |

### 16.2 项目加载

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/path?directory=...` | 项目路径上下文 |
| GET | `/lsp?directory=...` | LSP 列表，当前空数组 |
| GET | `/mcp?directory=...` | MCP 列表，当前空对象 |
| GET | `/session?directory=...&workspace=...&roots=true&limit=...` | Store 共享窗口使用的根会话有序前缀 |
| GET | `/agent?directory=...` | agent 列表 |
| GET | `/config?directory=...` | 项目配置，敏感字段脱敏 |
| PATCH | `/config?directory=...` | 更新项目配置，body `config` |
| GET | `/session/status?directory=...` | session 工作状态 |
| GET | `/vcs?directory=...` | 分支信息 |
| GET | `/command?directory=...` | slash command 列表 |
| GET | `/permission?directory=...` | pending permissions |
| GET | `/question?directory=...` | pending questions |

### 16.3 会话

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/session/{sessionID}` | 会话详情 |
| PATCH | `/session/{sessionID}` | 更新 title/metadata/permission/time |
| DELETE | `/session/{sessionID}` | 删除会话 |
| GET | `/session/{sessionID}/message?limit=200` | 消息列表；绕过 Retrofit 错误体缓存，2xx 以流式 JSON decode 限制为 64 MiB，非 2xx 不读取 body |
| GET | `/session/{sessionID}/message/{messageID}` | 单条消息 |
| DELETE | `/session/{sessionID}/message/{messageID}` | 删除消息 |
| DELETE | `/session/{sessionID}/message/{messageID}/part/{partID}` | 删除消息 part |
| POST | `/session/{sessionID}/fork` | fork 会话，body `messageID` |
| POST | `/session/{sessionID}/abort` | 中止运行 |
| POST | `/session/{sessionID}/init` | 初始化 AGENTS.md 流程 |
| POST | `/session/{sessionID}/share` | 分享 |
| DELETE | `/session/{sessionID}/share` | 取消分享 |
| POST | `/session/{sessionID}/summarize` | 总结/压缩，body `providerID/modelID/auto` |
| POST | `/session/{sessionID}/prompt_async` | 发送普通 prompt |
| POST | `/session/{sessionID}/command` | 发送 slash command |
| POST | `/session/{sessionID}/shell` | 发送 shell command |
| POST | `/session/{sessionID}/permissions/{permissionID}` | 回复 pending permission，body `response=reject|always|once` |
| POST | `/session/{sessionID}/revert` | 建立或移动可逆回滚边界，body `messageID/partID`；directory/workspace 使用请求 header |
| POST | `/session/{sessionID}/unrevert` | 清除回滚边界并恢复全部消息；directory/workspace 使用请求 header |
| GET | `/session/{sessionID}/diff` | 会话 diff |
| GET | `/session/{sessionID}/todo` | 会话 todo |
| GET | `/session/{sessionID}/children` | 子会话 |

### 16.4 文件、查找、VCS、PTY

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/find` | 文本查找，query `pattern` |
| GET | `/find/file` | 文件/目录查找，query `query/dirs/type/limit` |
| GET | `/find/symbol` | 符号查找 |
| GET | `/file` | 目录列表，query `path` |
| GET | `/file/content` | 文件内容，query `path` |
| GET | `/file/status` | 文件状态 |
| GET | `/vcs` | 分支信息 |
| GET | `/vcs/status` | VCS 状态 |
| GET | `/vcs/diff` | diff，query `mode/context` |
| GET | `/vcs/diff/raw` | raw diff |
| POST | `/vcs/apply` | 应用 patch，body `patch` |
| GET | `/pty/shells` | shell 列表 |
| GET | `/pty` | PTY 列表 |
| POST | `/pty` | 创建 PTY，body `command/args/cwd/title/env` |
| DELETE | `/pty/{ptyID}` | 删除 PTY |
| GET | `/pty/{ptyID}/connect-token` | connect token |
| GET | `/pty/{ptyID}/connect` | PTY 连接 |

文件接口补充约束：`/file` 只返回指定目录的直接子项，字段为必填的 `name/path/absolute/type/ignored`；`/file/content` 返回 `{ type: "text", content }`，或 `{ type: "binary", content, encoding: "base64", mimeType }`。当前服务端会对文本执行 `trim()`、缺失文件返回成功空文本，且 `/file/status` 仍固定返回空数组；Android 客户端不依赖 `/file/status`。该端点先经过通用 16 MiB encoded response-body 边界和延迟的 16 MiB decoded Retrofit 边界，再在 JSON 反序列化前保留 reader 层 decoded 复验，形成纵深防御。

## 17. 数据模型摘要

### 17.1 Project

字段：`id`、`worktree`、`vcs`、`icon.color`、`time.created`、`time.updated`、`sandboxes`。

Android 本地模型建议增加：`normalizedPath`、`displayName`、`lastOpenedAt`、`isDuplicateAlias`。

### 17.2 Session

字段：`id`、`slug`、`projectID`、`directory`、`path`、`summary.additions/deletions/files`、`cost`、`tokens.input/output/reasoning/cache.read/cache.write`、`title`、`agent`、`model.id/providerID/variant`、`version`、`time.created/updated`。

示例当前会话：

| 字段 | 值 |
| --- | --- |
| id | `ses_fixture_001` |
| title | `示例会话` |
| agent | `build` |
| model | `provider-alpha/model-standard` |
| variant | `xhigh` |
| tokens.input | `<fixture-input-tokens>` |
| tokens.output | `<fixture-output-tokens>` |
| tokens.reasoning | `<fixture-reasoning-tokens>` |
| tokens.cache.read | `<fixture-cache-read-tokens>` |

### 17.3 Agent

`/agent` 返回多个 agent。fixture 中的 `build`：description 为 `The default agent. Executes tools based on configured permissions.`，mode `primary`，native true，permission 包含 `{permission:"*", pattern:"*", action:"allow"}` 等。

Composer 选择器只显示 `Build` 和 `Plan`，内部仍使用 `build` 和 `plan` id，需应用 Web 过滤逻辑。

### 17.4 Provider

`/provider` 返回结构：`{ all, default, connected }`。当前服务端的 `all` 为数组，兼容解析同时接受 object 和 `providers` 变体。`connected` 是 Provider 是否已加载的权威来源，禁止根据 `source` 推断。

原始 Provider 对象可能包含 `id/name/source/env/key/options/models`，模型对象也可能包含 Header。Android 会立即投影为 ID/名称/来源、已加载状态、模型数量和自定义配置标记；不得暴露或保留 `env`、API key、Header、auth token、secret-capable options 中的 URL 或原始响应。

`/provider/auth` 返回按 Provider ID 分组的方法数组。未知方法/prompt 可跳过，但受支持方法必须保留原始数组下标供 authorize/callback 使用。OAuth URL 在打开浏览器前经过外部 HTTP(S) URL 策略。`/global/config` 同样只投影自定义 Provider 身份、模型身份、Header 名和 disabled 状态，不保留 API key 或 Header value。

## 18. 移动端适配问题清单

| 问题 | Web 观察 | Android 处理 |
| --- | --- | --- |
| 设置双栏太窄 | 右侧 panel 约 208px，内容断行 | 改单列全屏设置 |
| 服务器列表断行 | 地址/版本/用户名被挤压 | 全宽卡片 |
| 底部审查区域不可见 | y=843 外几乎看不到 | 独立页面或 bottom sheet |
| 路径重复 | `\` 和 `/` 导致同项目重复 | 路径规范化去重 |
| 抽屉 pointer 拦截 | offscreen DOM 偶发拦截 | 关闭后移除命中区域 |
| Composer chip 取消难点 | `取消` 被同层元素拦截 | chip 独立触摸目标和 z-index |
| Provider 子页返回 | 点击返回关闭整个设置弹窗 | 明确 NavBackStack |
| 新建会话继承 Shell 状态 | `/session` 新页仍显示 `Shell` chip | 新建会话默认重置 composer，或按产品定义保存草稿 |
| 文件附件来源不清晰 | Web 触发系统文件选择器 | 区分手机本地文件和服务端项目文件 |
| Popover 小屏定位 | 模型/状态/tooltip 使用桌面浮层 | 短列表和摘要使用受约束 anchored popup；长列表、搜索和复杂操作使用 bottom sheet |
| 选择语义隐含在视觉中 | 桌面视觉可能表示选中但没有无障碍 role | 暴露 Tab/RadioButton/Checkbox/Switch role 与 selected/checked 状态 |
| 状态依赖颜色 | 未读/错误/权限/连接颜色可能难以识别 | 颜色配合文字、图标、边框、标记或状态说明 |
| 大字体或 IME 隐藏操作 | 固定高度桌面浮层可能裁切内容 | 自然测量、有界纵向/横向滚动，并验证 200% 字体和 IME |
| 服务器排序只支持拖拽 | 长按拖拽不满足 TalkBack 使用 | 增加本地化上移/下移自定义操作 |

### 18.1 人工 UI 验收矩阵

当前没有 `app/src/androidTest` 测试集，CI 也没有 emulator/instrumentation job。在该门禁建立前，交互改动必须按以下矩阵人工检查：

| 环境 | 检查项 | 预期结果 |
| --- | --- | --- |
| 200% 字体的紧凑竖屏手机 | 二次确认 dialog、服务器状态、设置 sheet、建议面板、权限/问题 UI、附件行 | 内容保持受屏幕约束且可滚动；标题和主操作可达，不因固定高度被裁切 |
| 紧凑手机上打开 IME | 底部 Composer、内联建议、model/agent/variant popup、附件条、系统返回 | Composer 保持在 inset 上方；浮层不被遮挡；系统返回先关闭聚焦浮层，再离开页面 |
| 启用 TalkBack | 图标标签、Tab/选择控件、展开/折叠控件、未读/错误/权限/连接状态、服务器排序 | 正确播报 role 与状态；重要含义不只依赖颜色；服务器卡片提供上移/下移操作 |
| 浅色和深色主题 | 正文/辅助文字、文本框、边框、选中/错误/状态指示、Diff/Markdown/代码、图片标题遮罩 | 文字目标 4.5:1，必要非文本边界/指示目标 3:1；没有仅适配单一主题而丢失含义的硬编码样式 |
| Composer 模型选择器 | 搜索、provider 分组、当前选择、连接 Provider、管理模型 | 行保持 48dp 目标；管理入口关闭 picker 后打开真实 Provider/模型设置路由 |
| Provider 设置与认证 | 搜索、已加载/可连接分组、动态 text/select prompt、API key 键盘、OAuth 浏览器/code/取消、明文 HTTP 与断开确认、自定义 Provider 多行编辑 | 关闭后不回显密钥；sheet/表单在 IME 和 200% 字体下仍可滚动；返回先关闭聚焦浮层；已加载/disabled/错误状态不只依赖颜色 |

## 19. Android 原生实现建议

### 19.1 技术结构

推荐使用 Kotlin + Jetpack Compose。

| 层 | 职责 |
| --- | --- |
| Network | Retrofit/OkHttp，SSE client，统一 query `directory/workspace` |
| Repository | ProjectRepository、SessionRepository、ConfigRepository、ProviderRepository |
| Store | 内存 store + Room 可选缓存，响应 SSE 做增量更新 |
| UI | Compose screens，ViewModel 暴露 StateFlow |
| Security | Android Keystore 存 server credential，日志脱敏 |

### 19.2 导航图

当前 screen 与路由能力：

| Screen/能力 | 参数与状态 |
| --- | --- |
| `ProjectPickerScreen` | serverId |
| `ProjectShellScreen` | projectDirectory |
| `SessionListDrawerContent` | projectDirectory |
| `SessionDetailScreen` | projectDirectory, sessionId；新会话复用 `"new"` 状态，不设置独立 `NewSessionScreen` |
| `ReviewRoute` | projectDirectory, optional sessionId；当前仍为占位，实际 diff 位于会话详情 Changes tab |
| `SettingsScreen` | serverId |
| `BackgroundRunSettingsScreen` | serverId |
| `ProviderSettingsScreen` | serverId、可选项目 directory/workspace scope |
| `CustomProviderFormScreen` | serverId、可选项目 directory/workspace scope、编辑时可选 providerId |
| `ModelSettingsScreen` | serverId |
| `ServerListScreen` | none |
| `AddServerScreen` | none |

所有全屏页面之间的导航跳转统一使用滑入滑出动画：前进时新页面从右侧滑入、当前页面向左滑出；返回时上一页面从左侧滑入、当前页面向右滑出。底部 sheet、drawer、dialog 使用各自组件的标准展开/关闭动效，不混用全屏页面转场。

### 19.3 UI 组件与交互能力

组件函数名不强制使用 `*Sheet` 后缀；具体形态根据内容长度、搜索需求和屏幕空间在 inline panel、anchored popup、Dock 与 `ModalBottomSheet` 之间选择。

| 组件/能力 | 要点 |
| --- | --- |
| `BottomComposer` | 输入、附件、agent/model/variant、send/stop、mode chip |
| 命令建议 | composer 上方内联面板；slash commands、skill badge、快捷键 hint |
| Mention 建议 | composer 上方内联面板；agent mention，数据源不与文件搜索混用 |
| Model picker | 搜索、provider 分组、当前 check；长列表使用受约束 popup 或 sheet；连接 Provider 与管理模型导航到真实设置路由 |
| Agent picker | 展示 Build/Plan，内部 id 为 build/plan，未来可扩展 |
| Variant picker | 根据当前模型 `variants` 动态展示 `默认` + 模型支持项，模型无 variants 时不展示入口 |
| `SessionMessageCard` | user/assistant/tool/error 多 part 渲染，用户消息提供 48dp 重置与复制操作 |
| `SessionRevertDock` | 回滚数量、首条预览、逐步恢复、全部恢复和新分支提交提示 |
| 上下文用量 | token、使用率、成本；短摘要优先 anchored popup |
| `PermissionDock` | 底部阻塞式权限确认，显示工具说明、patterns、拒绝/始终允许/允许一次 |
| 问题交互 | 多问题、多选、自定义输入；内容较长时使用 sheet |
| `ProjectFilePanel` | 项目级右侧覆盖面板、懒加载目录、文件搜索、树/内容单页切换 |
| `OpenCodeCodeViewer` | 只读文本、行号、Prism4j 高亮、纵向虚拟列表和长行横向滚动 |
| 无障碍语义 | 48dp 目标、选择 role、本地化展开/折叠说明、非颜色状态提示和 TalkBack 服务器排序操作 |

### 19.4 状态同步

Android 应同时处理拉取和事件：

1. 首次进入项目并行拉取 `/path`、`/provider`、`/mcp`、`/session`、`/agent`、`/config`、`/session/status`、`/vcs`、`/command`、`/permission`、`/question`。
2. 建立 `/global/event` 或项目 `/event` SSE。
3. SSE 收到 `session.created`、`session.updated`、`session.deleted`、`permission.asked` 等事件后更新 store。
4. 周期性或前后台恢复时调用 `/global/health`。
5. 发送 prompt 做乐观 UI，失败回滚。
6. `session.updated` 同步 revert marker；暂存回滚只改变消息投影，发送新分支后的 `message.removed`、`message.part.removed` 才清理 Store 历史。

STCP 模式在步骤 1/2 之前还有统一连接屏障：`StartSession -> EnsureVisitor -> WaitVisitorReady -> /global/health`。同 tunnel generation 的调用共享 single-flight；相同 control epoch 可直接复用，重连到新 epoch 后重新校准。该屏障只应用于 STCP，直连和 SSH 保持原有连接语义。

SSE 打开、关闭和重试必须带 owner lease、generation、source 和 transport identity 校验，关闭后的迟到任务不得重新发布 `Open`。快照刷新失败不得阻断 EventSource 重连，每次恢复只执行一次可合并的 REST 校准；校准期间的事件缓冲必须同时限制数量和估算字节数，溢出时取消该快照并立即顺序重放，不能无界占用内存。能力变化在已有校准期间只设置 dirty，当前轮结束后最多补一轮，重放本身不能形成无限校准循环。应用前台恢复按 server 合并健康/readiness 检查；transport identity 变化时重建该服务器所有仍需保持的流，否则保留已打开流并为每个项目发起一次可合并校准。ViewModel 直接项目快照使用项目数据 revision CAS，并用 latest-request gate 阻止旧刷新写入 loading/error/最终数据；消息 GET 使用独立消息 revision，在并发 SSE 时合并历史与实时消息/parts，实时文本和 delta 优先，等 revision 替换仍保留 REST 缺失的本地乐观消息及 parts，空实时文本从最终有效 parts 重建，并用删除 revision 防止旧 GET 复活已删除 message/part/session 数据。错误写入 Store/UI 前必须脱敏。workspace 启用后，受控创建的项目状态 key 必须同时包含 directory 与 workspace，Windows 盘符形式按比较键大小写不敏感；全局连接状态按 serverId 隔离，prompt 的乐观插入、实体化、移动、接受和回滚必须落在同一 workspace 分支。

Repository 调用使用 `OpenCodeFailure` 分类失败，SSE 状态保存 `SseFailureReason`，快照协调返回 `ProjectSnapshotOutcome.Success` 或 `.Failure`。UI 将语义原因转换为本地化 `UiText.Resource`，只有未知失败才使用当前操作的本地化 fallback；取消与 JVM `Error` 原样传播。Bridge unavailable/API mismatch 和入站策略违规属于永久启动/协议失败，不是瞬时重连信号。

### 19.5 安全与脱敏

必须脱敏：

| 数据 | 处理 |
| --- | --- |
| `/global/config` Provider auth/options/headers | 立即投影为安全的身份/模型/Header 名/disabled 摘要，原始响应不进入 UI、Store 或日志 |
| `/config` provider/env/auth | UI 不展示，日志 `<redacted>` |
| Provider API key 与自定义 Header value | 使用 password field，只在编辑期间保留于内存，发送到 OpenCode Server auth/global config，绝不持久化到 Android Keystore；已提交、部分提交或结果未知后清除，Direct 非本机回环 HTTP 需要冻结确认 |
| Provider OAuth URL 与 callback | 只允许带有效 host 且无 userinfo 的 HTTP(S)，保持 scope/method index，auto callback 最长十分钟且可取消，并提示远程 loopback 拓扑限制 |
| SSH/STCP 隧道凭据 | password field，Keystore 保存，日志 `<redacted>` |
| SSH host fingerprint | 通过 `HostKeyRepository` 或等价机制在用户认证前校验，禁止关闭严格校验后再补验 |
| Server base URL | 只允许无 userinfo/query/fragment 的 HTTP(S) URL；异常旧值不在 UI 回显，自由文本日志中的 URL userinfo/fragment 统一脱敏 |
| SSH 私钥文件/文本 | IO dispatcher 有界读取，UTF-8 最多 256 KiB；保存与 JSch 前再次校验 |
| frp native state/error | token、secret、原始配置值必须在 Go/Kotlin 边界前脱敏，provenance 和 API signature 不包含凭据 |
| 请求 headers | 禁止写入 crash report |
| URI/ResponseBody/Base64/native 数据 | 普通 Retrofit/file 响应分别实施 16 MiB encoded-body 与 decoded-entity 上限，session messages direct transport 分别实施 64 MiB 上限；SSE 请求 identity encoding，并对该 representation 的行/event 实施 32 MiB 上限。这些上限不等于 heap 峰值，converter 与并发快照仍可能分配更多内存，`/file/content` 保留 reader 层 decoded 防御。附件同时限制单文件、数量和总字节数，其他潜在大 REST 响应继续逐端点审计 |
| 敏感或大型 value object 摘要 | `toString()` 只输出结构化数量/状态，不展开凭据、URL/endpoint、alias、路径、prompt、Base64、SSE payload 或 tool output；敏感字段严格使用 `<redacted>`，且不改变 serialization、`copy`、equals 或 hashCode |
| 分享链接 | 用户主动复制/分享才暴露 |

## 20. 当前能力与验收状态

| 项目 | 状态 | 当前验收说明 |
| --- | --- | --- |
| 最近项目 | 已具备 | 能选择规范化路径，不会因 slash 和 Windows 盘符大小写差异重复；导航与 best-effort 有序 DataStore 记录解耦，抽屉 rail 提供同服务器 MRU 项目的有界滚动和项目首页切换 |
| 项目加载 | 已具备 | 展示路径、分支、Store 共享且支持网络加载更多/重试/末尾状态的根会话窗口和 composer |
| 会话详情 | 已具备 | 显示标题、消息、上下文用量和会话菜单 |
| 项目文件 | 已具备 | 文件面板、目录懒加载、搜索、有界文本/图片/不可预览状态、预览，以及最多 10 个完整文件的 Composer 上下文选择均已具备 |
| 更改审查 | 部分完成 | 会话详情 Changes tab 可显示 Git/session diff；独立 Review route 仍为占位 |
| Composer | 主体具备 | slash/@/本机附件/项目文件上下文/agent/model/variant、纯上下文发送、Store 权威 capability 校验、已加载命令分流、附件/上下文终检和 send/abort/revert single-flight 已具备；Shell mode 未实现 |
| 会话重置 | 已具备 | 支持 revert/unrevert、逐步/全部恢复、新分支消息投影、纯附件或纯项目上下文重置，以及可恢复内容完整性检查 |
| 新会话 | 已具备 | 复用 `SessionDetailScreen` 的 `"new"` 状态，首次发送才创建 session |
| 设置 | 部分完成 | 通用、后台、服务器、Provider、自定义 Provider、模型均为单列路由；完整快捷键和部分 Web 设置未完成 |
| Server | 主体具备 | 直连/SSH/STCP 互斥、STCP readiness、类型化永久 bridge 启动失败、SSH 认证前 host key 校验、Server URL 约束和私钥 256 KiB 边界已实现 |
| Provider | 主体具备 | 真实安全投影列表/搜索、动态 API/OAuth 认证、断开、capability 刷新，以及分阶段的多模型/多 Header 自定义 Provider 持久化已接通；真实 Provider 兼容性、远程 loopback OAuth 拓扑和设备交互仍需验证 |
| 模型设置 | 部分完成 | enabled/hidden 是按 server 保存的本机过滤偏好，不修改 OpenCode server config |
| SSE | 主体具备 | identity content encoding、OkHttp Call 自定义流式 reader、200 + `text/event-stream` 协议门禁、空行 dispatch/EOF 丢弃、32 MiB 行/event 硬上限、owner lease、关闭终态、generation/source/transport 校验、指数退避、快照 single-flight、有界事件重放和应用级前台恢复已实现；仍需真实服务长时间断网、进程前后台和 STCP epoch 切换验证 |
| STCP 并发与恢复 | 主体具备 | readiness、generation/control epoch 与 AAR 门禁已建立，仍需真实设备 native load 和 STCP 闭环验证 |
| 失败处理 | 主体具备 | Repository/SSE/快照链路保留类型化语义原因，映射到带操作 fallback 的本地化资源，并传播取消/JVM `Error` |
| 移动 UI 与无障碍 | 主体具备 | 48dp 目标、匹配选择 role、本地化展开/折叠说明、非颜色状态提示、TalkBack 服务器排序、浅色/深色对比度测试和大字体/IME 有界布局已实现；设备 instrumentation 尚未成为 CI 门禁 |
| 安全与输入边界 | 主体具备 | Keystore、结构化/自由文本 Redactor、安全结构化摘要、Server URL 防 userinfo、私钥有界读取、附件 data URL/预算终检、Retrofit/file 独立 16 MiB encoded/decoded、session messages direct OkHttp 独立 64 MiB encoded/decoded 与 identity SSE 32 MiB 入站边界已具备；这些上限不等于 heap 峰值，其他 REST 大响应、native 返回值与真实设备链路仍需持续审计验证 |

## 21. 后续实现优先级

| 优先级 | 内容 |
| --- | --- |
| 高 | SSE 真实服务长时间断网、前后台切换、全局/项目重复事件及 STCP epoch 切换验证 |
| 中 | 在支持的真实 Server/Provider 版本上验证 Provider 管理，重点覆盖远程 loopback OAuth、长回调取消和自定义配置 partial/unknown outcome |
| 中 | 独立 Review route 产品决策与 session share |
| 中 | 继续审计 native/图片解码等剩余外部输入边界，并在真实设备验证 SSH/STCP 与大附件失败路径 |
| 中 | 完整 Settings 与剩余 loading/empty/error 重试状态 |
| 中 | 为紧凑屏幕、200% 字体、IME、TalkBack、浅色/深色主题和模型导航矩阵增加设备 instrumentation |
| 后续 | Shell mode、workspace/worktree、PTY/terminal |
| 条件触发 | Room、Hilt 和业务多模块；仅在需求明确且经确认后评估 |

历史 P0/P1/P2 只用于理解功能演进，不再代表当前完成度。
