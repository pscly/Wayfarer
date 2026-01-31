$ErrorActionPreference = 'Stop'

$src = @"
using System;
using System.Diagnostics;
using System.Linq;

public static class Program {
    public static int Main(string[] args) {
        // The diagnostics runner spawns `kotlin-lsp` directly. On some Windows setups,
        // libuv doesn't resolve `.cmd` via PATHEXT, so we provide a tiny `.exe` shim.
        string baseDir = AppDomain.CurrentDomain.BaseDirectory;
        string cmdPath = System.IO.Path.Combine(baseDir, "kotlin-lsp.cmd");
        string joined = string.Join(" ", args.Select(EscapeArg));

        var psi = new ProcessStartInfo("cmd.exe", "/c \"" + cmdPath + "\" " + joined);
        psi.UseShellExecute = false;

        var p = Process.Start(psi);
        if (p == null) return 1;
        p.WaitForExit();
        return p.ExitCode;
    }

    private static string EscapeArg(string a) {
        if (string.IsNullOrEmpty(a)) return "";
        bool needsQuotes = a.Any(ch => char.IsWhiteSpace(ch) || ch == (char)34);
        if (!needsQuotes) return a;
        return "\"" + a.Replace("\"", "\\\"") + "\"";
    }
}
"@

$out = Join-Path $PSScriptRoot "..\..\kotlin-lsp.exe"

Add-Type -TypeDefinition $src -OutputAssembly $out -OutputType ConsoleApplication -Language CSharp
Write-Host "Built: $out"
