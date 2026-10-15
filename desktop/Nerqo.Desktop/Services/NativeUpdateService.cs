using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.IO;

namespace Nerqo.Desktop.Services;

/// <summary>
/// Browser-free, consent-based Windows updater. Only a newer stable native
/// Inno Setup installer listed in a GitHub Release and its verified manifest
/// is accepted. This cannot silently authenticate an unsigned publisher.
/// </summary>
public sealed record NativeRelease(string Version, string Url, long Size, string Sha256);

public static class NativeUpdateService
{
    private const string Repo = "R3Dzf/Nerqo";
    private const string InstallerName = "Nerqo-Windows-Setup.exe";
    private const long MaxInstaller = 1024L * 1024 * 1024;
    private static readonly HttpClient Client = CreateClient();

    private static HttpClient CreateClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("Nerqo-Native", "0.5"));
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        return client;
    }

    public static bool IsNewerVersion(string tag, Version installed)
    {
        if (!Regex.IsMatch(tag, @"^v\d+\.\d+\.\d+$", RegexOptions.CultureInvariant))
            return false;
        return Version.TryParse(tag[1..], out var candidate) && candidate > installed;
    }

    private static Uri Expected(string tag, string filename) =>
        new($"https://github.com/{Repo}/releases/download/{tag}/{filename}");

    private static async Task<byte[]> ReadLimited(Uri uri, int limit, CancellationToken ct)
    {
        if (uri.Scheme != Uri.UriSchemeHttps)
            throw new IOException("Updater requires HTTPS");
        using var response = await Client.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();
        if (response.RequestMessage?.RequestUri?.Scheme != Uri.UriSchemeHttps)
            throw new IOException("Update metadata redirected to an insecure connection");
        if (response.Content.Headers.ContentLength is long count && count > limit)
            throw new IOException("Update metadata too large");
        await using var data = await response.Content.ReadAsStreamAsync(ct);
        using var output = new MemoryStream();
        byte[] block = new byte[16 * 1024];
        while (true)
        {
            int n = await data.ReadAsync(block, ct);
            if (n == 0) return output.ToArray();
            if (output.Length + n > limit) throw new IOException("Update metadata too large");
            output.Write(block, 0, n);
        }
    }

    public static async Task<NativeRelease?> CheckAsync(CancellationToken ct)
    {
        var uri = new Uri($"https://api.github.com/repos/{Repo}/releases/latest");
        using var release = JsonDocument.Parse(await ReadLimited(uri, 1024 * 1024, ct));
        var info = release.RootElement;
        if (info.GetProperty("draft").GetBoolean() || info.GetProperty("prerelease").GetBoolean())
            return null;
        string tag = info.GetProperty("tag_name").GetString() ?? "";
        var installed = typeof(NativeUpdateService).Assembly.GetName().Version ?? new Version(0, 5, 0);
        if (!IsNewerVersion(tag, installed)) return null;
        // Reject unrelated applications or old Python executables masquerading as native installers.
        var names = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var asset in info.GetProperty("assets").EnumerateArray())
        {
            string name = asset.GetProperty("name").GetString() ?? "";
            string url = asset.GetProperty("browser_download_url").GetString() ?? "";
            if (name is "update-manifest.json" or InstallerName)
                names[name] = url;
        }
        if (!names.TryGetValue("update-manifest.json", out var manifestUrl)
            || manifestUrl != Expected(tag, "update-manifest.json").ToString()
            || !names.TryGetValue(InstallerName, out var installerUrl)
            || installerUrl != Expected(tag, InstallerName).ToString())
            throw new IOException("No signed-off native Windows installer found in this release");
        using var manifest = JsonDocument.Parse(await ReadLimited(Expected(tag, "update-manifest.json"), 128 * 1024, ct));
        var body = manifest.RootElement;
        if (body.GetProperty("schema").GetInt32() != 1
            || body.GetProperty("version").GetString() != tag[1..])
            throw new IOException("Release manifest version mismatch");
        var full = body.GetProperty("assets").GetProperty("windows").GetProperty("full");
        if (full.GetProperty("name").GetString() != InstallerName)
            throw new IOException("Release manifest does not name a native installer");
        long size = full.GetProperty("size").GetInt64();
        string hash = full.GetProperty("sha256").GetString() ?? "";
        if (size <= 0 || size > MaxInstaller || !Regex.IsMatch(hash, "^[0-9a-f]{64}$"))
            throw new IOException("Invalid Windows installer metadata");
        return new NativeRelease(tag[1..], installerUrl, size, hash);
    }

    public static async Task<string> DownloadAsync(NativeRelease release, IProgress<int>? progress, CancellationToken ct)
    {
        string tag = "v" + release.Version;
        if (release.Url != Expected(tag, InstallerName).ToString()
            || release.Size <= 0 || release.Size > MaxInstaller
            || !Regex.IsMatch(release.Sha256, "^[0-9a-f]{64}$"))
            throw new IOException("Untrusted installer metadata");
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Nerqo", "Updates");
        Directory.CreateDirectory(directory);
        string staging = Path.Combine(directory, $"{Guid.NewGuid():N}.part");
        string complete = Path.Combine(directory, $"Nerqo-Windows-Setup-{release.Version}.exe");
        try
        {
            using var response = await Client.GetAsync(new Uri(release.Url), HttpCompletionOption.ResponseHeadersRead, ct);
            response.EnsureSuccessStatusCode();
            if (response.RequestMessage?.RequestUri?.Scheme != Uri.UriSchemeHttps)
                throw new IOException("Insecure installer download");
            if (response.Content.Headers.ContentLength is long count && count != release.Size)
                throw new IOException("Installer size differs from manifest");
            await using var input = await response.Content.ReadAsStreamAsync(ct);
            await using var output = new FileStream(staging, FileMode.CreateNew, FileAccess.Write, FileShare.None, 256 * 1024, FileOptions.Asynchronous);
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            byte[] buffer = new byte[256 * 1024];
            long downloaded = 0;
            while (true)
            {
                int n = await input.ReadAsync(buffer, ct);
                if (n == 0) break;
                downloaded += n;
                if (downloaded > release.Size) throw new IOException("Oversized Windows installer");
                await output.WriteAsync(buffer.AsMemory(0, n), ct);
                hash.AppendData(buffer, 0, n);
                progress?.Report((int)(100 * downloaded / release.Size));
            }
            await output.FlushAsync(ct);
            if (downloaded != release.Size ||
                !Convert.ToHexString(hash.GetHashAndReset()).Equals(release.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new IOException("Downloaded installer failed SHA-256 verification");
            File.Move(staging, complete, true);
            progress?.Report(100);
            return complete;
        }
        finally { if (File.Exists(staging)) File.Delete(staging); }
    }
}
