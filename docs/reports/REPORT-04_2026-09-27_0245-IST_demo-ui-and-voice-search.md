# Report #4: Demo gallery UI and offline voice search

| | |
|---|---|
| **Report timestamp** | **2026-09-27 02:45 IST** |
| **Device** | iQOO 15 (Snapdragon 8 Elite Gen 5, 16 GB, Android 16) |
| **Code** | repo `vijaylingoju/kairo`, branch `fix/improvements`. UI merged to `main` (commit `0f01838`); **voice search not committed yet** |
| **Previous** | [Report #2](REPORT-02_2026-09-26_1600-IST_gemma4-e4b-iqoo15-evaluation.md) (search quality, 39/41) · [Report #3](REPORT-03_2026-09-26_2355-IST_gemma-indexing-speed.md) (speculative decoding, Gemma −24 to −36%) |

---

## 1. Summary

| Area | Status |
|---|---|
| **Demo gallery UI** (iQOO Albums style) | ✅ Built, tested on the phone, merged to `main` |
| **App icon** (gallery style) | ✅ Merged |
| **Voice search** (speech → text → search) | ✅ Working on the phone, fully offline, English (US). Heard "Show me all the movie tickets" and returned all 4 movie tickets 0.65 s later |
| **Gallery-word boost for voice** (names like "Irumudi") | ✅ Built, 6 unit tests pass, installed. ⏳ Needs a real voice test |
| **Unit tests** | 20/20 pass (4 tokenizer + 10 field extraction + 6 voice vocabulary) |

---

## 2. Demo gallery UI (merged)

A user-facing screen modelled on the iQOO 15 (OriginOS) **Albums** app is now the default. The original developer screen is kept, one tap away behind a small 🔧 icon (Back returns to the gallery).

| Screen | What it shows |
|---|---|
| **Home** | Big "Photos" title, a pill-shaped **search bar with a moving AI glow**, photos grouped by day ("Today", "Yesterday", "Wed, 23 Sep") in a 4-column grid, **Photos \| Albums** tabs |
| **Albums** | Built automatically: All photos, Tickets, **IDs & documents** (blurred cover + 🔒), Bills & payments, Food, People, Places, Screenshots |
| **Smart search** | "Try asking" suggestions, recent searches, a **thinking animation** with shimmer tiles, an **answer card** ("PNR 4521367890" + Copy + "From your train ticket ›"), "Understood" chips, "1 photo · 343 ms · on this phone" |
| **Viewer** | Full-screen, swipeable. Details sheet with description, tags, every field with Copy, OCR text. **ID numbers masked until tapped** |
| **Background work** | A slim "Understanding your photos · 3 of 19" progress pill; a ✦ badge on photos still being read |
| **App icon** | Adaptive vector: photo card with mountains and sun, plus an AI sparkle on the purple→cyan gradient; supports themed icons |

The only non-UI changes: `SearchResult.answerImage` (which photo an answer came from) and `MainViewModel.recent` (this session's searches, never stored). Search and indexing behaviour are unchanged.

---

## 3. Voice search: what was built and why

### 3.1 How it works
```
Tap 🎤 (home search bar or search screen)
  → mic permission (asked once)
  → listening sheet: glowing orb that pulses with your voice, live words, "Speech is recognised on this phone"
  → Google's on-device speech model (SODA, via Android System Intelligence), language en-US
       + up to 100 words from your gallery passed as hints (titles, venues, names, tags)
  → final text → sound-alike fix with gallery words ("hero modi" → "irumudi")
  → text fills the search box → normal search runs → results
```

### 3.2 The journey (problems found and decisions)

| Step | What happened | Decision |
|---|---|---|
| 1 | Checked the phone: it has **two recognisers**, Android System Intelligence (on-device) and Speech Services by Google | Use the on-device one first |
| 2 | First test: **"language isn't available offline"**. Logs: the on-device engine had **only `en-US` installed**; en-IN and hi-IN were downloadable, and **te-IN isn't supported on-device at all** | Built language chips and a one-time pack download… |
| 3 | You asked to keep it simple | **English (US) only.** Removed the chips and the download flow |
| 4 | Error 11 (server disconnected): the app destroyed one recogniser and created another at the same moment | **One recogniser per screen**, reused for every session |
| 5 | ✅ Works: "Show me all the movie tickets" → heard exactly → 4 tickets in 0.65 s | – |
| 6 | Names are misheard: **"Irumudi" → "hero modi"**, "Odyssey" → "a RC" | Looked at better options (§3.3) |
| 7 | You chose **offline only** | **Gallery-word boost**: hints before recognition + sound-alike fix after |

### 3.3 Options considered for better accuracy

| Option | Accuracy on names | Privacy | Why chosen or not |
|---|---|---|---|
| **Offline SODA + gallery words** | Good (fixes the tested mis-hearings) | ✅ 100% on the phone | **Chosen.** SODA is the same offline model Gboard uses for voice typing |
| Google online (what Gboard does with internet) | Best | ❌ Audio goes to Google's servers | Rejected: breaks "zero cloud" |
| Gemma 4 E4B listening to audio (`Content.AudioBytes`) | Likely good (sees the names in its prompt) | ✅ On-device | Not built: slower (~2–4 s), no live words |
| Whisper (Qualcomm Snapdragon builds) | Good with a vocabulary prompt | ✅ On-device | Not built: 250–500 MB extra download, needs audio preprocessing and a decoding loop |

**About Gboard:** Gboard doesn't have a separate model an app can call. Its offline voice typing uses **SODA**, the model Kairo already uses; the phone's logs show `SodaSpeechRecognizer`. Gboard is only more accurate **online**, when it uses Google's servers.

### 3.4 Gallery-word boost (`voice/VoiceVocabulary.kt`)
- **Hints:** up to 100 phrases built from the index (cleaned titles like "Irumudi", venues like "Sarathi Cinemas", names, tags). They're passed as `EXTRA_BIASING_STRINGS` (Android 13+).
- **Sound-alike fix:** each run of 1–3 uncommon words is reduced to a consonant pattern ("heromodi" → r-m-d) and compared with gallery words ("irumudi" → r-m-d). A second letter-level check prevents loose matches.
- **Everyday words are never rewritten** ("show", "me", "movie", "ticket", "PNR", "food"…).
- **Unit tests** (from real mis-hearings on the phone):

| Heard | Becomes |
|---|---|
| "show me hero modi movie ticket" | "show me **irumudi** movie ticket" ✅ |
| "tickets at murali krishna" | "tickets at **muralikrishna**" ✅ |
| "Show me all the movie tickets" / "what's my PNR" / "food photos from last month" | unchanged ✅ |

### 3.5 Privacy
- Kairo still has **no INTERNET permission**.
- It now asks for **RECORD_AUDIO**, and declares the speech-recognition service in `<queries>` so Android lets Kairo find it (Android 11+).
- The recogniser used is the **on-device** one, and the sheet says "Speech is recognised on this phone".

---

## 4. Known issues and limits

| # | Issue | Impact | Next step |
|---|---|---|---|
| V1 | Names that sound very different may still be missed ("Odyssey" → "a RC" can't be matched by consonant pattern) | Some name searches fail by voice | The hints (§3.4) should help; if not, try Gemma audio or Whisper |
| V2 | English (US) only | Hindi/Telugu voice not supported (typed Telugu/Hindi still works) | Optional one-time download of the hi-IN / en-IN on-device packs later |
| V3 | Gallery-word boost not yet tested with a real voice | Unknown real-world gain | Say "Show me Irumudi movie ticket", then check the `KairoVoice` log ("Corrected: …") |
| V4 | Voice work not committed | – | Commit + PR once it's tested |

Earlier open items (Reports #2 and #3) still apply: "red car" / "fast food" edge cases, one OCR F→E misread, bus seat, heat slowing Gemma, and a small test gallery.

---

## 5. Files changed since Report #3

| File | Change |
|---|---|
| `ui/gallery/*` (merged) | Gallery, albums, smart search, viewer, AI effects, theme |
| `res/drawable`, `res/mipmap-anydpi-v26` (merged) | App icon |
| `voice/VoiceRecognizer.kt` (new) | On-device speech → text (en-US), one recogniser per screen, gallery-word hints |
| `voice/VoiceVocabulary.kt` (new) | Hints + sound-alike correction |
| `ui/gallery/VoiceUi.kt` (new) | Listening sheet, orb, mic icon |
| `ui/gallery/SmartSearch.kt`, `GalleryApp.kt` | Mic buttons, voice flow, auto-search |
| `AndroidManifest.xml` | `RECORD_AUDIO`, `<queries>` for the recognition service |
| `test/.../VoiceVocabularyTest.kt` (new) | 6 tests from real mis-hearings |

## 6. Next steps
1. **Test the gallery-word boost** with a real voice (V3), then commit and open a PR.
2. Pick the next feature: UI polish, the fingerprint lock for IDs, or indexing the whole gallery.
