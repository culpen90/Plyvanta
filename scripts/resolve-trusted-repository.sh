#!/usr/bin/env bash
#
# Resolve Plyvanta's current canonical GitHub name from its immutable repository ID.

set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

if [[ $# -gt 1 ]]; then
    fail "Usage: $0 [EXPECTED_OWNER_AND_REPOSITORY]"
fi

command -v gh >/dev/null 2>&1 ||
    fail "GitHub CLI is required to resolve the trusted repository."
command -v jq >/dev/null 2>&1 ||
    fail "jq is required to resolve the trusted repository."

trusted_repository_id=1313062669
expected_repository=${1:-}
repository_identity=$(gh api "repositories/$trusted_repository_id") ||
    fail "Could not resolve trusted GitHub repository ID $trusted_repository_id."
if ! jq -e \
    --argjson repository_id "$trusted_repository_id" \
    '
        .id == $repository_id
        and (.full_name | type == "string")
        and (.full_name | test("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))
    ' <<< "$repository_identity" >/dev/null
then
    fail "GitHub returned an unexpected trusted repository identity."
fi

canonical_repository=$(jq -r .full_name <<< "$repository_identity")
if [[ -n "$expected_repository" && "$expected_repository" != "$canonical_repository" ]]; then
    fail "Trusted repository ID $trusted_repository_id resolves to $canonical_repository, not $expected_repository."
fi

printf '%s\n' "$canonical_repository"
