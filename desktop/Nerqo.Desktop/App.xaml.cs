using System.Windows;
using Nerqo.Desktop.Services;
namespace Nerqo.Desktop;
public partial class App : System.Windows.Application
{
    private SingleInstance? instance;
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (e.Args.Length == 1 && e.Args[0] is "--allow-private-firewall" or "--firewall-smoke")
        {
            try
            {
                if (e.Args[0] == "--firewall-smoke") FirewallAccess.SmokeTest();
                else FirewallAccess.ConfigurePrivateRules();
                Shutdown(0);
            }
            catch (Exception ex)
            {
                if (e.Args[0] == "--firewall-smoke")
                {
                    System.IO.Directory.CreateDirectory("dist/qa");
                    System.IO.File.WriteAllText("dist/qa/windows-firewall-error.txt", ex.ToString());
                }
                Shutdown(1);
            }
            return;
        }
        // Firewall helpers deliberately run separately, without opening a listener.
        instance = SingleInstance.ClaimCurrentUser();
        if (instance == null) { Shutdown(0); return; }
        MainWindow = new MainWindow();
        instance.OnWake(() => Dispatcher.BeginInvoke(new Action(() =>
        {
            if (Dispatcher.HasShutdownStarted || MainWindow == null) return;
            MainWindow.Show();
            MainWindow.WindowState = WindowState.Normal;
            MainWindow.Activate();
        })));
        MainWindow.Show();
    }
    protected override void OnExit(ExitEventArgs e)
    {
        instance?.Dispose();
        base.OnExit(e);
    }
}
