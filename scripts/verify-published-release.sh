#!/usr/bin/env bash
#
# Verify an immutable GitHub release and its downloaded assets.

set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

if [[ $# -ne 1 ]]; then
    fail "Usage: $0 VERSION"
fi

command -v gh >/dev/null 2>&1 ||
    fail "GitHub CLI is required to verify a published release."
command -v jq >/dev/null 2>&1 ||
    fail "jq is required to verify a published release."

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/.." && pwd)
release_version=$1
release_tag="v$release_version"
# v1.0.0 predates the semantic-release version-code mapping and was published
# with versionCode 4. Every automated release uses the monotonic SemVer mapping.
if [[ "$release_version" == 1.0.0 ]]; then
    version_code=4
else
    version_code=$("$script_dir/semantic-version-code.sh" "$release_version")
fi
stable_certificate_sha256=2085e2b0c5bbd6273203f2aa0064b0f6f291a43746f9989dd0cea30e6cec4d8e
trusted_repository_id=1313062669
repository=${GITHUB_REPOSITORY:-}
if [[ -z "$repository" ]]; then
    repository=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
fi
[[ "$repository" =~ ^[^/]+/[^/]+$ ]] ||
    fail "Could not determine the GitHub repository."
repository=$(
    "$script_dir/resolve-trusted-repository.sh" "$repository"
) || fail "Release verification is restricted to the trusted Plyvanta repository ID."

apk_name="Plyvanta-$release_version.apk"
metadata_name="Plyvanta-$release_version-update.json"
local_asset_directory="$repository_root/app/build/outputs/stable"
local_assets_available=false
versioned_local_asset_count=0
for asset_name in "$apk_name" "$metadata_name"; do
    [[ ! -f "$local_asset_directory/$asset_name" ]] ||
        versioned_local_asset_count=$((versioned_local_asset_count + 1))
done
if [[ "$versioned_local_asset_count" -eq 2 &&
    -f "$local_asset_directory/SHA256SUMS" ]]
then
    local_assets_available=true
elif [[ "$versioned_local_asset_count" -ne 0 ]]; then
    fail "Local release output is incomplete; expected all three assets or none."
fi

verification_temp=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/plyvanta-published.XXXXXX")
release_json="$verification_temp/release.json"
download_directory="$verification_temp/download"
mkdir "$download_directory"
cleanup() {
    rm -f \
        "$release_json" \
        "$download_directory/$apk_name" \
        "$download_directory/$metadata_name" \
        "$download_directory/SHA256SUMS"
    rmdir "$download_directory" >/dev/null 2>&1 || true
    rmdir "$verification_temp" >/dev/null 2>&1 || true
}
trap cleanup EXIT

release_ready=false
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if gh api \
        "repositories/$trusted_repository_id/releases/tags/$release_tag" \
        > "$release_json" &&
        jq -e '.draft == false and .immutable == true' "$release_json" >/dev/null
    then
        release_ready=true
        break
    fi
    printf 'Waiting for immutable release metadata (attempt %s/10)...\n' "$attempt" >&2
    sleep 3
done
[[ "$release_ready" == true ]] ||
    fail "GitHub did not report a published immutable release for $release_tag."

if ! jq -e \
    --arg tag "$release_tag" \
    --arg title "Plyvanta $release_version" \
    --arg apk "$apk_name" \
    --arg metadata "$metadata_name" \
    '
        .tag_name == $tag
        and .name == $title
        and .draft == false
        and .prerelease == false
        and .immutable == true
        and (.body | type == "string" and length > 0)
        and (.assets | length) == 3
        and ([.assets[].name] | sort)
            == ([$apk, $metadata, "SHA256SUMS"] | sort)
        and ([.assets[]
            | select(
                .name == $apk
                and .content_type == "application/vnd.android.package-archive"
                and (.digest | startswith("sha256:"))
            )] | length) == 1
    ' "$release_json" >/dev/null
then
    fail "Published release metadata or asset inventory is invalid."
fi

tag_commit=$(git -C "$repository_root" rev-list -n 1 "$release_tag")
expected_commit=${GITHUB_SHA:-$(git -C "$repository_root" rev-parse HEAD)}
[[ "$tag_commit" == "$expected_commit" ]] ||
    fail "Release tag resolves to $tag_commit; expected $expected_commit."
remote_tag_commit=$(
    "$script_dir/github-tag-commit.sh" "$repository" "$release_tag"
)
[[ "$remote_tag_commit" == "$expected_commit" ]] ||
    fail "Remote release tag resolves to $remote_tag_commit; expected $expected_commit."
latest_tag=$(
    gh api "repositories/$trusted_repository_id/releases/latest" --jq .tag_name
)
[[ "$latest_tag" == "$release_tag" ]] ||
    fail "Latest stable GitHub release is '$latest_tag'; expected '$release_tag'."

attestation_verified=false
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if gh release verify "$release_tag" --repo "$repository"; then
        attestation_verified=true
        break
    fi
    printf 'Waiting for the signed release attestation (attempt %s/10)...\n' \
        "$attempt" >&2
    sleep 3
done
[[ "$attestation_verified" == true ]] ||
    fail "GitHub release attestation verification failed."

gh release download "$release_tag" \
    --repo "$repository" \
    --dir "$download_directory"

"$script_dir/verify-release-assets.sh" \
    "$download_directory" \
    "$release_version" \
    "$version_code" \
    "$stable_certificate_sha256"

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

for asset_name in "$apk_name" "$metadata_name" SHA256SUMS; do
    downloaded_asset="$download_directory/$asset_name"
    if [[ "$local_assets_available" == true ]]; then
        local_asset="$local_asset_directory/$asset_name"
        cmp -s "$downloaded_asset" "$local_asset" ||
            fail "Downloaded asset differs from the packaged file: $asset_name"
    fi
    gh release verify-asset "$release_tag" "$downloaded_asset" \
        --repo "$repository" >/dev/null
    expected_digest="sha256:$(sha256_file "$downloaded_asset")"
    published_digest=$(
        jq -r --arg name "$asset_name" \
            '.assets[] | select(.name == $name) | .digest' \
            "$release_json"
    )
    [[ "$published_digest" == "$expected_digest" ]] ||
        fail "GitHub digest does not match downloaded asset: $asset_name"
done

if [[ "$local_assets_available" == false ]]; then
    printf '%s\n' \
        "No fresh local package was present; verified downloaded assets, digests, and attestation."
fi
printf 'Verified published immutable release: %s\n' "$release_tag"
