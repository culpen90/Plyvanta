# Plyvanta 1.0.0 Debug Preview 1

This first public preview is a standalone Android player for YouTube links. It
plays direct content streams and automatically skips enabled SponsorBlock
segments.

## Install

Download `app-debug.apk` below and install it on Android 8.0 or later. Android
may ask you to allow installs from the browser or file manager you use.

This APK is intentionally signed with an Android debug certificate for testing.
It is not production-signed, and a future production build may require
uninstalling this preview first.

## Included

- Direct YouTube content-stream playback through AndroidX Media3.
- Automatic SponsorBlock skipping with an Undo action.
- Paste, Share, and Open-with link handling.
- Progressive, merged audio/video, HLS, and DASH playback.
- Configurable sponsor categories and video-quality limits.
- Fullscreen playback and stream-refresh recovery.

## Verification

- Version: `1.0.0-debug.1`
- Application ID: `app.plyvanta.debug`
- Minimum Android version: Android 8.0 / API 26
- APK SHA-256:
  `a89df67a2730fa3e77ebeede3e1c55753ef5cd73ac6b9f9721d377f7e053fc66`
- Debug certificate SHA-256:
  `f316b684e87b4df6deb4c9fc987e530e7c3fae9810e6a3371b0cc0ea05f179f1`
- APK Signature Scheme v2 verification passed.
- 29 unit tests, Android lint, debug assembly, release shrinking, and release
  assembly passed.
- Project and third-party legal materials are embedded under `assets/legal/`.

The complete Plyvanta source for this build is attached automatically to this
tag by GitHub. Exact upstream source locations for GPL- and MPL-licensed
runtime components are listed in the embedded `THIRD_PARTY_NOTICES.md`.

## Limits

Plyvanta is a separate player and does not modify the official YouTube app.
Private, DRM-protected, sign-in-required, and some restricted videos are not
supported. As an unofficial client, it may need updates when YouTube changes
its playback responses.
