# Connection Modes

[简体中文](connections.zh-CN.md)

This English document is canonical. The Chinese document is a complete convenience translation.

OC Deck is a client. It does not install, start, or manage OpenCode Server. Before adding a profile, operate or obtain access to an OpenCode Server and decide how the Android device will reach it.

## 1. Choose a Mode

| Mode | OpenCode service URL means | Additional requirement |
| --- | --- | --- |
| Direct | An HTTP(S) URL reachable from the Android device | Network path from Android to OpenCode Server |
| SSH Forwarding | An HTTP(S) URL reachable from the SSH remote host | SSH account and verified SSH host key |
| frpc STCP visitor | Scheme and base path used after local rewrite; the server-side STCP proxy selects the real target | Existing frps and server-side STCP proxy |

SSH and STCP create a listener at `127.0.0.1:<local-port>` on the Android device. OC Deck rewrites both REST and SSE to that local address.

## 2. Rules Shared by All Modes

Open **Servers** and select **Add Server**. Direct is selected initially. The connection name and Direct URL start empty. Selecting SSH or STCP fills an empty URL with `http://127.0.0.1:4096`, but it does not overwrite a value you already entered.

The OpenCode service URL must:

- Use `http://` or `https://`.
- Include a valid host.
- Not include user information, a query, or a fragment.
- Use a base path only when the OpenCode deployment requires one.

**OpenCode Username** and **OpenCode Password** are HTTP Basic Auth credentials. OC Deck sends Basic authentication only when both values are nonblank. These credentials are separate from SSH authentication, the frps token, and the STCP secret.

Saving validates and stores the profile; it does not prove that a network connection works. After saving, return to **Servers** and run **Health Check**. A successful check proves that `/global/health` was reachable through that profile, not that every OpenCode API or model/provider operation is compatible.

Sensitive values use Keystore-backed credential storage and are not shown again in plaintext. When editing an existing profile, leaving a saved sensitive field empty generally retains its current value.

## 3. Direct HTTP(S)

Use Direct when the Android device can reach OpenCode Server without an app-managed tunnel.

1. Select **Direct**.
2. Enter a URL reachable from the Android device, such as the deployment's DNS name and port.
3. Enter both OpenCode Basic username and password if the server requires them.
4. Save the profile.
5. Run **Health Check**.

Important address behavior:

- On a physical Android device, `localhost` and `127.0.0.1` mean the Android device itself, not a development computer.
- Emulator aliases, `adb reverse`, VPNs, and port mappings are environment-specific and are not universal defaults.
- The server must listen on an interface reachable through the selected network, firewall, VPN, or reverse proxy.

Prefer correctly configured HTTPS when traffic crosses an untrusted network. If a non-loopback Direct profile combines `http://` with active OpenCode Basic authentication, OC Deck displays a warning before saving. Android Keystore protects the locally stored password; it does not encrypt cleartext HTTP traffic.

## 4. SSH Forwarding

Use SSH when Android can reach an SSH host and that host can reach OpenCode Server.

### Fields

| Field | Meaning |
| --- | --- |
| OpenCode Service URL | Address reachable from the SSH host; often `http://127.0.0.1:4096` when OpenCode runs on that host |
| SSH Host / Port | SSH endpoint; default port is `22` |
| SSH Username | SSH account name |
| Authentication Method | Password, Private Key, or Password + Private Key |
| SSH Password | Required when the selected method includes a password |
| Private Key | Pasted content or a locally selected UTF-8 key file, limited to 256 KiB |
| Private-key Passphrase | Optional passphrase for an encrypted key |
| Local Forward Port | Android loopback port, default `4096` |
| Connection Timeout | Default `10` seconds |
| KeepAlive Interval | Default `30` seconds |
| Host-key Verification | Trust On First Use or a manually pinned fingerprint |

### Configure

1. Select **SSH Forwarding**.
2. Enter the OpenCode URL as seen from the SSH host.
3. Enter SSH host, port, username, and authentication material.
4. Keep local port `4096` unless it conflicts with another listener.
5. Select host-key handling.
6. Save and run **Health Check**.

**Trust On First Use (TOFU)** automatically captures and stores the first observed SSH host key before user authentication. It is not an interactive fingerprint confirmation. Later key changes are rejected.

For stronger first-connection verification, obtain the fingerprint through an independent trusted channel and use **Pinned Fingerprint**. Sensitive fingerprint examples must be written as `<redacted>`. If a legitimate server rotates its key, independently verify the new fingerprint and enter that new pinned value. Do not bypass a mismatch or expect reselecting TOFU for the same endpoint to silently accept the changed key.

OC Deck keeps only the selected key content and a display filename, not the original file path. The private-key UTF-8 content limit is 256 KiB.

### HTTPS Limitation

SSH preserves the URL scheme and path but rewrites its host and port to `127.0.0.1:<local-forward-port>`. An HTTPS certificate issued only for the original remote hostname will normally fail standard hostname verification after this rewrite. OC Deck does not provide a custom CA or an option to disable certificate or hostname validation. Use a deployment whose certificate is valid for the effective endpoint, or use another correctly secured topology.

## 5. frpc STCP Visitor

Use this mode only when a server-side STCP proxy already exists and points to OpenCode Server. OC Deck does not create the frps service or server-side proxy.

### Fields

| Field | Meaning |
| --- | --- |
| OpenCode Service URL | Scheme and base path retained after local rewrite; host and port do not select the server-side STCP target |
| frps Address / Port | Reachable frps hostname or IP and port; default port is `7000` |
| frps Token | Optional frps authentication token |
| frpc User | Optional frp session user |
| STCP Server User | Optional owner or namespace of the server-side proxy |
| STCP Server Name | Exact server-side STCP proxy name |
| STCP Secret Key | Exact shared visitor secret |
| Local Bind Port | Android loopback port, default `4096` |
| Wire Protocol | `v1` by default; use `v2` only when the deployment explicitly requires it |

### Configure

1. Confirm that the server-side STCP proxy can reach OpenCode Server and that `/global/health` works at its target.
2. Select **frpc STCP visitor**.
3. Enter the frps endpoint and any required token or user.
4. Enter the exact server user, server name, and secret expected by the proxy.
5. Choose a free local bind port.
6. Save and run **Health Check**.

At runtime OC Deck starts the visitor, waits for a listener in the current frps control epoch, and checks `/global/health` through the local tunnel. REST and SSE are exposed only after that readiness process succeeds. Cold startup may take several seconds.

The STCP secret authorizes the visitor; do not describe it as end-to-end encryption for OpenCode traffic. Protect frps and OpenCode transport according to the deployment's security requirements. The same HTTPS loopback hostname limitation described for SSH also applies after STCP rewrites the URL to `127.0.0.1:<local-bind-port>`.

The bridge artifact has extensive static validation, but physical-device native loading, 16 KiB page-size operation, and a real STCP end-to-end path remain `Unknown` until recorded device evidence exists. See [Compatibility](compatibility.md).

## 6. Credential Boundaries

| Credential | Used for |
| --- | --- |
| OpenCode username and password | HTTP Basic Auth sent to OpenCode Server after the connection path is ready |
| SSH password/private key/passphrase | Authentication to the SSH server only |
| SSH host fingerprint | Verifying SSH server identity before user authentication |
| frps token and frpc user | Authenticating or identifying the frp client session |
| STCP server name/user/secret | Selecting and authorizing the server-side STCP proxy |

Never paste real credentials, private keys, fingerprints, private URLs, or full configuration into public issues. Use `<redacted>`.

## Next Steps

- Follow [Getting Started](getting-started.md) to open a project and send the first prompt.
- Use [Troubleshooting](troubleshooting.md) for connection, TLS, SSH, STCP, SSE, or notification failures.
