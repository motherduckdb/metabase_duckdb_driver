#!/usr/bin/env bash
# Build (and optionally push) Metabase+DuckDB images from docker/versions.yml.
#
# Run from the repo root:
#   ./docker/build.sh                    # build all, no push
#   PUSH=true ./docker/build.sh          # build and push to ghcr.io
#
# Requirements:
#   docker, python3, pyyaml (pip3 install pyyaml)
#
# To push, authenticate first:
#   echo $GITHUB_TOKEN | docker login ghcr.io -u <your-github-username> --password-stdin

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

IMAGE_NAME="${IMAGE_NAME:-ghcr.io/motherduckdb/metabase-duckdb}"
PUSH="${PUSH:-false}"
VERSIONS_FILE="${VERSIONS_FILE:-${SCRIPT_DIR}/versions.yml}"

if ! python3 -c "import yaml, certifi" 2>/dev/null; then
    echo "Error: Missing Python dependencies. Install with: pip3 install -r docker/requirements.txt"
    exit 1
fi

# When pushing, build for both amd64 and arm64.
# When loading locally, only the host platform is supported by docker --load.
if [[ "${PUSH}" == "true" ]]; then
    PLATFORM="linux/amd64,linux/arm64"
    BUILD_FLAG="--push"
else
    PLATFORM="linux/$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')"
    BUILD_FLAG="--load"
fi

# Parse all version combinations upfront (bash 3.2-compatible alternative to mapfile)
combos=()
while IFS= read -r line; do
    combos+=("${line}")
done < <(python3 "${SCRIPT_DIR}/parse_versions.py" "${VERSIONS_FILE}")

if [[ ${#combos[@]} -eq 0 ]]; then
    echo "No version combinations found in ${VERSIONS_FILE}."
    exit 0
fi

# --- Plan phase: check registry and show what will happen ---
echo "Checking registry for existing images..."
echo ""

to_build=()
last_tag=""

for combo in "${combos[@]}"; do
    IFS=$'\t' read -r metabase driver <<< "${combo}"
    mb_tag="${metabase#v}"
    tag="${IMAGE_NAME}:${mb_tag}-duckdb${driver}"
    last_tag="${tag}"

    if docker manifest inspect "${tag}" > /dev/null 2>&1; then
        printf "  skip   %s\n" "${tag}"
    else
        printf "  build  %s\n" "${tag}"
        to_build+=("${combo}")
    fi
done

echo ""

if [[ ${#to_build[@]} -eq 0 ]]; then
    echo "Nothing to build — all images already exist in the registry."
    if [[ "${PUSH}" == "true" && -n "${last_tag}" ]]; then
        echo ""
        echo "==> Updating latest → ${last_tag}"
        docker buildx imagetools create -t "${IMAGE_NAME}:latest" "${last_tag}"
    fi
    exit 0
fi

echo "${#to_build[@]} image(s) to build. Platform: ${PLATFORM}. PUSH=${PUSH}."
echo ""

read -rp "Proceed? [y/N] " confirm
[[ "${confirm}" =~ ^[yY]$ ]] || { echo "Aborted."; exit 0; }
echo ""

# --- Build phase ---
built=0

for combo in "${to_build[@]}"; do
    IFS=$'\t' read -r metabase driver <<< "${combo}"
    mb_tag="${metabase#v}"
    tag="${IMAGE_NAME}:${mb_tag}-duckdb${driver}"

    echo "==> Building ${tag}"

    docker buildx build \
        --platform "${PLATFORM}" \
        --build-arg METABASE_VERSION="${mb_tag}" \
        --build-arg METABASE_DUCKDB_DRIVER_VERSION="${driver}" \
        "${BUILD_FLAG}" \
        -t "${tag}" \
        "${REPO_ROOT}"

    (( built++ )) || true
done

# Always re-tag latest to the newest combo so it stays consistent whether
# images were freshly built or already existed (mirrors the CI workflow).
if [[ "${PUSH}" == "true" && -n "${last_tag}" ]]; then
    echo ""
    echo "==> Updating latest → ${last_tag}"
    docker buildx imagetools create -t "${IMAGE_NAME}:latest" "${last_tag}"
fi

echo ""
echo "Done. Built ${built}. (PUSH=${PUSH})"
