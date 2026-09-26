# Report #3: Making Gemma indexing faster without losing quality

| | |
|---|---|
| **Report timestamp** | **2026-09-26 23:55 IST** (benchmarks 23:10 to 23:54 IST) |
| **Device** | iQOO 15 (Snapdragon 8 Elite Gen 5 / SM8850, 16 GB), plugged in, battery ~36 °C |
| **Model** | Gemma 4 E4B `gemma-4-E4B-it.litertlm`, LiteRT-LM 0.17, GPU |
| **Test gallery** | 19 photos (8 photos without text, 11 documents/tickets/screenshots) |
| **Code** | repo `vijaylingoju/kairo`, branch `fix/improvements` |
| **Previous** | [Report #2](REPORT-02_2026-09-26_1600-IST_gemma4-e4b-iqoo15-evaluation.md): search quality 39/41 |

---

## 1. Summary

**Question:** Gemma takes about 5 s per photo and about 10 s per document. Can it be faster **without missing anything**?

**Answer:** yes, with **speculative decoding**. The output is **identical** (all categories, all 59 field values and all tags the same as before), and:

| | Before | **After** | Change |
|---|---|---|---|
| Gemma time per **document** | 9.7 s | **5.7–6.7 s** | **-31 to -41%** |
| Gemma time per **photo** | 5.3 s | **4.2–4.9 s** | -8 to -21% |
| Whole gallery (19 photos, Gemma time) | 149 s | **96–114 s** | **-24 to -36%** |
| Writing speed | 16.6–17.9 tokens/s | **23–38 tokens/s** | up to 2.1× |
| Search time when Gemma interprets the question (median) | 3.25 s | **2.71 s** | -17% |
| Search quality (41 scenarios) | 39/41 | **39/41** | same |

Ranges = two separate runs. The slower one ran on a hotter phone (back-to-back tests), which lowers the writing speed.

**Two other ideas were tested and rejected**, because the quality gate showed they lose information:
- "Write less" prompts: -17% time, but **7 fields lost** and less specific tags.
- A lower image-detail budget: -48% time, but **all 4 movie tickets lost their category and every field**.

---

## 2. Where Gemma's time goes (measured)

LiteRT-LM's built-in benchmark (`ExperimentalFlags.enableBenchmark`, `getBenchmarkInfo()`), base settings:

| | Photos (8) | Documents (11) |
|---|---|---|
| Gemma time per photo | 5.3 s | 9.7 s |
| Tokens **read** (instructions + OCR text + image) | 628 | 753 |
| Tokens **written** (the JSON answer) | 55 | 136 |
| Writing speed | 16.6 tokens/s | 17.9 tokens/s |
| **Writing the JSON** | **~3.3 s (62%)** | **~7.6 s (78%)** |
| Turning the image into tokens (~0.9 s) + reading the input (~1.1 s, 600–900 tokens/s) | ~2.0 s | ~2.1 s |
| Creating the conversation | 2–6 ms (1.1 s once, at warm-up) | same |

**Conclusion:** writing the JSON is the biggest cost (62–78%). For photos there's also a fixed ~2 s to look at the image and read the prompt.

---

## 3. What was tested

Each variant: restart the app with the setting → clear the index → re-index all 19 photos → measure → **quality gate** against the base run (`tools/eval/bench_index.ps1` + `compare_index.ps1`). The gate compares, photo by photo, the **category**, **every field value** (title, name, ID, booking ID, date, time, venue, seats, amount) and **tag overlap**.

| Variant | What it changes | Documents | Photos | Quality vs base | Decision |
|---|---|---|---|---|---|
| **base** | – | 9.7 s | 5.3 s | reference | – |
| **compact prompts** | Short prompt for photos without text; documents only list fields that are present | 7.7 s | 5.0 s | ❌ 18/19 categories, **7 fields lost** (train passenger and route, bus title and venue, bill restaurant name, boarding-pass name), 3 changed, tag overlap 0.63, names like "muralikrishna cinema" dropped from tags | **Rejected** |
| **speculative decoding** | A small draft model proposes tokens, Gemma checks them | **5.7 s** | **4.2 s** | ✅ 19/19 categories, 59/59 fields, identical tags | **Adopted** |
| speculative + **image budget 140** | Half the image tokens | 5.0 s | 3.8 s | ❌ **4 movie tickets → "other", 28 fields lost** | **Rejected** |
| speculative (repeat, final build) | Same as adopted, hotter phone | 6.7 s | 4.9 s | ✅ identical again | Confirms |

### Why "write less" lost information
- Telling Gemma "only include fields written exactly in the text" made it **too cautious**. It skipped values it wasn't 100% sure about, even correct ones.
- Photos barely gained anything: Gemma was already writing only ~55 tokens for them. Their cost is mostly the fixed ~2 s of looking and reading.
- This was your concern going in ("it might miss things"), and the gate confirmed it.

### Why the lower image budget failed
Ticket screenshots are tall (1264×2780), with small printed text. At half the image detail, Gemma couldn't recognise them as tickets at all. Full detail is needed.

### Why speculative decoding is safe
Gemma still decides every token: the draft model only proposes several at a time, and Gemma accepts or rejects them in one step. So the answer is the same one Gemma would write alone, just produced faster. Confirmed in two runs.

---

## 4. Other findings

| Finding | Evidence | Impact |
|---|---|---|
| **Gemma's default output is already deterministic** | 3 separate app processes (base, spec, spec_final) gave identical tags and fields | Forcing greedy decoding isn't needed. The earlier "fast food" tag variation came from prompt/OCR changes, not randomness |
| **Heat lowers speed** | Writing speed dropped 21 → 16 tokens/s within one run, and 38 → 32 tokens/s between back-to-back runs | Real-world indexing of big galleries will be slower on a hot phone. Index while charging and cool; compare token counts, not only seconds |
| **Closing and immediately re-creating the Gemma engine can hang** | In-process reload right after a load never finished (LiteRT-LM 0.17) | The test harness now restarts the app instead. The app itself never reloads Gemma |
| **The app process can restart between steps** | Settings held only in memory were lost | Test settings are now saved in preferences (debug harness only) |
| **The first photo after load is slower** | 1.1 s one-time conversation warm-up | Negligible for big galleries |

---

## 5. What changed in the app

| File | Change |
|---|---|
| `llm/Llm.kt` | `LlmTuning` settings. **Speculative decoding on by default.** Per-call timing log (tokens read/written, speeds, set-up/generate/close). Debug-only overrides saved in preferences |
| `llm/Prompts.kt` | `indexPhoto` / `indexDocument` compact prompts kept for future experiments; **not used by default** |
| `index/Indexer.kt` | Photo-vs-document detection (80+ letters/digits of OCR text, or a recognised document); logs Gemma time per photo |
| `KairoApp.kt` | Applies debug overrides at start-up (none exist in normal use) |
| `debug/EvalReceiver.kt` | `EVAL_CONFIG` switches: `spec`, `greedy`, `compact`, `vtb`, `reset` |
| `tools/eval/bench_index.ps1` | Indexing benchmark: restart with settings → re-index → timing table → quality gate. Warns if any photo was indexed with the wrong settings or hit the timeout |
| `tools/eval/compare_index.ps1` | Quality gate between any two saved runs |

Re-run everything:
```
powershell -ExecutionPolicy Bypass -File tools\eval\bench_index.ps1 -Label base
powershell -ExecutionPolicy Bypass -File tools\eval\bench_index.ps1 -Label spec -Spec -Ref base
powershell -ExecutionPolicy Bypass -File tools\eval\compare_index.ps1 -A base -B spec -ShowTags
powershell -ExecutionPolicy Bypass -File tools\eval\run_eval.ps1 -Label spec
```

---

## 6. Next ideas (not done yet)

| Idea | Expected gain | Risk |
|---|---|---|
| **Defer Gemma for big galleries**: CLIP + OCR make every photo searchable first; run Gemma while charging, prioritising photos that appear in searches | Waiting time for the user → ~0 | None on quality |
| **Shorter query prompt** (fewer examples) | Search 2.7 s → maybe ~2 s | Must pass the 41-scenario eval |
| **Instant results**: show rules + CLIP results immediately, then refine with Gemma | Perceived search time ~0.13 s | None |
| **Try image budget 280** (between 140 and default) | Maybe -10% on photos | Needs the quality gate; 140 failed |
| **Qualcomm NPU build of Gemma** for SM8850 (when available) | Large speed-up, less heat | None expected |
| **Skip Gemma on ID cards the rules fully recognise** (PAN/Aadhaar) | -7 s per ID card | Description/tags become templates |
