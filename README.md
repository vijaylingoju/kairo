# Kairo Gallery: on-device AI gallery search

Ask your gallery in plain words: "my PAN number", "movie tickets", "golden retriever", "సినిమా టికెట్లు".
Everything runs **on the phone**: Gemma 4 (LiteRT-LM) + CLIP (LiteRT) + ML Kit OCR. The app has **no internet permission**.

**➡ New device? Follow [SETUP.md](SETUP.md)** (step by step, plus a one-command model push: `tools\setup_models.ps1`).

## How it works

```
New photo in Pictures/Kairo  ──(WorkManager wakes on gallery change)──▶
  Pass 1 (fast, ~0.04 s)     CLIP image embedding on the NPU   → searchable by what it looks like
  Pass 2 (deep, ~5-12 s)     ML Kit OCR (exact text)
                             Gemma 4 E4B (image + OCR)         → category, description, tags, fields (JSON)
                             Checks: regex + grounding          → PAN/Aadhaar/PNR/booking ID/seats/amount; drop anything not in OCR
                             SQLite (+FTS4, + CLIP vectors)     → saved on the phone

Question ─▶ rules (instant) ─┬─ confident? → skip Gemma (~0.1 s)
                             └─ else Gemma → filter (categories, keywords, field, dates, English visual phrase)
         ─▶ text evidence (category + keywords/tags, IDF-weighted)  decides WHAT is included
         ─▶ CLIP similarity (vs each photo's own baseline)          ranks, and adds only clearly strong visual matches
         ─▶ ranked photos + answer card ("PNR: 4521367890")
```

Evaluation on an iQOO 15 (41 scenarios): **39/41 pass, 98% precision**. See [Report #2](docs/reports/REPORT-02_2026-09-26_1600-IST_gemma4-e4b-iqoo15-evaluation.md).

## Using it
- The top lines show model status (`gemma-4-E4B-it ready on GPU`, `CLIP ready: images on NPU, text on GPU+CPU`) and indexing progress.
- **Index now** indexes new or changed photos. **Re-index all** reruns everything. **Clear all data & start fresh** wipes the index (not photos or models).
- **Also index new screenshots**: screenshots taken after you switch it on are indexed too.
- Tap a thumbnail to see its category, description, tags, fields, OCR text and indexing time.
- Try: `my PAN number`, `what's my PNR`, `seat number for my flight`, `movie tickets from last month`, `ice cream`, `golden retriever`, `food photos`.

## Code map
| File | Job |
|---|---|
| `index/GalleryScanner.kt` | Lists images in `Pictures/Kairo/` (+ new screenshots if on) |
| `index/Indexer.kt` | Two-pass pipeline (CLIP, then OCR + Gemma); skips already-indexed photos |
| `index/ClipCrop.kt` | Crops status/nav bars and empty borders before CLIP |
| `index/Ocr.kt` | ML Kit OCR (skips the status bar on screenshots) |
| `index/FieldExtractor.kt` | Regex + grounding checks, OCR-error repair, rule-based categories |
| `index/IndexWorker.kt` | WorkManager worker + gallery-change trigger |
| `embed/Clip.kt`, `embed/ClipTokenizer.kt` | CLIP runtime (LiteRT: photos on the NPU, search text on GPU+CPU) and Kotlin BPE tokenizer |
| `llm/Llm.kt`, `llm/Prompts.kt` | Gemma loader (auto-picks E4B > E2B), bounded generation, prompts |
| `data/IndexDb.kt` | SQLite + FTS4 + CLIP embeddings |
| `search/SearchEngine.kt` | Question → filter → evidence → ranked results + answer |
| `ui/gallery/` | **User/demo UI**: OriginOS-style gallery (day-grouped photos, smart albums), smart search with AI animations and answer cards, full-screen viewer; tabs Photos · Albums · Kairo |
| `assistant/` | **Kairo tab**: one chat for photos and settings. `IntentRouter` decides which one answers (rules, then Gemma); `AssistantSession` holds the conversation |
| `settings/` | Settings assistant: knowledge base, keyword + Gemma agents, the code that changes settings (see [docs/settings-progress](docs/settings-progress/README.md)) |
| `ui/MainActivity.kt` | Routes between the gallery (default) and the developer screen (🔧 icon) |
| `ui/MainViewModel.kt` | Shared state: photos, indexing status, search results, recent searches |
| `app/src/debug/…/EvalReceiver.kt` | Debug-only adb hooks for the evaluation harness |
| `tools/setup_models.ps1` | Checks the phone and pushes the models |
| `tools/download_parallel.ps1` | Parallel downloader for throttled networks |
| `tools/eval/` | 41-scenario evaluation harness + synthetic test documents |

## Docs
- [SETUP.md](SETUP.md): new laptop and phone setup, troubleshooting
- [docs/DECISIONS.md](docs/DECISIONS.md): every design decision and why
- [docs/KAIRO_CHECKLIST.md](docs/KAIRO_CHECKLIST.md): what's done and what's next
- [docs/reports/](docs/reports/): evaluation reports

Logcat filters: `KairoIndexer`, `KairoLlm`, `KairoClip`, `KairoSearch`, `KairoWorker`, `KairoEval`, `KairoAssistant`, `KairoSettingsLlm`.
