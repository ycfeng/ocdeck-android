#!/usr/bin/env python3
"""Read-only audit of OC Deck third-party, asset, and release legal metadata."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tomllib
from pathlib import Path, PurePosixPath
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
THIRD_PARTY = ROOT / "third_party"
GO_ROOT = ROOT / "frpc-stcp-visitor-go"
EXPECTED_GO_TARGETS = [
    "android/arm64",
    "android/arm",
    "android/386",
    "android/amd64",
]
FORBIDDEN_ANDROID_RESOURCE_TOKENS = (
    "opencode_mark",
    "opencode_logo",
    "provider_openai",
    "openai_logo",
    "openai_mark",
)
EXPECTED_FRP_INTEROP_BASE_URL = "https://github.com/fatedier/frp/releases/download/v0.69.1/"
EXPECTED_FRP_INTEROP_PURPOSE = (
    "Pinned official binaries downloaded only by the explicit frpcInteropTest harness; "
    "they are not App, APK, AAR, release, or repository-distributed assets."
)
EXPECTED_FRP_INTEROP_ASSETS = {
    "linux_amd64": (
        "frp_0.69.1_linux_amd64.tar.gz",
        "7be257b72dbbc60bcb3e0e25a5afd1dfac7b63f897084864d3c956dd3d5674e1",
    ),
    "linux_arm64": (
        "frp_0.69.1_linux_arm64.tar.gz",
        "bbc0c75e896af3f292fb46ba09c844a04fa9b5ea3530c039c7af20637f836355",
    ),
    "windows_amd64": (
        "frp_0.69.1_windows_amd64.zip",
        "829ac915f8655d4d4e021b8db61b46c3445205ed80d32b04cda7fa89d87c46e0",
    ),
    "windows_arm64": (
        "frp_0.69.1_windows_arm64.zip",
        "9b88e6eefc5d9ea2a1d5869026287e269e3d1486ac5bb08f7b4d2b26bdd6166d",
    ),
    "darwin_amd64": (
        "frp_0.69.1_darwin_amd64.tar.gz",
        "2bc26d02100ef333f2712149ea5997dc530dc0eefac64f4be41cb0f49d032f40",
    ),
    "darwin_arm64": (
        "frp_0.69.1_darwin_arm64.tar.gz",
        "310012e2f1dcf3cdde2605d29b95340b686c94d1680a23711d58efeffc02f64e",
    ),
}


class AuditError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AuditError(message)


def require_equal(actual: Any, expected: Any, label: str) -> None:
    if actual != expected:
        raise AuditError(f"{label}: got {actual!r}, expected {expected!r}")


def read_text(path: Path) -> str:
    require(path.is_file(), f"required file is missing: {path.relative_to(ROOT)}")
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError as error:
        raise AuditError(f"file is not valid UTF-8: {path.relative_to(ROOT)}") from error


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(read_text(path))
    except json.JSONDecodeError as error:
        raise AuditError(
            f"invalid JSON in {path.relative_to(ROOT)}: line {error.lineno}, column {error.colno}"
        ) from error
    require(isinstance(value, dict), f"JSON root must be an object: {path.relative_to(ROOT)}")
    return value


def load_toml(path: Path) -> dict[str, Any]:
    try:
        value = tomllib.loads(read_text(path))
    except tomllib.TOMLDecodeError as error:
        raise AuditError(f"invalid TOML in {path.relative_to(ROOT)}: {error}") from error
    require(isinstance(value, dict), f"TOML root must be a table: {path.relative_to(ROOT)}")
    return value


def read_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line_number, raw_line in enumerate(read_text(path).splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        require("=" in line, f"invalid property at {path.relative_to(ROOT)}:{line_number}")
        key, value = (part.strip() for part in line.split("=", 1))
        require(key, f"empty property key at {path.relative_to(ROOT)}:{line_number}")
        require(key not in result, f"duplicate property {key!r} in {path.relative_to(ROOT)}")
        result[key] = value
    return result


def one_match(pattern: str, text: str, label: str, flags: int = 0) -> str:
    matches = re.findall(pattern, text, flags)
    require(len(matches) == 1, f"{label} must appear exactly once, found {len(matches)}")
    value = matches[0]
    require(isinstance(value, str), f"{label} pattern must have one capture group")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_root_path(value: str, label: str) -> Path:
    require("\\" not in value, f"{label} must use forward slashes: {value!r}")
    relative = PurePosixPath(value)
    require(not relative.is_absolute(), f"{label} must be relative: {value!r}")
    require(".." not in relative.parts, f"{label} must not contain '..': {value!r}")
    path = (ROOT / Path(*relative.parts)).resolve()
    require(path.is_relative_to(ROOT.resolve()), f"{label} escapes the repository: {value!r}")
    return path


def component_path(value: str, label: str) -> Path:
    path = (THIRD_PARTY / value).resolve()
    require(path.is_relative_to(ROOT.resolve()), f"{label} escapes the repository: {value!r}")
    return path


def components_by_id(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    components = manifest.get("components")
    require(isinstance(components, list) and components, "components.toml must contain components")
    result: dict[str, dict[str, Any]] = {}
    for index, component in enumerate(components):
        require(isinstance(component, dict), f"components[{index}] must be a table")
        component_id = component.get("id")
        require(isinstance(component_id, str) and component_id, f"components[{index}] has no id")
        require(component_id not in result, f"duplicate component id: {component_id}")
        result[component_id] = component
    return result


def audit_versions(
    manifest: dict[str, Any],
    components: dict[str, dict[str, Any]],
) -> tuple[str, str, str, str]:
    gradle_properties = read_properties(ROOT / "gradle.properties")
    bridge_properties = read_properties(GO_ROOT / "bridge-versions.properties")
    version_name = gradle_properties.get("VERSION_NAME", "")
    require(
        re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", version_name) is not None,
        f"VERSION_NAME is not stable SemVer: {version_name!r}",
    )
    require_equal(manifest.get("app_version"), version_name, "components.toml app_version")
    require_equal(components["ocdeck"].get("version"), version_name, "ocdeck component version")

    app_build = read_text(ROOT / "app/build.gradle.kts")
    application_id = one_match(
        r'^\s*applicationId\s*=\s*"([^"]+)"',
        app_build,
        "app applicationId",
        re.MULTILINE,
    )
    require_equal(manifest.get("application_id"), application_id, "components.toml application_id")
    min_sdk = int(
        one_match(r"^\s*minSdk\s*=\s*(\d+)", app_build, "app minSdk", re.MULTILINE)
    )
    require_equal(manifest.get("min_android_api"), min_sdk, "components.toml min_android_api")
    require_equal(
        bridge_properties.get("ANDROID_API"), str(min_sdk), "bridge ANDROID_API"
    )

    bridge_version = bridge_properties.get("BRIDGE_VERSION", "")
    require(bridge_version, "bridge-versions.properties must define BRIDGE_VERSION")
    require_equal(manifest.get("bridge_version"), bridge_version, "components.toml bridge_version")
    artifact_id = "frpc-stcp-visitor-gobridge"
    coordinate = f"{application_id}:{artifact_id}:{bridge_version}"
    require_equal(
        manifest.get("bridge_maven_coordinate"),
        coordinate,
        "components.toml bridge_maven_coordinate",
    )

    bridge_build = read_text(ROOT / "frpc-stcp-visitor/build.gradle.kts")
    dependency_prefix = one_match(
        r'implementation\("([^"]+):\$goMobileBridgeVersion"\)',
        bridge_build,
        "GoMobile bridge dependency coordinate",
    )
    require_equal(
        dependency_prefix,
        f"{application_id}:{artifact_id}",
        "GoMobile bridge dependency coordinate",
    )
    for script_name in ("build-aar.sh", "build-aar.ps1"):
        script = read_text(GO_ROOT / script_name)
        require(
            f"<groupId>{application_id}</groupId>" in script,
            f"{script_name} Maven groupId does not match applicationId",
        )
        require(
            f"<artifactId>{artifact_id}</artifactId>" in script,
            f"{script_name} Maven artifactId is missing or inconsistent",
        )

    return version_name, application_id, bridge_version, coordinate


def audit_component_files(components: dict[str, dict[str, Any]]) -> None:
    for component_id, component in components.items():
        license_file = component.get("license_file")
        require(
            isinstance(license_file, str) and license_file,
            f"component {component_id} must declare license_file",
        )
        license_path = component_path(license_file, f"component {component_id} license_file")
        require(
            license_path.is_file(),
            f"component {component_id} license file is missing: {license_path.relative_to(ROOT)}",
        )
        provenance_file = component.get("provenance_file")
        if provenance_file is None:
            continue
        require(
            isinstance(provenance_file, str) and provenance_file,
            f"component {component_id} provenance_file must be a path",
        )
        provenance_path = component_path(
            provenance_file, f"component {component_id} provenance_file"
        )
        require(
            provenance_path.is_file(),
            f"component {component_id} provenance file is missing: {provenance_path.relative_to(ROOT)}",
        )
        if provenance_path.suffix == ".json":
            load_json(provenance_path)


def audit_asset_component(
    component_id: str,
    components: dict[str, dict[str, Any]],
    expected_suffix: str,
) -> tuple[dict[str, Any], set[str]]:
    component = components[component_id]
    provenance_file = component.get("provenance_file")
    require(isinstance(provenance_file, str), f"component {component_id} has no provenance_file")
    source_path = component_path(provenance_file, f"component {component_id} provenance_file")
    source = load_json(source_path)
    require_equal(source.get("schemaVersion"), 1, f"{source_path.name} schemaVersion")
    require_equal(source.get("component"), component.get("name"), f"{component_id} component name")
    require_equal(source.get("license"), component.get("license"), f"{component_id} license")
    require_equal(source.get("hashAlgorithm"), "SHA-256", f"{component_id} hashAlgorithm")
    source_license = source.get("licenseFile")
    require(isinstance(source_license, str), f"{source_path.name} must declare licenseFile")
    require_equal(
        safe_root_path(source_license, f"{source_path.name} licenseFile"),
        component_path(component["license_file"], f"component {component_id} license_file"),
        f"{component_id} license file",
    )

    commit = source.get("upstreamCommit")
    require(
        isinstance(commit, str) and re.fullmatch(r"[0-9a-f]{40}", commit) is not None,
        f"{source_path.name} has an invalid upstreamCommit",
    )
    require_equal(component.get("version"), f"commit {commit}", f"{component_id} version")
    require_equal(
        component.get("source"),
        f"{source.get('upstreamRepository')}/commit/{commit}",
        f"{component_id} source",
    )

    assets = source.get("assets")
    require(isinstance(assets, list), f"{source_path.name} assets must be an array")
    require_equal(source.get("assetCount"), len(assets), f"{source_path.name} assetCount")
    require_equal(component.get("asset_count"), len(assets), f"{component_id} asset_count")
    local_paths: set[str] = set()
    for index, asset in enumerate(assets):
        require(isinstance(asset, dict), f"{source_path.name} assets[{index}] must be an object")
        local_path = asset.get("localPath")
        require(
            isinstance(local_path, str) and local_path.endswith(expected_suffix),
            f"{source_path.name} assets[{index}] has an invalid localPath",
        )
        require(local_path not in local_paths, f"duplicate local asset path: {local_path}")
        local_paths.add(local_path)
        local_file = safe_root_path(local_path, f"{source_path.name} assets[{index}].localPath")
        require(local_file.is_file(), f"asset is missing: {local_path}")
        require_equal(asset.get("sizeBytes"), local_file.stat().st_size, f"{local_path} sizeBytes")
        expected_hash = asset.get("sha256")
        require(
            isinstance(expected_hash, str)
            and re.fullmatch(r"[0-9a-f]{64}", expected_hash) is not None,
            f"{local_path} has an invalid lowercase SHA-256",
        )
        require_equal(sha256(local_file), expected_hash, f"{local_path} SHA-256")
    return source, local_paths


def audit_assets(
    components: dict[str, dict[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any], int, int]:
    audio, audio_paths = audit_asset_component("opencode-audio", components, ".aac")
    require_equal(audio.get("verifiedAgainstUpstream"), True, "opencode audio upstream verification")
    raw_directory = ROOT / "app/src/main/res/raw"
    discovered_audio = {
        path.relative_to(ROOT).as_posix() for path in raw_directory.glob("*.aac") if path.is_file()
    }
    require_equal(
        sorted(discovered_audio), sorted(audio_paths), "bundled AAC files versus audio provenance"
    )

    vectors, vector_paths = audit_asset_component("opencode-ui-vectors", components, ".xml")
    for index, asset in enumerate(vectors["assets"]):
        require(
            isinstance(asset.get("upstreamSymbol"), str) and asset["upstreamSymbol"],
            f"opencode-ui-assets.json assets[{index}] has no upstreamSymbol",
        )
        require(
            isinstance(asset.get("relationship"), str) and asset["relationship"],
            f"opencode-ui-assets.json assets[{index}] has no relationship",
        )
    return audio, vectors, len(audio_paths), len(vector_paths)


def audit_forbidden_assets() -> None:
    app_root = ROOT / "app"
    text_suffixes = {".kt", ".kts", ".xml", ".json", ".properties", ".gradle"}
    paths = (
        item
        for item in app_root.rglob("*")
        if item.is_file() and item.relative_to(app_root).parts[0] != "build"
    )
    for path in sorted(paths):
        relative = path.relative_to(ROOT).as_posix()
        lower_name = path.name.lower()
        for token in FORBIDDEN_ANDROID_RESOURCE_TOKENS:
            require(token not in lower_name, f"forbidden removed resource has reappeared: {relative}")
        if path.suffix.lower() not in text_suffixes:
            continue
        try:
            content = path.read_text(encoding="utf-8").lower()
        except UnicodeDecodeError as error:
            raise AuditError(f"expected text file is not UTF-8: {relative}") from error
        for token in FORBIDDEN_ANDROID_RESOURCE_TOKENS:
            require(token not in content, f"forbidden removed resource is referenced by {relative}: {token}")


def audit_gradle_wrapper(components: dict[str, dict[str, Any]]) -> dict[str, Any]:
    component = components["gradle-wrapper"]
    provenance_path = component_path(component["provenance_file"], "Gradle provenance_file")
    provenance = load_json(provenance_path)
    require_equal(provenance.get("schemaVersion"), 1, "Gradle provenance schemaVersion")
    require_equal(provenance.get("component"), "Gradle Wrapper", "Gradle provenance component")
    require_equal(provenance.get("version"), component.get("version"), "Gradle version")
    require_equal(provenance.get("license"), component.get("license"), "Gradle license")
    require_equal(
        safe_root_path(provenance.get("licenseFile", ""), "Gradle provenance licenseFile"),
        component_path(component["license_file"], "Gradle component license_file"),
        "Gradle license file",
    )
    require_equal(
        provenance.get("upstreamTag"), f"v{component.get('version')}", "Gradle upstream tag"
    )
    require_equal(
        component.get("source"),
        f"{provenance.get('upstreamRepository')}/tree/{provenance.get('upstreamTag')}",
        "Gradle source",
    )

    wrapper_properties = read_properties(ROOT / "gradle/wrapper/gradle-wrapper.properties")
    distribution_url = wrapper_properties.get("distributionUrl", "").replace("\\:", ":")
    require_equal(distribution_url, provenance.get("distributionUrl"), "Gradle distributionUrl")
    require_equal(
        wrapper_properties.get("distributionSha256Sum"),
        provenance.get("distributionSha256"),
        "Gradle distribution SHA-256",
    )
    jar_path_value = provenance.get("wrapperJarPath")
    require(isinstance(jar_path_value, str), "Gradle provenance must declare wrapperJarPath")
    jar_path = safe_root_path(jar_path_value, "Gradle wrapperJarPath")
    require(jar_path.is_file(), f"Gradle wrapper JAR is missing: {jar_path_value}")
    require_equal(sha256(jar_path), provenance.get("wrapperJarSha256"), "Gradle wrapper JAR SHA-256")
    return provenance


def parse_patch_files(path: Path) -> tuple[set[str], set[str]]:
    lines = read_text(path).splitlines()
    modified: set[str] = set()
    added: set[str] = set()
    index = 0
    while index < len(lines):
        line = lines[index]
        if not line.startswith("diff --git "):
            index += 1
            continue
        match = re.fullmatch(r"diff --git a/(.+) b/(.+)", line)
        require(match is not None, f"unsupported patch diff header in {path.relative_to(ROOT)}: {line}")
        old_path, new_path = match.groups()
        require(old_path == new_path, f"renamed patch paths are not supported: {old_path} -> {new_path}")
        section_end = index + 1
        while section_end < len(lines) and not lines[section_end].startswith("diff --git "):
            section_end += 1
        section = lines[index + 1 : section_end]
        require(f"+++ b/{new_path}" in section, f"patch has no new-file header for {new_path}")
        if "--- /dev/null" in section:
            added.add(new_path)
        else:
            require(f"--- a/{old_path}" in section, f"patch has no old-file header for {old_path}")
            modified.add(new_path)
        index = section_end
    require(modified or added, f"patch contains no file changes: {path.relative_to(ROOT)}")
    return modified, added


def audit_frp(
    components: dict[str, dict[str, Any]], bridge_version: str
) -> tuple[dict[str, Any], dict[str, Any], int, int]:
    component = components["frp-patched"]
    source_path = component_path(component["provenance_file"], "frp provenance_file")
    source = load_json(source_path)
    downstream = source.get("downstreamVersion")
    require(isinstance(downstream, str) and downstream.startswith("v"), "invalid frp downstreamVersion")
    manifest_path = GO_ROOT / "downstream" / f"frp-{downstream}" / "manifest.json"
    manifest = load_json(manifest_path)
    generated_path = GO_ROOT / "build/frp-patch-provenance.json"
    require(
        generated_path.is_file(),
        "generated frp provenance is missing; run 'go run ./cmd/preparefrp' in frpc-stcp-visitor-go",
    )
    generated = load_json(generated_path)

    require_equal(source.get("schemaVersion"), 1, "frp source schemaVersion")
    require_equal(source.get("component"), manifest.get("module"), "frp module")
    require_equal(source.get("license"), component.get("license"), "frp license")
    require_equal(
        safe_root_path(source.get("licenseFile", ""), "frp source licenseFile"),
        component_path(component["license_file"], "frp component license_file"),
        "frp license file",
    )
    require_equal(source.get("modifiedBy"), component.get("modified_by"), "frp modifiedBy")
    require_equal(source.get("upstreamVersion"), manifest.get("version"), "frp upstream version")
    require_equal(source.get("moduleSum"), manifest.get("moduleSum"), "frp module sum")
    require_equal(source.get("goModSum"), manifest.get("goModSum"), "frp go.mod sum")
    require_equal(source.get("upstreamCommit"), manifest.get("originHash"), "frp upstream commit")
    require_equal(source.get("upstreamModuleZipSha256"), manifest.get("zipSha256"), "frp zip SHA-256")
    require_equal(
        source.get("upstreamFileSha256BeforePatch"),
        manifest.get("upstreamFiles"),
        "frp locked upstream file hashes",
    )
    require_equal(component.get("version"), downstream, "frp component version")
    require_equal(
        component.get("source"),
        f"{source.get('upstreamRepository')}/commit/{source.get('upstreamCommit')}",
        "frp component source",
    )
    require(
        bridge_version.endswith(f"-frp{downstream.removeprefix('v')}"),
        f"bridge version {bridge_version!r} does not encode frp downstream {downstream!r}",
    )

    manifest_patches = manifest.get("patches")
    source_patches = source.get("patches")
    require(isinstance(manifest_patches, list) and manifest_patches, "frp manifest has no patches")
    require(isinstance(source_patches, list), "frp source patches must be an array")
    source_by_name: dict[str, dict[str, Any]] = {}
    for entry in source_patches:
        require(isinstance(entry, dict), "frp source patch entry must be an object")
        source_patch_path = entry.get("path")
        require(isinstance(source_patch_path, str), "frp source patch entry has no path")
        name = PurePosixPath(source_patch_path).name
        require(name not in source_by_name, f"duplicate frp source patch entry: {name}")
        source_by_name[name] = entry
    require_equal(sorted(source_by_name), sorted(manifest_patches), "frp patch file list")

    all_modified: set[str] = set()
    all_added: set[str] = set()
    patch_hashes: dict[str, str] = {}
    downstream_directory = manifest_path.parent
    for patch_name in manifest_patches:
        require(isinstance(patch_name, str), "frp manifest patch name must be a string")
        patch_path = downstream_directory / patch_name
        require(patch_path.is_file(), f"frp patch is missing: {patch_path.relative_to(ROOT)}")
        actual_modified, actual_added = parse_patch_files(patch_path)
        require(not (actual_modified & actual_added), f"frp patch marks files as both added and modified: {patch_name}")
        entry = source_by_name[patch_name]
        require_equal(
            entry.get("path"), patch_path.relative_to(ROOT).as_posix(), f"frp source path for {patch_name}"
        )
        patch_hash = sha256(patch_path)
        require_equal(entry.get("sha256"), patch_hash, f"frp patch SHA-256 for {patch_name}")
        require_equal(
            sorted(entry.get("modifiedFiles", [])),
            sorted(actual_modified),
            f"frp modifiedFiles for {patch_name}",
        )
        require_equal(
            sorted(entry.get("addedFiles", [])),
            sorted(actual_added),
            f"frp addedFiles for {patch_name}",
        )
        require_equal(
            sorted(entry.get("patchedFiles", [])),
            sorted(actual_modified | actual_added),
            f"frp patchedFiles for {patch_name}",
        )
        all_modified.update(actual_modified)
        all_added.update(actual_added)
        patch_hashes[patch_name] = patch_hash

    all_patched = all_modified | all_added
    require_equal(sorted(manifest.get("upstreamFiles", {})), sorted(all_modified), "frp modified upstream files")
    require_equal(sorted(manifest.get("gofmtFiles", [])), sorted(all_patched), "frp patched/gofmt file list")
    generated_expected = {
        "downstream": manifest_path.parent.name,
        "module": manifest.get("module"),
        "version": manifest.get("version"),
        "moduleSum": manifest.get("moduleSum"),
        "goModSum": manifest.get("goModSum"),
        "originHash": manifest.get("originHash"),
        "zipSha256": manifest.get("zipSha256"),
        "patchSha256": patch_hashes,
        "patchedFiles": sorted(all_patched),
        "modifiedFiles": sorted(all_modified),
        "addedFiles": sorted(all_added),
    }
    for key, expected in generated_expected.items():
        require_equal(generated.get(key), expected, f"generated frp provenance {key}")
    return source, manifest, len(all_patched), audit_frp_interop_assets(source)


def audit_frp_interop_assets(source: dict[str, Any]) -> int:
    section = source.get("interopHarnessReleaseAssets")
    require(isinstance(section, dict), "frp source omits interopHarnessReleaseAssets")
    purpose = section.get("purpose")
    require_equal(purpose, EXPECTED_FRP_INTEROP_PURPOSE, "frp interop asset purpose")
    require_equal(section.get("version"), source.get("upstreamVersion"), "frp interop asset version")
    require_equal(section.get("baseUrl"), EXPECTED_FRP_INTEROP_BASE_URL, "frp interop asset base URL")
    assets = section.get("assets")
    require(isinstance(assets, list), "frp interop assets must be an array")
    actual: dict[str, tuple[str, str]] = {}
    for entry in assets:
        require(isinstance(entry, dict), "frp interop asset entry must be an object")
        platform = entry.get("platform")
        file_name = entry.get("file")
        digest = entry.get("sha256")
        require(isinstance(platform, str) and platform, "frp interop asset platform is invalid")
        require(isinstance(file_name, str) and file_name, f"frp interop asset filename is invalid: {platform}")
        require(
            isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
            f"frp interop asset SHA-256 is invalid: {platform}",
        )
        require(platform not in actual, f"duplicate frp interop asset platform: {platform}")
        actual[platform] = (file_name, digest)
    require_equal(actual, EXPECTED_FRP_INTEROP_ASSETS, "frp interop release asset pins")

    provisioner = read_text(
        ROOT
        / "frpc-stcp-visitor/src/test/java/io/github/ycfeng/ocdeck/frpcstcpvisitor/interop/FrpReleaseProvisioner.kt"
    )
    base_url_match = re.search(r'const val BASE_URL = "([^"]+)"', provisioner)
    require(base_url_match is not None, "frp provisioner base URL is missing")
    require_equal(
        base_url_match.group(1),
        EXPECTED_FRP_INTEROP_BASE_URL,
        "frp provisioner base URL",
    )
    assets_match = re.search(
        r"private val assets = listOf\(\s*(.*?)\n\s*\)\s*\n\s*\n\s*fun current\(",
        provisioner,
        re.DOTALL,
    )
    require(assets_match is not None, "frp provisioner asset list is missing")
    pin_pattern = re.compile(
        r"""FrpAssetPin\(
            \s*"([^"]+)"\s*,
            \s*"([^"]+)"\s*,
            \s*"([0-9a-f]{64})"\s*,
            \s*FrpArchiveKind\.([A-Z_]+)\s*,
            \s*"([^"]*)"\s*,?
            \s*\)
        """,
        re.VERBOSE,
    )
    assets_body = assets_match.group(1)
    parsed_pins: dict[str, tuple[str, str, str, str]] = {}
    for match in pin_pattern.finditer(assets_body):
        platform, file_name, digest, archive_kind, executable_suffix = match.groups()
        require(platform not in parsed_pins, f"duplicate frp provisioner platform pin: {platform}")
        parsed_pins[platform] = (file_name, digest, archive_kind, executable_suffix)
    require(
        re.fullmatch(r"[\s,]*", pin_pattern.sub("", assets_body)) is not None,
        "frp provisioner asset list contains an unparsed entry",
    )
    expected_pins = {
        platform: (
            file_name,
            digest,
            "ZIP" if file_name.endswith(".zip") else "TAR_GZ",
            ".exe" if platform.startswith("windows_") else "",
        )
        for platform, (file_name, digest) in EXPECTED_FRP_INTEROP_ASSETS.items()
    }
    require_equal(parsed_pins, expected_pins, "frp provisioner release asset pins")
    return len(actual)


def decode_json_stream(text: str, label: str) -> list[dict[str, Any]]:
    decoder = json.JSONDecoder()
    result: list[dict[str, Any]] = []
    index = 0
    while True:
        while index < len(text) and text[index].isspace():
            index += 1
        if index == len(text):
            return result
        try:
            value, index = decoder.raw_decode(text, index)
        except json.JSONDecodeError as error:
            raise AuditError(f"invalid JSON stream from {label}: {error}") from error
        require(isinstance(value, dict), f"non-object JSON value from {label}")
        result.append(value)


def effective_module(module: dict[str, Any]) -> str | None:
    current = module
    seen: set[tuple[Any, Any]] = set()
    while isinstance(current.get("Replace"), dict):
        identity = (current.get("Path"), current.get("Version"))
        require(identity not in seen, f"cyclic Go module replacement: {identity}")
        seen.add(identity)
        replacement = current["Replace"]
        if not replacement.get("Version"):
            return None
        current = replacement
    path = current.get("Path")
    version = current.get("Version")
    if not isinstance(path, str) or not isinstance(version, str) or not version:
        return None
    return f"{path}@{version}"


def audit_go_modules(
    manifest: dict[str, Any],
    components: dict[str, dict[str, Any]],
    frp_manifest: dict[str, Any],
) -> tuple[int, int]:
    audit = manifest.get("audit")
    require(isinstance(audit, dict), "components.toml must contain [audit]")
    require("go_target" not in audit, "[audit].go_target is obsolete; declare go_targets")
    require_equal(audit.get("go_targets"), EXPECTED_GO_TARGETS, "[audit].go_targets")
    require_equal(
        audit.get("android_configuration"),
        ":app:releaseRuntimeClasspath",
        "[audit].android_configuration",
    )
    modfile_value = audit.get("go_modfile")
    require(isinstance(modfile_value, str), "[audit].go_modfile must be a path")
    modfile = safe_root_path(modfile_value, "[audit].go_modfile")
    require(
        modfile.is_file(),
        "patched Go modfile is missing; run 'go run ./cmd/preparefrp' in frpc-stcp-visitor-go",
    )
    require(modfile.with_suffix(".sum").is_file(), f"patched Go sum file is missing: {modfile.with_suffix('.sum')}")

    declared_modules: set[str] = set()
    for component_id, component in components.items():
        modules = component.get("modules", [])
        require(isinstance(modules, list), f"component {component_id} modules must be an array")
        for module in modules:
            require(
                isinstance(module, str) and re.fullmatch(r"[^@\s]+@[^@\s]+", module) is not None,
                f"component {component_id} has an invalid module entry: {module!r}",
            )
            require(module not in declared_modules, f"Go module is declared more than once: {module}")
            declared_modules.add(module)

    yamux = components["fatedier-yamux"]
    yamux_module = f"github.com/fatedier/yamux@{yamux.get('version')}"
    require(
        yamux.get("source", "").startswith("https://github.com/fatedier/yamux/commit/"),
        "fatedier-yamux component source is invalid",
    )

    union: set[str] = set()
    saw_patched_frp = False
    expected_frp_replacement = (
        GO_ROOT / "build" / f"frp-{components['frp-patched'].get('version')}"
    ).resolve()
    for target in EXPECTED_GO_TARGETS:
        goos, goarch = target.split("/", 1)
        environment = os.environ.copy()
        environment.update(
            {
                "CGO_ENABLED": "1",
                "GOOS": goos,
                "GOARCH": goarch,
                "GOWORK": "off",
            }
        )
        if goarch == "arm":
            environment["GOARM"] = "7"
        else:
            environment.pop("GOARM", None)
        command = [
            "go",
            "list",
            "-deps",
            "-json",
            "-mod=readonly",
            f"-modfile={modfile}",
            ".",
        ]
        try:
            completed = subprocess.run(
                command,
                cwd=GO_ROOT,
                env=environment,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
            )
        except FileNotFoundError as error:
            raise AuditError("go was not found on PATH") from error
        require(
            completed.returncode == 0,
            f"Go dependency walk failed for {target}: {completed.stderr.strip() or 'unknown error'}",
        )
        packages = decode_json_stream(completed.stdout, f"go list {target}")
        target_modules: set[str] = set()
        for package in packages:
            module = package.get("Module")
            if not isinstance(module, dict):
                continue
            if (
                module.get("Path") == frp_manifest.get("module")
                and module.get("Version") == frp_manifest.get("version")
                and isinstance(module.get("Replace"), dict)
                and not module["Replace"].get("Version")
            ):
                replacement_value = module["Replace"].get("Path")
                require(
                    isinstance(replacement_value, str) and replacement_value,
                    f"Go dependency walk has an invalid local frp replacement for {target}",
                )
                replacement_path = Path(replacement_value)
                if not replacement_path.is_absolute():
                    replacement_path = GO_ROOT / replacement_path
                require_equal(
                    replacement_path.resolve(),
                    expected_frp_replacement,
                    f"prepared frp replacement for {target}",
                )
                saw_patched_frp = True
            resolved = effective_module(module)
            if resolved is not None:
                target_modules.add(resolved)
        require(target_modules, f"Go dependency walk returned no external modules for {target}")
        union.update(target_modules)

    require(saw_patched_frp, "Go dependency walks did not use the prepared local frp replacement")
    require(yamux_module in union, f"Go dependency union is missing dedicated module {yamux_module}")
    listed_union = union - {yamux_module}
    missing = sorted(union - {yamux_module} - declared_modules)
    stale = sorted(declared_modules - listed_union)
    require(
        not missing and not stale,
        "Go module inventory differs from the four-ABI dependency union; "
        f"missing from components.toml modules={missing}, not reached={stale}",
    )
    return len(union), len(declared_modules)


def audit_release_legal_files(
    manifest: dict[str, Any],
    version_name: str,
    bridge_version: str,
    audio: dict[str, Any],
    vectors: dict[str, Any],
    frp: dict[str, Any],
) -> None:
    required_files = (
        "LICENSE",
        "NOTICE",
        "PRIVACY.md",
        "PRIVACY.zh-CN.md",
        "README.md",
        "README.zh-CN.md",
        "SECURITY.md",
        "SECURITY.zh-CN.md",
        "SUPPORT.md",
        "SUPPORT.zh-CN.md",
        "THIRD_PARTY_NOTICES.txt",
        "THIRD_PARTY_NOTICES.zh-CN.txt",
        "TRADEMARKS.md",
        "TRADEMARKS.zh-CN.md",
    )
    contents = {name: read_text(ROOT / name) for name in required_files}

    translated_sources = {
        "PRIVACY.zh-CN.md": "PRIVACY.md",
        "SECURITY.zh-CN.md": "SECURITY.md",
        "SUPPORT.zh-CN.md": "SUPPORT.md",
        "TRADEMARKS.zh-CN.md": "TRADEMARKS.md",
    }
    for translated, source in translated_sources.items():
        require(
            f"[English]({source})" in contents[translated],
            f"{translated} does not link to its English source {source}",
        )

    chinese_readme = contents["README.zh-CN.md"]
    for translated in (
        "PRIVACY.zh-CN.md",
        "SECURITY.zh-CN.md",
        "SUPPORT.zh-CN.md",
        "THIRD_PARTY_NOTICES.zh-CN.txt",
        "TRADEMARKS.zh-CN.md",
    ):
        require(
            f"]({translated})" in chinese_readme,
            f"README.zh-CN.md does not link to {translated}",
        )

    require_equal(
        one_match(r"Pre-1\.0 warning:\*\* version `([^`]+)`", contents["README.md"], "README version"),
        version_name,
        "README current version",
    )
    require_equal(
        one_match(r"included in the `([^`]+)` project facade", contents["README.md"], "README facade version"),
        version_name,
        "README facade version",
    )
    require_equal(
        one_match(r"当前版本为 `([^`]+)`", contents["README.zh-CN.md"], "Chinese README version"),
        version_name,
        "Chinese README current version",
    )
    require_equal(
        one_match(r"`([^`]+)` 项目门面", contents["README.zh-CN.md"], "Chinese README facade version"),
        version_name,
        "Chinese README facade version",
    )
    require_equal(
        one_match(r"current OC Deck `([^`]+)` codebase", contents["PRIVACY.md"], "privacy version"),
        version_name,
        "privacy version",
    )
    require_equal(
        one_match(r"当前 OC Deck `([^`]+)` 代码库", contents["PRIVACY.zh-CN.md"], "Chinese privacy version"),
        version_name,
        "Chinese privacy version",
    )
    notices = contents["THIRD_PARTY_NOTICES.txt"]
    chinese_notices = contents["THIRD_PARTY_NOTICES.zh-CN.txt"]
    require(
        "英文原文：THIRD_PARTY_NOTICES.txt" in chinese_notices,
        "THIRD_PARTY_NOTICES.zh-CN.txt does not identify its English source",
    )
    require_equal(
        one_match(r"^OC Deck application version: (.+)$", notices, "third-party notice app version", re.MULTILINE),
        version_name,
        "third-party notice app version",
    )
    require_equal(
        one_match(r"^GoMobile bridge version: (.+)$", notices, "third-party notice bridge version", re.MULTILINE),
        bridge_version,
        "third-party notice bridge version",
    )
    require_equal(
        one_match(r"^OC Deck 应用版本：(.+)$", chinese_notices, "Chinese third-party notice app version", re.MULTILINE),
        version_name,
        "Chinese third-party notice app version",
    )
    require_equal(
        one_match(r"^GoMobile bridge 版本：(.+)$", chinese_notices, "Chinese third-party notice bridge version", re.MULTILINE),
        bridge_version,
        "Chinese third-party notice bridge version",
    )

    vector_count = vectors.get("assetCount")
    require(isinstance(vector_count, int) and vector_count > 0, "vector source assetCount must be positive")
    require(
        f"OC Deck contains {vector_count} Android vector resources" in notices,
        "THIRD_PARTY_NOTICES.txt does not state the current OpenCode-derived vector count",
    )
    require(
        "from OpenCode's general UI icon source" in notices
        and "packages/ui/src/components/logo.tsx" not in notices,
        "THIRD_PARTY_NOTICES.txt must describe only the remaining non-brand OpenCode UI icons",
    )

    frp_patches = frp.get("patches")
    require(isinstance(frp_patches, list), "frp source patches must be an array")
    for patch in frp_patches:
        require(isinstance(patch, dict), "frp source patch entry must be an object")
        for category in ("modifiedFiles", "addedFiles"):
            files = patch.get(category)
            require(isinstance(files, list), f"frp source patch {category} must be an array")
            for path in files:
                require(
                    isinstance(path, str) and f"- {path}" in notices,
                    f"THIRD_PARTY_NOTICES.txt is missing frp {category} entry: {path}",
                )
                require(
                    f"- {path}" in chinese_notices,
                    f"THIRD_PARTY_NOTICES.zh-CN.txt is missing frp {category} entry: {path}",
                )

    require(
        "the union of Android arm64, arm, 386," in notices
        and "and amd64 Go dependency walks" in notices,
        "THIRD_PARTY_NOTICES.txt does not describe the four-target Go dependency union",
    )
    require(
        "assets/legal/" in notices and "META-INF/OCDECK/" in notices,
        "THIRD_PARTY_NOTICES.txt does not describe embedded APK/AAR legal metadata",
    )

    third_party_readme = read_text(THIRD_PARTY / "README.md")
    require(
        all(target in third_party_readme for target in EXPECTED_GO_TARGETS),
        "third_party/README.md does not describe all audited Android Go targets",
    )
    require(
        "assets/legal/" in third_party_readme and "META-INF/OCDECK/" in third_party_readme,
        "third_party/README.md does not describe embedded APK/AAR legal metadata",
    )

    audited_on = manifest.get("audited_on")
    require(isinstance(audited_on, str), "components.toml audited_on must be a string")
    require_equal(
        one_match(r"^Last reviewed: (.+)$", notices, "third-party notice review date", re.MULTILINE),
        audited_on,
        "third-party notice review date",
    )
    require_equal(
        one_match(r"^Last updated: (.+)$", contents["PRIVACY.md"], "privacy update date", re.MULTILINE),
        audited_on,
        "privacy update date",
    )
    require_equal(
        one_match(r"^最后审阅日期：(.+)$", chinese_notices, "Chinese third-party notice review date", re.MULTILINE),
        audited_on,
        "Chinese third-party notice review date",
    )
    require_equal(
        one_match(r"^最后更新：(.+)$", contents["PRIVACY.zh-CN.md"], "Chinese privacy update date", re.MULTILINE),
        audited_on,
        "Chinese privacy update date",
    )

    notice = contents["NOTICE"]
    for label, value in (
        ("audio upstream commit", audio.get("upstreamCommit")),
        ("vector upstream commit", vectors.get("upstreamCommit")),
        ("frp upstream commit", frp.get("upstreamCommit")),
        ("frp downstream version", frp.get("downstreamVersion")),
    ):
        require(isinstance(value, str) and value in notice, f"NOTICE is missing current {label}: {value}")
        require(value in notices, f"THIRD_PARTY_NOTICES.txt is missing current {label}: {value}")
        require(value in chinese_notices, f"THIRD_PARTY_NOTICES.zh-CN.txt is missing current {label}: {value}")

    app_build = read_text(ROOT / "app/build.gradle.kts")
    release_verifier = read_text(ROOT / ".github/scripts/verify-release-artifacts.sh")
    for legal_file in ("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.txt", "TRADEMARKS.md"):
        require(
            f'rootProject.file("{legal_file}")' in app_build,
            f"generateLegalAssets does not include {legal_file}",
        )
        require(
            legal_file in release_verifier,
            f"release artifact verifier does not check {legal_file}",
        )


def main() -> None:
    manifest = load_toml(THIRD_PARTY / "components.toml")
    require_equal(manifest.get("schema_version"), 1, "components.toml schema_version")
    components = components_by_id(manifest)
    required_component_ids = {
        "ocdeck",
        "gradle-wrapper",
        "frp-patched",
        "fatedier-yamux",
        "opencode-ui-vectors",
        "opencode-audio",
    }
    missing_components = sorted(required_component_ids - components.keys())
    require(not missing_components, f"components.toml is missing required components: {missing_components}")

    version_name, _application_id, bridge_version, _coordinate = audit_versions(
        manifest, components
    )
    audit_component_files(components)
    audio, vectors, audio_count, vector_count = audit_assets(components)
    audit_forbidden_assets()
    audit_gradle_wrapper(components)
    frp, frp_manifest, patched_file_count, interop_asset_count = audit_frp(components, bridge_version)
    go_union_count, listed_go_count = audit_go_modules(manifest, components, frp_manifest)
    audit_release_legal_files(
        manifest,
        version_name,
        bridge_version,
        audio,
        vectors,
        frp,
    )

    print(
        "Third-party audit passed: "
        f"{len(components)} components, {audio_count} audio assets, {vector_count} OpenCode-derived vectors, "
        f"{patched_file_count} patched frp files, {interop_asset_count} pinned test-only frp release assets, "
        f"and {go_union_count} external Go modules "
        f"({listed_go_count} grouped in modules arrays) across {len(EXPECTED_GO_TARGETS)} Android targets."
    )


if __name__ == "__main__":
    try:
        main()
    except AuditError as error:
        print(f"third-party audit failed: {error}", file=sys.stderr)
        raise SystemExit(1)
