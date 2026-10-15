using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Nerqo.Desktop.Services;

/// <summary>Persistent local TLS certificate protected by Windows DPAPI (current user).</summary>
public static class LanIdentity
{
    public static X509Certificate2 LoadOrCreate(string folder)
    {
        Directory.CreateDirectory(folder);
        string path = Path.Combine(folder, "lan-identity.dpapi");
        if (File.Exists(path))
        {
            try
            {
                byte[] pfx = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser);
                try { return new X509Certificate2(pfx, (string?)null, X509KeyStorageFlags.EphemeralKeySet); }
                finally { CryptographicOperations.ZeroMemory(pfx); }
            }
            catch (Exception e) when (e is CryptographicException or IOException)
            {
                throw new IOException("Nerqo's secure LAN identity cannot be opened. Restore it or re-pair intentionally.", e);
            }
        }
        using var key = RSA.Create(3072);
        var request = new CertificateRequest("CN=Nerqo Local Transfer", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
        using var created = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        byte[] raw = created.Export(X509ContentType.Pfx);
        try
        {
            byte[] encrypted = ProtectedData.Protect(raw, null, DataProtectionScope.CurrentUser);
            string staging = path + ".tmp";
            File.WriteAllBytes(staging, encrypted);
            File.Move(staging, path, overwrite: false);
            return new X509Certificate2(raw, (string?)null, X509KeyStorageFlags.EphemeralKeySet);
        }
        finally { CryptographicOperations.ZeroMemory(raw); }
    }
}
