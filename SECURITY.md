# Security Policy

[简体中文](SECURITY.zh-CN.md)

## Supported Versions

OC Deck is pre-1.0. Security fixes are expected to target the latest source and, when releases exist, the latest published release. Older builds may not receive fixes. This is a project policy, not a response-time or support guarantee.

## Private Reporting Status

Report suspected OC Deck vulnerabilities through [GitHub Private Vulnerability Reporting](https://github.com/ycfeng/ocdeck-android/security/advisories/new).

Publishing this URL in the repository does not enable the feature. Repository administrators must actually enable Private Vulnerability Reporting under the repository security settings and verify that the advisory form is accessible before relying on it for public distribution or external contribution intake.

- Do not put vulnerability details, exploit steps, credentials, private keys, tokens, cookies, private server addresses, project content, complete responses, or unredacted logs in a public issue.
- If the private advisory form is unavailable, do not move the report into a public issue. A public issue may only state that private reporting appears unavailable, without vulnerability or reproduction details.
- If a secret may already be exposed, rotate or revoke it immediately. Do not wait for project triage.
- This channel is for security vulnerabilities. It is not the Code of Conduct complaint channel described in [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## What to Include Privately

- Affected OC Deck version or commit
- Android version and device ABI
- Connection mode: direct, SSH, or STCP
- Impact and realistic attack preconditions
- Minimal reproduction steps or proof of concept
- Suggested mitigation, if known
- Redacted logs with secrets and private project data removed

Do not send a live production credential. Use disposable test values and replace sensitive data with `<redacted>`.

## Scope

Relevant reports include credential disclosure, authentication or host-key bypass, unsafe server URL handling, tunnel isolation failures, path traversal, unbounded external-input handling, malicious SSE/REST payload handling, dependency or native-library loading issues, and unintended exposure through logs or notifications.

Issues in OpenCode Server, model providers, Android, SSH servers, frps, or other upstream components may need to be reported to their respective maintainers. OC Deck maintainers may still need a coordinated report when the client integration increases the impact.

## Disclosure

Please allow maintainers time to investigate and prepare a fix before public disclosure. No fixed acknowledgement, remediation, bounty, or disclosure timeline is promised while the project remains community-maintained and pre-1.0.
