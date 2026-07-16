#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
    printf 'usage: %s <version-name> <version-code> <expected-cert-sha256> <staging-directory>\n' "$0" >&2
    exit 2
fi

VERSION_NAME=$1
VERSION_CODE=$2
EXPECTED_CERT_SHA256=$3
STAGING_DIR=$4
APK_DIR=app/build/outputs/apk/release
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
    python3 - "$APK_METADATA" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    metadata = json.load(source)

if metadata.get("applicationId") != "io.github.ycfeng.ocdeck" or metadata.get("variantName") != "release":
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
[[ -f $APK_METADATA ]] || fail "APK metadata not found: $APK_METADATA"
[[ -f $BRIDGE_NATIVE_REPORT ]] || fail "bridge native report not found: $BRIDGE_NATIVE_REPORT"
command -v python3 >/dev/null || fail "python3 is required"
command -v unzip >/dev/null || fail "unzip is required"
command -v sha256sum >/dev/null || fail "sha256sum is required"
validate_apk_metadata ||
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

    mapfile -t native_entries < <(unzip -Z1 "$apk_path" | grep -E '^lib/[^/]+/[^/]+\.so$' || true)
    [[ ${#native_entries[@]} -gt 0 ]] || fail "$output_file does not contain native libraries"
    has_gojni=false
    for entry in "${native_entries[@]}"; do
        entry_abi=${entry#lib/}
        entry_abi=${entry_abi%%/*}
        [[ $entry_abi == "$abi" ]] || fail "$output_file contains unexpected native ABI $entry_abi"
        [[ $entry == "lib/${abi}/libgojni.so" ]] && has_gojni=true
    done
    [[ $has_gojni == true ]] || fail "$output_file does not contain lib/${abi}/libgojni.so"

    expected_native_sha=$(native_sha_for_abi "$abi") ||
        fail "bridge native report does not contain ABI $abi"
    apk_absolute_path=$(realpath "$apk_path")
    (
        cd frpc-stcp-visitor-go
        go run ./cmd/checkapk "$apk_absolute_path" "$abi" "$expected_native_sha" >/dev/null
    ) || fail "$output_file native ELF or AAR byte binding verification failed"

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
