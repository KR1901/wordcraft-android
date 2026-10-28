# WordCraft Android

A modern Android writing assistant and keyboard scaffold built with Jetpack Compose and Material 3. GitHub Actions builds APKs in the cloud, so Android Studio is not required locally.

## Included

- Compose writing studio with Material 3, system light/dark mode, editable draft, character count, clipboard copy, selectable writing actions, and keyboard setup shortcut.
- Native Android keyboard (IME) with letters, shift, number and symbol layouts, a small autocorrect strip, delete, return and space, plus a toolbar that hands selected text to the studio.
- On-device Qwen3 0.6B INT4 writing assistant via LiteRT-LM: model download in Settings, then local inference for grammar correction, paraphrasing, tone changes, simplification, concise rewrites, and expansion. Nothing leaves the phone.
- Crash reporting that survives a startup failure: the studio shows why the previous run ended (see below).
- GitHub Actions CI that runs tests and uploads a debug APK artifact on pushes to `main`.
- Tagged APK release workflow that builds and attaches an APK to a GitHub Release.
- Unit tests for action metadata and unconfigured-provider behavior.

## Current scope and limitations

This is a starting implementation, not yet a complete commercial keyboard. The IME is a simple keyboard foundation with shift, number and symbol layouts plus a small autocorrect strip; predictions, swipe typing, clipboard history, custom themes, and in-keyboard AI suggestions still need implementation. Rewriting runs in the writing studio, not inside the keyboard.

## On-device AI

WordCraft uses Google AI Edge LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`, pinned to 0.18.1) to run the Apache-2.0 Qwen3 0.6B INT4 model locally. Open **Settings → Download Qwen3 model** to download the model (approximately 347 MB) once. It is stored in the app-private files directory, excluded from the APK, and then runs without an internet connection. The first inference loads weights and the native runtime, so it takes noticeably longer than later ones; CPU speed and RAM use depend on the phone.

The model is served from the [LiteRT Community Qwen3-0.6B-int4 repository](https://huggingface.co/litert-community/Qwen3-0.6B-int4). Review its model card and license before redistribution. No provider API key is needed, and draft text is not sent to a remote inference API. A future cloud-provider option should use an authenticated HTTPS backend and explicit user consent.

### Two rules for the native runtime

1. **Never touch LiteRT from a startup path.** All LiteRT imports live in `LiteRtWritingAssistant`, which is instantiated only when the user runs a writing action. If a device cannot load the runtime, the studio shows a message and the rest of the app keeps working.
2. **Keep `packaging { jniLibs { useLegacyPackaging = true } }`.** It extracts `.so` files at install time instead of mapping them out of the APK. The first native library loads during the first frame (vector icons), so a loader that cannot map a library from inside the APK turns into an instant crash on open — the failure mode this project hit before.

## Two studios

The default studio (`SimpleStudioActivity`) is built from framework widgets only — no Compose, no
androidx, no theme resources — and does the full job: draft, action picker, on-device rewrite,
copy, model download. The Compose studio (`MainActivity`) is still there and reachable from
Diagnostics, but it is not on the launch path until the Compose startup failure on Android 16 is
resolved. `SmokeActivity` provides four staged Compose tests that isolate which ingredient fails.

## If the app crashes on launch

The launcher is a plain framework-widget screen (`DiagnosticsActivity`) with no Compose, no startup
initializers and no native code. On a healthy launch it forwards straight to the writing studio and
you never see it. If the studio is opened and never comes up, the next launch lands there instead
and shows: the build and device details, our uncaught-exception log, the system's process-exit
record (Android 11+, which covers native crashes and includes the tombstone trace), and a launch
counter that still proves a failed launch when nothing else was recorded. Use **Share this report**
to send it on.

The studio also shows a **Previous session crashed** card on its next healthy start, with a copy
button.

## Versions

Pinned versions from October 2026 release information:
- Android Gradle Plugin 9.4.1
- Gradle 9.8.2
- Kotlin 2.4.21 and Compose compiler plugin
- Compose BOM 2026.09.00
- compileSdk 37, targetSdk 36, minSdk 26
- LiteRT-LM 0.18.1
- JDK 17

The release workflow currently produces a **debug-signed** release APK (`signingConfig = signingConfigs.getByName("debug")`), which is installable for testing but not suitable for store distribution. Configure a real signing key and secrets before publishing a production release.

## Download an APK

1. Open the repository's **Actions** tab.
2. Select the latest **Android CI** run for `main`.
3. Download `wordcraft-debug-apk`.
4. Extract the ZIP and install the APK. Android may ask you to allow installation from that source.

To create a GitHub Release, push a tag such as `v0.1.0`, or manually run **Release APK** in Actions. The release APK is debug-signed until a real signing key is configured.

Because CI signs with a freshly generated debug key on each run, uninstall an older build before installing a newer release APK; Android rejects an update signed with a different key.

## Enable the keyboard

1. Install and open WordCraft.
2. Tap **Enable keyboard**.
3. Enable WordCraft in Android's **On-screen keyboard / Manage keyboards** settings.
4. In a text field, select WordCraft using the system keyboard picker.

## Build locally (optional)

You do not need this for APK downloads—GitHub Actions builds in the cloud.

```bash
./gradlew testDebugUnitTest assembleDebug
```

## Privacy

The keyboard does not log or transmit keystrokes. Draft text is only sent to an AI provider after an explicit user action, and the default provider is a model running on the device. Never log draft content, keys, or credentials. The crash reporter stores a stack trace in app-private storage and never uploads it.
