# Pull Request / 合并请求

> External pull requests are welcome and are governed by `CONTRIBUTING.md` and `CODE_OF_CONDUCT.md`. / 欢迎外部 pull request；所有贡献均须遵守 `CONTRIBUTING.md` 与 `CODE_OF_CONDUCT.md`。

## Summary / 摘要

<!-- Explain what changed, why, and the user-visible and technical impact. / 说明改动内容、原因，以及用户可见和技术影响。 -->

## Related Issue / 关联 Issue

<!-- Use "Closes #123" when appropriate. / 适用时使用 "Closes #123"。 -->

## Change Type / 改动类型

- [ ] Bug fix / 缺陷修复
- [ ] Feature / 功能
- [ ] Refactor or maintenance / 重构或维护
- [ ] Documentation / 文档
- [ ] Build, dependency, or release / 构建、依赖或发布
- [ ] Security or privacy / 安全或隐私

## Expected Release-Note Label / 预期 Release Note 标签

Select one expected label. These labels must be created and configured in GitHub by maintainers; this template does not create or apply them automatically. / 选择一个预期标签。这些标签必须由维护者在 GitHub 服务端创建并配置；本模板不会自动创建或应用标签。

- [ ] `release-notes/breaking`
- [ ] `release-notes/security`
- [ ] `release-notes/feature`
- [ ] `release-notes/fix`
- [ ] `release-notes/compatibility` or `release-notes/migration`
- [ ] `documentation`, `dependencies`, `release-notes/maintenance`, or `ci`
- [ ] `release-notes/skip` with a reason below

## Verification / 验证

- [ ] I added or updated focused tests for changed behavior. / 我已为变更行为新增或更新聚焦测试。
- [ ] `:app:testDebugUnitTest` passed. / 已通过。
- [ ] `:frpc-stcp-visitor:testDebugUnitTest` passed. / 已通过。
- [ ] `:app:assembleDebug` passed. / 已通过。
- [ ] When this change affects STCP backend selection/Kotlin implementation or variant automation, `:app:testCanaryUnitTest`, `:app:testKotlinReleaseUnitTest`, `:app:assembleCanary`, and `:app:assembleKotlinRelease` passed; unrelated ordinary changes may mark this not applicable below. / 改动涉及 STCP backend 选择/Kotlin 实现或 variant 自动化时，已通过 Canary 与 Kotlin Release-Like 的四个任务；无关的普通改动可在下方说明不适用。
- [ ] I listed any test not run and the reason below. / 我已在下方列出未运行的测试及原因。

<!-- Commands run and results / 已运行命令与结果 -->

## UI and Accessibility / UI 与无障碍

- [ ] Not applicable / 不适用
- [ ] User-visible text uses both Chinese and English string resources. / 用户可见文案已同时使用中英文 string resource。
- [ ] The change was checked in light and dark themes. / 已检查浅色和深色主题。
- [ ] Clickable icons and chip actions retain at least 48 dp touch targets. / 可点击图标和 chip 操作保留至少 48 dp 触控目标。
- [ ] Mobile layout, IME, insets, focus, and system back behavior were checked where relevant. / 已按需检查移动布局、IME、inset、焦点和系统返回行为。
- [ ] Screenshots or recordings are attached for meaningful UI changes. / 实质性 UI 改动已附截图或录屏。

## Security and Privacy / 安全与隐私

- [ ] Inputs remain normalized, bounded, and redacted where applicable. / 相关输入继续满足规范化、有界读取和脱敏要求。
- [ ] Credential, host-key, URL, REST/SSE, attachment, and logging impacts were reviewed. / 已审查凭据、host key、URL、REST/SSE、附件和日志影响。
- [ ] Privacy behavior and user-visible disclosures were updated when required. / 需要时已更新隐私行为和用户可见披露。
- [ ] No vulnerability details that require private handling are disclosed in this PR. / 本 PR 未披露需要私密处理的漏洞细节。

## Bridge Gate / Bridge 门禁

- [ ] Not applicable; this change does not touch either STCP backend, App backend selection, the bridge, patched frp, or bridge version inputs. / 不适用；本改动不涉及任一 STCP backend、App backend 选择、bridge、patched frp 或 bridge 版本输入。
- [ ] `go run ./cmd/preparefrp` passed in `frpc-stcp-visitor-go/`. / 已通过。
- [ ] Wrapper and patched frp client `go test -race` gates passed. / wrapper 与 patched frp client 的竞态测试已通过。
- [ ] `build-aar.sh` or `build-aar.ps1` passed. / 已通过。
- [ ] The full Gradle gate passed: `:frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :app:testCanaryUnitTest :app:testKotlinReleaseUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug :app:assembleCanary :app:assembleKotlinRelease -PrequireGoMobileBridge=true`. / 已通过完整 Gradle 门禁。
- [ ] A bridge byte change uses a new bridge version and Maven coordinate. / bridge 字节变化已使用新的 bridge 版本与 Maven 坐标。

## Documentation and Third Parties / 文档与第三方

- [ ] Relevant documentation, changelog or release notes were updated, or are not applicable. / 已更新相关文档、changelog 或 release note，或确认不适用。
- [ ] Dependency and asset source, license, notice, provenance, trademark, and hash records were updated when applicable. / 适用时已更新依赖与资产的来源、许可、notice、provenance、商标和哈希记录。
- [ ] Architecture or interaction changes reference the current files under `doc/`. / 架构或交互变化已对应当前 `doc/` 文件。

## Secret-Free Confirmation / 无秘密信息确认

- [ ] I confirm this PR, its commits, fixtures, screenshots, logs, and discussion contain no real token, API key, password, cookie, authorization value, provider sensitive header, SSH private key, passphrase, host fingerprint, private URL, private project source, complete sensitive response, signing material, or other secret. / 我确认本 PR、commit、fixture、截图、日志和讨论不含真实 token、API key、密码、cookie、Authorization 值、Provider 敏感请求头、SSH 私钥、口令、主机指纹、非公开 URL、私有项目源码、完整敏感响应、签名材料或其他秘密信息。

## Additional Notes / 补充说明

<!-- Migration, compatibility, risk, rollout, or reviewer notes. / 迁移、兼容性、风险、发布或审查说明。 -->
