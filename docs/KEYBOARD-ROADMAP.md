# WordCraft keyboard roadmap

Goal: a keyboard that feels like Gboard (or Heliboard/OpenBoard) rather than a prototype, on a
Material 3 Expressive surface, while keeping WordCraft's on-device writing AI as the differentiator.

Written 2026-10-28. Status legend: **shipped**, *next*, planned.

---

## Phase 1 — shipped in 0.2.0 (this release)

**Foundation rewritten**
- Compose-based IME view (`ComposeView` inside `InputMethodService`) replacing the TextView grid.
  Keys use raw pointer input, so the keyboard never steals editor focus.
- Material 3 Expressive surface: tonal key colours, 14 dp key corners, 22 dp bar corners, spring
  press animation (scale + tonal shift), dynamic colour from the wallpaper on Android 12+ with a
  fixed fallback palette below that.
- Light/dark follows the system, or force light/dark in settings.

**Text editing QoL**
- Tap backspace = one character (code-point aware, so it never splits an emoji).
- **Hold backspace = word-by-word deletion that repeats fast** — holding it clears a long draft in
  well under a second.
- Delete-selection aware: backspace removes a selection instead of one character.
- Cursor control: drag left/right on the space bar to move the caret.
- Shift: tap = one-shot, double-tap or long-press = caps lock, with an indicator on the key.
- Auto-capitalise at the start of a sentence, and auto-unshift after one character.
- Smart punctuation: double space inserts ". ".
- Enter sends the editor's action (search/go/send) when the field defines one, otherwise newline.

**Input surface**
- Letters, numbers, symbols and emoji pages.
- Optional number row above the letters.
- Long-press accents (à á â ä…, ç, ñ, ß, ÿ) and symbol alternates, offered in the suggestion strip
  rather than a popup so the finger never has to leave the key.
- Emoji page with recently-used emojis first.

**Text intelligence (learning-based, no bundled dictionary yet)**
- Suggestion strip: next-word prediction, prefix completion, capitalised variant, typo correction.
- Learns the words you type and the bigrams between them, stored privately on the device.
- Autocorrect on space, toggleable.
- Corrections never change meaning silently: a candidate is only auto-applied when it is a
  one-edit typo of a word you have already used.

**Feedback**
- Key-press sound (respects the system's touch-sounds setting) and haptic feedback, both toggleable.

**Extras**
- Toolbar: WordCraft mark, Grammar, Rewrite, paste-from-clipboard, settings.
- Keyboard settings screen: sound, vibration, number row, smart punctuation, auto-capitalise,
  autocorrect, suggestions, theme.

---

## Phase 2 — *next* (the gaps that still feel "basic")

- **Key preview popup** (the magnified character bubble on press) with the accent row attached.
- **Gesture typing / glide**: path decoder over the learned word list first, then a bundled
  frequency dictionary. This is the single biggest missing feature and the one that needs a real
  dictionary, so it comes with the item below.
- **Bundled dictionary** (~50k English words with frequencies, compressed asset) for real
  autocorrect, proper prefix ranking and next-word prediction. Needed before glide typing is any
  good.
- **Gesture delete**: swipe left on backspace to delete whole words.
- **Clipboard manager**: history, pinning, and a strip above the keyboard.
- **Personal dictionary** UI: add words, exclusions, per-language lists.
- **Multi-language**: several enabled languages at once with per-language dictionaries and a
  language key.
- **Undo**: a small "undo" chip after an autocorrect or a delete burst.

## Phase 3 — planned (parity with Gboard's long tail)

- Floating and split keyboards; one-handed mode; adjustable height.
- Themes beyond dynamic colour: solid, photo-backed, per-app accent.
- Sticker/GIF search (needs a provider and an API key decision — likely out of scope for a
  privacy-first app).
- Voice typing via the system speech recogniser.
- Text editing panel: select all, copy, cut, paste, undo/redo, cursor keys.
- Emoji search, skin-tone picker, emoji recents per language.
- Number/date row long-press variants; currency and maths evaluation in the strip.
- Accessibility pass: TalkBack labels on every key, large-key mode, high-contrast theme.
- Battery/perf: baseline profile for the IME process, cold-start budget under 150 ms.

## Phase 4 — AI surface (WordCraft's differentiator)

- Inline rewrite chip on a selected sentence, not just the studio.
- Grammar underline in the suggestion strip for the current sentence.
- Tone presets that persist per app (email vs chat).
- Everything stays on-device through LiteRT-LM; no text leaves the phone without an explicit tap.

---

## Engineering rules that must not regress

1. Never read `Settings.Secure` keys that are restricted for `targetSdk >= 34`
   (`ENABLED_INPUT_METHODS` was the startup crash). Use `InputMethodManager` instead.
2. Never touch LiteRT from a startup path; it loads only when the user runs a rewrite.
3. Keep `packaging { jniLibs { useLegacyPackaging = true } }`.
4. The keyboard must never take focus from the editor: use pointer input, not `clickable`.
5. Any new IME surface must be reachable on a device where Compose fails, via the widget studio.

## How each phase is verified

- `./gradlew testDebugUnitTest` for the word engine and correction rules.
- The emulator smoke test for launch.
- A device checklist per release (the keyboard cannot be fully tested without a real IME session):
  typing, deleting, holding backspace, shift/caps, accents, emoji, cursor drag, suggestions,
  autocorrect, clipboard paste, enter action, dark mode.
