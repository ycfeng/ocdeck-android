# Support

[简体中文](SUPPORT.zh-CN.md)

OC Deck is a pre-1.0 community project. Support is provided on a best-effort basis without a guaranteed response time, compatibility period, or service-level agreement.

## Before Reporting

1. Follow [Installing and updating](doc/user/installing.md) to confirm Android API level, APK source, device ABI, checksum, and update compatibility.
2. Follow [Getting started](doc/user/getting-started.md) and confirm each earlier stage succeeds before the failing stage.
3. Confirm the configured OpenCode Server is reachable and run **Health Check** through the same Direct, SSH, or STCP path.
4. Use [Troubleshooting](doc/user/troubleshooting.md) for installation, 401/403, TLS, SSH host-key, STCP, project, prompt, SSE, and notification failures.
5. Reproduce with the latest available OC Deck build when practical, then check evidence and known limits in [Compatibility](doc/user/compatibility.md).

## Diagnostic Information

Useful non-sensitive details include:

- OC Deck version or commit
- Android version, device model, and ABI
- OpenCode Server version
- Connection mode: direct, SSH, or STCP
- Stage of failure: install, Health Check, project load, prompt, SSE, or notification
- Exact non-sensitive installer, HTTP, TLS, tunnel, or UI error text
- Expected and actual behavior
- Minimal reproduction steps
- Redacted log excerpts and stack traces

Do not include passwords, API keys, tokens, cookies, private keys, host fingerprints, provider headers, private server URLs, project source, prompts, or complete server responses. Replace sensitive values with `<redacted>`.

## Where to Ask

The canonical issue tracker is [https://github.com/ycfeng/ocdeck-android/issues](https://github.com/ycfeng/ocdeck-android/issues). Use the GitHub issue chooser or open the appropriate form directly:

- [Bug report](https://github.com/ycfeng/ocdeck-android/issues/new?template=01-bug-report.yml)
- [Feature request](https://github.com/ycfeng/ocdeck-android/issues/new?template=02-feature-request.yml)
- [Question](https://github.com/ycfeng/ocdeck-android/issues/new?template=03-question.yml)

Questions are handled through the question issue form; the project does not promise a separate real-time support channel. For implementation scope after completing the user guides, consult the [mobile interaction design](doc/architecture/mobile-interaction.md) and [project framework](doc/architecture/project-framework.md). For OpenCode Server behavior, configuration, or upstream API questions, consult the [OpenCode documentation](https://opencode.ai/docs/) and [OpenCode repository](https://github.com/anomalyco/opencode). For frp protocol issues independent of OC Deck, consult the [frp repository](https://github.com/fatedier/frp).

Security-sensitive reports must follow [SECURITY.md](SECURITY.md) and use the private vulnerability reporting route. Never post vulnerability details, exploit steps, or secrets in a public issue.
