# Report #5: CLIP on the Snapdragon NPU

| | |
|---|---|
| **Report timestamp** | **2026-09-27 04:25 IST** |
| **Device** | iQOO 15 (Snapdragon 8 Elite Gen 5 / SM8850, Hexagon **V81** NPU, 16 GB, Android 16) |
| **Code** | branch `fix/improvements`, on top of `088df95` (not committed yet) |
| **Previous** | [Report #3](REPORT-03_2026-09-26_2355-IST_gemma-indexing-speed.md) (Gemma speed) · [Report #4](REPORT-04_2026-09-27_0245-IST_demo-ui-and-voice-search.md) (demo UI, voice) |

---

## 1. Summary

| | Before (GPU + CPU) | **After (NPU for photos)** | Change |
|---|---|---|---|
| CLIP per photo (model run) | 98.8 ms | **13.9 ms** | **7.1× faster** |
| Per photo incl. decoding + crop | 122 ms | **41 ms** | **3.0× faster** |
| **Real visual indexing pass** (20 photos, app's own code path) | 2.31 s | **0.90 s** | **2.6× faster** |
| Photo fingerprints vs GPU | – | cosine **0.99999** avg (worst 0.99995) | same results |
| Search query (text) fingerprint | 91 ms (GPU + CPU) | 91 ms (GPU + CPU, unchanged on purpose, §4) | same |
| CLIP load | 0.65 s | **20 s once** (compiles for the NPU), then **0.4 s** (cached) | – |
| App size (APK) | – | +45 MB of NPU libraries (111 MB installed) | see §6 |

**The "Built for the Snapdragon NPU" claim in the deck is now true for visual indexing.** Gemma and search text still run on the GPU.

Measured on a cool phone (thermal status 0, battery 34 °C), 20 photos × 3 rounds, with the same app build switching only CLIP's accelerator (`tools/eval/bench_clip.ps1`).

---

## 2. What it means at gallery scale

The visual pass is what makes every photo searchable by what it looks like, before Gemma reads it.

| Gallery size | GPU + CPU (115 ms/photo) | **NPU (45 ms/photo)** |
|---|---|---|
| 1,000 photos | ~1.9 min | **~45 s** |
| 10,000 photos | ~19 min | **~7.5 min** |
| 10,000 photos, CLIP time only | ~16.5 min | **~2.3 min** |

*(Real-pass rates from §1; linear projection, not measured on 10k photos.)*

**Where the rest of the time goes now:** decoding and cropping each photo (~25 ms) takes longer than CLIP itself (14 ms). Running decoding in parallel with the NPU would bring the pass to ~25 ms per photo (next step).

**Full re-index (CLIP + OCR + Gemma), 19 photos: 102 s.** Gemma reading each photo (~5 s) is still ~98% of full indexing, so the NPU change saves ~1.4 s of those 102 s on this small set. The big win is the visual pass, which matters most for **new and large galleries**: photos become visually searchable 2.6× sooner, and the GPU is left to Gemma.

---

## 3. How it works

```
clip.tflite (NPU-patched, §5)
   │
   ├─ images ─▶ LiteRT CompiledModel, Accelerator.NPU
   │             first launch: Qualcomm compiler plugin compiles all 1,166 ops into one NPU graph (~20 s)
   │             → cached in the app's cache folder (308 MB, fp16) → later launches load in ~0.4 s
   │             runs on the Hexagon V81 via FastRPC (unsigned PD), HTP "burst" performance mode
   │
   └─ text ───▶ LiteRT CompiledModel, GPU + CPU (unchanged; one session shared if images also fall back to GPU)
```

- **Fallbacks:** NPU → GPU + CPU → CPU. A phone without a supported Snapdragon NPU just uses the old path.
- **What's bundled:** LiteRT's Qualcomm dispatch + compiler plugin (V81), Qualcomm's QNN HTP runtime (`com.qualcomm.qti:qnn-runtime:2.47.0`, V81 only), and `libQnnIr.so` / `libQnnSaver.so` from the QAIRT 2.47 SDK.
- **Proof it's really the NPU** (LiteRT log): `Partitioned subgraph<0>, selected 1166 ops, from a total of 1166 ops` → `1 compiler plugins were applied successfully: Qualcomm` → `Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)`.

---

## 4. Problems found on the way (and fixes)

| # | Problem | Cause | Fix |
|---|---|---|---|
| 1 | First "NPU" run was actually **CPU** (102 ms, vectors identical to GPU) | LiteRT's Qualcomm compiler plugin needs `libQnnIr.so`, which Qualcomm's Maven package leaves out. LiteRT **silently** fell back to the CPU | Fetched `libQnnIr.so` + `libQnnSaver.so` from the QAIRT 2.47 SDK by **HTTP range requests: 5.7 MB read instead of 2.2 GB** (`tools/fetch_qnn_libs.ps1`). The benchmark now checks LiteRT's log, not just our label |
| 2 | NPU compiled, then crashed: `QnnDsp <E> Tensor ID 895 clientBuf is null` | The Qualcomm AI Hub CLIP export has one `UNPACK` that splits 197 vision tokens but uses only the first. LiteRT turns every **unused tensor into an NPU graph output**, and the runtime only gives buffers to the model's declared outputs | **Patched the model file**: declare the 196 unused tensors as outputs (append-only edit, `tools/patch_clip_npu.ps1`). Maths unchanged: GPU output is **bit-identical** before and after |
| 3 | Photos correct on the NPU, but **search text wrong** (cosine 0.58 vs GPU) | The text tower finds the end-of-text token with `ARG_MAX` over token ids. In the NPU's fp16, the end token (49407) and start token (49406) both round to 49408, so it picks the wrong position | **Text stays on GPU + CPU** (one run per search, ~90 ms). Photos, the heavy indexing work, use the NPU. Text now matches GPU exactly (cosine 1.000000) |
| 4 | One run showed text at 190 ms and decode at 51 ms | Phone was hot (thermal status 1, CPU sensors ~99 °C) right after a Gemma re-index | Waited for thermal status 0, re-measured GPU and NPU back-to-back (§1 numbers) |

---

## 5. Quality check

| Check | Result |
|---|---|
| Photo fingerprints, NPU vs GPU (20 photos) | avg cosine **0.999989**, worst 0.999952 (fp16 rounding) |
| Search-text fingerprints (10 probe queries) | **1.000000** (same GPU path) |
| Patched vs original model on GPU | **1.000000** (identical) |
| 41-query search eval | **37/41** (was 39/41). The 2 new failures come from **test-gallery changes, not the NPU** (below) |
| Unit tests | 20/20 pass |

**Why 37 and not 39:** the test folder on the phone changed after the 39/41 run:
- **V07 "man with makeup":** its only correct photo (`Screenshot_2026-08-26…`) is now in the phone's **gallery trash** (`.trashed-…`), so there is nothing correct to find.
- **N01 "sunset at the beach":** a **new, unlabelled photo** (in the new `Pictures/Kairo/Test/` folder or added at 01:00) is returned. The grader counts it as wrong because it isn't in `labels.tsv`; the top similarity is the same as before (0.272).
- "red car" and "fast food" were already failing (Report #2).

These are your photos, so I didn't restore or label them. Restoring the trashed photo and labelling the new ones would bring the eval back to a like-for-like comparison.

---

## 6. Cost

| | |
|---|---|
| APK | 109 MB now. NPU libraries add **45 MB** compressed (111 MB installed), mostly Qualcomm's on-phone NPU compiler `libQnnHtpPrepare.so` (82 MB installed). Partly offset by shipping arm64 only (the x86/32-bit copies of all libraries are gone) and by dropping the unused QNN DSP/GPU backends and the other 5 Hexagon generations |
| Phone storage | +308 MB compiled NPU graph in the app's cache (recreated automatically if cleared) |
| First launch | +20 s, once, to compile CLIP for the NPU |
| Memory | Two CLIP sessions when the NPU is used (NPU for photos, GPU for text) |
| Not measured | Battery/energy per photo. Expected to be lower on the NPU, but not verified |

**Possible later saving:** compile the NPU graph on the laptop ahead of time (AOT) and ship it; then `libQnnHtpPrepare.so` (82 MB) isn't needed. That needs a Linux machine with the QAIRT SDK.

---

## 7. Files changed

| File | Change |
|---|---|
| `embed/Clip.kt` | NPU session for photos (on-device compile + cache, burst mode), GPU + CPU session for text, fallbacks, output indices found once, accelerator reporting |
| `index/Indexer.kt` | `clipInput()` shared with the benchmark; "Visual pass: N photos in X ms on NPU" log |
| `app/build.gradle.kts` | `qnn-runtime:2.47.0`; arm64 only; keep only V81 NPU libs; no QNN DSP/GPU |
| `app/src/main/jniLibs/arm64-v8a/` | LiteRT Qualcomm dispatch + compiler plugin (V81). `libQnnIr.so` / `libQnnSaver.so` are **git-ignored** (fetched by script) |
| `debug/EvalReceiver.kt` + manifest | `EVAL_CLIP_BENCH` (times the real indexing path, returns image and text vectors); `EVAL_CONFIG --es clip npu\|gpu\|cpu\|auto` |
| `tools/eval/bench_clip.ps1` (new) | GPU vs NPU speed + vector comparison + real re-index timing |
| `tools/patch_clip_npu.ps1` (new) | The model fix (§4 #2) |
| `tools/fetch_qnn_libs.ps1` (new) | Range-request fetch of the 2 QAIRT libraries |
| `tools/setup_models.ps1`, `SETUP.md` | Patch CLIP automatically; new setup step for the NPU libraries |
| `docs/DECISIONS.md` (D46–D50), `docs/KAIRO_CHECKLIST.md`, `README.md` | Updated |

## 8. Next steps
1. **Overlap decoding with the NPU** in the visual pass (~41 → ~25 ms per photo).
2. **Label the new test photos / restore the trashed one** so the 41-query eval compares like for like again.
3. **Gemma on the NPU** when a Gemma 4 build for SM8850 exists (only E2B for SM8750 today). That is where most indexing time is.
4. Optional: AOT-compile CLIP to drop the 82 MB on-phone compiler.
5. Measure battery per 1,000 photos, GPU vs NPU.
