# Documentation Policy

[简体中文](documentation-policy.zh-CN.md)

This English document is canonical. The Chinese document is a convenience translation.

## Language Model

- Public documentation is written in English as the canonical source.
- A paired `.zh-CN.md` file is a complete Simplified Chinese convenience translation, not a summary. It must identify itself as a convenience translation and link back to the English file.
- Paired files must link to each other and be updated in the same change whenever facts, commands, status, security constraints, or links change.
- If a translation and its English source differ, the English source controls. The mismatch must still be fixed promptly.
- The intentional single-file bilingual exceptions are root `CHANGELOG.md`, `.github/release-notes-template.md`, and files under `release-notes/`.
- Root `AGENTS.md` and `AGENTS.zh-CN.md` follow the same canonical/translation model. Any change to either file, including a user request to modify only one language, must update both in the same change and preserve semantic parity.

## Information Architecture

The documentation index uses exactly five public categories:

- User
- Development
- Architecture
- Maintainers
- Release

Add a document to the narrowest applicable category in `doc/README.md` and `doc/README.zh-CN.md`. Do not create competing indexes or duplicate canonical guides.

## Status and Accuracy

- Describe implemented, partial, placeholder, and unimplemented behavior separately.
- Do not turn a one-time observation into a support claim. Compatibility documents must use `Supported`, `Tested`, `Observed only`, `Known incompatible`, or `Unknown` with explicit evidence.
- Keep known gaps visible, including incomplete provider persistence, server-file Composer context, standalone Review, Shell, sharing, workspace/worktree, PTY/terminal, and missing physical-device STCP/16KB validation when applicable.
- Use exact source-of-truth versions from `gradle.properties`, `gradle/libs.versions.toml`, and `frpc-stcp-visitor-go/bridge-versions.properties` rather than estimates.
- Do not claim that all inbound network data is bounded when only audited endpoints have hard limits.

## Links and Versioning

- Use relative repository links for files in the same revision.
- Use <https://github.com/ycfeng/ocdeck-android> as the canonical repository URL in public documentation.
- Release notes must link version-specific documentation, privacy, support, license, notices, and trademark files to the release tag so later edits do not rewrite historical context.
- The security policy may link to the latest repository policy because reporting instructions can change after a release.
- When moving a document, update all inbound links in the same integrated change and remove the obsolete path unless an explicit compatibility redirect is required.

## Security and Privacy

- Documentation, examples, screenshots, fixtures, logs, and release notes must not contain real API keys, tokens, passwords, cookies, private keys, passphrases, provider headers, environment values, host fingerprints, private server URLs, project content, prompts, or raw sensitive responses.
- Use `<redacted>` for sensitive example values. Use clearly synthetic paths, IDs, hosts, and payloads.
- Do not publish signing keystores, certificate material, passwords, aliases, Base64 keystores, or test certificate details.
- Screenshots require the same review as text and must reflect the current Android UI.

## Release Documentation Responsibilities

- `CHANGELOG.md` tracks notable repository changes, deprecations, removals, security-relevant changes, and migration obligations over time.
- `release-notes/vX.Y.Z.md` is the curated bilingual user-facing description for one version, including compatibility and known issues.
- GitHub-generated notes append the pull-request list at publication time; they do not replace the curated version file or changelog.
- `.github/release-notes-template.md` defines required sections. Empty sections must explicitly say `None / 无`.
- Changes must not leave `TODO`, `TBD`, or unresolved `{{...}}` placeholders in final release notes.

## Licensing

Original OC Deck documentation is licensed under the same [MIT License](../../LICENSE) as the repository's original code. Quoted or adapted third-party material must retain applicable attribution, license, and notice requirements.

## Review Checklist

- Facts match the current code, workflows, and source-of-truth version files.
- English and Chinese paired documents are complete and mutually linked.
- Commands are runnable from the stated working directory and platform.
- Relative links resolve, moved paths have no stale inbound references, and release links use the correct tag behavior.
- Compatibility wording does not exceed available evidence.
- Security, privacy, legal, trademark, and third-party statements match the release contents.
- No secrets, private data, unresolved placeholders, or unreviewed screenshots are present.
