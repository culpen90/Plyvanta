#!/usr/bin/env bash
#
# Build and verify the production assets for a Semantic Release version.

set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

if [[ $# -ne 1 ]]; then
    fail "Usage: $0 VERSION"
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/.." && pwd)
release_version=$1
release_tag="v$release_version"
version_code=$("$script_dir/semantic-version-code.sh" "$release_version")
stable_certificate_sha256=2085e2b0c5bbd6273203f2aa0064b0f6f291a43746f9989dd0cea30e6cec4d8e

if git -C "$repository_root" show-ref --verify --quiet "refs/tags/$release_tag"; then
    fail "Release tag already exists: $release_tag"
fi

temporary_signing_directory=
cleanup() {
    if [[ -n "$temporary_signing_directory" ]]; then
        rm -f "$temporary_signing_directory/plyvanta-release.p12"
        rmdir "$temporary_signing_directory" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

if [[ -n "${PLYVANTA_RELEASE_STORE_BASE64:-}" ]]; then
    [[ -z "${PLYVANTA_RELEASE_STORE_FILE:-}" ]] ||
        fail "Set either PLYVANTA_RELEASE_STORE_BASE64 or PLYVANTA_RELEASE_STORE_FILE, not both."
    for required_variable in \
        PLYVANTA_RELEASE_STORE_PASSWORD \
        PLYVANTA_RELEASE_KEY_ALIAS \
        PLYVANTA_RELEASE_KEY_PASSWORD
    do
        [[ -n "${!required_variable:-}" ]] ||
            fail "$required_variable is required with PLYVANTA_RELEASE_STORE_BASE64."
    done

    umask 077
    temporary_signing_directory=$(
        mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/plyvanta-signing.XXXXXX"
    )
    export PLYVANTA_RELEASE_STORE_FILE=
    PLYVANTA_RELEASE_STORE_FILE+="$temporary_signing_directory/plyvanta-release.p12"
    if base64 --help 2>&1 | grep -q -- '--decode'; then
        printf '%s' "$PLYVANTA_RELEASE_STORE_BASE64" |
            base64 --decode > "$PLYVANTA_RELEASE_STORE_FILE"
    else
        printf '%s' "$PLYVANTA_RELEASE_STORE_BASE64" |
            base64 -D > "$PLYVANTA_RELEASE_STORE_FILE"
    fi
    chmod 600 "$PLYVANTA_RELEASE_STORE_FILE"
    unset PLYVANTA_RELEASE_STORE_BASE64
fi

"$script_dir/package-stable-release.sh" \
    "-PplyvantaVersionName=$release_version" \
    "-PplyvantaVersionCode=$version_code"

"$script_dir/verify-release-assets.sh" \
    "$repository_root/app/build/outputs/stable" \
    "$release_version" \
    "$version_code" \
    "$stable_certificate_sha256"

printf 'Semantic release package is ready: %s (%s)\n' \
    "$release_tag" \
    "$version_code"
