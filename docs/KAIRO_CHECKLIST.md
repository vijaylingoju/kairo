# Kairo AI: Build Checklist

iQOO Hackathon 2026 · Productivity track · Everything runs on the phone, with no cloud.
Last updated: 2026-09-26 · Repo: `vijaylingoju/kairo` (main) · Test phone: **iQOO 15** (Snapdragon 8 Elite Gen 5 / SM8850, 16 GB RAM, Android 16). Earlier: OnePlus 13R (Snapdragon 8 Gen 3). Decisions: see [DECISIONS.md](DECISIONS.md)

Legend: ✅ done · 🟡 partly done / needs tuning · ⬜ not started · ⭐ needed for the demo

---

## 1. What runs where (tech stack)

| Stage | What we use | Runs on | Speed (13R) | Stored where |
|---|---|---|---|---|
| Find new photos | Android MediaStore + WorkManager (starts when the gallery changes) | CPU | instant | – |
| Read image text | **Google ML Kit Text Recognition** (Latin, bundled offline model) | **CPU** | not measured yet | `images.ocr_text` |
| Visual fingerprint | **OpenAI CLIP ViT-B/16** (Qualcomm AI Hub TFLite, float, 600 MB) on **LiteRT 2.2** | **GPU + CPU** (4 threads) | ~0.4–0.7 s / photo | `embeddings` table (512 floats per photo) |
| Understand the photo | **Gemma 4 E4B** (`.litertlm`, 3.66 GB) on **LiteRT-LM 0.17**. Was E2B (2.6 GB) on the OnePlus 13R | **GPU** (falls back to CPU) | E2B: ~3–4 s / photo; E4B on iQOO 15: to be measured | `images.category / description / tags / fields` |
| Check the numbers | Regex + checks against the OCR text (`FieldExtractor`) | CPU | instant | `images.fields_json` |
| Split question into words | Kotlin port of CLIP's tokenizer | CPU | instant | vocab file in `assets/` |
| Understand the question | Rules first, then Gemma turns the question into a filter plus a visual phrase | CPU + GPU | ~1–3 s | – |
| Search | SQLite **FTS4** text search + CLIP dot product over all photos | CPU | milliseconds | – |
| Database | Plain **SQLite** (`kairo_index.db`): `images`, `images_fts`, `embeddings` | storage | – | app's private storage |

> **How CLIP's speed changed while we were building it:**
> 1. First try: **GPU only**. This failed ("Failed to compile model"), because the GPU can't run some of the text part's integer operations.
> 2. The app fell back to **CPU**, one thread: about **2 s per photo**.
> 3. Now: **GPU + CPU together** (the GPU runs what it can, the CPU runs the rest, 4 CPU threads): about **0.4–0.7 s per photo**, roughly 3–4× faster.
> 4. Next: the **NPU** (Hexagon). Qualcomm quotes about **22 ms per photo** for this model there.
>
> Note: **OCR runs on CPU, not GPU.** ML Kit's bundled text model runs on the CPU. The GPU is used by Gemma and, partly, by CLIP.

### How a photo is indexed (two passes)
```
New photo in Pictures/Kairo (or a new screenshot)
   └─ WorkManager wakes up (2–10 s after the gallery changes)
       ├─ Pass 1: FAST  → decode 512px → CLIP → embeddings table      ≈0.5 s  → searchable right away
       └─ Pass 2: DEEP  → decode 2048px → ML Kit OCR → Gemma (image + text)
                          → regex checks → images + images_fts tables   ≈3–4 s  → categories, fields, answers
```

### How a search works
```
Question → rules (instant) + Gemma filter (categories, keywords, wanted field, dates, visual phrase)
        ├─ text score:   category +5 · each keyword hit +2 · has the wanted field +1
        └─ visual score: CLIP(question) · CLIP(photo); kept if ≥ 0.20 and within 0.04 of the best
        → merged ranking (document questions: text leads · visual questions: CLIP leads)
        → answer card (e.g. "PAN number: ABCDE1234F", value checked against the OCR text)
```

---

## 2. Setup and environment

- [x] Android project builds (AGP 9.4, Kotlin 2.4, Compose, minSdk 31)
- [x] Git repo + GitHub remote (`vijaylingoju/KairoAI`, branch `feature/semantic-search`) → moved to `vijaylingoju/kairo` (main)
- [x] OnePlus 13R: phone connected, Gemma 4 E2B copied from Edge Gallery, CLIP pushed, 14 test photos, semantic search tested
- [x] **iQOO 15**: phone connected, app installed (4/4 tests pass), CLIP pushed, `Pictures/Kairo` created
- [ ] ⏳ **iQOO 15: Gemma 4 E4B** downloading to `%USERPROFILE%\kairo-models` (hackathon Wi-Fi ~50–90 KB/s, so use USB tethering or a hotspot)
- [ ] ⬜ **iQOO 15: add 20–40 test photos** to `Pictures/Kairo` (tickets, IDs, bills, animals, sunsets, food, people)
- [ ] ⬜ iQOO 15: re-run the test queries; compare E2B vs E4B and CLIP speed
- [x] App auto-picks the best Gemma in its folder (E4B > E2B) and shows the model name
- [ ] ⬜ README: how to set up the models (the 2 `adb` commands), since they aren't in the repo
- [ ] ⬜ Add `adb` to PATH on the laptop (it's only in the SDK folder right now)

**Improve:** keep the models out of git (done). Add a small "Setup" screen in the app that checks both model files and shows the exact `adb push` command for anything missing.

---

## 3. Gallery AI: document search

- [x] ML Kit OCR reads exact text
- [x] Gemma sorts photos into 15 categories and pulls out fields (ID, booking ID, seats, amount…)
- [x] Regex checks: values not found in the OCR text are dropped (no made-up digits)
- [x] Answer card with a Copy button ("PAN number: …", "PNR: …")
- [x] Rules understand dates ("today", "this week", "this month", "last month")
- [x] "other" category no longer floods results
- [ ] 🟡 **Bus tickets** are labelled `train_ticket`. Add a `bus_ticket` category plus rules (`bus`, `redbus`, `abhibus`, `apsrtc`)
- [ ] ⬜ Aadhaar/PAN regex tests (JVM unit tests like the tokenizer tests)
- [ ] ⬜ ⭐ Fingerprint lock before showing ID numbers (BiometricPrompt)
- [ ] ⬜ Hide most of an ID number in thumbnails and details (`XXXX XXXX 8271`) until unlocked

**Improve:** Gemma is the slow part (~3–4 s). Skip Gemma for photos where OCR finds no text *and* CLIP is confident. For those, CLIP alone is enough, which makes indexing about 5× faster on normal camera photos.

---

## 4. Gallery AI: semantic image search ⭐ (the deck's main promise)

- [x] CLIP image + text embeddings on the phone (LiteRT 2.2)
- [x] Tokenizer matches OpenAI's reference token IDs (unit tested)
- [x] Separate `embeddings` table (a Gemma re-index never wipes it; the DB upgrade keeps existing data)
- [x] Two-pass indexing (photos become searchable within seconds)
- [x] Hybrid ranking (CLIP + keywords + category + fields)
- [x] Gemma rewrites the question into a visual phrase ("a dog at a lake during sunset")
- [x] Prompt ensemble: raw phrase + "a photo of …" + Gemma's phrase, averaged
- [x] Tested: "golden retriever dog" → only the puppy (0.271 vs next 0.186)
- [ ] 🟡 Tune the cut-offs (`MIN_SIM 0.20`, `REL_GAP 0.04`) on 50+ varied photos
- [ ] 🟡 Screenshots with black bars score higher than they should, because the center crop sees mostly black. Crop away letterboxing before running CLIP
- [ ] ⬜ Run a 10-query test set and record results (sunset, flowers, food, ice cream, burgers, person, tickets, Aadhaar, bus, dog)
- [ ] ⬜ ⭐ **Move CLIP to the NPU** (Qualcomm build of the model, or LiteRT NPU with the Qualcomm dispatch library) → about 22 ms per photo, and backs up the "NPU" claim on slides 5 and 8
- [ ] ⬜ Smaller CLIP: an 8-bit build, or split image and text models (MobileCLIP-S2), so the model is ~150 MB instead of 600 MB and there's no wasted text-part work per photo
- [ ] ⬜ Search inside SQL / keep embeddings in memory, so search stays fast at 10k photos (today every search loads all rows)
- [ ] ⬜ Index the whole gallery, not only `Pictures/Kairo` (remove the folder filter + run on the charger at night)

**Improve:** cache the question's CLIP embedding for repeated searches. Show "why this matched" (visual 0.27 / keyword "hyderabad") in the detail view.

---

## 5. Background indexing

- [x] WorkManager watches the gallery (runs 2–10 s after a change)
- [x] Foreground notification while indexing (Android 14 data-sync type)
- [x] Optional: also index new screenshots
- [x] "Re-index all" button
- [ ] 🟡 Live test: drop a new photo → searchable within ~10 s (worked in testing; time it for the demo)
- [ ] ⬜ Battery rule: do the deep Gemma pass only while charging when the backlog is over 50 photos
- [ ] ⬜ Retry photos that failed (`status = failed`) once, later

---

## 6. Settings assistant ⬜ (the deck's second use case)

- [ ] ⬜ ⭐ Gemma turns a request into an intent: `{action: "dark_mode", value: "on"}`
- [ ] ⬜ ⭐ Direct actions where Android allows them: Wi-Fi/Bluetooth/DND panels, flashlight, brightness, dark mode (`UiModeManager`), timers and alarms
- [ ] ⬜ Deep-link into the exact Settings screen when Kairo can't act directly
- [ ] ⬜ Step-by-step guide as the fallback ("Settings → Display → Dark mode")
- [ ] ⬜ Accessibility-service actions for toggles that are only in the UI (optional, needs care)
- [ ] ⬜ An offline table of iQOO/Funtouch settings paths

---

## 7. Unified bubble + voice ⬜

- [ ] ⬜ ⭐ Floating bubble overlay (`SYSTEM_ALERT_WINDOW`), tap → search box
- [x] Sends each request to photos or settings (rules + Gemma), in the Kairo tab (`assistant/`)
- [ ] ⬜ Offline speech-to-text (Android on-device `SpeechRecognizer`, `EXTRA_PREFER_OFFLINE`)
- [ ] ⬜ Show results inside the bubble (thumbnails + answer card)

---

## 8. UI refinement 🎨

The current screen is a developer screen. Changes for the demo:

**Home**
- [ ] ⬜ Search bar at the top as the main element, with a mic button and example chips ("my PAN number", "food photos", "movie tickets", "sunset with my dog")
- [ ] ⬜ Move model status lines (Gemma/CLIP/indexing) into a compact status pill: "● Private · On-device · 14 photos indexed". Tap it for details
- [ ] ⬜ Put the debug buttons (Index now / Re-index all / screenshots toggle) and the filter line behind a Settings / Developer sheet
- [ ] ⬜ Two-stage progress: "Making photos searchable 8/14" → "Reading details 3/14"
- [ ] ⬜ Grid: rounded thumbnails, no category text under every photo; a small badge only for documents (🎫 ticket, 🪪 ID)
- [ ] ⬜ Fix the layout jump when the filter line appears, which moves the search box

**Results**
- [ ] ⬜ Answer card at the top: large value, Copy, 🔒 unlock for IDs, "from: Aadhaar card · 01 Sep"
- [ ] ⬜ Results header: "3 photos · 0.4 s · on-device"
- [ ] ⬜ Friendly empty state: "No photos match 'sunset'. Try 'evening sky'"
- [ ] ⬜ Photos still being read show a subtle shimmer instead of "reading…"

**Detail view**
- [ ] ⬜ Full-screen photo viewer (swipe between results) instead of an alert dialog
- [ ] ⬜ Info sheet: description, tags as chips, fields with copy buttons, date + place; OCR text hidden under "Show text"
- [ ] ⬜ Actions: Share, Open in Gallery, (later) Add to calendar for tickets

**Look and feel**
- [ ] ⬜ Kairo theme from the deck colours; dark mode support; app icon
- [ ] ⬜ Airplane-mode badge during the demo ("✈ Offline, still works")

---

## 9. Demo prep ⭐

- [ ] ⬜ Curate ~40 demo photos (animals, sunsets, food, people, 5 tickets, fake/masked ID cards)
- [ ] ⬜ Script: airplane mode on → "my PAN number" → "movie tickets" → "sunset at the lake with my dog" → take a new screenshot, and it's found live → "turn on dark mode"
- [ ] ⬜ Record a backup video of the demo
- [ ] ⬜ Fix the slides: "NPU" wording (until CLIP runs on the NPU), and add the document-answer feature (it isn't in the deck)
- [ ] ⬜ Check it on the iQOO 15 if one is available (the deck names it)

---

## 10. Known issues / technical debt

| Issue | Impact | Fix |
|---|---|---|
| CLIP can't run fully on the GPU | ~0.5 s per photo instead of ms | NPU build or a split/quantised model |
| One CLIP file for image + text | each image run also runs the text part | split models (MobileCLIP) |
| Search loads every row + embedding | slow at thousands of photos | cache in memory / filter in SQL |
| Phone storage 98% full (~4.5 GB free) | model pushes may fail | delete Gemma from Edge Gallery (2.6 GB) |
| Cut-offs tuned on 14 photos | wrong matches possible | a bigger test set |
| Bus ticket → `train_ticket` | wrong label, PNR wording | `bus_ticket` category |
| ID numbers shown in plain text | privacy risk in the demo | fingerprint lock + masking |
