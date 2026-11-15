# Hiroi 拾い

A small Android app that picks up the text on your screen, even in apps that don't let you select it, so you can copy it or send it to a dictionary app such as PopLingo or Takoboto.

It reads text through an **accessibility service**, which sees every window, including the status bar, notifications and dialogs. It draws a box over each piece of text. Tap a box to read it, then tap a word to look it up in your own **Yomitan dictionaries**, the same zip files desktop Yomitan uses. Conjugated forms are found too: 食べさせられなかった → 食べる. You can also select text to copy it or send it to another dictionary app.

## Ways to open it

| Trigger | How |
|---|---|
| **Long-press the power button** | Make Hiroi the *digital assistant app*, then set the power button to open the voice assistant. On many phones this is under Settings → Additional settings → Power button. Gesture navigation can also open the assistant by swiping up from a bottom corner. |
| **Accessibility button / shortcut** | Once the service is on, Android shows the accessibility button: a floating button with gesture navigation, a nav-bar icon with buttons. You can also set the shortcut to *hold both volume keys*. |

When the accessibility service is off, the assistant trigger still works. It then uses the view tree the system gives assistant apps, which covers only the foreground app and has less accurate positions.

## Setup on the phone

1. Install the APK.
2. Open Hiroi → *Accessibility settings* → turn on **Hiroi screen text**.
   On Android 13+ a sideloaded app's switch is greyed out at first. Open *App info*, tap ⋮ and choose **Allow restricted settings**, then try again.
3. Optional: *Digital assistant settings* → choose Hiroi, and enable *Use text from screen*.
4. Optional: import a Yomitan dictionary zip under *Dictionaries*.
5. Trigger it on any screen and tap a box. Then:
   - tap a word to look it up in your dictionaries. The matched word gets selected, so **Copy** copies it;
   - or long-press to select text yourself, then pick a dictionary app in the selection menu, or tap **Look up**. The first time, you pick the app and Hiroi remembers it. Long-press **Look up** to change it.

## Dictionaries

Import Yomitan dictionary zips under *Dictionaries* in the app: term dictionaries (Jitendex, JMdict), frequency lists (JPDB), pitch accent (Kanjium) and kanji dictionaries (KANJIDIC). They're stored in SQLite on the phone. Dictionaries imported first are shown first.

A lookup works like Yomitan's: starting at the tapped character, it tries the longest text first, both as written and deinflected. A deinflected form only matches entries whose part-of-speech rules fit (`v1`, `v5*`, `vk`, `vs`, `adj-i`). The deinflection rules are [Yomitan](https://github.com/yomidevs/yomitan)'s own (`japanese-transforms.js`). `scripts/update-yomitan-transforms.mjs` exports them to `app/src/main/resources/.../japanese-transforms.json`, and `LanguageTransformer.kt` is a Kotlin port of Yomitan's engine. The script also records Yomitan's results for sample words, and `YomitanParityTest` checks the port returns exactly the same. One addition: する verb forms also match the noun (勉強した → 勉強), since dictionaries list those verbs under the noun.

To move to a newer Yomitan release:

```bash
node scripts/update-yomitan-transforms.mjs 26.11.2.0
```

Not supported yet: images in definitions, tag descriptions (`tag_bank`), kanji frequencies (`kanji_meta_bank`), and reordering dictionaries.

## Limitations

- Only text the app exposes to accessibility is visible. Canvas-drawn text (games, images, video subtitles) needs OCR, which is not implemented yet.
- As the assistant app, Hiroi replaces Google Assistant / Gemini on the long-press gesture. Its stub speech recogniser always fails, so apps that use the *system default* speech recogniser lose voice input. Keyboards with their own voice input are not affected.

## Build

The build runs in podman, so the Android SDK never touches the host (see `Containerfile.android`). It runs the unit tests and then builds the APK. The first run downloads the SDK; later runs reuse the cached layers.

```bash
./podman-build-apk.sh            # -> build/outputs/hiroi-debug.apk
./podman-build-apk.sh --install  # also adb install -r (needs adb on the host)
```

## License

Copyright (C) 2026 Kevin Thomas

GPL-3.0-or-later, see [LICENSE](LICENSE). The deinflection rules and `LanguageTransformer.kt` come from [Yomitan](https://github.com/yomidevs/yomitan) (GPL-3.0-or-later, Copyright (C) 2024-2026 Yomitan Authors).
