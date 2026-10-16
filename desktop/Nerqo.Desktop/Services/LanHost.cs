using System.IO;
using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Runtime.InteropServices;
using System.IO.Compression;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Nerqo.Desktop.Services;

public sealed class TransferItem
{
    public string Id { get; set; } = "";
    public string Kind { get; set; } = "";
    public string Sender { get; set; } = "";
    public string Content { get; set; } = "";
    public long Size { get; set; }
    public string Sha256 { get; set; } = "";
    public string FolderId { get; set; } = "";
    public DateTimeOffset Created { get; set; } = DateTimeOffset.UtcNow;
}
internal sealed class UploadRecord
{
    public string Id { get; set; } = "";
    public string Owner { get; set; } = "";
    public string Name { get; set; } = "";
    public long Size { get; set; }
    public long Offset { get; set; }
    public string FolderId { get; set; } = "";
    public string RelativePath { get; set; } = "";
}
internal sealed class CompletedUploadRecord
{
    public UploadRecord Upload { get; set; } = new();
    public string Sha256 { get; set; } = "";
}
internal sealed class FolderRecord
{
    public string Owner { get; set; } = "";
    public string Name { get; set; } = "";
    public string DirectoryName { get; set; } = "";
    public DateTimeOffset Created { get; set; } = DateTimeOffset.UtcNow;
}
public sealed record OutgoingFolderFile(string Path, long Size, string Sha256);
internal sealed record OutgoingFolder(string Root, string Name, List<string> Directories, Dictionary<string, OutgoingFolderFile> Files);
public sealed record PairedDevice(string TokenHash, string Name, DateTimeOffset Expires);
internal sealed class PeerRecord
{
    public string Name { get; set; } = "";
    public DateTimeOffset Expires { get; set; }
}
internal sealed class PersistedData
{
    public Dictionary<string, PeerRecord> Peers { get; set; } = new();
    public Dictionary<string, UploadRecord> Uploads { get; set; } = new();
    public Dictionary<string, CompletedUploadRecord> CompletedUploads { get; set; } = new();
    public Dictionary<string, FolderRecord> Folders { get; set; } = new();
    public Dictionary<string, OutgoingFolder> OutgoingFolders { get; set; } = new();
    public List<TransferItem> Items { get; set; } = new();
}

/// <summary>Pure .NET, in-process LAN transfer service. No Python, shell or embedded web browser.</summary>
public sealed class LanHost : IAsyncDisposable
{
    public const int Port = 44888;
    public const int DiscoveryPort = 44889;
    public const int ChunkLimit = 4 * 1024 * 1024;
    private const long MaxFile = 2L * 1024 * 1024 * 1024 * 1024;
    private readonly object gate = new();
    private readonly ConcurrentDictionary<string, Queue<DateTimeOffset>> attempts = new();
    private readonly ConcurrentDictionary<string, OutgoingFolder> outgoingFolders = new();
    private readonly string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Nerqo");
    private PersistedData state = new();
    private readonly X509Certificate2 tlsCertificate;
    public string ServerFingerprint => tlsCertificate.GetCertHashString(HashAlgorithmName.SHA256).ToLowerInvariant();
    public string PairingIdentity => ServerFingerprint[..12].ToUpperInvariant();
    private WebApplication? app;
    private CancellationTokenSource? discoveryCancellation;
    private Task? discoveryTask;
    public string PairCode { get; } = RandomNumberGenerator.GetInt32(0, 1000000).ToString("D6");
    public string ReceivedDirectory => Path.Combine(root, "Received");
    public string StorageDirectory => Path.Combine(root, "Storage");
    public event Action? Changed;
    public List<TransferItem> Items { get { lock (gate) return state.Items.OrderByDescending(x => x.Created).Take(150).Select(x => x).ToList(); } }
    public List<PairedDevice> PairedDevices { get { lock (gate) return state.Peers.Where(x => x.Value.Expires > DateTimeOffset.UtcNow).Select(x => new PairedDevice(x.Key, x.Value.Name, x.Value.Expires)).OrderBy(x => x.Name).ToList(); } }
    public bool RevokePeer(string tokenHash)
    {
        if (!Regex.IsMatch(tokenHash, "^[a-f0-9]{64}$")) return false;
        lock (gate)
        {
            if (!state.Peers.Remove(tokenHash)) return false;
            Save();
            return true;
        }
    }
    // Prefer the actual Wi-Fi / phone hotspot. VPNs, VirtualBox adapters and
    // inactive virtual NICs otherwise regularly produce an unusable QR address.
    internal static bool IsCandidateAddress(IPAddress ip)
    {
        if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
        var a = ip.GetAddressBytes();
        return a[0] == 10 || (a[0] == 172 && a[1] is >= 16 and <= 31)
            || (a[0] == 192 && a[1] == 168) || (a[0] == 169 && a[1] == 254);
    }
    public string[] Addresses => NetworkInterface.GetAllNetworkInterfaces()
        .Where(n => n.OperationalStatus == OperationalStatus.Up
            && n.NetworkInterfaceType is not NetworkInterfaceType.Loopback and not NetworkInterfaceType.Tunnel)
        .Select(n => new { Nic = n, Properties = n.GetIPProperties() })
        .SelectMany(n => n.Properties.UnicastAddresses
            .Where(a => IsCandidateAddress(a.Address))
            .Select(a => new
            {
                Address = a.Address.ToString(),
                Priority = (n.Nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 ? 100 : 0)
                    + (n.Properties.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork) ? 20 : 0)
                    + (a.Address.GetAddressBytes()[0] == 169 ? -50 : 0)
            }))
        .OrderByDescending(x => x.Priority)
        .Select(x => x.Address)
        .Distinct().ToArray();

    public LanHost()
    {
        Directory.CreateDirectory(root);
        Directory.CreateDirectory(ReceivedDirectory);
        Directory.CreateDirectory(StorageDirectory);
        tlsCertificate = LanIdentity.LoadOrCreate(root);
        string data = Path.Combine(root, "state.json");
        if (File.Exists(data))
            try
            {
                state = JsonSerializer.Deserialize<PersistedData>(File.ReadAllText(data)) ?? new();
                state.Folders ??= new();
                state.CompletedUploads ??= new();
                state.OutgoingFolders ??= new();
                foreach (var entry in state.OutgoingFolders)
                    if (Directory.Exists(entry.Value.Root)) outgoingFolders[entry.Key] = entry.Value;
            }
            catch (JsonException) { throw new IOException("Cannot safely load saved Nerqo state; restore state.json from backup."); }
    }
    private void Save()
    {
        // Always called while gate is locked; atomic rename prevents partial state updates.
        var output = Path.Combine(root, "state.json");
        var staging = output + ".tmp";
        File.WriteAllText(staging, JsonSerializer.Serialize(state));
        File.Move(staging, output, true);
        Changed?.Invoke();
    }
    [DllImport("kernel32.dll", EntryPoint = "CreateHardLinkW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateHardLink(string newFile, string existingFile, IntPtr securityAttributes);

    // Incoming files stay available both to the Windows user and Android, with
    // one physical copy on NTFS. This prevents a 100 GB upload from needing 200 GB.
    // Some filesystems do not support hard links, so copy is a safe fallback.
    private static void LinkOrCopy(string source, string target)
    {
        if (!OperatingSystem.IsWindows() || !CreateHardLink(target, source, IntPtr.Zero))
            File.Copy(source, target);
    }
    // Paths coming from an Android document provider are ALWAYS relative to a
    // server-created folder. Reject Windows device paths, traversal, and aliases.
    internal static string ValidFolderPath(string? raw)
    {
        if (string.IsNullOrEmpty(raw) || raw.Length > 2048 || raw.Contains('\\')
            || raw.StartsWith('/') || raw.EndsWith('/'))
            throw new BadHttpRequestException("Invalid relative folder path", 400);
        string[] segments = raw.Split('/');
        if (segments.Length > 64) throw new BadHttpRequestException("Folder nesting too deep", 400);
        foreach (string segment in segments)
        {
            ValidName(segment);
            if (segment.Length > 190 || segment != segment.TrimEnd(' ', '.')
                || segment.Any(c => char.IsControl(c)))
                throw new BadHttpRequestException("Unsafe folder name", 400);
            string stem = segment.Split('.')[0].ToUpperInvariant();
            if (stem is "CON" or "PRN" or "AUX" or "NUL"
                || Regex.IsMatch(stem, @"^(COM|LPT)[1-9]$"))
                throw new BadHttpRequestException("Reserved Windows device name", 400);
        }
        return raw;
    }

    // Only the server creates these paths, and each part is rechecked against
    // reparse-point redirects. Never trust a peer-provided absolute target.
    private string ResolveFolderPath(FolderRecord folder, string relative, bool directory)
    {
        string safe = ValidFolderPath(relative);
        string basePath = Path.Combine(ReceivedDirectory, folder.DirectoryName);
        if (!Directory.Exists(basePath)
            || (File.GetAttributes(basePath) & FileAttributes.ReparsePoint) != 0)
            throw new BadHttpRequestException("Folder destination is unavailable or redirected", 400);
        string current = basePath;
        string[] segments = safe.Split('/');
        int parentCount = directory ? segments.Length : segments.Length - 1;
        for (int i = 0; i < parentCount; i++)
        {
            current = Path.Combine(current, segments[i]);
            if (Directory.Exists(current)
                && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new BadHttpRequestException("Folder contains a redirected directory", 400);
            Directory.CreateDirectory(current);
        }
        return directory ? current : Path.Combine(current, segments[^1]);
    }

    private static string Hex(byte[] bytes) => Convert.ToHexString(bytes).ToLowerInvariant();
    private static string Digest(string secret) => Hex(SHA256.HashData(Encoding.UTF8.GetBytes(secret)));
    private static string Id() => Hex(RandomNumberGenerator.GetBytes(16));
    private static string ValidName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name) || name.Length > 200 || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0 || name is "." or ".." || name.Contains('/') || name.Contains('\\'))
            throw new BadHttpRequestException("Invalid file name", 400);
        return name;
    }
    private static string ValidId(string value) => Regex.IsMatch(value, "^[a-f0-9]{32}$") ? value : throw new BadHttpRequestException("Invalid ID", 400);
    private static string? Bearer(HttpContext ctx)
    {
        string? header = ctx.Request.Headers.Authorization.ToString();
        if (header?.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) == true) return header[7..].Trim();
        return ctx.Request.Headers["X-Nerqo-Token"].ToString();
    }
    private PeerRecord? Authorize(HttpContext ctx)
    {
        var token = Bearer(ctx);
        if (string.IsNullOrWhiteSpace(token) || token.Length > 256) return null;
        lock (gate)
        {
            state.Peers.TryGetValue(Digest(token), out var peer);
            return peer != null && peer.Expires > DateTimeOffset.UtcNow ? peer : null;
        }
    }
    private bool PairAllowed(string ip)
    {
        var q = attempts.GetOrAdd(ip, _ => new());
        lock (q)
        {
            while (q.Count > 0 && q.Peek() < DateTimeOffset.UtcNow.AddMinutes(-10)) q.Dequeue();
            if (q.Count >= 6) return false;
            q.Enqueue(DateTimeOffset.UtcNow);
            return true;
        }
    }
    private static async Task<JsonDocument> ReadJson(HttpRequest request, CancellationToken ct)
    {
        if (request.ContentLength is > 65536 or 0) throw new BadHttpRequestException("JSON request too large or empty", 413);
        // HttpClient can send Transfer-Encoding: chunked (no Content-Length).
        // Stream to a fixed cap instead of trusting the declared body size.
        using var memory = new MemoryStream();
        byte[] block = new byte[8192];
        while (true)
        {
            int n = await request.Body.ReadAsync(block, ct);
            if (n == 0) break;
            if (memory.Length + n > 65536) throw new BadHttpRequestException("JSON request too large", 413);
            memory.Write(block, 0, n);
        }
        if (memory.Length == 0) throw new BadHttpRequestException("Empty JSON request", 400);
        return JsonDocument.Parse(memory.ToArray(), new JsonDocumentOptions { MaxDepth = 12 });
    }
    private static string? Str(JsonElement json, string field) => json.TryGetProperty(field, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
    private static long Number(JsonElement json, string field) => json.TryGetProperty(field, out var v) && v.TryGetInt64(out long n) ? n : -1;
    private static IResult Unauthorized() => Results.Json(new { error = "Pair this device with the code shown on Windows." }, statusCode: 401);

    public async Task StartAsync()
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = Array.Empty<string>() });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(o =>
        {
            o.Limits.MaxRequestBodySize = ChunkLimit + 65536;
            o.ListenAnyIP(Port, endpoint => endpoint.UseHttps(tlsCertificate));
        });
        app = builder.Build();
        app.Use(async (ctx, next) =>
        {
            ctx.Response.Headers.CacheControl = "no-store";
            ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
            // Never expose transfers to a browser page from another origin.
            if (ctx.Request.Headers.ContainsKey("Origin")) { ctx.Response.StatusCode = 403; return; }
            try { await next(); }
            catch (BadHttpRequestException e) { if (!ctx.Response.HasStarted) { ctx.Response.StatusCode = e.StatusCode; await ctx.Response.WriteAsJsonAsync(new { error = e.Message }); } }
            catch (JsonException) { if (!ctx.Response.HasStarted) { ctx.Response.StatusCode = 400; await ctx.Response.WriteAsJsonAsync(new { error = "Invalid JSON" }); } }
        });
        app.MapGet("/api/status", (HttpContext ctx) => Results.Json(new { name = Environment.MachineName, authorized = Authorize(ctx) != null, port = Port, version = "1.0.6", transferProtocol = 2, band = WifiInspector.GetBand() }));
        app.MapPost("/api/pair", async (HttpContext ctx) =>
        {
            string ip = ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown";
            if (!PairAllowed(ip)) return Results.StatusCode(429);
            using var doc = await ReadJson(ctx.Request, ctx.RequestAborted);
            string? pin = Str(doc.RootElement, "pin");
            if (pin == null || !CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(pin), Encoding.UTF8.GetBytes(PairCode))) return Results.StatusCode(403);
            string name = (Str(doc.RootElement, "name") ?? "Android").Trim();
            if (name.Length is 0 or > 80) return Results.BadRequest();
            string token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
            lock (gate) { state.Peers[Digest(token)] = new PeerRecord { Name = name, Expires = DateTimeOffset.UtcNow.AddDays(30) }; Save(); }
            attempts.TryRemove(ip, out _);
            return Results.Json(new { ok = true, token });
        });
        app.MapPost("/api/disconnect", (HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized();
            RevokePeer(Digest(Bearer(ctx)!));
            return Results.Json(new { disconnected = true });
        });
        app.MapGet("/api/items", (HttpContext ctx) => Authorize(ctx) == null ? Unauthorized() : Results.Json(new { items = Items }));
        app.MapPost("/api/items", async (HttpContext ctx) =>
        {
            var peer = Authorize(ctx); if (peer == null) return Unauthorized();
            using var doc = await ReadJson(ctx.Request, ctx.RequestAborted);
            string? kind = Str(doc.RootElement, "kind"), content = Str(doc.RootElement, "content");
            if (kind is not ("text" or "link") || string.IsNullOrWhiteSpace(content) || Encoding.UTF8.GetByteCount(content) > 32768 || (kind == "link" && !(Uri.TryCreate(content, UriKind.Absolute, out var uri) && uri.Scheme is ("http" or "https")))) return Results.BadRequest();
            TransferItem item = new() { Id = Id(), Kind = kind, Sender = peer.Name, Content = content.Trim() };
            lock (gate) { state.Items.Add(item); Save(); }
            return Results.Json(new { id = item.Id }, statusCode: 201);
        });
        app.MapPost("/api/folders/begin", async (HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized();
            using var doc = await ReadJson(ctx.Request, ctx.RequestAborted);
            string name = ValidName(Str(doc.RootElement, "name"));
            ValidFolderPath(name);
            string owner = Digest(Bearer(ctx)!);
            string? resume = Str(doc.RootElement, "resumeId");
            lock (gate)
            {
                if (resume != null)
                {
                    if (state.Folders.TryGetValue(resume, out var claimed) && claimed.Owner != owner)
                        return Results.StatusCode(403);
                    if (!state.Folders.TryGetValue(resume, out var existing)
                        || existing.Name != name
                        || !Directory.Exists(Path.Combine(ReceivedDirectory, existing.DirectoryName)))
                        return Results.Conflict();
                    return Results.Json(new { id = resume, name = existing.Name });
                }
                string id = Id();
                string directoryName = name + " (" + id[..8] + ")";
                Directory.CreateDirectory(Path.Combine(ReceivedDirectory, directoryName));
                state.Folders[id] = new FolderRecord
                {
                    Owner = owner, Name = name, DirectoryName = directoryName
                };
                Save();
                return Results.Json(new { id, name });
            }
        });
        app.MapPost("/api/folders/{id}/directory", async (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized();
            ValidId(id);
            using var doc = await ReadJson(ctx.Request, ctx.RequestAborted);
            string relative = ValidFolderPath(Str(doc.RootElement, "path"));
            lock (gate)
            {
                if (!state.Folders.TryGetValue(id, out var folder)
                    || folder.Owner != Digest(Bearer(ctx)!)) return Results.StatusCode(403);
                ResolveFolderPath(folder, relative, true);
            }
            return Results.Json(new { ok = true });
        });
        app.MapPost("/api/transfers/begin", async (HttpContext ctx) =>
        {
            var peer = Authorize(ctx); if (peer == null) return Unauthorized();
            using var doc = await ReadJson(ctx.Request, ctx.RequestAborted);
            string name = ValidName(Str(doc.RootElement, "name"));
            long size = Number(doc.RootElement, "size");
            if (size < 0 || size > MaxFile) return Results.BadRequest();
            string? resume = Str(doc.RootElement, "resumeId");
            string folderId = Str(doc.RootElement, "folderId") ?? "";
            string relative = Str(doc.RootElement, "relativePath") ?? "";
            if (folderId.Length > 0)
            {
                ValidId(folderId);
                relative = ValidFolderPath(relative);
                if (relative.Split('/')[^1] != name) return Results.BadRequest();
            }
            else if (!string.IsNullOrEmpty(relative)) return Results.BadRequest();
            string owner = Digest(Bearer(ctx)!);
            lock (gate)
            {
                if (folderId.Length > 0
                    && (!state.Folders.TryGetValue(folderId, out var folder) || folder.Owner != owner))
                    return Results.StatusCode(403);
                if (folderId.Length > 0)
                {
                    var completed = state.Items.FirstOrDefault(i => i.FolderId == folderId
                        && i.Content == relative && i.Kind == "file" && i.Size == size);
                    if (completed != null && File.Exists(Path.Combine(StorageDirectory, completed.Id + ".bin")))
                        return Results.Json(new { id = completed.Id, complete = true, sha256 = completed.Sha256, size });
                }
                if (resume != null && state.CompletedUploads.TryGetValue(resume, out var receipt))
                {
                    var saved = receipt.Upload;
                    if (saved.Owner != owner) return Results.StatusCode(403);
                    if (saved.Name != name || saved.Size != size || saved.FolderId != folderId || saved.RelativePath != relative)
                        return Results.Conflict();
                    if (!File.Exists(Path.Combine(StorageDirectory, resume + ".bin"))) return Results.StatusCode(410);
                    return Results.Json(new { id = resume, complete = true, sha256 = receipt.Sha256, size });
                }
                if (resume != null && state.Uploads.TryGetValue(resume, out var existing))
                {
                    if (existing.Owner != owner) return Results.StatusCode(403);
                    if (existing.Name != name || existing.Size != size || existing.FolderId != folderId || existing.RelativePath != relative) return Results.Conflict();
                    string part = Part(resume);
                    if (!File.Exists(part)) return Results.Conflict();
                    existing.Offset = Math.Min(existing.Offset, new FileInfo(part).Length);
                    using (var file = new FileStream(part, FileMode.Open, FileAccess.Write, FileShare.None)) file.SetLength(existing.Offset);
                    Save();
                    return Results.Json(new { id = existing.Id, offset = existing.Offset, size = existing.Size });
                }
                if (resume != null) return Results.Conflict();
                string id = Id(); File.WriteAllBytes(Part(id), Array.Empty<byte>());
                state.Uploads[id] = new UploadRecord { Id = id, Owner = owner, Name = name, Size = size, FolderId = folderId, RelativePath = relative };
                Save(); return Results.Json(new { id, offset = 0L, size });
            }
        });
        app.MapPut("/api/transfers/{id}/chunk", async (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized();
            ValidId(id);
            long offset = long.TryParse(ctx.Request.Headers["X-Offset"].ToString(), out long val) ? val : -1;
            int length = ctx.Request.ContentLength is > 0 and <= ChunkLimit ? (int)ctx.Request.ContentLength.Value : -1;
            if (length < 1 || offset < 0) return Results.BadRequest();
            byte[] data = new byte[length];
            await ctx.Request.Body.ReadExactlyAsync(data, ctx.RequestAborted);
            string? hash = ctx.Request.Headers["X-SHA256"].ToString();
            if (hash is null || !Regex.IsMatch(hash, "^[a-fA-F0-9]{64}$")
                || !CryptographicOperations.FixedTimeEquals(
                    SHA256.HashData(data), Convert.FromHexString(hash)))
                return Results.UnprocessableEntity();
            lock (gate)
            {
                if (!state.Uploads.TryGetValue(id, out var t) || t.Owner != Digest(Bearer(ctx)!) || t.Offset != offset || offset + length > t.Size) return Results.Conflict();
                using var stream = new FileStream(Part(id), FileMode.Open, FileAccess.Write, FileShare.None);
                if (stream.Length != offset) return Results.Conflict();
                stream.Position = offset; stream.Write(data); stream.Flush(true);
                t.Offset += length; Save(); return Results.Json(new { offset = t.Offset });
            }
        });
        app.MapPost("/api/transfers/{id}/finish", (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) is not { } peer) return Unauthorized();
            ValidId(id);
            string expectedHash = ctx.Request.Query["sha256"].ToString();
            if (!Regex.IsMatch(expectedHash, "^[a-fA-F0-9]{64}$"))
                return Results.BadRequest();
            lock (gate)
            {
                if (state.CompletedUploads.TryGetValue(id, out var receipt))
                {
                    if (receipt.Upload.Owner != Digest(Bearer(ctx)!)) return Results.StatusCode(403);
                    if (!receipt.Sha256.Equals(expectedHash, StringComparison.OrdinalIgnoreCase)) return Results.Conflict();
                    return File.Exists(Path.Combine(StorageDirectory, id + ".bin"))
                        ? Results.Json(new { id, name = receipt.Upload.Name, size = receipt.Upload.Size, sha256 = receipt.Sha256 })
                        : Results.StatusCode(410);
                }
                if (!state.Uploads.TryGetValue(id, out var t) || t.Owner != Digest(Bearer(ctx)!) || t.Offset != t.Size || !File.Exists(Part(id)) || new FileInfo(Part(id)).Length != t.Size) return Results.Conflict();
                string hash;
                using (var read = File.OpenRead(Part(id))) hash = Hex(SHA256.HashData(read));
                if (!hash.Equals(expectedHash, StringComparison.OrdinalIgnoreCase))
                {
                    // Never publish a mixed file after the source was changed
                    // while a transfer was suspended. Force a fresh session.
                    state.Uploads.Remove(id);
                    File.Delete(Part(id));
                    Save();
                    return Results.UnprocessableEntity();
                }
                string target;
                if (t.FolderId.Length > 0)
                {
                    if (!state.Folders.TryGetValue(t.FolderId, out var folder)
                        || folder.Owner != t.Owner) return Results.StatusCode(403);
                    target = ResolveFolderPath(folder, t.RelativePath, false);
                    if (File.Exists(target) || Directory.Exists(target)) return Results.Conflict();
                }
                else target = UniqueReceivedName(t.Name);
                string finalPath = Path.Combine(StorageDirectory, id + ".bin");
                File.Move(Part(id), finalPath);
                try { LinkOrCopy(finalPath, target); }
                catch
                {
                    if (File.Exists(finalPath)) File.Move(finalPath, Part(id));
                    throw;
                }
                var item = new TransferItem { Id = id, Kind = "file", Sender = peer.Name,
                    Content = t.FolderId.Length > 0 ? t.RelativePath : t.Name,
                    FolderId = t.FolderId, Size = t.Size, Sha256 = hash };
                state.Items.Add(item);
                state.CompletedUploads[id] = new CompletedUploadRecord { Upload = t, Sha256 = hash };
                state.Uploads.Remove(id); Save();
                return Results.Json(new { id, name = t.Name, size = t.Size, sha256 = hash });
            }
        });
        app.MapDelete("/api/transfers/{id}", (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized();
            ValidId(id);
            string owner = Digest(Bearer(ctx)!);
            lock (gate)
            {
                if (state.CompletedUploads.TryGetValue(id, out var done))
                    return done.Upload.Owner == owner ? Results.Json(new { complete = true }) : Results.StatusCode(403);
                if (!state.Uploads.TryGetValue(id, out var upload)) return Results.NotFound();
                if (upload.Owner != owner) return Results.StatusCode(403);
                File.Delete(Part(id)); state.Uploads.Remove(id); Save();
                return Results.Json(new { cancelled = true });
            }
        });
        // A native folder never gets packed into ZIP. Android requests a
        // manifest and saves each member into its selected SAF document tree.
        app.MapGet("/api/outgoing-folders/{id}/manifest", (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized();
            ValidId(id);
            if (!outgoingFolders.TryGetValue(id, out var folder)) return Results.NotFound();
            return Results.Json(new
            {
                name = folder.Name,
                directories = folder.Directories,
                files = folder.Files.Values.OrderBy(f => f.Path).ToArray()
            });
        });
        app.MapGet("/api/outgoing-folders/{id}/file", (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized();
            ValidId(id);
            if (!outgoingFolders.TryGetValue(id, out var folder)) return Results.NotFound();
            string relative = ValidFolderPath(ctx.Request.Query["path"].ToString());
            if (!folder.Files.TryGetValue(relative, out var member)) return Results.NotFound();
            string physical = folder.Root;
            foreach (var component in relative.Split('/'))
            {
                physical = Path.Combine(physical, component);
                if (!File.Exists(physical) && !Directory.Exists(physical)) return Results.NotFound();
                if ((File.GetAttributes(physical) & FileAttributes.ReparsePoint) != 0)
                    return Results.StatusCode(403);
            }
            if (!File.Exists(physical) || new FileInfo(physical).Length != member.Size)
                return Results.Conflict();
            ctx.Response.Headers["X-SHA256"] = member.Sha256;
            return Results.File(physical, "application/octet-stream", enableRangeProcessing: true);
        });
        app.MapGet("/api/files/{id}", (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized();
            ValidId(id);
            TransferItem? item; lock (gate) item = state.Items.FirstOrDefault(x => x.Id == id && x.Kind == "file");
            if (item == null || !File.Exists(Path.Combine(StorageDirectory, id + ".bin"))) return Results.NotFound();
            ctx.Response.Headers["X-SHA256"] = item.Sha256;
            return Results.File(Path.Combine(StorageDirectory, id + ".bin"), "application/octet-stream", item.Content, enableRangeProcessing: true);
        });
        try { await app.StartAsync(); }
        catch { try { await app.DisposeAsync(); } catch { } app = null; throw; }
        discoveryCancellation = new CancellationTokenSource();
        discoveryTask = RunDiscovery(discoveryCancellation.Token);
    }
    private string Part(string id) => Path.Combine(StorageDirectory, id + ".part");
    private string UniqueReceivedName(string name)
    {
        var baseName = Path.GetFileNameWithoutExtension(name); var ext = Path.GetExtension(name);
        string path = Path.Combine(ReceivedDirectory, name); int i = 1;
        while (File.Exists(path)) path = Path.Combine(ReceivedDirectory, $"{baseName} ({i++}){ext}");
        return path;
    }
    public async Task ShareFileAsync(string path, CancellationToken ct = default)
    {
        var original = new FileInfo(path);
        string name = ValidName(original.Name), id = Id();
        string dest = Path.Combine(StorageDirectory, id + ".bin");
        await using (var src = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, true))
        await using (var target = new FileStream(dest, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1024 * 1024, true))
            await src.CopyToAsync(target, 1024 * 1024, ct);
        string hash; using (var data = File.OpenRead(dest)) hash = Hex(SHA256.HashData(data));
        lock (gate) { state.Items.Add(new TransferItem { Id = id, Kind = "file", Sender = "Windows", Content = name, Size = original.Length, Sha256 = hash }); Save(); }
    }
    /// <summary>
    /// Share a complete folder as a stream-created ZIP archive, preserving nested
    /// paths and empty directories. Reparse points are skipped to prevent the
    /// selected folder from escaping into unrelated filesystem locations.
    /// </summary>
    /// <summary>Native folder sharing: no archive and no duplicate storage.</summary>
    public async Task ShareFolderDirectAsync(string path, IProgress<int>? progress = null,
        CancellationToken ct = default)
    {
        var rootInfo = new DirectoryInfo(path);
        if (!rootInfo.Exists || rootInfo.Attributes.HasFlag(FileAttributes.ReparsePoint))
            throw new IOException("Choose an accessible folder, not a junction.");
        string folderName = ValidFolderPath(rootInfo.Name);
        var dirs = new List<string>();
        var memberFiles = new Dictionary<string, OutgoingFolderFile>(StringComparer.Ordinal);
        long total = 0;
        int entries = 0;
        async Task Scan(DirectoryInfo directory, string prefix, int depth)
        {
            if (depth > 64) throw new IOException("Folder is nested too deeply.");
            foreach (var entry in directory.EnumerateFileSystemInfos())
            {
                ct.ThrowIfCancellationRequested();
                if (++entries > 100000) throw new IOException("Too many folder members.");
                if (entry.Attributes.HasFlag(FileAttributes.ReparsePoint)) continue;
                string relative = ValidFolderPath(prefix.Length == 0
                    ? entry.Name : prefix + "/" + entry.Name);
                if (entry is DirectoryInfo sub)
                {
                    dirs.Add(relative);
                    await Scan(sub, relative, depth + 1);
                }
                else if (entry is FileInfo file)
                {
                    if (file.Length > MaxFile - total)
                        throw new IOException("Folder exceeds transfer limit.");
                    total += file.Length;
                    await using var stream = new FileStream(file.FullName, FileMode.Open,
                        FileAccess.Read, FileShare.Read, 1024 * 1024, FileOptions.Asynchronous);
                    string hash = Hex(await SHA256.HashDataAsync(stream, ct));
                    memberFiles.Add(relative, new OutgoingFolderFile(relative, file.Length, hash));
                }
                progress?.Report(entries);
            }
        }
        await Scan(rootInfo, "", 0);
        string id = Id();
        var share = new OutgoingFolder(rootInfo.FullName, folderName, dirs, memberFiles);
        outgoingFolders[id] = share;
        lock (gate)
        {
            state.OutgoingFolders[id] = share;
            state.Items.Add(new TransferItem
            {
                Id = id, Kind = "folder", Sender = "Windows",
                Content = folderName, Size = total,
            });
            Save();
        }
    }

    public async Task ShareFolderAsync(string path, IProgress<int>? progress = null, CancellationToken ct = default)
    {
        var directory = new DirectoryInfo(path);
        if (!directory.Exists || directory.Attributes.HasFlag(FileAttributes.ReparsePoint))
            throw new IOException("Select a real, accessible folder.");
        string folder = ValidName(directory.Name);
        string display = ValidName(folder[..Math.Min(folder.Length, 190)] + ".zip");
        string id = Id();
        string archivePath = Path.Combine(StorageDirectory, id + ".bin");
        int entries = 0;
        long totalBytes = 0;

        async Task AddDirectoryAsync(ZipArchive zip, DirectoryInfo dir, string prefix, int depth)
        {
            ct.ThrowIfCancellationRequested();
            if (depth > 64) throw new IOException("Folder nesting exceeds the safe limit of 64 levels.");
            if (++entries > 100000) throw new IOException("Folder contains too many items (limit: 100000).");
            zip.CreateEntry(prefix + "/", CompressionLevel.NoCompression);
            progress?.Report(entries);
            foreach (var child in dir.EnumerateFileSystemInfos())
            {
                ct.ThrowIfCancellationRequested();
                // Skip symlinks, junctions and other reparse points.
                if (child.Attributes.HasFlag(FileAttributes.ReparsePoint)) continue;
                string name = ValidName(child.Name);
                string relative = prefix + "/" + name;
                if (child is DirectoryInfo subdir)
                {
                    await AddDirectoryAsync(zip, subdir, relative, depth + 1);
                }
                else if (child is FileInfo file)
                {
                    if (++entries > 100000) throw new IOException("Folder has too many items.");
                    if (file.Length > MaxFile - totalBytes) throw new IOException("Folder is larger than the 2 TB transfer policy.");
                    totalBytes += file.Length;
                    progress?.Report(entries);
                    var entry = zip.CreateEntry(relative, CompressionLevel.Fastest);
                    await using var source = new FileStream(file.FullName, FileMode.Open, FileAccess.Read,
                        FileShare.Read, 1024 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
                    await using var target = entry.Open();
                    await source.CopyToAsync(target, 1024 * 1024, ct);
                }
            }
        }

        try
        {
            await using (var output = new FileStream(archivePath, FileMode.CreateNew, FileAccess.Write,
                FileShare.None, 1024 * 1024, FileOptions.Asynchronous))
            {
                using var zip = new ZipArchive(output, ZipArchiveMode.Create, leaveOpen: true);
                await AddDirectoryAsync(zip, directory, folder, 0);
            }
            long size = new FileInfo(archivePath).Length;
            if (size > MaxFile) throw new IOException("Compressed folder exceeds the transfer size limit.");
            string digest;
            using (var input = File.OpenRead(archivePath)) digest = Hex(SHA256.HashData(input));
            lock (gate)
            {
                state.Items.Add(new TransferItem
                {
                    Id = id, Kind = "file", Sender = "Windows", Content = display,
                    Size = size, Sha256 = digest
                });
                Save();
            }
        }
        catch
        {
            if (File.Exists(archivePath)) File.Delete(archivePath);
            throw;
        }
    }

    public void ShareNote(string content)
    {
        if (string.IsNullOrWhiteSpace(content) || Encoding.UTF8.GetByteCount(content) > 32768) throw new ArgumentException("Text must be 1–32768 bytes.");
        string kind = Uri.TryCreate(content.Trim(), UriKind.Absolute, out var link) && link.Scheme is ("http" or "https") ? "link" : "text";
        lock (gate) { state.Items.Add(new TransferItem { Id = Id(), Kind = kind, Sender = "Windows", Content = content.Trim() }); Save(); }
    }
    private async Task RunDiscovery(CancellationToken ct)
    {
        try
        {
            using var sock = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
            while (!ct.IsCancellationRequested)
            {
                var data = await sock.ReceiveAsync(ct);
                if (Encoding.ASCII.GetString(data.Buffer) != "NERQO_DISCOVER_V1") continue;
                var reply = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { app = "Nerqo", name = Environment.MachineName, port = Port, band = WifiInspector.GetBand() }));
                await sock.SendAsync(reply, data.RemoteEndPoint, ct);
            }
        }
        catch (OperationCanceledException) { }
        catch (SocketException) { /* another instance or firewall prevents discovery; LAN host still works */ }
    }
    public async ValueTask DisposeAsync()
    {
        if (discoveryCancellation != null) { await discoveryCancellation.CancelAsync(); if (discoveryTask != null) await discoveryTask; discoveryCancellation.Dispose(); }
        if (app != null) await app.DisposeAsync();
        tlsCertificate.Dispose();
    }
}
