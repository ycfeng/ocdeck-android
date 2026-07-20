#!/usr/bin/env python3
"""Audit OC Deck community, documentation, and release-note metadata."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[2]
CANONICAL_REPOSITORY = "https://github.com/ycfeng/ocdeck-android"
PVR_URL = f"{CANONICAL_REPOSITORY}/security/advisories/new"


class AuditError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AuditError(message)


def read_text(relative: str | Path) -> str:
    path = ROOT / relative
    require(path.is_file(), f"required file is missing: {path.relative_to(ROOT)}")
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError as error:
        raise AuditError(f"file is not UTF-8: {path.relative_to(ROOT)}") from error


def read_properties(relative: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in read_text(relative).splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        require(separator == "=", f"invalid property line in {relative}: {raw_line!r}")
        result[key.strip()] = value.strip()
    return result


def load_yaml(relative: str) -> Any:
    path = ROOT / relative
    read_text(relative)
    try:
        import yaml  # type: ignore[import-not-found]

        return yaml.safe_load(path.read_text(encoding="utf-8"))
    except ImportError:
        ruby = shutil.which("ruby")
        require(ruby is not None, "YAML audit requires PyYAML or Ruby's standard yaml library")
        command = [
            ruby,
            "-e",
            "require 'yaml'; require 'json'; puts JSON.generate(YAML.safe_load(File.read(ARGV[0]), permitted_classes: [], aliases: false))",
            str(path),
        ]
        process = subprocess.run(command, text=True, capture_output=True, check=False)
        require(process.returncode == 0, f"invalid YAML in {relative}: {process.stderr.strip()}")
        return json.loads(process.stdout)
    except Exception as error:
        raise AuditError(f"invalid YAML in {relative}: {error}") from error


def markdown_files() -> list[Path]:
    files = list(ROOT.glob("*.md"))
    files.extend((ROOT / "doc").rglob("*.md"))
    files.extend((ROOT / "release-notes").glob("*.md"))
    files.extend((ROOT / ".github").glob("*.md"))
    return sorted(set(files))


def audit_relative_links() -> int:
    link_pattern = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
    checked = 0
    for path in markdown_files():
        text = path.read_text(encoding="utf-8")
        for raw_target in link_pattern.findall(text):
            target = raw_target.strip().strip("<>").split(maxsplit=1)[0]
            if not target or target.startswith(("#", "http://", "https://", "mailto:")):
                continue
            if "{{" in target or "}}" in target:
                continue
            target = unquote(target).split("#", 1)[0]
            if not target:
                continue
            resolved = (path.parent / target).resolve()
            require(
                resolved.is_relative_to(ROOT.resolve()),
                f"relative link escapes repository: {path.relative_to(ROOT)} -> {target}",
            )
            require(
                resolved.exists(),
                f"broken relative link: {path.relative_to(ROOT)} -> {target}",
            )
            checked += 1
    return checked


def audit_document_pairs() -> int:
    root_pairs = [
        "AGENTS",
        "README",
        "CONTRIBUTING",
        "CODE_OF_CONDUCT",
        "SECURITY",
        "PRIVACY",
        "SUPPORT",
        "TRADEMARKS",
    ]
    pairs: list[tuple[Path, Path]] = []
    for name in root_pairs:
        pairs.append((ROOT / f"{name}.md", ROOT / f"{name}.zh-CN.md"))

    for english in sorted((ROOT / "doc").rglob("*.md")):
        if english.name.endswith(".zh-CN.md"):
            continue
        chinese = english.with_name(f"{english.stem}.zh-CN.md")
        pairs.append((english, chinese))

    for english, chinese in pairs:
        require(english.is_file(), f"missing English document: {english.relative_to(ROOT)}")
        require(chinese.is_file(), f"missing Chinese document: {chinese.relative_to(ROOT)}")
        english_head = "\n".join(english.read_text(encoding="utf-8").splitlines()[:10])
        chinese_head = "\n".join(chinese.read_text(encoding="utf-8").splitlines()[:10])
        require(chinese.name in english_head, f"English document does not link its Chinese pair: {english.relative_to(ROOT)}")
        require(english.name in chinese_head, f"Chinese document does not link its English pair: {chinese.relative_to(ROOT)}")
    return len(pairs)


def audit_issue_forms() -> int:
    form_paths = [
        ".github/ISSUE_TEMPLATE/01-bug-report.yml",
        ".github/ISSUE_TEMPLATE/02-feature-request.yml",
        ".github/ISSUE_TEMPLATE/03-question.yml",
    ]
    expected_labels = {
        form_paths[0]: {"bug", "needs-triage"},
        form_paths[1]: {"enhancement", "needs-triage"},
        form_paths[2]: {"needs-triage"},
    }
    sensitive_terms = (
        "token",
        "api key",
        "password",
        "cookie",
        "ssh private key",
        "passphrase",
        "host fingerprint",
        "complete configuration",
        "provider",
        "private url",
        "project source",
        "complete server response",
    )
    for relative in form_paths:
        value = load_yaml(relative)
        require(isinstance(value, dict), f"issue form must be an object: {relative}")
        require(isinstance(value.get("name"), str), f"issue form is missing name: {relative}")
        require(isinstance(value.get("description"), str), f"issue form is missing description: {relative}")
        labels = value.get("labels")
        require(isinstance(labels, list), f"issue form labels must be a list: {relative}")
        require(set(labels) == expected_labels[relative], f"issue form has unexpected labels: {relative}")
        body = value.get("body")
        require(isinstance(body, list) and body, f"issue form has no body: {relative}")
        ids = [item.get("id") for item in body if isinstance(item, dict) and item.get("id")]
        require(len(ids) == len(set(ids)), f"issue form has duplicate ids: {relative}")
        text = read_text(relative).lower()
        for term in sensitive_terms:
            require(term in text, f"issue form omits sensitive-data warning {term!r}: {relative}")
        required_environment_fields = (
            ("oc deck version", "app version"),
            ("android version",),
            ("device abi",),
            ("opencode server",),
            ("connection mode",),
        )
        for alternatives in required_environment_fields:
            require(
                any(term in text for term in alternatives),
                f"issue form omits required environment field {alternatives[0]!r}: {relative}",
            )
        require("direct /" in text and "ssh" in text and "stcp" in text, f"issue form omits connection choices: {relative}")
        require(PVR_URL in text, f"issue form does not route security reports to PVR: {relative}")
        require("required: true" in text, f"issue form has no required fields: {relative}")
        require("redacted" in text and "attached no logs" in text, f"issue form lacks log-redaction confirmation: {relative}")

    config = load_yaml(".github/ISSUE_TEMPLATE/config.yml")
    require(isinstance(config, dict), "issue chooser config must be an object")
    require(config.get("blank_issues_enabled") is False, "blank issues must be disabled")
    config_text = read_text(".github/ISSUE_TEMPLATE/config.yml")
    require(f"{CANONICAL_REPOSITORY}/blob/main/SUPPORT.md" in config_text, "issue chooser lacks support policy")
    require(f"{CANONICAL_REPOSITORY}/blob/main/SECURITY.md" in config_text, "issue chooser lacks security policy")
    require(PVR_URL in config_text, "issue chooser lacks private conduct-report route")
    require("Private Code of Conduct report" in config_text, "issue chooser does not identify the private conduct route")
    return len(form_paths)


def audit_release_metadata(version_name: str) -> None:
    release_config = load_yaml(".github/release.yml")
    require(isinstance(release_config, dict), ".github/release.yml must be an object")
    changelog = release_config.get("changelog")
    require(isinstance(changelog, dict), ".github/release.yml is missing changelog")
    categories = changelog.get("categories")
    require(isinstance(categories, list) and categories, ".github/release.yml has no categories")
    require(categories[-1].get("labels") == ["*"], "release-note catch-all category must be last")
    category_titles: list[str] = []
    for category in categories:
        require(isinstance(category, dict), "release-note category must be an object")
        title = category.get("title")
        labels = category.get("labels")
        require(isinstance(title, str) and title.strip(), "release-note category is missing title")
        require(not re.match(r"^\s*#+\s*", title), f"release-note category title must not include Markdown heading markers: {title}")
        require(isinstance(labels, list) and labels, f"release-note category has no labels: {title}")
        category_titles.append(title)
    require(len(category_titles) == len(set(category_titles)), "release-note category titles must be unique")
    release_labels = {
        label
        for category in categories
        if isinstance(category, dict)
        for label in category.get("labels", [])
        if isinstance(label, str) and label.startswith("release-notes/")
    }
    expected_labels = {
        "release-notes/breaking",
        "release-notes/security",
        "release-notes/feature",
        "release-notes/fix",
        "release-notes/compatibility",
        "release-notes/migration",
        "release-notes/maintenance",
    }
    require(expected_labels <= release_labels, "release-note categories omit required labels")
    excluded = changelog.get("exclude", {}).get("labels", [])
    require("release-notes/skip" in excluded, "release-notes/skip must be excluded")

    pull_request_template = read_text(".github/PULL_REQUEST_TEMPLATE.md")
    for label in sorted(expected_labels | {"release-notes/skip"}):
        require(label in pull_request_template, f"PR template omits release label: {label}")

    template = read_text(".github/release-notes-template.md")
    version_notes_relative = f"release-notes/v{version_name}.md"
    version_notes = read_text(version_notes_relative)
    required_headings = [
        "Highlights / 亮点",
        "Upgrade and migration / 升级与迁移",
        "Compatibility / 兼容性",
        "Breaking changes / 破坏性变更",
        "Deprecated and removed / 弃用与移除",
        "Known issues / 已知问题",
        "Security and privacy / 安全与隐私",
        "Downloads and verification / 下载与校验",
    ]
    for heading in required_headings:
        require(heading in template, f"release-notes template omits section: {heading}")
        require(heading in version_notes, f"{version_notes_relative} omits section: {heading}")
    require(not re.search(r"(?<![A-Za-z0-9_])(TODO|TBD)(?![A-Za-z0-9_])", version_notes, re.IGNORECASE), f"{version_notes_relative} contains TODO/TBD")
    require(not re.search(r"\{\{[^{}\n]+\}\}", version_notes), f"{version_notes_relative} contains unresolved placeholders")
    require(f"## [{version_name}]" in read_text("CHANGELOG.md"), "CHANGELOG lacks current version")

    workflow = load_yaml(".github/workflows/release.yml")
    require(isinstance(workflow, dict), "release workflow must be an object")
    jobs = workflow.get("jobs")
    require(isinstance(jobs, dict), "release workflow has no jobs")
    expected_jobs = {"preflight", "prepare-notes", "frpc-interop", "build-release", "publish"}
    require(expected_jobs <= set(jobs), "release workflow omits required jobs")

    preflight_job = jobs["preflight"]
    prepare_job = jobs["prepare-notes"]
    interop_job = jobs["frpc-interop"]
    build_job = jobs["build-release"]
    publish_job = jobs["publish"]
    require(isinstance(preflight_job, dict), "preflight job must be an object")
    require(isinstance(prepare_job, dict), "prepare-notes job must be an object")
    require(isinstance(interop_job, dict), "frpc-interop job must be an object")
    require(isinstance(build_job, dict), "build-release job must be an object")
    require(isinstance(publish_job, dict), "publish job must be an object")
    require(prepare_job.get("needs") == "preflight", "prepare-notes must depend on preflight")
    require(interop_job.get("needs") == "preflight", "frpc-interop must depend on preflight")
    build_needs = build_job.get("needs")
    require(isinstance(build_needs, list), "build-release needs must be a list")
    require(set(build_needs) == {"preflight", "frpc-interop"}, "build-release has unexpected dependencies")
    publish_needs = publish_job.get("needs")
    require(isinstance(publish_needs, list), "publish needs must be a list")
    require(set(publish_needs) == {"preflight", "prepare-notes", "build-release"}, "publish has unexpected dependencies")
    require("environment" not in prepare_job, "prepare-notes must not access the release environment")
    require("environment" not in interop_job, "frpc-interop must not access the release environment")
    require(prepare_job.get("permissions", {}).get("contents") == "write", "prepare-notes needs scoped contents: write")
    require(interop_job.get("permissions", {}).get("contents") == "read", "frpc-interop must remain read-only")
    require(build_job.get("permissions", {}).get("contents") == "read", "build-release must remain read-only")
    require(publish_job.get("permissions", {}).get("contents") == "write", "publish needs scoped contents: write")

    def step_named(job: dict[str, Any], name: str) -> dict[str, Any]:
        steps = job.get("steps")
        require(isinstance(steps, list), f"job has no steps: {name}")
        matches = [step for step in steps if isinstance(step, dict) and step.get("name") == name]
        require(len(matches) == 1, f"release workflow must contain exactly one step named {name!r}")
        return matches[0]

    preflight_checkout = step_named(preflight_job, "Check out repository and tags").get("with")
    require(isinstance(preflight_checkout, dict), "preflight checkout must define inputs")
    require(preflight_checkout.get("fetch-depth") == 0, "preflight checkout must fetch complete history and refs")
    require(preflight_checkout.get("persist-credentials") is False, "preflight checkout must not persist credentials")
    version_check_run = step_named(preflight_job, "Check version and tag").get("run")
    require(isinstance(version_check_run, str), "Check version and tag must use a run script")
    require("check-release-version.sh" in version_check_run, "preflight does not run the release version check")
    require("git fetch" not in version_check_run, "preflight must rely on authenticated checkout instead of an unauthenticated fetch")

    reproducibility_run = step_named(build_job, "Verify reproducible GoMobile bridge").get("run")
    require(isinstance(reproducibility_run, str), "bridge reproducibility gate must use a run script")
    require(
        reproducibility_run.strip() == "bash .github/scripts/verify-bridge-reproducibility.sh",
        "release workflow does not use the canonical cross-checkout bridge reproducibility gate",
    )

    ci_workflow = load_yaml(".github/workflows/ci.yml")
    require(isinstance(ci_workflow, dict), "CI workflow must be an object")
    ci_jobs = ci_workflow.get("jobs")
    require(isinstance(ci_jobs, dict), "CI workflow has no jobs")
    ci_verify_job = ci_jobs.get("verify")
    ci_interop_job = ci_jobs.get("frpc-interop")
    ci_android_bootstrap_job = ci_jobs.get("k6v-android-interop-bootstrap")
    require(isinstance(ci_verify_job, dict), "CI workflow has no verify job")
    require(isinstance(ci_interop_job, dict), "CI workflow has no frpc-interop job")
    require(isinstance(ci_android_bootstrap_job, dict), "CI workflow has no K6V Android bootstrap job")
    require("environment" not in ci_interop_job, "CI frpc-interop must not access an environment")
    require("environment" not in ci_android_bootstrap_job, "CI K6V bootstrap must not access an environment")
    require(
        ci_android_bootstrap_job.get("uses") == "./.github/workflows/frpc-kotlin-android-interop.yml",
        "CI K6V bootstrap must call the canonical reusable workflow",
    )
    require(
        ci_android_bootstrap_job.get("permissions") == {"contents": "read"},
        "CI K6V bootstrap must have only read-only contents permission",
    )
    require(
        ci_android_bootstrap_job.get("if") == "${{ inputs.run_frpc_android_interop == true }}",
        "CI K6V bootstrap must remain explicitly opt-in",
    )
    bootstrap_inputs = ci_android_bootstrap_job.get("with")
    require(isinstance(bootstrap_inputs, dict), "CI K6V bootstrap must define reusable-workflow inputs")
    require(
        bootstrap_inputs.get("candidate_sha") == "${{ inputs.candidate_sha || github.sha }}",
        "CI K6V bootstrap must bind the candidate to an explicit SHA or the dispatched ref SHA",
    )
    ci_trigger = ci_workflow.get("on", ci_workflow.get(True))
    require(isinstance(ci_trigger, dict), "CI workflow trigger must be an object")
    ci_dispatch = ci_trigger.get("workflow_dispatch")
    require(isinstance(ci_dispatch, dict), "CI workflow_dispatch must define bootstrap inputs")
    ci_dispatch_inputs = ci_dispatch.get("inputs")
    require(isinstance(ci_dispatch_inputs, dict), "CI workflow_dispatch has no inputs")
    run_android_input = ci_dispatch_inputs.get("run_frpc_android_interop")
    require(isinstance(run_android_input, dict), "CI workflow_dispatch has no K6V opt-in input")
    require(run_android_input.get("type") == "boolean", "CI K6V opt-in input must be boolean")
    require(run_android_input.get("default") is False, "CI K6V bootstrap must default to disabled")
    bootstrap_sha_input = ci_dispatch_inputs.get("candidate_sha")
    require(isinstance(bootstrap_sha_input, dict), "CI workflow_dispatch has no candidate_sha input")
    require(bootstrap_sha_input.get("type") == "string", "CI bootstrap candidate_sha must be a string")
    ci_reproducibility_steps = [
        step
        for step in ci_verify_job.get("steps", [])
        if isinstance(step, dict) and step.get("name") == "Verify reproducible GoMobile bridge"
    ]
    require(len(ci_reproducibility_steps) == 1, "CI workflow must contain exactly one bridge reproducibility gate")
    ci_reproducibility_run = ci_reproducibility_steps[0].get("run")
    require(isinstance(ci_reproducibility_run, str), "CI bridge reproducibility gate must use a run script")
    require(
        ci_reproducibility_run.strip() == "bash .github/scripts/verify-bridge-reproducibility.sh",
        "CI workflow does not use the canonical cross-checkout bridge reproducibility gate",
    )
    release_interop_run = step_named(interop_job, "Run fixed-frp interoperability tests").get("run")
    ci_interop_steps = [
        step
        for step in ci_interop_job.get("steps", [])
        if isinstance(step, dict) and step.get("name") == "Run fixed-frp interoperability tests"
    ]
    require(len(ci_interop_steps) == 1, "CI workflow must contain exactly one fixed-frp interop gate")
    ci_interop_run = ci_interop_steps[0].get("run")
    expected_interop_run = "bash ./gradlew --no-daemon :frpc-stcp-visitor:frpcInteropTest"
    require(release_interop_run == expected_interop_run, "Release frpc-interop does not use the canonical task")
    require(ci_interop_run == expected_interop_run, "CI frpc-interop does not use the canonical task")
    build_step_text = "\n".join(
        str(step.get("run", "")) for step in build_job.get("steps", []) if isinstance(step, dict)
    )
    require("frpcInteropTest" not in build_step_text, "signed build-release must not launch downloaded frp binaries")

    android_workflow_path = ".github/workflows/frpc-kotlin-android-interop.yml"
    android_workflow_text = read_text(android_workflow_path)
    android_workflow = load_yaml(android_workflow_path)
    require(isinstance(android_workflow, dict), "Android interop workflow must be an object")
    android_trigger = android_workflow.get("on", android_workflow.get(True))
    require(isinstance(android_trigger, dict), "Android interop workflow trigger must be an object")
    require(
        set(android_trigger) == {"workflow_dispatch", "workflow_call"},
        "Android interop workflow must be directly manual or called only by the manual CI bootstrap",
    )
    dispatch = android_trigger.get("workflow_dispatch")
    require(isinstance(dispatch, dict), "Android interop workflow_dispatch must define inputs")
    dispatch_inputs = dispatch.get("inputs")
    require(isinstance(dispatch_inputs, dict), "Android interop workflow has no inputs")
    candidate_input = dispatch_inputs.get("candidate_sha")
    require(isinstance(candidate_input, dict), "Android interop workflow has no candidate_sha input")
    require(candidate_input.get("required") is True, "Android interop candidate_sha must be required")
    require(candidate_input.get("type") == "string", "Android interop candidate_sha must be a string")
    workflow_call = android_trigger.get("workflow_call")
    require(isinstance(workflow_call, dict), "Android interop workflow_call must define inputs")
    workflow_call_inputs = workflow_call.get("inputs")
    require(isinstance(workflow_call_inputs, dict), "Android interop workflow_call has no inputs")
    call_candidate_input = workflow_call_inputs.get("candidate_sha")
    require(isinstance(call_candidate_input, dict), "Android interop workflow_call has no candidate_sha input")
    require(call_candidate_input.get("required") is True, "Reusable Android candidate_sha must be required")
    require(call_candidate_input.get("type") == "string", "Reusable Android candidate_sha must be a string")
    require(
        android_workflow.get("permissions") == {"contents": "read"},
        "Android interop workflow must have only read-only contents permission",
    )
    require("secrets." not in android_workflow_text, "Android interop workflow must not access secrets")
    require("environment:" not in android_workflow_text, "Android interop workflow must not access an Environment")

    android_jobs = android_workflow.get("jobs")
    require(isinstance(android_jobs, dict), "Android interop workflow has no jobs")
    require(
        set(android_jobs) == {"preflight", "android-interop", "acceptance-report"},
        "Android interop workflow has unexpected jobs",
    )
    android_preflight = android_jobs["preflight"]
    android_matrix_job = android_jobs["android-interop"]
    android_report_job = android_jobs["acceptance-report"]
    for name, job in android_jobs.items():
        require(isinstance(job, dict), f"Android interop job must be an object: {name}")
        require("environment" not in job, f"Android interop job must not access an Environment: {name}")
        permissions = job.get("permissions")
        if permissions is not None:
            require(
                permissions == {"contents": "read"},
                f"Android interop job must have only read-only contents permission: {name}",
            )
        for step in job.get("steps", []):
            if isinstance(step, dict) and isinstance(step.get("uses"), str):
                require(
                    re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+@[0-9a-f]{40}", step["uses"]) is not None,
                    f"Android interop action must be pinned to a full commit SHA: {name}",
                )
    require(android_matrix_job.get("needs") == "preflight", "Android interop matrix must depend on preflight")
    report_needs = android_report_job.get("needs")
    require(isinstance(report_needs, list), "Android interop report needs must be a list")
    require(
        set(report_needs) == {"preflight", "android-interop"},
        "Android interop report has unexpected dependencies",
    )
    matrix = android_matrix_job.get("strategy", {}).get("matrix", {})
    require(matrix.get("api-level") == [26, 36], "Android interop emulator matrix must cover API 26 and 36")
    require(android_matrix_job.get("strategy", {}).get("fail-fast") is False, "Android interop matrix must not fail fast")

    def android_step_named(job: dict[str, Any], name: str) -> dict[str, Any]:
        steps = job.get("steps")
        require(isinstance(steps, list), f"Android interop job has no steps: {name}")
        matches = [step for step in steps if isinstance(step, dict) and step.get("name") == name]
        require(len(matches) == 1, f"Android interop workflow must contain exactly one step named {name!r}")
        return matches[0]

    android_checkout = android_step_named(android_preflight, "Check out exact candidate").get("with")
    require(isinstance(android_checkout, dict), "Android interop preflight checkout must define inputs")
    require(
        android_checkout.get("ref") == "${{ inputs.candidate_sha }}",
        "Android interop preflight must check out candidate_sha",
    )
    require(
        android_checkout.get("persist-credentials") is False,
        "Android interop checkout must not persist credentials",
    )
    identity_run = android_step_named(android_preflight, "Verify candidate and workflow identity").get("run")
    require(isinstance(identity_run, str), "Android interop identity check must use a run script")
    for token in ("GITHUB_SHA", "WORKFLOW_SHA", "git rev-parse HEAD", "HEAD^{tree}"):
        require(token in identity_run, f"Android interop identity check omits {token}")
    sdk_run = android_step_named(android_matrix_job, "Install Android SDK and emulator image").get("run")
    require(isinstance(sdk_run, str), "Android interop SDK setup must use a run script")
    require(
        "system-images;android-${API_LEVEL};default;x86_64" in sdk_run,
        "Android interop workflow does not use the canonical x86_64 system image",
    )
    emulator_run = android_step_named(android_matrix_job, "Create and start clean emulator").get("run")
    require(isinstance(emulator_run, str), "Android interop emulator setup must use a run script")
    for token in ("runner already has an emulator attached", "EMULATOR_PORT=5554", '-port "$EMULATOR_PORT"'):
        require(token in emulator_run, f"Android interop emulator ownership check omits {token}")
    bridge_run = android_step_named(android_matrix_job, "Build pinned GoMobile bridge").get("run")
    require(bridge_run == "bash frpc-stcp-visitor-go/build-aar.sh", "Android interop workflow does not build the pinned bridge")
    android_interop_run = android_step_named(
        android_matrix_job,
        "Run real GoMobile then Kotlin Android interop",
    ).get("run")
    require(isinstance(android_interop_run, str), "Android interop gate must use a run script")
    for token in (
        ":frpc-stcp-visitor:frpcAndroidInteropTest",
        "-PrequireGoMobileBridge=true",
        "ocdeck.frp.androidInterop.deviceSerial",
    ):
        require(token in android_interop_run, f"Android interop gate omits {token}")
    report_run = android_step_named(android_report_job, "Render bilingual exact-SHA report").get("run")
    require(isinstance(report_run, str), "Android interop report must use a run script")
    require(
        "render-frpc-android-interop-report.py" in report_run,
        "Android interop workflow does not use the canonical report renderer",
    )
    require("--matrix-result" in report_run, "Android interop report does not bind matrix status")
    evidence_run = android_step_named(android_matrix_job, "Write bounded lane evidence").get("run")
    require(isinstance(evidence_run, str), "Android interop evidence step must use a run script")
    for token in ("testApkSha256", "bridgeAarSha256", "/proc/self/smaps"):
        require(token in evidence_run, f"Android interop evidence omits {token}")

    assemble = step_named(prepare_job, "Assemble release notes")
    assemble_run = assemble.get("run")
    require(isinstance(assemble_run, str), "Assemble release notes must use a run script")
    for token in ("release-notes.md", "release-notes-preamble.md", "releases/generate-notes"):
        require(token in assemble_run, f"release-note assembly omits {token}")
    validation_index = assemble_run.find("forbidden = re.search")
    generated_index = assemble_run.find("generated_notes=$(gh api")
    require(validation_index >= 0 and generated_index >= 0, "release-note assembly lacks validation or generated notes")
    require(validation_index < generated_index, "curated release-note validation must run before GitHub-generated PR text is appended")

    upload = step_named(prepare_job, "Upload prepared release notes").get("with")
    download = step_named(publish_job, "Download prepared release notes").get("with")
    require(isinstance(upload, dict) and isinstance(download, dict), "release-note artifact steps must define inputs")
    require(upload.get("name") == download.get("name"), "prepared release-note artifact names do not match")
    require(upload.get("path") == "release-notes.md", "prepared release-note artifact has unexpected path")
    require(download.get("path") == "prepared-notes", "publish downloads release notes to an unexpected path")

    publish_run = step_named(publish_job, "Create GitHub Release").get("run")
    require(isinstance(publish_run, str), "Create GitHub Release must use a run script")
    require("--notes-file prepared-notes/release-notes.md" in publish_run, "publish regenerates or ignores prepared notes")


def audit_governance() -> None:
    required = [
        "CONTRIBUTING.md",
        "CONTRIBUTING.zh-CN.md",
        "CODE_OF_CONDUCT.md",
        "CODE_OF_CONDUCT.zh-CN.md",
        ".github/CODEOWNERS",
        ".github/PULL_REQUEST_TEMPLATE.md",
    ]
    for relative in required:
        read_text(relative)
    codeowners = read_text(".github/CODEOWNERS")
    require("* @ycfeng" in codeowners, "CODEOWNERS lacks the confirmed default owner")
    for path in ("/.github/", "/frpc-stcp-visitor-go/", "/SECURITY.md", "/third_party/"):
        require(path in codeowners, f"CODEOWNERS omits high-risk path: {path}")

    security = read_text("SECURITY.md")
    support = read_text("SUPPORT.md")
    conduct = read_text("CODE_OF_CONDUCT.md")
    conduct_zh = read_text("CODE_OF_CONDUCT.zh-CN.md")
    contributing = read_text("CONTRIBUTING.md")
    contributing_zh = read_text("CONTRIBUTING.zh-CN.md")
    readme = read_text("README.md")
    readme_zh = read_text("README.zh-CN.md")
    require(PVR_URL in security, "SECURITY.md lacks canonical PVR URL")
    require(f"{CANONICAL_REPOSITORY}/issues" in support, "SUPPORT.md lacks canonical issue tracker")
    require("External contributions are welcome" in conduct, "Code of Conduct does not open external contributions")
    require("External code and documentation contributions are welcome" in contributing, "contribution guide does not open external contributions")
    require("External code and documentation contributions are welcome" in readme, "README does not open external contributions")
    require("欢迎外部贡献" in conduct_zh, "Chinese Code of Conduct does not open external contributions")
    require("欢迎外部代码和文档贡献" in contributing_zh, "Chinese contribution guide does not open external contributions")
    require("欢迎外部代码和文档贡献" in readme_zh, "Chinese README does not open external contributions")
    require(PVR_URL in conduct, "Code of Conduct lacks the private reporting route")
    require(PVR_URL in conduct_zh, "Chinese Code of Conduct lacks the private reporting route")
    require("[Code of Conduct]" in conduct, "Code of Conduct lacks private-report routing guidance")
    require("[Code of Conduct]" in conduct_zh, "Chinese Code of Conduct lacks private-report routing guidance")


def audit_documentation() -> None:
    english_index = read_text("doc/README.md")
    chinese_index = read_text("doc/README.zh-CN.md")
    for heading in ("User", "Development", "Architecture", "Maintainers", "Release"):
        require(f"## {heading}" in english_index, f"English documentation index omits {heading}")
    for heading in ("用户", "开发", "架构", "维护者", "Release"):
        require(f"## {heading}" in chinese_index, f"Chinese documentation index omits {heading}")

    obsolete_paths = [
        "doc/OpenCode Web UI Android 端交互设计.md",
        "doc/项目框架搭建方案.md",
        "doc/GitHub Actions 发布流程.md",
    ]
    searchable = [
        ROOT / "README.md",
        ROOT / "README.zh-CN.md",
        ROOT / "AGENTS.md",
        ROOT / "AGENTS.zh-CN.md",
    ]
    searchable.extend((ROOT / "doc").rglob("*.md"))
    combined = "\n".join(path.read_text(encoding="utf-8") for path in searchable)
    for obsolete in obsolete_paths:
        require(not (ROOT / obsolete).exists(), f"obsolete document still exists: {obsolete}")
        require(obsolete not in combined, f"stale document link remains: {obsolete}")

    compatibility = read_text("doc/user/compatibility.md")
    for status in ("Supported", "Tested", "Observed only", "Known incompatible", "Unknown"):
        require(status in compatibility, f"compatibility matrix omits status: {status}")
    require(re.search(r"1\.17\.7.*Observed only", compatibility), "OpenCode Server 1.17.7 must remain Observed only")
    deprecation = read_text("doc/maintainers/deprecation-policy.md")
    require("at least one public release" in deprecation, "pre-1.0 deprecation notice period is missing")

    forbidden_tokens = (
        "muhai-cpa",
        "api.myprovider.com",
        "remote.internal",
        "opencode-dashboard",
        "quant-stock-selector",
        "miaosha-GLM",
        "AndroidAdbProbe",
        "@tarquinen/opencode-dcp",
    )
    public_paths = list((ROOT / "doc").rglob("*.md"))
    public_paths.extend((ROOT / "app" / "src" / "main").rglob("*"))
    public_paths.extend((ROOT / "app" / "src" / "test").rglob("*"))
    public_text = "\n".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for path in public_paths
        if path.is_file()
    )
    for token in forbidden_tokens:
        require(token not in public_text, f"environment-specific fixture remains: {token}")
    for token in ("E:\\project\\", "C:\\Users\\", "/Users/", "/home/ycfeng/"):
        require(token not in public_text, f"machine-specific path remains: {token}")


def audit_repository_hygiene() -> None:
    gitignore = read_text(".gitignore")
    for token in ("signing.properties", "*.jks", "__pycache__/", "*.py[cod]"):
        require(token in gitignore, f".gitignore omits local or generated file pattern: {token}")
    process = subprocess.run(
        ["git", "ls-files"],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    require(process.returncode == 0, f"git ls-files failed: {process.stderr.strip()}")
    forbidden = re.compile(r"(^|/)(local\.properties(?=$|/)|signing\.properties(?=$|/)|\.idea/|\.playwright-cli/|tmp/|build/|app/release/|__pycache__/)|\.py[co]$")
    tracked_forbidden = [path for path in process.stdout.splitlines() if forbidden.search(path)]
    require(not tracked_forbidden, f"local/generated files are tracked: {tracked_forbidden}")


def main() -> int:
    try:
        version_name = read_properties("gradle.properties")["VERSION_NAME"]
        audit_governance()
        issue_forms = audit_issue_forms()
        audit_release_metadata(version_name)
        audit_documentation()
        document_pairs = audit_document_pairs()
        relative_links = audit_relative_links()
        audit_repository_hygiene()
    except (AuditError, KeyError) as error:
        print(f"community audit failed: {error}", file=sys.stderr)
        return 1
    print(
        "Community audit passed: "
        f"{issue_forms} issue forms, {document_pairs} bilingual document pairs, "
        f"{relative_links} relative Markdown links, release notes v{version_name}."
    )
    print("External contribution intake is open; security and conduct reports share the private advisory intake.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
