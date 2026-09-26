# Quality gate between two indexing runs saved by bench_index.ps1 (results\index-<label>.json):
# categories, every extracted field value, and tag overlap per photo.
#   powershell -ExecutionPolicy Bypass -File tools\eval\compare_index.ps1 -A base -B fast [-ShowTags]
[CmdletBinding()]
param([Parameter(Mandatory)] [string]$A, [Parameter(Mandatory)] [string]$B, [string]$Dir = "", [switch]$ShowTags)
if (-not $Dir) { $Dir = Join-Path $PSScriptRoot "results" }

function Load($label) {
    $d = Get-Content (Join-Path $Dir "index-$label.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    # Windows PowerShell 5.1 may save arrays as {"value": [...]}; accept both shapes.
    $items = if ($d.items.value) { $d.items.value } else { $d.items }
    $by = @{}; $items | ForEach-Object { $by[$_.name] = $_ }
    return $by
}
function Fields($json) { if ($json) { $json | ConvertFrom-Json } else { [pscustomobject]@{} } }
function TagList($json) { if ($json) { @($json | ConvertFrom-Json | Select-Object -Unique) } else { @() } }

$ia = Load $A; $ib = Load $B
$catSame = 0; $fSame = 0; $fLost = 0; $fNew = 0; $fChanged = 0; $jac = @(); $notes = @(); $n = 0
foreach ($name in ($ib.Keys | Sort-Object)) {
    $x = $ia[$name]; $y = $ib[$name]
    if (-not $x) { $notes += "NEW PHOTO $name (not in '$A')"; continue }
    $n++
    if ($x.category -eq $y.category) { $catSame++ } else { $notes += "CATEGORY $name : $($x.category) -> $($y.category)" }
    $fa = Fields $x.fields_json; $fb = Fields $y.fields_json
    foreach ($k in "title","name","id_number","booking_id","date","time","venue","seats","amount") {
        $va = "$($fa.$k)"; $vb = "$($fb.$k)"
        if (-not $va -and -not $vb) { continue }
        if ($va -eq $vb) { $fSame++ }
        elseif ($va -and -not $vb) { $fLost++; $notes += "LOST    $name $k = '$va'" }
        elseif (-not $va -and $vb) { $fNew++; $notes += "NEW     $name $k = '$vb'" }
        else { $fChanged++; $notes += "CHANGED $name $k : '$va' -> '$vb'" }
    }
    $ta = TagList $x.tags; $tb = TagList $y.tags
    $union = @($ta + $tb | Select-Object -Unique).Count
    $inter = @($ta | Where-Object { $tb -contains $_ }).Count
    if ($union -gt 0) { $jac += $inter / $union }
    if ($ShowTags -and $inter -lt $union) { $notes += "TAGS    $name : [$($ta -join ', ')] -> [$($tb -join ', ')]" }
}
Write-Host "=== quality: '$B' vs '$A' ($n photos in both)"
Write-Host ("categories same : {0}/{1}" -f $catSame, $n)
Write-Host ("fields         : same={0} lost={1} new={2} changed={3}" -f $fSame, $fLost, $fNew, $fChanged)
Write-Host ("tag overlap    : {0:N2} (Jaccard, 1.00 = identical)" -f (($jac | Measure-Object -Average).Average))
$notes | ForEach-Object { Write-Host "  $_" }
