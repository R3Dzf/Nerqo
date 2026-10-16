using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace Nerqo.Desktop.Services;

public sealed record FirewallRuleView(string Program, bool Enabled, int Direction, int Action,
    int Protocol, string Ports, int Profiles, string RemoteAddresses = "*", string LocalAddresses = "*",
    string RemotePorts = "*", string Service = "", string Interfaces = "All");
public sealed record FirewallStatus(string State, string Message, bool CanAllow = false);

public static class FirewallAccess
{
    public const int PrivateProfile = 2;
    private const int Tcp = 6, Udp = 17;
    private static string Executable => Environment.ProcessPath ?? throw new IOException("Application path unavailable");
    private static object Create(string progId) => Activator.CreateInstance(Type.GetTypeFromProgID(progId)
        ?? throw new InvalidOperationException("Windows Firewall is unavailable"))!;
    private static void Release(object? value) { if (value != null && Marshal.IsComObject(value)) Marshal.ReleaseComObject(value); }
    private static bool ProfileFlag(object policy, string name) => (bool)policy.GetType().InvokeMember(name,
        BindingFlags.GetProperty, null, policy, new object[] { PrivateProfile })!;
    private static bool SameProgram(string left, string right) => string.Equals(
        Environment.ExpandEnvironmentVariables(left).Trim('"'), right, StringComparison.OrdinalIgnoreCase);
    private static bool IncludesPort(string value, int port) => value.Split(',').Any(part =>
    {
        var p = part.Trim();
        if (p == "*") return true;
        if (int.TryParse(p, out var exact)) return exact == port;
        var ends = p.Split('-');
        return ends.Length == 2 && int.TryParse(ends[0], out var first) && int.TryParse(ends[1], out var last)
            && first <= port && port <= last;
    });
    public static (bool Covered, bool Blocked) Evaluate(IEnumerable<FirewallRuleView> rules, string executable)
    {
        var matching = rules.Where(r => r.Enabled && r.Direction == 1 && (r.Profiles & PrivateProfile) != 0
            && (SameProgram(r.Program, executable) || (r.Action == 0 && string.IsNullOrEmpty(r.Program)))).ToArray();
        bool Relevant(FirewallRuleView r, int protocol, int port) =>
            r.Protocol == 256 || (r.Protocol == protocol && IncludesPort(r.Ports, port));
        bool blocked = matching.Any(r => r.Action == 0 && (Relevant(r, Tcp, 44888) || Relevant(r, Udp, 44889)));
        bool Allows(int protocol, int port) => matching.Any(r => r.Action == 1 && Relevant(r, protocol, port)
            && r.LocalAddresses == "*" && r.RemotePorts == "*" && string.IsNullOrEmpty(r.Service)
            && (string.IsNullOrEmpty(r.Interfaces) || r.Interfaces.Equals("All", StringComparison.OrdinalIgnoreCase))
            && r.RemoteAddresses.Split(',').Any(s => s.Trim() == "*" || s.Trim().Equals("LocalSubnet", StringComparison.OrdinalIgnoreCase)));
        return (Allows(Tcp, 44888) && Allows(Udp, 44889), blocked);
    }
    private static List<FirewallRuleView> ReadRules(object policy)
    {
        var result = new List<FirewallRuleView>();
        object? collection = null;
        try
        {
            collection = ((dynamic)policy).Rules;
            foreach (dynamic rule in (System.Collections.IEnumerable)collection)
            {
                try
                {
                    // Ignore unrelated rules before accessing their optional protocol fields.
                    if (!SameProgram((string?)rule.ApplicationName ?? "", Executable)
                        && !((int)rule.Action == 0 && string.IsNullOrEmpty((string?)rule.ApplicationName))) continue;
                    int protocol = rule.Protocol;
                    result.Add(new((string?)rule.ApplicationName ?? "", (bool)rule.Enabled, (int)rule.Direction,
                        (int)rule.Action, protocol, protocol is Tcp or Udp ? (string)rule.LocalPorts : "*", (int)rule.Profiles,
                        (string?)rule.RemoteAddresses ?? "*", (string?)rule.LocalAddresses ?? "*",
                        protocol is Tcp or Udp ? (string)rule.RemotePorts : "*", (string?)rule.ServiceName ?? "",
                        (string?)rule.InterfaceTypes ?? "All"));
                }
                finally { Release(rule); }
            }
        }
        finally { Release(collection); }
        return result;
    }
    public static FirewallStatus Inspect()
    {
        object? instance = null;
        try
        {
            instance = Create("HNetCfg.FwPolicy2");
            dynamic policy = instance;
            var state = Evaluate(ReadRules(instance), Executable);
            if (!ProfileFlag(instance, "FirewallEnabled")) return new("disabled", "Windows Firewall is off for private networks. Nerqo has not changed it.");
            if (ProfileFlag(instance, "BlockAllInboundTraffic") || (int)policy.LocalPolicyModifyState != 0)
                return new("managed", "Windows policy restricts inbound access. Review Windows Firewall settings or contact your administrator.");
            if (state.Blocked) return new("blocked", "A Windows Firewall rule explicitly blocks Nerqo. Review that rule in Windows Firewall; Nerqo will not remove it automatically.");
            if (!state.Covered) return new("missing", "Allow Nerqo to receive files and discover devices on your private Wi-Fi or hotspot.", true);
            if (((int)policy.CurrentProfileTypes & PrivateProfile) == 0 && ((int)policy.CurrentProfileTypes & 4) != 0)
                return new("public", "Private-network rules are ready. This connection is Public: mark your trusted Wi-Fi as Private in Windows network settings.");
            return new("ready", "Private-network rules for Nerqo transfers and discovery are ready. Existing Windows rules are unchanged.");
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException or System.Reflection.TargetInvocationException)
        { return new("unknown", "Could not read Windows Firewall permissions. Review Windows Firewall settings."); }
        finally { Release(instance); }
    }
    private static string RuleName(int protocol) => "Nerqo private LAN " + (protocol == Tcp ? "TCP " : "UDP ")
        + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Executable.ToUpperInvariant())))[..12];
    public static void ConfigurePrivateRules()
    {
        object? instance = null, collection = null;
        try
        {
            instance = Create("HNetCfg.FwPolicy2");
            dynamic policy = instance;
            if (ProfileFlag(instance, "BlockAllInboundTraffic") || (int)policy.LocalPolicyModifyState != 0)
                throw new InvalidOperationException("Windows policy does not permit this change");
            if (Evaluate(ReadRules(instance), Executable).Blocked)
                throw new InvalidOperationException("An explicit blocking rule must be reviewed in Windows Firewall");
            collection = policy.Rules;
            foreach (int protocol in new[] { Tcp, Udp })
            {
                object? entry = null;
                try
                {
                    entry = Create("HNetCfg.FWRule");
                    dynamic rule = entry;
                    rule.Name = RuleName(protocol);
                    rule.Description = "Nerqo local sharing on private networks only. Approved in Nerqo by the device owner.";
                    rule.ApplicationName = Executable;
                    rule.Protocol = protocol;
                    rule.LocalPorts = protocol == Tcp ? "44888" : "44889";
                    rule.Direction = 1;
                    rule.Profiles = PrivateProfile;
                    rule.RemoteAddresses = "LocalSubnet";
                    rule.InterfaceTypes = "All";
                    rule.EdgeTraversal = false;
                    rule.Action = 1;
                    rule.Enabled = true;
                    ((dynamic)collection).Add(rule);
                }
                finally { Release(entry); }
            }
        }
        finally { Release(collection); Release(instance); }
    }
    public static bool RequestPrivateAccess()
    {
        using var process = Process.Start(new ProcessStartInfo(Executable, "--allow-private-firewall")
        { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden });
        if (process == null) return false;
        process.WaitForExit();
        return process.ExitCode == 0;
    }
    public static void SmokeTest()
    {
        // Disposable Windows CI runner only. The real user flow always requests UAC consent.
        object instance = Create("HNetCfg.FwPolicy2");
        dynamic policy = instance;
        dynamic rules = policy.Rules;
        try
        {
            foreach (int protocol in new[] { Tcp, Udp })
            {
                try { object existing = rules.Item(RuleName(protocol)); Release(existing); throw new InvalidOperationException("Smoke rules already exist; refusing to modify them"); }
                catch (Exception ex) when (ex.HResult == unchecked((int)0x80070002)) { }
            }
            try
            {
                ConfigurePrivateRules();
                var state = Evaluate(ReadRules(instance), Executable);
                if (!state.Covered || state.Blocked) throw new InvalidOperationException("Private rule verification failed");
                foreach (int protocol in new[] { Tcp, Udp })
                {
                    dynamic rule = rules.Item(RuleName(protocol));
                    try
                    {
                        if ((int)rule.Profiles != PrivateProfile || (string)rule.RemoteAddresses != "LocalSubnet"
                            || (bool)rule.EdgeTraversal || !SameProgram((string)rule.ApplicationName, Executable))
                            throw new InvalidOperationException("Rule scope was broader than private local sharing");
                    }
                    finally { Release(rule); }
                }
                Directory.CreateDirectory("dist/qa");
                File.WriteAllText("dist/qa/windows-firewall.txt", "PASS: native Firewall COM read/write; executable-specific TCP 44888 and UDP 44889; Private profile and LocalSubnet only; edge traversal disabled; test rules removed.\n");
            }
            finally
            {
                foreach (int protocol in new[] { Tcp, Udp })
                    try { rules.Remove(RuleName(protocol)); }
                    catch (Exception ex) when (ex.HResult == unchecked((int)0x80070002)) { }
            }
        }
        finally { Release(rules); Release(instance); }
    }
}
