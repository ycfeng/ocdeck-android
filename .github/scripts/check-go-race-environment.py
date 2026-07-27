#!/usr/bin/env python3
"""Reject host environments with incompatible socket semantics for Go race gates."""

from __future__ import annotations

import platform
import sys


def is_wsl1_kernel_release(kernel_release: str) -> bool:
    normalized = kernel_release.strip().lower()
    return (
        "microsoft" in normalized
        and "microsoft-standard" not in normalized
        and "wsl2" not in normalized
    )


def validate_kernel_release(kernel_release: str) -> None:
    if is_wsl1_kernel_release(kernel_release):
        raise RuntimeError(
            "WSL1 is not supported for the patched frp Go race gate because its "
            "socket translation can allow duplicate loopback listener binds. Run "
            "the gate on WSL2, native Linux, or the GitHub Actions Ubuntu runner."
        )


def main() -> int:
    try:
        validate_kernel_release(platform.release())
    except RuntimeError as error:
        print(f"Go race environment check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
