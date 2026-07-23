#!/usr/bin/env bash
set -euo pipefail
export GOWORK=off
export GOFLAGS=

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
source "$SCRIPT_DIR/bridge-versions.properties"

ARTIFACT_NAME="frpc-stcp-visitor-gobridge-$BRIDGE_VERSION"
AAR_OUTPUT="$SCRIPT_DIR/../frpc-stcp-visitor/libs/frpc-stcp-visitor.aar"
SOURCES_OUTPUT="${AAR_OUTPUT%.aar}-sources.jar"
REPO_DIR="$SCRIPT_DIR/build/repo/io/github/ycfeng/ocdeck/frpc-stcp-visitor-gobridge/$BRIDGE_VERSION"
REPO_AAR="$REPO_DIR/$ARTIFACT_NAME.aar"
REPO_SOURCES="$REPO_DIR/$ARTIFACT_NAME-sources.jar"
REPO_POM="$REPO_DIR/$ARTIFACT_NAME.pom"
REPO_SHA="$REPO_AAR.sha256"
REPO_API="$REPO_AAR.api.txt"
REPO_PROVENANCE="$REPO_AAR.provenance.json"
REPO_FRP_PROVENANCE="$REPO_AAR.frp-provenance.json"
REPO_NATIVE="$REPO_AAR.native.json"
PATCH_PROVENANCE="$SCRIPT_DIR/build/frp-patch-provenance.json"
MODULE_PROXY_CONFIG="$SCRIPT_DIR/build/module-proxy.properties"
STAGING_DIR="$SCRIPT_DIR/build/aar-staging"
STAGED_AAR="$STAGING_DIR/frpc-stcp-visitor.aar"
STAGED_SOURCES="$STAGING_DIR/frpc-stcp-visitor-sources.jar"
STAGED_API="$STAGING_DIR/bridge-api.txt"
PRE_NATIVE_REPORT="$STAGING_DIR/native-preflight.json"
VALIDATED_NATIVE_REPORT="$STAGING_DIR/native-validated.json"
EMBEDDED_PROVENANCE="$STAGING_DIR/bridge-provenance.json"
EXTERNAL_PROVENANCE="$STAGING_DIR/bridge-provenance-with-hash.json"
API_CHECK_DIR="$STAGING_DIR/api-check"
EXPECTED_API="$SCRIPT_DIR/api/frpcstcpvisitor.txt"
BRIDGE_CLASS='io.github.ycfeng.ocdeck.frpcstcpvisitor.gobridge.frpcstcpvisitor.Frpcstcpvisitor'

if ! command -v go >/dev/null 2>&1; then
  echo "go was not found on PATH" >&2
  exit 1
fi

ACTUAL_GO_VERSION=$(go env GOVERSION)
if [[ "$ACTUAL_GO_VERSION" != "$GO_VERSION" ]]; then
  echo "Go version mismatch: got $ACTUAL_GO_VERSION, require $GO_VERSION" >&2
  exit 1
fi

GOPATH=$(go env GOPATH)
GO_HOST_OS=$(go env GOHOSTOS)
if [[ "$GO_HOST_OS" == windows ]]; then
  PRIMARY_GOPATH=${GOPATH%%;*}
  if command -v cygpath >/dev/null 2>&1; then
    PRIMARY_GOPATH=$(cygpath -u "$PRIMARY_GOPATH")
  fi
else
  PRIMARY_GOPATH=${GOPATH%%:*}
fi
[[ -n "$PRIMARY_GOPATH" ]] || { echo "go env GOPATH returned no usable entries" >&2; exit 1; }
export PATH="$PRIMARY_GOPATH/bin:$PATH"

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && -f "$ROOT_DIR/local.properties" ]]; then
  SDK_DIR=$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -n 1)
  if [[ -n "$SDK_DIR" ]]; then
    SDK_DIR=${SDK_DIR//\\:/:}
    SDK_DIR=${SDK_DIR//\\\\/\\}
    export ANDROID_HOME="$SDK_DIR"
    export ANDROID_SDK_ROOT="$SDK_DIR"
  fi
fi
SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
[[ -n "$SDK_ROOT" ]] || { echo "Android SDK path is not configured" >&2; exit 1; }
NDK_DIR="$SDK_ROOT/ndk/$NDK_VERSION"
[[ -d "$NDK_DIR" ]] || { echo "Android NDK $NDK_VERSION is missing" >&2; exit 1; }
export ANDROID_NDK_HOME="$NDK_DIR"

cd "$SCRIPT_DIR"
go run ./cmd/preparefrp
go run ./cmd/preparemoduleproxy

PROXY_URL=
BIND_PACKAGE=
BIND_MODULE_RELATIVE=
NO_SUM_DB=
while IFS='=' read -r key value; do
  case "$key" in
    PROXY_URL) PROXY_URL=$value ;;
    BIND_PACKAGE) BIND_PACKAGE=$value ;;
    BIND_MODULE_DIR) BIND_MODULE_RELATIVE=$value ;;
    GONOSUMDB) NO_SUM_DB=$value ;;
  esac
done < "$MODULE_PROXY_CONFIG"
if [[ -z "$PROXY_URL" || -z "$BIND_PACKAGE" || -z "$BIND_MODULE_RELATIVE" || -z "$NO_SUM_DB" ]]; then
  echo "Generated module proxy configuration is incomplete" >&2
  exit 1
fi
BIND_MODULE_DIR="$SCRIPT_DIR/$BIND_MODULE_RELATIVE"
export GOPROXY="$PROXY_URL,https://proxy.golang.org,direct"
export GONOSUMDB="$NO_SUM_DB"
export GONOPROXY=none
export GOPRIVATE=

go install golang.org/x/mobile/cmd/gomobile
go install golang.org/x/mobile/cmd/gobind
mkdir -p "$PRIMARY_GOPATH/pkg/gomobile"

rm -f "$AAR_OUTPUT" "$SOURCES_OUTPUT"
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR" "$(dirname "$AAR_OUTPUT")"

(
  cd "$BIND_MODULE_DIR"
  gomobile bind \
    -target android \
    -androidapi "$ANDROID_API" \
    -javapkg io.github.ycfeng.ocdeck.frpcstcpvisitor.gobridge \
    -trimpath \
    -ldflags '-s -w -extldflags=-Wl,-z,max-page-size=16384' \
    -o "$STAGED_AAR" \
    "$BIND_PACKAGE"
)
[[ -f "$STAGED_AAR" ]] || { echo "gomobile did not create the AAR" >&2; exit 1; }
[[ -f "$STAGED_SOURCES" ]] || { echo "gomobile did not create the sources JAR" >&2; exit 1; }

go run ./cmd/normalizezip "$STAGED_AAR"
go run ./cmd/normalizezip "$STAGED_SOURCES"
mkdir -p "$API_CHECK_DIR"
(
  cd "$API_CHECK_DIR"
  jar xf "$STAGED_AAR" classes.jar
)
javap -classpath "$API_CHECK_DIR/classes.jar" "$BRIDGE_CLASS" > "$STAGED_API"
diff -u "$EXPECTED_API" "$STAGED_API"

go run ./cmd/checkaar \
  -native-only \
  -root "$ROOT_DIR" \
  -versions "$SCRIPT_DIR/bridge-versions.properties" \
  "$STAGED_AAR" > "$PRE_NATIVE_REPORT"

go run ./cmd/writebridgeprovenance \
  -versions "$SCRIPT_DIR/bridge-versions.properties" \
  -native-report "$PRE_NATIVE_REPORT" \
  -output "$EMBEDDED_PROVENANCE"

ADD_ARGUMENTS=(
  --add-text "META-INF/OCDECK/LICENSE.txt=$ROOT_DIR/LICENSE"
  --add-text "META-INF/OCDECK/NOTICE.txt=$ROOT_DIR/NOTICE"
  --add-text "META-INF/OCDECK/THIRD_PARTY_NOTICES.txt=$ROOT_DIR/THIRD_PARTY_NOTICES.txt"
  --add-text "META-INF/OCDECK/TRADEMARKS.md=$ROOT_DIR/TRADEMARKS.md"
  --add-text "META-INF/OCDECK/bridge-api.txt=$EXPECTED_API"
  --add-text "META-INF/OCDECK/bridge-provenance.json=$EMBEDDED_PROVENANCE"
  --add-text "META-INF/OCDECK/frp-patch-provenance.json=$PATCH_PROVENANCE"
)
shopt -s nullglob
LICENSE_FILES=("$ROOT_DIR"/third_party/licenses/*)
[[ ${#LICENSE_FILES[@]} -gt 0 ]] || { echo "No third-party license files found" >&2; exit 1; }
for license in "${LICENSE_FILES[@]}"; do
  ADD_ARGUMENTS+=(--add-text "META-INF/OCDECK/licenses/$(basename "$license")=$license")
done
go run ./cmd/normalizezip "${ADD_ARGUMENTS[@]}" "$STAGED_AAR"

go run ./cmd/checkaar \
  -versions "$SCRIPT_DIR/bridge-versions.properties" \
  -root "$ROOT_DIR" \
  -api "$EXPECTED_API" \
  -bridge-provenance "$EMBEDDED_PROVENANCE" \
  -frp-provenance "$PATCH_PROVENANCE" \
  "$STAGED_AAR" > "$VALIDATED_NATIVE_REPORT"
AAR_SHA256=$(go run ./cmd/filehash "$STAGED_AAR")

mkdir -p "$REPO_DIR"
if [[ -f "$REPO_AAR" ]]; then
  EXISTING_SHA256=$(go run ./cmd/filehash "$REPO_AAR")
  [[ "$EXISTING_SHA256" == "$AAR_SHA256" ]] || {
    echo "Immutable bridge version $BRIDGE_VERSION already exists with different bytes" >&2
    exit 1
  }
else
  cp "$STAGED_AAR" "$REPO_AAR"
fi
cp "$STAGED_AAR" "$AAR_OUTPUT.tmp"
mv -f "$AAR_OUTPUT.tmp" "$AAR_OUTPUT"
cp "$STAGED_SOURCES" "$REPO_SOURCES"
cp "$STAGED_SOURCES" "$SOURCES_OUTPUT.tmp"
mv -f "$SOURCES_OUTPUT.tmp" "$SOURCES_OUTPUT"

cat > "$REPO_POM" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.ycfeng.ocdeck</groupId>
  <artifactId>frpc-stcp-visitor-gobridge</artifactId>
  <version>$BRIDGE_VERSION</version>
  <packaging>aar</packaging>
  <name>OC Deck frpc STCP visitor GoMobile bridge</name>
  <description>Native frpc STCP visitor bridge used by the OC Deck Android client.</description>
  <licenses>
    <license>
      <name>MIT License</name>
      <url>https://opensource.org/license/mit</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
</project>
POM
printf '%s  %s\n' "$AAR_SHA256" "$ARTIFACT_NAME.aar" > "$REPO_SHA"
cp "$EXPECTED_API" "$REPO_API"
cp "$PATCH_PROVENANCE" "$REPO_FRP_PROVENANCE"
cp "$VALIDATED_NATIVE_REPORT" "$REPO_NATIVE"
go run ./cmd/writebridgeprovenance \
  -versions "$SCRIPT_DIR/bridge-versions.properties" \
  -native-report "$VALIDATED_NATIVE_REPORT" \
  -output "$EXTERNAL_PROVENANCE" \
  -aar-sha256 "$AAR_SHA256"
cp "$EXTERNAL_PROVENANCE" "$REPO_PROVENANCE"

echo "AAR: $AAR_OUTPUT"
echo "Maven artifact: $REPO_AAR"
echo "SHA-256: $AAR_SHA256"
