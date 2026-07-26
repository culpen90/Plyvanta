# Plyvanta 1.0.0 Debug Preview 3

This preview makes the bug-report entry and the installed build identity
impossible to miss.

## Install

Download and open **`Plyvanta-1.0.0-debug.3.apk`**. Do not select an older
generic `app-debug.apk` left in Downloads.

The APK is intentionally signed with an Android debug certificate for testing.
It has version code 3 and should update either earlier official debug preview.
If Android reports a signing conflict with a locally built copy, uninstall that
copy before installing this preview.

## What's changed

- Help & Support now appears at the top of Settings.
- The whole **Report a bug** row opens the report editor.
- Settings visibly shows **Version 1.0.0-debug.3 (3)**.
- The downloadable APK has a unique, versioned filename.
- Release verification installs the exact packaged APK and checks the Settings
  entry and report editor through Android UI automation.

## Bug-report privacy

Nothing is submitted automatically. Plyvanta shows the complete report before
you choose GitHub or Android's share sheet. Technical diagnostics and the
current video link remain separate options and are both off by default.

## Verification

- Version: `1.0.0-debug.3` (`versionCode` 3)
- Application ID: `app.plyvanta.debug`
- Minimum Android version: Android 8.0 / API 26
- APK filename: `Plyvanta-1.0.0-debug.3.apk`
- APK SHA-256:
  `846f202248f014ea832c30055158f8e3cbc162032af6a96de912667d013c5a61`
- Debug certificate SHA-256:
  `f316b684e87b4df6deb4c9fc987e530e7c3fae9810e6a3371b0cc0ea05f179f1`
- APK Signature Scheme v2 verification passed with the same certificate as the
  earlier official previews.
- 35 unit tests passed for both debug and release variants (70 executions).
- Android lint, debug assembly, release shrinking, and release assembly passed.
- The exact versioned APK was installed on an Android 16/API 36 emulator; the
  smoke test verified its installed version, the top-level Settings entry, and
  entry into the report editor without changing the APK checksum.

## Limits

Plyvanta is a separate player and does not modify the official YouTube app.
Private, DRM-protected, sign-in-required, and some restricted videos are not
supported. As an unofficial client, it may need updates when YouTube changes
its playback responses.
