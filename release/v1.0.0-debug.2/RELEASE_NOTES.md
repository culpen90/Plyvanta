# Plyvanta 1.0.0 Debug Preview 2

This second debug preview adds a user-reviewed way to include useful developer
context with bug reports.

## Install

Download `app-debug.apk` below and install it on Android 8.0 or later. Android
may ask you to allow installs from the browser or file manager you use.

This APK is intentionally signed with an Android debug certificate for testing.
It is not production-signed, and a future production build may require
uninstalling this preview first.

## What's new

- Start a bug report from Settings or directly from a playback error.
- Review the complete issue title and report before choosing an action.
- Optionally include bounded app/device diagnostics and, separately, the current
  video link. Both are off by default.
- Open a prefilled GitHub issue or use Android's share sheet. Nothing is
  submitted automatically.

## Verification

- Version: `1.0.0-debug.2` (`versionCode` 2)
- Application ID: `app.plyvanta.debug`
- Minimum Android version: Android 8.0 / API 26
- APK SHA-256:
  `f437fc2e613cd31f129722fa90838e5ba86ed6a6f80226dfb254bc1da8ef760e`
- Debug certificate SHA-256:
  `f316b684e87b4df6deb4c9fc987e530e7c3fae9810e6a3371b0cc0ea05f179f1`
- The certificate matches Debug Preview 1, and APK Signature Scheme v2
  verification passed.
- 35 unit tests passed for both debug and release variants (70 executions).
- Android lint completed with 0 errors; debug assembly, release shrinking, and
  release assembly passed.
- Project and third-party legal materials are embedded under `assets/legal/`.

The complete Plyvanta source for this build is attached automatically to this
tag by GitHub. Exact upstream source locations for GPL- and MPL-licensed runtime
components are listed in the embedded `THIRD_PARTY_NOTICES.md`.

## Limits

Plyvanta is a separate player and does not modify the official YouTube app.
Private, DRM-protected, sign-in-required, and some restricted videos are not
supported. As an unofficial client, it may need updates when YouTube changes
its playback responses.
