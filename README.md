# OC Deck

[简体中文](README.zh-CN.md)

OC Deck is a native Android client for [OpenCode](https://github.com/anomalyco/opencode). It connects to an OpenCode Server that you operate or trust. Note: OC Deck is not built by, endorsed by, or affiliated with the OpenCode project or Anomaly.

> **Pre-1.0 warning:** version `0.1.0` is under active development. Features, storage formats, network behavior, and compatibility may change. Keep backups of important work and avoid relying on OC Deck as the only way to access a server.

## Requirements

- Android 8.0 or newer, API level 26+
- A reachable [OpenCode Server](https://opencode.ai/docs/server/); OC Deck does not install or run the server
- Provider and model access configured on that server where required

## Current Features

- Multiple server profiles using direct HTTP(S), SSH local forwarding, or an embedded frpc STCP visitor
- Project selection, recent projects, session lists, session details, rename/archive/delete, revert/unrevert, and asynchronous prompts
- Global and project SSE synchronization with reconnect and snapshot reconciliation
- Slash commands, agent mentions, agent/model/variant pickers, local-device attachments, and server project-file context selection
- Read-only server project file browsing, search, text highlighting, and image preview
- Permission and question handling, context usage, and Git/session diff in the session Changes tab
- English and Simplified Chinese UI, theme preferences, Android notifications, notification sounds, and background-run guidance

## Not Yet Complete

- Provider authentication, disconnect, OAuth, and custom-provider persistence are still prototypes
- The standalone Review route is a placeholder; usable diff is in the session Changes tab
- Model enabled/hidden settings are local filters and do not modify OpenCode Server configuration
- Shell mode, session sharing, workspace/worktree flows, and PTY/terminal are not implemented

See the detailed status in the [mobile interaction design](doc/architecture/mobile-interaction.md), the [project framework](doc/architecture/project-framework.md), and the [documentation index](doc/README.md).

## Install and First Use

Public installable builds exist only on the canonical [GitHub Releases page](https://github.com/ycfeng/ocdeck-android/releases). If that page has no OC Deck APK assets, there is no public installable build yet. A prepared release note, source version, or GitHub source archive is not an APK.

The release workflow produces ABI-specific APKs only. There is no universal APK or AAB.

| Device ABI | Download |
| --- | --- |
| Most current Android phones and tablets | `OCDeck_<version>_arm64-v8a.apk` |
| Older 32-bit ARM devices | `OCDeck_<version>_armeabi-v7a.apk` |
| x86_64 emulators and compatible devices | `OCDeck_<version>_x86_64.apk` |

If unsure, query the device ABI before installing. Do not install APKs from an untrusted mirror; verify the selected APK against the `SHA256SUMS` from the same Release.

- Download, ABI query, checksum, sideload, update, rollback, and uninstall: [Installing and updating](doc/user/installing.md)
- Add a server, open a project, and send the first prompt: [Getting started](doc/user/getting-started.md)
- Direct, SSH, and STCP configuration: [Connection modes](doc/user/connections.md)
- 401/403, TLS, SSH host key, STCP, SSE, and notification failures: [Troubleshooting](doc/user/troubleshooting.md)

## Security Warning

- Connect only to servers and tunnel endpoints you trust.
- Prefer HTTPS or a trusted SSH/STCP path, and enable OpenCode Server authentication before exposing it beyond a trusted network.
- When a Direct profile combines a non-loopback `http://` URL with active OpenCode Basic authentication, OC Deck displays an advisory confirmation before saving. Android Keystore protects the locally stored password, not HTTP traffic; choosing Save Anyway still saves the profile and does not restrict later use.
- Server operators, model providers, and network intermediaries may receive project content, prompts, attachments, and metadata.
- Never post API keys, passwords, private keys, tokens, cookies, server URLs containing credentials, or raw sensitive logs in issues or discussions.

Read [SECURITY.md](SECURITY.md), [PRIVACY.md](PRIVACY.md), and [SUPPORT.md](SUPPORT.md) before reporting a problem.

## Build

Prerequisites:

- JDK 21
- Android SDK 36 and Build Tools 36.0.0
- For the STCP bridge: Go 1.26.4 and Android NDK 27.1.12297006

Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

macOS/Linux:

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

Debug builds can compile without a generated GoMobile AAR, but STCP is unavailable at runtime. To build and verify the fixed bridge first, run `frpc-stcp-visitor-go/build-aar.ps1` on Windows or `frpc-stcp-visitor-go/build-aar.sh` on macOS/Linux, then run the bridge gate documented in the canonical [repository agent rules](AGENTS.md).

## Screenshots

Screenshots are intentionally not included in the `0.1.0` project facade. Add only reviewed screenshots that reflect the current Android UI and contain no server addresses, project paths, session content, credentials, or other sensitive data.

## Support and Contributing

- Support scope and diagnostic guidance: [SUPPORT.md](SUPPORT.md)
- Security reports: [SECURITY.md](SECURITY.md)
- Contribution guide: [CONTRIBUTING.md](CONTRIBUTING.md)
- Code of Conduct: [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- Documentation: [doc/README.md](doc/README.md)
- Repository agent rules: [AGENTS.md](AGENTS.md)
- Release process: [doc/release/github-actions.md](doc/release/github-actions.md)

Contributions should keep the app mobile-first, preserve the manual-DI/single-business-module strategy, update both UI languages, include relevant tests, and never include real credentials or private project data.

## License and Notices

OC Deck's original code is licensed under the [MIT License](LICENSE). Third-party components and assets remain under their respective licenses; see [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt), [NOTICE](NOTICE), and the auditable inventory under [`third_party/`](third_party/).

Product names and marks are addressed in [TRADEMARKS.md](TRADEMARKS.md).

## Upstream Projects

- [OpenCode source](https://github.com/anomalyco/opencode)
- [OpenCode Server documentation](https://opencode.ai/docs/server/)
- [frp](https://github.com/fatedier/frp)
- [Gradle](https://github.com/gradle/gradle)
