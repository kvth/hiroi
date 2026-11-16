# Hiroi 拾い

A small Android app that picks up the text on your screen, even in apps that don't let you select it, so you can copy it or send it to a dictionary app such as PopLingo or Takoboto.

It reads text through an **accessibility service**, which sees every window, including the status bar, notifications and dialogs. It draws a box over each piece of text that contains Japanese. Tap a word in a box to look it up in your own **Yomitan dictionaries**, the same zip files desktop Yomitan uses. If the app doesn't report where its characters are (some WebViews, games, custom views), the tap opens the box's text in a panel, and you tap the word there. Conjugated forms are found too: 食べさせられなかった → 食べる. You can also select text to copy it or send it to another dictionary app.

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

## Example

The bottom of the app's setup screen has Japanese example sentences to try Hiroi on, and a button that imports a small example dictionary with every word in them (`app/src/main/assets/example-dictionary.zip`). Its definitions are entries from [Jitendex](https://jitendex.org) (CC BY-SA 4.0, by Stephen Kraus, based on JMdict by the Electronic Dictionaries Research Group and Tatoeba example sentences). `scripts/make-example-dictionary.py <jitendex-yomitan.zip>` rebuilds it.

## Dictionaries

Import Yomitan dictionary zips under *Dictionaries* in the app, or open a downloaded zip with Hiroi (*Open with* / *Share*): term dictionaries (Jitendex, JMdict), frequency lists (JPDB), pitch accent (Kanjium) and kanji dictionaries (KANJIDIC). They're stored in SQLite on the phone. Dictionaries imported first are shown first.

Results look like Yomitan's popup:
- the headword with furigana, then tags for the inflection, Anki status, frequencies and pitch accent,
- the definitions, numbered and labelled with their dictionary, styled by the dictionary's own `styles.css` as in Yomitan (Jitendex's coloured labels and example-sentence boxes, for instance).

The looked-up word is also marked in the box on screen, where the app reports its character positions. Dictionaries imported before styles were supported need importing again to get theirs.

A lookup works like Yomitan's: starting at the tapped character, it tries the longest text first, both as written and deinflected. A deinflected form only matches entries whose part-of-speech rules fit (`v1`, `v5*`, `vk`, `vs`, `adj-i`). The deinflection rules are [Yomitan](https://github.com/yomidevs/yomitan)'s own (`japanese-transforms.js`). `scripts/update-yomitan-transforms.mjs` exports them to `app/src/main/resources/.../japanese-transforms.json`, and `LanguageTransformer.kt` is a Kotlin port of Yomitan's engine. The script also records Yomitan's results for sample words, and `YomitanParityTest` checks the port returns exactly the same. One addition: する verb forms also match the noun (勉強した → 勉強), since dictionaries list those verbs under the noun.

To move to a newer Yomitan release:

```bash
node scripts/update-yomitan-transforms.mjs 26.11.2.0
```

Not supported yet: images in definitions, tag descriptions (`tag_bank`), kanji frequencies (`kanji_meta_bank`), and reordering dictionaries.

## OCR

Apps don't expose text inside images. So as soon as the overlay opens, Hiroi reads the screenshot it took on trigger with Google ML Kit's on-device Japanese recognizer, in the background. Japanese lines it finds in images are added as dashed boxes when it's done, usually within a second, and the other boxes can be used meanwhile. If you tap an image before then, the word under your tap is looked up once its line arrives. Everything runs on the phone; the model is bundled, so it needs neither a network connection nor Play Services.

- Screenshots need the accessibility service. With only the assistant trigger, Hiroi uses the screenshot Android hands to assistants, if *Use screenshot* is on in the assistant settings.
- Apps that forbid screenshots (streaming, banking) give a black image, so there's nothing to read.
- ML Kit reads horizontal text well; vertical text (manga) is hit and miss.
- The first scan after starting the app takes a moment while the model loads.

**Licensing:** ML Kit is closed source (Google APIs Terms of Service), while Hiroi is GPL-3.0 and contains Yomitan's GPL code. Publishing this source code is fine, and so is building the app for yourself. Distributing built APKs, which bundle ML Kit, would conflict with the GPL. A distributable build would need an open-source OCR engine (PaddleOCR, Tesseract), or ML Kit in a separate companion app.

## Anki

Under *Anki* in the app, pick a deck and the field that holds the word. Lookups then show **✓ in Anki** or **not in Anki** for each headword. A field matches if its text is the word, ignoring HTML and furigana such as `使う[つかう]`.

Hiroi talks to [AnkiConnect](https://foosoft.net/projects/anki-connect/):
- **On the phone:** AnkiDroid plus [AnkiConnect Android](https://github.com/KamWithK/AnkiconnectAndroid), with its service started. It listens on `http://127.0.0.1:8765`, Hiroi's default.
- **Desktop Anki** with the AnkiConnect add-on: enter its address, e.g. `http://192.168.0.20:8765`. On that machine, set `"webBindAddress": "0.0.0.0"` in AnkiConnect's config. Also set an `apiKey` and enter it in Hiroi, because anyone on the network can then reach your Anki.

### Making cards

With a card format set up under *Anki → Cards*, every result in the overlay gets a **＋ Anki** button that adds a note, the way Yomitan's button does. A card format works like Yomitan's: a deck, a note type, and a template per field using Yomitan's markers. **Import from Yomitan settings…** takes a format from a Yomitan settings export (*Yomitan → Settings → Backup → Export settings*); with several profiles, you pick one. Otherwise, choose a note type and Hiroi suggests templates from the field names.

Markers produce the same output as Yomitan's default templates. That includes `{glossary}`, `{furigana}` and `{frequencies}`, so note types made for Yomitan (Lapis, JP Mining Note, …) show them as they would Yomitan's.

| | Markers |
|---|---|
| Word | `{expression}` `{reading}` `{furigana}` `{furigana-plain}` `{conjugation}` `{part-of-speech}` `{tags}` |
| Definitions | `{glossary}` and its `-brief`, `-no-dictionary`, `-first`, `-plain` variants, `{dictionary}` |
| Sentence | `{sentence}` `{sentence-furigana}` `{sentence-furigana-plain}` `{cloze-prefix}` `{cloze-body}` `{cloze-body-kana}` `{cloze-suffix}` |
| Frequency, pitch | `{frequencies}` `{frequency-harmonic-rank}` `{frequency-average-rank}` `{pitch-accents}` `{pitch-accent-positions}` `{pitch-accent-categories}` |
| Media | `{audio}` (word audio from JapanesePod101, Yomitan's default source) and `{screenshot}` (the screen when the overlay opened) |
| Other | `{document-title}` (the app the text came from) |

Not supported, left empty and listed under *Cards*: custom markers from Yomitan's handlebars templates (e.g. JP Mining Note's `{jpmn-*}`), the pitch graphs, `{phonetic-transcriptions}`, and markers that don't apply on a phone (clipboard, URL). Sentence furigana comes from Hiroi's own lookup, word by word, so it's only as good as your dictionaries.

Hiroi uses only actions AnkiConnect Android supports: `deckNames`, `modelNames`, `modelFieldNames`, `findNotes`, `notesInfo`, `canAddNotes`, `addNote` and `storeMediaFile`.

## Limitations

- Only text the app exposes to accessibility is visible. Canvas-drawn text (games, images, video subtitles) needs OCR, which is not implemented yet.
- As the assistant app, Hiroi replaces Google Assistant / Gemini on the long-press gesture. Its stub speech recogniser always fails, so apps that use the *system default* speech recogniser lose voice input. Keyboards with their own voice input are not affected.

## Build

The build runs in podman, so the Android SDK never touches the host (see `Containerfile.android`). It runs the unit tests and then builds the APK. The first run downloads the SDK; later runs reuse the cached layers.

```bash
./podman-build-apk.sh            # -> build/outputs/hiroi-debug.apk
./podman-build-apk.sh --install  # also adb install -r (needs adb on the host)
```

## Emulator

`scripts/emulator.sh` runs Hiroi in an Android 15 emulator inside podman (needs `/dev/kvm`), set up like a phone in use: Japanese system language, the accessibility service on, Hiroi as the digital assistant, and a dictionary imported. The default dictionary is the app's example dictionary; you can pass your own zip instead.

```bash
scripts/emulator.sh start                 # or: scripts/emulator.sh start ~/Downloads/jitendex-yomitan.zip
scripts/emulator.sh view                  # a window showing the emulator; click and type in it yourself
scripts/emulator.sh app                   # Hiroi's example sentences
scripts/emulator.sh settings              # the Settings app, which has Japanese text
scripts/emulator.sh texts                 # text on screen and where to tap it; Hiroi's boxes sit there
scripts/emulator.sh trigger               # open the overlay, as long-pressing power does
scripts/emulator.sh tap 521 1398          # tap a box, then a word in the panel
scripts/emulator.sh screenshot            # -> build/emulator/screenshot.png
scripts/emulator.sh anki                  # AnkiDroid + AnkiConnect Android with the Lapis note type
scripts/emulator.sh yomitan ~/Downloads/yomitan-settings.json   # take a card format from Yomitan settings
scripts/emulator.sh fake-anki             # or: a fake AnkiConnect deck (Japanese::Core, field Word), much quicker
scripts/emulator.sh logcat                # Hiroi's log and crashes
```

After a code change: `./podman-build-apk.sh && scripts/emulator.sh install`. Run `scripts/emulator.sh` without arguments for all commands.

## License

Copyright (C) 2026 Kevin Thomas

GPL-3.0-or-later, see [LICENSE](LICENSE). The deinflection rules and `LanguageTransformer.kt` come from [Yomitan](https://github.com/yomidevs/yomitan) (GPL-3.0-or-later, Copyright (C) 2024-2026 Yomitan Authors). The launcher icon is 拾 from [Noto Sans CJK JP](https://github.com/notofonts/noto-cjk) (SIL Open Font License), turned into a vector by `scripts/make-icon.py`.
