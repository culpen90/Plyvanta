# Plyvanta third-party notices

This file records the third-party software included in the Plyvanta Android
runtime. Versions match the resolved `debugRuntimeClasspath` used for the
`v1.0.0-debug.1` prerelease on 2026-07-26.

Complete license texts are provided in `LICENSE` and `licenses/`. The same
files are embedded in the APK under `assets/legal/`.

## GPL-licensed components

### NewPipe Extractor 0.26.4

- Modules: `com.github.TeamNewPipe:NewPipeExtractor:v0.26.4`
- Copyright: NewPipe Extractor contributors
- Project and source:
  https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.4
- License: GNU General Public License, version 3 or later
- License text: `LICENSE`

Plyvanta uses NewPipe Extractor without source modification.

### Android desugared NIO libraries 2.1.5

- Modules: `com.android.tools:desugar_jdk_libs_nio:2.1.5`
- Copyright: Oracle America, Inc., Google LLC, and other OpenJDK contributors
- Project and source: https://github.com/google/desugar_jdk_libs
- License: GNU General Public License, version 2, with the Classpath Exception
- License text:
  `licenses/GPL-2.0-with-Classpath-Exception.txt`
- Additional upstream licensing information:
  `licenses/desugar_jdk_libs-ADDITIONAL_LICENSE_INFO.txt`
- OpenJDK assembly exception:
  `licenses/desugar_jdk_libs-ASSEMBLY_EXCEPTION.txt`

Plyvanta uses the published artifact without source modification.

## Mozilla Public License component

### Mozilla Rhino 1.8.1

- Module: `org.mozilla:rhino:1.8.1`
- Copyright: Mozilla Foundation and Rhino contributors
- Project and source: https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release
- License: Mozilla Public License 2.0
- License text: `licenses/MPL-2.0.txt`

Plyvanta uses the published artifact without source modification. Rhino source
is available at the project link above.

## MIT-licensed component

### jsoup 1.22.2

- Module: `org.jsoup:jsoup:1.22.2`
- Copyright: 2009-2026 Jonathan Hedley
- Project and source: https://github.com/jhy/jsoup/tree/jsoup-1.22.2
- License: MIT
- License text: `licenses/MIT-jsoup.txt`

## BSD-licensed component

### Protocol Buffers Java Lite 4.35.1

- Module: `com.google.protobuf:protobuf-javalite:4.35.1`
- Copyright: 2008 Google Inc.
- Project and source: https://github.com/protocolbuffers/protobuf/tree/v35.1
- License: BSD 3-Clause
- License text: `licenses/BSD-3-Clause-protobuf.txt`

## Apache License 2.0 components

The following resolved runtime components are distributed under the Apache
License 2.0. The complete text is in `licenses/Apache-2.0.txt`.

- AndroidX Activity 1.13.0, Annotation 1.10.0, Arch Core 2.2.0, Collection
  1.4.2, Compose Runtime Annotation 1.9.0, Concurrent Futures 1.1.0, Core
  1.18.0, CustomView 1.0.0, ExifInterface 1.3.6, Interpolator 1.0.0,
  Lifecycle 2.6.2, NavigationEvent 1.0.0, ProfileInstaller 1.4.0,
  RecyclerView 1.3.0, SavedState 1.2.1, Startup 1.2.0, Tracing 1.2.0, and
  VersionedParcelable 1.1.1.
- AndroidX Media3 1.10.1: Common, Container, Database, DataSource, Decoder,
  ExoPlayer, DASH, HLS, Extractor, and UI.
- Kotlin standard library 2.2.21 and JetBrains annotations 23.0.0.
- Kotlin coroutines Android/Core 1.9.0.
- Guava 33.3.1-android, FailureAccess 1.0.2, and ListenableFuture.
- JSpecify annotations 1.0.0.
- TeamNewPipe nanojson commit
  `e9d656ddb49a412a5a0a5d5ef20ca7ef09549996`, derived from nanojson.
  Copyright 2011 The nanojson Authors.
- OkHttp 5.4.0 and Okio 3.17.0. Copyright Square, Inc. and contributors.
- FindBugs JSR-305 annotations 3.0.2.

AndroidX components are copyright The Android Open Source Project and their
respective contributors. Kotlin, Kotlin coroutines, and JetBrains annotations
are copyright JetBrains s.r.o. and their respective contributors. Guava,
FailureAccess, and JSR-305 are copyright their Google and FindBugs project
contributors. JSpecify is copyright the JSpecify authors.

## SponsorBlock data

SponsorBlock segment data is retrieved at runtime and is not bundled in the
APK. It is provided by the SponsorBlock community under Creative Commons
Attribution-NonCommercial-ShareAlike 4.0 International. See `NOTICE.md` for
attribution and the license URL.

## No endorsement

The inclusion of these notices does not imply endorsement of Plyvanta by any
third-party project or copyright holder.
