# Privacy

[简体中文](PRIVACY.zh-CN.md)

Last updated: 2026-07-15

This document describes the current OC Deck `0.1.0` codebase. It is not a promise that every future build, distribution channel, OpenCode Server, model provider, Android device, or network operator behaves the same way.

## OC Deck Services and Telemetry

The current app does not operate an OC Deck-hosted backend and does not intentionally include product analytics or an app-specific crash-reporting service. The app does connect to services selected or configured by the user. Android, an app store, a device vendor, a network provider, or a separately configured service may collect information under its own terms.

## Connections to User-Configured Services

OC Deck sends requests to the OpenCode Server profile selected by the user. Depending on the configured mode, traffic may use direct HTTP(S), an SSH local port forward, or an frpc STCP visitor. The OpenCode Server, its operator, configured model providers, tunnel infrastructure, and network intermediaries may process or retain server addresses, authentication metadata, project paths, prompts, session content, tool results, attachments, and operational logs.

HTTPS protects the HTTP connection only when correctly configured and validated. SSH or STCP changes the transport path but does not by itself define the retention or privacy practices of the OpenCode Server or model provider.

## Data Stored on the Android Device

OC Deck stores lightweight configuration and preferences locally, including server profiles, recent projects, language/theme choices, notification and sound settings, and local model visibility preferences. Runtime project/session state is primarily held in memory.

Sensitive server credentials, SSH secrets, host fingerprints, and STCP secrets are stored through the app's Android Keystore-backed credential boundary, while ordinary configuration stores references to credential aliases. Device compromise, insecure backups, OS vulnerabilities, or implementation defects may still affect confidentiality; this document does not make an absolute security guarantee.

Removing a server profile is intended to remove its local configuration and unreferenced credentials. Uninstalling the app generally removes app-private local data according to Android behavior. Neither action necessarily deletes data already stored by an OpenCode Server, model provider, SSH server, frps service, backup system, or notification history.

## Attachments and Project Files

When the user selects a phone-local attachment, OC Deck reads the selected content and may encode and send it to the configured OpenCode Server as part of a prompt. The system picker grants access to the selected item; OC Deck does not need broad access to the entire phone storage for this flow.

Server project-file browsing and selection use the OpenCode Server's filesystem APIs, not Android local storage. When the user adds a server project file to Composer context, OC Deck keeps only its validated project-relative path in the in-memory draft and sends a `file://` reference to the configured OpenCode Server. The server may then read and expand that file for the prompt. OC Deck does not read the selected project file into an Android local attachment or Base64-encode it. A draft can contain at most 10 server project-file contexts.

## Notifications and Sounds

If enabled and permitted by Android, OC Deck can create local system notifications for agent, permission, or error events and can play bundled notification sounds. Notification text is designed to use localized and redacted summaries, but session titles or other non-secret context may still be visible on the lock screen depending on Android and user settings. Users should configure lock-screen privacy appropriately.

The 45 bundled sound files are local app assets. Their source and hashes are documented in `third_party/sources/opencode-audio.json`.

## Logs and Diagnostics

Debug builds may produce diagnostic logs. Network logging is intended to be disabled outside debug builds and to pass through redaction, but users should still inspect logs before sharing them. Never share raw credentials, Authorization headers, cookies, private keys, provider configuration, private project content, or complete sensitive responses.

## User Choices

Users can choose which server to connect to, whether to configure SSH or STCP, whether to select local attachments or server project-file contexts, and whether to enable notifications or sounds. Remote data access, correction, retention, and deletion are controlled by the relevant OpenCode Server or provider and must be handled with that operator.

## Changes

Material changes to app data flows should be reflected in this file and the related security and architecture documentation. Review the version of this document shipped with the build you use.
