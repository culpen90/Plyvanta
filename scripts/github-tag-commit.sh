#!/usr/bin/env bash
#
# Resolve a GitHub tag reference through annotated tags to its commit SHA.

set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

if [[ $# -ne 2 ]]; then
    fail "Usage: $0 OWNER/REPOSITORY TAG"
fi

repository=$1
release_tag=$2
[[ "$repository" =~ ^[^/]+/[^/]+$ ]] ||
    fail "Repository must use the OWNER/REPOSITORY form."
[[ "$release_tag" =~ ^v[1-9][0-9]*\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
    fail "Tag must be a stable vX.Y.Z release tag."
command -v gh >/dev/null 2>&1 ||
    fail "GitHub CLI is required to resolve the remote tag."
command -v jq >/dev/null 2>&1 ||
    fail "jq is required to resolve the remote tag."

reference_json=$(gh api "repos/$repository/git/ref/tags/$release_tag") ||
    fail "Could not read remote tag $release_tag."
[[ "$(jq -r .ref <<< "$reference_json")" == "refs/tags/$release_tag" ]] ||
    fail "GitHub returned the wrong tag reference."
object_type=$(jq -r .object.type <<< "$reference_json")
object_sha=$(jq -r .object.sha <<< "$reference_json")

for depth in 1 2 3 4 5; do
    case "$object_type" in
        commit)
            [[ "$object_sha" =~ ^[0-9a-f]{40}$ ]] ||
                fail "Remote tag resolved to an invalid commit SHA."
            printf '%s\n' "$object_sha"
            exit 0
            ;;
        tag)
            tag_json=$(gh api "repos/$repository/git/tags/$object_sha") ||
                fail "Could not peel annotated tag object $object_sha."
            object_type=$(jq -r .object.type <<< "$tag_json")
            object_sha=$(jq -r .object.sha <<< "$tag_json")
            ;;
        *)
            fail "Remote tag points to unsupported object type '$object_type'."
            ;;
    esac
done

fail "Remote tag contains more than five nested annotated tags."
