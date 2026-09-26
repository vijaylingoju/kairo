# Fetches the two QNN libraries LiteRT's Qualcomm NPU compiler plugin needs but Qualcomm's Maven package
# (com.qualcomm.qti:qnn-runtime) does not ship: libQnnIr.so and libQnnSaver.so.
#
# They live in the QAIRT SDK zip (2.35 GB) - the same public URL LiteRT's own fetch_qualcomm_library.sh uses.
# Instead of downloading all of it, this reads the zip over HTTP range requests: the file index at the end,
# then only the two entries (~ a few MB in total).
#
#   powershell -ExecutionPolicy Bypass -File tools\fetch_qnn_libs.ps1
param(
    [string]$Version = "2.47.0.260601",
    [string]$Dest = ""
)
$ErrorActionPreference = "Stop"
if (-not $Dest) { $Dest = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..\app\src\main\jniLibs\arm64-v8a" }
$url = "https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$Version/v$Version.zip"
$want = @("libQnnIr.so", "libQnnSaver.so")

Add-Type -AssemblyName System.IO.Compression
Add-Type -Language CSharp -ReferencedAssemblies System.IO.Compression -TypeDefinition @"
using System;
using System.IO;
using System.Net;

// Read-only, seekable view of a remote file; fetches 1 MB blocks on demand with HTTP Range requests.
public class HttpRangeStream : Stream {
    readonly string url; readonly long length; long pos;
    long blockStart = -1; byte[] block;
    const int BlockSize = 1 << 20;
    public long BytesFetched;

    public HttpRangeStream(string url) {
        ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
        var req = (HttpWebRequest)WebRequest.Create(url);
        req.AddRange(0L, 0L);
        using (var resp = (HttpWebResponse)req.GetResponse()) {
            var cr = resp.Headers["Content-Range"];              // bytes 0-0/TOTAL
            length = long.Parse(cr.Substring(cr.LastIndexOf('/') + 1));
            this.url = resp.ResponseUri.ToString();               // follow the redirect once
        }
    }
    void Load(long start) {
        long end = Math.Min(start + BlockSize, length) - 1;
        var req = (HttpWebRequest)WebRequest.Create(url);
        req.AddRange(start, end);
        using (var resp = req.GetResponse()) using (var s = resp.GetResponseStream()) using (var ms = new MemoryStream()) {
            s.CopyTo(ms); block = ms.ToArray();
        }
        blockStart = start; BytesFetched += block.Length;
    }
    public override int Read(byte[] buffer, int offset, int count) {
        if (pos >= length) return 0;
        if (blockStart < 0 || pos < blockStart || pos >= blockStart + block.Length) Load(pos - (pos % BlockSize));
        int n = (int)Math.Min(count, blockStart + block.Length - pos);
        Array.Copy(block, pos - blockStart, buffer, offset, n);
        pos += n; return n;
    }
    public override long Seek(long offset, SeekOrigin origin) {
        pos = origin == SeekOrigin.Begin ? offset : origin == SeekOrigin.Current ? pos + offset : length + offset;
        return pos;
    }
    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return true; } }
    public override bool CanWrite { get { return false; } }
    public override long Length { get { return length; } }
    public override long Position { get { return pos; } set { pos = value; } }
    public override void Flush() {}
    public override void SetLength(long v) { throw new NotSupportedException(); }
    public override void Write(byte[] b, int o, int c) { throw new NotSupportedException(); }
}
"@

$s = New-Object HttpRangeStream $url
Write-Host ("QAIRT {0}: {1:N0} MB zip, reading index only..." -f $Version, ($s.Length / 1MB))
$zip = New-Object System.IO.Compression.ZipArchive($s, [System.IO.Compression.ZipArchiveMode]::Read)
New-Item -ItemType Directory -Force $Dest | Out-Null
foreach ($name in $want) {
    $entry = $zip.Entries | Where-Object { $_.FullName -like "*/lib/aarch64-android/$name" } | Select-Object -First 1
    if (-not $entry) { throw "$name not found in the SDK zip" }
    $out = Join-Path $Dest $name
    $in = $entry.Open(); $f = [System.IO.File]::Create($out)
    try { $in.CopyTo($f) } finally { $f.Close(); $in.Close() }
    Write-Host ("  {0}  {1:N1} MB  <- {2}" -f $name, ($entry.Length / 1MB), $entry.FullName)
}
Write-Host ("downloaded {0:N1} MB in total (instead of {1:N0} MB)" -f ($s.BytesFetched / 1MB), ($s.Length / 1MB))
