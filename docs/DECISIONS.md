# Kairo AI: Decisions Log

Every important decision made while building Kairo Gallery, in order, with the reason and what we chose *not* to do.
Status: ✅ in place · 🔄 changed later · ⏳ in progress · 🅿️ parked

---

## Phase 1: Idea and approach (before 2026-09-25)

| # | Decision | Why | Alternatives rejected | Status |
|---|---|---|---|---|
| D1 | Kairo AI has 3 features: **Gallery AI search**, **Screenshot Brain**, **Settings agent**. Build **Gallery AI first**. | Most visual demo; the other two can reuse its index | Building all three at once | ✅ |
| D2 | **Everything on-device, nothing in the cloud** | The pitch is "Privacy is the product": airplane-mode demo, no accounts | Cloud vision APIs (faster to build, but break the promise) | ✅ |
| D3 | LLM = **Gemma 4 E2B** on **LiteRT-LM** | Works offline, understands images and text, ships as `.litertlm` | Cloud Gemini; text-only models | 🔄 upgrading to E4B (D24) |
| D4 | **Step 0 test in AI Edge Gallery** before writing code | Check the model on a real ticket first | – | ✅ It labelled the BookMyShow ticket correctly but **misread the booking ID, seats and theatre** |
| D5 | Because of D4: **OCR gives the exact text, Gemma only sorts and copies, regex checks every number** | A small LLM can't be trusted with digits. Any value not found in the OCR text is dropped | Trusting Gemma's values directly | ✅ |
| D6 | OCR = **Google ML Kit Text Recognition** (bundled Latin model) | Offline, free, no download, good on screenshots | Tesseract (slower, bigger); Gemma-only reading | ✅ Runs on **CPU** |

## Phase 2: First build (2026-09-25)

| # | Decision | Why | Alternatives rejected | Status |
|---|---|---|---|---|
| D7 | Demo scope: **only `Pictures/Kairo`**, **images only** (no videos), optional new screenshots | Controlled demo set, fast indexing | Whole gallery (slow with Gemma at ~3 s per photo) | ✅ (whole gallery is a later goal) |
| D8 | Storage = **plain SQLite + FTS4**, no Room | Fewer build and annotation-processing problems; FTS4 gives fast text search | Room; a vector DB | ✅ |
| D9 | Background indexing = **WorkManager** triggered by gallery changes (2–10 s delay) + foreground notification | New photos get indexed automatically, even with the app closed | Polling; a manual button only | ✅ |
| D10 | Search = **rules first, Gemma fills the gaps**. Gemma never sees the gallery, only writes a filter | Rules are instant and exact; the LLM handles odd wording; privacy + speed | Sending all captions to the LLM | ✅ |
| D11 | Gemma on **GPU**, falls back to CPU | GPU is about 5× faster for prefill | CPU only | ✅ |
| D12 | **Reuse Edge Gallery's E2B download**: copy it on the phone (`adb shell cp`) into the app's folder | No second 2.6 GB download; Android blocks reading another app's folder | Download again | ✅ (OnePlus 13R) |

## Phase 3: Semantic search (2026-09-25 to 26)

| # | Decision | Why | Alternatives rejected | Status |
|---|---|---|---|---|
| D13 | **Add image-text embeddings (CLIP)** | The deck promises "the sunset at the lake with my dog". Keyword search can't find a *golden retriever* in a photo with no text unless Gemma happened to use those words | Better Gemma tags only (still word-matching); captioning + a text-embedding model (slow, and depends on the caption) | ✅ |
| D14 | CLIP model = **Qualcomm AI Hub OpenAI-CLIP ViT-B/16, TFLite float** (MIT) | Documented inputs and outputs, tuned for Snapdragon, quoted at ~22 ms on the NPU | MobileCLIP TFLite (inputs, outputs and tokenizer undocumented); SigLIP (no ready TFLite); ViT-L (too big) | ✅ Downside: **600 MB**, image and text parts in **one file** |
| D15 | CLIP runtime = **LiteRT 2.2 `CompiledModel` API** | Official successor to TFLite; picks GPU/NPU/CPU; no clash with LiteRT-LM's native libraries | Old TFLite `Interpreter` | ✅ |
| D16 | **Write the CLIP tokenizer ourselves in Kotlin** + unit tests against OpenAI's reference token IDs | No Android library for it; a silent tokenizer bug would ruin search | Tokenizer model on the phone | ✅ 4/4 tests pass |
| D17 | Vocab stored as **plain text** in `assets/` | The Android build silently un-gzips `*.gz` assets and renames them (found on the phone) | `.gz` asset | ✅ |
| D18 | Embeddings in a **separate `embeddings` table**; DB v2 upgrade **keeps old data** | Re-indexing with Gemma must never wipe the vectors; no forced re-index for users | Extra column on `images`; drop and rebuild | ✅ |
| D19 | **Two-pass indexing**: fast CLIP pass (~0.5 s) → deep OCR + Gemma pass (~3–4 s) | Photos are searchable within seconds; placeholder rows ("reading…") until Gemma finishes | One combined pass (slow first result) | ✅ |
| D20 | **Hybrid ranking**: text score (category +5, keyword +2, field +1) + CLIP score `(sim − 0.20) × 100` | Documents need exact text; photos need meaning | CLIP only (can't read ID numbers); text only | ✅ |
| D21 | Document questions (ID/ticket/bill, or asking for a value) → **CLIP weight × 0.3**. Otherwise CLIP leads | "My PAN number" must use exact OCR; "cute puppy" must use visuals | Same weight for both | ✅ |
| D22 | Cut-offs **MIN_SIM 0.20, REL_GAP 0.06 → 0.04** | "cute puppy" let in 2 unrelated screenshots at 0.06 | – | 🟡 tuned on only 14 photos |
| D23 | Gemma's query prompt returns a **`visual` phrase**; CLIP averages 4 prompts (raw, "a photo of …", Gemma phrase ×2). The index prompt now asks for **species/breed + general tags**. **"other"** is no longer used as a filter | Better CLIP matching; stops "other" flooding results | – | ✅ |
| D24 | CLIP accelerator: **GPU only** ❌ (failed to compile) → **CPU, 1 thread** (~2 s per photo) → **GPU + CPU, 4 threads** (~0.4–0.7 s) | The GPU can't run some of the text part's integer operations; mixed mode lets the CPU run those | NPU (needs LiteRT's Qualcomm NPU runtime) | 🔄 **NPU is the next step** |

## Phase 4: Git and repo

| # | Decision | Why | Status |
|---|---|---|---|
| D25 | Semantic search built on the branch **`feature/semantic-search`** (repo `vijaylingoju/KairoAI`), pushed **without merging into main** | Keep `main` stable until tested on the phone | ✅ PR not opened (stopped by user) |
| D26 | Code moved to a **new repo `vijaylingoju/kairo`** (`main`), same code | User's choice for the hackathon | ✅ |
| D27 | **Models are never committed**. They live outside OneDrive (`%USERPROFILE%\kairo-models`) and are pushed with `adb` | GB-sized files; OneDrive would sync them to the cloud | ✅ |

## Phase 5: iQOO 15 (2026-09-26, at the hackathon)

| # | Decision | Why | Alternatives rejected | Status |
|---|---|---|---|---|
| D28 | Test phone changed: **OnePlus 13R → iQOO 15** (Snapdragon 8 Elite Gen 5 / SM8850, 16 GB RAM, Android 16, Hexagon NPU driver present) | It's the device named in the pitch deck; much stronger | – | ✅ connected, app installed, CLIP pushed |
| D29 | **Parked the Settings assistant**; focus only on making Gallery AI the best it can be | Deliver one excellent feature for the demo | Building both at once | 🅿️ |
| D30 | LLM upgrade = **Gemma 4 E4B** (`gemma-4-E4B-it.litertlm`, 3.66 GB, vision included) | About 2× the reasoning of E2B, <1 GB GPU memory, ~22 tokens/s decode | **12B** (6.9 GB, likely 10 s+ per photo, too slow for indexing); 26B/31B (too big) | ⏳ downloading |
| D31 | App **automatically picks the best Gemma** in its folder (E4B > E2B > `model.litertlm`) and shows the model name on screen | Swap models by just pushing a file; shows the upgrade during the demo | Fixed filename | ✅ built, not committed |
| D32 | Network workaround: the hackathon Wi-Fi gives **50–90 KB/s** (~10 h for E4B); the phone's adb shell has **no DNS** (worked around by pinning IPs, but the same Wi-Fi is just as slow) | – | Options: USB tethering over 5G, hotspot, copy from a teammate, or E2B from the OnePlus over USB | ⏳ waiting for user |

## Phase 6: End-to-end evaluation on iQOO 15 (2026-09-26, see [Report #2](reports/REPORT-02_2026-09-26_1600-IST_gemma4-e4b-iqoo15-evaluation.md))

| # | Decision | Why | Status |
|---|---|---|---|
| D33 | **Repeatable eval harness**: debug-only adb receiver + 41 labelled scenarios + synthetic fake documents (`tools/eval/`) | Every change is measured against the same test set, not eyeballed | ✅ baseline 10/41 → final 39/41 |
| D34 | **Remove the INTERNET permission** (merged in by ML Kit's `datatransport`) | "Zero network calls" must be enforced by the OS, not just by our code | ✅ |
| D35 | **Every Gemma call is bounded** (token cap + watchdog `cancelProcess()`) | A runaway generation froze all searches during testing | ✅ |
| D36 | **Gemma's tags decide what's included; CLIP ranks and confirms.** A CLIP-only result needs adjusted score ≥ 0.08 | Calibration: CLIP margins were only 0.003–0.022; negatives scored up to 0.069 | ✅ precision 0.35 → 0.98 |
| D37 | **Fast path: skip Gemma when rules fully understand the question** | Same accuracy for document questions, 0.11–0.17 s instead of ~3 s | ✅ 15/41 queries |
| D38 | Field extraction: **IDs only on ID cards and only if regex-valid**; OCR-tolerant patterns (O→0, `0ct`, `B2I 34`); status bar excluded from OCR | Gemma copies misread or unrelated values | ✅ 10 unit tests from real OCR |
| D39 | Eval results are **git-ignored** | They contain OCR of real personal documents (Aadhaar) | ✅ |

## Phase 7: Faster Gemma indexing (2026-09-26 night, see [Report #3](reports/REPORT-03_2026-09-26_2355-IST_gemma-indexing-speed.md))

| # | Decision | Why | Status |
|---|---|---|---|
| D40 | **Measure before optimising** with LiteRT-LM's benchmark (tokens read/written, speeds) | Writing the JSON turned out to be 62–78% of Gemma's time; conversation set-up was only 2–6 ms | ✅ |
| D41 | **Every speed change must pass a quality gate** (categories, every field value, tags vs the base run) | Speed that loses PNRs, names or seats isn't worth it | ✅ `bench_index.ps1` + `compare_index.ps1` |
| D42 | **Speculative decoding on by default** | Identical output in 2 runs; documents 9.7 → 5.7–6.7 s, gallery -24 to -36%, searches -17% | ✅ adopted |
| D43 | **Rejected "write less" prompts** | -17% time but 7 fields lost and less specific tags | ❌ kept the original prompts |
| D44 | **Rejected a lower image budget (140 tokens)** | 4 movie tickets lost their category and all fields | ❌ full image detail |
| D45 | No forced greedy decoding | Default output is already deterministic (3 separate processes, identical) | – |

## Phase 8: CLIP on the Snapdragon NPU (2026-09-27, see [Report #5](reports/REPORT-05_2026-09-27_0425-IST_clip-on-npu.md))

| # | Decision | Why | Status |
|---|---|---|---|
| D46 | **CLIP image embeddings run on the Hexagon NPU** through LiteRT 2.2 (on-device compile, cached), with GPU+CPU then CPU as fallbacks | 99 → 14 ms per photo (7×); real visual pass 2.3 → 0.9 s for 20 photos; vectors 0.99999 identical | ✅ |
| D47 | **Search text stays on GPU+CPU** | On the NPU (fp16) the text tower's ARG_MAX can't tell the end token (49407) from the start token (49406), so text vectors were wrong (cosine 0.58). Queries are one run each, so the GPU's ~90 ms is fine | ✅ exact match with GPU |
| D48 | **Patch the CLIP file instead of the app** (`tools/patch_clip_npu.ps1`): declare 196 unused vision-token tensors as outputs | LiteRT's Qualcomm compiler turns unused tensors into NPU outputs that never get buffers ("clientBuf is null"). Append-only patch; maths unchanged (GPU output bit-identical) | ✅ done by `setup_models.ps1` |
| D49 | **Bundle only the V81 (SM8850) NPU libraries**, arm64 only, no QNN DSP/GPU backends | The full Qualcomm package is 67 MB for 6 chip generations | ✅ +45 MB APK |
| D50 | **Don't commit Qualcomm's `libQnnIr.so` / `libQnnSaver.so`**; fetch them from the public QAIRT SDK with range requests (`tools/fetch_qnn_libs.ps1`, ~6 MB of 2.2 GB) | They aren't on Maven and are Qualcomm SDK binaries; keep the public repo clean. Without them the app falls back to GPU | ✅ |

## Phase 9: One assistant for photos and settings (2026-09-27)

| # | Decision | Why | Alternatives rejected | Status |
|---|---|---|---|---|
| D51 | **A router in front of the two features; neither feature changes.** Rules first; unclear requests ask Gemma for one word (`gallery` / `settings` / `none`, ~0.7 s); after that the user picks with two buttons | Both features stay as tested (41-scenario eval, 94-phrase settings test); rule-matched requests still skip Gemma | One big prompt with both formats (longer, slower, a small model mixes up the JSON shapes, loses the fast paths) | ✅ 47-phrase `IntentRouterTest` |
| D52 | When a request has both photo and settings words, **a word that changes something ("set", "turn") means settings, anything else means photos**; the wallpaper is a setting that uses the gallery | Settings synonyms appear in photo requests: "bright sunset photos", "screenshot of the wifi password", "black and white photos", "landscape photos" | Settings keyword match alone | ✅ |
| D53 | **The chat is the gallery's third tab (Photos · Albums · Kairo)**, not a separate app or activity. Photo answers show thumbnails in the chat; "See all" opens the gallery search with the same result | One app, one way in for the floating ball; the gallery stays the place to browse photos | Two launcher icons from one APK (feels like two apps); keeping the chat behind the developer screen | ✅ tested on the iQOO |
| D54 | **The conversation lives in `AssistantSession`** (process-wide), not in a ViewModel | The floating ball can hand a question and its answer to the Kairo tab without asking Gemma again | Passing the reply through Intent extras (not Parcelable) or running the query again (2–5 s) | ✅ |
| D55 | Floating ball: **load Gemma when the ball is tapped, unload after ~5 min unused**; "Keep Kairo ready" switch for the demo | Both models take ~2 GB; a foreground service keeps the process important, so Android would close the user's other apps first | Always loaded; load per question (+5–9 s each time) | ⏳ next step |

---

## Open decisions (to make next)

1. **Overlap photo decoding with the NPU run** in the visual pass: decoding (~25 ms) is now slower than CLIP itself (14 ms).
2. **Gemma on the NPU** once Google/Qualcomm publish a Gemma 4 build for SM8850 (only E2B for SM8750 exists today).
3. **Smaller / split CLIP** (MobileCLIP-S2 or 8-bit): 150 MB instead of 600 MB, no wasted text-part work.
4. **Whole gallery vs `Pictures/Kairo`** for the demo.
5. **Skip Gemma for plain camera photos** (no OCR text + confident CLIP) to index about 5× faster.
6. **Fingerprint lock + masking** for ID numbers.
7. **UI refinement** (list in `KAIRO_CHECKLIST.md` §8).
