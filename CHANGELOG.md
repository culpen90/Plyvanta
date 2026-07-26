# Changelog

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
