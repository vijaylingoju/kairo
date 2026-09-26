# Makes the Qualcomm AI Hub CLIP export (openai_clip.tflite) run on the Snapdragon NPU through LiteRT.
#
# Why: the image tower has one UNPACK op that splits the 197 vision tokens into 197 tensors but only the first
# (the CLS token) is used. LiteRT's Qualcomm compiler turns every unused tensor into an NPU graph output, and the
# runtime only binds buffers to the model's declared outputs, so the NPU run fails with
# "QnnDsp <E> Tensor ID ... clientBuf is null".
#
# Fix: declare those unused tensors as model outputs too, so every NPU output gets a buffer. Weights and maths are
# untouched (same results on CPU/GPU); the NPU just writes ~600 KB of extra, ignored outputs per run.
# Done append-only: a new outputs list is added at the end of the file and the subgraph's 4-byte offset to it is
# repointed (flatbuffer offsets point forward, so this is valid without re-serialising 600 MB).
#
#   powershell -ExecutionPolicy Bypass -File tools\patch_clip_npu.ps1 -In openai_clip.tflite -Out clip.tflite
param(
    [Parameter(Mandatory)] [string]$In,
    [Parameter(Mandatory)] [string]$Out
)
$ErrorActionPreference = "Stop"
$In = (Resolve-Path $In).Path
$Out = [System.IO.Path]::GetFullPath($Out)

Add-Type -TypeDefinition @"
using System;
using System.IO;
using System.Collections.Generic;

public static class ClipNpuPatch {
    static byte[] b;
    static int I32(long p) { return BitConverter.ToInt32(b, (int)p); }
    static long Ref(long p) { return p + (uint)I32(p); }
    static long Field(long table, int id) {
        long vt = table - I32(table);
        int vtLen = BitConverter.ToUInt16(b, (int)vt);
        if (4 + id * 2 >= vtLen) return -1;
        int off = BitConverter.ToUInt16(b, (int)(vt + 4 + id * 2));
        return off == 0 ? -1 : table + off;
    }
    static List<long> Tables(long table, int id) {
        var r = new List<long>(); long f = Field(table, id); if (f < 0) return r;
        long v = Ref(f); int n = I32(v); for (int i = 0; i < n; i++) r.Add(Ref(v + 4 + i * 4)); return r;
    }
    static List<int> Ints(long table, int id) {
        var r = new List<int>(); long f = Field(table, id); if (f < 0) return r;
        long v = Ref(f); int n = I32(v); for (int i = 0; i < n; i++) r.Add(I32(v + 4 + i * 4)); return r;
    }

    // Returns a summary; writes the patched model to outPath.
    public static string Patch(string inPath, string outPath) {
        b = File.ReadAllBytes(inPath);
        if (b.Length > int.MaxValue - 4096) throw new Exception("model too large for this patcher");
        long root = Ref(0);
        var subgraphs = Tables(root, 2);
        if (subgraphs.Count != 1) throw new Exception("expected 1 subgraph, found " + subgraphs.Count);
        long sg = subgraphs[0];
        var outputs = Ints(sg, 2);
        var used = new HashSet<int>(outputs);
        var ops = Tables(sg, 3);
        foreach (var op in ops) foreach (var x in Ints(op, 1)) if (x >= 0) used.Add(x);
        var dead = new List<int>();
        foreach (var op in ops) foreach (var o in Ints(op, 2)) if (!used.Contains(o)) dead.Add(o);
        if (dead.Count == 0) { File.Copy(inPath, outPath, true); return "no unused tensors: copied unchanged"; }

        // Keep outputs in the order the ops produce them (the order the NPU graph creates its outputs in),
        // so buffers bind to the right tensors by position.
        var keep = new HashSet<int>(outputs); foreach (var d in dead) keep.Add(d);
        var all = new List<int>();
        foreach (var op in ops) foreach (var o in Ints(op, 2)) if (keep.Remove(o)) all.Add(o);
        all.AddRange(keep);  // outputs that are also graph inputs (none expected)
        int pad = (4 - (b.Length % 4)) % 4;
        long vecPos = b.Length + pad;
        long fieldPos = Field(sg, 2);
        using (var fs = new FileStream(outPath, FileMode.Create, FileAccess.Write)) {
            var patched = BitConverter.GetBytes((uint)(vecPos - fieldPos));
            fs.Write(b, 0, (int)fieldPos);
            fs.Write(patched, 0, 4);
            fs.Write(b, (int)fieldPos + 4, b.Length - (int)fieldPos - 4);
            fs.Write(new byte[pad], 0, pad);
            fs.Write(BitConverter.GetBytes(all.Count), 0, 4);
            foreach (var t in all) fs.Write(BitConverter.GetBytes(t), 0, 4);
        }
        return string.Format("outputs {0} -> {1} (added {2} unused tensors as outputs)", outputs.Count, all.Count, dead.Count);
    }
}
"@
Write-Host ([ClipNpuPatch]::Patch($In, $Out))
Write-Host ("wrote {0} ({1:N1} MB)" -f $Out, ((Get-Item $Out).Length / 1MB))
