#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("render-frpc-android-interop-report.py")
SPEC = importlib.util.spec_from_file_location("render_frpc_android_interop_report", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("report renderer could not be loaded")
renderer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(renderer)


class RenderFrpcAndroidInteropReportTest(unittest.TestCase):
    def test_complete_full_lane_passes(self) -> None:
        lane = self.valid_lane(36)

        self.assertEqual(
            [],
            renderer.lane_failures(lane, 36, "a" * 40, "b" * 40, "a" * 40),
        )

    def test_missing_profile_and_check_drift_fail_closed(self) -> None:
        lane = self.valid_lane(36)
        lane["hostSummary"]["profiles"].pop()
        self.assertIn(
            "host summary profile count is invalid",
            renderer.lane_failures(lane, 36, "a" * 40, "b" * 40, "a" * 40),
        )

        lane = self.valid_lane(36)
        lane["hostSummary"]["profiles"][0]["backends"][1]["checks"].pop()
        self.assertIn(
            "profile success-v1-00 checks are invalid",
            renderer.lane_failures(lane, 36, "a" * 40, "b" * 40, "a" * 40),
        )

    def test_evidence_loader_rejects_unknown_and_duplicate_keys(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "lane.json"
            lane = self.valid_lane(26)
            lane["unexpected"] = "value"
            path.write_text(json.dumps(lane), encoding="utf-8")
            with self.assertRaises(renderer.ReportError):
                renderer.load_lane(path)

            path.write_text('{"schemaVersion":2,"schemaVersion":2}', encoding="utf-8")
            with self.assertRaises(renderer.ReportError):
                renderer.load_lane(path)

    def test_boolean_values_cannot_impersonate_integer_schema_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "lane.json"
            lane = self.valid_lane(26)
            lane["schemaVersion"] = True
            path.write_text(json.dumps(lane), encoding="utf-8")
            with self.assertRaises(renderer.ReportError):
                renderer.load_lane(path)

        lane = self.valid_lane(26)
        lane["hostSummary"]["device"]["pageSize"] = True
        self.assertIn(
            "host summary page size is invalid",
            renderer.lane_failures(lane, 26, "a" * 40, "b" * 40, "a" * 40),
        )

    def test_report_marks_a_structurally_invalid_lane_as_failed(self) -> None:
        lane = self.valid_lane(36)
        lane["hostSummary"]["profiles"][0]["backends"][1]["checks"].pop()
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.md"
            renderer.write_report(
                report,
                language="en",
                status="Fail",
                candidate_sha="a" * 40,
                tree_sha="b" * 40,
                workflow_sha="a" * 40,
                validated_lanes=[(36, lane, False)],
                failures=["profile success-v1-00 checks are invalid"],
            )

            rendered = report.read_text(encoding="utf-8")
            self.assertIn("| `modern-api36-x86_64` | 36 | `x86_64` | invalid | `full` | 0 | Fail |", rendered)
            self.assertIn("| `modern-api36-x86_64` | `invalid evidence` |", rendered)

    def test_report_survives_a_malformed_nested_backend_value(self) -> None:
        lane = self.valid_lane(36)
        lane["hostSummary"]["profiles"][0]["backends"] = 1
        failures = renderer.lane_failures(lane, 36, "a" * 40, "b" * 40, "a" * 40)
        self.assertIn("profile success-v1-00 backends are invalid", failures)
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.md"
            renderer.write_report(
                report,
                language="en",
                status="Fail",
                candidate_sha="a" * 40,
                tree_sha="b" * 40,
                workflow_sha="a" * 40,
                validated_lanes=[(36, lane, False)],
                failures=failures,
            )

            rendered = report.read_text(encoding="utf-8")
            self.assertIn("| `modern-api36-x86_64` | 36 | `x86_64` | invalid | `full` | 0 | Fail |", rendered)

    def test_failed_report_does_not_render_untrusted_profile_strings(self) -> None:
        lane = self.valid_lane(36)
        lane["hostSummary"]["profiles"][0]["caseId"] = "\ud800|untrusted`value"
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.md"
            renderer.write_report(
                report,
                language="en",
                status="Fail",
                candidate_sha="a" * 40,
                tree_sha="b" * 40,
                workflow_sha="a" * 40,
                validated_lanes=[(36, lane, False)],
                failures=["profile identity is invalid"],
            )

            rendered = report.read_text(encoding="utf-8")
            self.assertNotIn("untrusted", rendered)
            self.assertIn("| `modern-api36-x86_64` | `invalid evidence` |", rendered)

    def test_failed_main_does_not_copy_an_invalid_lane_into_consolidated_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "evidence"
            output = root / "output"
            evidence.mkdir()
            (evidence / "api-26.json").write_text(json.dumps(self.valid_lane(26)), encoding="utf-8")
            invalid_lane = self.valid_lane(36)
            invalid_lane["hostSummary"]["profiles"][0]["caseId"] = "\ud800|untrusted`value"
            (evidence / "api-36.json").write_text(json.dumps(invalid_lane), encoding="utf-8")
            arguments = [
                str(SCRIPT),
                "--evidence-directory", str(evidence),
                "--output-directory", str(output),
                "--candidate-sha", "a" * 40,
                "--tree-sha", "b" * 40,
                "--workflow-sha", "a" * 40,
                "--matrix-result", "success",
                "--expected-api", "26",
                "--expected-api", "36",
            ]

            with mock.patch.object(sys, "argv", arguments):
                self.assertEqual(1, renderer.main())

            consolidated_text = (output / "evidence.json").read_text(encoding="utf-8")
            consolidated = json.loads(consolidated_text)
            self.assertEqual([26], [lane["emulatorApi"] for lane in consolidated["lanes"]])
            self.assertNotIn("untrusted", consolidated_text)
            self.assertTrue((output / "acceptance-report.md").is_file())
            self.assertTrue((output / "acceptance-report.zh-CN.md").is_file())
            self.assertTrue((output / "SHA256SUMS").is_file())

    @staticmethod
    def valid_lane(api: int) -> dict[str, object]:
        lane_id, suite, expected_profiles = renderer.EXPECTED_LANES[api]
        profiles = []
        for case_id, scenario, wire, encryption, compression, checks in expected_profiles:
            profiles.append(
                {
                    "caseId": case_id,
                    "scenario": scenario,
                    "wireProtocol": wire,
                    "useEncryption": encryption,
                    "useCompression": compression,
                    "backends": [
                        {
                            "backend": "gomobile",
                            "semanticOutcome": renderer.EXPECTED_SEMANTIC_OUTCOMES[scenario],
                            "checks": list(checks),
                        },
                        {
                            "backend": "kotlin",
                            "semanticOutcome": renderer.EXPECTED_SEMANTIC_OUTCOMES[scenario],
                            "checks": list(checks),
                        },
                    ],
                    "equivalent": True,
                }
            )
        return {
            "schemaVersion": 2,
            "candidateSha": "a" * 40,
            "treeSha": "b" * 40,
            "workflowSha": "a" * 40,
            "runUrl": "https://example.invalid/run",
            "runId": "1",
            "runAttempt": "1",
            "eventName": "workflow_dispatch",
            "ref": "refs/heads/test",
            "recordedAtUtc": "2026-07-20T00:00:00+00:00",
            "runnerOs": "Linux",
            "runnerImage": "ubuntu24",
            "runnerImageVersion": "1",
            "jdkVersion": "openjdk 21",
            "gradleVersion": "Gradle 9.6.1",
            "agpVersion": "9.0.0",
            "kotlinVersion": "2.3.0",
            "laneId": lane_id,
            "suite": suite,
            "emulatorApi": api,
            "emulatorRelease": "test",
            "emulatorAbi": "x86_64",
            "emulatorPageSize": "4096",
            "emulatorFingerprint": "test",
            "emulatorVersion": "test",
            "systemImagePackage": f"system-images;android-{api};default;x86_64",
            "systemImageRevision": "1.0.0",
            "frpVersion": "v0.69.1",
            "frpArchive": "frp.tar.gz",
            "frpExpectedSha256": "c" * 64,
            "frpActualSha256": "c" * 64,
            "testApkSha256": "d" * 64,
            "bridgeAarSha256": "e" * 64,
            "interopOutcome": "success",
            "testCommand": (
                ":frpc-stcp-visitor:frpcAndroidInteropTest -PrequireGoMobileBridge=true "
                f"-Pocdeck.frp.androidInterop.suite={suite} "
                "-Pocdeck.frp.androidInterop.summaryFile=<runner-temp>"
            ),
            "hostSummary": {
                "schemaVersion": 1,
                "suite": suite,
                "device": {"apiLevel": api, "abi": "x86_64", "pageSize": 4096},
                "backendOrder": ["gomobile", "kotlin"],
                "profiles": profiles,
                "authorizesKotlinDefault": False,
            },
        }


if __name__ == "__main__":
    unittest.main()
