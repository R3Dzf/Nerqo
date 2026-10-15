using System.Buffers.Binary;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.IO;

namespace Nerqo.Desktop.Services;

/// <summary>
/// NRQDEL01 patch reader, byte-compatible with nerqo/delta.py and Android's
/// DeltaPatch.java. Does not replace installed files and deletes bad output.
/// </summary>
public static class NativeDelta
{
    private const ulong MaxOutput = 8UL * 1024 * 1024 * 1024;
    private const int MaxLiteral = 1024 * 1024;
    private static readonly byte[] Signature = Encoding.ASCII.GetBytes("NRQDEL01");

    public static void Apply(string originalPath, string patchPath, string outputPath)
    {
        if (File.Exists(outputPath)) throw new IOException("Delta output already exists");
        bool created = false;
        try
        {
            using var original = new FileStream(originalPath, FileMode.Open, FileAccess.Read, FileShare.Read, MaxLiteral);
            using var patch = new FileStream(patchPath, FileMode.Open, FileAccess.Read, FileShare.Read, MaxLiteral);
            Span<byte> header = stackalloc byte[88];
            patch.ReadExactly(header);
            if (!header[..8].SequenceEqual(Signature)) throw new InvalidDataException("Not a Nerqo delta");
            ulong oldLength = BinaryPrimitives.ReadUInt64BigEndian(header.Slice(8, 8));
            ulong newLength = BinaryPrimitives.ReadUInt64BigEndian(header.Slice(16, 8));
            byte[] oldHash = header.Slice(24, 32).ToArray();
            byte[] newHash = header.Slice(56, 32).ToArray();
            if (oldLength > MaxOutput || newLength > MaxOutput || oldLength != (ulong)original.Length)
                throw new InvalidDataException("Invalid delta sizes");
            byte[] actualOldHash = SHA256.HashData(original);
            if (!CryptographicOperations.FixedTimeEquals(oldHash, actualOldHash))
                throw new InvalidDataException("Delta source does not match installed installer");
            using var output = new FileStream(outputPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, MaxLiteral);
            created = true;
            using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            ulong written = 0;
            byte[] scratch = new byte[MaxLiteral];
            while (true)
            {
                int kind = patch.ReadByte();
                if (kind == -1) throw new InvalidDataException("Truncated delta");
                if (kind == 'E')
                {
                    if (patch.ReadByte() != -1) throw new InvalidDataException("Trailing delta content");
                    break;
                }
                if (kind == 'C')
                {
                    Span<byte> op = stackalloc byte[12];
                    patch.ReadExactly(op);
                    ulong offset = BinaryPrimitives.ReadUInt64BigEndian(op[..8]);
                    uint size = BinaryPrimitives.ReadUInt32BigEndian(op.Slice(8, 4));
                    if (size == 0 || offset > oldLength || size > oldLength - offset || size > newLength - written)
                        throw new InvalidDataException("Invalid copy operation");
                    original.Position = (long)offset;
                    ulong remaining = size;
                    while (remaining > 0)
                    {
                        int n = (int)Math.Min((ulong)scratch.Length, remaining);
                        original.ReadExactly(scratch.AsSpan(0, n));
                        output.Write(scratch, 0, n);
                        digest.AppendData(scratch, 0, n);
                        remaining -= (ulong)n;
                        written += (ulong)n;
                    }
                    continue;
                }
                if (kind == 'D')
                {
                    Span<byte> sizes = stackalloc byte[8];
                    patch.ReadExactly(sizes);
                    uint rawLength = BinaryPrimitives.ReadUInt32BigEndian(sizes[..4]);
                    uint compressedLength = BinaryPrimitives.ReadUInt32BigEndian(sizes.Slice(4, 4));
                    if (rawLength == 0 || rawLength > MaxLiteral ||
                        compressedLength == 0 || compressedLength > MaxLiteral + 1024 ||
                        rawLength > newLength - written)
                        throw new InvalidDataException("Invalid compressed literal");
                    byte[] packed = new byte[(int)compressedLength];
                    patch.ReadExactly(packed);
                    using var compressed = new MemoryStream(packed, writable: false);
                    using var zlib = new ZLibStream(compressed, CompressionMode.Decompress, leaveOpen: true);
                    int remaining = (int)rawLength;
                    while (remaining > 0)
                    {
                        int n = zlib.Read(scratch, 0, Math.Min(remaining, scratch.Length));
                        if (n <= 0) throw new InvalidDataException("Truncated compressed data");
                        output.Write(scratch, 0, n);
                        digest.AppendData(scratch, 0, n);
                        remaining -= n;
                        written += (ulong)n;
                    }
                    if (zlib.ReadByte() != -1) throw new InvalidDataException("Literal expands beyond claimed length");
                    continue;
                }
                throw new InvalidDataException("Unknown delta opcode");
            }
            if (written != newLength || !CryptographicOperations.FixedTimeEquals(digest.GetHashAndReset(), newHash))
                throw new InvalidDataException("Reconstructed installer failed SHA-256 verification");
            output.Flush(true);
        }
        catch
        {
            if (created && File.Exists(outputPath)) File.Delete(outputPath);
            throw;
        }
    }
}
