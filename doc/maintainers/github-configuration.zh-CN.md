# GitHub 仓库配置

[English](github-configuration.md)

本中文译本仅为阅读便利；如与英文原文存在歧义，以英文原文为准。

仓库文件无法启用或证明 GitHub 服务端设置。管理员必须在 canonical 仓库 <https://github.com/ycfeng/ocdeck-android> 中逐项验证以下配置，并在接受外部贡献或发布 Release 期间持续保证配置有效。

## 外部贡献入口

接受外部 pull request 和文档贡献。GitHub Private Vulnerability Reporting 是本仓库安全报告与行为准则投诉共用的私密入口。管理员必须持续启用该功能，使用无仓库管理权限的账号验证访问，并确保行为投诉按照 `CODE_OF_CONDUCT.md` 处理，而不是进入安全漏洞披露流程。

## 必需仓库功能

- 将 `main` 设为默认分支并启用 Issues。
- 启用 GitHub Private Vulnerability Reporting，并使用无仓库管理权限的账号验证 <https://github.com/ycfeng/ocdeck-android/security/advisories/new> 可同时接收安全报告和私密行为准则投诉。
- 创建 issue 分流和 `.github/release.yml` 使用的标签：`bug`、`enhancement`、`documentation`、`dependencies`、`ci`、`needs-triage`、`release-notes/breaking`、`release-notes/security`、`release-notes/feature`、`release-notes/fix`、`release-notes/compatibility`、`release-notes/migration`、`release-notes/maintenance`、`release-notes/skip`。
- 配置 `release` Environment，加入 required reviewers、签名 secrets 和发布文档要求的固定证书 SHA-256 变量。
- 在平台支持时启用 immutable releases。

## Rulesets

- 合并到 `main` 前必须通过 `.github/workflows/ci.yml` 对应的 CI 状态检查。
- 对受保护路径要求 pull request review 和 Code Owner review。
- 禁止对 `main` force push 或删除分支。
- 限制 `v*` Release tag 的创建和更新；已发布 tag 不得移动。
- workflow 默认使用只读权限；仅向 tag 事件中调用 GitHub Release Notes API 的 `prepare-notes` 和创建已验证 Release 的 `publish` 授予受限的 `contents: write`。

单维护者阶段不要配置会永久阻止该维护者合并的审批规则。任何临时例外都应记录，并在另一位维护者取得 write 权限后重新评估。

## 验证

1. 使用非维护者身份打开每个 issue form，确认必填字段能阻止空提交。
2. 确认普通用户看不到 blank issue。
3. 创建修改受保护路径的测试 pull request，确认 CODEOWNERS 会请求 `@ycfeng` 评审。
4. 确认预期标签按精确拼写存在，并用样例 pull request 验证自动 Release Notes 分类。
5. 在 `main` 上执行手动 Release dry-run，同时审查签名产物和准备好的 release-notes artifact。
6. 创建 Release tag 前，按发布检查清单完成并记录目标设备 native load、16KB page-size 和真实 STCP 验证。

不能仅凭仓库文件或截图认定配置已完成，必须直接验证线上设置与访问路径。
