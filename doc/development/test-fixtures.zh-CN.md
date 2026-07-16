# 测试夹具

[English](test-fixtures.md)

本文档是英文 canonical 测试夹具文档的便利翻译。中英文发生差异时，以英文文档为准。

## 当前方式

仓库当前没有共享 `fixtures/` 或 `testdata/` 目录。测试通常使用小型内联 JSON、内存 fake、本地 OkHttp 测试响应、合成字节流和由程序生成的边界数据。除非共享 fixture 明显更清晰且可复用，否则继续使用这种方式。

Fixture 是测试输入，不是生产数据抓取。OC Deck 原创 fixture 及其文档使用与仓库相同的 [MIT License](../../LICENSE)。从第三方复制或改编的 fixture 必须保留来源、许可证和必要声明，并在加入前完成审查。

## 数据规则

- 使用 `ses_123`、`msg_1` 等合成标识，以及不可路由或本地示例端点。
- 只有在测试脱敏或凭据处理时才使用明显为人工合成的 secret 值，并断言异常、日志、alias、序列化配置和 `toString()` 都不包含这些值。
- 预期用户可见输出使用 `<redacted>`。不得粘贴真实 API key、密码、token、cookie、私钥、provider header、环境变量、host fingerprint 或签名 URL。
- 将私有项目路径和名称替换为最小示例。不得把本地用户目录、源码、prompt、会话内容或完整服务端响应复制进测试。
- 时间戳、UUID、顺序、随机种子和受 locale 影响的值必须可重复。
- 测试前向容错时应包含未知 JSON 字段。

## 边界 Fixture

大体积或对抗性输入应在测试中生成，不要提交大型二进制或文本文件。

- 对 SSE 行/event、session messages body、文件内容、Base64、附件预算、URI 输入、native 返回值和私钥测试精确上限与 `max + 1`。
- 使用分块或无限合成 source，证明 reader 会停止且不会无界分配。
- 取消测试使用可控的阻塞 fake，不依赖 sleep 或外部服务。
- Race 与 generation 测试应提供确定性 barrier 或 callback，使迟到结果顺序可复现。
- 损坏 payload 应保持最小，使预期失败原因清楚可见。

## 文件 Fixture

只有在内联数据会掩盖行为、必须逐字节保留或多个测试共享同一输入时，才新增文件 fixture。

确需文件 fixture 时：

1. 放在所属模块的 test resources 或命名范围明确的测试数据目录。
2. 文件名描述测试场景，不使用真实项目或客户名称。
3. 非原创合成测试字节必须记录来源和许可证。
4. 文件尽量小，并审查 secret 与个人数据。
5. 被测生产路径有上限时，fixture 也必须通过有界路径加载。

不得把生成的 AAR、APK、keystore、私钥、原始网络抓包、完整日志或生产数据库导出作为 fixture 提交。

## 更新 Fixture

- Fixture 应与要求它变化的行为或 schema 在同一变更中更新。
- 必须检查语义 diff，不能仅因为测试命令生成了新 golden file 就直接接受。
- Fixture 策略或公开测试流程变化时，同步更新中英文文档。
- 修改 fixture 后运行聚焦测试和标准模块门禁。
