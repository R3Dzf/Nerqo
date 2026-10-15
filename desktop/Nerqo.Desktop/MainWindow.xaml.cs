using Nerqo.Desktop.Services;
using QRCoder;
using System.Text.Json;
using System.IO;
using System.Windows.Media.Imaging;
using MessageBox = System.Windows.MessageBox;
using Clipboard = System.Windows.Clipboard;
using OpenFileDialog = Microsoft.Win32.OpenFileDialog;
using System.Diagnostics;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Win32;

namespace Nerqo.Desktop;
public partial class MainWindow : Window
{
    private readonly LanHost service = new();
    private System.Windows.Forms.NotifyIcon? tray;
    private bool exiting;
    public MainWindow()
    {
        InitializeComponent();
        PairCodeLabel.Text = service.PairCode;
        IdentityLabel.Text = service.PairingIdentity;
        service.Changed += () => Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(RefreshItems));
        Loaded += Start;
        Closing += (s, e) =>
        {
            if (!exiting && tray != null) { e.Cancel = true; Hide(); tray.ShowBalloonTip(1500, "Nerqo", "Still receiving on your local Wi-Fi.", System.Windows.Forms.ToolTipIcon.Info); }
        };
    }
    private async void Start(object sender, RoutedEventArgs e)
    {
        try
        {
            await service.StartAsync();
            StatusLabel.Text = "●  Ready on local network";
            await UpdateNetworkAndQrAsync();
            SetupTray(); RefreshItems();
        }
        catch (Exception ex)
        {
            StatusLabel.Text = "●  Not listening";
            FooterLabel.Text = "Port 44888 may already be in use";
            MessageBox.Show($"Nerqo couldn't start its local receiver.\n\n{ex.Message}\n\nCheck Windows Firewall and make sure no previous instance is running.", "Nerqo connection", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }
    private async void RefreshNetworkAndQr(object sender, RoutedEventArgs e)
    {
        try { await UpdateNetworkAndQrAsync(); FooterLabel.Text = "Network details and QR refreshed"; }
        catch (Exception ex) { FooterLabel.Text = "Network refresh failed"; MessageBox.Show(ex.Message, "Nerqo network", MessageBoxButton.OK, MessageBoxImage.Warning); }
    }
    private async Task UpdateNetworkAndQrAsync()
    {
        var addresses = service.Addresses;
        AddressLabel.Text = addresses.Length == 0 ? "Connect Windows to Wi-Fi or a phone hotspot, then tap Refresh." : "Local addresses: " + string.Join("  ·  ", addresses);
        BandLabel.Text = "Wi-Fi link: " + await Task.Run(WifiInspector.GetBand) + "  |  5 GHz / 6 GHz recommended";
        ShowPairingQr();
    }
    private void ShowPairingQr()
    {
        var addresses = service.Addresses.Take(6).ToArray();
        if (addresses.Length == 0) { PairQrImage.Source = null; PairQrImage.ToolTip = "Connect Windows to Wi-Fi then refresh the QR"; return; }
        string payload = JsonSerializer.Serialize(new
        {
            nerqo = 1, name = Environment.MachineName, addresses, port = LanHost.Port,
            pin = service.PairCode, fingerprint = service.ServerFingerprint
        });
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        using var qr = new PngByteQRCode(data);
        using var stream = new MemoryStream(qr.GetGraphic(4));
        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        PairQrImage.Source = image;
    }
    private void SetupTray()
    {
        tray = new System.Windows.Forms.NotifyIcon
        {
            Text = "Nerqo — local transfer",
            Icon = System.Drawing.SystemIcons.Information,
            Visible = true,
            ContextMenuStrip = new System.Windows.Forms.ContextMenuStrip()
        };
        tray.DoubleClick += (_, _) => Restore();
        tray.ContextMenuStrip!.Items.Add("Open Nerqo", null, (_, _) => Restore());
        tray.ContextMenuStrip.Items.Add("Exit Nerqo", null, async (_, _) => await Quit());
    }
    private void Restore() { Show(); WindowState = WindowState.Normal; Activate(); }
    private async Task Quit()
    {
        exiting = true; tray?.Dispose(); tray = null;
        await service.DisposeAsync(); Close();
    }
    private void RefreshItems()
    {
        var entries = service.Items;
        ItemList.ItemsSource = entries;
        PeerList.ItemsSource = service.PairedDevices;
        FooterLabel.Text = entries.Count + " items";
    }
    private void RevokeSelectedPeer(object s, RoutedEventArgs e)
    {
        if (PeerList.SelectedItem is not PairedDevice peer) return;
        if (MessageBox.Show($"Remove {peer.Name} from trusted devices? They will need to pair again.", "Revoke access", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        if (service.RevokePeer(peer.TokenHash)) { RefreshItems(); FooterLabel.Text = $"Access removed: {peer.Name}"; }
    }
    private void CopyPairCode(object s, RoutedEventArgs e)
    {
        Clipboard.SetText(service.PairCode);
        FooterLabel.Text = "Pairing code copied";
    }
    private async void ShareFile(object s, RoutedEventArgs e)
    {
        var file = new OpenFileDialog { Title = "Select a file to send", Multiselect = true, CheckFileExists = true };
        if (file.ShowDialog(this) != true) return;
        try
        {
            foreach (var path in file.FileNames) await service.ShareFileAsync(path);
            FooterLabel.Text = "Files ready to download on Android";
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Transfer failed", MessageBoxButton.OK, MessageBoxImage.Warning); }
    }
    private void ShareNote(object s, RoutedEventArgs e)
    {
        try { service.ShareNote(NoteInput.Text); NoteInput.Clear(); FooterLabel.Text = "Sent to paired devices"; }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Invalid message", MessageBoxButton.OK, MessageBoxImage.Warning); }
    }
    private async void CheckForUpdates(object sender, RoutedEventArgs e)
    {
        CheckUpdatesButton.IsEnabled = false;
        try
        {
            FooterLabel.Text = "Checking for a stable Windows update…";
            var release = await NativeUpdateService.CheckAsync(CancellationToken.None);
            if (release == null)
            {
                FooterLabel.Text = "No newer stable Windows update available";
                MessageBox.Show("You have the latest eligible Nerqo Windows version. Offline file transfer continues to work without internet.", "Nerqo updates", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }
            if (MessageBox.Show($"Nerqo {release.Version} is available.\n\nDownload verified Windows installer ({release.Size / 1048576.0:0.0} MB)? Your files and paired devices will be preserved.\n\nUpdates require internet. File transfers do not.", "Nerqo updates", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
                return;
            var progress = new Progress<int>(percent => FooterLabel.Text = $"Downloading Windows update: {percent}%");
            var installer = await NativeUpdateService.DownloadAsync(release, progress, CancellationToken.None);
            FooterLabel.Text = "Update downloaded and SHA-256 verified";
            if (MessageBox.Show("The download passed the SHA-256 check. Launch Windows setup? Windows may warn because the installer does not yet have a trusted Authenticode signature.", "Install Nerqo", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes)
                Process.Start(new ProcessStartInfo(installer) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            FooterLabel.Text = "Update check or download failed";
            MessageBox.Show(ex.Message, "Nerqo updates", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally { CheckUpdatesButton.IsEnabled = true; }
    }
    private void OpenReceived(object s, RoutedEventArgs e) => Process.Start(new ProcessStartInfo(service.ReceivedDirectory) { UseShellExecute = true });
}
