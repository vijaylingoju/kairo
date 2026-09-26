# Downloads a big file in many parallel HTTP range pieces, for networks that throttle each connection
# (hackathon Wi-Fi gave ~65 KB/s per connection; 16 connections gave 4-6 MB/s).
# Safe to re-run: finished pieces are kept, missing/short ones are fetched again, then joined and size-checked.
#
#   powershell -ExecutionPolicy Bypass -File tools\download_parallel.ps1 `
#       -Url "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm" `
#       -Out "C:\kairo-models\gemma-4-E4B-it.litertlm" [-Connections 16] [-ChunkMB 16]
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$Url,
    [Parameter(Mandatory)] [string]$Out,
    [int]$Connections = 16,
    [int]$ChunkMB = 16
)
$ErrorActionPreference = "Continue"

# Total size (follow redirects; take the last Content-Length).
$head = & curl.exe -sSIL $Url
$total = [long](($head | Select-String -Pattern '^content-length:\s*(\d+)' -AllMatches | Select-Object -Last 1).Matches[0].Groups[1].Value)
if ($total -le 0) { Write-Host "Could not read the file size from the server." -ForegroundColor Red; exit 1 }
Write-Host ("File size: {0:N0} bytes ({1:N2} GB)" -f $total, ($total / 1e9))

$dir = Split-Path -Parent ([IO.Path]::GetFullPath($Out))
New-Item -ItemType Directory -Force $dir | Out-Null
$parts = "$Out.parts"
New-Item -ItemType Directory -Force $parts | Out-Null
$chunk = [long]$ChunkMB * 1MB
$ranges = for ($a = [long]0; $a -lt $total; $a += $chunk) {
    $b = [Math]::Min($a + $chunk, $total) - 1
    [pscustomobject]@{ Name = ("p{0:D12}.bin" -f $a); From = $a; To = $b; Len = $b - $a + 1 }
}
Write-Host "$($ranges.Count) pieces, $Connections at a time"

for ($round = 1; $round -le 30; $round++) {
    $todo = @($ranges | Where-Object { $f = Join-Path $parts $_.Name; -not (Test-Path $f) -or (Get-Item $f).Length -ne $_.Len })
    if ($todo.Count -eq 0) { break }
    Write-Host "round ${round}: $($todo.Count) pieces left"
    $cfg = New-Object System.Collections.Generic.List[string]
    $first = $true
    foreach ($r in $todo) {
        if (-not $first) { $cfg.Add("next") }
        $first = $false
        $cfg.Add("url = `"$Url`"")
        $cfg.Add("location")
        $cfg.Add("range = $($r.From)-$($r.To)")
        # Forward slashes: curl config treats "\" inside quotes as an escape character.
        $cfg.Add("output = `"$((Join-Path $parts $r.Name).Replace('\', '/'))`"")
        $cfg.Add("retry = 5")
        $cfg.Add("retry-all-errors")
        $cfg.Add("speed-limit = 5000")
        $cfg.Add("speed-time = 60")
        $cfg.Add("silent")
    }
    $cfgFile = Join-Path $parts "curl.cfg"
    [IO.File]::WriteAllLines($cfgFile, $cfg, (New-Object Text.UTF8Encoding($false)))
    & curl.exe --parallel --parallel-max $Connections -K $cfgFile 2>$null
}

$bad = @($ranges | Where-Object { $f = Join-Path $parts $_.Name; -not (Test-Path $f) -or (Get-Item $f).Length -ne $_.Len })
if ($bad.Count -gt 0) { Write-Host "$($bad.Count) pieces still missing - run the same command again." -ForegroundColor Yellow; exit 1 }

Write-Host "Joining pieces..."
if (Test-Path $Out) { Remove-Item -Force $Out }
$o = [IO.File]::Open($Out, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
try { foreach ($r in $ranges) { $i = [IO.File]::OpenRead((Join-Path $parts $r.Name)); try { $i.CopyTo($o) } finally { $i.Dispose() } } }
finally { $o.Dispose() }
$final = (Get-Item $Out).Length
if ($final -ne $total) { Write-Host "Size mismatch: $final vs $total" -ForegroundColor Red; exit 1 }
Remove-Item -Recurse -Force $parts
Write-Host ("Done: {0} ({1:N0} bytes)" -f $Out, $final) -ForegroundColor Green
