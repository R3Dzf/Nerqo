using Nerqo.Desktop.Services;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

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
var finish = await client.PostAsJsonAsync($"/api/transfers/{id}/finish", new {});
finish.EnsureSuccessStatusCode();
var received = Path.Combine(host.ReceivedDirectory, "native.txt");
if (!File.Exists(received) || !uploaded.SequenceEqual(await File.ReadAllBytesAsync(received)))
    throw new Exception("Android upload was not saved to the Windows Received folder");
var downloaded = await client.GetByteArrayAsync($"/api/files/{id}");
if (!uploaded.SequenceEqual(downloaded)) throw new Exception("SHA-256 roundtrip mismatch");
var items = await client.GetFromJsonAsync<JsonElement>("/api/items");
if (items.GetProperty("items").GetArrayLength() < 2) throw new Exception("Shared items missing");
var devices = host.PairedDevices;
if (devices.Count == 0 || !host.RevokePeer(devices[0].TokenHash)) throw new Exception("Cannot revoke paired device");
if ((await client.GetAsync("/api/items")).StatusCode != System.Net.HttpStatusCode.Unauthorized) throw new Exception("Revoked token still works");
Console.WriteLine("Nerqo native encrypted LAN smoke: TLS, pairing, files, SHA256, peer revocation PASSED");
