#!/usr/bin/env bash
#
# Build, test, sign, and package the stable Plyvanta release.
#
# Signing can be provided with all four PLYVANTA_RELEASE_* environment
# variables. On macOS, the first-party release setup defaults to the protected
# keystore in Application Support and its password in the login Keychain.

set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/.." && pwd)

signing_variable_names=(
    PLYVANTA_RELEASE_STORE_FILE
    PLYVANTA_RELEASE_STORE_PASSWORD
    PLYVANTA_RELEASE_KEY_ALIAS
    PLYVANTA_RELEASE_KEY_PASSWORD
)
configured_signing_variable_count=0
for variable_name in "${signing_variable_names[@]}"; do
    if [[ -n "${!variable_name:-}" ]]; then
        configured_signing_variable_count=$((configured_signing_variable_count + 1))
    fi
done

if [[ "$configured_signing_variable_count" -eq 0 ]]; then
    [[ "$(uname -s)" == "Darwin" ]] ||
        fail "Set all four PLYVANTA_RELEASE_* signing environment variables."
    command -v security >/dev/null 2>&1 ||
        fail "macOS Keychain command 'security' was not found."

    export PLYVANTA_RELEASE_STORE_FILE="$HOME/Library/Application Support/Plyvanta/signing/plyvanta-release.p12"
    [[ -f "$PLYVANTA_RELEASE_STORE_FILE" ]] ||
        fail "Stable signing keystore was not found: $PLYVANTA_RELEASE_STORE_FILE"
    export PLYVANTA_RELEASE_STORE_PASSWORD
    PLYVANTA_RELEASE_STORE_PASSWORD=$(
        security find-generic-password \
            -a plyvanta-release \
            -s app.plyvanta.release-signing \
            -w
    ) || fail "Stable signing password was not found in macOS Keychain."
    export PLYVANTA_RELEASE_KEY_ALIAS=plyvanta-release
    export PLYVANTA_RELEASE_KEY_PASSWORD="$PLYVANTA_RELEASE_STORE_PASSWORD"
elif [[ "$configured_signing_variable_count" -ne "${#signing_variable_names[@]}" ]]; then
    fail "Set all four PLYVANTA_RELEASE_* signing variables or none of them."
fi

java_home_is_21() {
    local candidate=$1
    [[ -x "$candidate/bin/java" ]] || return 1
    "$candidate/bin/java" -version 2>&1 |
        sed -n '1p' |
        grep -Eq 'version "21([."]|$)'
}

if ! java_home_is_21 "${JAVA_HOME:-}"; then
    for java_home_candidate in \
        /opt/homebrew/opt/openjdk@21 \
        /usr/local/opt/openjdk@21
    do
        if java_home_is_21 "$java_home_candidate"; then
            export JAVA_HOME=$java_home_candidate
            break
        fi
    done
fi
java_home_is_21 "${JAVA_HOME:-}" ||
    fail "JDK 21 was not found. Set JAVA_HOME to a JDK 21 installation."

cd "$repository_root"

"$repository_root/gradlew" \
    --no-configuration-cache \
    --no-daemon \
    clean \
    test \
    lint \
    packageStableRelease \
    "$@"

printf '%s\n' \
    "Stable release package:" \
    "$repository_root/app/build/outputs/stable"
