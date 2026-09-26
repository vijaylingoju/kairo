# CLIP accelerator benchmark (debug build): picks CLIP's accelerator on the phone, restarts the app, then times
# CLIP on every gallery photo exactly as the visual indexing pass does, plus query-text embedding.
#
#   powershell -ExecutionPolicy Bypass -File tools\eval\bench_clip.ps1 -Label gpu -Accel gpu
#   powershell -ExecutionPolicy Bypass -File tools\eval\bench_clip.ps1 -Label npu -Accel npu -Ref gpu -ColdCache
#   powershell -ExecutionPolicy Bypass -File tools\eval\bench_clip.ps1 -Label npu-idx -Accel npu -Reindex
#
# -Ref       compares this run's image vectors with an earlier run (cosine per photo; 1.0 = identical).
# -ColdCache deletes the app's cache first, so the load time includes compiling CLIP for the NPU.
# -Reindex   also clears the index and times the real visual pass ("Visual pass: ..." log line).
# -WaitAll   with -Reindex, waits for the Gemma pass too (needed before running the search eval).
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$Label,
    [ValidateSet("auto", "npu", "gpu", "cpu")] [string]$Accel = "auto",
    [int]$Rounds = 3,
    [string]$Ref,
    [switch]$ColdCache,
    [switch]$Reindex,
    [switch]$WaitAll,
    [string]$Out = ""
)
$ErrorActionPreference = "Continue"
if (-not $Out) { $Out = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "results" }
New-Item -ItemType Directory -Force $Out | Out-Null
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "ai.kairo.gallery"
$remote = "/sdcard/Android/data/$pkg/files/eval"

function Broadcast($action, $id, $extra = "", $timeoutSec = 120) {
    & $adb shell "am broadcast -p $pkg -a ai.kairo.gallery.$action --es id $id $extra" | Out-Null
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    do {
        Start-Sleep -Milliseconds 700
        & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
        $j = (& $adb shell "cat $remote/$id.json 2>/dev/null") -join ""
    } until ($j -or (Get-Date) -gt $deadline)
    if (-not $j) { Write-Host "timeout waiting for $action" -ForegroundColor Red; exit 1 }
    return ($j | ConvertFrom-Json)
}

function WaitIdle() {
    # Gemma loads on the GPU at start-up and pending photos get indexed; wait so neither competes with CLIP.
    $deadline = (Get-Date).AddSeconds(180)
    do {
        Start-Sleep -Seconds 3
        $s = Broadcast "EVAL_STATUS" ("st" + [guid]::NewGuid().ToString("N").Substring(0, 6))
    } until (($s.llmReady -and -not $s.running) -or (Get-Date) -gt $deadline)
    Start-Sleep -Seconds 3
    return $s
}

& $adb shell "rm -rf $remote" | Out-Null
& $adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null
Start-Sleep -Seconds 3
$tag = [guid]::NewGuid().ToString("N").Substring(0, 6)
$cfg = Broadcast "EVAL_CONFIG" "cfg$tag" "--es clip $Accel"
if ($cfg.error) { Write-Host "config failed: $($cfg.error)" -ForegroundColor Red; exit 1 }

# Fresh process so CLIP loads once with the new accelerator.
& $adb shell am force-stop $pkg
if ($ColdCache) { & $adb shell "run-as $pkg sh -c 'rm -rf cache/*'" | Out-Null; Write-Host "app cache cleared (NPU graph will be recompiled)" }
& $adb logcat -c
& $adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null
$st = WaitIdle
Write-Host "app idle: llm=$($st.llmReady) running=$($st.running) indexed=$($st.indexed)"

Write-Host "benchmarking CLIP ($Accel), $Rounds rounds..."
$r = Broadcast "EVAL_CLIP_BENCH" "clip$tag" "--ei rounds $Rounds" 900
if ($r.error) { Write-Host "bench failed: $($r.error)" -ForegroundColor Red; exit 1 }

Write-Host ""
Write-Host "=== $Label  images on $($r.accelerator), text on $($r.textAccelerator)  load=$($r.loadMs) ms  photos=$($r.photos) x $($r.rounds) rounds"
$table = foreach ($k in "decodeMs", "preprocessMs", "runMs", "perPhotoMs", "text1Ms", "text5Ms") {
    $v = $r.$k
    [pscustomobject]@{ step = $k; avg = [Math]::Round([double]$v.avg, 1); p50 = $v.p50; p90 = $v.p90; min = $v.min; max = $v.max }
}
$table | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
$npuLog = @(& $adb logcat -d | Select-String -Pattern "KairoClip|LiteRt|Qnn|QNN|NPU" | Select-Object -Last 25)

$file = Join-Path $Out "clip-$Label.json"
[pscustomobject]@{ label = $Label; accel = $Accel; result = $r; log = @($npuLog | ForEach-Object { "$_" }) } |
    ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 $file
Write-Host "saved $file"

function Compare-Vectors($a, $b, $what) {
    if (-not $a -or -not $b) { Write-Host "no $what vectors to compare"; return }
    $cos = foreach ($p in $a.PSObject.Properties) {
        $vb = $b.($p.Name)
        if (-not $vb) { continue }
        $va = $p.Value
        $dot = 0.0; $na = 0.0; $nb = 0.0
        for ($i = 0; $i -lt $va.Count; $i++) { $dot += $va[$i] * $vb[$i]; $na += $va[$i] * $va[$i]; $nb += $vb[$i] * $vb[$i] }
        [pscustomobject]@{ item = $p.Name; cosine = [Math]::Round($dot / [Math]::Sqrt($na * $nb), 6) }
    }
    $m = $cos | Measure-Object cosine -Average -Minimum
    Write-Host ("{0} vectors vs {1}: avg cosine {2:N6}, worst {3:N6} ({4} items)" -f $what, $Ref, $m.Average, $m.Minimum, $m.Count)
    $cos | Sort-Object cosine | Select-Object -First 3 | Format-Table -AutoSize | Out-String | Write-Host
}

if ($Ref) {
    $refResult = (Get-Content -Raw (Join-Path $Out "clip-$Ref.json") | ConvertFrom-Json).result
    Compare-Vectors $refResult.vectors $r.vectors "image"
    Compare-Vectors $refResult.textVectors $r.textVectors "text"
}

if ($Reindex) {
    & $adb logcat -c
    $t0 = Get-Date
    Broadcast "EVAL_CLEAR" "clr$tag" | Out-Null
    do {
        Start-Sleep -Seconds 1
        & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
        $line = @(& $adb logcat -d -s KairoIndexer:I | Where-Object { $_ -match "Visual pass:" })
    } until ($line -or (Get-Date) -gt $t0.AddMinutes(10))
    Write-Host ($line[-1] -replace '^.*KairoIndexer: ', 'real indexing: ')
    if ($WaitAll) {
        $expect = @(& $adb shell "ls /sdcard/Pictures/Kairo/" | Where-Object { $_ -match '\.(jpe?g|png|webp)$' }).Count
        do {
            Start-Sleep -Seconds 4
            & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
            $done = @(& $adb logcat -d -s KairoIndexer:I | Where-Object { $_ -match "Indexed .* \((done|ocr_only|failed)\)" }).Count
        } until ($done -ge $expect -or (Get-Date) -gt $t0.AddMinutes(20))
        Write-Host ("full re-index finished: {0} photos in {1:N0} s" -f $done, ((Get-Date) - $t0).TotalSeconds)
    }
}
