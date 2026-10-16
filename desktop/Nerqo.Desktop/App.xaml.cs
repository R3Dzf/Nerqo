using System.Windows;
using Nerqo.Desktop.Services;
namespace Nerqo.Desktop;
public partial class App : System.Windows.Application
{
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
        MainWindow = new MainWindow();
        MainWindow.Show();
    }
}
