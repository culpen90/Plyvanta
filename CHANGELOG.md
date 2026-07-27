# Changelog

Releases after 1.0.0 are versioned from Conventional Commits and documented in
their immutable GitHub Release notes.

## 1.0.0 - 2026-07-26

First stable release of Plyvanta, built from the functionality validated in
Debug Preview 4.

### Included

- Direct content-stream playback for supported YouTube links.
- SponsorBlock skipping with configurable categories and an Undo action.
- Paste, Share, Open-with, fullscreen, quality limits, and stream recovery.
- User-reviewed bug reports with separately opt-in diagnostics and video
  context.
- Trusted update notifications backed by immutable GitHub releases, stable
  metadata, and APK digest validation.

### Distribution

- Uses the production package `app.plyvanta`, version `1.0.0` (`versionCode` 4).
- Is optimized, resource-shrunk, and signed with Plyvanta's dedicated release
  identity.
- Installs separately from debug previews, which use `app.plyvanta.debug`.
- Includes generated stable update metadata and SHA-256 checksums beside the
  APK.

## 1.0.0-debug.4 - 2026-07-26

### Added

- Added periodic, network-constrained checks for compatible releases published
  on the official Plyvanta GitHub repository.
- Added one notification per newer version, with Android 13+ permission handling
  and a dedicated app-update notification channel.
- Added a notification-tap flow that opens Plyvanta first, shows the validated
  available version, and lets the user start the official APK download.
- Added immutable-release metadata and digest validation for package,
  preview/stable channel, Android version code, minimum SDK, APK filename,
  GitHub asset URL, and SHA-256.
- Added generation of the required update metadata beside every packaged debug
  preview APK.

## 1.0.0-debug.3 - 2026-07-26

### Changed

- Moved Help & Support to the top of Settings and made the complete report row
  tappable.
- Added the installed version and version code directly to Settings.
- Added a versioned preview-packaging task so the distributed APK is named
  `Plyvanta-1.0.0-debug.3.apk` instead of the ambiguous `app-debug.apk`.
- Added an installed-APK smoke test for manifest identity, Settings visibility,
  and entry into the report editor.

## 1.0.0-debug.2 - 2026-07-26

### Added

- Added a user-reviewed bug-report flow in Settings and on playback error cards.
- Added separately opt-in technical diagnostics and current-video context, with
  no automatic submission or background collection.
- Added direct GitHub issue handoff plus an Android share-sheet alternative.

## 1.0.0-debug.1 - 2026-07-26

First public preview of Plyvanta.

### Included

- Direct YouTube content-stream playback in a standalone Android player.
- Automatic SponsorBlock skipping with an Undo action.
- Paste, Share, and Open-with link handling.
- Progressive, merged audio/video, HLS, and DASH playback through Media3.
- Configurable sponsor categories and video-quality limits.
- Fullscreen playback and stream-refresh recovery.

### Verification

- 29 unit tests passed with zero failures.
- Android lint, debug assembly, release shrinking, and release assembly passed.
- A real YouTube video resolved and played at 960p on an Android 16/API 36
  emulator.
- A known 12-second SponsorBlock segment was skipped automatically.
- Project and third-party legal materials are embedded under
  `assets/legal/` in the APK.

### Preview signing

The release asset is the installable debug APK requested for this preview. It is
signed with an Android debug certificate, not a production release key. A future
production-signed build may require uninstalling this preview before
installation.

APK SHA-256:

`a89df67a2730fa3e77ebeede3e1c55753ef5cd73ac6b9f9721d377f7e053fc66`

Debug certificate SHA-256:

`f316b684e87b4df6deb4c9fc987e530e7c3fae9810e6a3371b0cc0ea05f179f1`

### Known limitations

- Plyvanta is a separate player and does not alter the official YouTube app.
- It is unofficial and may need extractor updates after YouTube playback
  changes.
- Private, DRM-protected, sign-in-required, and some restricted videos are not
  supported.
