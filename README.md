# PebbleRecorder

Use your Pebble watch as a remote start/stop button for audio recording on your
phone. Press a button on the watch and a companion Android app records straight
to a folder you choose — no need to touch your phone.

## How it works

Two apps:

- **`watch/`** — a Pebble watchapp (C). An Idle → Recording → Paused state
  machine with a status icon and a live timer. It has no microphone logic; it
  just sends start/stop/pause and shows whatever status comes back.
- **`android/`** — a native Android app (Kotlin) that does the recording
  (`MediaRecorder`, AAC/M4A), with optional Gemini transcription to a sibling
  `-txt.md` file.

They don't talk directly. Messages go over Pebble's AppMessage protocol, relayed
by the Pebble/Core companion app:

```
watch app  ◀─AppMessage─▶  Pebble/Core companion app  ◀─AIDL─▶  android app
```

Pebble's SDK has never exposed raw microphone access to watchapps, so all
recording lives on the phone and the watch is just a button.

## Requirements

- A Pebble watch with a phone running a PebbleKit-2 companion app (e.g.
  [Core Devices' app](https://github.com/coredevices/mobileapp)).
- Android 8.0 (API 26) or newer.

## Build

### Watch (`watch/`) — needs the [Pebble SDK](https://developer.repebble.com)

```sh
cd watch
pebble build
pebble install --emulator basalt      # or: --phone <phone-ip>
```

### Android (`android/`)

```sh
cd android
./gradlew :app:assembleGithubDebug
```

Install the APK and grant microphone access. Recordings default to
`Downloads/PebbleRecorder`; pick another folder in the app if you like. It then
runs quietly in the background, armed for the watch trigger.

A real end-to-end test needs both apps **plus** the companion app and a paired
watch — no emulator can exercise the full loop alone. `CLAUDE.md` has the
protocol details, the foreground-service model, and the release checklist.

## Downloads & changelog

`.apk` / `.pbw` artifacts and full release notes:
[Releases](https://github.com/BigWebstas/PebbleRecorder/releases). Also on F-Droid
(reproducible `fdroid` build flavor).
