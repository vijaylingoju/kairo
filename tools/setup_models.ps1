# Kairo - one-command phone setup (Windows PowerShell).
# Checks adb + phone + app, then pushes the two AI models and (optionally) test photos, and confirms both models load.
#
#   powershell -ExecutionPolicy Bypass -File tools\setup_models.ps1 `
#       -Gemma "D:\models\gemma-4-E4B-it.litertlm" `
#       -Clip  "D:\models\openai_clip-tflite-float.zip" `
#       [-Photos "D:\my_test_photos"] [-SyntheticDocs] [-Install] [-VerifyHash]
#
# -Clip accepts the Qualcomm zip or the extracted openai_clip.tflite.  See SETUP.md for details.
[CmdletBinding()]
param(
    [string]$Gemma,
    [string]$Clip,
    [string]$Photos,
    [switch]$SyntheticDocs,
    [switch]$Install,
    [switch]$VerifyHash
)
# Continue, not Stop: in Windows PowerShell 5.1 any stderr text from adb (e.g. progress) would abort the script.
# Every step checks its own result and calls Fail with a clear message instead.
$ErrorActionPreference = "Continue"
$pkg = "ai.kairo.gallery"
$remoteDir = "/sdcard/Android/data/$pkg/files"
$repo = Split-Path -Parent $PSScriptRoot

# Known-good files (size in bytes, SHA-256 from Hugging Face / Qualcomm).
$known = @{
    "gemma-4-E4B-it.litertlm" = @{ Size = 3659530240; Sha = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0" }
    "gemma-4-E2B-it.litertlm" = @{ Size = 2588147712; Sha = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c" }
    "openai_clip.tflite"      = @{ Size = 598730640;  Sha = $null }
}

function Step($msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    OK  $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "    !!  $msg" -ForegroundColor Yellow }
function Fail($msg) { Write-Host "    XX  $msg" -ForegroundColor Red; exit 1 }

# --- 1. adb --------------------------------------------------------------------------------------
Step "Finding adb"
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
                     "$env:ANDROID_HOME\platform-tools\adb.exe", "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe")) {
        if ($c -and (Test-Path $c)) { $adb = $c; break }
    }
}
if (-not $adb) { Fail "adb not found. Install Android Studio (it includes the SDK platform-tools) - see SETUP.md step 1." }
Ok $adb

# --- 2. phone ------------------------------------------------------------------------------------
Step "Checking the phone"
$lines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() })
$ready = @($lines | Where-Object { $_ -match "\sdevice$" })
if ($lines -match "unauthorized") { Fail "Phone shows 'unauthorized': unlock it and tap 'Allow' on the USB debugging prompt, then re-run." }
if ($ready.Count -eq 0) { Fail "No phone found. Connect by USB with USB debugging on (SETUP.md step 3). 'adb devices' must list it as 'device'." }
if ($ready.Count -gt 1) { Fail "More than one device connected. Unplug the others (or emulators) and re-run." }
$model = (& $adb shell getprop ro.product.model).Trim()
$soc = (& $adb shell getprop ro.soc.model).Trim()
$ramKb = [long]((& $adb shell "grep MemTotal /proc/meminfo") -replace '[^\d]', '')
$ramGb = [Math]::Round($ramKb / 1MB, 1)
Ok "$model ($soc), $ramGb GB RAM, Android $((& $adb shell getprop ro.build.version.release).Trim())"

# --- 3. app ----------------------------------------------------------------------------------------
Step "Checking the Kairo app"
$installed = (& $adb shell pm list packages $pkg) -match "package:$pkg$"
if (-not $installed -and $Install) {
    Write-Host "    Building and installing the debug app (first build can take several minutes)..."
    $jbr = "C:\Program Files\Android\Android Studio\jbr"
    if (-not $env:JAVA_HOME -and (Test-Path $jbr)) { $env:JAVA_HOME = $jbr }
    Push-Location $repo
    try { & .\gradlew.bat installDebug --console=plain -q; if ($LASTEXITCODE -ne 0) { Fail "gradlew installDebug failed (see output above)." } }
    finally { Pop-Location }
    $installed = (& $adb shell pm list packages $pkg) -match "package:$pkg$"
}
if (-not $installed) { Fail "Kairo is not installed. In Android Studio press Run (SETUP.md step 4), or re-run this script with -Install." }
Ok "installed"
# Opening the app once creates its model folder.
& $adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null
Start-Sleep -Seconds 3
& $adb shell mkdir -p $remoteDir | Out-Null

# --- 4. model files on the laptop ------------------------------------------------------------------
function Check-Local($path, $expectName) {
    $f = Get-Item $path
    $k = $known[$expectName]
    if ($k -and $f.Length -ne $k.Size) {
        Warn "$($f.Name) is $($f.Length) bytes, expected $($k.Size). The download may be incomplete - re-download it."
        return $false
    }
    if ($VerifyHash -and $k -and $k.Sha) {
        Write-Host "    Checking SHA-256 of $($f.Name) (about 30 s)..."
        $h = (Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLower()
        if ($h -ne $k.Sha) { Warn "SHA-256 mismatch for $($f.Name) - the file is corrupt, re-download it."; return $false }
        Ok "SHA-256 matches"
    }
    return $true
}

$toPush = @()
if ($Gemma) {
    Step "Gemma model"
    if (-not (Test-Path $Gemma)) { Fail "Not found: $Gemma" }
    $g = Get-Item $Gemma
    if ($g.Extension -ne ".litertlm") { Fail "Gemma must be the .litertlm file itself (not a zip). Got: $($g.Name)" }
    if ($g.Name -like "*E4B*" -and $ramGb -lt 8) { Warn "This phone has $ramGb GB RAM; Gemma 4 E4B needs ~8 GB+. Consider gemma-4-E2B-it.litertlm." }
    if (-not (Check-Local $g.FullName $g.Name)) { Fail "Fix the Gemma file first." }
    Ok "$($g.Name) ($([Math]::Round($g.Length / 1e9, 2)) GB)"
    $toPush += @{ Local = $g.FullName; Remote = "$remoteDir/$($g.Name)" }
}
if ($Clip) {
    Step "CLIP model"
    if (-not (Test-Path $Clip)) { Fail "Not found: $Clip" }
    $c = Get-Item $Clip
    if ($c.Extension -eq ".zip") {
        $tmp = Join-Path $env:TEMP "kairo_clip_unzip"
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Write-Host "    Extracting $($c.Name)..."
        Expand-Archive -Path $c.FullName -DestinationPath $tmp
        $c = Get-ChildItem $tmp -Recurse -Filter "openai_clip.tflite" | Select-Object -First 1
        if (-not $c) { Fail "openai_clip.tflite not found inside the zip." }
    }
    if ($c.Extension -ne ".tflite") { Fail "CLIP must be openai_clip.tflite or its zip. Got: $($c.Name)" }
    if (-not (Check-Local $c.FullName "openai_clip.tflite")) { Fail "Fix the CLIP file first." }
    # Snapdragon NPU fix: expose the export's unused tensors as outputs (see tools/patch_clip_npu.ps1).
    $patched = Join-Path $env:TEMP "kairo_clip_npu.tflite"
    Write-Host "    Preparing CLIP for the NPU..."
    & (Join-Path $PSScriptRoot "patch_clip_npu.ps1") -In $c.FullName -Out $patched
    if (-not (Test-Path $patched)) { Fail "Could not prepare CLIP for the NPU." }
    Ok "$($c.Name) ($([Math]::Round($c.Length / 1e6)) MB), NPU-ready -> will be pushed as clip.tflite"
    $toPush += @{ Local = $patched; Remote = "$remoteDir/clip.tflite" }  # the app looks for exactly this name
}

# --- 5. push --------------------------------------------------------------------------------------
if ($toPush.Count -gt 0) {
    Step "Pushing models to the phone"
    $needMb = ($toPush | ForEach-Object { (Get-Item $_.Local).Length } | Measure-Object -Sum).Sum / 1MB
    $freeKb = [long](((& $adb shell "df -k /sdcard | tail -1") -split '\s+')[3])
    if ($freeKb / 1KB -lt $needMb + 500) { Fail "Not enough space on the phone: need ~$([int]$needMb) MB, free $([int]($freeKb / 1KB)) MB." }
    foreach ($p in $toPush) {
        $localSize = (Get-Item $p.Local).Length
        $remoteSize = (& $adb shell "stat -c %s '$($p.Remote)' 2>/dev/null") -join ""
        if ("$remoteSize".Trim() -eq "$localSize") { Ok "$($p.Remote) already on the phone (same size) - skipped"; continue }
        Write-Host "    adb push $([IO.Path]::GetFileName($p.Local)) -> $($p.Remote)  (a 3.6 GB file takes ~2 min over USB 3)"
        & $adb push $p.Local $p.Remote
        if ($LASTEXITCODE -ne 0) { Fail "adb push failed." }
    }
}

# --- 6. photos ------------------------------------------------------------------------------------
if ($Photos -or $SyntheticDocs) {
    Step "Test photos -> Pictures/Kairo"
    & $adb shell mkdir -p /sdcard/Pictures/Kairo | Out-Null
    $files = @()
    if ($Photos) { $files += Get-ChildItem $Photos -File | Where-Object { $_.Extension -match '^\.(jpe?g|png|webp|heic)$' } }
    if ($SyntheticDocs) {
        $out = Join-Path $env:TEMP "kairo_test_docs"
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "eval\make_test_docs.ps1") -Out $out | Out-Null
        $files += Get-ChildItem $out -File -Filter *.jpg
    }
    foreach ($f in $files) {
        & $adb push $f.FullName "/sdcard/Pictures/Kairo/$($f.Name)" | Out-Null
        & $adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Pictures/Kairo/$($f.Name)" | Out-Null
    }
    Ok "$($files.Count) photos pushed"
}

# --- 7. verify ------------------------------------------------------------------------------------
Step "Files in the app's model folder"
& $adb shell ls -la $remoteDir | Where-Object { $_ -match "\.(litertlm|tflite)$" } | ForEach-Object { "    $_" }
$remoteFiles = (& $adb shell ls $remoteDir) -join " "
if ($remoteFiles -notmatch "\.litertlm") { Warn "No Gemma (.litertlm) on the phone yet - search works without it, but no categories/answers." }
if ($remoteFiles -notmatch "clip\.tflite") { Warn "No clip.tflite on the phone yet - visual search is off." }

Step "Restarting Kairo and waiting for the models to load (up to 60 s)"
& $adb logcat -c
& $adb shell am force-stop $pkg
& $adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null
$deadline = (Get-Date).AddSeconds(60)
$gemmaOk = $false; $clipOk = $false
do {
    Start-Sleep -Seconds 3
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null   # a locked/sleeping phone freezes the app
    $log = & $adb logcat -d -s KairoLlm:I KairoClip:I
    if ($log -match "ready on") {
        $gemmaOk = [bool]($log | Where-Object { $_ -match "KairoLlm.*ready on" })
        $clipOk = [bool]($log | Where-Object { $_ -match "KairoClip.*ready on" })
    }
} until (($gemmaOk -or $remoteFiles -notmatch "\.litertlm") -and ($clipOk -or $remoteFiles -notmatch "clip\.tflite") -or (Get-Date) -gt $deadline)
$log | Where-Object { $_ -match "ready on|failed|missing" } | ForEach-Object { "    $($_ -replace '^.*?(Kairo\w+: )', '$1')" }
if ($gemmaOk) { Ok "Gemma loaded" } elseif ($remoteFiles -match "\.litertlm") { Warn "Gemma did not report ready in 60 s - open the app and check the top status line (first load can take ~10 s, keep the screen on)." }
if ($clipOk) { Ok "CLIP loaded" } elseif ($remoteFiles -match "clip\.tflite") { Warn "CLIP did not report ready yet - it loads on first indexing/search; check the app's second status line." }

Write-Host ""
Write-Host "Done. On the phone: tap 'Grant photo access' -> 'Allow all', then try a search like 'movie tickets'." -ForegroundColor Green
