# Indexing A/B benchmark: sets Gemma options on the phone (debug build), clears and re-indexes the whole
# test gallery, then reports Gemma time per photo and compares what was extracted against a reference run.
#
#   powershell -ExecutionPolicy Bypass -File tools\eval\bench_index.ps1 -Label base
#   powershell -ExecutionPolicy Bypass -File tools\eval\bench_index.ps1 -Label fast -Spec -Greedy -Compact -Ref base
#
# Quality gate (vs -Ref): categories, every field value and tag overlap per photo.
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$Label,
    [switch]$Spec, [switch]$Greedy, [switch]$Compact, [int]$Vtb = 0,
    [string]$Ref,
    [int]$Expect = 0,
    [string]$Out = ""
)
$ErrorActionPreference = "Continue"
if (-not $Out) { $Out = Join-Path $PSScriptRoot "results" }
New-Item -ItemType Directory -Force $Out | Out-Null
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$sqlite = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\sqlite3.exe"
$pkg = "ai.kairo.gallery"
$remote = "/sdcard/Android/data/$pkg/files/eval"

function Broadcast($action, $id, $extra = "") {
    & $adb shell "am broadcast -p $pkg -a ai.kairo.gallery.$action --es id $id $extra" | Out-Null
    $deadline = (Get-Date).AddSeconds(120)
    do {
        Start-Sleep -Milliseconds 700
        & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
        $j = (& $adb shell "cat $remote/$id.json 2>/dev/null") -join ""
    } until ($j -or (Get-Date) -gt $deadline)
    if (-not $j) { Write-Host "timeout waiting for $action" -ForegroundColor Red; exit 1 }
    return ($j | ConvertFrom-Json)
}

if ($Expect -le 0) {
    $Expect = @(& $adb shell "ls /sdcard/Pictures/Kairo/" | Where-Object { $_ -match '\.(jpe?g|png|webp)$' }).Count
}
& $adb shell "rm -rf $remote" | Out-Null
& $adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null
Start-Sleep -Seconds 3

$tag = [guid]::NewGuid().ToString("N").Substring(0, 6)
$flags = "--ez spec $($Spec.IsPresent.ToString().ToLower()) --ez greedy $($Greedy.IsPresent.ToString().ToLower()) --ez compact $($Compact.IsPresent.ToString().ToLower()) --ei vtb $Vtb"
$cfg = Broadcast "EVAL_CONFIG" "cfg$tag" $flags
if ($cfg.error) { Write-Host "config failed: $($cfg.error)" -ForegroundColor Red; exit 1 }
# Restart the app so a fresh process loads Gemma once with the saved settings (no in-process reload).
& $adb shell am force-stop $pkg
& $adb logcat -c
& $adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null
$deadline = (Get-Date).AddSeconds(120)
do {
    Start-Sleep -Seconds 2
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    $ready = @(& $adb logcat -d -s KairoLlm:I | Where-Object { $_ -match "ready on .*\[(.*)\]" })
} until ($ready -or (Get-Date) -gt $deadline)
if (-not $ready) { Write-Host "Gemma did not load within 120 s" -ForegroundColor Red; exit 1 }
$null = $ready[-1] -match "\[(.*)\]"
if ($Matches[1] -ne $cfg.tuning) { Write-Host "Loaded with [$($Matches[1])], expected [$($cfg.tuning)]" -ForegroundColor Red; exit 1 }
Write-Host "config: $($cfg.tuning) | $($ready[-1] -replace '^.*KairoLlm: ', '')"
Start-Sleep -Seconds 5  # let the app's own start-up indexing check finish

& $adb logcat -c
$t0 = Get-Date
Broadcast "EVAL_CLEAR" "clr$tag" | Out-Null
Write-Host "re-indexing $Expect photos..."
do {
    Start-Sleep -Seconds 4
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    $log = @(& $adb logcat -d -s KairoIndexer:I)
    $done = @($log | Where-Object { $_ -match "Indexed .* \((done|ocr_only|failed)\)" }).Count
} until ($done -ge $Expect -or (Get-Date) -gt $t0.AddMinutes(20))
$wall = [int]((Get-Date) - $t0).TotalSeconds

# --- timing ---
$rows = foreach ($l in $log) {
    if ($l -match "gemma (\S+) kind=(\w+) ms=(\d+) in=(\S+) out=(\S+) outRate=(\S+)") {
        [pscustomobject]@{ name = $Matches[1]; kind = $Matches[2]; ms = [int]$Matches[3]
            inTok = [int]("0" + $Matches[4] -replace 'null', ''); outTok = [int]("0" + $Matches[5] -replace 'null', ''); rate = [double]$Matches[6] }
    }
}
$bad = @($log | Where-Object { $_ -match "\((ocr_only|failed)\)" }).Count
$wrong = @($log | Where-Object { $_ -match "gemma .* \[(.*)\]" -and $Matches[1] -ne $cfg.tuning }).Count
if ($wrong -gt 0) { Write-Host "WARNING: $wrong photos were indexed with different settings than [$($cfg.tuning)] - results invalid" -ForegroundColor Red }
$timeouts = @(& $adb logcat -d -s KairoLlm:W | Where-Object { $_ -match "exceeded" }).Count
if ($timeouts -gt 0) { Write-Host "WARNING: $timeouts Gemma calls hit the timeout" -ForegroundColor Yellow }
Write-Host ""
Write-Host "=== $Label  [$($cfg.tuning)]  photos=$done  wall=${wall}s  invalid/failed=$bad"
$summary = $rows | Group-Object kind | ForEach-Object {
    $g = $_.Group
    [pscustomobject]@{
        kind = $_.Name; n = $g.Count
        gemma_ms_avg = [int]($g | Measure-Object ms -Average).Average
        gemma_ms_max = ($g | Measure-Object ms -Maximum).Maximum
        in_tok_avg = [int]($g | Measure-Object inTok -Average).Average
        out_tok_avg = [int]($g | Measure-Object outTok -Average).Average
        out_tok_per_s = [Math]::Round(($g | Measure-Object rate -Average).Average, 1)
    }
}
$summary | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
$totalGemma = ($rows | Measure-Object ms -Sum).Sum
Write-Host ("total Gemma time: {0:N1} s for {1} photos" -f ($totalGemma / 1000), $rows.Count)

# --- extracted data ---
$db = Join-Path $env:TEMP "kairo_bench_$Label.db"
cmd /c "`"$adb`" exec-out run-as $pkg cat databases/kairo_index.db > `"$db`""
$json = & $sqlite -json $db "select name, category, description, tags, fields_json, status, index_ms from images order by name"
$items = ($json -join "`n") | ConvertFrom-Json
$dump = [pscustomobject]@{ label = $Label; tuning = $cfg.tuning; wall_s = $wall; timing = $rows; summary = $summary; items = $items }
$dumpFile = Join-Path $Out "index-$Label.json"
$dump | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 $dumpFile
Write-Host "saved $dumpFile"

# --- quality gate vs reference ---
if ($Ref) { & (Join-Path $PSScriptRoot "compare_index.ps1") -A $Ref -B $Label -Dir $Out }
