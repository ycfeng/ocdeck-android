# 连接方式

[English](connections.md)

本文档是英文 canonical 文档的完整便利翻译。中英文发生差异时，以英文文档为准。

OC Deck 是客户端，不负责安装、启动或管理 OpenCode Server。添加配置前，应自行运行或获取某个 OpenCode Server 的访问权限，并确定 Android 设备如何访问它。

## 1. 选择连接方式

| 方式 | OpenCode 服务地址的含义 | 额外要求 |
| --- | --- | --- |
| 直连 | Android 设备可以访问的 HTTP(S) URL | Android 到 OpenCode Server 的网络路径 |
| SSH 转发 | SSH 远端主机可以访问的 HTTP(S) URL | SSH 账号和已校验的 SSH host key |
| frpc STCP visitor | 本地重写后保留的 scheme 和 base path；真实目标由服务端 STCP proxy 选择 | 已存在的 frps 和服务端 STCP proxy |

SSH 和 STCP 会在 Android 设备上创建 `127.0.0.1:<local-port>` listener。OC Deck 会把 REST 和 SSE 都重写到这个本地地址。

## 2. 所有方式的共同规则

打开“服务器”，选择“添加服务器”。初始选择为“直连”，连接名称和直连 URL 均为空。选择 SSH 或 STCP 时，如果 URL 仍为空，会填入 `http://127.0.0.1:4096`，但不会覆盖已经输入的值。

OpenCode 服务地址必须：

- 使用 `http://` 或 `https://`。
- 包含有效 host。
- 不包含 userinfo、query 或 fragment。
- 仅在 OpenCode 部署需要时使用 base path。

“OpenCode 用户名”和“OpenCode 密码”是 HTTP Basic Auth 凭据。只有两项都非空白时，OC Deck 才发送 Basic 认证。这些凭据与 SSH 认证、frps token 和 STCP secret 相互独立。

保存操作只校验并存储配置，不能证明网络连接可用。保存后返回“服务器”，运行“健康检查”。检查成功只说明 `/global/health` 可以通过该配置访问，不代表全部 OpenCode API、模型或 Provider 操作都兼容。

敏感值通过 Keystore 支持的凭据存储保存，不会再次以明文显示。编辑已有配置时，将已经保存的敏感字段留空通常表示保留当前值。

## 3. HTTP(S) 直连

当 Android 设备无需应用管理的隧道即可访问 OpenCode Server 时，使用直连。

1. 选择“直连”。
2. 输入 Android 设备可访问的 URL，例如部署使用的 DNS 名称和端口。
3. 如果服务端要求 OpenCode Basic 认证，同时输入用户名和密码。
4. 保存配置。
5. 运行“健康检查”。

重要的地址行为：

- 在 Android 真机上，`localhost` 和 `127.0.0.1` 指 Android 设备自身，不是开发电脑。
- 模拟器 alias、`adb reverse`、VPN 和端口映射都取决于实际环境，不是通用默认值。
- 服务端必须监听可通过所选网络、防火墙、VPN 或反向代理访问的接口。

流量经过不可信网络时，应优先使用正确配置的 HTTPS。非回环直连配置同时使用 `http://` 和有效 OpenCode Basic 认证时，OC Deck 会在保存前显示警告。Android Keystore 保护本地保存的密码，但不会加密明文 HTTP 流量。

## 4. SSH 转发

当 Android 可以访问某台 SSH 主机，且该主机可以访问 OpenCode Server 时，使用 SSH。

### 字段

| 字段 | 含义 |
| --- | --- |
| OpenCode 服务地址 | SSH 主机可以访问的地址；OpenCode 运行在该主机时，通常是 `http://127.0.0.1:4096` |
| SSH 主机/端口 | SSH endpoint；默认端口 `22` |
| SSH 用户名 | SSH 账号名 |
| 认证方式 | 密码、私钥、密码 + 私钥 |
| SSH 密码 | 所选方式包含密码时必填 |
| 私钥 | 粘贴的内容或本地选择的 UTF-8 key 文件，上限 256 KiB |
| 私钥 Passphrase | 加密私钥的可选 passphrase |
| 本地转发端口 | Android loopback 端口，默认 `4096` |
| 连接超时 | 默认 `10` 秒 |
| KeepAlive 间隔 | 默认 `30` 秒 |
| Host key 校验 | Trust On First Use 或手工固定 fingerprint |

### 配置步骤

1. 选择“SSH 转发”。
2. 按 SSH 主机看到的地址填写 OpenCode URL。
3. 填写 SSH 主机、端口、用户名和认证材料。
4. 除非与其他 listener 冲突，否则保留本地端口 `4096`。
5. 选择 host key 处理方式。
6. 保存并运行“健康检查”。

**Trust On First Use（TOFU）**会在用户认证前自动捕获并保存第一次观察到的 SSH host key，不会弹出交互式 fingerprint 确认。后续 key 变化会被拒绝。

如需更强的首次连接校验，应通过独立可信渠道获取 fingerprint，并使用“固定 Fingerprint”。敏感 fingerprint 示例必须写成 `<redacted>`。如果服务器合法轮换 key，应先独立核验新 fingerprint，再输入新的固定值。不要绕过 mismatch，也不要期望对同一 endpoint 重新选择 TOFU 就会静默接受变化后的 key。

OC Deck 只保留选中的 key 内容和显示文件名，不保留原始文件路径。私钥 UTF-8 内容上限是 256 KiB。

### HTTPS 限制

SSH 会保留 URL 的 scheme 和 path，但把 host 和 port 重写为 `127.0.0.1:<local-forward-port>`。如果 HTTPS 证书只签发给原始远端 hostname，经过重写后通常会因为标准 hostname 校验失败。OC Deck 不提供自定义 CA，也不能关闭证书或 hostname 校验。应使用证书对有效 endpoint 生效的部署方式，或选择其他正确加固的拓扑。

## 5. frpc STCP Visitor

只有服务端 STCP proxy 已存在并指向 OpenCode Server 时，才使用此方式。OC Deck 不会创建 frps 服务或服务端 proxy。

### 字段

| 字段 | 含义 |
| --- | --- |
| OpenCode 服务地址 | 本地重写后保留的 scheme 和 base path；其中的 host 和 port 不负责选择服务端 STCP 目标 |
| frps 地址/端口 | 可访问的 frps hostname 或 IP 与端口；默认端口 `7000` |
| frps Token | 可选的 frps 认证 token |
| frpc User | 可选的 frp session user |
| STCP Server User | 可选的服务端 proxy owner 或 namespace |
| STCP Server Name | 精确的服务端 STCP proxy 名称 |
| STCP Secret Key | 精确匹配的 visitor shared secret |
| 本地绑定端口 | Android loopback 端口，默认 `4096` |
| Wire Protocol | 默认为 `v1`；只有部署明确要求时才使用 `v2` |

### 配置步骤

1. 确认服务端 STCP proxy 可以访问 OpenCode Server，并且其目标 `/global/health` 可用。
2. 选择“frpc STCP visitor”。
3. 输入 frps endpoint 以及所需 token 或 user。
4. 输入与 proxy 完全一致的 server user、server name 和 secret。
5. 选择空闲的本地绑定端口。
6. 保存并运行“健康检查”。

运行时，OC Deck 会启动 visitor，等待当前 frps control epoch 中的 listener，再通过本地隧道检查 `/global/health`。只有 readiness 流程成功后，REST 和 SSE 才可使用。冷启动可能需要数秒。

STCP secret 用于授权 visitor，不能把它描述为 OpenCode 流量的端到端加密。应按实际部署安全要求保护 frps 和 OpenCode transport。STCP 把 URL 重写到 `127.0.0.1:<local-bind-port>` 后，同样存在 SSH 一节所述的 HTTPS loopback hostname 限制。

Bridge 制品已经过大量静态验证，但真机 native load、16 KiB page-size 运行和真实 STCP 端到端路径，在记录设备证据前仍为 `Unknown`。详见[兼容性](compatibility.zh-CN.md)。

## 6. 凭据边界

| 凭据 | 用途 |
| --- | --- |
| OpenCode 用户名和密码 | 连接路径就绪后，向 OpenCode Server 发送 HTTP Basic Auth |
| SSH 密码/私钥/passphrase | 只用于 SSH 服务器认证 |
| SSH host fingerprint | 在用户认证前校验 SSH 服务器身份 |
| frps token 和 frpc user | 认证或标识 frp client session |
| STCP server name/user/secret | 选择并授权服务端 STCP proxy |

不要在公开 issue 中粘贴真实凭据、私钥、fingerprint、私有 URL 或完整配置。使用 `<redacted>`。

## 后续步骤

- 按[首次使用](getting-started.zh-CN.md)打开项目并发送首条 prompt。
- 连接、TLS、SSH、STCP、SSE 或通知失败时使用[排障](troubleshooting.zh-CN.md)。
