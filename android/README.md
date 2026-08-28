# android

The Android app (Kotlin) — does the actual audio recording. See the repo root `README.md` for
what the app is and how it talks to the watch.

## Building & running

```sh
cd android
./gradlew :app:assembleGithubDebug        # dev build
./gradlew :app:assembleGithubRelease      # release, github flavor only
./gradlew :app:assembleRelease            # release, both flavors
```

Release APKs land in `app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`.

## Product flavors (`distribution` dimension)

- **`github`** — bundles `watch/build/watch.pbw` as an asset (synced by the `syncWatchAppAsset`
  Gradle task) and shows the in-app "Install on Pebble" sideload button. Published on GitHub
  Releases.
- **`fdroid`** — ships **without** the bundled `.pbw` (F-Droid's build server has no Pebble SDK).
  This is the flavor F-Droid builds from source; see `packaging/fdroid/`.

## SDK setup

`compileSdk` / `targetSdk` / `buildToolsVersion` are **37** (`android/app/build.gradle.kts`), so
the SDK Gradle uses needs `platforms;android-37.0` and `build-tools;37.0.0` installed.

Gradle finds the SDK via `android/local.properties` (`sdk.dir=...`) first, then the
`ANDROID_HOME` / `ANDROID_SDK_ROOT` environment variables. If your environment points those at a
system-wide SDK that's read-only or has no accepted licenses, the build fails with *"Failed to
install the following Android SDK packages as some licences have not been accepted"* even after
`sdkmanager --licenses` succeeds elsewhere — the licenses and the packages have to live in the
SDK Gradle actually resolves. Fix by creating `android/local.properties` (git-ignored) pointing
at a writable, licensed SDK, e.g.:

```properties
sdk.dir=/home/you/Android/Sdk
```

then install the API 37 packages into *that* SDK:

```sh
"$SDK"/cmdline-tools/latest/bin/sdkmanager --sdk_root="$SDK" "platforms;android-37.0" "build-tools;37.0.0"
```

## Release signing

`assembleRelease` signs only if `android/keystore.properties` (git-ignored) exists; without it the
APKs come out **unsigned** and F-Droid's reproducible-build verification (which pins the signing
cert via `AllowedAPKSigningKeys`) rejects them.

```properties
storeFile=/absolute/or/relative/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` is resolved relative to `android/app/` when not absolute. After a release build,
verify the signature matches the cert F-Droid expects:

```sh
"$SDK"/build-tools/37.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

The `AGP "Dependency metadata"` signing block is deliberately disabled
(`dependenciesInfo { includeInApk = false; includeInBundle = false }`) — F-Droid's binary scanner
rejects any extra signing block.

## Notable build config

- **AGP 9 built-in Kotlin** — `android/build.gradle.kts` pins KGP to 2.4.10 via a `buildscript`
  classpath override (AGP 9's bundled KGP can't read metadata from `pebblekit2`). No
  `kotlin-android` plugin is applied anywhere — AGP 9 forbids combining it with the built-in
  Kotlin DSL.
- **`buildToolsVersion` is pinned** to avoid AGP's default pick triggering a
  download-and-license-accept flow.
