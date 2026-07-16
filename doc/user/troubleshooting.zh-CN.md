# 排障

[English](troubleshooting.md)

本文档是英文 canonical 文档的完整便利翻译。中英文发生差异时，以英文文档为准。

请按顺序排查：安装、健康检查、项目加载、prompt 提交、SSE、通知。修复前一阶段的问题通常也会解决后续症状。

不要公开真实密码、token、私钥、host fingerprint、私有服务器 URL、项目内容、prompt、Authorization header 或完整响应。敏感值替换为 `<redacted>`。

## 1. 安装与启动

确认：

- Android 为 API 26 或更高版本。
- APK 与设备 ABI 列表中第一个受支持值匹配。
- APK 来自真实存在的 canonical GitHub Release，而不是源码归档或镜像。
- 如果已有安装，其签名与新 APK 兼容，并且已安装版本不高于待安装 APK。

厂商安装器只显示“应用未安装”时，可通过 ADB 获取具体安装错误。见[安装与更新](installing.zh-CN.md#9-常见安装错误)。

## 2. 健康检查与服务器连接

保存服务器配置不会测试连接。打开项目前，先在“服务器”页面运行“健康检查”。

### 无法访问服务器

1. 确认 OpenCode Server 正在运行，并且 `/global/health` 可以从 OC Deck 使用的网络侧访问。
2. 确认配置的 host、port、scheme 和可选 base path 正确。
3. 在真机上替换误填的直连 `localhost` 或 `127.0.0.1`；它们指手机本身。
4. 检查 Wi-Fi/移动网络路由、VPN、防火墙、反向代理，以及服务端是否监听可访问接口。
5. 对 SSH，从 SSH 主机测试 OpenCode URL。
6. 对 STCP，从服务端 proxy 一侧测试目标，并确认 Android 可以访问 frps。

### HTTP 401 或 403

OC Deck 当前只支持 OpenCode HTTP Basic Auth。只有用户名和密码都非空白时，才发送 Authorization header。

1. 编辑服务器配置，核对两项 OpenCode Basic 字段。
2. 不要把 SSH 凭据、frps token 或 STCP secret 填入 OpenCode 字段。
3. 检查部署是否要求 OC Deck 当前不支持的 Bearer、OAuth、Cookie 或其他登录方式。
4. 保存，运行“健康检查”，再重新打开项目。

SSE 会把 401 和 403 视为永久失败，不会持续重试无效凭据。

### TLS 或证书失败

OC Deck 使用 Android 和 OkHttp 的常规证书信任及 hostname 校验。它不提供自定义 CA 导入、证书绕过、hostname 绕过或“不安全继续”选项。

检查：

- 自签名或其他不受信任的 CA。
- 已过期或尚未生效的证书。
- 缺少中间证书。
- DNS 名称与证书 SAN 不匹配。
- SSH/STCP 把 URL 重写到 `127.0.0.1`，而证书只对原始 hostname 有效。

应修复证书、证书链、DNS 名称或部署拓扑，不要通过关闭校验绕过错误。只有在明确接受网络风险时才考虑直连 HTTP；通过 HTTP 发送的 Basic 凭据没有传输加密。

### HTTP 404 或 5xx

- `404` 通常表示 base path 错误或服务端 endpoint 不兼容。
- `5xx` 表示服务端或上游失败；检查已脱敏的服务端诊断信息。
- `/global/health` 成功只能证明可达，不代表全部 OpenCode API 都兼容。

## 3. SSH Host Key 与隧道

### 首次连接失败

1. 核对 SSH host、port、username 和网络可达性。
2. 核对所选密码/私钥认证方式。
3. 确认私钥内容是有效 UTF-8，且不超过 256 KiB。
4. 核对加密私钥的 passphrase。
5. 检查本地转发端口是否已被占用。
6. 从 SSH 主机确认所配置的 OpenCode 服务地址可访问。

### Host Key Mismatch

不要绕过 mismatch。它可能表示服务器变化、合法 key 轮换、host 填错或中间人攻击。

1. 停止连接。
2. 通过独立可信渠道核验新的 host fingerprint。
3. 如果确认是合法轮换，编辑配置并输入核验后的新“固定 Fingerprint”。
4. 再次运行“健康检查”。

TOFU 会自动保存第一次 key，不是用于批准后续每次变化的提示。不能把对同一 endpoint 重新选择 TOFU 当作安全轮换流程。

### 隧道已打开但 HTTPS 失败

SSH 会把有效 URL 重写为 `127.0.0.1:<local-port>`。只签发给远端 hostname 的证书通常不匹配该 loopback host。应使用证书和拓扑对有效 endpoint 生效的部署，或选择其他正确加固的连接方式。

## 4. STCP Visitor

按以下顺序检查：

1. 服务端 STCP proxy 已存在，并指向目标 OpenCode 服务。
2. Proxy 一侧可以访问目标 `/global/health`。
3. Android 可以访问配置的 frps 地址和端口。
4. frps token、frpc user 和 wire protocol 与部署一致。
5. STCP server user、server name 和 secret 与服务端 proxy 完全一致。
6. 本地绑定端口空闲。
7. 当前构建包含可用 native bridge。
8. 重试前允许有界冷启动 readiness 流程完成。
9. frps 重连或 control epoch 变化后，连接稳定时重新运行“健康检查”。

Readiness 期间，瞬时 connection refused/reset/timeout、unhealthy 响应、HTTP 408/425/429 和 5xx 可能重试。DNS、TLS、health 数据格式错误、认证、地址和配置错误不会进入无界重试。

真实 Android 真机 STCP 端到端行为目前还不是广泛兼容性声明。将设备专项失败判断为异常前，请查看[兼容性](compatibility.zh-CN.md)。

## 5. 项目无法打开

1. 先确认健康检查成功。
2. 输入 OpenCode Server 看到的目录，不要输入 Android 本地存储路径。
3. Windows 示例使用正斜杠规范化，例如 `X:/workspace/sample-project`。
4. 确认目录存在，并且 OpenCode Server 进程有权访问。
5. 检查 Basic 认证和服务端日志中的 snapshot endpoint 错误。
6. 手工输入的路径可能到 OC Deck 请求项目快照时才失败。

OC Deck 通过 OpenCode Server API 浏览和选择项目文件。项目上下文保持为经过校验的服务端引用；Android 本地文件选择器只用于手机本地附件，不用于打开或附加服务端项目文件。

## 6. Prompt 无法发送或结果不确定

### 发送按钮不可用

确认：

- 文本、手机本地附件或项目文件上下文至少有一项非空。
- 项目文件上下文不超过 10 个，且每个文件仍属于当前项目。
- Prompt capabilities 已加载。
- 已选择当前可用模型。
- 所选 Agent 是 Composer 支持的 `build` 或 `plan`。
- 所选 reasoning variant 仍由该模型提供。

如果 capabilities 在编辑期间变化，应等待重新加载并选择当前值后再重试。

### 新 Session 创建失败

OC Deck 未收到 `POST /session` 已完成的确认。应用会从本地删除仍为 optimistic 的消息，并在新会话中保留草稿。即使应用报告失败，响应丢失仍可能在服务端留下 session。重试前先刷新并检查项目 session 列表，然后按需修正连接、认证或服务端问题。

### Session 已存在但 Prompt 失败

如果 `POST /session` 成功后 prompt 提交报告失败，OC Deck 会保留并打开真实 session，同时保留草稿。如果 SSE 尚未确认消息，应用会删除其仍为 optimistic 的本地副本。Prompt 响应丢失时，服务端仍可能已经接受请求。重新发送前先刷新并检查该 session 的消息，然后再从真实 session 中处理。

### 结果不确定

如果 HTTP 路径报错前 SSE 已确认消息，OC Deck 会保留已确认消息。重新发送前先刷新并检查 session；立即重试可能造成重复 prompt。

## 7. SSE 与实时更新缺失

OC Deck 使用 global 和 project SSE stream。断开连接不会清空已有 UI 数据。重连后，OC Deck 会通过 REST 快照校准状态。

连接类 I/O 失败、HTTP 408/409/425/429 和 5xx 会进行有界自动重试。以下是常见终止失败：

- 401/403 认证失败。
- 重试列表之外的其他非 200 响应。
- TLS 或 hostname 校验失败。
- 响应 Content-Type 不是 `text/event-stream`。
- 服务端返回非 identity 的 SSE content encoding，或 SSE line/event 超过 32 MiB identity response-body 上限。

UI 可能只显示通用“失败”，而不显示内部详细原因，并且当前没有显式“重新连接”按钮。恢复顺序：

1. 必要时编辑并修正服务器配置。
2. 运行“健康检查”。
3. 返回项目或重新打开项目，让 REST 快照和 SSE 重新启动。
4. 对服务端结果不确定的操作，应先刷新 session，再决定是否重复执行。

## 8. 通知没有到达

OC Deck 通知是对本地 SSE 事件的反应，不是 FCM push。Android 杀死应用进程后，不能把它视为可靠通知。当前没有 foreground service、WorkManager 连接或其他持久后台 transport。

按以下顺序检查：

1. 确认应用进程仍存活，并且项目 SSE 连接正在运行。
2. Android 13/API 33 及以上，打开“设置 > 后台运行设置”并授予通知权限。
3. 在“设置 > 通用”确认对应的 Agent、权限或错误通知开关。Agent 和权限默认开启，错误默认关闭。
4. 在系统设置中确认应用通知及对应的“智能体提醒”“权限提醒”或“错误提醒”channel 已启用。
5. 测试时离开受影响的 session 页面；正在查看同一 session 时，OC Deck 会抑制该 session 的通知。
6. 在“后台运行设置”检查电池优化和厂商后台限制。
7. 如果厂商提供“锁定应用”或保留最近任务功能，启用它以降低一键清理终止应用的概率。

低于 API 33 的 Android 不需要运行时通知权限，但系统级应用或 channel 设置仍可能抑制通知。v2 channel 默认静音，以便 OC Deck 只播放一次 App 内所选音效；如果用户在 Android 设置中显式选择了 channel 声音，则系统声音优先。OC Deck 中选择“无”只关闭 OC Deck 自己的播放，不覆盖 Android 中显式选择的 channel 声音。电池优化豁免只能降低中断概率，不能保证进程死亡后继续送达。

## 9. 不能用于恢复的未实现功能

当前应用不提供：

- 应用内更新器或自动回退。
- OpenCode Server 的 Bearer、OAuth、Cookie 或 API-key 登录。
- 自定义 TLS CA 导入或证书/hostname 绕过。
- 显式 SSE 重连按钮或详细 SSE 失败页面。
- 进程死亡后的可靠 push 通知。
- 受支持的设置/凭据导出与恢复流程。

## 10. 报告问题

问题仍未解决时，按[支持](../../SUPPORT.zh-CN.md)说明报告。只提供不敏感信息：OC Deck 版本或 commit、Android 版本、设备型号和 ABI、OpenCode Server 版本、连接方式、失败阶段、精确但不敏感的错误文本和最小复现步骤。
