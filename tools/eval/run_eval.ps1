# Runs every scenario in scenarios.tsv against the app on the connected phone (debug build)
# and scores the results against labels.tsv.
# Usage: powershell -ExecutionPolicy Bypass -File tools/eval/run_eval.ps1 -Label baseline [-Out folder]
[CmdletBinding()]
param([string]$Label = "run", [string]$Out = "", [switch]$NoLlm)
if (-not $Out) { $Out = Join-Path $PSScriptRoot "results" }  # $PSScriptRoot is empty inside param defaults here
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "ai.kairo.gallery"
$remote = "/sdcard/Android/data/$pkg/files/eval"
New-Item -ItemType Directory -Force $Out | Out-Null

$labels = @{}
Import-Csv "$PSScriptRoot\labels.tsv" -Delimiter "`t" | ForEach-Object { $labels[$_.alias] = $_.file_contains }
$aliasOf = { param($name) foreach ($k in $labels.Keys) { if ($name -like "*$($labels[$k])*") { return $k } }; return "?" }
$scen = Import-Csv "$PSScriptRoot\scenarios.tsv" -Delimiter "`t" -Encoding UTF8

& $adb shell "rm -rf $remote" | Out-Null
& $adb logcat -c
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null  # foreground: models warm, no freezer
# Wait (max 90 s) until Gemma is loaded so every query is measured the same way.
$ready = $false
for ($i = 0; $i -lt 45 -and -not $ready; $i++) {
    Start-Sleep -Seconds 2
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    & $adb shell "am broadcast -p $pkg -a ai.kairo.gallery.EVAL_STATUS --es id warm$i" | Out-Null
    Start-Sleep -Milliseconds 500
    $st = (& $adb shell "cat $remote/warm$i.json 2>/dev/null") -join ""
    if ($st -match '"llmReady":true') { $ready = $true; "models ready: " + (($st | ConvertFrom-Json).llm) }
}
if (-not $ready) { "WARNING: Gemma not ready, queries will use rules + CLIP only" }
$rows = @()
foreach ($s in $scen) {
    $id = "$Label-$($s.id)"
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null  # screen-off freezes the app (see report)
    $q64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s.query))
    $cmd = "am broadcast -p $pkg -a ai.kairo.gallery.EVAL_SEARCH --es id $id --es q64 $q64"
    if ($NoLlm) { $cmd += " --ez nollm true" }
    & $adb shell $cmd | Out-Null
    $deadline = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Milliseconds 400
        $done = (& $adb shell "test -f $remote/$id.json && echo Y") -eq "Y"
    } until ($done -or (Get-Date) -gt $deadline)
    if (-not $done) { $rows += [pscustomobject]@{ id = $s.id; group = $s.group; query = $s.query; error = "timeout" }; continue }
    $raw = (& $adb shell "cat $remote/$id.json") -join ""
    $r = $raw | ConvertFrom-Json
    if ($r.error) { $rows += [pscustomobject]@{ id = $s.id; group = $s.group; query = $s.query; error = $r.error }; continue }

    $got = @($r.results | ForEach-Object { & $aliasOf $_.name })
    $rel = @($s.relevant -split "," | Where-Object { $_ })
    $hits = @($got | Where-Object { $rel -contains $_ })
    if ($rel.Count -eq 0) {
        $top1 = ($got.Count -eq 0); $recall = $null; $prec = $null
        $pass = $top1
    } else {
        $top1 = ($got.Count -gt 0 -and $rel -contains $got[0])
        $recall = [Math]::Round((@($rel | Where-Object { $got -contains $_ }).Count) / $rel.Count, 2)
        $prec = if ($got.Count -gt 0) { [Math]::Round($hits.Count / $got.Count, 2) } else { 0 }
        $pass = $top1 -and $recall -eq 1 -and $prec -ge 0.5
    }
    $ansOk = $null
    if ($s.answer) {
        $norm = { param($x) ("$x" -replace '[\s,]', '').ToLower() }
        $ansOk = (& $norm $r.answer).Contains((& $norm $s.answer))
        $pass = $pass -and $ansOk
    }
    $rows += [pscustomobject]@{
        id = $s.id; group = $s.group; query = $s.query; pass = $pass; top1 = $top1; recall = $recall; precision = $prec
        n = $got.Count; results = ($got -join " "); answer = $r.answer; expected = $s.answer; answer_ok = $ansOk
        ms = $r.ms; topSim = if ($r.topSim) { [Math]::Round($r.topSim, 3) } else { $null }
        cats = ($r.cats -join ","); kw = ($r.kw -join ","); field = $r.field; visual = $r.visual; date = $r.date
        sims = (($r.results | Select-Object -First 6 | ForEach-Object { "$(& $aliasOf $_.name)=$([Math]::Round([double]$_.sim, 3))" }) -join " ")
    }
    $last = $rows[-1]
    "{0,-4} {1,-5} {2,-38} n={3,-2} top1={4,-5} P={5,-4} R={6,-4} ans={7} {8}ms" -f $s.id, $(if ($last.pass) { "PASS" } else { "FAIL" }), $s.query, $last.n, $last.top1, $last.precision, $last.recall, $last.answer, $last.ms
}

$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$base = Join-Path $Out "$stamp-$Label"
$rows | ForEach-Object { $_ | ConvertTo-Json -Compress } | Set-Content -Encoding UTF8 "$base.jsonl"
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 "$base.csv"
$passN = @($rows | Where-Object { $_.pass }).Count
""
"SUMMARY $Label : $passN / $($rows.Count) passed"
$rows | Group-Object group | ForEach-Object {
    $p = @($_.Group | Where-Object { $_.pass }).Count
    "  {0,-9} {1}/{2}" -f $_.Name, $p, $_.Count
}
"saved $base.jsonl / .csv"
