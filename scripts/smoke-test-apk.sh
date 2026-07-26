#!/usr/bin/env bash
#
# Install and smoke-test the exact APK that will be distributed.
#
# Usage:
#   scripts/smoke-test-apk.sh APK VERSION_NAME VERSION_CODE PACKAGE SHA256 [ADB_SERIAL]
#
# When ADB_SERIAL is omitted, exactly one authorized device must be connected.
# The expected release identity is mandatory and checked before installation.

set -euo pipefail

usage() {
    printf '%s\n' \
        "Usage: $0 APK VERSION_NAME VERSION_CODE PACKAGE SHA256 [ADB_SERIAL]" \
        "" \
        "Installs APK with adb install -r; it never uninstalls the app or clears app data."
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

if [[ $# -lt 5 || $# -gt 6 ]]; then
    usage >&2
    exit 2
fi

apk_path=$1
expected_version_name=$2
expected_version_code=$3
expected_package_name=$4
expected_sha=$5
requested_serial=${6:-}

[[ -f "$apk_path" ]] || fail "APK does not exist: $apk_path"
[[ "$expected_version_code" =~ ^[0-9]+$ ]] ||
    fail "Expected versionCode must be numeric: $expected_version_code"
[[ "$expected_sha" =~ ^[0-9a-fA-F]{64}$ ]] ||
    fail "Expected SHA-256 must contain exactly 64 hexadecimal characters."
expected_sha=$(printf '%s' "$expected_sha" | tr '[:upper:]' '[:lower:]')

find_adb() {
    local candidate
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return
    fi
    for candidate in \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "${ANDROID_HOME:-}/platform-tools/adb" \
        "${HOME:-}/Library/Android/sdk/platform-tools/adb"
    do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    return 1
}

find_aapt() {
    local sdk_root candidate newest
    if command -v aapt >/dev/null 2>&1; then
        command -v aapt
        return
    fi
    for sdk_root in \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "${HOME:-}/Library/Android/sdk"
    do
        [[ -d "$sdk_root/build-tools" ]] || continue
        newest=
        for candidate in "$sdk_root"/build-tools/*/aapt; do
            if [[ -x "$candidate" ]]; then
                newest=$candidate
            fi
        done
        if [[ -n "$newest" ]]; then
            printf '%s\n' "$newest"
            return
        fi
    done
    return 1
}

sha256_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        fail "Neither shasum nor sha256sum is available."
    fi
}

adb_bin=$(find_adb) || fail "adb was not found. Install Android SDK Platform-Tools."
aapt_bin=$(find_aapt) || fail "aapt was not found. Install Android SDK Build-Tools."

badging=$("$aapt_bin" dump badging "$apk_path") ||
    fail "aapt could not inspect the APK: $apk_path"
package_name=$(printf '%s\n' "$badging" |
    sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)
version_name=$(printf '%s\n' "$badging" |
    sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" | head -n 1)
version_code=$(printf '%s\n' "$badging" |
    sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" | head -n 1)
launch_activity=$(printf '%s\n' "$badging" |
    sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" | head -n 1)

[[ -n "$package_name" ]] || fail "APK manifest has no package name."
[[ -n "$version_name" ]] || fail "APK manifest has no versionName."
[[ -n "$version_code" ]] || fail "APK manifest has no versionCode."
[[ -n "$launch_activity" ]] || fail "APK manifest has no launchable activity."

if [[ "$package_name" != "$expected_package_name" ]]; then
    fail "Expected package '$expected_package_name', APK contains '$package_name'."
fi
if [[ "$version_name" != "$expected_version_name" ]]; then
    fail "Expected versionName '$expected_version_name', APK contains '$version_name'."
fi
if [[ "$version_code" != "$expected_version_code" ]]; then
    fail "Expected versionCode '$expected_version_code', APK contains '$version_code'."
fi

device_list=$("$adb_bin" devices)
if [[ -n "$requested_serial" ]]; then
    device_state=$(printf '%s\n' "$device_list" |
        awk -v serial="$requested_serial" '$1 == serial {print $2; exit}')
    [[ "$device_state" == "device" ]] ||
        fail "Device '$requested_serial' is not connected and authorized (state: ${device_state:-missing})."
    serial=$requested_serial
else
    connected_devices=$(printf '%s\n' "$device_list" |
        awk 'NR > 1 && $2 == "device" {print $1}')
    device_count=$(printf '%s\n' "$connected_devices" |
        awk 'NF {count++} END {print count + 0}')
    if [[ "$device_count" -eq 0 ]]; then
        fail "No connected and authorized adb device was found."
    fi
    if [[ "$device_count" -gt 1 ]]; then
        printf 'Connected devices:\n%s\n' "$connected_devices" >&2
        fail "More than one adb device is connected; pass ADB_SERIAL explicitly."
    fi
    serial=$connected_devices
fi

initial_sha=$(sha256_file "$apk_path")
[[ "$initial_sha" == "$expected_sha" ]] ||
    fail "Expected SHA-256 '$expected_sha', APK contains '$initial_sha'."
printf 'APK: %s\n' "$apk_path"
printf 'SHA-256: %s\n' "$initial_sha"
printf 'Manifest: %s %s (%s)\n' "$package_name" "$version_name" "$version_code"
printf 'Device: %s\n' "$serial"

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/plyvanta-apk-smoke.XXXXXX")
host_xml="$temp_dir/window.xml"
normalized_xml="$temp_dir/window.normalized.xml"
device_xml="/sdcard/plyvanta-apk-smoke-$$.xml"

cleanup() {
    "$adb_bin" -s "$serial" shell rm -f "$device_xml" >/dev/null 2>&1 || true
    rm -f "$host_xml" "$normalized_xml"
    rmdir "$temp_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT

assert_apk_unchanged() {
    local stage current_sha
    stage=$1
    current_sha=$(sha256_file "$apk_path")
    [[ "$current_sha" == "$initial_sha" ]] ||
        fail "APK SHA-256 changed $stage: expected $initial_sha, found $current_sha."
}

printf 'Installing exact APK with adb install -r...\n'
if ! install_output=$("$adb_bin" -s "$serial" install -r "$apk_path" 2>&1); then
    printf '%s\n' "$install_output" >&2
    fail "adb install -r failed for the exact APK: $apk_path"
fi
printf '%s\n' "$install_output"
assert_apk_unchanged "during installation"

installed_dump=$("$adb_bin" -s "$serial" shell dumpsys package "$package_name")
installed_version_name=$(printf '%s\n' "$installed_dump" |
    sed -n 's/^[[:space:]]*versionName=//p' | head -n 1 | tr -d '\r')
installed_version_code=$(printf '%s\n' "$installed_dump" |
    sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)

[[ "$installed_version_name" == "$version_name" ]] ||
    fail "Installed versionName is '$installed_version_name'; expected '$version_name'."
[[ "$installed_version_code" == "$version_code" ]] ||
    fail "Installed versionCode is '$installed_version_code'; expected '$version_code'."

dump_ui() {
    "$adb_bin" -s "$serial" shell uiautomator dump "$device_xml" >/dev/null 2>&1 &&
        "$adb_bin" -s "$serial" pull "$device_xml" "$host_xml" >/dev/null 2>&1 &&
        awk '{gsub(/></, ">\n<"); print}' "$host_xml" > "$normalized_xml"
}

last_node=
wait_for_node() {
    local needle attempts node
    needle=$1
    attempts=${2:-20}
    while [[ "$attempts" -gt 0 ]]; do
        if dump_ui; then
            if grep -F "text=\"System UI isn't responding\"" \
                "$normalized_xml" >/dev/null; then
                show_visible_text_on_failure
                fail "Device System UI is unresponsive; reboot or replace the test device."
            fi
            node=$(grep -F "$needle" "$normalized_xml" | head -n 1 || true)
            if [[ -n "$node" ]]; then
                last_node=$node
                return 0
            fi
        fi
        attempts=$((attempts - 1))
        sleep 1
    done
    return 1
}

show_visible_text_on_failure() {
    if [[ -f "$normalized_xml" ]]; then
        printf 'Visible UI text at failure:\n' >&2
        sed -n 's/.*text="\([^"]\{1,\}\)".*/  \1/p' "$normalized_xml" |
            sed -n '1,40p' >&2
    fi
}

tap_node() {
    local node coordinates left top right bottom center_x center_y
    node=$1
    coordinates=$(printf '%s\n' "$node" |
        sed -n 's/.*bounds="\[\([0-9][0-9]*\),\([0-9][0-9]*\)\]\[\([0-9][0-9]*\),\([0-9][0-9]*\)\]".*/\1 \2 \3 \4/p')
    [[ -n "$coordinates" ]] || fail "Could not read UI bounds from: $node"
    read -r left top right bottom <<< "$coordinates"
    center_x=$(((left + right) / 2))
    center_y=$(((top + bottom) / 2))
    "$adb_bin" -s "$serial" shell input tap "$center_x" "$center_y"
}

component="$package_name/$launch_activity"
printf 'Launching %s...\n' "$component"
"$adb_bin" -s "$serial" shell am force-stop "$package_name"
launch_output=$("$adb_bin" -s "$serial" shell am start -W -n "$component")
printf '%s\n' "$launch_output"
printf '%s\n' "$launch_output" | grep -F "Status: ok" >/dev/null ||
    fail "Android did not report a successful activity launch."

if ! wait_for_node 'content-desc="Settings"' 30; then
    show_visible_text_on_failure
    fail "Settings button did not appear."
fi
settings_node=$last_node
tap_node "$settings_node"

visible_version="Version $version_name ($version_code)"
if ! wait_for_node "text=\"$visible_version\"" 20; then
    show_visible_text_on_failure
    fail "Settings does not show the exact installed version '$visible_version'."
fi
if ! wait_for_node 'text="Report a bug"' 5; then
    show_visible_text_on_failure
    fail "Settings does not show 'Report a bug'."
fi
report_row_text_node=$last_node
if ! wait_for_node 'text="Start"' 5; then
    show_visible_text_on_failure
    fail "Settings does not show the bug-report Start button."
fi
tap_node "$report_row_text_node"

editor_intro='Tell us what went wrong and what you expected to happen.'
if ! wait_for_node "text=\"$editor_intro\"" 20; then
    show_visible_text_on_failure
    fail "Bug-report editor did not open; expected intro text was not visible."
fi
if ! wait_for_node 'text="What happened? Include steps that help reproduce it."' 5; then
    show_visible_text_on_failure
    fail "Bug-report description editor was not visible."
fi

assert_apk_unchanged "during the UI smoke test"
printf 'PASS: exact APK version, Settings entry, and bug-report editor are verified.\n'
