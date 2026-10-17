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
using System.Threading.Channels;
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
    public bool LocalShare { get; set; }
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
public sealed record TransferHistoryEntry(string Key, string Name, string Peer, string Direction, string Status, long Size, DateTimeOffset When, string LocalPath = "");
public sealed record IncomingTransfer(string Name, long Done, long Size)
{
    public double Percent => Size > 0 ? Math.Clamp(Done * 100.0 / Size, 0, 100) : 0;
    public string Detail => $"{Done / 1048576.0:0.#} / {Size / 1048576.0:0.#} MB received";
}
public sealed record PairedDevice(string TokenHash, string Name, DateTimeOffset Expires, bool Suspended = false)
{ public string DisplayName => Name + (Suspended ? " · Disconnected" : " · Ready"); }
internal sealed class PeerRecord
{
    public bool Suspended { get; set; }
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
    private readonly Dictionary<string, (TransferItem Item, string Path, FileStream Lease)> directShares = new();
    private readonly Dictionary<string, LiveTransfer> live = new();
    private readonly HashSet<string> stopped = new();
    private readonly Dictionary<string, (long Done, long Ticks, double Rate)> incomingRates = new();
    public List<LiveTransfer> Transfers { get { lock (gate) return state.Uploads.Values.Select(x => new LiveTransfer(x.Id, x.Name, "Receiving", stopped.Contains(x.Id) ? "Paused on Windows" : "Ready / checkpoint saved", x.Offset, x.Size, (System.Diagnostics.Stopwatch.GetTimestamp() - incomingRates.GetValueOrDefault(x.Id).Ticks) < System.Diagnostics.Stopwatch.Frequency * 5L ? incomingRates.GetValueOrDefault(x.Id).Rate : 0)).Concat(live.Values).Take(200).ToList(); } }
    internal void StreamProgress(string id, long done, double rate, string status)
    {
        bool updated = false;
        lock (gate) if (live.TryGetValue(id, out var entry))
        {
            live[id] = entry with { Done = done, Rate = rate, Status = stopped.Contains(id) ? "Paused on Windows" : status };
            updated = true;
        }
        if (updated) Changed?.Invoke();
    }
    internal bool StreamStopped(string id) {
        lock (gate) return stopped.Contains(id) || (id.StartsWith("send:") && (!state.Peers.TryGetValue(id.Split(':')[1], out var peer) || peer.Suspended || peer.Expires <= DateTimeOffset.UtcNow));
    }
    public void ControlTransfer(string id, string action)
    {
        lock (gate)
        {
            if (action == "resume") stopped.Remove(id); else stopped.Add(id);
            if (action == "cancel" && state.Uploads.Remove(id, out var upload))
            {
                File.Delete(Part(id)); incomingRates.Remove(id);
                RecordHistory("cancelled:" + id, upload.Name, "Android", "Received", "Cancelled", upload.Size);
                Save();
            }
            if (live.TryGetValue(id, out var entry)) live[id] = entry with { Status = action == "resume" ? "Ready · Resume on Android" : action == "cancel" ? "Cancelled on Windows" : "Paused on Windows", Rate = 0 };
        }
        Changed?.Invoke();
    }
    private IResult TrackFile(HttpContext ctx, string path, string name, long size, string shareId, string member = "")
    {
        string id = "send:" + Digest(Bearer(ctx)!) + ":" + shareId + ":" + Digest(member);
        lock (gate)
        {
            if (stopped.Contains(id)) return Results.Conflict(new { error = "Paused on Windows. Resume there before retrying on Android." });
            live[id] = new(id, name, "Sending", "Starting", 0, size, 0);
            if (live.Count > 100) foreach (var old in live.Where(x => x.Value.Status is "Awaiting verification" or "Interrupted").Take(live.Count - 100).Select(x => x.Key).ToArray()) live.Remove(old);
        }
        Changed?.Invoke();
        return new TrackedFileResult(path, size, id, this);
    }
    public string? HistoryPath(TransferHistoryEntry entry)
    {
        if (entry.LocalPath.Length > 0 && (File.Exists(entry.LocalPath) || Directory.Exists(entry.LocalPath))) return entry.LocalPath;
        lock (gate)
        {
            string id = entry.Key.StartsWith("received:") ? entry.Key[9..] : "";
            string stored = Path.Combine(StorageDirectory, id + ".bin");
            return Regex.IsMatch(id, "^[a-f0-9]{32}$") && File.Exists(stored) ? stored : null;
        }
    }
    public void ClearHistory()
    {
        lock (gate) { File.WriteAllText(Path.Combine(root, "history.json.tmp"), "[]"); File.Move(Path.Combine(root, "history.json.tmp"), Path.Combine(root, "history.json"), true); history.Clear(); historyWritable = true; HistoryWarning = ""; }
        Changed?.Invoke();
    }
    private readonly object gate = new();
    private readonly ConcurrentDictionary<string, Queue<DateTimeOffset>> attempts = new();
    private readonly ConcurrentDictionary<string, OutgoingFolder> outgoingFolders = new();
    private readonly string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Nerqo");
    private PersistedData state = new();
    private List<TransferHistoryEntry> history = new();
    private bool historyWritable = true;
    public string HistoryWarning { get; private set; } = "";
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
    // A single volatile notification per listener is enough: subscribers fetch
    // fresh state through the normal authenticated endpoint after each signal.
    // No user data, bearer tokens or file contents are sent on this stream.
    private readonly ConcurrentDictionary<Guid, Channel<long>> eventListeners = new();
    private long eventRevision;
    private void NotifyEventListeners()
    {
        long revision = Interlocked.Increment(ref eventRevision);
        foreach (var channel in eventListeners.Values)
            channel.Writer.TryWrite(revision);
    }

    public List<TransferItem> Items { get { lock (gate) return state.Items.Concat(directShares.Values.Select(x => x.Item)).OrderByDescending(x => x.Created).Take(150).Select(x => x).ToList(); } }
    public List<PairedDevice> PairedDevices { get { lock (gate) return state.Peers.Where(x => x.Value.Expires > DateTimeOffset.UtcNow).Select(x => new PairedDevice(x.Key, x.Value.Name, x.Value.Expires, x.Value.Suspended)).OrderBy(x => x.Name).ToList(); } }
    public List<TransferHistoryEntry> History { get { lock (gate) return history.OrderByDescending(x => x.When).Take(500).ToList(); } }
    public List<IncomingTransfer> Incoming { get { lock (gate) return state.Uploads.Values.Select(x => new IncomingTransfer(x.Name, x.Offset, x.Size)).ToList(); } }
    private void RecordHistory(string key, string name, string peer, string direction, string status, long size, string localPath = "")
    {
        if (history.Any(x => x.Key == key)) return;
        history.Add(new(key, name, peer, direction, status, size, DateTimeOffset.UtcNow, localPath));
        if (history.Count > 500) history.RemoveRange(0, history.Count - 500);
        if (!historyWritable) return;
        // The log is separate from frequent upload checkpoints. Logging failures must not fail a verified delivery.
        string target = Path.Combine(root, "history.json");
        try
        {
            File.WriteAllText(target + ".tmp", JsonSerializer.Serialize(history));
            File.Move(target + ".tmp", target, true);
            HistoryWarning = "";
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        { HistoryWarning = "History could not be saved. New entries are currently in memory only."; }
    }
    public bool SetPeerSuspended(string tokenHash, bool suspended)
    {
        lock (gate)
        {
            if (!state.Peers.TryGetValue(tokenHash, out var peer) || peer.Expires <= DateTimeOffset.UtcNow) return false;
            peer.Suspended = suspended; Save(); return true;
        }
    }
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
        string historyFile = Path.Combine(root, "history.json");
        if (File.Exists(historyFile))
            try { history = (JsonSerializer.Deserialize<List<TransferHistoryEntry>>(File.ReadAllText(historyFile)) ?? new()).TakeLast(500).ToList(); }
            catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
            {
                historyWritable = false;
                HistoryWarning = "Saved History could not be read. Its file is unchanged; new entries are currently in memory only.";
            }
    }
    private void Save()
    {
        // Always called while gate is locked; atomic rename prevents partial state updates.
        var output = Path.Combine(root, "state.json");
        var staging = output + ".tmp";
        File.WriteAllText(staging, JsonSerializer.Serialize(state));
        File.Move(staging, output, true);
        NotifyEventListeners();
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
    private PeerRecord? Authorize(HttpContext ctx, bool allowSuspended = false)
    {
        var token = Bearer(ctx);
        if (string.IsNullOrWhiteSpace(token) || token.Length > 256) return null;
        lock (gate)
        {
            state.Peers.TryGetValue(Digest(token), out var peer);
            return peer != null && peer.Expires > DateTimeOffset.UtcNow && (allowSuspended || !peer.Suspended) ? peer : null;
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
    private IResult Unauthorized(HttpContext ctx) => Authorize(ctx, true)?.Suspended == true
        ? Results.Json(new { error = "Connection paused. Choose Reconnect on either device." }, statusCode: 423)
        : Results.Json(new { error = "Pair this device with the code shown on Windows." }, statusCode: 401);

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
        app.MapGet("/api/status", (HttpContext ctx) => Results.Json(new { name = Environment.MachineName, authorized = Authorize(ctx) != null, port = Port, version = typeof(LanHost).Assembly.GetName().Version?.ToString(3) ?? "unknown", transferProtocol = 2, band = WifiInspector.GetBand() }));
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
            if (Authorize(ctx) == null) return Unauthorized(ctx);
            SetPeerSuspended(Digest(Bearer(ctx)!), true);
            return Results.Json(new { disconnected = true });
        });
        app.MapPost("/api/reconnect", (HttpContext ctx) =>
        {
            if (Authorize(ctx, true) == null) return Unauthorized(ctx);
            SetPeerSuspended(Digest(Bearer(ctx)!), false);
            return Results.Json(new { ok = true });
        });
        app.MapPost("/api/forget", (HttpContext ctx) =>
        {
            if (Authorize(ctx, true) == null) return Unauthorized(ctx);
            RevokePeer(Digest(Bearer(ctx)!)); return Results.Json(new { ok = true });
        });
        app.MapPost("/api/receipts", async (HttpContext ctx) =>
        {
            var peer = Authorize(ctx); if (peer == null) return Unauthorized(ctx);
            using var doc = await ReadJson(ctx.Request, ctx.RequestAborted);
            var body = doc.RootElement;
            string id = Str(body, "id") ?? "";
            if (!Guid.TryParse(Str(body, "session"), out var session)) return Results.BadRequest();
            long size = body.TryGetProperty("size", out var supplied) && supplied.ValueKind == JsonValueKind.Number && supplied.TryGetInt64(out var bytes) ? bytes : -1;
            string hash = Str(body, "sha256") ?? "";
            lock (gate)
            {
                var item = state.Items.Concat(directShares.Values.Select(x => x.Item)).FirstOrDefault(x => x.Id == id && x.LocalShare && x.Kind is "file" or "folder");
                if (item == null || item.Size != size || item.Sha256 != hash) return Results.BadRequest();
                foreach (var key in live.Keys.Where(k => k.StartsWith("send:" + Digest(Bearer(ctx)!) + ":" + id + ":")).ToArray()) live.Remove(key);
                RecordHistory("sent:" + Digest(Bearer(ctx)!) + ":" + session.ToString("N"), item.Content, peer.Name, "Sent", "Completed", item.Size, directShares.TryGetValue(id, out var source) ? source.Path : item.Kind == "file" ? Path.Combine(StorageDirectory, id + ".bin") : outgoingFolders.TryGetValue(id, out var sharedFolder) ? sharedFolder.Root : "");
                Save();
            }
            return Results.Json(new { confirmed = true });
        });
        // HTTP Server-Sent Events: immediate notifications, no periodic GET
        // refresh, with a small keepalive comment to detect broken Wi-Fi links.
        // Android reconnects and resynchronizes after any interruption.
        app.MapGet("/api/events", async (HttpContext ctx) =>
        {
            if (Authorize(ctx) == null)
            {
                var denied = Unauthorized(ctx);
                await denied.ExecuteAsync(ctx);
                return;
            }
            ctx.Response.ContentType = "text/event-stream";
            ctx.Response.Headers.CacheControl = "no-cache, no-store";
            ctx.Response.Headers["X-Accel-Buffering"] = "no";
            var channel = Channel.CreateBounded<long>(new BoundedChannelOptions(1)
            {
                FullMode = BoundedChannelFullMode.DropOldest,
                SingleReader = true,
                SingleWriter = false
            });
            Guid id = Guid.NewGuid();
            eventListeners[id] = channel;
            try
            {
                // Always synchronize on subscribe, so reconnect never misses a
                // change that happened while the phone was disconnected.
                await ctx.Response.WriteAsync("event: changed\ndata: " + Interlocked.Read(ref eventRevision) + "\n\n", ctx.RequestAborted);
                await ctx.Response.Body.FlushAsync(ctx.RequestAborted);
                while (!ctx.RequestAborted.IsCancellationRequested)
                {
                    using var wait = CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted);
                    wait.CancelAfter(TimeSpan.FromSeconds(20));
                    long revision;
                    try { revision = await channel.Reader.ReadAsync(wait.Token); }
                    catch (OperationCanceledException) when (!ctx.RequestAborted.IsCancellationRequested)
                    {
                        // A protocol heartbeat is not a data refresh. It allows
                        // intermediaries and clients to detect idle TCP failures.
                        if (Authorize(ctx) == null) break;
                        await ctx.Response.WriteAsync(": keepalive\n\n", ctx.RequestAborted);
                        await ctx.Response.Body.FlushAsync(ctx.RequestAborted);
                        continue;
                    }
                    if (Authorize(ctx) == null) break;
                    await ctx.Response.WriteAsync("event: changed\ndata: " + revision + "\n\n", ctx.RequestAborted);
                    await ctx.Response.Body.FlushAsync(ctx.RequestAborted);
                }
            }
            catch (OperationCanceledException) when (ctx.RequestAborted.IsCancellationRequested) { }
            catch (IOException) { /* Client switched Wi-Fi or suspended the app. */ }
            finally { eventListeners.TryRemove(id, out _); channel.Writer.TryComplete(); }
        });
        app.MapGet("/api/items", (HttpContext ctx) => Authorize(ctx) == null ? Unauthorized(ctx) : Results.Json(new { items = Items }));
        app.MapPost("/api/items", async (HttpContext ctx) =>
        {
            var peer = Authorize(ctx); if (peer == null) return Unauthorized(ctx);
            using var doc = await ReadJson(ctx.Request, ctx.RequestAborted);
            string? kind = Str(doc.RootElement, "kind"), content = Str(doc.RootElement, "content");
            if (kind is not ("text" or "link") || string.IsNullOrWhiteSpace(content) || Encoding.UTF8.GetByteCount(content) > 32768 || (kind == "link" && !(Uri.TryCreate(content, UriKind.Absolute, out var uri) && uri.Scheme is ("http" or "https")))) return Results.BadRequest();
            TransferItem item = new() { Id = Id(), Kind = kind, Sender = peer.Name, Content = content.Trim() };
            lock (gate) { state.Items.Add(item); Save(); }
            return Results.Json(new { id = item.Id }, statusCode: 201);
        });
        app.MapPost("/api/folders/begin", async (HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized(ctx);
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
            if (Authorize(ctx) == null) return Unauthorized(ctx);
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
            var peer = Authorize(ctx); if (peer == null) return Unauthorized(ctx);
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
            if (Authorize(ctx) == null) return Unauthorized(ctx);
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
                if (Authorize(ctx) == null) return Unauthorized(ctx);
                if (stopped.Contains(id)) return Results.Conflict(new { error = "Paused on Windows" });
                if (!state.Uploads.TryGetValue(id, out var t) || t.Owner != Digest(Bearer(ctx)!) || t.Offset != offset || offset + length > t.Size) return Results.Conflict();
                using var stream = new FileStream(Part(id), FileMode.Open, FileAccess.Write, FileShare.None);
                if (stream.Length != offset) return Results.Conflict();
                stream.Position = offset; stream.Write(data); stream.Flush(true);
                t.Offset += length;
                long now = System.Diagnostics.Stopwatch.GetTimestamp();
                var previous = incomingRates.GetValueOrDefault(id);
                double seconds = previous.Ticks > 0 ? (now - previous.Ticks) / (double)System.Diagnostics.Stopwatch.Frequency : 0;
                incomingRates[id] = (t.Offset, now, seconds > 0 ? (t.Offset - previous.Done) / seconds : 0);
                Save(); return Results.Json(new { offset = t.Offset });
            }
        });
        app.MapPost("/api/transfers/{id}/finish", (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) is not { } peer) return Unauthorized(ctx);
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
                    RecordHistory("failed:" + id, t.Name, peer.Name, "Received", "Integrity failed", t.Size);
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
                RecordHistory("received:" + id, item.Content, peer.Name, "Received", "Completed", item.Size, target);
                state.CompletedUploads[id] = new CompletedUploadRecord { Upload = t, Sha256 = hash };
                state.Uploads.Remove(id); Save();
                return Results.Json(new { id, name = t.Name, size = t.Size, sha256 = hash });
            }
        });
        app.MapDelete("/api/transfers/{id}", (string id, HttpContext ctx) =>
        {
            var peer = Authorize(ctx); if (peer == null) return Unauthorized(ctx);
            ValidId(id);
            string owner = Digest(Bearer(ctx)!);
            lock (gate)
            {
                if (state.CompletedUploads.TryGetValue(id, out var done))
                    return done.Upload.Owner == owner ? Results.Json(new { complete = true }) : Results.StatusCode(403);
                if (!state.Uploads.TryGetValue(id, out var upload)) return Results.NotFound();
                if (upload.Owner != owner) return Results.StatusCode(403);
                RecordHistory("cancelled:" + id, upload.Name, peer.Name, "Received", "Cancelled", upload.Size);
                File.Delete(Part(id)); state.Uploads.Remove(id); Save();
                return Results.Json(new { cancelled = true });
            }
        });
        // A native folder never gets packed into ZIP. Android requests a
        // manifest and saves each member into its selected SAF document tree.
        app.MapGet("/api/outgoing-folders/{id}/manifest", (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized(ctx);
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
            if (Authorize(ctx) == null) return Unauthorized(ctx);
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
            return TrackFile(ctx, physical, relative, member.Size, id, relative);
        });
        app.MapGet("/api/files/{id}", (string id, HttpContext ctx) =>
        {
            if (Authorize(ctx) == null) return Unauthorized(ctx);
            ValidId(id);
            TransferItem? item; string path;
            lock (gate)
            {
                item = state.Items.FirstOrDefault(x => x.Id == id && x.Kind == "file");
                path = Path.Combine(StorageDirectory, id + ".bin");
                if (directShares.TryGetValue(id, out var source)) { item = source.Item; path = source.Path; }
            }
            if (item == null || !File.Exists(path)) return Results.NotFound();
            ctx.Response.Headers["X-SHA256"] = item.Sha256;
            return TrackFile(ctx, path, item.Content, item.Size, id);
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
    /// <summary>Optional faster preparation: no duplicate copy; keep a Windows read lease until app exit.</summary>
    public async Task ShareFileDirectAsync(string path, CancellationToken ct = default, Action<long, long>? progress = null)
    {
        string id = Id();
        var source = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, true);
        bool retained = false;
        try
        {
            long size = source.Length;
            if (size > MaxFile) throw new IOException("File exceeds the supported size limit");
            using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            byte[] buffer = new byte[1024 * 1024]; long done = 0; int lastPercent = -1;
            progress?.Invoke(0, size);
            while (true)
            {
                int count = await source.ReadAsync(buffer, ct); if (count == 0) break;
                digest.AppendData(buffer, 0, count); done += count;
                int percent = size > 0 ? (int)(done * 100 / size) : 100;
                if (percent != lastPercent) { progress?.Invoke(done, size); lastPercent = percent; }
            }
            ct.ThrowIfCancellationRequested();
            if (done != size) throw new IOException("Source changed during preparation");
            var item = new TransferItem { Id = id, Kind = "file", Content = ValidName(Path.GetFileName(path)), Size = size, Sha256 = Hex(digest.GetHashAndReset()), Sender = "Windows", LocalShare = true };
            lock (gate) directShares[id] = (item, Path.GetFullPath(path), source);
            retained = true; progress?.Invoke(size, size); Changed?.Invoke();
        }
        finally { if (!retained) await source.DisposeAsync(); }
    }
    public async Task ShareFileAsync(string path, CancellationToken ct = default, Action<long, long>? progress = null)
    {
        string name = ValidName(Path.GetFileName(path)), id = Id();
        string dest = Path.Combine(StorageDirectory, id + ".bin");
        bool complete = false;
        try
        {
            string hash; long size;
            await using (var src = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024, true))
            {
                size = src.Length;
                if (size > MaxFile) throw new IOException("File exceeds the supported size limit");
                using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                await using (var target = new FileStream(dest, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1024 * 1024, true))
                {
                    byte[] buffer = new byte[1024 * 1024]; long done = 0; int lastPercent = -1;
                    progress?.Invoke(0, size);
                    while (true)
                    {
                        int read = await src.ReadAsync(buffer, ct);
                        if (read == 0) break;
                        await target.WriteAsync(buffer.AsMemory(0, read), ct);
                        digest.AppendData(buffer, 0, read); done += read;
                        int percent = size > 0 ? (int)Math.Min(100, done * 100 / size) : 100;
                        if (percent != lastPercent) { progress?.Invoke(done, size); lastPercent = percent; }
                    }
                    if (done != size) throw new IOException("The source changed during preparation");
                    await target.FlushAsync(ct);
                    hash = Hex(digest.GetHashAndReset());
                }
            }
            complete = true;
            lock (gate) { state.Items.Add(new TransferItem { Id = id, Kind = "file", Sender = "Windows", LocalShare = true, Content = name, Size = size, Sha256 = hash }); Save(); }
            progress?.Invoke(size, size);
        }
        finally { if (!complete && File.Exists(dest)) File.Delete(dest); }
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
                Id = id, Kind = "folder", Sender = "Windows", LocalShare = true,
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
                    Id = id, Kind = "file", Sender = "Windows", LocalShare = true, Content = display,
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
        lock (gate) { state.Items.Add(new TransferItem { Id = Id(), Kind = kind, Sender = "Windows", LocalShare = true, Content = content.Trim() }); Save(); }
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
        lock (gate) { foreach (var source in directShares.Values) source.Lease.Dispose(); directShares.Clear(); }
        tlsCertificate.Dispose();
    }
}
