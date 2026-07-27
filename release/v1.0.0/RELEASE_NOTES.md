# Plyvanta 1.0.0

Plyvanta 1.0.0 is the first production-signed stable release. It promotes the
functionality validated in Debug Preview 4 into the stable `app.plyvanta`
application and establishes the signing identity used for future stable
updates.

## Install

Download and open **`Plyvanta-1.0.0.apk`**.

The stable app and Debug Preview 4 have different Android package names, so
they can be installed together. Stable does not replace the preview in place or
copy its private settings. After confirming the stable app works as expected,
the debug preview can be removed.

## Included

- Direct content-stream playback for supported YouTube links.
- Automatic SponsorBlock skipping with configurable categories and an Undo
  action.
- Paste, Share, and Open-with link handling.
- Progressive, merged audio/video, HLS, and DASH playback through Media3.
- 360p, 720p, 1080p, and 2160p quality limits.
- Fullscreen playback and stream-refresh recovery.
- User-reviewed bug reports with separately opt-in technical diagnostics and
  current-video context.
- Periodic update notifications that validate immutable GitHub release
  metadata, package and channel identity, version code, minimum Android
  version, official asset URL, filename, and APK SHA-256 before offering a
  download.

## Verification

- Version: `1.0.0` (`versionCode` 4)
- Application ID: `app.plyvanta`
- Build type: optimized, resource-shrunk, non-debuggable release
- Minimum Android version: Android 8.0 / API 26
- Target Android version: API 36
- APK filename: `Plyvanta-1.0.0.apk`
- Update metadata: `Plyvanta-1.0.0-update.json`
- APK SHA-256:
  `38493e828a18a2db6ea33c673c5d7112f52f2ceee97119062dca29532d2264eb`
- Update metadata SHA-256:
  `db089221582e6187e3eb26560be72bfff160241519401cbdd4782dc41a2db240`
- Release certificate SHA-256:
  `2085e2b0c5bbd6273203f2aa0064b0f6f291a43746f9989dd0cea30e6cec4d8e`
- APK Signature Scheme v2 verification passed with one 4096-bit RSA signer.
- Android zip alignment verification passed.
- 50 unit tests passed for each build variant (100 executions total).
- Android lint passed with zero errors.
- Release shrinking and signed release assembly passed.
- The exact packaged APK passed manifest, checksum, signature, installation,
  installed-version, Settings, and bug-report-editor checks on Android 16 /
  API 36.

## Limits

Plyvanta is a separate player and does not modify the official YouTube app.
Private, DRM-protected, sign-in-required, and some restricted videos are not
supported. As an unofficial client, it may need updates when YouTube changes
its playback responses.
