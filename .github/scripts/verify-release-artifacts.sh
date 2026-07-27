#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
    printf 'usage: %s <gomobile-release|kotlin-default-release-dry-run> <version-name> <version-code> <expected-cert-sha256> <staging-directory>\n' "$0" >&2
    exit 2
fi

PROFILE=$1
VERSION_NAME=$2
VERSION_CODE=$3
EXPECTED_CERT_SHA256=$4
STAGING_DIR=$5
case "$PROFILE" in
    gomobile-release)
        APK_VARIANT=release
        ;;
    kotlin-default-release-dry-run)
        APK_VARIANT=kotlinDefaultReleaseDryRun
        ;;
    *)
        printf 'release artifact verification failed: unsupported artifact profile: %s\n' "$PROFILE" >&2
        exit 1
        ;;
esac
APK_DIR="app/build/outputs/apk/$APK_VARIANT"
APK_METADATA="$APK_DIR/output-metadata.json"
BRIDGE_VERSION=$(sed -n 's/^BRIDGE_VERSION=//p' frpc-stcp-visitor-go/bridge-versions.properties)
BRIDGE_REPO_DIR="frpc-stcp-visitor-go/build/repo/io/github/ycfeng/ocdeck/frpc-stcp-visitor-gobridge/$BRIDGE_VERSION"
BRIDGE_NATIVE_REPORT="$BRIDGE_REPO_DIR/frpc-stcp-visitor-gobridge-$BRIDGE_VERSION.aar.native.json"
ANDROID_SDK_PATH=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
BUILD_TOOLS_DIR="${ANDROID_SDK_PATH}/build-tools/36.0.0"
APKSIGNER=${APKSIGNER:-}
APKSIGNER_BATCH=${APKSIGNER_BATCH:-}
APKSIGNER_JAR=${APKSIGNER_JAR:-}
JAVA_COMMAND=${JAVA_COMMAND:-java}
ZIPALIGN=${ZIPALIGN:-}
APKANALYZER=${APKANALYZER:-}
BRIDGE_DEFINITION=io.github.ycfeng.ocdeck.frpcstcpvisitor.gobridge.frpcstcpvisitor.Frpcstcpvisitor

if [[ -z $APKSIGNER && -z $APKSIGNER_BATCH && -z $APKSIGNER_JAR ]]; then
    if [[ -x ${BUILD_TOOLS_DIR}/apksigner ]]; then
        APKSIGNER=${BUILD_TOOLS_DIR}/apksigner
    elif [[ -f ${BUILD_TOOLS_DIR}/apksigner.bat ]] &&
        command -v cmd.exe >/dev/null && command -v wslpath >/dev/null; then
        APKSIGNER_BATCH=${BUILD_TOOLS_DIR}/apksigner.bat
    elif [[ -f ${BUILD_TOOLS_DIR}/lib/apksigner.jar ]]; then
        APKSIGNER_JAR=${BUILD_TOOLS_DIR}/lib/apksigner.jar
    fi
fi

if [[ -z $ZIPALIGN ]]; then
    for candidate in "${BUILD_TOOLS_DIR}/zipalign" "${BUILD_TOOLS_DIR}/zipalign.exe"; do
        if [[ -x $candidate ]]; then
            ZIPALIGN=$candidate
            break
        fi
    done
fi

if [[ -z $APKANALYZER ]]; then
    for candidate in "${ANDROID_SDK_PATH}/cmdline-tools/latest/bin/apkanalyzer"; do
        if [[ -x $candidate ]]; then
            APKANALYZER=$candidate
            break
        fi
    done
fi

fail() {
    printf 'release artifact verification failed: %s\n' "$*" >&2
    exit 1
}

normalize_fingerprint() {
    printf '%s' "$1" | tr -d '[:space:]:' | tr '[:lower:]' '[:upper:]'
}

run_apksigner() {
    if [[ -n $APKSIGNER ]]; then
        "$APKSIGNER" "$@"
    elif [[ -n $APKSIGNER_BATCH ]]; then
        local argument
        local -a windows_arguments=()
        for argument in "$@"; do
            if [[ -e $argument ]]; then
                argument=$(wslpath -m "$(realpath "$argument")")
            fi
            windows_arguments+=("$argument")
        done
        cmd.exe /d /c "$(wslpath -m "$APKSIGNER_BATCH")" "${windows_arguments[@]}"
    else
        "$JAVA_COMMAND" -jar "$APKSIGNER_JAR" "$@"
    fi
}

validate_apk_metadata() {
    python3 - "$APK_METADATA" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    metadata = json.load(source)

if metadata.get("applicationId") != "io.github.ycfeng.ocdeck" or metadata.get("variantName") != sys.argv[2]:
    raise SystemExit(1)
PY
}

list_apk_rows() {
    python3 - "$APK_METADATA" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    metadata = json.load(source)

for element in metadata.get("elements", []):
    abi = next(
        (
            item.get("value", "")
            for item in element.get("filters", [])
            if item.get("filterType") == "ABI"
        ),
        "",
    )
    print(
        "\t".join(
            (
                str(abi),
                str(element.get("versionName", "")),
                str(element.get("versionCode", "")),
                str(element.get("outputFile", "")),
            )
        )
    )
PY
}

native_sha_for_abi() {
    python3 - "$BRIDGE_NATIVE_REPORT" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    report = json.load(source)

matches = [
    item.get("sha256")
    for item in report.get("libraries", [])
    if item.get("abi") == sys.argv[2]
]
if len(matches) != 1 or not isinstance(matches[0], str) or not matches[0]:
    raise SystemExit(1)
print(matches[0])
PY
}

apk_defines_bridge() {
    local apk_path=$1
    local output_file
    output_file=$(mktemp)
    if ! "$APKANALYZER" dex packages --defined-only "$apk_path" >"$output_file"; then
        rm -f "$output_file"
        return 2
    fi
    python3 - "$output_file" "$BRIDGE_DEFINITION" <<'PY'
from pathlib import Path
import sys

expected = sys.argv[2]
for line in Path(sys.argv[1]).read_text(encoding="utf-8", errors="strict").splitlines():
    for token in line.split():
        normalized = token.replace("/", ".").removeprefix("L").removesuffix(";")
        if normalized == expected:
            raise SystemExit(0)
raise SystemExit(1)
PY
    local status=$?
    rm -f "$output_file"
    return "$status"
}

apk_manifest_value() {
    local apk_path=$1
    local attribute=$2
    local output_file
    output_file=$(mktemp)
    if ! "$APKANALYZER" manifest "$attribute" "$apk_path" >"$output_file"; then
        rm -f "$output_file"
        return 2
    fi
    python3 - "$output_file" <<'PY'
from pathlib import Path
import sys

raw = Path(sys.argv[1]).read_bytes()
if not 1 <= len(raw) <= 4096 or b"\0" in raw:
    raise SystemExit(1)
text = raw.decode("utf-8", errors="strict")
lines = text.splitlines()
if len(lines) != 1 or not lines[0] or lines[0] != lines[0].strip():
    raise SystemExit(1)
print(lines[0])
PY
    local status=$?
    rm -f "$output_file"
    return "$status"
}

EXPECTED_CERT_SHA256=$(normalize_fingerprint "$EXPECTED_CERT_SHA256")
[[ $VERSION_NAME =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
    fail "version name must be stable SemVer: $VERSION_NAME"
[[ $VERSION_CODE =~ ^[1-9][0-9]*$ ]] || fail "version code must be a positive integer: $VERSION_CODE"
[[ $EXPECTED_CERT_SHA256 =~ ^[0-9A-F]{64}$ ]] || fail "expected certificate SHA-256 must contain 64 hexadecimal characters"
[[ -n $ANDROID_SDK_PATH ]] || fail "ANDROID_SDK_ROOT or ANDROID_HOME is not set"
if [[ -n $APKSIGNER ]]; then
    [[ -x $APKSIGNER ]] || fail "apksigner not found: $APKSIGNER"
elif [[ -n $APKSIGNER_BATCH ]]; then
    [[ -f $APKSIGNER_BATCH ]] || fail "apksigner.bat not found: $APKSIGNER_BATCH"
else
    [[ -f $APKSIGNER_JAR ]] || fail "apksigner or apksigner.jar not found under $BUILD_TOOLS_DIR"
    if [[ $JAVA_COMMAND == */* ]]; then
        [[ -x $JAVA_COMMAND ]] || fail "java is required to run $APKSIGNER_JAR: $JAVA_COMMAND"
    else
        command -v "$JAVA_COMMAND" >/dev/null || fail "java is required to run $APKSIGNER_JAR"
    fi
fi
[[ -n $ZIPALIGN && -x $ZIPALIGN ]] || fail "zipalign not found under $BUILD_TOOLS_DIR"
[[ -n $APKANALYZER && -x $APKANALYZER ]] || fail "apkanalyzer not found under ${ANDROID_SDK_PATH}/cmdline-tools/latest/bin"
[[ -f $APK_METADATA ]] || fail "APK metadata not found: $APK_METADATA"
if [[ $PROFILE == gomobile-release ]]; then
    [[ -n $BRIDGE_VERSION ]] || fail "BRIDGE_VERSION is missing or duplicated"
    [[ -f $BRIDGE_NATIVE_REPORT ]] || fail "bridge native report not found: $BRIDGE_NATIVE_REPORT"
fi
command -v python3 >/dev/null || fail "python3 is required"
command -v unzip >/dev/null || fail "unzip is required"
command -v sha256sum >/dev/null || fail "sha256sum is required"
validate_apk_metadata "$APK_VARIANT" ||
    fail "APK metadata has an unexpected applicationId or variantName"

if [[ -e $STAGING_DIR ]]; then
    [[ -d $STAGING_DIR ]] || fail "staging path is not a directory: $STAGING_DIR"
    [[ -z $(find "$STAGING_DIR" -mindepth 1 -maxdepth 1 -print -quit) ]] ||
        fail "staging directory must be empty: $STAGING_DIR"
else
    mkdir -p "$STAGING_DIR"
fi

EXPECTED_ABIS=(arm64-v8a armeabi-v7a x86_64)
EXPECTED_APKS=(
    "OCDeck_${VERSION_NAME}_arm64-v8a.apk"
    "OCDeck_${VERSION_NAME}_armeabi-v7a.apk"
    "OCDeck_${VERSION_NAME}_x86_64.apk"
)
declare -A EXPECTED_FILE_BY_ABI=(
    [arm64-v8a]="${EXPECTED_APKS[0]}"
    [armeabi-v7a]="${EXPECTED_APKS[1]}"
    [x86_64]="${EXPECTED_APKS[2]}"
)
declare -A SEEN_ABI=()

verify_apk_asset() {
    local apk_path=$1
    local source_path=$2
    local asset_path=$3
    unzip -p "$apk_path" "$asset_path" | cmp - "$source_path" >/dev/null ||
        fail "$(basename "$apk_path") asset $asset_path does not match $source_path"
}

mapfile -t APK_ROWS < <(
    list_apk_rows
)
[[ ${#APK_ROWS[@]} -eq 3 ]] || fail "expected exactly three release APK outputs, found ${#APK_ROWS[@]}"

for row in "${APK_ROWS[@]}"; do
    IFS=$'\t' read -r abi metadata_version metadata_version_code output_file <<< "$row"
    [[ -n ${EXPECTED_FILE_BY_ABI[$abi]+x} ]] || fail "unexpected or missing APK ABI filter: ${abi:-<none>}"
    [[ -z ${SEEN_ABI[$abi]+x} ]] || fail "duplicate APK ABI output: $abi"
    [[ $metadata_version == "$VERSION_NAME" ]] ||
        fail "$output_file reports versionName=$metadata_version, expected $VERSION_NAME"
    [[ $metadata_version_code == "$VERSION_CODE" ]] ||
        fail "$output_file reports versionCode=$metadata_version_code, expected $VERSION_CODE"
    [[ $output_file == "${EXPECTED_FILE_BY_ABI[$abi]}" ]] ||
        fail "unexpected APK filename for $abi: $output_file"

    apk_path="$APK_DIR/$output_file"
    [[ -f $apk_path ]] || fail "APK not found: $apk_path"

    actual_application_id=$(apk_manifest_value "$apk_path" application-id) ||
        fail "$output_file manifest application ID inspection failed"
    [[ $actual_application_id == io.github.ycfeng.ocdeck ]] ||
        fail "$output_file manifest application ID is unexpected"
    actual_version_name=$(apk_manifest_value "$apk_path" version-name) ||
        fail "$output_file manifest version name inspection failed"
    [[ $actual_version_name == "$VERSION_NAME" ]] ||
        fail "$output_file manifest version name is unexpected"
    actual_version_code=$(apk_manifest_value "$apk_path" version-code) ||
        fail "$output_file manifest version code inspection failed"
    [[ $actual_version_code == "$VERSION_CODE" ]] ||
        fail "$output_file manifest version code is unexpected"

    signer_output=$(run_apksigner verify --verbose --print-certs "$apk_path") ||
        fail "apksigner verification failed: $output_file"
    mapfile -t signer_digests < <(
        printf '%s\n' "$signer_output" |
            sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p'
    )
    [[ ${#signer_digests[@]} -eq 1 ]] || fail "$output_file must have exactly one signer"
    actual_apk_fingerprint=$(normalize_fingerprint "${signer_digests[0]}")
    [[ $actual_apk_fingerprint == "$EXPECTED_CERT_SHA256" ]] ||
        fail "$output_file signer certificate does not match RELEASE_CERT_SHA256"

    "$ZIPALIGN" -c -P 16 -v 4 "$apk_path" >/dev/null || fail "zipalign verification failed: $output_file"

    apk_absolute_path=$(realpath "$apk_path")
    case "$PROFILE" in
        gomobile-release)
            expected_native_sha=$(native_sha_for_abi "$abi") ||
                fail "bridge native report does not contain ABI $abi"
            (
                cd frpc-stcp-visitor-go
                go run ./cmd/checkapk gomobile-release "$apk_absolute_path" "$abi" "$expected_native_sha" >/dev/null
            ) || fail "$output_file native ELF or AAR byte binding verification failed"
            if apk_defines_bridge "$apk_path"; then
                :
            else
                status=$?
                [[ $status -eq 1 ]] || fail "$output_file bridge class inspection failed"
                fail "$output_file does not define the GoMobile bridge class"
            fi
            ;;
        kotlin-default-release-dry-run)
            (
                cd frpc-stcp-visitor-go
                go run ./cmd/checkapk kotlin-default-release-dry-run "$apk_absolute_path" "$abi" >/dev/null
            ) || fail "$output_file native ELF verification or GoMobile exclusion failed"
            if apk_defines_bridge "$apk_path"; then
                fail "$output_file unexpectedly defines the GoMobile bridge class"
            else
                status=$?
                [[ $status -eq 1 ]] || fail "$output_file bridge class inspection failed"
            fi
            ;;
    esac

    verify_apk_asset "$apk_path" LICENSE "assets/legal/LICENSE.txt"
    verify_apk_asset "$apk_path" NOTICE "assets/legal/NOTICE.txt"
    verify_apk_asset "$apk_path" THIRD_PARTY_NOTICES.txt "assets/legal/THIRD_PARTY_NOTICES.txt"
    verify_apk_asset "$apk_path" TRADEMARKS.md "assets/legal/TRADEMARKS.md"
    verify_apk_asset \
        "$apk_path" \
        app/build/generated/legalText/THIRD_PARTY_LICENSES.txt \
        "assets/legal/THIRD_PARTY_LICENSES.txt"
    for license in third_party/licenses/*; do
        verify_apk_asset "$apk_path" "$license" "assets/legal/licenses/$(basename "$license")"
    done

    cp "$apk_path" "$STAGING_DIR/$output_file"
    SEEN_ABI[$abi]=true
done

for abi in "${EXPECTED_ABIS[@]}"; do
    [[ -n ${SEEN_ABI[$abi]+x} ]] || fail "missing APK for ABI $abi"
done

(
    cd "$STAGING_DIR"
    sha256sum "${EXPECTED_APKS[@]}" > SHA256SUMS
)

printf 'Verified release assets in %s\n' "$STAGING_DIR"
