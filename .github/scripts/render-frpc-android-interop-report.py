#!/usr/bin/env python3
"""Render bounded bilingual K6V Android interoperability evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


SHA256 = re.compile(r"^[0-9a-f]{64}$")
SHA1 = re.compile(r"^[0-9a-f]{40}$")
EXPECTED_KEYS = {
    "schemaVersion",
    "candidateSha",
    "treeSha",
    "workflowSha",
    "runUrl",
    "runId",
    "runAttempt",
    "eventName",
    "ref",
    "recordedAtUtc",
    "runnerOs",
    "runnerImage",
    "runnerImageVersion",
    "jdkVersion",
    "gradleVersion",
    "agpVersion",
    "kotlinVersion",
    "emulatorApi",
    "emulatorRelease",
    "emulatorAbi",
    "emulatorPageSize",
    "emulatorFingerprint",
    "emulatorVersion",
    "systemImagePackage",
    "systemImageRevision",
    "frpVersion",
    "frpArchive",
    "frpExpectedSha256",
    "frpActualSha256",
    "testApkSha256",
    "bridgeAarSha256",
    "interopOutcome",
    "backendOrder",
    "scenario",
    "testCommand",
}
MAX_EVIDENCE_BYTES = 64 * 1024
MAX_TEXT_LENGTH = 1_024


class ReportError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReportError(message)


def bounded_string(value: Any, label: str) -> str:
    require(isinstance(value, str), f"{label} must be a string")
    require(0 < len(value) <= MAX_TEXT_LENGTH, f"{label} has an invalid length")
    require("\x00" not in value and "\r" not in value and "\n" not in value, f"{label} contains control characters")
    return value


def load_lane(path: Path) -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), f"invalid evidence file: {path.name}")
    require(path.stat().st_size <= MAX_EVIDENCE_BYTES, f"evidence file is too large: {path.name}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReportError(f"invalid evidence JSON: {path.name}") from error
    require(isinstance(value, dict), f"evidence root must be an object: {path.name}")
    require(set(value) == EXPECTED_KEYS, f"evidence keys are invalid: {path.name}")
    require(value["schemaVersion"] == 1, f"unsupported evidence schema: {path.name}")
    for key, item in value.items():
        if key in {"schemaVersion", "emulatorApi"}:
            require(isinstance(item, int), f"{key} must be an integer")
        elif key == "backendOrder":
            require(item == ["gomobile", "kotlin"], "backend order is invalid")
        else:
            bounded_string(item, key)
    return value


def lane_failures(
    lane: dict[str, Any],
    expected_api: int,
    candidate_sha: str,
    tree_sha: str,
    workflow_sha: str,
) -> list[str]:
    failures: list[str] = []
    checks = (
        (lane["candidateSha"] == candidate_sha, "candidate SHA mismatch"),
        (lane["treeSha"] == tree_sha, "tree SHA mismatch"),
        (lane["workflowSha"] == workflow_sha == candidate_sha, "workflow SHA mismatch"),
        (lane["eventName"] == "workflow_dispatch", "unexpected workflow event"),
        (lane["emulatorApi"] == expected_api, "emulator API mismatch"),
        (lane["emulatorAbi"] == "x86_64", "emulator ABI mismatch"),
        (lane["emulatorPageSize"].isdigit() and int(lane["emulatorPageSize"]) > 0, "invalid page size"),
        (lane["systemImagePackage"] == f"system-images;android-{expected_api};default;x86_64", "system image mismatch"),
        (lane["frpVersion"] == "v0.69.1", "frp version mismatch"),
        (bool(SHA256.fullmatch(lane["frpExpectedSha256"])), "invalid expected frp hash"),
        (lane["frpActualSha256"] == lane["frpExpectedSha256"], "frp archive hash mismatch"),
        (bool(SHA256.fullmatch(lane["testApkSha256"])), "invalid Android test APK hash"),
        (bool(SHA256.fullmatch(lane["bridgeAarSha256"])), "invalid GoMobile bridge AAR hash"),
        (lane["interopOutcome"] == "success", "interop task did not pass"),
        (lane["scenario"] == "wire=v1 encryption=false compression=false", "unexpected scenario"),
        (
            lane["testCommand"]
            == ":frpc-stcp-visitor:frpcAndroidInteropTest -PrequireGoMobileBridge=true",
            "unexpected test command",
        ),
    )
    failures.extend(message for passed, message in checks if not passed)
    return failures


def write_report(
    path: Path,
    *,
    language: str,
    status: str,
    candidate_sha: str,
    tree_sha: str,
    workflow_sha: str,
    lanes: list[dict[str, Any]],
    failures: list[str],
) -> None:
    run_url = lanes[0]["runUrl"] if lanes else "unavailable"
    if language == "en":
        title = "# K6V Android STCP Interoperability Acceptance Report"
        labels = {
            "status": "Status",
            "candidate": "Candidate commit",
            "tree": "Tree",
            "workflow": "Workflow commit",
            "run": "Workflow run",
            "matrix": "## Emulator Matrix",
            "api": "API",
            "abi": "ABI",
            "page": "Page size",
            "image": "System image",
            "result": "Result",
            "failures": "## Blocking Findings",
            "limits": "## Scope and Limits",
            "pass": "Pass",
            "fail": "Fail",
        }
        limits = (
            "This evidence covers real GoMobile then pure Kotlin visitors in separate instrumentation processes, "
            "using one fully stopped session and the same rebound loopback port, official frp v0.69.1, TLS, REST, "
            "global/project SSE, concurrent echo, and larger-than-window downloads. It covers only x86_64 emulators "
            "and the phase-one `wire=v1`, encryption-off, compression-off scenario. It does not replace physical "
            "arm devices, 16 KB page-size validation, Doze/network-switch tests, performance, or soak evidence. "
            "A passing K6V report does not authorize the K7 Kotlin-default switch."
        )
    else:
        title = "# K6V Android STCP 互操作验收报告"
        labels = {
            "status": "状态",
            "candidate": "候选提交",
            "tree": "Tree",
            "workflow": "工作流提交",
            "run": "工作流运行",
            "matrix": "## Emulator 矩阵",
            "api": "API",
            "abi": "ABI",
            "page": "Page size",
            "image": "System image",
            "result": "结果",
            "failures": "## 阻断项",
            "limits": "## 范围与限制",
            "pass": "通过",
            "fail": "失败",
        }
        limits = (
            "本证据在相互独立的 instrumentation 进程中依次运行真实 GoMobile 与纯 Kotlin visitor，旧 session "
            "完整停止后复用同一个 loopback 端口，并覆盖官方 frp v0.69.1、TLS、REST、全局/项目 SSE、并发 "
            "echo 和超过窗口的大下载。当前只覆盖 x86_64 emulator 与第一阶段 `wire=v1`、关闭加密、关闭压缩场景，"
            "不能替代 arm 真机、16 KB page-size、Doze/网络切换、性能或 soak 证据。K6V 通过不代表已授权 K7 "
            "切换 Kotlin 默认实现。"
        )

    lines = [
        title,
        "",
        f"- {labels['status']}: **{labels['pass'] if status == 'Pass' else labels['fail']}**",
        f"- {labels['candidate']}: `{candidate_sha}`",
        f"- {labels['tree']}: `{tree_sha}`",
        f"- {labels['workflow']}: `{workflow_sha}`",
        f"- {labels['run']}: {run_url}",
        "",
        labels["matrix"],
        "",
        f"| {labels['api']} | {labels['abi']} | {labels['page']} | {labels['image']} | {labels['result']} |",
        "| --- | --- | --- | --- | --- |",
    ]
    for lane in sorted(lanes, key=lambda item: item["emulatorApi"]):
        lane_result = labels["pass"] if lane["interopOutcome"] == "success" else labels["fail"]
        lines.append(
            f"| {lane['emulatorApi']} | `{lane['emulatorAbi']}` | {lane['emulatorPageSize']} | "
            f"`{lane['systemImagePackage']}` revision `{lane['systemImageRevision']}` | {lane_result} |"
        )
    if failures:
        lines.extend(["", labels["failures"], ""])
        lines.extend(f"- {failure}" for failure in failures)
    lines.extend(["", labels["limits"], "", limits, ""])
    path.write_text("\n".join(lines), encoding="utf-8", newline="\n")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-directory", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--tree-sha", required=True)
    parser.add_argument("--workflow-sha", required=True)
    parser.add_argument(
        "--matrix-result",
        choices=("success", "failure", "cancelled", "skipped"),
        required=True,
    )
    parser.add_argument("--expected-api", type=int, action="append", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    require(bool(SHA1.fullmatch(args.candidate_sha)), "candidate SHA is invalid")
    require(bool(SHA1.fullmatch(args.workflow_sha)), "workflow SHA is invalid")
    require(bool(SHA1.fullmatch(args.tree_sha)), "tree SHA is invalid")
    expected_apis = sorted(set(args.expected_api))
    require(expected_apis and all(api >= 26 for api in expected_apis), "expected API matrix is invalid")
    args.output_directory.mkdir(parents=True, exist_ok=True)

    lanes: list[dict[str, Any]] = []
    failures: list[str] = []
    if args.matrix_result != "success":
        failures.append(f"Android interop matrix result was {args.matrix_result}")
    for api in expected_apis:
        matches = list(args.evidence_directory.rglob(f"api-{api}.json"))
        if len(matches) != 1:
            failures.append(f"API {api}: expected one evidence file, found {len(matches)}")
            continue
        try:
            lane = load_lane(matches[0])
            lanes.append(lane)
            failures.extend(
                f"API {api}: {failure}"
                for failure in lane_failures(
                    lane,
                    api,
                    args.candidate_sha,
                    args.tree_sha,
                    args.workflow_sha,
                )
            )
        except ReportError as error:
            failures.append(f"API {api}: {error}")

    status = "Pass" if not failures and len(lanes) == len(expected_apis) else "Fail"
    consolidated = {
        "schemaVersion": 1,
        "status": status,
        "candidateSha": args.candidate_sha,
        "treeSha": args.tree_sha,
        "workflowSha": args.workflow_sha,
        "matrixResult": args.matrix_result,
        "expectedApis": expected_apis,
        "failures": failures,
        "lanes": sorted(lanes, key=lambda item: item["emulatorApi"]),
    }
    evidence_output = args.output_directory / "evidence.json"
    evidence_output.write_text(
        json.dumps(consolidated, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    english = args.output_directory / "acceptance-report.md"
    chinese = args.output_directory / "acceptance-report.zh-CN.md"
    write_report(
        english,
        language="en",
        status=status,
        candidate_sha=args.candidate_sha,
        tree_sha=args.tree_sha,
        workflow_sha=args.workflow_sha,
        lanes=lanes,
        failures=failures,
    )
    write_report(
        chinese,
        language="zh-CN",
        status=status,
        candidate_sha=args.candidate_sha,
        tree_sha=args.tree_sha,
        workflow_sha=args.workflow_sha,
        lanes=lanes,
        failures=failures,
    )
    checksum = args.output_directory / "SHA256SUMS"
    checksum.write_text(
        "".join(f"{sha256(path)}  {path.name}\n" for path in (english, chinese, evidence_output)),
        encoding="utf-8",
        newline="\n",
    )
    if status != "Pass":
        print("K6V Android interop report is incomplete or failed", file=sys.stderr)
        return 1
    print("K6V Android interop report passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ReportError as error:
        print(f"K6V Android interop report failed: {error}", file=sys.stderr)
        raise SystemExit(1)
