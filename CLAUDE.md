# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

PebbleRecorder is two separate apps that talk to each other:

- `watch/` — a PebbleOS watchapp (C) that acts as a **remote start/stop trigger**. It has no audio
  logic of its own — it just sends a command and displays whatever status comes back.
- `android/` — a native Android app (Kotlin) that will do the actual audio recording and write
  files to a user-chosen folder. Currently it only stubs the recording (see Status below).

The watch never touches the microphone. Pebble's SDK has never exposed raw mic/PCM access to
third-party apps — the only public mic API is the cloud-based Dictation API (speech → text, not
audio). Recording audio would require patching the (now open-source) PebbleOS firmware itself, on
mic-equipped hardware only (Time/Steel/Round, Pebble 2, Core Time 2). Given that, all recording
logic lives on the phone; the watch is just a button.

## Communication model (read this before touching either side)

The two apps do **not** talk directly over Bluetooth. The chain is:

```
watch app  <--AppMessage-->  Pebble/Core companion app  <--AIDL bound service-->  android/ app
           (real BLE link)                              (io.rebble.pebblekit2:client)
```

The Android app depends on `io.rebble.pebblekit2:client` (PebbleKitAndroid2, published to Maven
Central by pebble-dev). It binds to whatever Pebble/Core companion app is installed on the phone
(e.g. `coredevices/mobileapp` or `microPebble`) via a bound Service — it never manages Bluetooth
itself. This means:

- A real end-to-end test needs the official Pebble/Core companion app installed and a watch
  paired to it, in addition to this repo's two apps. Neither `pebble install --emulator` nor a
  bare Android emulator can exercise the full loop by itself.
- `android/app/src/main/java/com/pebblerecorder/app/PebbleListenerService.kt` only receives
  messages while the watchapp is open on the watch (the companion app binds/unbinds the service
  around that). `onAppOpened`/`onAppClosed` mark those transitions.

### Protocol

Two AppMessage keys, defined in `watch/package.json` under `pebble.messageKeys` and mirrored in
`android/app/src/main/java/com/pebblerecorder/app/WatchProtocol.kt`:

- `COMMAND` (watch → phone): `0` = stop, `1` = start
- `STATUS` (phone → watch): `0` = idle, `1` = recording, `2` = error

**Pebble auto-assigns the numeric key IDs from the `messageKeys` array order, starting at 10000**
(not 0/1 as you'd guess) — e.g. `COMMAND=10000`, `STATUS=10001` currently. If you reorder or add
entries to `messageKeys` in `watch/package.json`, rebuild the watch app and check
`watch/build/js/message_keys.json` for the real values, then update `WatchProtocol.kt` to match.

Also: `watch/package.json`'s `pebble.companionApp.android.apps[].package` must list the Android
app's `applicationId` (`com.pebblerecorder.app`), or the companion app will reject messages with
`TransmissionResult.FailedNoPermissions`.

## Status

Verified end-to-end on real hardware (Pixel phone + Pebble Time 2 + the `coredevices.coreapp`
companion app, over wireless adb): watch button press → `COMMAND` over AppMessage → companion app
→ `PebbleListenerService` → real `MediaRecorder` capture into the SAF folder → `STATUS` reply back
to the watch. Produces valid AAC/M4A files (confirmed with `ffprobe`).

- `watch/`: full Idle/Starting/Recording/Stopping/Error state machine wired to AppMessage,
  verified both in the QEMU emulator and against a real watch.
- `android/`: `PebbleListenerService` records AAC/M4A audio via `MediaRecorder` into a file
  created (via `DocumentFile`) in the SAF folder the user picks in `MainActivity`, replying
  `STATUS_RECORDING`/`STATUS_IDLE`/`STATUS_ERROR` accordingly. `MainActivity` requests
  `RECORD_AUDIO` (+ `POST_NOTIFICATIONS` on API 33+) on launch, exposes the
  `ACTION_OPEN_DOCUMENT_TREE` folder picker (persisted via `RecordingFolderPrefs`), and arms
  `PebbleListenerService` as a persistent foreground service.

  That last part matters: Android forbids starting a *new* microphone-type foreground service
  while the app has no visible UI, which is exactly the state `PebbleListenerService` is in when a
  watch `COMMAND` arrives via the AIDL bind (confirmed live - it throws
  `ForegroundServiceStartNotAllowedException`). Adding the microphone type to an *already*
  foregrounded service is allowed, though, so `MainActivity` arms `PebbleListenerService` at launch
  with a permanent low-priority `specialUse`-type foreground service ("PebbleRecorder is armed for
  the watch trigger" notification), and `COMMAND_START`/`COMMAND_STOP` just add/drop the
  `microphone` type on that already-running instance. If `MainActivity` has never been opened since
  the process started, recording will fail with `STATUS_ERROR`.

### Known gotchas hit while getting this working

- **`pebble build` can silently produce a stale `.pbw`.** An incremental build once skipped
  regenerating `appinfo.json`, shipping a `.pbw` missing the `companionApp` block - the companion
  app then silently drops inbound `AppMessage`s for the watchapp with no error on either side (logs
  "Got inbound message" and nothing else). If watch→phone messages seem to vanish, `rm -rf
  watch/build` and rebuild clean before assuming it's a code bug.
- **`dict_write_int`'s width argument must match the actual variable size.** On
  `arm-none-eabi-gcc` (ARM EABI), plain C enums default to the *smallest* type that fits their
  values (e.g. 1 byte for `{STOP=0, START=1}`), not `int`. Passing `sizeof(int32_t)` as the width
  while pointing at a narrower enum variable reads adjacent stack garbage into the outgoing
  dictionary. Always copy into an explicit `int32_t` local first (see `prv_send_command` in
  `watch/src/c/watch.c`).

## Commands

### Watch app (`watch/`)

```sh
cd watch
pebble build                              # builds all targetPlatforms
pebble install --emulator basalt          # install + launch in QEMU
pebble logs --emulator basalt             # stream APP_LOG output
pebble screenshot --emulator basalt out.png
pebble emu-button --emulator basalt click select   # simulate a button press
pebble send-app-message --emulator basalt --int 10001=1   # simulate a phone reply (STATUS=RECORDING)
```

`pebble-tool` (v5.x, SDK 4.33.1) is already installed and configured on this machine — no setup
needed. Message key IDs for `send-app-message`/`--int` come from
`watch/build/js/message_keys.json` after a build, not from the names in `package.json`.

### Android app (`android/`)

```sh
cd android
./gradlew :app:assembleDebug
```

Two environment-specific things are already handled in the build files, worth knowing about if
they start failing after a dependency bump:

- AGP 9's built-in Kotlin support bundles Kotlin Gradle Plugin 2.2.10 by default, which can't
  read metadata from libraries built with newer Kotlin (like `pebblekit2`). `android/build.gradle.kts`
  pins KGP to 2.4.10 via a `buildscript` classpath override — there is no `kotlin-android` plugin
  applied anywhere (AGP 9 forbids combining it with the built-in Kotlin DSL).
- `buildToolsVersion` is pinned to `37.0.0` in `android/app/build.gradle.kts` to match what's
  actually installed at `/opt/android-sdk` (only `android-37.0`/`37.0.0` are installed; AGP's
  default build-tools pick for `compileSdk = 37` triggers a download-and-license-accept flow that
  fails in this sandboxed environment).

No Android emulator or AVD is set up in this environment, and no device is attached (`adb devices`
is empty) — Android-side changes can only be verified by compiling, not by running.

There are no automated tests on either side yet.
