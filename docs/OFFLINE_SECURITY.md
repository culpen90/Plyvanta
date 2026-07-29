# Offline Media Security Contract

Plyvanta's offline-media feature is designed to make copied app files unusable
away from the eligible device and to remove ordinary export and capture paths.
This is a defense-in-depth boundary, not a claim that media shown or played by a
user cannot be copied. Every offline-media change must preserve this contract.

## Project rationale and official upstream policy

Plyvanta is an unofficial client that resolves direct media streams. Downloading
and redistributing media can implicate platform terms and copyright, depending
on the content, authorization, use, jurisdiction, and applicable exceptions.
Personal or noncommercial use alone is not treated as proof of permission. This
contract is a project risk policy, not legal advice, a legal conclusion, or a
claim that technical restrictions make a download lawful.

Official Plyvanta permits offline saving for one narrow purpose: authorized
personal offline entertainment. It is not intended to be a general-purpose
downloader or a convenient source of free copies for other people. The
device-bound vault, lack of export paths, capture defenses, and explicit
acknowledgement are therefore deliberate barriers against redistribution of
media that may be protected by copyright, not incidental implementation
details.

Because Plyvanta is open source, a fork can alter or remove these controls,
subject to the source license and applicable law. That possibility is not a
reason to weaken the official project. Official source, builds, documentation,
and accepted contributions must preserve both the technical barriers and the
plain-language explanation of why they exist. A fork that removes the controls
does not satisfy, and cannot claim the guarantees of, this official upstream
contract.

## Scope and protected assets

This contract covers media downloaded for offline playback, its content-
encryption keys, wrapped-key envelopes, download state, metadata, deletion, and
the playback path that exposes decrypted audio or video.

The protected assets are:

- plaintext media bytes;
- direct, expiring media URLs;
- per-item content-encryption keys and the Android Keystore wrapping key;
- filesystem locations and internal identifiers that could be used to locate
  protected files; and
- authenticated metadata needed to interpret encrypted media.

Ordinary app playback of a public source is not evidence that the user has a
right to retain it. Before starting a download, the app must clearly warn that
the user is responsible for having every necessary right and permission,
explain the project's personal-use and anti-redistribution policy, identify the
copyright and platform-terms risk, acknowledge that forks can remove the
controls while official builds retain them, and require an explicit,
unambiguous acknowledgement. That acknowledgement does not grant or verify any
right.

## Eligibility must fail closed

Offline download, key creation or unwrapping, and offline playback are allowed
only when all of these conditions hold:

- the app is the production release variant and is not debuggable;
- the device runs Android 9 (API 28) or newer;
- Android reports that a secure device lock is configured;
- the device advertises StrongBox and accepts generation of the wrapping key
  with StrongBox required and no TEE or software fallback;
- the generated key's properties match the requested size, purpose, mode,
  padding, authentication, and hardware-security policy;
- no debugger is attached or waiting;
- the OS build is not signed with test or development keys; and
- no checked root or system-modification indicator is present.

On API 31 and newer, wrapping-key verification must require
`KeyProperties.SECURITY_LEVEL_STRONGBOX`. On API 28 through 30, where that exact
security-level accessor is unavailable, the minimum accepted evidence is a
successful StrongBox-only generation request plus
`KeyInfo.isInsideSecureHardware()`. A generation error, unavailable property,
unexpected property, failed probe, or invalidated key makes offline media
unavailable; the implementation must not retry with a weaker key.

The eligibility decision must be rechecked at every operation that creates or
unwraps a key and whenever a download or playback session crosses a lifecycle
boundary. Existing downloads remain unavailable when the gate fails. An error
message may explain which requirement failed, but must not disclose internal
paths, keys, URLs, or detailed integrity probes.

## Storage boundary

All offline files must live under an app-private subdirectory of
`Context.getNoBackupFilesDir()`. Android backup and device-transfer exclusion
rules remain mandatory defense in depth. Offline code must not request broad
storage permissions or place any part of an item in external storage, shared
storage, a cache exported by another component, or another app's directory.
Every activity and task in the app process must use one shared store and active-
session registry; creating independent coordinators for the same vault would
make cleanup, deletion, reset, and reader revocation race each other.

Persistent storage may contain only:

- versioned encrypted track files;
- the authenticated, StrongBox-wrapped per-item key envelope; and
- bounded metadata that is necessary to list and validate an offline item.

Plaintext media, direct media URLs, raw or encoded keys, filesystem paths, and
download request headers must never be written to metadata, preferences,
databases, saved-instance state, work requests, notifications, logs,
diagnostics, crash reports, or bug reports. A direct URL may exist transiently
in memory only while the foreground resolver/downloader is making the
corresponding HTTPS request.

There must be no offline-media integration with:

- `MediaStore` or the Storage Access Framework;
- `FileProvider`, content URI grants, or exported providers;
- Android share, send, save-as, or open-in actions;
- the clipboard;
- casting, remote playback, or media routing; or
- an export, recovery, migration, backup, or device-transfer workflow.

Do not expose raw file descriptors, paths, streams, or decrypted buffers outside
the offline storage and Media3 data-source boundary.

## Encryption format and key handling

Each offline item receives an independent 32-byte content-encryption key from a
cryptographically secure random generator. The raw content key must never be
persisted. It is wrapped with an Android Keystore AES-256-GCM key that is
StrongBox-backed, non-exportable, randomized, and subject to device
authentication. The canonical random item UUID must be authenticated when the
content key is wrapped and unwrapped so envelopes cannot be moved between
items.

Track data must use independently authenticated 256 KiB plaintext chunks with
AES-256-GCM and a 128-bit authentication tag. Every chunk must use a unique
nonce under its content key. The format's version, item and track binding,
chunk index, declared plaintext length, and other fields needed to prevent
substitution, reordering, truncation, extension, or cross-file replay must be
authenticated.

Readers must validate bounded header and chunk lengths before allocating or
seeking. Authentication must complete before a chunk's plaintext is supplied
to Media3. A bad tag, malformed header, invalid length, duplicate or
out-of-order chunk, wrong item binding, or truncated file is a terminal
failure; no partial unauthenticated output or recovery fallback is allowed.

Decryption is in memory only. Keep plaintext to the smallest practical chunk,
do not create plaintext temporary files, and clear content keys and reusable
plaintext buffers as soon as practical. Do not log cryptographic exceptions
with attacker-controlled file contents or secret-bearing parameters.

## Download lifecycle and accepted sources

Downloads are foreground-only operations owned by a visible app lifecycle.
They must not be delegated to a persistent worker, background service, system
download manager, or job that can continue after the user leaves the
foreground flow.

When the activity loses the foreground, the device becomes ineligible, the
user cancels, the source changes, or any network, storage, validation, or
cryptographic step fails, the implementation must:

1. cancel network and encryption work;
2. close every stream and file handle;
3. clear transient key and plaintext buffers as soon as practical; and
4. remove the partial ciphertext, wrapped envelope, and provisional metadata.

Only finite, seekable progressive media is accepted. The implementation may
store one progressive audio/video track or a finite pair of separately
encrypted video and audio tracks that Media3 merges during playback. Live,
upcoming, HLS, DASH, manifest-driven, unbounded, or otherwise non-finite sources
must be rejected before any persistent item is committed. There is no
best-effort fallback to a less protected format.

## Playback and capture controls

Protection must be installed before sensitive UI or a video surface is attached:

- set `WindowManager.LayoutParams.FLAG_SECURE` before `setContentView`;
- on API 31 and newer, request overlay hiding with
  `Window.setHideOverlayWindows(true)` and retain the
  `android.permission.HIDE_OVERLAY_WINDOWS` manifest permission;
- on API 33 and newer, disable the recents screenshot;
- require Media3 to use a `SurfaceView`, call `SurfaceView.setSecure(true)`
  before attachment, and fail rather than substitute an unprotected surface;
- retain `android:allowAudioPlaybackCapture="false"` in the manifest and set
  Media3 audio attributes to `C.ALLOW_CAPTURE_BY_NONE`; and
- where supported, mark the containing view as sensitive for platform
  screen-sharing protections.

Do not add picture-in-picture, casting, external-display, notification playback,
background playback, or another output route for offline items unless this
contract is revised first and the route provides equivalent, verified
protection. A capture-control API failure must disable offline playback for that
session rather than silently continue.

## Deletion and cryptographic erasure

Deleting an item must stop its download and playback, clear in-memory key
material, remove the item's only wrapped content-key envelope and metadata, and
then remove its ciphertext files. With the wrapped per-item key gone, remaining
ciphertext is treated as cryptographically erased even if flash storage delays
physical block reuse.

An erase-all operation must delete the Android Keystore wrapping key before
removing item records and ciphertext. Key invalidation, a missing envelope,
authentication failure, or an orphaned/corrupt file is not recoverable; clean up
the unusable files without creating an export or weaker recovery path.

Deletion cannot revoke plaintext that an attacker already captured from
process memory or an output device. Do not describe filesystem deletion as
physical overwrite on flash storage.

## Prohibited regressions

A change violates this contract if it:

- introduces a plaintext file, plaintext-media cache, resumable plaintext
  partial, persistent direct URL, raw key, or exported path;
- permits a non-StrongBox, non-authenticated, debuggable, rooted-indicator,
  test-key, debugger-attached, or otherwise ineligible fallback;
- permits background downloading or leaves partial artifacts after
  cancellation or failure;
- exposes offline media through a provider, share target, export action,
  storage picker, cast route, or other inter-app interface;
- decrypts more data than playback currently requires or returns bytes before
  their authentication succeeds;
- accepts live, HLS, DASH, or unbounded sources; or
- weakens capture controls, backup exclusions, key deletion, or rights
  acknowledgement; or
- removes or obscures the explanation that official Plyvanta intentionally
  limits offline saving to authorized personal entertainment and retains these
  anti-redistribution barriers even though forks can remove them.

Such a change requires an explicit security review and a documented contract
revision before merge. Compatibility, convenience, and recovery are not
reasons to silently weaken a failed security check.

## Required verification

Changes in this area require focused positive and negative tests. At minimum,
cover:

- every eligibility rejection and precedence when several checks fail;
- StrongBox unavailability, property mismatch, authentication requirement, and
  key invalidation on real supported devices;
- unique per-item keys and unique chunk nonces;
- wrong-key, wrong-item, reordered, duplicated, truncated, extended, malformed,
  and corrupted ciphertext;
- bounded random access across chunk boundaries without a plaintext file;
- cancellation and cleanup at every download stage;
- rejection of live, HLS, DASH, and unbounded inputs;
- deletion of one item and cryptographic erasure of all items;
- the merged-manifest absence of storage/export providers and the retention of
  backup, overlay, and audio-capture restrictions; and
- screenshot, recents, overlay, screen-sharing, audio-capture, lifecycle, and
  secure-surface behavior on the applicable Android API levels; and
- the default pre-download UI and public project documentation continuing to
  state the complete personal-use, copyright/platform-terms,
  anti-redistribution, official-upstream, and fork policy before consent.

Release verification must confirm that the production APK is not debuggable.
Tests demonstrate intended behavior but do not prove that an Android device or
OEM implementation is uncompromised.

## Known limitations and accepted risk

These controls raise the cost of extracting and casually transferring files;
they do not make user-visible public media non-copyable.

- A rooted device, compromised kernel, malicious accessibility or
  instrumentation framework, debugger bypass, hooking tool, or hardware attack
  can potentially inspect process memory, invoke an authorized key, alter
  integrity probes, or bypass UI controls. Local root detection is heuristic;
  absence of a known indicator is not proof of device integrity.
- StrongBox makes key extraction harder, but a compromised OS may still be able
  to ask the hardware to use a key while its authentication policy is
  satisfied.
- Screenshot, overlay, audio-capture, recents, and screen-sharing controls
  depend on Android and OEM enforcement and vary by API level.
- A camera can record the display, a microphone or external device can record
  audio, and sufficiently privileged system or hardware capture remains
  outside the app's control.
- The same public source may be fetched independently with another client.
  Device-bound storage in Plyvanta cannot restrict copies obtained elsewhere.
- StrongBox and the required security posture are unavailable on some otherwise
  supported Android devices. On those devices, offline media remains disabled.
- Device loss, app uninstall, key invalidation, credential changes on affected
  platforms, or storage corruption can make downloads permanently
  unrecoverable. This is an intentional consequence of having no export,
  migration, escrow, or recovery key.
