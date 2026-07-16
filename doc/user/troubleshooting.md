# Troubleshooting

[简体中文](troubleshooting.zh-CN.md)

This English document is canonical. The Chinese document is a complete convenience translation.

Work through this guide in order: installation, Health Check, project loading, prompt submission, SSE, then notifications. Fixing an earlier stage often resolves later symptoms.

Never post real passwords, tokens, private keys, host fingerprints, private server URLs, project content, prompts, Authorization headers, or complete responses. Replace sensitive values with `<redacted>`.

## 1. Installation and Startup

Confirm:

- Android is API 26 or newer.
- The APK matches the first supported value in the device ABI list.
- The APK came from an actual canonical GitHub Release, not a source archive or mirror.
- An existing installation is signed compatibly and is not newer than the APK being installed.

Use ADB to obtain a specific installation error when the vendor installer only says **App not installed**. See [Installing and Updating](installing.md#9-common-installation-errors).

## 2. Health Check and Server Connection

Saving a profile does not test the connection. Run **Health Check** from **Servers** before opening a project.

### Cannot Reach the Server

1. Verify that OpenCode Server is running and that `/global/health` works from the network side used by OC Deck.
2. Confirm that the configured host, port, scheme, and optional base path are correct.
3. On a physical device, replace accidental `localhost` or `127.0.0.1` Direct URLs; they refer to the phone itself.
4. Check Wi-Fi/mobile routing, VPN, firewall, reverse proxy, and whether the server listens on a reachable interface.
5. For SSH, test the OpenCode URL from the SSH host.
6. For STCP, test the target from the server-side proxy host and verify frps reachability from Android.

### HTTP 401 or 403

OC Deck currently supports OpenCode HTTP Basic Auth only. It sends an Authorization header only when both username and password are nonblank.

1. Edit the server profile and verify both OpenCode Basic fields.
2. Do not put SSH credentials, an frps token, or an STCP secret into the OpenCode fields.
3. Check whether the deployment requires Bearer, OAuth, Cookie, or another login method that OC Deck does not currently support.
4. Save, run **Health Check**, then reopen the project.

SSE treats 401 and 403 as permanent failures and does not keep retrying invalid credentials.

### TLS or Certificate Failure

OC Deck uses Android and OkHttp's normal certificate trust and hostname verification. It has no custom CA import, certificate bypass, hostname bypass, or **Continue insecurely** option.

Check for:

- A self-signed or otherwise untrusted CA.
- An expired or not-yet-valid certificate.
- A missing intermediate certificate.
- A DNS name that does not match the certificate SAN.
- SSH/STCP URL rewriting to `127.0.0.1`, while the certificate is valid only for the original hostname.

Fix the certificate, chain, DNS name, or deployment topology. Do not work around a validation error by disabling verification. Direct HTTP may be appropriate only on a network whose risk you explicitly accept; Basic credentials over HTTP are not transport-encrypted.

### HTTP 404 or 5xx

- `404` commonly indicates an incorrect base path or an incompatible server endpoint.
- `5xx` indicates a server or upstream failure; inspect redacted server-side diagnostics.
- A successful `/global/health` response is reachability evidence, not a complete OpenCode API compatibility guarantee.

## 3. SSH Host Key and Tunnel

### First Connection Fails

1. Verify SSH host, port, username, and network reachability.
2. Verify the selected password/private-key authentication mode.
3. Confirm that private-key content is valid UTF-8 and no larger than 256 KiB.
4. Verify the passphrase for encrypted keys.
5. Check whether the local forward port is already in use.
6. From the SSH host, confirm that the configured OpenCode service URL is reachable.

### Host Key Mismatch

Do not bypass a mismatch. It may indicate a changed server, legitimate key rotation, a wrong host, or an interception attempt.

1. Stop connecting.
2. Verify the new host fingerprint through an independent trusted channel.
3. If rotation is legitimate, edit the profile and enter the verified new **Pinned Fingerprint**.
4. Run **Health Check** again.

TOFU stores the first key automatically; it is not a prompt to approve every later change. Re-selecting TOFU for the same endpoint must not be treated as a safe rotation procedure.

### Tunnel Opens but HTTPS Fails

SSH rewrites the effective URL to `127.0.0.1:<local-port>`. A certificate issued only to the remote hostname will not normally match that loopback host. Use a certificate/topology valid for the effective endpoint or another correctly secured connection mode.

## 4. STCP Visitor

Check in this order:

1. The server-side STCP proxy exists and points to the intended OpenCode target.
2. The target's `/global/health` works from the proxy side.
3. Android can reach the configured frps address and port.
4. The frps token, frpc user, and wire protocol match the deployment.
5. STCP server user, server name, and secret exactly match the server-side proxy.
6. The local bind port is free.
7. The build contains an available native bridge.
8. Allow the bounded cold-start readiness process to finish before retrying.
9. After an frps reconnect or control-epoch change, run **Health Check** again when the connection stabilizes.

Transient connection refused/reset/timeout, unhealthy responses, HTTP 408/425/429, and 5xx may be retried during readiness. DNS, TLS, malformed health data, authentication, address, and configuration failures fail without an unbounded retry loop.

Real physical-device STCP end-to-end behavior is not yet a broad compatibility claim. Check [Compatibility](compatibility.md) before treating a device-specific failure as unexpected.

## 5. Project Does Not Open

1. Confirm Health Check succeeds first.
2. Enter the directory as seen by OpenCode Server, not Android local storage.
3. Normalize Windows examples to forward slashes, such as `X:/workspace/sample-project`.
4. Confirm the directory exists and the OpenCode Server process can access it.
5. Check Basic authentication and server logs for snapshot endpoint failures.
6. A manually entered path may not fail until OC Deck requests the project snapshot.

OC Deck browses and selects project files through OpenCode Server APIs. Project contexts remain validated server references; Android's local file picker is used only for phone-local attachments, not for opening or attaching a server project file.

## 6. Prompt Cannot Be Sent or Completes Uncertainly

### Send Is Disabled

Confirm that:

- At least one of text, local-device attachments, or project-file contexts is present.
- No more than 10 project-file contexts are selected, and each still belongs to the current project.
- Prompt capabilities have loaded.
- A currently available model is selected.
- The selected agent is a Composer-supported `build` or `plan` agent.
- The selected reasoning variant is still offered by the model.

If capabilities changed while composing, wait for reload and select current values before retrying.

### New Session Creation Failed

OC Deck did not receive confirmation that `POST /session` completed. It removes the still-optimistic message locally and keeps the draft on New Session. A lost response can leave a backend session even though the app reports failure. Refresh and inspect the project session list before retrying, then correct any connection, authentication, or server issue.

### Session Exists but Prompt Failed

If `POST /session` succeeded before prompt submission reported failure, OC Deck keeps and opens the real session and retains the draft. If SSE has not confirmed the message, the app removes its still-optimistic local copy. A lost prompt response may still mean the server accepted it. Refresh and inspect the session messages before resending from that real session.

### Result Is Uncertain

If SSE confirmed the message before the HTTP path reported failure, OC Deck keeps the confirmed message. Refresh and inspect the session before resending; an immediate retry may duplicate the prompt.

## 7. SSE and Missing Live Updates

OC Deck uses global and project SSE streams. Disconnecting does not clear existing UI data. After reconnect, OC Deck reconciles state with REST snapshots.

Automatic bounded retries apply to connection-class I/O failures, HTTP 408/409/425/429, and 5xx. Examples of terminal failures include:

- 401/403 authentication failures.
- Other non-200 responses outside the retry list.
- TLS or hostname verification failures.
- A response whose content type is not `text/event-stream`.
- A server that returns non-identity SSE content encoding, or an SSE line/event exceeding the 32 MiB identity response-body limit.

The UI may show only a general **Failed** state rather than the detailed internal reason, and there is no explicit reconnect button. Recovery sequence:

1. Edit and correct the server profile if needed.
2. Run **Health Check**.
3. Return to the project or reopen it so REST snapshots and SSE can start again.
4. Refresh the session before repeating an operation whose server result is uncertain.

## 8. Notifications Do Not Arrive

OC Deck notifications are local reactions to SSE events. They are not FCM push notifications and are not reliable after Android kills the app process. There is no foreground service, WorkManager connection, or other persistent background transport.

Check in this order:

1. Confirm the app process is still alive and the project SSE connection is running.
2. On Android 13/API 33 or newer, open **Settings > Background Run Settings** and grant notification permission.
3. In **Settings > General**, confirm the relevant Agent, Permission, or Error notification toggle. Agent and Permission are enabled by default; Error is disabled by default.
4. Confirm Android app notifications and the relevant **Agent alerts**, **Permission alerts**, or **Error alerts** channel are enabled in system settings.
5. Leave the affected session screen while testing; OC Deck suppresses a session's notification when that same session is currently being viewed.
6. Review battery optimization and vendor background restrictions in **Background Run Settings**.
7. If the vendor supports locking or retaining an app in recent tasks, enable it to reduce one-tap cleanup termination.

On Android versions below API 33, no runtime notification permission is required, but system-level app or channel settings can still suppress notifications. The v2 channels are silent by default so OC Deck can play the sound selected in the app exactly once. If you explicitly choose a channel sound in Android settings, that system sound takes precedence; choosing None in OC Deck disables OC Deck's own playback but does not override an explicit Android channel sound. Battery exemptions reduce interruptions but cannot guarantee delivery after process death.

## 9. Features That Are Not Recovery Options

The current app does not provide:

- An in-app updater or automatic rollback.
- Bearer, OAuth, Cookie, or API-key login for OpenCode Server.
- Custom TLS CA import or certificate/hostname bypass.
- An explicit SSE reconnect button or detailed SSE failure screen.
- Reliable push notifications after process death.
- A supported settings/credential export and restore workflow.

## 10. Report a Problem

If the issue remains, follow [Support](../../SUPPORT.md). Include only non-sensitive details: OC Deck version or commit, Android version, device model and ABI, OpenCode Server version, connection mode, stage of failure, exact non-sensitive error text, and minimal reproduction steps.
