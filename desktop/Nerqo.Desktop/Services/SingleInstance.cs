using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;

namespace Nerqo.Desktop.Services;

public sealed class SingleInstance : IDisposable
{
    private readonly Mutex mutex;
    private readonly EventWaitHandle wake;
    private RegisteredWaitHandle? registration;
    private SingleInstance(Mutex mutex, EventWaitHandle wake) { this.mutex = mutex; this.wake = wake; }
    public static SingleInstance? ClaimCurrentUser()
    {
        string sid = WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName;
        return Claim(Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(sid)))[..24]);
    }
    public static SingleInstance? Claim(string key)
    {
        string name = @"Local\Nerqo.Native." + key;
        var mutex = new Mutex(false, name + ".mutex");
        bool owned;
        try { owned = mutex.WaitOne(0); }
        catch (AbandonedMutexException) { owned = true; }
        if (!owned)
        {
            mutex.Dispose();
            try { using var existing = EventWaitHandle.OpenExisting(name + ".wake"); existing.Set(); }
            catch (WaitHandleCannotBeOpenedException) { }
            return null;
        }
        try { return new(mutex, new EventWaitHandle(false, EventResetMode.AutoReset, name + ".wake")); }
        catch { mutex.ReleaseMutex(); mutex.Dispose(); throw; }
    }
    public void OnWake(Action action) => registration = ThreadPool.RegisterWaitForSingleObject(wake,
        (_, _) => action(), null, Timeout.Infinite, executeOnlyOnce: false);
    public bool WaitForWake(int milliseconds) => wake.WaitOne(milliseconds);
    public void Dispose()
    {
        registration?.Unregister(null);
        wake.Dispose();
        mutex.ReleaseMutex();
        mutex.Dispose();
    }
}
