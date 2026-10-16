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
using System.Windows.Media;
using Color = System.Windows.Media.Color;
using ColorConverter = System.Windows.Media.ColorConverter;
using System.Windows.Controls;
using System.Globalization;
using Microsoft.Win32;

namespace Nerqo.Desktop;
public partial class MainWindow : Window
{
    private readonly LanHost service = new();
    private System.Windows.Forms.NotifyIcon? tray;
    private bool exiting;
    private bool preparing;
    private readonly string themeFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Nerqo", "appearance.txt");
    public MainWindow()
    {
        InitializeComponent();
        TransferNav.IsChecked = true;
        DarkModeToggle.IsChecked = File.Exists(themeFile) && File.ReadAllText(themeFile) == "dark";
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
            if (Environment.GetCommandLineArgs().Contains("--ui-smoke")) await CaptureUiSmokeAsync();
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
            Icon = new System.Drawing.Icon(System.Windows.Application.GetResourceStream(new Uri("pack://application:,,,/nerqo.ico")).Stream),
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
        ItemList.ItemsSource = entries.Select(x => new ActivityRow(x)).ToList();
        var peers = service.PairedDevices;
        PeerList.ItemsSource = peers;
        EmptyPeersLabel.Visibility = peers.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptyItemsLabel.Visibility = entries.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        ItemCountLabel.Text = $"{entries.Count} items";
        TransferHint.Text = peers.Count == 0 ? "Pair your phone from Devices to get started." : $"{peers.Count} trusted device(s) · Ready to share";
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
            await PrepareSharesAsync(file.FileNames);
            FooterLabel.Text = "Files ready to download on Android";
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Transfer failed", MessageBoxButton.OK, MessageBoxImage.Warning); }
    }
    private async void ShareFolder(object s, RoutedEventArgs e)
    {
        using var dialog = new System.Windows.Forms.FolderBrowserDialog
        {
            Description = "Choose a folder to transfer directly to Android",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false
        };
        if (dialog.ShowDialog() != System.Windows.Forms.DialogResult.OK) return;
        try { await PrepareSharesAsync(new[] { dialog.SelectedPath }); }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Could not share folder", MessageBoxButton.OK, MessageBoxImage.Warning); }
    }
    private async Task PrepareSharesAsync(string[] paths)
    {
        if (preparing) return;
        preparing = true;
        ShareFilesButton.IsEnabled = ShareFolderButton.IsEnabled = false;
        OperationProgress.IsIndeterminate = true;
        OperationProgress.Visibility = Visibility.Visible;
        try
        {
            // Hashing and folder enumeration must not block the WPF dispatcher.
            await Task.Run(async () =>
            {
                foreach (var path in paths)
                {
                    Dispatcher.Invoke(() => FooterLabel.Text = "Preparing " + Path.GetFileName(path) + "…");
                    if (Directory.Exists(path)) await service.ShareFolderDirectAsync(path);
                    else await service.ShareFileAsync(path);
                }
            });
            RefreshItems();
            FooterLabel.Text = "Ready to save from your paired Android device";
        }
        finally
        {
            preparing = false;
            ShareFilesButton.IsEnabled = ShareFolderButton.IsEnabled = true;
            OperationProgress.IsIndeterminate = false;
            OperationProgress.Visibility = Visibility.Collapsed;
        }
    }
    private void WindowDragOver(object sender, System.Windows.DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(System.Windows.DataFormats.FileDrop) && !preparing
            ? System.Windows.DragDropEffects.Copy : System.Windows.DragDropEffects.None;
        e.Handled = true;
    }
    private async void WindowDrop(object sender, System.Windows.DragEventArgs e)
    {
        if (preparing || e.Data.GetData(System.Windows.DataFormats.FileDrop) is not string[] paths) return;
        TransferNav.IsChecked = true;
        try { await PrepareSharesAsync(paths); }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Transfer failed", MessageBoxButton.OK, MessageBoxImage.Warning); }
    }
    private void Navigate(object sender, RoutedEventArgs e)
    {
        if (TransfersPage == null || sender is not System.Windows.Controls.RadioButton button) return;
        string page = button.Tag?.ToString() ?? "Transfers";
        TransfersPage.Visibility = page == "Transfers" ? Visibility.Visible : Visibility.Collapsed;
        DevicesPage.Visibility = page == "Devices" ? Visibility.Visible : Visibility.Collapsed;
        SettingsPage.Visibility = page == "Settings" ? Visibility.Visible : Visibility.Collapsed;
        PageTitle.Text = page;
        PageSubtitle.Text = page switch { "Devices" => "A secure connection starts here.", "Settings" => "A few things to make Nerqo yours.", _ => "A little closer. A lot easier to share." };
    }
    private void GoToDevices(object sender, RoutedEventArgs e) => DevicesNav.IsChecked = true;
    private void ChangeTheme(object sender, RoutedEventArgs e)
    {
        bool dark = DarkModeToggle.IsChecked == true;
        string[] names = { "Background", "Panel", "Surface", "Foreground", "Muted", "Stroke", "Accent", "OnAccent" };
        string[] colors = dark
            ? new[] { "#17272C", "#20363B", "#2B464A", "#EEF7F3", "#AEC7C2", "#3C565A", "#A8EBD0", "#163D35" }
            : new[] { "#F3F6F5", "#FFFFFF", "#EAF1EE", "#173638", "#5B7375", "#D7E4DE", "#176C5B", "#FFFFFF" };
        for (int i = 0; i < names.Length; i++)
            System.Windows.Application.Current.Resources[names[i]] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
        try { Directory.CreateDirectory(Path.GetDirectoryName(themeFile)!); File.WriteAllText(themeFile, dark ? "dark" : "light"); }
        catch (IOException) { FooterLabel.Text = "Appearance changed for this session"; }
    }
    private sealed record ActivityRow(TransferItem Item)
    {
        public string Title => Item.Content;
        public string Detail => $"{Item.Kind} · {Item.Sender}" + (Item.Kind is "file" or "folder" ? $" · {FormatBytes(Item.Size)}" : "");
        private static string FormatBytes(long bytes) => bytes < 1024 ? $"{bytes} B" : bytes < 1048576 ? $"{bytes / 1024.0:0.#} KB" : bytes < 1073741824 ? $"{bytes / 1048576.0:0.#} MB" : $"{bytes / 1073741824.0:0.##} GB";
    }
    private void CopySelectedItem(object sender, RoutedEventArgs e)
    {
        if (ItemList.SelectedItem is not ActivityRow row) return;
        Clipboard.SetText(row.Item.Content);
        FooterLabel.Text = "Copied selected item";
    }
    private void OpenTransfer(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (ItemList.SelectedItem is not ActivityRow row) return;
        if (row.Item.Kind is "file" or "folder") OpenReceived(sender, e);
        else { Clipboard.SetText(row.Item.Content); FooterLabel.Text = "Text copied"; }
    }
    private async Task CaptureUiSmokeAsync()
    {
        string output = Path.GetFullPath("ui-smoke");
        Directory.CreateDirectory(output);
        foreach (var nav in new[] { TransferNav, DevicesNav, SettingsNav })
        {
            nav.IsChecked = true;
            await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
            UpdateLayout();
            var bitmap = new RenderTargetBitmap((int)ActualWidth, (int)ActualHeight, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(this);
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var file = File.Create(Path.Combine(output, nav.Tag + ".png")); encoder.Save(file);
        }
        DarkModeToggle.IsChecked = true;
        TransferNav.IsChecked = true;
        await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
        UpdateLayout();
        var darkBitmap = new RenderTargetBitmap((int)ActualWidth, (int)ActualHeight, 96, 96, PixelFormats.Pbgra32);
        darkBitmap.Render(this);
        var darkEncoder = new PngBitmapEncoder(); darkEncoder.Frames.Add(BitmapFrame.Create(darkBitmap));
        using (var file = File.Create(Path.Combine(output, "Transfers-dark.png"))) darkEncoder.Save(file);
        await Quit();
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
            if (MessageBox.Show($"Nerqo {release.Version} is available.\n\nDownload verified Windows installer ({(release.Delta?.Size ?? release.Size) / 1048576.0:0.0} MB{(release.Delta == null ? "" : " delta")}; full: {release.Size / 1048576.0:0.0} MB)? Your files and paired devices will be preserved.\n\nUpdates require internet. File transfers do not.", "Nerqo updates", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
                return;
            OperationProgress.IsIndeterminate = false;
            OperationProgress.Visibility = Visibility.Visible;
            var progress = new Progress<int>(percent => { OperationProgress.Value = percent; FooterLabel.Text = $"Downloading Windows update: {percent}%"; });
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
        finally { CheckUpdatesButton.IsEnabled = true; OperationProgress.Visibility = Visibility.Collapsed; }
    }
    private void OpenReceived(object s, RoutedEventArgs e) => Process.Start(new ProcessStartInfo(service.ReceivedDirectory) { UseShellExecute = true });
}
