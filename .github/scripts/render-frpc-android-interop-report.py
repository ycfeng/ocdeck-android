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
MAX_EVIDENCE_BYTES = 64 * 1024
MAX_TEXT_LENGTH = 1_024
BACKEND_ORDER = ["gomobile", "kotlin"]
EXPECTED_SEMANTIC_OUTCOMES = {
    "success": "success",
    "wrong_token": "control_failure",
    "wrong_secret": "stcp_secret_rejected",
    "bind_conflict": "bind_conflict",
    "restart": "recovered",
}
SUCCESS_CHECKS = [
    "open_state",
    "health",
    "global_sse",
    "project_sse",
    "concurrent_rest_sse",
    "closed",
    "port_released",
]
WRONG_TOKEN_CHECKS = [
    "control_rejected",
    "session_failed",
    "listener_not_bound",
    "closed",
    "port_released",
]
WRONG_SECRET_CHECKS = [
    "open_state",
    "health_rejected",
    "listener_consistent",
    "closed",
    "port_released",
]
BIND_CONFLICT_CHECKS = [
    "bind_rejected",
    "visitor_failed",
    "listener_not_bound",
    "closed",
    "port_released",
]
RESTART_CHECKS = [
    "initial_open_state",
    "initial_health",
    "initial_global_sse",
    "initial_project_sse",
    "sse_disconnected",
    "control_unavailable",
    "control_epoch_advanced",
    "recovered_open_state",
    "health",
    "global_sse",
    "project_sse",
    "concurrent_rest_sse",
    "closed",
    "port_released",
]
FULL_PROFILES = [
    ("success-v1-00", "success", "v1", False, False, SUCCESS_CHECKS),
    ("success-v1-01", "success", "v1", False, True, SUCCESS_CHECKS),
    ("success-v1-10", "success", "v1", True, False, SUCCESS_CHECKS),
    ("success-v1-11", "success", "v1", True, True, SUCCESS_CHECKS),
    ("success-v2-00", "success", "v2", False, False, SUCCESS_CHECKS),
    ("success-v2-01", "success", "v2", False, True, SUCCESS_CHECKS),
    ("success-v2-10", "success", "v2", True, False, SUCCESS_CHECKS),
    ("success-v2-11", "success", "v2", True, True, SUCCESS_CHECKS),
    ("negative-wrong-token-v2-00", "wrong_token", "v2", False, False, WRONG_TOKEN_CHECKS),
    ("negative-wrong-secret-v2-00", "wrong_secret", "v2", False, False, WRONG_SECRET_CHECKS),
    ("negative-bind-conflict-v2-00", "bind_conflict", "v2", False, False, BIND_CONFLICT_CHECKS),
    ("restart-v2-11", "restart", "v2", True, True, RESTART_CHECKS),
]
EXPECTED_LANES = {
    26: ("legacy-api26-x86_64", "compat", FULL_PROFILES[:1]),
    36: ("modern-api36-x86_64", "full", FULL_PROFILES),
}
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
    "laneId",
    "suite",
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
    "testCommand",
    "hostSummary",
}
SUMMARY_KEYS = {
    "schemaVersion",
    "suite",
    "device",
    "backendOrder",
    "profiles",
    "authorizesKotlinDefault",
}
SUMMARY_DEVICE_KEYS = {"apiLevel", "abi", "pageSize"}
SUMMARY_PROFILE_KEYS = {
    "caseId",
    "scenario",
    "wireProtocol",
    "useEncryption",
    "useCompression",
    "backends",
    "equivalent",
}
SUMMARY_BACKEND_KEYS = {"backend", "semanticOutcome", "checks"}


class ReportError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReportError(message)


def bounded_string(value: Any, label: str) -> str:
    require(isinstance(value, str), f"{label} must be a string")
    require(0 < len(value) <= MAX_TEXT_LENGTH, f"{label} has an invalid length")
    require("\x00" not in value and "\r" not in value and "\n" not in value, f"{label} contains control characters")
    require(not any(0xD800 <= ord(character) <= 0xDFFF for character in value), f"{label} contains invalid Unicode")
    return value


def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ReportError("evidence contains a duplicate JSON key")
        value[key] = item
    return value


def load_lane(path: Path) -> dict[str, Any]:
    require(path.is_file() and not path.is_symlink(), f"invalid evidence file: {path.name}")
    require(path.stat().st_size <= MAX_EVIDENCE_BYTES, f"evidence file is too large: {path.name}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReportError(f"invalid evidence JSON: {path.name}") from error
    require(isinstance(value, dict), f"evidence root must be an object: {path.name}")
    require(set(value) == EXPECTED_KEYS, f"evidence keys are invalid: {path.name}")
    require(
        type(value["schemaVersion"]) is int and value["schemaVersion"] == 2,
        f"unsupported evidence schema: {path.name}",
    )
    for key, item in value.items():
        if key in {"schemaVersion", "emulatorApi", "hostSummary"}:
            continue
        bounded_string(item, key)
    require(type(value["emulatorApi"]) is int, "emulatorApi must be an integer")
    return value


def validate_host_summary(
    summary: Any,
    *,
    expected_suite: str,
    expected_profiles: list[tuple[str, str, str, bool, bool, list[str]]],
    expected_api: int,
    expected_abi: str,
    expected_page_size: int,
) -> None:
    require(isinstance(summary, dict), "host summary is missing")
    require(set(summary) == SUMMARY_KEYS, "host summary keys are invalid")
    require(type(summary["schemaVersion"]) is int and summary["schemaVersion"] == 1, "host summary schema is invalid")
    require(summary["suite"] == expected_suite, "host summary suite is invalid")
    require(summary["backendOrder"] == BACKEND_ORDER, "host summary backend order is invalid")
    require(summary["authorizesKotlinDefault"] is False, "host summary must not authorize Kotlin-default")

    device = summary["device"]
    require(isinstance(device, dict) and set(device) == SUMMARY_DEVICE_KEYS, "host summary device is invalid")
    require(type(device["apiLevel"]) is int and device["apiLevel"] == expected_api, "host summary API is invalid")
    require(device["abi"] == expected_abi, "host summary ABI is invalid")
    require(
        type(device["pageSize"]) is int and device["pageSize"] == expected_page_size,
        "host summary page size is invalid",
    )

    profiles = summary["profiles"]
    require(isinstance(profiles, list), "host summary profiles must be a list")
    require(len(profiles) == len(expected_profiles), "host summary profile count is invalid")
    for index, (profile, expected) in enumerate(zip(profiles, expected_profiles, strict=True)):
        case_id, scenario, wire, encryption, compression, checks = expected
        require(isinstance(profile, dict) and set(profile) == SUMMARY_PROFILE_KEYS, f"profile {index} keys are invalid")
        require(profile["caseId"] == case_id, f"profile {index} identity is invalid")
        require(profile["scenario"] == scenario, f"profile {case_id} scenario is invalid")
        require(profile["wireProtocol"] == wire, f"profile {case_id} wire protocol is invalid")
        require(profile["useEncryption"] is encryption, f"profile {case_id} encryption flag is invalid")
        require(profile["useCompression"] is compression, f"profile {case_id} compression flag is invalid")
        require(profile["equivalent"] is True, f"profile {case_id} was not equivalent")
        expected_outcome = EXPECTED_SEMANTIC_OUTCOMES[scenario]
        backends = profile["backends"]
        require(isinstance(backends, list) and len(backends) == 2, f"profile {case_id} backends are invalid")
        for backend_index, backend in enumerate(backends):
            require(
                isinstance(backend, dict) and set(backend) == SUMMARY_BACKEND_KEYS,
                f"profile {case_id} backend keys are invalid",
            )
            require(backend["backend"] == BACKEND_ORDER[backend_index], f"profile {case_id} backend order is invalid")
            require(
                backend["semanticOutcome"] == expected_outcome,
                f"profile {case_id} semantic outcome is invalid",
            )
            require(backend["checks"] == checks, f"profile {case_id} checks are invalid")


def lane_failures(
    lane: dict[str, Any],
    expected_api: int,
    candidate_sha: str,
    tree_sha: str,
    workflow_sha: str,
) -> list[str]:
    failures: list[str] = []
    expected_lane_id, expected_suite, expected_profiles = EXPECTED_LANES[expected_api]
    page_size = int(lane["emulatorPageSize"]) if lane["emulatorPageSize"].isdigit() else 0
    expected_command = (
        ":frpc-stcp-visitor:frpcAndroidInteropTest -PrequireGoMobileBridge=true "
        f"-Pocdeck.frp.androidInterop.suite={expected_suite} "
        "-Pocdeck.frp.androidInterop.summaryFile=<runner-temp>"
    )
    checks = (
        (lane["candidateSha"] == candidate_sha, "candidate SHA mismatch"),
        (lane["treeSha"] == tree_sha, "tree SHA mismatch"),
        (lane["workflowSha"] == workflow_sha == candidate_sha, "workflow SHA mismatch"),
        (lane["eventName"] == "workflow_dispatch", "unexpected workflow event"),
        (lane["laneId"] == expected_lane_id, "lane identity mismatch"),
        (lane["suite"] == expected_suite, "lane suite mismatch"),
        (lane["emulatorApi"] == expected_api, "emulator API mismatch"),
        (lane["emulatorAbi"] == "x86_64", "emulator ABI mismatch"),
        (page_size > 0, "invalid page size"),
        (lane["systemImagePackage"] == f"system-images;android-{expected_api};default;x86_64", "system image mismatch"),
        (lane["frpVersion"] == "v0.69.1", "frp version mismatch"),
        (bool(SHA256.fullmatch(lane["frpExpectedSha256"])), "invalid expected frp hash"),
        (lane["frpActualSha256"] == lane["frpExpectedSha256"], "frp archive hash mismatch"),
        (bool(SHA256.fullmatch(lane["testApkSha256"])), "invalid Android test APK hash"),
        (bool(SHA256.fullmatch(lane["bridgeAarSha256"])), "invalid GoMobile bridge AAR hash"),
        (lane["interopOutcome"] == "success", "interop task did not pass"),
        (lane["testCommand"] == expected_command, "unexpected test command"),
    )
    failures.extend(message for passed, message in checks if not passed)
    try:
        validate_host_summary(
            lane["hostSummary"],
            expected_suite=expected_suite,
            expected_profiles=expected_profiles,
            expected_api=expected_api,
            expected_abi="x86_64",
            expected_page_size=page_size,
        )
    except ReportError as error:
        failures.append(str(error))
    return failures


def write_report(
    path: Path,
    *,
    language: str,
    status: str,
    candidate_sha: str,
    tree_sha: str,
    workflow_sha: str,
    validated_lanes: list[tuple[int, dict[str, Any], bool]],
    failures: list[str],
) -> None:
    passed_lanes = [lane for _, lane, lane_passed in validated_lanes if lane_passed]
    run_url = passed_lanes[0]["runUrl"] if passed_lanes else "unavailable"
    if language == "en":
        title = "# K6V Android STCP Interoperability Acceptance Report"
        labels = {
            "status": "Status", "candidate": "Candidate commit", "tree": "Tree",
            "workflow": "Workflow commit", "run": "Workflow run", "matrix": "## Emulator Matrix",
            "lane": "Lane", "api": "API", "abi": "ABI", "page": "Page size", "suite": "Suite",
            "profiles": "Profiles", "result": "Result", "profile_matrix": "## Profile Evidence",
            "profile": "Profile", "scenario": "Scenario", "wire": "Wire", "payload": "Payload",
            "gomobile": "GoMobile", "kotlin": "Kotlin", "equivalent": "Equivalent",
            "failures": "## Blocking Findings", "limits": "## Scope and Limits", "pass": "Pass", "fail": "Fail",
        }
        limits = (
            "This evidence runs real GoMobile then pure Kotlin visitors in separate instrumentation processes, with "
            "complete stop/Closed/port-release checks and same-port rebinding. API 36 covers wire v1/v2, all four "
            "encryption/compression modes, wrong token, wrong STCP secret, bind conflict, and frps restart/control-epoch "
            "recovery while REST, global/project SSE, concurrent echo, and larger-than-window downloads are exercised. "
            "API 26 provides the v1/plain compatibility baseline. It covers only x86_64 emulators and does not replace "
            "physical arm devices, 16 KB page-size validation, App Store/snapshot reconciliation, Doze/network-switch "
            "tests, performance, or soak evidence. A passing K6V report does not authorize the K7 Kotlin-default switch."
        )
    else:
        title = "# K6V Android STCP 互操作验收报告"
        labels = {
            "status": "状态", "candidate": "候选提交", "tree": "Tree", "workflow": "工作流提交",
            "run": "工作流运行", "matrix": "## Emulator 矩阵", "lane": "Lane", "api": "API", "abi": "ABI",
            "page": "Page size", "suite": "Suite", "profiles": "Profiles", "result": "结果",
            "profile_matrix": "## Profile 证据", "profile": "Profile", "scenario": "场景", "wire": "Wire",
            "payload": "Payload", "gomobile": "GoMobile", "kotlin": "Kotlin", "equivalent": "等价",
            "failures": "## 阻断项", "limits": "## 范围与限制", "pass": "通过", "fail": "失败",
        }
        limits = (
            "本证据在独立 instrumentation 进程中依次运行真实 GoMobile 与纯 Kotlin visitor，并验证完整停止、"
            "Closed、端口释放及同端口重绑。API 36 覆盖 wire v1/v2、四种加密/压缩组合、错误 token、错误 STCP "
            "secret、端口冲突，以及 frps 重启/control epoch 恢复，同时验证 REST、全局/项目 SSE、并发 echo 和超过"
            "窗口的大下载；API 26 提供 v1/plain 兼容基线。当前仅覆盖 x86_64 emulator，不能替代 arm 真机、16 KB "
            "page-size、App Store/快照协调、Doze/网络切换、性能或 soak 证据。K6V 通过不代表已授权 K7 切换 Kotlin "
            "默认实现。"
        )

    lines = [
        title, "", f"- {labels['status']}: **{labels['pass'] if status == 'Pass' else labels['fail']}**",
        f"- {labels['candidate']}: `{candidate_sha}`", f"- {labels['tree']}: `{tree_sha}`",
        f"- {labels['workflow']}: `{workflow_sha}`", f"- {labels['run']}: {run_url}", "", labels["matrix"], "",
        f"| {labels['lane']} | {labels['api']} | {labels['abi']} | {labels['page']} | {labels['suite']} | {labels['profiles']} | {labels['result']} |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for expected_api, lane, lane_passed in sorted(validated_lanes, key=lambda item: item[0]):
        expected_lane_id, expected_suite, expected_profiles = EXPECTED_LANES[expected_api]
        summary = lane.get("hostSummary") if lane_passed else None
        profile_count = len(summary["profiles"]) if isinstance(summary, dict) else 0
        lane_result = labels["pass"] if lane_passed else labels["fail"]
        lines.append(
            f"| `{expected_lane_id}` | {expected_api} | `x86_64` | "
            f"{lane['emulatorPageSize'] if lane_passed else 'invalid'} | `{expected_suite}` | "
            f"{profile_count if lane_passed else 0} | {lane_result} |"
        )
    lines.extend([
        "", labels["profile_matrix"], "",
        f"| {labels['lane']} | {labels['profile']} | {labels['scenario']} | {labels['wire']} | {labels['payload']} | {labels['gomobile']} | {labels['kotlin']} | {labels['equivalent']} |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ])
    for expected_api, lane, lane_passed in sorted(validated_lanes, key=lambda item: item[0]):
        expected_lane_id, _, _ = EXPECTED_LANES[expected_api]
        if not lane_passed:
            lines.append(
                f"| `{expected_lane_id}` | `invalid evidence` | `invalid` | `invalid` | invalid | "
                "`invalid` / 0 checks | `invalid` / 0 checks | no |"
            )
            continue
        summary = lane.get("hostSummary")
        if not isinstance(summary, dict) or not isinstance(summary.get("profiles"), list):
            continue
        for profile in summary["profiles"]:
            if not isinstance(profile, dict):
                continue
            payload = f"E={int(profile.get('useEncryption') is True)}, C={int(profile.get('useCompression') is True)}"
            backends_value = profile.get("backends")
            backends = backends_value if isinstance(backends_value, list) else []
            go_backend = backends[0] if len(backends) > 0 and isinstance(backends[0], dict) else {}
            kotlin_backend = backends[1] if len(backends) > 1 and isinstance(backends[1], dict) else {}
            go_checks_value = go_backend.get("checks")
            kotlin_checks_value = kotlin_backend.get("checks")
            go_checks = len(go_checks_value) if isinstance(go_checks_value, list) else 0
            kotlin_checks = len(kotlin_checks_value) if isinstance(kotlin_checks_value, list) else 0
            go_outcome = go_backend.get("semanticOutcome") if isinstance(go_backend.get("semanticOutcome"), str) else "invalid"
            kotlin_outcome = (
                kotlin_backend.get("semanticOutcome")
                if isinstance(kotlin_backend.get("semanticOutcome"), str)
                else "invalid"
            )
            lines.append(
                f"| `{expected_lane_id}` | `{profile.get('caseId', 'invalid')}` | `{profile.get('scenario', 'invalid')}` | "
                f"`{profile.get('wireProtocol', 'invalid')}` | {payload} | `{go_outcome}` / {go_checks} checks | "
                f"`{kotlin_outcome}` / {kotlin_checks} checks | "
                f"{'yes' if profile.get('equivalent') is True else 'no'} |"
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
    parser.add_argument("--matrix-result", choices=("success", "failure", "cancelled", "skipped"), required=True)
    parser.add_argument("--expected-api", type=int, action="append", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    require(bool(SHA1.fullmatch(args.candidate_sha)), "candidate SHA is invalid")
    require(bool(SHA1.fullmatch(args.workflow_sha)), "workflow SHA is invalid")
    require(bool(SHA1.fullmatch(args.tree_sha)), "tree SHA is invalid")
    expected_apis = sorted(set(args.expected_api))
    require(expected_apis == sorted(EXPECTED_LANES), "expected API matrix is invalid")
    args.output_directory.mkdir(parents=True, exist_ok=True)

    lanes: list[dict[str, Any]] = []
    validated_lanes: list[tuple[int, dict[str, Any], bool]] = []
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
            current_failures = lane_failures(lane, api, args.candidate_sha, args.tree_sha, args.workflow_sha)
            validated_lanes.append((api, lane, not current_failures))
            if not current_failures:
                lanes.append(lane)
            failures.extend(f"API {api}: {failure}" for failure in current_failures)
        except ReportError as error:
            failures.append(f"API {api}: {error}")

    status = "Pass" if not failures and len(lanes) == len(expected_apis) else "Fail"
    consolidated = {
        "schemaVersion": 2,
        "status": status,
        "candidateSha": args.candidate_sha,
        "treeSha": args.tree_sha,
        "workflowSha": args.workflow_sha,
        "matrixResult": args.matrix_result,
        "expectedApis": expected_apis,
        "failures": failures,
        "lanes": sorted(lanes, key=lambda item: item["emulatorApi"]),
        "authorizesKotlinDefault": False,
    }
    evidence_output = args.output_directory / "evidence.json"
    evidence_output.write_text(json.dumps(consolidated, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    english = args.output_directory / "acceptance-report.md"
    chinese = args.output_directory / "acceptance-report.zh-CN.md"
    write_report(
        english, language="en", status=status, candidate_sha=args.candidate_sha, tree_sha=args.tree_sha,
        workflow_sha=args.workflow_sha, validated_lanes=validated_lanes, failures=failures,
    )
    write_report(
        chinese, language="zh-CN", status=status, candidate_sha=args.candidate_sha, tree_sha=args.tree_sha,
        workflow_sha=args.workflow_sha, validated_lanes=validated_lanes, failures=failures,
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
