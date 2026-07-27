# Plyvanta 1.0.0 Debug Preview 4

This preview adds trusted update notifications so future compatible Plyvanta
releases are easier to discover and install.

## Install

Download and open **`Plyvanta-1.0.0-debug.4.apk`**.

The APK is intentionally signed with the same Android debug certificate as the
earlier official previews. It has version code 4 and updates an installed
Preview 1, 2, or 3 in place. If Android reports a signing conflict with a
locally built copy, uninstall that copy before installing this preview.

Preview 3 does not contain the new updater, so it cannot announce Preview 4.
Install Preview 4 manually once; future compatible immutable previews can then
notify you when they become available.

## What's new

- Plyvanta periodically checks the official GitHub repository for compatible
  releases while a network connection is available.
- Each newer version produces at most one app-update notification.
- Tapping the notification opens Plyvanta first, shows the validated available
  version, and lets you start the official APK download.
- Android 13 and later asks for notification permission before update alerts can
  appear. Notification status and settings are available inside Plyvanta.
- Release metadata is validated against the package, preview channel, Android
  version code, minimum SDK, immutable GitHub release, asset URL, filename, and
  SHA-256 digest before a download is offered.

Update checks are periodic rather than instant, and Android may delay background
work to preserve battery life.

## Verification

- Version: `1.0.0-debug.4` (`versionCode` 4)
- Application ID: `app.plyvanta.debug`
- Minimum Android version: Android 8.0 / API 26
- Target Android version: API 36
- APK filename: `Plyvanta-1.0.0-debug.4.apk`
- Update metadata: `Plyvanta-1.0.0-debug.4-update.json`
- APK SHA-256:
  `14385f32e876c030019d4cfa1e2d51d40b783c05b8fb774706b06543ddf55ebb`
- Update metadata SHA-256:
  `b8d0367d910bdae60ed7351e62fa38eaad46653152b4dc97ded5b9774e075167`
- Debug certificate SHA-256:
  `f316b684e87b4df6deb4c9fc987e530e7c3fae9810e6a3371b0cc0ea05f179f1`
- APK Signature Scheme v2 verification passed with one signer and the same
  certificate as the earlier official previews.
- Android zip alignment verification passed.
- 49 unit tests passed for each build variant (98 executions total).
- Android lint passed with zero errors.
- Release shrinking and release assembly passed.
- The API 36 notification test passed for cold and already-running activities
  and verified the trusted APK download intent.
- The official Preview 3 APK was checksum-verified and installed first; the
  exact Preview 4 APK then passed an in-place upgrade, installed-version,
  Settings, and bug-report-editor smoke test.

## Limits

Plyvanta is a separate player and does not modify the official YouTube app.
Private, DRM-protected, sign-in-required, and some restricted videos are not
supported. As an unofficial client, it may need updates when YouTube changes
its playback responses.
