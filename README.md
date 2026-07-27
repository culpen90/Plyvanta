# Plyvanta

Plyvanta is an unofficial Android video player for watching a YouTube link without
the interruptions normally added around the video. It resolves a direct,
content-only media stream instead of embedding the official YouTube player, then
uses community-maintained SponsorBlock timestamps to skip sponsored sections
inside the video.

Plyvanta does not modify the YouTube app and is not a system-wide ad blocker.
Playback must happen inside Plyvanta.

## Download

Preview builds are available from
[GitHub Releases](https://github.com/culpen90/Plyvanta/releases). The current
prerelease APK is debug-signed for testing and is not a production signing
artifact. A future production-signed build may require uninstalling the preview
before installation.

Plyvanta checks the public GitHub Releases feed about every six hours while a
network is available. When a compatible newer version is published, it posts one
Android notification for that version. Tapping the notification opens Plyvanta's
update prompt; **Download update** then opens the exact official GitHub APK.
Android can delay periodic background work, so this is an update alert rather
than an instantaneous server push. Android 13 and later also require the user to
allow notifications.

## What it does

- Accepts a YouTube URL pasted into the app.
- Opens links shared from another Android app, including YouTube.
- Handles supported YouTube links opened through Android's link chooser.
- Plays progressive, separate audio/video, HLS, or DASH media with AndroidX
  Media3.
- Avoids the ad slots used by the official player by requesting the video's
  direct content stream.
- Fetches crowdsourced SponsorBlock segments and automatically seeks over enabled
  categories.
- Lets you undo a sponsor skip.
- Provides 360p, 720p, 1080p, and 2160p quality limits.
- Provides a user-reviewed bug-report flow with optional app/device diagnostics
  and a separately optional current video link.

Paid sponsor segments are enabled by default. Self-promotion, interaction
reminders, intros, and outros can be enabled from **Settings**.

## Use

1. Install and open Plyvanta.
2. Paste a YouTube video URL and tap **Play**.

Alternatively, choose **Share** in YouTube or another app, then select Plyvanta.
You can also select Plyvanta when Android asks which app should open a supported
YouTube link.

Plyvanta resolves the link, starts the direct media stream, and loads the
available SponsorBlock segments independently. When an enabled segment is
reached, playback seeks to its end and briefly offers **Undo**.

To report a problem, open **Settings → Report a bug**. The support action and
installed app version appear at the top of Settings so the active preview can be
checked immediately. Playback errors also show **Report this issue** next to the
error. Plyvanta previews the complete report before opening a public GitHub
issue or the Android share sheet.

## Requirements

- Android 8.0 (API 26) or later
- JDK 21 for local builds
- Android SDK Platform 36

## Build

On macOS, select JDK 21 and run the checked-in Gradle wrapper:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew clean test lint packageDebugPreview
```

On other operating systems, configure `JAVA_HOME` to a JDK 21 installation and
run the equivalent wrapper command (`gradlew.bat` on Windows). The Android SDK
must contain Platform 36; Android Studio can install it through **SDK Manager**.

The preview packaging task writes a versioned APK so downloads from different
previews cannot be confused. It also writes the release metadata that future
installed versions require before trusting an update:

```text
app/build/outputs/preview/Plyvanta-1.0.0-debug.4.apk
app/build/outputs/preview/Plyvanta-1.0.0-debug.4-update.json
```

Install it on a connected device or emulator with:

```sh
adb install -r app/build/outputs/preview/Plyvanta-1.0.0-debug.4.apk
```

With one emulator or device connected, verify the exact packaged artifact before
distribution:

```sh
scripts/smoke-test-apk.sh \
  app/build/outputs/preview/Plyvanta-1.0.0-debug.4.apk \
  app/build/outputs/preview/Plyvanta-1.0.0-debug.4-update.json \
  f316b684e87b4df6deb4c9fc987e530e7c3fae9810e6a3371b0cc0ea05f179f1
```

The API 36 device test posts the real update notification, follows its content
intent into a cold and already-running activity, and verifies the trusted APK
download intent:

```sh
./gradlew connectedDebugAndroidTest
```

**Immutable releases** are enabled for this repository and must remain enabled.
Publish the APK and its matching `-update.json` file as assets on the same
immutable GitHub prerelease tagged
`v1.0.0-debug.4`. The updater ignores mutable releases and releases without
metadata. It rejects metadata unless its package, preview/stable channel,
monotonic Android `versionCode`, tag, minimum SDK, APK filename, trusted GitHub
URL, and SHA-256 all agree with the immutable published GitHub asset. Future
releases must increment `versionCode` and include both generated files before
the release is published.

The current production task still creates an unsigned `app.plyvanta` APK. A
future stable publishing pipeline must sign its final APK first, generate the
same metadata schema for package `app.plyvanta` and channel `stable`, and
publish both files on an immutable, non-prerelease GitHub release. Until that
signing pipeline exists, stable builds cannot be offered as in-place updates.

For an optimized release build:

```sh
./gradlew assembleRelease
```

Release signing is not configured in this repository. The resulting unsigned APK
is normally written to
`app/build/outputs/apk/release/app-release-unsigned.apk` and must be signed before
distribution.

## Architecture

Plyvanta is a single-activity Java Android app:

- `MainActivity` owns the link/share UI, player lifecycle, fullscreen mode,
  settings, playback recovery, and sponsor-skip feedback.
- `YouTubeUrlParser` accepts only trusted YouTube hosts and supported video URL
  forms, then converts them to a canonical watch URL.
- `NewPipeVideoResolver` uses NewPipe Extractor to obtain public video metadata
  and direct media URLs. It selects a progressive stream when available,
  otherwise separate audio/video, HLS, or DASH.
- `OkHttpDownloader` is the network adapter used by NewPipe Extractor.
- `PlaybackSourceFactory` maps resolved streams to Media3 sources and merges
  separate audio and video when necessary.
- `SponsorBlockClient` hashes the video ID, performs a K-anonymous hash-prefix
  request, validates the matching response locally, and returns normalized skip
  ranges.
- `SponsorSkipController` watches the Media3 playback position and seeks over
  enabled ranges while supporting an undo grace period.
- `PreferenceStore` keeps skip-category and maximum-quality settings in Android
  local preferences.
- `GitHubReleaseClient` reads bounded, anonymous release metadata from the
  official repository and accepts only a compatible APK from an immutable
  release with matching metadata and GitHub SHA-256.
- `UpdateScheduler` creates unique, network-constrained periodic and immediate
  WorkManager requests. `UpdateCheckWorker` serializes checks, deduplicates
  alerts by Android version code, and posts the app-update notification.
- `DiagnosticReport` builds a bounded, text-only report from an explicit
  allowlist and defensively redacts links, credentials, email addresses, and
  local paths from technical values.

There is no Plyvanta server. Stream extraction, response validation, playback,
and skipping all happen on the device.

## Privacy

Plyvanta has no user accounts, sign-in flow, analytics, advertising identifier,
or viewing-history database. It stores the selected skip categories, maximum
video quality, whether the update-notification explanation or permission request
has been shown, the last version that produced an alert, a random private token
used only to authenticate notification navigation, and the validated metadata
for a currently available update in local Android preferences. WorkManager also
keeps its scheduling state in the app's private database. A currently playing
URL and position may be retained temporarily by Android to restore the activity
after a configuration or process-state change; they are not presented as
history. Android cloud backup and device-to-device transfer are disabled for
Plyvanta's app data.

Bug reports are entirely user-initiated. Plyvanta creates the report locally and
shows its full text before handing anything to another app or website. Technical
details are off by default; if selected, they include only the app version and
package, debug/release status, Android version, device model, locale,
orientation, Plyvanta quality and sponsor settings, playback/source state,
SponsorBlock lookup status, retry status, and a structured error type. They do
not include logs, stack traces, throwable messages, accounts, device
identifiers, network details, titles, uploader names, direct media URLs, or
viewing history. The current canonical YouTube link has its own separate option
and is also off by default.

While the report editor or review is open, Android's saved-instance state may
temporarily retain the bounded draft, the two inclusion choices, the allowlisted
technical snapshot, and the exact preview so an interrupted configuration or
process can restore the user's work. That local, system-managed state is used
only for the in-progress report; it is not added to preferences or a report
history.

Nothing is sent until the user chooses an action from the review screen.
Choosing GitHub sends the reviewed text to GitHub to prefill a public issue that
the user can still edit or abandon. Android asks which browser or app should
handle that action. If a report is too long to prefill safely, Plyvanta opens
the Android share sheet instead of putting report contents in an oversized URL.
Choosing Share also hands the report to an app selected in that share sheet.
GitHub, the selected browser, or the selected sharing app receives only what the
user chose to include and handles it under its own privacy practices.

Playing a link makes these network requests:

- **YouTube and its media hosts:** NewPipe Extractor requests public page/player
  metadata, and Media3 requests the selected direct stream. YouTube and its
  delivery providers therefore receive the network address and ordinary request
  metadata needed to serve the video.
- **SponsorBlock:** Plyvanta computes the SHA-256 hash of the video ID and sends
  only the first four hexadecimal characters to SponsorBlock's hash-prefix API.
  This places the lookup in a bucket with other video IDs. The full hash is
  not sent; Plyvanta matches the exact video ID inside the returned bucket before
  using any segment. Enabled category names are also included in the request. No
  SponsorBlock request is made when every skip category is disabled.

Separately, WorkManager periodically makes an anonymous, read-only request to
the public **GitHub Releases API** for `culpen90/Plyvanta`. When a release
contains the required update metadata, Plyvanta downloads that small metadata
file from GitHub and validates it locally. The request uses no GitHub account,
token, device identifier, app usage, video link, or viewing history.

Plyvanta does not proxy these requests, upload an account, or send a local
viewing history to its own service. Network operators and the services contacted
can still observe requests according to their own privacy practices.

## Limitations

- Plyvanta is unofficial and is not affiliated with, endorsed by, or part of
  YouTube, Google, SponsorBlock, or NewPipe.
- It is a separate player. It does not remove ads from the official YouTube app,
  a browser, casting sessions, or other apps.
- YouTube changes its site and playback responses regularly. Extraction can stop
  working until NewPipe Extractor or Plyvanta is updated.
- Private, members-only, paid, DRM-protected, sign-in-required, and
  age-restricted videos are not supported. Region restrictions still apply.
- Some live streams or uncommon media formats may not resolve or play.
- Sponsor skipping depends on community submissions. A video may have no
  segments, incomplete segments, or inaccurate timing.
- Direct media URLs expire. Plyvanta retries a failed stream once, but a link may
  still need to be opened again.
- Update notifications are periodic rather than real-time and can be delayed by
  Android battery, network, app-standby, notification-permission, or force-stop
  behavior.
- Debug previews follow only compatible `app.plyvanta.debug` prereleases.
  A future production-signed `app.plyvanta` release is a separate update channel.
- Availability and use of YouTube content remain subject to applicable law and
  the service's terms.

## License and attribution

Plyvanta is distributed under the GNU General Public License, version 3. See
[LICENSE](LICENSE).

This license is required in particular by the app's use of
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor), which is
GPL-licensed. SponsorBlock segment data is provided by
[SponsorBlock](https://sponsor.ajay.app/) under
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
AndroidX Media3 and OkHttp are used under their respective Apache License 2.0
terms.

See [NOTICE.md](NOTICE.md) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the complete runtime
dependency and data-source notices. The same legal bundle is embedded in every
APK. YouTube and related marks belong to their respective owners.
