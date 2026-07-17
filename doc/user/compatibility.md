# Compatibility

[简体中文](compatibility.zh-CN.md)

This English document is the public canonical compatibility statement for OC Deck. The Chinese document is a convenience translation.

OC Deck is a pre-1.0, independently maintained Android client for OpenCode Server. It does not include, install, or operate an OpenCode Server. Compatibility claims below apply only to OC Deck and the evidence currently available in this repository.

For user procedures, see [Installing and updating](installing.md), [Connection modes](connections.md), [Getting started](getting-started.md), and [Troubleshooting](troubleshooting.md).

## Status Definitions

| Status | Meaning |
| --- | --- |
| Supported | An explicit product target. Maintainers accept compatibility bug reports, but pre-1.0 behavior may still change under the deprecation policy. |
| Tested | Repeated automated or documented validation exists for the stated environment. This is evidence, not a broader support promise. |
| Observed only | A one-time observation exists, but it is not a compatibility guarantee or a maintained test matrix entry. |
| Known incompatible | The current build or design intentionally cannot run in that environment. |
| Unknown | There is not enough repeatable evidence to make a compatibility claim. |

Do not interpret `Observed only` or `Tested` as `Supported`, and do not infer compatibility for versions between listed rows.

## Compatibility Matrix

| Area | Environment or version | Status | Evidence and limits |
| --- | --- | --- | --- |
| Android OS | Android 8.0 / API 26 and newer | Supported | `minSdk` is 26. Exact device, vendor, and Android-version coverage is not yet a complete manual matrix. |
| Android OS | Below Android 8.0 / API 26 | Known incompatible | The APK cannot be installed because it is below `minSdk`. |
| Release ABI | `arm64-v8a`, `armeabi-v7a`, `x86_64` | Supported | The release workflow builds one APK per ABI and statically verifies ABI contents. |
| Release ABI | `x86`, `riscv64`, and other unlisted ABIs | Known incompatible | The current release workflow publishes no matching APK or universal APK. |
| Build environment | Ubuntu 24.04, JDK 21, Go 1.26.4, SDK 36, Build Tools 36.0.0, NDK 27.1.12297006 | Tested | GitHub Actions CI and Release workflows pin this toolchain. |
| OpenCode Server | `1.17.7` | Observed only | A health response with this version was observed while documenting the Web UI. It is not a supported-server declaration or an end-to-end regression suite result. |
| OpenCode Server | Any other version | Unknown | API compatibility is still evolving and no maintained server-version matrix exists. |
| Direct HTTP(S) | Reachable server with valid URL and optional Basic authentication | Supported | The client implements this connection mode. Network, TLS, authentication, and server behavior still depend on the deployment. |
| SSH forwarding | Supported Android device and compatible SSH endpoint | Supported | The client implements local forwarding, host-key verification, password/private-key modes, and unit-tested state handling. Broad device/server interoperability is not yet documented. |
| STCP bridge artifact | Fixed GoMobile bridge `0.3.5-frp0.69.1-p1` | Tested | Automated gates verify checksum, Java API, provenance, ABIs, ELF machine, 16KB `PT_LOAD` alignment, stripped state, and reproducibility. |
| Native loading | Recorded `0.1.0` physical-device release-gate run | Tested | Maintainers recorded successful native loading and app startup with the signed candidate APK. Exact device and ABI details are not published, so this is not a complete device or per-ABI matrix; future candidates must repeat the gate. |
| 16KB page size | Recorded `0.1.0` physical Android release-gate run using a 16KB page size | Tested | Maintainers recorded successful app startup and native loading on a 16KB page-size device. Exact device details are not published, and static alignment checks remain only a prerequisite. |
| STCP end to end | Recorded `0.1.0` physical-device run through real frps/STCP to OpenCode Server | Tested | Maintainers recorded listener readiness, tunneled `/global/health`, representative REST, global/project SSE, and controlled reconnect with existing UI data retained. Exact deployment versions are not published; this is not an OpenCode Server version-support claim. |

## Server Compatibility Guidance

- Check the server's `GET /global/health` response through the same direct, SSH, or STCP path that OC Deck will use.
- Treat a successful health check as reachability evidence, not proof that every API used by OC Deck is compatible.
- Unknown JSON fields are tolerated where DTOs have been designed for forward compatibility, but endpoint behavior, required fields, and event semantics may still change.
- Follow the installation guide for ABI selection, sideloading, update requirements, destructive rollback, and uninstall data effects.
- Before upgrading either side, review the [changelog](../../CHANGELOG.md), the version's release notes, and the [deprecation policy](../maintainers/deprecation-policy.md).

## Reporting Compatibility Results

Report non-sensitive results through the canonical repository at <https://github.com/ycfeng/ocdeck-android>. Include the OC Deck version or commit, Android version, device model and ABI, OpenCode Server version, connection mode, and redacted reproduction steps. Never include credentials, private keys, host fingerprints, private server URLs, project content, prompts, or unredacted logs.
