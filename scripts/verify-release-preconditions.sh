#!/usr/bin/env bash
#
# Fail before Semantic Release can tag if GitHub or signing controls are absent.

set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

command -v gh >/dev/null 2>&1 ||
    fail "GitHub CLI is required for release preflight checks."

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository=${GITHUB_REPOSITORY:-}
[[ "$repository" =~ ^[^/]+/[^/]+$ ]] ||
    fail "GITHUB_REPOSITORY must identify the production repository."
[[ "${GITHUB_REF:-}" == refs/heads/main ]] ||
    fail "Production releases are restricted to refs/heads/main."
[[ -n "${GH_TOKEN:-}" ]] ||
    fail "GH_TOKEN is required for the release job."
[[ -n "${GITHUB_ENV:-}" ]] ||
    fail "GITHUB_ENV is required to propagate the trusted repository."
repository=$(
    "$script_dir/resolve-trusted-repository.sh" "$repository"
) || fail "Production releases are restricted to the trusted Plyvanta repository ID."
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
    fail "The trusted repository has an invalid canonical full name."
repository_owner=${repository%%/*}
repository_name=${repository#*/}
[[ "$repository_owner" != "." && "$repository_owner" != ".." &&
    "$repository_name" != "." && "$repository_name" != ".." ]] ||
    fail "The trusted repository has invalid canonical components."

for required_secret in \
    PLYVANTA_RELEASE_STORE_BASE64 \
    PLYVANTA_RELEASE_STORE_PASSWORD \
    PLYVANTA_RELEASE_KEY_ALIAS \
    PLYVANTA_RELEASE_KEY_PASSWORD
do
    [[ -n "${!required_secret:-}" ]] ||
        fail "The release environment secret $required_secret is not configured."
done

latest_release_state=$(
    gh api "repos/$repository/releases/latest" \
        --jq '[.draft, .prerelease, .immutable] | @tsv'
) || fail "Could not read the latest stable GitHub Release."
[[ "$latest_release_state" == $'false\tfalse\ttrue' ]] ||
    fail "The latest stable GitHub Release is not published and immutable."

printf 'PLYVANTA_RELEASE_REPOSITORY=%s\n' "$repository" >> "$GITHUB_ENV" ||
    fail "Could not propagate the trusted repository to later release steps."
printf 'Verified release controls for %s on main.\n' "$repository"
