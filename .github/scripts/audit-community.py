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
    expected_jobs = {"preflight", "prepare-notes", "build-release", "publish"}
    require(expected_jobs <= set(jobs), "release workflow omits required jobs")

    preflight_job = jobs["preflight"]
    prepare_job = jobs["prepare-notes"]
    build_job = jobs["build-release"]
    publish_job = jobs["publish"]
    require(isinstance(preflight_job, dict), "preflight job must be an object")
    require(isinstance(prepare_job, dict), "prepare-notes job must be an object")
    require(isinstance(build_job, dict), "build-release job must be an object")
    require(isinstance(publish_job, dict), "publish job must be an object")
    require(prepare_job.get("needs") == "preflight", "prepare-notes must depend on preflight")
    require(build_job.get("needs") == "preflight", "build-release must depend on preflight")
    publish_needs = publish_job.get("needs")
    require(isinstance(publish_needs, list), "publish needs must be a list")
    require(set(publish_needs) == {"preflight", "prepare-notes", "build-release"}, "publish has unexpected dependencies")
    require("environment" not in prepare_job, "prepare-notes must not access the release environment")
    require(prepare_job.get("permissions", {}).get("contents") == "write", "prepare-notes needs scoped contents: write")
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
    require(PVR_URL in security, "SECURITY.md lacks canonical PVR URL")
    require(f"{CANONICAL_REPOSITORY}/issues" in support, "SUPPORT.md lacks canonical issue tracker")
    require("External contributions are blocked" in conduct, "conduct-contact blocker is not explicit")
    require("must not be presented or used" in conduct, "PVR must not be used as conduct channel")


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
    print("External contribution intake remains blocked until a private conduct channel is published.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
