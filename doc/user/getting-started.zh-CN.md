# 首次使用

[English](getting-started.md)

本文档是英文 canonical 文档的完整便利翻译。中英文发生差异时，以英文文档为准。

这是从新安装 OC Deck 到发送首条普通 prompt 的最短完整路径。详细连接字段见[连接方式](connections.zh-CN.md)，恢复步骤见[排障](troubleshooting.zh-CN.md)。

## 1. 准备前置条件

打开 OC Deck 前，请确认具备：

- Android 8.0/API 26 或更高版本，并已安装匹配的 APK。见[安装与更新](installing.zh-CN.md)。
- 你自行运行或信任的 OpenCode Server。OC Deck 不负责安装或启动服务端。
- Android 设备到服务端的直连、SSH 或 STCP 网络路径。
- 所选工作流需要时，已在 OpenCode Server 配置 Provider 和模型访问能力。
- OpenCode Server 实际看到的项目目录，而不是 Android 本地路径。

## 2. 添加服务器配置

1. 启动 OC Deck。应用会打开“服务器”页面。
2. 选择“添加服务器”。
3. 选择“直连”、“SSH 转发”或“frpc STCP visitor”。
4. 输入 OpenCode 服务地址及该方式要求的字段。
5. 如果服务端使用 Basic 认证，同时输入 OpenCode 用户名和密码。
6. 保存配置。

保存只会存储配置，不会测试连接。如果保存结果提示不确定，应先返回或刷新服务器列表再重试；第一次写入可能已经成功。

## 3. 运行健康检查

在服务器卡片上选择“健康检查”。

- 成功表示 OC Deck 已经通过所配置的直连、SSH 或 STCP 路径访问并解析 `/global/health`。
- 失败表示连接路径、TLS、凭据、隧道或 endpoint 需要修正。
- 成功不代表全部 OpenCode endpoint、Provider、模型或 SSE 行为都兼容。

打开项目前应先解决健康检查失败。见[排障](troubleshooting.zh-CN.md#2-健康检查与服务器连接)。

## 4. 打开服务端项目

1. 打开健康状态正常的服务器配置。
2. 在“打开项目”页面选择最近项目，或输入项目路径。
3. 使用 OpenCode Server 访问项目时看到的路径，例如 `/workspace/sample-project` 或 `X:/workspace/sample-project`。
4. 选择“打开项目”。

项目选择器会规范化路径，但手工输入的路径通常要到请求项目快照时才实际验证。输入 Android 文件路径或 OpenCode Server 无法访问的目录，会在加载项目时失败。

项目打开后，OC Deck 会加载 REST 快照并启动 global 和 project SSE 同步。短暂的 SSE 连接状态不会删除已经加载的 UI 数据。

项目选择页和导航抽屉按服务器共享同一份已保存项目顺序。新添加项目默认置顶，点击已有项目进入时不会改变其位置。长按项目选择页的卡片正文或 Drawer 项目按钮可纵向拖拽排序；列表较长时支持边缘滚动，“打开项目”和“设置”保持固定。TalkBack 用户可使用“上移项目”和“下移项目”自定义操作。

## 5. 新建会话

1. 选择“新建会话”。
2. 等待 prompt capabilities、模型和 Agent 加载。
3. 选择有效模型。
4. 选择 Composer 支持的 Agent：`build` 或 `plan`。
5. 只有当前模型提供 reasoning variant 时才选择；不确定时使用“默认”。

新会话页面最初只是本地占位。OC Deck 直到第一次发送时才创建后端 session。

## 6. 发送首条 Prompt

输入一条普通文本 prompt，例如：

```text
在不修改文件的前提下总结这个项目的结构。
```

也可以打开“添加附件”并选择“从项目选择”，浏览或搜索当前服务端项目、预览文件，并确认最多 10 个完整文件上下文。这些内容保持为服务端引用，不会变成手机本地附件。

然后选择发送。文本、手机本地附件或项目文件上下文至少需要一项非空。

对于新会话，OC Deck 会按顺序执行：

1. 在本地时间线中乐观插入用户消息。
2. 调用 `POST /session` 获取真实 session ID。
3. 将本地消息和导航切换到真实 session。
4. 调用 `POST /session/{sessionID}/prompt_async`。
5. 等待 SSE 或后续消息刷新提供 assistant 回复。

提交成功不代表完整 assistant 回复已经到达。请保持项目打开，让 SSE 继续传递更新。

## 7. 从首次发送失败中恢复

| 结果 | OC Deck 保留的状态 | 处理方式 |
| --- | --- | --- |
| OC Deck 未收到 session 创建确认 | 草稿保留在新会话中；仍为 optimistic 的消息会从本地删除；如果响应丢失，服务端仍可能已经创建 session | 重试前先刷新并检查项目 session 列表；然后按需修正连接、认证或服务端问题 |
| 真实 session 已存在，但 OC Deck 未收到 prompt 提交确认 | 真实 session 和草稿保留；如果 SSE 尚未确认消息，其 optimistic 副本会从本地删除；如果响应丢失，服务端仍可能已经接受 prompt | 重试前先刷新并检查该 session 的消息，然后在真实 session 中处理 |
| HTTP 报错前 SSE 已确认消息 | 已确认消息保留，结果标记为不确定 | 发送前先刷新并检查 session，避免重复 prompt |
| Prompt 已提交但没有 assistant 回复 | 用户消息保留；SSE 可能断开或延迟 | 等待重连，必要时刷新 session |
| 模型、Agent、variant 或 capability revision 已过期 | 请求会在提交前或提交中被拒绝 | 等待 capabilities 重新加载，并选择当前可用项 |

## 8. 确认闭环完成

满足以下条件时，基础首次使用闭环完成：

- 服务器健康检查成功。
- 目标服务端项目可以打开。
- Session 获得真实服务端 ID，并出现在项目 session 列表中。
- 用户消息保留在时间线中。
- Assistant 回复通过 SSE 或手工刷新到达。

任何步骤失败时，使用按阶段组织的[排障](troubleshooting.zh-CN.md)。不要在公开报告中分享原始服务器 URL、凭据、私有项目路径、prompt 或完整响应；敏感值替换为 `<redacted>`。
