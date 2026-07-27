#!/usr/bin/env bash
#
# Verify a stable release tag on the current commit, including workflow retries.

set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

command -v curl >/dev/null 2>&1 ||
    fail "curl is required to inspect a release."
command -v gh >/dev/null 2>&1 ||
    fail "GitHub CLI is required to verify or recover a release."
command -v jq >/dev/null 2>&1 ||
    fail "jq is required to verify or recover a release."

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/.." && pwd)
expected_commit=${GITHUB_SHA:-$(git -C "$repository_root" rev-parse HEAD)}
repository=${GITHUB_REPOSITORY:-}
[[ "$repository" == culpen90/Plyvanta ]] ||
    fail "Release recovery is restricted to culpen90/Plyvanta."
[[ "${GITHUB_REF:-refs/heads/main}" == refs/heads/main ]] ||
    fail "Release recovery is restricted to refs/heads/main."
[[ -n "${GH_TOKEN:-}" ]] ||
    fail "GH_TOKEN is required to verify or recover a release."
stable_tags=$(
    git -C "$repository_root" tag --points-at "$expected_commit" |
        grep -E '^v[1-9][0-9]*\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' ||
        true
)
tag_count=$(printf '%s\n' "$stable_tags" | awk 'NF {count++} END {print count + 0}')

if [[ "$tag_count" -eq 0 ]]; then
    printf 'No stable release tag points at %s; nothing to verify.\n' \
        "$expected_commit"
    exit 0
fi
[[ "$tag_count" -eq 1 ]] ||
    fail "Expected at most one stable tag on $expected_commit, found: $stable_tags"

release_tag=$stable_tags
release_version=${release_tag#v}
remote_tag_commit=$(
    "$script_dir/github-tag-commit.sh" "$repository" "$release_tag"
)
[[ "$remote_tag_commit" == "$expected_commit" ]] ||
    fail "Remote tag resolves to $remote_tag_commit; expected $expected_commit."

verification_temp=$(
    mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/plyvanta-release-state.XXXXXX"
)
release_json="$verification_temp/release.json"
release_pages_json="$verification_temp/release-pages.json"
cleanup() {
    rm -f "$release_json" "$release_pages_json"
    rmdir "$verification_temp" >/dev/null 2>&1 || true
}
trap cleanup EXIT

release_endpoint="${GITHUB_API_URL:-https://api.github.com}/repos/$repository/releases/tags/$release_tag"
fetch_release() {
    curl --silent --show-error \
        --output "$release_json" \
        --write-out '%{http_code}' \
        --header "Accept: application/vnd.github+json" \
        --header "Authorization: Bearer $GH_TOKEN" \
        --header "X-GitHub-Api-Version: 2026-03-10" \
        "$release_endpoint"
}

release_status=$(fetch_release) ||
    fail "Could not read the GitHub release for $release_tag."

validate_release_identity() {
    local release_identity_valid
    release_identity_valid=$(
        jq -r \
            --arg tag "$release_tag" \
            --arg name "Plyvanta $release_version" \
            --arg target main \
            --arg commit "$expected_commit" \
            '
                .tag_name == $tag
                and .name == $name
                and (
                    .target_commitish == $target
                    or .target_commitish == $commit
                )
                and .prerelease == false
                and (.immutable == true or .immutable == false)
            ' \
            "$release_json"
    )
    [[ "$release_identity_valid" == true ]] ||
        fail "Release identity is unexpected; refusing automatic recovery."
}

find_matching_draft() {
    local match_count
    gh api \
        --paginate \
        "repos/$repository/releases?per_page=100" > "$release_pages_json" ||
        fail "Could not inspect unpublished GitHub releases."
    match_count=$(
        jq -s \
            --arg tag "$release_tag" \
            '[.[][] | select(.tag_name == $tag)] | length' \
            "$release_pages_json"
    )
    [[ "$match_count" =~ ^[0-9]+$ ]] ||
        fail "Could not count unpublished release candidates."
    if [[ "$match_count" -eq 0 ]]; then
        return 1
    fi
    [[ "$match_count" -eq 1 ]] ||
        fail "Multiple releases use $release_tag; refusing automatic recovery."
    jq -s \
        --arg tag "$release_tag" \
        '[.[][] | select(.tag_name == $tag)][0]' \
        "$release_pages_json" > "$release_json"
    validate_release_identity
    jq -e '.draft == true and .immutable == false' \
        "$release_json" >/dev/null ||
        fail "The tag-matched unpublished release is not a recoverable draft."
}

verify_immutable_release() {
    "$script_dir/verify-published-release.sh" "$release_version"
    exit 0
}

case "$release_status" in
    200)
        validate_release_identity
        if [[ "$(jq -r .immutable "$release_json")" == true ]]; then
            verify_immutable_release
        fi
        ;;
    404)
        # The release-by-tag endpoint intentionally omits drafts. List releases
        # with push access so an interrupted asset upload cannot leave a hidden
        # draft that blocks the retry.
        find_matching_draft || true
        ;;
    *)
        fail "GitHub release lookup returned HTTP $release_status; no recovery action was taken."
        ;;
esac

for attempt in 1 2 3 4 5 6 7 8 9 10; do
    printf 'Waiting for release immutability (attempt %s/10)...\n' \
        "$attempt" >&2
    sleep 3
    release_status=$(fetch_release) ||
        fail "Could not refresh the GitHub release for $release_tag."
    if [[ "$release_status" == 200 ]]; then
        validate_release_identity
        if [[ "$(jq -r .immutable "$release_json")" == true ]]; then
            verify_immutable_release
        fi
    elif [[ "$release_status" != 404 ]]; then
        fail "GitHub release refresh returned HTTP $release_status; no recovery action was taken."
    fi
done

# Refresh once more before reporting the failure state. Recovery is
# deliberately read-only: deleting a release has an unavoidable race with
# GitHub making it immutable, and an immutable tag name cannot be reused.
release_status=$(fetch_release) ||
    fail "Could not perform the final release-state check."
case "$release_status" in
    200)
        validate_release_identity
        if [[ "$(jq -r .immutable "$release_json")" == true ]]; then
            verify_immutable_release
        fi
        fail "Release $release_tag is still non-immutable; it was left unchanged for manual recovery."
        ;;
    404)
        if find_matching_draft; then
            fail "Draft release $release_tag is still unpublished; it was left unchanged for manual recovery."
        fi
        fail "Remote tag $release_tag exists without a GitHub Release; it was left unchanged for manual recovery."
        ;;
    *)
        fail "Final GitHub release lookup returned HTTP $release_status; no recovery action was taken."
        ;;
esac
