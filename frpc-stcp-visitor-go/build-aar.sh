#!/usr/bin/env bash
set -euo pipefail

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
PATCHED_MODFILE="$SCRIPT_DIR/build/frp-patched.mod"
PATCH_PROVENANCE="$SCRIPT_DIR/build/frp-patch-provenance.json"
BIND_MODULE_DIR="$SCRIPT_DIR/build/bind-module"
STAGING_DIR="$SCRIPT_DIR/build/aar-staging"
STAGED_AAR="$STAGING_DIR/frpc-stcp-visitor.aar"
STAGED_SOURCES="$STAGING_DIR/frpc-stcp-visitor-sources.jar"
STAGED_API="$STAGING_DIR/bridge-api.txt"
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
export PATH="$GOPATH/bin:$PATH"

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
[[ -d "$NDK_DIR" ]] || { echo "Android NDK $NDK_VERSION is missing at $NDK_DIR" >&2; exit 1; }
export ANDROID_NDK_HOME="$NDK_DIR"

cd "$SCRIPT_DIR"
go run ./cmd/preparefrp
go install "golang.org/x/mobile/cmd/gomobile@$XMOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$XMOBILE_VERSION"
gomobile init

rm -f "$AAR_OUTPUT" "$SOURCES_OUTPUT"
rm -rf "$BIND_MODULE_DIR" "$STAGING_DIR"
mkdir -p "$BIND_MODULE_DIR" "$STAGING_DIR" "$(dirname "$AAR_OUTPUT")"
cp "$SCRIPT_DIR/visitor.go" "$SCRIPT_DIR/types.go" "$BIND_MODULE_DIR/"
ANET_PATH=$(cd -- "$SCRIPT_DIR/internal/anetcompat" && pwd)
sed "s|replace github.com/wlynxg/anet => ./internal/anetcompat|replace github.com/wlynxg/anet => $ANET_PATH|" \
  "$PATCHED_MODFILE" > "$BIND_MODULE_DIR/go.mod"
cp "$SCRIPT_DIR/build/frp-patched.sum" "$BIND_MODULE_DIR/go.sum"

(
  cd "$BIND_MODULE_DIR"
  gomobile bind \
    -target android \
    -androidapi "$ANDROID_API" \
    -javapkg io.github.ycfeng.ocdeck.frpcstcpvisitor.gobridge \
    -trimpath \
    -ldflags '-s -w -extldflags=-Wl,-z,max-page-size=16384' \
    -o "$STAGED_AAR" \
    .
)
[[ -f "$STAGED_AAR" ]] || { echo "gomobile did not create AAR: $STAGED_AAR" >&2; exit 1; }

go run ./cmd/normalizezip "$STAGED_AAR"
[[ ! -f "$STAGED_SOURCES" ]] || go run ./cmd/normalizezip "$STAGED_SOURCES"
mkdir -p "$API_CHECK_DIR"
(
  cd "$API_CHECK_DIR"
  jar xf "$STAGED_AAR" classes.jar
)
javap -classpath "$API_CHECK_DIR/classes.jar" "$BRIDGE_CLASS" > "$STAGED_API"
diff -u "$EXPECTED_API" "$STAGED_API"

go run ./cmd/writebridgeprovenance \
  -versions "$SCRIPT_DIR/bridge-versions.properties" \
  -output "$EMBEDDED_PROVENANCE"

ADD_ARGUMENTS=(
  --add "META-INF/OCDECK/LICENSE.txt=$ROOT_DIR/LICENSE"
  --add "META-INF/OCDECK/NOTICE.txt=$ROOT_DIR/NOTICE"
  --add "META-INF/OCDECK/THIRD_PARTY_NOTICES.txt=$ROOT_DIR/THIRD_PARTY_NOTICES.txt"
  --add "META-INF/OCDECK/TRADEMARKS.md=$ROOT_DIR/TRADEMARKS.md"
  --add "META-INF/OCDECK/bridge-api.txt=$EXPECTED_API"
  --add "META-INF/OCDECK/bridge-provenance.json=$EMBEDDED_PROVENANCE"
  --add "META-INF/OCDECK/frp-patch-provenance.json=$PATCH_PROVENANCE"
)
shopt -s nullglob
LICENSE_FILES=("$ROOT_DIR"/third_party/licenses/*)
[[ ${#LICENSE_FILES[@]} -gt 0 ]] || { echo "No third-party license files found" >&2; exit 1; }
for license in "${LICENSE_FILES[@]}"; do
  ADD_ARGUMENTS+=(--add "META-INF/OCDECK/licenses/$(basename "$license")=$license")
done
go run ./cmd/normalizezip "${ADD_ARGUMENTS[@]}" "$STAGED_AAR"

NATIVE_REPORT=$(go run ./cmd/checkaar \
  -root "$ROOT_DIR" \
  -api "$EXPECTED_API" \
  -bridge-provenance "$EMBEDDED_PROVENANCE" \
  -frp-provenance "$PATCH_PROVENANCE" \
  "$STAGED_AAR")
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
if [[ -f "$STAGED_SOURCES" ]]; then
  cp "$STAGED_SOURCES" "$REPO_SOURCES"
  cp "$STAGED_SOURCES" "$SOURCES_OUTPUT.tmp"
  mv -f "$SOURCES_OUTPUT.tmp" "$SOURCES_OUTPUT"
fi

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
printf '%s\n' "$NATIVE_REPORT" > "$REPO_NATIVE"
go run ./cmd/writebridgeprovenance \
  -versions "$SCRIPT_DIR/bridge-versions.properties" \
  -output "$EXTERNAL_PROVENANCE" \
  -aar-sha256 "$AAR_SHA256"
cp "$EXTERNAL_PROVENANCE" "$REPO_PROVENANCE"

echo "AAR: $AAR_OUTPUT"
echo "Maven artifact: $REPO_AAR"
echo "SHA-256: $AAR_SHA256"
