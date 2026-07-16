# Installing and Updating OC Deck

[简体中文](installing.zh-CN.md)

This English document is canonical. The Chinese document is a complete convenience translation.

This guide covers release downloads, ABI selection, APK verification, sideloading, updating, rollback, and uninstalling. For supported environments and current evidence, see [Compatibility](compatibility.md).

## 1. Find an Actual Release

Use only the canonical [OC Deck Releases page](https://github.com/ycfeng/ocdeck-android/releases). A prepared release-notes file or a version in source code does not mean that an APK has been published. A public version exists only when its Git tag and GitHub Release exist and the Release contains APK assets.

If the Releases page has no OC Deck APK assets, there is no public installable build yet. Do not download files from an unofficial mirror.

GitHub may show automatic `Source code (zip)` and `Source code (tar.gz)` archives. These are source archives, not Android packages, and cannot be installed. The current release workflow uploads only:

- `OCDeck_<version>_arm64-v8a.apk`
- `OCDeck_<version>_armeabi-v7a.apk`
- `OCDeck_<version>_x86_64.apk`
- `SHA256SUMS`

There is no universal APK or AAB. Each ABI APK is a complete application; install exactly one of them.

## 2. Check Android Version and ABI

OC Deck requires Android 8.0/API 26 or newer. With Android Platform Tools installed and USB or wireless debugging enabled, check the device:

```text
adb devices
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
```

If `ro.product.cpu.abilist` is unexpectedly empty, use:

```text
adb shell getprop ro.product.cpu.abi
```

Read the ABI list from left to right and choose the first value that exactly matches a published ABI:

| Device result starts with | Download |
| --- | --- |
| `arm64-v8a,armeabi-v7a,...` | `OCDeck_<version>_arm64-v8a.apk` |
| `armeabi-v7a,...` | `OCDeck_<version>_armeabi-v7a.apk` |
| `x86_64,x86,...` | `OCDeck_<version>_x86_64.apk` |
| Only `x86`, `riscv64`, or other values | No compatible APK is currently published |

Do not use `adb install-multiple`; the three files are alternative builds of the same application, not split APKs.

## 3. Download and Verify

Download the selected APK and `SHA256SUMS` from the same GitHub Release. Replace `X.Y.Z` and the ABI below with the actual published values.

Linux:

```bash
sha256sum OCDeck_X.Y.Z_arm64-v8a.apk
grep 'OCDeck_X.Y.Z_arm64-v8a.apk' SHA256SUMS
```

macOS:

```bash
shasum -a 256 OCDeck_X.Y.Z_arm64-v8a.apk
grep 'OCDeck_X.Y.Z_arm64-v8a.apk' SHA256SUMS
```

Windows PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\OCDeck_X.Y.Z_arm64-v8a.apk
Select-String -LiteralPath .\SHA256SUMS -SimpleMatch "OCDeck_X.Y.Z_arm64-v8a.apk"
```

Compare the calculated hexadecimal value with the line for the same filename. `sha256sum -c SHA256SUMS` is convenient only when all three APKs named by the file are present; otherwise it also reports the missing APKs.

If Android SDK Build Tools are installed, this verifies that the APK has a structurally valid signature and prints its certificate details:

```text
apksigner verify --verbose --print-certs OCDeck_X.Y.Z_arm64-v8a.apk
```

`SHA256SUMS` confirms that the APK matches the checksum file in the same Release. By itself, it does not independently prove publisher identity. The repository does not currently publish a user-comparable release-certificate fingerprint, so `apksigner` alone cannot establish that identity either.

## 4. Sideload with Android

Menu names differ by device vendor, but the standard flow is:

1. Download and verify the matching APK.
2. Open the APK from the browser or file manager that downloaded it.
3. If Android blocks the operation, allow **Install unknown apps** for that browser or file manager.
4. Return to the installer and confirm installation.
5. After installation, optionally revoke that source's **Install unknown apps** permission.

Android 8.0 and newer grant this permission per source. OC Deck does not request permission to install other applications and has no in-app updater.

## 5. Install with ADB

From the directory containing the APK:

```text
adb install "OCDeck_X.Y.Z_arm64-v8a.apk"
```

The application ID is `io.github.ycfeng.ocdeck`.

## 6. Update an Existing Installation

OC Deck does not currently check for or download updates inside the app. Review the Release notes, download the new matching ABI APK, verify it, and open it with Android's installer. With ADB, use:

```text
adb install -r "OCDeck_X.Y.Z_arm64-v8a.apk"
```

Android permits a normal replacement update only when all relevant conditions hold:

- The installed and new APK use application ID `io.github.ycfeng.ocdeck`.
- Their signing certificates are update-compatible.
- The new `versionCode` is not lower than the installed one.
- The APK matches the device ABI and requires no newer Android version than the device provides.

Android normally preserves app-private data during a compatible replacement update, but maintainers must still validate each signed release path. Pre-1.0 storage and behavior may change; review the version's migration notes before updating.

## 7. Roll Back

Do not rely on `adb install -r -d` for a normal OC Deck Release APK. Android restricts this downgrade path and the Release build is not intended to be debuggable.

The practical rollback is destructive:

```text
adb uninstall io.github.ycfeng.ocdeck
adb install "OCDeck_OLD.VERSION_<abi>.apk"
```

This removes app-private local data, including locally saved profiles and preferences. OC Deck sets Android backup off and currently provides no supported export/restore workflow. Uninstalling does not delete data already stored by OpenCode Server, model providers, SSH servers, frps, or other remote systems. An older Release remaining downloadable does not mean that it is still supported or compatible with current servers.

## 8. Uninstall

Use the Android app-info page and select **Uninstall**, or run:

```text
adb uninstall io.github.ycfeng.ocdeck
```

Treat a later reinstall as a new local installation. Do not use `adb uninstall -k` as a backup strategy; OC Deck does not validate it as a supported recovery path for credentials or settings. See [Privacy](../../PRIVACY.md) for local and remote data boundaries.

## 9. Common Installation Errors

| Error | Typical cause | Action |
| --- | --- | --- |
| `INSTALL_FAILED_OLDER_SDK` | Device API is below 26 | Use Android 8.0/API 26 or newer |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | APK ABI is not supported by the device | Query the ABI list and download the correct APK |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Same application ID but incompatible signer | Install an APK from the same canonical signing lineage, or uninstall first and accept local-data loss |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | APK has a lower `versionCode` | Use the destructive rollback procedure if rollback is necessary |
| `INSTALL_FAILED_ALREADY_EXISTS` | The package exists and ADB was not told to replace it | Use `adb install -r` for a compatible update |

Vendor installers may show only a generic **App not installed** message. Reproduce with ADB when possible to obtain the specific error.

## Next Steps

- Continue with [Getting Started](getting-started.md).
- Read [Connection Modes](connections.md) before configuring Direct, SSH, or STCP.
- Use [Troubleshooting](troubleshooting.md) if installation or startup fails.
