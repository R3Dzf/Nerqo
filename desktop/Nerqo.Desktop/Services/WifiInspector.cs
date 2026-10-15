using System.Diagnostics;
using System.Text.RegularExpressions;

namespace Nerqo.Desktop.Services;

public static class WifiInspector
{
    /// <summary>Only reports the actual connected band; never pretends Windows can silently switch the router.</summary>
    public static string GetBand()
    {
        try
        {
            var start = new ProcessStartInfo("netsh", "wlan show interfaces")
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            using var p = Process.Start(start);
            if (p is null) return "Unknown";
            string output = p.StandardOutput.ReadToEnd();
            if (!p.WaitForExit(3000)) { p.Kill(); return "Unknown"; }
            var band = Regex.Match(output, @"(?im)^\s*(?:Band|Network band)\s*:\s*(?<v>[^\r\n]+)");
            if (band.Success)
            {
                var s = band.Groups["v"].Value.Trim();
                if (s.Contains("6 GHz", StringComparison.OrdinalIgnoreCase)) return "6 GHz";
                if (s.Contains("5 GHz", StringComparison.OrdinalIgnoreCase)) return "5 GHz";
                if (s.Contains("2.4", StringComparison.OrdinalIgnoreCase)) return "2.4 GHz";
            }
            var channel = Regex.Match(output, @"(?im)^\s*Channel\s*:\s*(\d+)");
            if (channel.Success && int.TryParse(channel.Groups[1].Value, out var n))
            {
                if (n >= 36 && n <= 177) return "5 GHz";
                if (n >= 1 && n <= 14) return "2.4 GHz or 6 GHz (confirm in Wi-Fi settings)";
            }
        }
        catch { /* netsh output differs by Windows locale, adapter and policy */ }
        return "Unknown (check Wi-Fi adapter settings)";
    }
}
