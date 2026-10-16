using Nerqo.Desktop.Services;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

// Read-side firewall decisions must reject partial, public-only and blocking rules.
static void TestFirewallDecisions()
{
    const string app = @"C:\Nerqo\Nerqo.exe";
    var tcp = new FirewallRuleView(app, true, 1, 1, 6, "44888", 2, "LocalSubnet");
    var udp = tcp with { Protocol = 17, Ports = "44889" };
    var allow = new[] { tcp, udp };
    if (!FirewallAccess.Evaluate(allow, app).Covered) throw new Exception("Valid private rules rejected");
    foreach (var invalid in new[] { tcp with { Enabled = false }, tcp with { Profiles = 4 }, tcp with { Ports = "44889" },
        tcp with { Program = @"C:\Other\Nerqo.exe" }, tcp with { Direction = 2 }, tcp with { RemoteAddresses = "192.168.1.2" },
        tcp with { Service = "OtherService" }, tcp with { RemotePorts = "123" } })
        if (FirewallAccess.Evaluate(new[] { invalid, udp }, app).Covered) throw new Exception("Invalid private rule accepted");
    if (!FirewallAccess.Evaluate(new[] { tcp, udp, tcp with { Action = 0 } }, app).Blocked)
        throw new Exception("Explicit application block ignored");
    if (FirewallAccess.Evaluate(new[] { tcp, udp, tcp with { Program = "", Action = 0, Protocol = 256 } }, app).Blocked)
        throw new Exception("An unattributed programless rule falsely blocked the desktop app");
    var generalBlock = tcp with { Program = "", Action = 0, Protocol = 256 };
    foreach (var scoped in new[] { generalBlock with { Service = "OtherWindowsService" },
        generalBlock with { LocalAddresses = "127.0.0.1" }, generalBlock with { RemoteAddresses = "203.0.113.1" },
        generalBlock with { Interfaces = "RemoteAccess" }, generalBlock with { RemotePorts = "53" },
        tcp with { Action = 0, Service = "OtherWindowsService" } })
        if (FirewallAccess.Evaluate(new[] { tcp, udp, scoped }, app).Blocked)
            throw new Exception("An unrelated/scoped rule falsely blocked the desktop app");
    if (!FirewallAccess.Evaluate(new[] { tcp with { Ports = "44000-45000" }, udp }, app).Covered)
        throw new Exception("Port range allow rule rejected");
}
TestFirewallDecisions();
static void TestSingleInstance()
{
    string key = "test-" + Guid.NewGuid().ToString("N");
    using (var owner = SingleInstance.Claim(key) ?? throw new Exception("First instance rejected"))
    {
        bool duplicate = Task.Run(() =>
        {
            using var second = SingleInstance.Claim(key);
            return second != null;
        }).GetAwaiter().GetResult();
        if (duplicate || !owner.WaitForWake(3000)) throw new Exception("Duplicate app was not rejected or did not signal owner");
    }
    bool reopened = Task.Run(() =>
    {
        using var next = SingleInstance.Claim(key);
        return next != null;
    }).GetAwaiter().GetResult();
    if (!reopened) throw new Exception("Application could not reopen after exit");
}
TestSingleInstance();


if (!NativeUpdateService.IsNewerVersion("v0.6.0", new Version(0,5,0))) throw new Exception("Native update version detection failed");
if (NativeUpdateService.IsNewerVersion("v0.5.0", new Version(0,5,0))) throw new Exception("Same version should not upgrade");
if (NativeUpdateService.IsNewerVersion("v0.3.0", new Version(0,5,0))) throw new Exception("Legacy release should not downgrade");
if (NativeUpdateService.IsNewerVersion("v0.7.0-preview", new Version(0,5,0))) throw new Exception("Pre-release tag should be rejected");
if (!LanHost.IsCandidateAddress(System.Net.IPAddress.Parse("192.168.43.1"))
    || !LanHost.IsCandidateAddress(System.Net.IPAddress.Parse("10.0.0.10"))
    || !LanHost.IsCandidateAddress(System.Net.IPAddress.Parse("172.16.10.1"))
    || LanHost.IsCandidateAddress(System.Net.IPAddress.Parse("8.8.8.8"))
    || LanHost.IsCandidateAddress(System.Net.IPAddress.Loopback))
    throw new Exception("LAN address selection failed");

static void TestNativeDelta()
{
    string folder = Path.Combine(Path.GetTempPath(), "nerqo-delta-test-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(folder);
    try
    {
        byte[] original = Encoding.UTF8.GetBytes("native installed setup binary");
        byte[] extra = Encoding.UTF8.GetBytes(" -- patched content");
        byte[] updated = original.Concat(extra).ToArray();
        string basePath = Path.Combine(folder, "old.exe"), patchPath = Path.Combine(folder, "update.nrqd");
        string target = Path.Combine(folder, "new.exe"), corruptedTarget = Path.Combine(folder, "bad.exe");
        File.WriteAllBytes(basePath, original);
        using (var patch = File.Create(patchPath))
        {
            byte[] header = new byte[88];
            Encoding.ASCII.GetBytes("NRQDEL01").CopyTo(header, 0);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt64BigEndian(header.AsSpan(8, 8), (ulong)original.Length);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt64BigEndian(header.AsSpan(16, 8), (ulong)updated.Length);
            SHA256.HashData(original).CopyTo(header, 24);
            SHA256.HashData(updated).CopyTo(header, 56);
            patch.Write(header);
            patch.WriteByte((byte)'C');
            byte[] copy = new byte[12];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(copy.AsSpan(8, 4), (uint)original.Length);
            patch.Write(copy);
            using var packed = new MemoryStream();
            using (var z = new System.IO.Compression.ZLibStream(packed, System.IO.Compression.CompressionLevel.Optimal, leaveOpen:true))
                z.Write(extra);
            patch.WriteByte((byte)'D');
            byte[] sizes = new byte[8];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(sizes.AsSpan(0, 4), (uint)extra.Length);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(sizes.AsSpan(4, 4), (uint)packed.Length);
            patch.Write(sizes);
            patch.Write(packed.ToArray());
            patch.WriteByte((byte)'E');
        }
        NativeDelta.Apply(basePath, patchPath, target);
        if (!updated.SequenceEqual(File.ReadAllBytes(target))) throw new Exception("Delta reconstruction was incorrect");
        byte[] broken = File.ReadAllBytes(patchPath);
        broken[87] ^= 0xff;  // Mutate declared target hash.
        File.WriteAllBytes(patchPath, broken);
        try { NativeDelta.Apply(basePath, patchPath, corruptedTarget); throw new Exception("Corrupt delta was accepted"); }
        catch (InvalidDataException) { }
        if (File.Exists(corruptedTarget)) throw new Exception("Corrupt reconstructed file was not deleted");
    }
    finally { Directory.Delete(folder, recursive: true); }
}
TestNativeDelta();

await using var host = new LanHost();
await host.StartAsync();
await using (var secondInstance = new LanHost())
    if (host.ServerFingerprint != secondInstance.ServerFingerprint) throw new Exception("LAN identity unexpectedly changed");
using var handler = new HttpClientHandler { ServerCertificateCustomValidationCallback = (_, cert, _, _) => cert != null && cert.GetCertHashString(HashAlgorithmName.SHA256).Equals(host.ServerFingerprint, StringComparison.OrdinalIgnoreCase) };
using var client = new HttpClient(handler) { BaseAddress = new Uri("https://127.0.0.1:44888"), Timeout = TimeSpan.FromSeconds(20) };
var status = await client.GetFromJsonAsync<JsonElement>("/api/status");
if (status.GetProperty("port").GetInt32() != 44888) throw new Exception("Status endpoint failed");
if ((await client.GetAsync("/api/items")).StatusCode != System.Net.HttpStatusCode.Unauthorized) throw new Exception("Unpaired access was permitted");
var bad = await client.PostAsJsonAsync("/api/pair", new { pin = "0000000", name = "Smoke" });
if (bad.IsSuccessStatusCode) throw new Exception("Incorrect pairing code accepted");
var pair = await client.PostAsJsonAsync("/api/pair", new { pin = host.PairCode, name = "Smoke" });
pair.EnsureSuccessStatusCode();
string token = (await pair.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;
client.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
var note = await client.PostAsJsonAsync("/api/items", new { kind = "link", content = "https://example.com/" });
note.EnsureSuccessStatusCode();
var uploaded = Encoding.UTF8.GetBytes("Nerqo native end-to-end roundtrip via local network");
var begin = await client.PostAsJsonAsync("/api/transfers/begin", new { name = "native.txt", size = uploaded.Length });
begin.EnsureSuccessStatusCode();
string id = (await begin.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
using var chunk = new ByteArrayContent(uploaded);
chunk.Headers.Add("X-Offset", "0");
chunk.Headers.Add("X-SHA256", Convert.ToHexString(SHA256.HashData(uploaded)).ToLowerInvariant());
using (var noHash = new ByteArrayContent(uploaded))
{
    noHash.Headers.Add("X-Offset", "0");
    var response = await client.PutAsync($"/api/transfers/{id}/chunk", noHash);
    if (response.StatusCode != System.Net.HttpStatusCode.UnprocessableEntity) throw new Exception("Missing chunk checksum accepted");
}
using (var wrongHash = new ByteArrayContent(uploaded))
{
    wrongHash.Headers.Add("X-Offset", "0");
    wrongHash.Headers.Add("X-SHA256", new string('0', 64));
    var response = await client.PutAsync($"/api/transfers/{id}/chunk", wrongHash);
    if (response.StatusCode != System.Net.HttpStatusCode.UnprocessableEntity) throw new Exception("Invalid chunk checksum accepted");
}
var put = await client.PutAsync($"/api/transfers/{id}/chunk", chunk);
put.EnsureSuccessStatusCode();
var finish = await client.PostAsJsonAsync($"/api/transfers/{id}/finish?sha256={Convert.ToHexString(SHA256.HashData(uploaded)).ToLowerInvariant()}", new {});
finish.EnsureSuccessStatusCode();
if ((await client.PostAsJsonAsync($"/api/transfers/{id}/finish", new {})).StatusCode
    != System.Net.HttpStatusCode.BadRequest)
    throw new Exception("File completion without SHA-256 must be rejected");
var received = Path.Combine(host.ReceivedDirectory, "native.txt");
if (!File.Exists(received) || !uploaded.SequenceEqual(await File.ReadAllBytesAsync(received)))
    throw new Exception("Android upload was not saved to the Windows Received folder");
// A lost finish response must not create a second published file.
int completedCount = host.Items.Count;
var retryBegin = await client.PostAsJsonAsync("/api/transfers/begin", new { name = "native.txt", size = uploaded.Length, resumeId = id });
retryBegin.EnsureSuccessStatusCode();
var receipt = await retryBegin.Content.ReadFromJsonAsync<JsonElement>();
if (!receipt.GetProperty("complete").GetBoolean() || receipt.GetProperty("id").GetString() != id
    || receipt.GetProperty("sha256").GetString() != Convert.ToHexString(SHA256.HashData(uploaded)).ToLowerInvariant())
    throw new Exception("Completed upload receipt did not survive retry");
var retryFinish = await client.PostAsJsonAsync($"/api/transfers/{id}/finish?sha256={Convert.ToHexString(SHA256.HashData(uploaded)).ToLowerInvariant()}", new {});
retryFinish.EnsureSuccessStatusCode();
if (host.Items.Count != completedCount) throw new Exception("Finish retry duplicated a file");
if ((await client.PostAsJsonAsync($"/api/transfers/{id}/finish?sha256={new string('0', 64)}", new {})).StatusCode != System.Net.HttpStatusCode.Conflict)
    throw new Exception("Receipt accepted a different checksum");
if ((await client.PostAsJsonAsync("/api/transfers/begin", new { name = "wrong.txt", size = uploaded.Length, resumeId = id })).StatusCode != System.Net.HttpStatusCode.Conflict)
    throw new Exception("Receipt was replayed for a different file");
if ((await client.PostAsJsonAsync("/api/transfers/begin", new { name = "native.txt", size = uploaded.Length, resumeId = new string('f', 32) })).StatusCode != System.Net.HttpStatusCode.Conflict)
    throw new Exception("Unknown session silently restarted");
var cancellable = await client.PostAsJsonAsync("/api/transfers/begin", new { name = "cancel.txt", size = 100 });
cancellable.EnsureSuccessStatusCode();
string cancelId = (await cancellable.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
var otherPair = await client.PostAsJsonAsync("/api/pair", new { pin = host.PairCode, name = "Other smoke device" });
otherPair.EnsureSuccessStatusCode();
string otherToken = (await otherPair.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("token").GetString()!;
client.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", otherToken);
if ((await client.DeleteAsync($"/api/transfers/{cancelId}")).StatusCode != System.Net.HttpStatusCode.Forbidden
    || (await client.PostAsJsonAsync("/api/transfers/begin", new { name = "native.txt", size = uploaded.Length, resumeId = id })).StatusCode != System.Net.HttpStatusCode.Forbidden)
    throw new Exception("Another paired device could cancel or replay the upload session");
client.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
(await client.DeleteAsync($"/api/transfers/{cancelId}")).EnsureSuccessStatusCode();
if (File.Exists(Path.Combine(host.StorageDirectory, cancelId + ".part"))) throw new Exception("Cancelled partial still exists");
if ((await client.PostAsJsonAsync("/api/transfers/begin", new { name = "cancel.txt", size = 100, resumeId = cancelId })).StatusCode != System.Net.HttpStatusCode.Conflict)
    throw new Exception("Cancelled session could still resume");
(await client.DeleteAsync($"/api/transfers/{id}")).EnsureSuccessStatusCode();
if (!File.Exists(Path.Combine(host.StorageDirectory, id + ".bin"))) throw new Exception("Cancel deleted a completed file");
using (var rangeRequest = new HttpRequestMessage(HttpMethod.Get, $"/api/files/{id}"))
{
    rangeRequest.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(7, null);
    using var rangeResponse = await client.SendAsync(rangeRequest);
    if (rangeResponse.StatusCode != System.Net.HttpStatusCode.PartialContent
        || rangeResponse.Content.Headers.ContentRange?.From != 7
        || rangeResponse.Content.Headers.ContentRange?.Length != uploaded.Length
        || !(await rangeResponse.Content.ReadAsByteArrayAsync()).SequenceEqual(uploaded.Skip(7)))
        throw new Exception("Range download did not honor the committed byte offset");
}
var downloaded = await client.GetByteArrayAsync($"/api/files/{id}");
if (!uploaded.SequenceEqual(downloaded)) throw new Exception("SHA-256 roundtrip mismatch");
var items = await client.GetFromJsonAsync<JsonElement>("/api/items");
if (items.GetProperty("items").GetArrayLength() < 2) throw new Exception("Shared items missing");
// Simulate a Wi-Fi interruption between two chunks and resume using the same
// transfer ID. The first committed chunk must not be sent twice.
var total = Encoding.UTF8.GetBytes("resumable native upload over a disconnected five gigahertz link");
var first = total.AsSpan(0, 24).ToArray();
var second = total.AsSpan(24).ToArray();
var startResume = await client.PostAsJsonAsync("/api/transfers/begin", new { name = "resume.txt", size = total.Length });
startResume.EnsureSuccessStatusCode();
string resumableId = (await startResume.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
using (var part1 = new ByteArrayContent(first))
{
    part1.Headers.Add("X-Offset", "0");
    part1.Headers.Add("X-SHA256", Convert.ToHexString(SHA256.HashData(first)).ToLowerInvariant());
    (await client.PutAsync($"/api/transfers/{resumableId}/chunk", part1)).EnsureSuccessStatusCode();
}
var resume = await client.PostAsJsonAsync("/api/transfers/begin",
    new { name = "resume.txt", size = total.Length, resumeId = resumableId });
resume.EnsureSuccessStatusCode();
var resumeObject = await resume.Content.ReadFromJsonAsync<JsonElement>();
if (resumeObject.GetProperty("offset").GetInt64() != first.Length) throw new Exception("Upload resume offset was lost");
using (var wrongOffset = new ByteArrayContent(second))
{
    wrongOffset.Headers.Add("X-Offset", "0");
    wrongOffset.Headers.Add("X-SHA256", Convert.ToHexString(SHA256.HashData(second)).ToLowerInvariant());
    if ((await client.PutAsync($"/api/transfers/{resumableId}/chunk", wrongOffset)).StatusCode
        != System.Net.HttpStatusCode.Conflict) throw new Exception("Incorrect resume offset accepted");
}
using (var part2 = new ByteArrayContent(second))
{
    part2.Headers.Add("X-Offset", first.Length.ToString());
    part2.Headers.Add("X-SHA256", Convert.ToHexString(SHA256.HashData(second)).ToLowerInvariant());
    (await client.PutAsync($"/api/transfers/{resumableId}/chunk", part2)).EnsureSuccessStatusCode();
}
(await client.PostAsJsonAsync($"/api/transfers/{resumableId}/finish?sha256={Convert.ToHexString(SHA256.HashData(total)).ToLowerInvariant()}", new {})).EnsureSuccessStatusCode();
if (!total.SequenceEqual(await client.GetByteArrayAsync($"/api/files/{resumableId}")))
    throw new Exception("Resumed file bytes differ from original");
// Empty files should also survive begin/finish without producing a corrupt download.
var emptyResponse = await client.PostAsJsonAsync("/api/transfers/begin", new { name = "empty.dat", size = 0 });
emptyResponse.EnsureSuccessStatusCode();
string emptyId = (await emptyResponse.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
(await client.PostAsJsonAsync($"/api/transfers/{emptyId}/finish?sha256={Convert.ToHexString(SHA256.HashData(Array.Empty<byte>())).ToLowerInvariant()}", new {})).EnsureSuccessStatusCode();
if ((await client.GetByteArrayAsync($"/api/files/{emptyId}")).Length != 0)
    throw new Exception("Empty file transfer corrupted");

// Local Windows -> Android folder archive preserves nested directories and contents.
string sampleFolder = Path.Combine(Path.GetTempPath(), "NerqoFolder-" + Guid.NewGuid().ToString("N"));
try
{
    Directory.CreateDirectory(Path.Combine(sampleFolder, "nested", "empty"));
    byte[] folderContents = Encoding.UTF8.GetBytes("folder transfer keeps the nested layout");
    await File.WriteAllBytesAsync(Path.Combine(sampleFolder, "nested", "sample.txt"), folderContents);
    await host.ShareFolderAsync(sampleFolder);
    var archived = host.Items.FirstOrDefault(i => i.Content == Path.GetFileName(sampleFolder) + ".zip");
    if (archived == null || archived.Kind != "file") throw new Exception("Folder archive absent from shared items");
    string archiveFile = Path.Combine(host.StorageDirectory, archived.Id + ".bin");
    using var zip = new System.IO.Compression.ZipArchive(File.OpenRead(archiveFile), System.IO.Compression.ZipArchiveMode.Read);
    if (zip.GetEntry(Path.GetFileName(sampleFolder) + "/nested/empty/") == null)
        throw new Exception("Empty folder was lost during archiving");
    var fileInZip = zip.GetEntry(Path.GetFileName(sampleFolder) + "/nested/sample.txt")
        ?? throw new Exception("Nested file lost during archiving");
    using var read = fileInZip.Open();
    using var buffer = new MemoryStream();
    await read.CopyToAsync(buffer);
    if (!folderContents.SequenceEqual(buffer.ToArray())) throw new Exception("Folder member bytes changed");
    if (archived.Sha256 != Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(archiveFile))).ToLowerInvariant())
        throw new Exception("Folder archive checksum incorrect");
}
finally { if (Directory.Exists(sampleFolder)) Directory.Delete(sampleFolder, true); }


// Direct folder transport: create a folder and nested/empty directories, send a file
// by its validated relative path, and ensure no ZIP or second staging archive exists.
var folderStart = await client.PostAsJsonAsync("/api/folders/begin",
    new { name = "DirectPhotos" });
folderStart.EnsureSuccessStatusCode();
string folderId = (await folderStart.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
var createEmpty = await client.PostAsJsonAsync($"/api/folders/{folderId}/directory",
    new { path = "nested/empty" });
createEmpty.EnsureSuccessStatusCode();
foreach (var attack in new[] { "../escape", "nested/../../escape", "C:/absolute", "nested/CON",
                                "nested/trailing. ", "/absolute", "nested\\evil", "nested//bad" })
{
    var attempt = await client.PostAsJsonAsync($"/api/folders/{folderId}/directory", new { path = attack });
    if (attempt.IsSuccessStatusCode) throw new Exception("Unsafe folder path accepted: " + attack);
}
var folderPayload = Encoding.UTF8.GetBytes("Direct folder transfer without ZIP");
var folderFileBegin = await client.PostAsJsonAsync("/api/transfers/begin",
    new { name = "photo.txt", size = folderPayload.Length, folderId, relativePath = "nested/photo.txt" });
folderFileBegin.EnsureSuccessStatusCode();
string folderFileId = (await folderFileBegin.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
using (var folderPart = new ByteArrayContent(folderPayload))
{
    folderPart.Headers.Add("X-Offset", "0");
    folderPart.Headers.Add("X-SHA256", Convert.ToHexString(SHA256.HashData(folderPayload)).ToLowerInvariant());
    (await client.PutAsync($"/api/transfers/{folderFileId}/chunk", folderPart)).EnsureSuccessStatusCode();
}
(await client.PostAsJsonAsync($"/api/transfers/{folderFileId}/finish?sha256={Convert.ToHexString(SHA256.HashData(folderPayload)).ToLowerInvariant()}", new {})).EnsureSuccessStatusCode();
var directParent = Directory.GetDirectories(host.ReceivedDirectory, "DirectPhotos (*)").Single();
var directFile = Path.Combine(directParent, "nested", "photo.txt");
if (!File.Exists(directFile) || !folderPayload.SequenceEqual(await File.ReadAllBytesAsync(directFile)))
    throw new Exception("Direct folder file not found with original relative path");
if (!Directory.Exists(Path.Combine(directParent, "nested", "empty")))
    throw new Exception("Empty nested directory lost");
var repeated = await client.PostAsJsonAsync("/api/transfers/begin",
    new { name = "photo.txt", size = folderPayload.Length, folderId, relativePath = "nested/photo.txt" });
repeated.EnsureSuccessStatusCode();
var repeatedInfo = await repeated.Content.ReadFromJsonAsync<JsonElement>();
if (!repeatedInfo.GetProperty("complete").GetBoolean()
    || repeatedInfo.GetProperty("sha256").GetString() != Convert.ToHexString(SHA256.HashData(folderPayload)).ToLowerInvariant())
    throw new Exception("Already-transferred folder file was not recognized");
var otherStart = await client.PostAsJsonAsync("/api/folders/begin", new { name = "AnotherFolder" });
otherStart.EnsureSuccessStatusCode();
string otherId = (await otherStart.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
var pendingFolder = await client.PostAsJsonAsync("/api/transfers/begin",
    new { name = "pending.txt", size = folderPayload.Length, folderId, relativePath = "nested/pending.txt" });
pendingFolder.EnsureSuccessStatusCode();
string pendingId = (await pendingFolder.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
var wrongFolder = await client.PostAsJsonAsync("/api/transfers/begin",
    new { name = "pending.txt", size = folderPayload.Length, folderId = otherId,
          relativePath = "pending.txt", resumeId = pendingId });
if (wrongFolder.IsSuccessStatusCode) throw new Exception("Cross-folder transfer identity reuse accepted");

// A successful set of checksummed chunks is still not trusted until the
// sender's complete-file digest matches. Simulate a source change on resume.
var tampered = Encoding.UTF8.GetBytes("modified source after interruption");
var wrongBegin = await client.PostAsJsonAsync("/api/transfers/begin",
    new { name = "tampered.txt", size = tampered.Length });
wrongBegin.EnsureSuccessStatusCode();
string badId = (await wrongBegin.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString()!;
using (var validChunk = new ByteArrayContent(tampered))
{
    validChunk.Headers.Add("X-Offset", "0");
    validChunk.Headers.Add("X-SHA256", Convert.ToHexString(SHA256.HashData(tampered)).ToLowerInvariant());
    (await client.PutAsync($"/api/transfers/{badId}/chunk", validChunk)).EnsureSuccessStatusCode();
}
var refusedFinish = await client.PostAsJsonAsync(
    $"/api/transfers/{badId}/finish?sha256={new string('0', 64)}", new {});
if (refusedFinish.StatusCode != System.Net.HttpStatusCode.UnprocessableEntity)
    throw new Exception("Whole-file SHA-256 mismatch was accepted");
if ((await client.GetAsync($"/api/files/{badId}")).StatusCode != System.Net.HttpStatusCode.NotFound)
    throw new Exception("Corrupt transferred file was published");


// Windows -> Android native folder manifest: no ZIP, no archive staging.
string directSource = Path.Combine(Path.GetTempPath(), "NerqoDirect-" + Guid.NewGuid().ToString("N"));
try
{
    Directory.CreateDirectory(Path.Combine(directSource, "images", "empty"));
    byte[] originalDirect = Encoding.UTF8.GetBytes("native folder transport");
    await File.WriteAllBytesAsync(Path.Combine(directSource, "images", "image.txt"), originalDirect);
    await host.ShareFolderDirectAsync(directSource);
    var directItem = host.Items.FirstOrDefault(x => x.Kind == "folder"
        && x.Content == Path.GetFileName(directSource))
        ?? throw new Exception("Native outgoing folder not advertised");
    var manifestResponse = await client.GetAsync($"/api/outgoing-folders/{directItem.Id}/manifest");
    manifestResponse.EnsureSuccessStatusCode();
    var manifest = await manifestResponse.Content.ReadFromJsonAsync<JsonElement>();
    if (!manifest.GetProperty("directories").EnumerateArray()
        .Any(x => x.GetString() == "images/empty")) throw new Exception("Empty direct folder missing");
    if (!manifest.GetProperty("files").EnumerateArray()
        .Any(x => x.GetProperty("path").GetString() == "images/image.txt"))
        throw new Exception("Nested file missing from direct manifest");
    var file = await client.GetByteArrayAsync(
        $"/api/outgoing-folders/{directItem.Id}/file?path=images%2Fimage.txt");
    if (!file.SequenceEqual(originalDirect)) throw new Exception("Direct member bytes modified");
    foreach (var unsafePath in new[] { "..%2Fsecret", "%2Froot", "images%2F..%2Fsecret", "CON" })
    {
        var response = await client.GetAsync(
            $"/api/outgoing-folders/{directItem.Id}/file?path={unsafePath}");
        if (response.IsSuccessStatusCode) throw new Exception("Unsafe direct path accepted");
    }
}
finally { if (Directory.Exists(directSource)) Directory.Delete(directSource, true); }

var devices = host.PairedDevices;
if (devices.Count == 0 || !host.RevokePeer(devices.Single(p => p.Name == "Smoke").TokenHash)) throw new Exception("Cannot revoke paired device");
if ((await client.GetAsync("/api/items")).StatusCode != System.Net.HttpStatusCode.Unauthorized) throw new Exception("Revoked token still works");
Console.WriteLine("Nerqo native encrypted LAN smoke: TLS, pairing, files, SHA256, receipts, range resume, cancellation, peer revocation PASSED");
