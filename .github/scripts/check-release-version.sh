#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
cd "$REPO_ROOT"

SEMVER_PATTERN='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
TAG_PATTERN='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

fail() {
    printf 'release version check failed: %s\n' "$*" >&2
    exit 1
}

trim() {
    local value=$1
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

read_property() {
    local key=$1
    local file=$2
    local matches=()
    mapfile -t matches < <(grep -E "^[[:space:]]*${key}[[:space:]]*=" "$file" || true)
    [[ ${#matches[@]} -eq 1 ]] || fail "$file must define $key exactly once"
    trim "${matches[0]#*=}"
}

read_property_from_ref() {
    local key=$1
    local ref=$2
    local contents
    local matches=()
    contents=$(git show "${ref}:gradle.properties") || fail "$ref does not contain gradle.properties"
    mapfile -t matches < <(printf '%s\n' "$contents" | grep -E "^[[:space:]]*${key}[[:space:]]*=" || true)
    [[ ${#matches[@]} -eq 1 ]] || fail "$ref:gradle.properties must define $key exactly once"
    trim "${matches[0]#*=}"
}

VERSION_NAME=$(read_property VERSION_NAME gradle.properties)
VERSION_CODE=$(read_property VERSION_CODE gradle.properties)

[[ $VERSION_NAME =~ $SEMVER_PATTERN ]] || fail "VERSION_NAME must be stable SemVer without leading zeroes: $VERSION_NAME"
[[ $VERSION_CODE =~ ^[1-9][0-9]*$ ]] || fail "VERSION_CODE must be a positive integer: $VERSION_CODE"
(( VERSION_CODE <= 2100000000 )) || fail "VERSION_CODE exceeds Android's 2100000000 limit"

RELEASE_TAG="v${VERSION_NAME}"
REQUESTED_TAG=${1:-}
if [[ -n $REQUESTED_TAG ]]; then
    [[ $REQUESTED_TAG =~ $TAG_PATTERN ]] || fail "tag must match vMAJOR.MINOR.PATCH: $REQUESTED_TAG"
    [[ $REQUESTED_TAG == "$RELEASE_TAG" ]] || fail "tag $REQUESTED_TAG does not match VERSION_NAME=$VERSION_NAME"
fi

mapfile -t STABLE_TAGS < <(git tag --list 'v*' | grep -E "$TAG_PATTERN" | sort -V || true)
TAG_EXISTS=false
if git rev-parse --verify --quiet "refs/tags/${RELEASE_TAG}^{commit}" >/dev/null; then
    TAG_EXISTS=true
fi

if [[ -n $REQUESTED_TAG && $TAG_EXISTS != true ]]; then
    fail "tag $RELEASE_TAG does not exist in the checkout"
fi

PREVIOUS_TAG=
if [[ $TAG_EXISTS == true ]]; then
    TAG_COMMIT=$(git rev-parse "refs/tags/${RELEASE_TAG}^{commit}")
    HEAD_COMMIT=$(git rev-parse HEAD)
    [[ $TAG_COMMIT == "$HEAD_COMMIT" ]] || fail "tag $RELEASE_TAG does not resolve to the checked-out commit"
    if [[ -n $REQUESTED_TAG ]]; then
        git rev-parse --verify --quiet 'refs/remotes/origin/main^{commit}' >/dev/null ||
            fail "origin/main is missing; fetch the default branch before validating a release tag"
        git merge-base --is-ancestor "$TAG_COMMIT" refs/remotes/origin/main ||
            fail "tag $RELEASE_TAG is not reachable from origin/main"
    fi

    [[ ${#STABLE_TAGS[@]} -gt 0 ]] || fail "existing tag $RELEASE_TAG was not recognized as stable SemVer"
    LATEST_TAG=${STABLE_TAGS[$((${#STABLE_TAGS[@]} - 1))]}
    [[ $LATEST_TAG == "$RELEASE_TAG" ]] || fail "$RELEASE_TAG is not the highest stable release tag; latest is $LATEST_TAG"

    for candidate in "${STABLE_TAGS[@]}"; do
        [[ $candidate == "$RELEASE_TAG" ]] && continue
        PREVIOUS_TAG=$candidate
    done
elif [[ ${#STABLE_TAGS[@]} -gt 0 ]]; then
    LATEST_TAG=${STABLE_TAGS[$((${#STABLE_TAGS[@]} - 1))]}
    HIGHEST_TAG=$(printf '%s\n%s\n' "$LATEST_TAG" "$RELEASE_TAG" | sort -V | tail -n 1)
    [[ $HIGHEST_TAG == "$RELEASE_TAG" && $LATEST_TAG != "$RELEASE_TAG" ]] ||
        fail "VERSION_NAME=$VERSION_NAME must be newer than latest stable tag $LATEST_TAG"
    PREVIOUS_TAG=$LATEST_TAG
fi

if [[ -n $PREVIOUS_TAG ]]; then
    PREVIOUS_VERSION_CODE=$(read_property_from_ref VERSION_CODE "$PREVIOUS_TAG")
    [[ $PREVIOUS_VERSION_CODE =~ ^[1-9][0-9]*$ ]] ||
        fail "$PREVIOUS_TAG has invalid VERSION_CODE=$PREVIOUS_VERSION_CODE"
    (( VERSION_CODE > PREVIOUS_VERSION_CODE )) ||
        fail "VERSION_CODE=$VERSION_CODE must exceed $PREVIOUS_TAG VERSION_CODE=$PREVIOUS_VERSION_CODE"
fi

if [[ -n ${GITHUB_OUTPUT:-} ]]; then
    {
        printf 'version_name=%s\n' "$VERSION_NAME"
        printf 'version_code=%s\n' "$VERSION_CODE"
        printf 'release_tag=%s\n' "$RELEASE_TAG"
    } >> "$GITHUB_OUTPUT"
fi

printf 'Release version check passed: %s (versionCode %s)\n' "$RELEASE_TAG" "$VERSION_CODE"
