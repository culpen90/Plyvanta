#!/usr/bin/env bash
#
# Verify signed stable release assets without requiring an Android device.

set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

if [[ $# -ne 4 ]]; then
    fail "Usage: $0 ASSET_DIRECTORY VERSION VERSION_CODE CERT_SHA256"
fi

asset_directory=$1
release_version=$2
expected_version_code=$3
expected_certificate_sha256=$4
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/.." && pwd)

[[ -d "$asset_directory" ]] ||
    fail "Asset directory does not exist: $asset_directory"
[[ "$release_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    fail "VERSION must be a stable semantic version."
[[ "$expected_version_code" =~ ^[1-9][0-9]*$ ]] ||
    fail "VERSION_CODE must be a positive integer."
[[ "$expected_certificate_sha256" =~ ^[0-9a-fA-F]{64}$ ]] ||
    fail "CERT_SHA256 must contain exactly 64 hexadecimal characters."

apk_name="Plyvanta-$release_version.apk"
metadata_name="Plyvanta-$release_version-update.json"
apk_path="$asset_directory/$apk_name"
metadata_path="$asset_directory/$metadata_name"
checksum_path="$asset_directory/SHA256SUMS"

for release_file in "$apk_path" "$metadata_path" "$checksum_path"; do
    [[ -s "$release_file" ]] ||
        fail "Required release asset is missing or empty: $release_file"
done

actual_file_names=$(
    find "$asset_directory" -mindepth 1 -maxdepth 1 -type f \
        -exec basename {} \; |
        LC_ALL=C sort
)
expected_file_names=$(
    printf '%s\n' "$apk_name" "$metadata_name" SHA256SUMS |
        LC_ALL=C sort
)
[[ "$actual_file_names" == "$expected_file_names" ]] ||
    fail "Release directory must contain exactly the APK, update metadata, and SHA256SUMS."

command -v jq >/dev/null 2>&1 ||
    fail "jq is required to verify release metadata."
command -v unzip >/dev/null 2>&1 ||
    fail "unzip is required to verify embedded legal files."

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        fail "Neither sha256sum nor shasum is available."
    fi
}

verify_checksums() {
    local expected_checksums actual_checksums checksum_line_count
    expected_checksums=$(
        printf '%s  %s\n' \
            "$apk_sha256" "$apk_name" \
            "$metadata_sha256" "$metadata_name" |
            LC_ALL=C sort
    )
    actual_checksums=$(LC_ALL=C sort "$checksum_path")
    checksum_line_count=$(wc -l < "$checksum_path" | tr -d '[:space:]')
    [[ "$checksum_line_count" == 2 && "$actual_checksums" == "$expected_checksums" ]] ||
        fail "SHA256SUMS must contain exactly the canonical APK and metadata digests."
    (
        cd "$asset_directory"
        if command -v sha256sum >/dev/null 2>&1; then
            sha256sum --check SHA256SUMS
        else
            shasum -a 256 --check SHA256SUMS
        fi
    )
}

find_android_tool() {
    local tool_name=$1
    local sdk_root candidate
    if command -v "$tool_name" >/dev/null 2>&1; then
        command -v "$tool_name"
        return
    fi
    for sdk_root in \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "${HOME:-}/Library/Android/sdk"
    do
        candidate="$sdk_root/build-tools/36.0.0/$tool_name"
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    return 1
}

apk_sha256=$(sha256_file "$apk_path")
metadata_sha256=$(sha256_file "$metadata_path")
verify_checksums
if ! jq -e \
    --arg package_name app.plyvanta \
    --arg version_name "$release_version" \
    --arg apk_name "$apk_name" \
    --arg apk_sha256 "$apk_sha256" \
    --argjson version_code "$expected_version_code" \
    '
        type == "object"
        and (keys | sort) == [
            "apkName",
            "channel",
            "minimumSdk",
            "packageName",
            "schemaVersion",
            "sha256",
            "versionCode",
            "versionName"
        ]
        and .schemaVersion == 1
        and .packageName == $package_name
        and .channel == "stable"
        and .versionCode == $version_code
        and .versionName == $version_name
        and .minimumSdk == 26
        and .apkName == $apk_name
        and .sha256 == $apk_sha256
    ' "$metadata_path" >/dev/null
then
    fail "Update metadata does not match the signed APK and release identity."
fi

aapt_bin=$(find_android_tool aapt) ||
    fail "Android Build Tools 36.0.0 aapt was not found."
apksigner_bin=$(find_android_tool apksigner) ||
    fail "Android Build Tools 36.0.0 apksigner was not found."
zipalign_bin=$(find_android_tool zipalign) ||
    fail "Android Build Tools 36.0.0 zipalign was not found."

badging=$("$aapt_bin" dump badging "$apk_path") ||
    fail "aapt could not inspect the APK."
package_name=$(printf '%s\n' "$badging" |
    sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)
version_name=$(printf '%s\n' "$badging" |
    sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" | head -n 1)
version_code=$(printf '%s\n' "$badging" |
    sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" | head -n 1)
minimum_sdk=$(printf '%s\n' "$badging" |
    sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" | head -n 1)
target_sdk=$(printf '%s\n' "$badging" |
    sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" | head -n 1)

[[ "$package_name" == app.plyvanta ]] ||
    fail "APK package is '$package_name'; expected app.plyvanta."
[[ "$version_name" == "$release_version" ]] ||
    fail "APK versionName is '$version_name'; expected '$release_version'."
[[ "$version_code" == "$expected_version_code" ]] ||
    fail "APK versionCode is '$version_code'; expected '$expected_version_code'."
[[ "$minimum_sdk" == 26 ]] ||
    fail "APK minimum SDK is '$minimum_sdk'; expected 26."
[[ "$target_sdk" == 36 ]] ||
    fail "APK target SDK is '$target_sdk'; expected 36."
if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
    fail "Stable APK must not be debuggable."
fi

"$zipalign_bin" -c -P 16 -v 4 "$apk_path" >/dev/null ||
    fail "Stable APK failed Android zip-alignment verification."

signature_report=$(
    "$apksigner_bin" verify --verbose --print-certs "$apk_path"
) || fail "apksigner rejected the stable APK."
printf '%s\n' "$signature_report" |
    grep -Eq '^Verified using v2 scheme.*: true$' ||
    fail "Stable APK is not verified with APK Signature Scheme v2."
signer_count=$(printf '%s\n' "$signature_report" |
    sed -n 's/^Number of signers: //p' | head -n 1)
certificate_sha256=$(printf '%s\n' "$signature_report" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
    head -n 1 |
    tr '[:upper:]' '[:lower:]')
expected_certificate_sha256=$(printf '%s' "$expected_certificate_sha256" |
    tr '[:upper:]' '[:lower:]')
[[ "$signer_count" == 1 ]] ||
    fail "Expected exactly one APK signer, found '${signer_count:-unknown}'."
[[ "$certificate_sha256" == "$expected_certificate_sha256" ]] ||
    fail "APK signing certificate does not match the permanent release identity."

verification_temp=$(mktemp -d "${TMPDIR:-/tmp}/plyvanta-legal.XXXXXX")
expected_legal_files="$verification_temp/expected.txt"
actual_legal_files="$verification_temp/actual.txt"
extracted_legal_file="$verification_temp/extracted"
cleanup_legal_files() {
    rm -f "$expected_legal_files" "$actual_legal_files" "$extracted_legal_file"
    rmdir "$verification_temp" >/dev/null 2>&1 || true
}
trap cleanup_legal_files EXIT

for legal_source in \
    "$repository_root/LICENSE" \
    "$repository_root/NOTICE.md" \
    "$repository_root/THIRD_PARTY_NOTICES.md" \
    "$repository_root"/licenses/*
do
    printf 'assets/legal/%s\n' "${legal_source##*/}"
done | LC_ALL=C sort > "$expected_legal_files"
duplicate_legal_names=$(uniq -d "$expected_legal_files")
[[ -z "$duplicate_legal_names" ]] ||
    fail "Repository legal files contain duplicate basenames: $duplicate_legal_names"
unzip -Z1 "$apk_path" |
    grep '^assets/legal/[^/][^/]*$' |
    LC_ALL=C sort > "$actual_legal_files"
if ! cmp -s "$expected_legal_files" "$actual_legal_files"; then
    diff -u "$expected_legal_files" "$actual_legal_files" >&2 || true
    fail "APK legal assets do not match the repository legal bundle."
fi

for legal_source in \
    "$repository_root/LICENSE" \
    "$repository_root/NOTICE.md" \
    "$repository_root/THIRD_PARTY_NOTICES.md" \
    "$repository_root"/licenses/*
do
    legal_entry="assets/legal/${legal_source##*/}"
    unzip -p "$apk_path" "$legal_entry" > "$extracted_legal_file" ||
        fail "Could not extract legal asset: $legal_entry"
    cmp -s "$legal_source" "$extracted_legal_file" ||
        fail "Embedded legal asset differs from repository source: $legal_entry"
done

printf 'Verified release assets: %s (%s)\n' \
    "$release_version" \
    "$expected_version_code"
printf 'APK SHA-256: %s\n' "$apk_sha256"
printf 'Signer SHA-256: %s\n' "$certificate_sha256"
