# WordCraft Android

A modern Android writing assistant and keyboard scaffold built with Jetpack Compose and Material 3. GitHub Actions builds APKs in the cloud, so Android Studio is not required locally.

## Included

- Compose writing studio with Material 3, system light/dark mode, editable draft, character count, clipboard copy, selectable writing actions, and keyboard setup shortcut.
- Native Android keyboard (IME) with letters, shift, number and symbol layouts, a learning autocorrect strip, hold-to-clear delete, swipe-to-delete, key previews, clipboard history and inline AI rewrites.
- On-device writing assistant via LiteRT-LM. Choose Qwen3.5 0.8B or 2B in Settings, download once, then run grammar correction, paraphrasing, thirteen tones and length targets locally. Nothing leaves the phone.
- Crash reporting that survives a startup failure: the studio shows why the previous run ended (see below).
- GitHub Actions CI that runs tests and uploads a debug APK artifact on pushes to `main`.
- Tagged APK release workflow that builds and attaches an APK to a GitHub Release.
- Unit tests for action metadata and unconfigured-provider behavior.

## Current scope and limitations

This is a starting implementation, not yet a complete commercial keyboard. The IME is a simple keyboard foundation with shift, number and symbol layouts plus a small autocorrect strip; predictions, swipe typing, clipboard history, custom themes, and in-keyboard AI suggestions still need implementation. Rewriting runs in the writing studio, not inside the keyboard.

## On-device AI

WordCraft uses Google AI Edge LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`, pinned to 0.18.1) to run Apache-2.0 Qwen3.5 models locally. Open **Settings → Models**, download Qwen3.5 0.8B (965 MB) or 2B (2.0 GB), and the download becomes the active model. Files live in the app-private files directory, never in the APK, and run without a connection afterwards. The engine is cached after the first use; CPU speed and RAM use depend on the phone, and switching models closes the previous engine.

Models are served from the LiteRT Community on Hugging Face: [Qwen3.5-0.8B](https://huggingface.co/litert-community/Qwen3.5-0.8B) and [Qwen3.5-2B](https://huggingface.co/litert-community/Qwen3.5-2B). Every catalogue URL is checked to answer a plain unauthenticated request, so a gated repository can never ship as an option (Gemma's are gated and were removed for exactly that reason). No provider API key is needed and draft text is not sent to a remote inference API.

### How the AI is driven

Prompting here is measured, not guessed. Against the real model:

- **Grammar is an edit list, not a rewrite.** The model returns `[{"find": ..., "replace": ...}]` and the app applies those spans to your text. Content loss is structurally impossible; an unmatched span is skipped. `GrammarEdits` is unit-tested.
- **Tone work**: instruction in the system message, the user turn is only the text. Instructions after the text, or metadata like sentence counts, get echoed into the answer.
- Sampling is explicit (temperature 0.3 proofreading / 0.7 restyling, top-p 0.95, top-k 40), thinking is disabled, and repetition penalties are on.
- Guards reject echoes, one-word fragments, repeated paragraphs and summaries dressed as "same length" (`RewriteQuality`, unit-tested).

### Two rules for the native runtime

1. **Never touch LiteRT from a startup path.** All LiteRT imports live in `LiteRtWritingAssistant`, which is instantiated only when the user runs a writing action. If a device cannot load the runtime, the studio shows a message and the rest of the app keeps working.
2. **Keep `packaging { jniLibs { useLegacyPackaging = true } }`.** It extracts `.so` files at install time instead of mapping them out of the APK. The first native library loads during the first frame (vector icons), so a loader that cannot map a library from inside the APK turns into an instant crash on open — the failure mode this project hit before.

## The startup crash on Android 14+ (root cause)

Every build up to 0.1.17 died on launch on Android 14+ with:

```
java.lang.SecurityException: Settings key: <enabled_input_methods> is only readable
  to apps with targetSdkVersion lower than or equal to: 33
    at com.wordcraft.android.ui.WordCraftAppKt$WordCraftApp$1$1.invokeSuspend(WordCraftApp.kt:106)
```

The studio read `Settings.Secure.ENABLED_INPUT_METHODS` to label its keyboard button. Android
denies that key to apps targeting SDK 34+, so the read threw inside a `LaunchedEffect` on the first
frame — after `onResume`, before any pixels, which is why it looked like an instant crash with no
window. It never reproduced on the API 30 CI emulator because the restriction does not exist
there. `InputMethodManager.getEnabledInputMethodList()` reports the same thing publicly and is now
used instead. Do not reintroduce the `Settings.Secure` read.

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
