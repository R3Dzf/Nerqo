using System.Windows;
using System.IO;
using System.Threading.Tasks;
using Nerqo.Desktop.Services;
namespace Nerqo.Desktop;
public partial class App : System.Windows.Application
{
    private SingleInstance? instance;
    private static void RecordCrash(Exception error, string stage)
    {
        try
        {
            string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Nerqo", "Logs");
            Directory.CreateDirectory(folder);
            File.AppendAllText(Path.Combine(folder, "crash.log"),
                DateTimeOffset.Now.ToString("O") + " [" + stage + "] " + error + Environment.NewLine);
        }
        catch { /* Never crash again while recording a crash. */ }
    }
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += (_, args) =>
        {
            RecordCrash(args.Exception, "UI");
            // Keep the process alive only for recoverable clipboard/IO problems.
            if (args.Exception is System.Runtime.InteropServices.ExternalException or IOException)
            {
                args.Handled = true;
            }
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            if (args.ExceptionObject is Exception error) RecordCrash(error, "Process");
        };
        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            RecordCrash(args.Exception, "BackgroundTask");
            args.SetObserved();
        };
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
        try { MainWindow = new MainWindow(); }
        catch (Exception ex)
        {
            RecordCrash(ex, "Startup");
            System.Windows.MessageBox.Show(
                "Nerqo could not start. Diagnostic details are saved in %LOCALAPPDATA%\\Nerqo\\Logs\\crash.log.\\n" + ex.Message,
                "Nerqo startup error", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
            return;
        }
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
