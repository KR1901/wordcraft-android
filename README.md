# WordCraft Android

A modern Android writing assistant and keyboard scaffold built with Jetpack Compose and Material 3. GitHub Actions builds APKs in the cloud, so Android Studio is not required locally.

## Included

- Compose writing studio with Material 3, system light/dark mode, editable draft, character count, clipboard copy, selectable writing actions, and keyboard setup shortcut.
- Native Android keyboard (IME) foundation with letters, basic punctuation, delete, return, space, and a toolbar shell for writing tools.
- On-device Qwen3 0.6B INT4 model download in Settings. Inference through LiteRT-LM is temporarily disabled in this build (see limitations), so writing actions report that clearly instead of pretending to run.
- GitHub Actions CI that runs tests and uploads a debug APK artifact on pushes to `main`.
- Tagged APK release workflow that builds and attaches an APK to a GitHub Release.
- Unit tests for action metadata and unconfigured-provider behavior.

## Current scope and limitations

This is a starting implementation, not yet a complete commercial keyboard. The IME is a simple keyboard foundation with shift, number and symbol layouts plus a small autocorrect strip; predictions, swipe typing, clipboard history, custom themes, and in-keyboard AI suggestions still need implementation. The writing studio and model download work; on-device inference is temporarily disabled while LiteRT native library stability on Android 15/16 is investigated.

## On-device AI

WordCraft uses Google AI Edge LiteRT-LM to run the Apache-2.0 Qwen3 0.6B INT4 model locally. Open **Settings → Download Qwen3 model** to download the model (approximately 347 MB) once. It is stored in the app-private files directory, excluded from the APK, and can then run without an internet connection. The first inference loads model weights and may take several seconds; CPU speed and RAM use depend on the phone. The download requires an internet connection.

The model is served from the [LiteRT Community Qwen3-0.6B-int4 repository](https://huggingface.co/litert-community/Qwen3-0.6B-int4). Review its model card and license before redistribution. No provider API key is needed, and draft text is not sent to a remote inference API. A future cloud-provider option should use an authenticated HTTPS backend and explicit user consent.

The release workflow currently produces a **debug-signed** release APK (`signingConfig = signingConfigs.getByName("debug")`), which is installable for testing but not suitable for store distribution. Configure a real signing key and secrets before publishing a production release.

## Versions

Pinned versions from October 2026 release information:
- Android Gradle Plugin 9.4.1
- Gradle 9.8.2
- Kotlin 2.4.21 and Compose compiler plugin
- Compose BOM 2026.09.00
- compileSdk 37, targetSdk 36, minSdk 26
- JDK 17

## Download an APK

1. Open the repository's **Actions** tab.
2. Select the latest **Android CI** run for `main`.
3. Download `wordcraft-debug-apk`.
4. Extract the ZIP and install the APK. Android may ask you to allow installation from that source.

To create a GitHub Release, push a tag such as `v0.1.0`, or manually run **Release APK** in Actions. The release APK is debug-signed until a real signing key is configured.

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

The keyboard currently does not log or transmit keystrokes. Draft text should only be sent to an AI provider after an explicit user action and clear disclosure. Never log draft content, keys, or credentials.
