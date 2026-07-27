#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-go-race-environment.py")
SPEC = importlib.util.spec_from_file_location("check_go_race_environment", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Go race environment check could not be loaded")
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)


class CheckGoRaceEnvironmentTest(unittest.TestCase):
    def test_wsl1_kernel_is_rejected(self) -> None:
        release = "4.4.0-26100-Microsoft"

        self.assertTrue(checker.is_wsl1_kernel_release(release))
        with self.assertRaisesRegex(RuntimeError, "WSL1 is not supported"):
            checker.validate_kernel_release(release)

    def test_wsl2_and_native_kernels_are_accepted(self) -> None:
        for release in (
            "5.15.167.4-microsoft-standard-WSL2",
            "4.19.128-microsoft-standard",
            "6.8.0-1030-azure",
            "10.0.26100",
        ):
            with self.subTest(release=release):
                self.assertFalse(checker.is_wsl1_kernel_release(release))
                checker.validate_kernel_release(release)


if __name__ == "__main__":
    unittest.main()
