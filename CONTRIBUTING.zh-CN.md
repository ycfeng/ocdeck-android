# 为 OC Deck 贡献

[English](CONTRIBUTING.md)

本中文译本仅为阅读便利；如有歧义，以英文原文为准。

感谢您关注 OC Deck。OC Deck 是一个由社区独立维护的 OpenCode Server 原生 Android 客户端，并非由 OpenCode 项目或 Anomaly 开发、背书、赞助或关联的产品。

## 外部贡献状态

欢迎外部代码和文档贡献。参与贡献即表示贡献者同意遵守 [CODE_OF_CONDUCT.zh-CN.md](CODE_OF_CONDUCT.zh-CN.md)。维护者会根据贡献本身进行审查，并可能要求修改或拒绝不符合项目范围、质量、安全或维护要求的改动。

提交 pull request 不保证一定会被接受。请先搜索已有 issue；准备投入大型改动前，建议先提交功能建议讨论方向。

## 范围与设计参考

修改实现或用户可见行为前，请先阅读：

- [仓库代理规则](AGENTS.zh-CN.md)
- [移动端交互设计](doc/architecture/mobile-interaction.zh-CN.md)
- [项目框架](doc/architecture/project-framework.zh-CN.md)

改动应聚焦原生 Android 客户端。当移动端全屏页面、受约束弹窗、内联面板或底部弹层更合适时，不要机械照搬桌面 Web 的对话框或布局。

## Issue 与安全分流

- 缺陷：使用[缺陷报告表单](https://github.com/ycfeng/ocdeck-android/issues/new?template=01-bug-report.yml)。
- 功能建议：使用[功能建议表单](https://github.com/ycfeng/ocdeck-android/issues/new?template=02-feature-request.yml)。
- 问题咨询：使用[问题咨询表单](https://github.com/ycfeng/ocdeck-android/issues/new?template=03-question.yml)。
- 安全漏洞：遵循 [SECURITY.zh-CN.md](SECURITY.zh-CN.md)，并使用 [GitHub 私密漏洞报告](https://github.com/ycfeng/ocdeck-android/security/advisories/new)。不得在公开 issue 中填写漏洞细节。
- 行为投诉：遵循 [CODE_OF_CONDUCT.zh-CN.md](CODE_OF_CONDUCT.zh-CN.md)，并使用 [GitHub 私密漏洞报告](https://github.com/ycfeng/ocdeck-android/security/advisories/new)作为项目共用的私密入口。报告标题应以 `[Code of Conduct]` 开头，以便与安全报告分开处理。不得公开发布投诉细节。

提交新 issue 前请先搜索已有 issue。报告应保持最小化，并移除非公开项目数据和凭据。

## 开发环境

当前基线为 Android API 26+、compile/target SDK 36、JDK 21、Kotlin 2.4.0、Gradle 9.6.1 和 Android Gradle Plugin 9.2.1。Android Studio Gradle JDK 与命令行 `JAVA_HOME` 都应使用 JDK 21。

普通 App 开发需要 Android SDK 36 和 Build Tools 36.0.0。修改 GoMobile STCP bridge 时，还需要使用 `frpc-stcp-visitor-go/bridge-versions.properties` 固定的 Go、x/mobile、NDK 和 frp 版本；禁止使用浮动工具或依赖版本。

在仓库根目录运行普通验证：

```powershell
.\gradlew.bat :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

类 Unix 系统使用：

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

## 架构规则

- 业务代码保持在单一 `:app` 模块。`:frpc-stcp-visitor` 是已批准的 native bridge 边界，不代表可以继续拆分业务 Gradle 模块。
- 通过 `AppContainer` 手动依赖注入，并对 ViewModel 使用构造函数注入。除非项目基于明确需求事先批准，否则不要引入 Hilt、Room、KSP 或 kapt。
- 网络、Repository 和应用级状态不得放入 feature composable。不要在 feature 代码中临时创建 `OkHttpClient`、Retrofit、Repository 或全局单例。
- 所有 OpenCode `directory` 值在进入 Repository 或网络代码前必须规范化。服务端项目文件与 Android 本机附件必须明确区分。
- 对外部输入、REST、SSE、附件、私钥、Base64 和 native bridge 数据保持有界流式读取与脱敏保证。

## UI 与本地化

- 所有新增或修改的用户可见文案都必须使用 Android string resource，并同时更新 `app/src/main/res/values/strings.xml` 与 `app/src/main/res/values-en/strings.xml`。
- 每项视觉改动都要验证浅色与深色主题。不得硬编码只适用于单一主题的颜色。
- 每个可点击图标和 chip 操作都必须保留至少 48 dp 的真实触控目标。
- 优先采用移动端原生布局，并保证在 IME、系统 inset、受限屏幕尺寸、焦点导航和系统返回操作下仍可用。
- 对实质性 UI 改动提供截图或录屏；受影响时应覆盖中英文和浅深色主题。

## 测试

为改动行为新增或更新聚焦的单元测试，尤其关注路径规范化、脱敏、prompt 发送、SSE generation 与边界、凭据、SSH host key 校验、STCP epoch 和有界输入处理。

普通最低门禁为：

```text
:app:testDebugUnitTest
:frpc-stcp-visitor:testDebugUnitTest
:app:assembleDebug
```

如果改动涉及 `frpc-stcp-visitor-go/`、`frpc-stcp-visitor/`、frp patch、bridge API、bridge 依赖或 bridge 版本，还必须执行与 CI 等价的额外门禁：

```text
frpc-stcp-visitor-go: go run ./cmd/preparefrp
仓库根目录: python3 .github/scripts/audit-third-party.py
仓库根目录: python3 .github/scripts/audit-community.py
frpc-stcp-visitor-go: go test -race -modfile=build/frp-patched.mod ./...
frpc-stcp-visitor-go/build/frp-v0.69.1-p1: go test -race ./client/...
仓库根目录: bash frpc-stcp-visitor-go/build-aar.sh
仓库根目录: ./gradlew :frpc-stcp-visitor:checkGoMobileBridgeAar :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug -PrequireGoMobileBridge=true
```

Windows 下按需使用 `frpc-stcp-visitor-go/build-aar.ps1` 和 `gradlew.bat`。bridge 校验必须继续覆盖 AAR checksum、Java API signature、provenance、预期 ABI、ELF machine、16 KiB `PT_LOAD` 对齐、stripped 状态和可复现性。bridge 字节发生变化时必须递增 bridge 版本；不得在同一 Maven 坐标下发布不同字节。

## 敏感信息与测试 Fixture

严禁提交或粘贴真实 API key、token、password、cookie、Authorization header、Provider 认证信息、自定义 Provider header、env、SSH password、SSH private key、passphrase、host fingerprint、非公开 URL、项目源码、prompt 或敏感服务器响应。

请使用合成 fixture 和一次性测试值。日志、测试、snapshot、截图、issue 和文档中的敏感值统一替换为 `<redacted>`。即使移除了明显凭据，也不得直接从私有项目或生产响应复制 fixture。

## 第三方材料

新增或升级依赖、资产、音效、图标、Gradle wrapper、GoMobile 组件、frp 源码或 patch 前：

- 核实来源、版本、许可、版权、再分发条款和商标影响。
- 按需更新 `THIRD_PARTY_NOTICES.txt`、`third_party/components.toml`、对应的 `third_party/sources/*` 记录和许可全文。
- 根据本地实际字节重新计算哈希。
- 未完成来源、许可和商标审查前，不得引入 Provider 品牌或其他第三方标志。

## Pull Request

Pull request 必须聚焦、可审查，并在已有对应 issue 时建立关联。使用 [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md)，并包含：

- 用户可见影响和技术影响。
- 已运行的测试及结果，包括所有适用的 bridge 门禁。
- 相关时的 UI 证据及本地化、主题、无障碍检查。
- 安全、隐私、迁移、兼容性和第三方 notice 影响。
- 文档或 release note 改动；不适用时明确说明原因。

不要把无关清理和行为改动混入同一个 PR。不要提交生成的秘密信息、签名材料、本机配置或私有 fixture。

## 贡献许可

OC Deck 使用 [MIT License](LICENSE)。除非维护者另行书面明确同意，每项贡献都按照与项目相同的 MIT 条款提交，即 `inbound = outbound`。项目不要求签署 Contributor License Agreement，也不要求 Developer Certificate of Origin sign-off。
