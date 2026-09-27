# Kairo: setup on a new laptop and phone

This guide takes you from a fresh Windows laptop and an Android phone to a working Kairo with on-device AI search.
Allow **about 30–45 minutes**, most of it downloads.

> **TL;DR** (if you already have Android Studio and the two model files):
> 1. Clone the repo, open it in Android Studio, press **Run ▶** with the phone connected.
> 2. `powershell -ExecutionPolicy Bypass -File tools\setup_models.ps1 -Gemma <path to .litertlm> -Clip <path to openai_clip-tflite-float.zip>`
> 3. On the phone: **Grant photo access → Allow all**, put photos in `Pictures/Kairo`, search.

---

## 0. What you need

| Item | Details |
|---|---|
| Laptop | Windows 10/11, 16 GB RAM recommended, about **15 GB free disk** (Android Studio + Gradle + models) |
| Phone | Android **12 or newer** (minSdk 31). **8 GB RAM or more** for Gemma 4 E4B (use E2B on 6–8 GB phones). About **5 GB free storage** |
| Cable | USB cable that supports **data** (not charge-only) |
| Software | Android Studio (includes the JDK and `adb`), Git |
| Models (not in git, too big) | ① **Gemma 4** `.litertlm` (the "brain": categories, fields, understanding questions). ② **CLIP** `openai_clip.tflite` (the "eyes": visual search) |

**Tested on:** iQOO 15 (Snapdragon 8 Elite Gen 5, 16 GB) with Gemma 4 E4B, and OnePlus 13R (Snapdragon 8 Gen 3) with Gemma 4 E2B.

Everything runs **offline on the phone**. The app has no internet permission, so it can't download the models itself; you push them over USB.

---

## 1. Install Android Studio and Git

1. Install **Android Studio** (latest stable) from developer.android.com/studio. Keep the default components; they include the Android SDK, **platform-tools (adb)** and a bundled JDK (JBR).
2. Install **Git** from git-scm.com.
3. Open Android Studio once and finish the setup wizard (it downloads the SDK).

Check that `adb` exists:
```bash
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" version
```

---

## 2. Get the code

```bash
git clone https://github.com/vijaylingoju/kairo.git
```
```bash
cd kairo
```
```bash
git checkout fix/improvements
```
> `fix/improvements` has the latest tested code (evaluation fixes, Gemma auto-pick, this guide). Use `main` once it's merged.

Open it in Android Studio: **File → Open → select the `kairo` folder**. Wait for **Gradle sync** to finish (the first sync downloads Gradle and the libraries, 5–10 minutes). If Studio suggests upgrading the Android Gradle Plugin, you can accept it.

---

## 3. Prepare the phone

1. **Developer options:** Settings → About phone → tap **Build number** 7 times. On iQOO/vivo: Settings → About phone → Software version → tap **Software version** 7 times.
2. **USB debugging:** Settings → System → Developer options → turn on **USB debugging**.
   - iQOO/vivo, Xiaomi, OnePlus: also turn on **"USB debugging (Security settings)"** / **"Install via USB"** if present, otherwise installs fail.
3. Connect the phone by USB. On the phone, choose **File transfer** mode if asked, and tap **Allow** on the "Allow USB debugging?" prompt (tick "Always allow").
4. Check:
   ```bash
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
   ```
   You should see one line ending in **`device`**. `unauthorized` means you need to accept the prompt on the phone.

---

## 4. Build and install the app

**First, once: fetch the two Snapdragon NPU libraries** (not stored in git; ~6 MB downloaded from Qualcomm's public QAIRT SDK, only the two files are read out of the 2.2 GB zip):
```bash
powershell -ExecutionPolicy Bypass -File tools\fetch_qnn_libs.ps1
```
This puts `libQnnIr.so` and `libQnnSaver.so` in `app/src/main/jniLibs/arm64-v8a/`. Skip it and the app still works, with CLIP on the GPU instead of the NPU (about 6x slower visual indexing).

In Android Studio, select your phone in the device dropdown and press **Run ▶**. The app installs and opens.
It will say **"Model missing"**. That's expected until step 6.

*(Command-line alternative:)*
```bash
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat installDebug
```

---

## 5. Get the two model files

Put them in a folder **outside OneDrive/Dropbox** (e.g. `C:\kairo-models`), because synced folders try to upload several GB.

### ① Gemma 4 (pick one)
| Model | When | File | Size | Link |
|---|---|---|---|---|
| **Gemma 4 E4B** (recommended) | Phone with **8 GB+ RAM** | `gemma-4-E4B-it.litertlm` | 3.66 GB (3,659,530,240 bytes) | huggingface.co/litert-community/gemma-4-E4B-it-litert-lm → Files |
| Gemma 4 E2B (lighter) | 6–8 GB RAM, or faster indexing | `gemma-4-E2B-it.litertlm` | 2.59 GB (2,588,147,712 bytes) | huggingface.co/litert-community/gemma-4-E2B-it-litert-lm → Files |

- Download the file named **exactly** as above. **Not** the `-gpu`, `-web`, `_qualcomm_…` or `_Google_Tensor_…` variants.
- Direct download URL pattern: `https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm`
- License: Gemma terms (Apache 2.0 for this build). Downloading it doesn't require a login.
- **Don't zip it** to share it: it doesn't compress, and the app needs the `.litertlm` file itself.

### ② CLIP (visual search)
- File: `openai_clip-tflite-float.zip` (388 MB) from Qualcomm AI Hub:
  `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/openai_clip/releases/v0.63.0/openai_clip-tflite-float.zip`
  (listed on huggingface.co/qualcomm/OpenAI-Clip, MIT license)
- Inside is `openai_clip-tflite-float\openai_clip.tflite` (599 MB). The setup script extracts it for you.

> **Not needed:** `bpe_simple_vocab_16e6.txt(.gz)`. The CLIP vocabulary is already inside the app (`app/src/main/assets/clip_bpe_vocab.txt`).

### Slow or unstable network (hackathon Wi-Fi)?
- **Best:** copy the files from a teammate with a pen drive or local share.
- Or use your phone's **USB tethering** or hotspot for the laptop.
- Resume a broken download (run it again after a drop; `-C -` continues where it stopped):
  ```bash
  curl.exe -L -C - --retry 10 -o gemma-4-E4B-it.litertlm https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm
  ```
- If each connection is throttled (common on shared Wi-Fi: we saw ~65 KB/s per connection), download in **16 parallel pieces**. This took us from 90 KB/s to about **5 MB/s**. Safe to re-run if it stops:
  ```bash
  powershell -ExecutionPolicy Bypass -File tools\download_parallel.ps1 -Url "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm" -Out "C:\kairo-models\gemma-4-E4B-it.litertlm"
  ```
  The same works for the CLIP zip (use its URL and `-Out "C:\kairo-models\openai_clip-tflite-float.zip"`).

**Check the file size** after downloading. A wrong size means an incomplete download.

---

## 6. Push the models to the phone (one command)

With the phone connected and the app installed (step 4), run from the repo folder:

```bash
powershell -ExecutionPolicy Bypass -File tools\setup_models.ps1 -Gemma "C:\kairo-models\gemma-4-E4B-it.litertlm" -Clip "C:\kairo-models\openai_clip-tflite-float.zip"
```

The script:
1. finds `adb`
2. checks the phone (model, RAM, Android version) and warns if E4B is too heavy for its RAM
3. checks the app is installed (add `-Install` to build and install it for you)
4. checks the model file sizes (add `-VerifyHash` to also check SHA-256, about 30 s)
5. extracts CLIP from the zip and prepares it for the NPU (`tools/patch_clip_npu.ps1`, a few seconds)
6. checks free space on the phone
7. pushes both files (skipping any already there)
8. restarts the app and waits until both models report **ready**

Expected ending (the very first NPU load compiles CLIP for the NPU, ~20 s once, then ~0.4 s):
```
    KairoClip: CLIP (text) ready on GPU+CPU (loaded in ~700 ms)
    KairoLlm: gemma-4-E4B-it ready on GPU (loaded in ~5000-9000 ms)
    OK  Gemma loaded
    OK  CLIP loaded
```

Optional extras:
- `-Photos "C:\my_test_photos"` pushes your test photos into `Pictures/Kairo`.
- `-SyntheticDocs` adds 5 clearly fake test documents (PAN, bill, UPI receipt, train ticket, boarding pass) for testing answers like "what's my PNR".

<details>
<summary><b>Manual alternative (without the script)</b></summary>

Use **PowerShell**, not Git Bash (Git Bash rewrites `/sdcard/...` paths). Unzip CLIP first (right-click → Extract All), then prepare it for the NPU (the unpatched file still works, on the GPU only):

```bash
powershell -ExecutionPolicy Bypass -File tools\patch_clip_npu.ps1 -In "C:\kairo-models\openai_clip-tflite-float\openai_clip.tflite" -Out "C:\kairo-models\clip_npu.tflite"
```
```bash
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" push "C:\kairo-models\clip_npu.tflite" /sdcard/Android/data/ai.kairo.gallery/files/clip.tflite
```
```bash
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" push "C:\kairo-models\gemma-4-E4B-it.litertlm" /sdcard/Android/data/ai.kairo.gallery/files/gemma-4-E4B-it.litertlm
```
```bash
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell ls -la /sdcard/Android/data/ai.kairo.gallery/files/
```
Rules the app relies on:
- CLIP **must** be named exactly `clip.tflite`.
- Gemma can have any name ending in `.litertlm`. If several are present, the app picks E4B, then E2B, then anything else.
- Both go in `/sdcard/Android/data/ai.kairo.gallery/files/`. **Uninstalling the app deletes this folder**; reinstalling with Run ▶ keeps it.

Then force-stop and reopen the app.
</details>

---

## 7. First run on the phone

1. Open **Kairo**. The top status lines should read:
   - `gemma-4-E4B-it ready on GPU (loaded in … ms)`
   - `CLIP ready: images on NPU, text on GPU+CPU` (it says `–` for a part until it's first used)
2. Tap **Grant photo access** → choose **Allow all**. Don't pick "Select photos", or new photos won't be seen.
3. Add photos to the folder **`Pictures/Kairo`** (the demo only indexes this folder):
   - Files app → move/copy photos into Internal storage → Pictures → Kairo, or
   - `-Photos` in the setup script, or `adb push <file> /sdcard/Pictures/Kairo/`
4. Indexing starts on its own (or tap **Index now**):
   - Pass 1, **"Visual index"**: about 0.1–0.3 s per photo. Photos are searchable straight away.
   - Pass 2, **"Indexing"**: Gemma reads each photo, about 5 s for a normal photo and 7–12 s for a ticket or document. Labels change from "reading…" to a category.
5. **Keep the screen on** during the first indexing, and allow background activity:
   Settings → Apps → Kairo → Battery → **Allow background activity** / **No restrictions**.
   Android freezes apps when the screen is off, which pauses indexing.
6. Optional: turn on **"Also index new screenshots"**.

### Try these searches
| Search | Expect |
|---|---|
| `movie tickets` | Movie ticket screenshots (instant, ~0.1 s) |
| `what's my PNR` | Answer card `PNR: …` with Copy |
| `my PAN number` | Answer card `PAN number: …` |
| `golden retriever` / `ice cream` / `food photos` | Matching photos (visual search) |
| `sunset at the beach` (if you have none) | "No matching images", which is correct |
| `సినిమా టికెట్లు` / `कुत्ता` | Telugu/Hindi work too (Gemma translates) |

Testing tip: **"Clear all data & start fresh"** wipes the index (not your photos or models) and re-indexes everything.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| **"Model missing → adb push it to …/model.litertlm"** | No `.litertlm` file in the app folder | Step 6. Check with `adb shell ls -la /sdcard/Android/data/ai.kairo.gallery/files/` |
| **"CLIP missing → adb push it to …/clip.tflite"** | CLIP not pushed or wrong name | Push `openai_clip.tflite` **as `clip.tflite`** |
| Pushed the model but still "missing" | Pushed the **zip**, a wrong folder, or used **Git Bash** | Use PowerShell or the script; the target must be exactly `/sdcard/Android/data/ai.kairo.gallery/files/` |
| "Model failed to load" / app crashes on load | Incomplete download, or not enough RAM | Check the file size (step 5), `-VerifyHash`; use **E2B** on phones under 8 GB RAM |
| `adb devices` shows nothing | Charge-only cable, USB debugging off, or driver | Change cable/port, redo step 3; on Windows install the phone maker's USB driver if needed |
| `unauthorized` | Prompt not accepted | Unlock the phone, accept "Allow USB debugging", or revoke authorizations in Developer options and reconnect |
| Install fails: `INSTALL_FAILED_USER_RESTRICTED` | Vendor security | Turn on "Install via USB" / "USB debugging (Security settings)" (Xiaomi/iQOO/OnePlus) |
| Gradle: `JAVA_HOME is not set` | Command-line build | `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` or build from Android Studio |
| Everything shows **"other"** / no answers | Gemma not loaded yet (categories come from Gemma) | Wait for "ready on GPU", keep the screen on, tap **Index now** |
| Indexing seems stuck | Screen turned off, so the app is frozen | Keep the screen on, open the app; it resumes where it stopped |
| Nothing gets indexed | Photos aren't in `Pictures/Kairo`, or access is "Selected photos" | Move photos there; grant **Allow all** |
| First Gemma load is slow (~10 s) | GPU program cache being built | Normal once; later loads take ~5 s |
| Model folder empty after reinstall | App was **uninstalled** (Android deletes its folder) | Push the models again (the script skips files already there) |

Still stuck? Send the output of:
```bash
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -d -s KairoLlm KairoClip KairoIndexer KairoSearch
```

---

## 9. Where things live

| What | Where |
|---|---|
| Models on the phone | `/sdcard/Android/data/ai.kairo.gallery/files/` (`clip.tflite`, `gemma-4-E4B-it.litertlm`) |
| Photos that get indexed | `/sdcard/Pictures/Kairo/` (+ new screenshots if the toggle is on) |
| Search index | App-private SQLite `kairo_index.db` (clear it with "Clear all data & start fresh") |
| Model/push setup script | `tools/setup_models.ps1` |
| Evaluation harness (41 test searches) | `tools/eval/` (see `docs/reports/REPORT-02_…md`) |
| How it works and why | `README.md`, `docs/DECISIONS.md`, `docs/KAIRO_CHECKLIST.md` |

### What runs where (for reference)
| Stage | Runs on |
|---|---|
| OCR (ML Kit) | CPU |
| CLIP visual fingerprint of each photo (indexing) | **NPU** (Hexagon, ~14 ms/photo); falls back to GPU + CPU |
| CLIP fingerprint of a search query | GPU + CPU (the text tower is wrong in the NPU's fp16, see `embed/Clip.kt`) |
| Gemma 4 (reading photos, understanding questions) | GPU (falls back to CPU) |
| Search (SQLite FTS + CLIP similarity) | CPU |
