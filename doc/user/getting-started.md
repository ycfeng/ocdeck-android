# Getting Started

[简体中文](getting-started.zh-CN.md)

This English document is canonical. The Chinese document is a complete convenience translation.

This is the shortest complete path from a new OC Deck installation to the first ordinary prompt. Detailed connection fields are in [Connection Modes](connections.md), and recovery steps are in [Troubleshooting](troubleshooting.md).

## 1. Prepare the Prerequisites

Before opening OC Deck, confirm that you have:

- Android 8.0/API 26 or newer and a matching installed APK. See [Installing and Updating](installing.md).
- An OpenCode Server that you operate or trust. OC Deck does not install or start it.
- A Direct, SSH, or STCP network path from the Android device to that server.
- Provider and model access configured on OpenCode Server when the selected workflow requires them.
- The real project directory as seen by OpenCode Server, not an Android-local path.

## 2. Add a Server Profile

1. Start OC Deck. The app opens the **Servers** page.
2. Select **Add Server**.
3. Choose **Direct**, **SSH Forwarding**, or **frpc STCP visitor**.
4. Enter the OpenCode service URL and the fields required for that mode.
5. Add both OpenCode Basic username and password if the server uses Basic authentication.
6. Save the profile.

Saving stores the configuration but does not test it. If a save reports that its outcome is uncertain, return to or refresh the server list before retrying; the first write may already have succeeded.

## 3. Run Health Check

On the server card, select **Health Check**.

- Success means OC Deck reached and parsed `/global/health` through the configured Direct, SSH, or STCP path.
- Failure means the connection path, TLS, credentials, tunnel, or endpoint needs correction.
- Success does not prove that every OpenCode endpoint, provider, model, or SSE behavior is compatible.

Resolve Health Check failures before opening a project. See [Troubleshooting](troubleshooting.md#2-health-check-and-server-connection).

## 4. Open a Server Project

1. Open the healthy server profile.
2. On **Open Project**, choose a recent project or enter its path.
3. Use the path on the machine where OpenCode Server accesses the project, for example `/workspace/sample-project` or `X:/workspace/sample-project`.
4. Select **Open Project**.

The picker normalizes the path, but a manually entered path is normally validated only when project snapshots are requested. Entering an Android file path or a directory unavailable to OpenCode Server will fail during project loading.

When the project opens, OC Deck loads REST snapshots and starts global and project SSE synchronization. Temporary SSE connection states do not erase already loaded UI data.

The project picker and navigation drawer share one saved project order per server. A newly added project starts at the top, while clicking an existing project to enter it does not change its position. Long-press a project-card body in the picker or a project button in the drawer to drag it vertically; edge scrolling helps with longer lists, and Open Project and Settings remain fixed. TalkBack users can use the Move Project Up and Move Project Down custom actions.

## 5. Start a New Session

1. Select **New Session**.
2. Wait for prompt capabilities, models, and agents to load.
3. Select a valid model.
4. Select a Composer-supported agent: `build` or `plan`.
5. Select a reasoning variant only when the chosen model provides one; use **Default** when unsure.

The New Session screen is initially a local placeholder. OC Deck does not create a backend session until the first send.

## 6. Send the First Prompt

Enter an ordinary text prompt, for example:

```text
Summarize the structure of this project without changing files.
```

Optionally open **Add attachment** and choose **From project** to browse or search the current server project, preview files, and confirm up to 10 whole-file contexts. These remain server references rather than phone-local attachments.

Then select Send. At least one of text, local-device attachments, or project-file contexts must be present.

For a new session, OC Deck performs this sequence:

1. Inserts the user message optimistically in the local timeline.
2. Calls `POST /session` to obtain the real session ID.
3. Moves the local message and navigation to that real session.
4. Calls `POST /session/{sessionID}/prompt_async`.
5. Waits for SSE or a later message refresh to provide the assistant response.

Successful submission does not mean the complete assistant response has already arrived. Keep the project open while SSE delivers updates.

## 7. Recover from First-Send Failures

| Result | What OC Deck keeps | What to do |
| --- | --- | --- |
| OC Deck did not receive session-creation confirmation | Draft remains on New Session; the still-optimistic message is removed locally; a lost response may still have left a backend session | Refresh and inspect the project session list before retrying; then correct any connection, authentication, or server issue |
| A real session exists, but OC Deck did not receive prompt-submission confirmation | Real session and draft remain; if SSE has not confirmed the message, its optimistic copy is removed locally; a lost response may still mean the server accepted the prompt | Refresh and inspect the session messages before retrying inside the real session |
| SSE confirmed the message before an HTTP failure was reported | Confirmed message remains and the result is marked uncertain | Refresh and inspect the session before sending again to avoid a duplicate |
| Prompt submitted but assistant response is missing | User message remains; SSE may be disconnected or delayed | Wait for reconnect, then refresh the session if needed |
| Model, agent, variant, or capability revision became stale | Request is rejected before or during submission | Wait for capabilities to reload and select a currently available option |

## 8. Confirm the Workflow Is Complete

The basic first-use loop is complete when:

- The server Health Check succeeds.
- The intended server project opens.
- The session has a real server ID and appears in the project session list.
- The user message remains in the timeline.
- The assistant response arrives through SSE or a manual refresh.

If any step fails, use the stage-based [Troubleshooting](troubleshooting.md) guide. Do not share raw server URLs, credentials, private project paths, prompts, or complete responses in a public report; replace sensitive values with `<redacted>`.
