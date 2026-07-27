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

repository=${GITHUB_REPOSITORY:-}
[[ "$repository" == culpen90/Plyvanta ]] ||
    fail "Production releases are restricted to culpen90/Plyvanta."
[[ "${GITHUB_REF:-}" == refs/heads/main ]] ||
    fail "Production releases are restricted to refs/heads/main."
[[ -n "${GH_TOKEN:-}" ]] ||
    fail "GH_TOKEN is required for the release job."

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

printf 'Verified release controls for %s on main.\n' "$repository"
