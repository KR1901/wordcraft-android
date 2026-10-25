# WordCraft Android

A modern Android writing assistant and keyboard scaffold built with Jetpack Compose and Material 3. GitHub Actions builds APKs in the cloud, so Android Studio is not required locally.

## Included

- Compose writing studio with Material 3, system light/dark mode, editable draft, character count, clipboard copy, selectable writing actions, and keyboard setup shortcut.
- Native Android keyboard (IME) foundation with letters, basic punctuation, delete, return, space, and a toolbar shell for writing tools.
- AI-provider interface for grammar correction, paraphrasing, professional/friendly tone, simplification, concise rewrites, and expansion.
- GitHub Actions CI that runs tests and uploads a debug APK artifact on pushes to `main`.
- Tagged APK release workflow that builds and attaches an APK to a GitHub Release.
- Unit tests for action metadata and unconfigured-provider behavior.

## Current scope and limitations

This is a starting implementation, not yet a complete commercial keyboard. The IME is a simple keyboard foundation; shift/number layouts, predictions, swipe typing, clipboard history, custom themes, and in-keyboard AI suggestions still need implementation. The writing studio supports editing/copying, but AI actions explain that a provider is not yet configured.

No private AI key is bundled in the source or APK. For production AI, add an authenticated HTTPS backend/proxy that stores provider credentials server-side, applies rate limits, and documents handling/retention for submitted text. Implement a provider adapter in `ai/WritingAssistant.kt` and a settings screen. Never ship a long-lived provider secret in an APK.

The release workflow currently produces an **unsigned** release APK. Configure signing secrets and a protected signing step before distributing a signed production release. Debug APKs are signed automatically for testing.

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

To create a GitHub Release, push a tag such as `v0.1.0`, or manually run **Release APK** in Actions. The release APK is unsigned until signing is configured.

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
