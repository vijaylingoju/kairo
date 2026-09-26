# Calibration helper: for each scenario, compares the CLIP "adjusted" score (similarity minus the photo's
# own baseline) of relevant photos vs the best non-relevant photo, and simulates thresholds.
# Usage: powershell -File tools/eval/analyze_adj.ps1 -Jsonl <results .jsonl> -RawDir <folder with <id>.json>
param([string]$RawDir)
$labels = @{}
Import-Csv "$PSScriptRoot\labels.tsv" -Delimiter "`t" | ForEach-Object { $labels[$_.alias] = $_.file_contains }
$aliasOf = { param($name) foreach ($k in $labels.Keys) { if ($name -like "*$($labels[$k])*") { return $k } }; return "?" }
$scen = Import-Csv "$PSScriptRoot\scenarios.tsv" -Delimiter "`t" -Encoding UTF8

$rows = foreach ($s in $scen) {
    $f = Get-ChildItem $RawDir -Filter "*-$($s.id).json" | Select-Object -First 1
    if (-not $f) { continue }
    $r = Get-Content $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $r.allAdj) { continue }
    $rel = @($s.relevant -split "," | Where-Object { $_ })
    $adj = @{}
    $r.allAdj.PSObject.Properties | ForEach-Object { $adj[(& $aliasOf $_.Name)] = [double]$_.Value }
    $relVals = @($rel | ForEach-Object { $adj[$_] } | Where-Object { $_ -ne $null })
    $non = @($adj.Keys | Where-Object { $rel -notcontains $_ } | ForEach-Object { $adj[$_] })
    $bestNonName = ($adj.Keys | Where-Object { $rel -notcontains $_ } | Sort-Object { -$adj[$_] } | Select-Object -First 1)
    [pscustomobject]@{
        id = $s.id; group = $s.group; query = $s.query
        rel_min = if ($relVals) { [Math]::Round(($relVals | Measure-Object -Minimum).Minimum, 3) } else { $null }
        rel_max = if ($relVals) { [Math]::Round(($relVals | Measure-Object -Maximum).Maximum, 3) } else { $null }
        non_max = [Math]::Round(($non | Measure-Object -Maximum).Maximum, 3)
        non_max_is = $bestNonName
        margin = if ($relVals) { [Math]::Round(($relVals | Measure-Object -Minimum).Minimum - ($non | Measure-Object -Maximum).Maximum, 3) } else { $null }
    }
}
$rows | Format-Table -AutoSize | Out-String -Width 220
