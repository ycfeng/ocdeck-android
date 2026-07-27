#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(git -C "$SCRIPT_DIR/../.." rev-parse --show-toplevel)
COMMIT=$(git -C "$ROOT_DIR" rev-parse HEAD)

if [[ -n "$(git -C "$ROOT_DIR" status --porcelain --untracked-files=all)" ]]; then
  echo "Bridge reproducibility verification requires a clean checkout" >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && -f "$ROOT_DIR/local.properties" ]]; then
  SDK_DIR=$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -n 1)
  if [[ -n "$SDK_DIR" ]]; then
    SDK_DIR=${SDK_DIR//\:/:}
    SDK_DIR=${SDK_DIR//\\\\/\\}
    export ANDROID_HOME="$SDK_DIR"
    export ANDROID_SDK_ROOT="$SDK_DIR"
  fi
fi

TEMP_PARENT=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
TEMP_ROOT=$(mktemp -d "$TEMP_PARENT/ocdeck-bridge-repro.XXXXXXXX")
SECONDARY_ROOT="$TEMP_ROOT/secondary-checkout-with-a-distinct-absolute-path"
WORKTREE_ADDED=false

cleanup() {
  local status=$?
  trap - EXIT
  set +e
  if [[ "$WORKTREE_ADDED" == true ]]; then
    git -C "$ROOT_DIR" worktree remove --force "$SECONDARY_ROOT" >/dev/null 2>&1 || true
  fi
  chmod -R u+w "$TEMP_ROOT" >/dev/null 2>&1 || true
  rm -rf "$TEMP_ROOT" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

git -C "$ROOT_DIR" -c core.autocrlf=false -c core.eol=lf worktree add --detach "$SECONDARY_ROOT" "$COMMIT"
WORKTREE_ADDED=true

build_bridge() {
  local checkout=$1
  local cache_root=$2
  mkdir -p "$cache_root/go-build" "$cache_root/go-mod" "$cache_root/gopath"
  (
    export GOCACHE="$cache_root/go-build"
    export GOMODCACHE="$cache_root/go-mod"
    export GOPATH="$cache_root/gopath"
    export GOENV=off
    export GOTOOLCHAIN=local
    bash "$checkout/frpc-stcp-visitor-go/build-aar.sh"
  )
}

build_bridge "$ROOT_DIR" "$TEMP_ROOT/cache-primary"
build_bridge "$SECONDARY_ROOT" "$TEMP_ROOT/cache-secondary"

BRIDGE_VERSION=$(sed -n 's/^BRIDGE_VERSION=//p' "$ROOT_DIR/frpc-stcp-visitor-go/bridge-versions.properties")
[[ -n "$BRIDGE_VERSION" ]] || { echo "BRIDGE_VERSION is missing" >&2; exit 1; }
ARTIFACT_NAME="frpc-stcp-visitor-gobridge-$BRIDGE_VERSION"
REPOSITORY_RELATIVE="frpc-stcp-visitor-go/build/repo/io/github/ycfeng/ocdeck/frpc-stcp-visitor-gobridge/$BRIDGE_VERSION"
PRIMARY_REPOSITORY="$ROOT_DIR/$REPOSITORY_RELATIVE"
SECONDARY_REPOSITORY="$SECONDARY_ROOT/$REPOSITORY_RELATIVE"
EXPECTED_FILES=(
  "$ARTIFACT_NAME-sources.jar"
  "$ARTIFACT_NAME.aar"
  "$ARTIFACT_NAME.aar.api.txt"
  "$ARTIFACT_NAME.aar.frp-provenance.json"
  "$ARTIFACT_NAME.aar.native.json"
  "$ARTIFACT_NAME.aar.provenance.json"
  "$ARTIFACT_NAME.aar.sha256"
  "$ARTIFACT_NAME.pom"
)

assert_artifact_set() {
  local directory=$1
  local expected
  local actual
  for expected in "${EXPECTED_FILES[@]}"; do
    [[ -s "$directory/$expected" ]] || { echo "Missing reproducibility artifact: $expected" >&2; exit 1; }
  done
  shopt -s nullglob
  local files=("$directory"/*)
  shopt -u nullglob
  [[ ${#files[@]} -eq ${#EXPECTED_FILES[@]} ]] || {
    echo "Bridge repository contains an unexpected artifact set" >&2
    exit 1
  }
  for actual in "${files[@]}"; do
    actual=$(basename "$actual")
    local found=false
    for expected in "${EXPECTED_FILES[@]}"; do
      if [[ "$actual" == "$expected" ]]; then
        found=true
        break
      fi
    done
    [[ "$found" == true ]] || { echo "Unexpected reproducibility artifact: $actual" >&2; exit 1; }
  done
}

assert_artifact_set "$PRIMARY_REPOSITORY"
assert_artifact_set "$SECONDARY_REPOSITORY"
for artifact in "${EXPECTED_FILES[@]}"; do
  cmp -s "$PRIMARY_REPOSITORY/$artifact" "$SECONDARY_REPOSITORY/$artifact" || {
    echo "Bridge artifact is not reproducible across checkout paths: $artifact" >&2
    exit 1
  }
done

echo "Bridge artifacts are byte-for-byte reproducible across distinct checkout paths."
