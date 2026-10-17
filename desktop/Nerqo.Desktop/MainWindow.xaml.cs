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
    private List<TransferHistoryEntry> displayedHistory = new();
    private List<TransferItem> displayedShares = new();
    private List<PairedDevice> displayedPeers = new();
    private readonly System.Collections.ObjectModel.ObservableCollection<LiveTransferRow> displayedIncoming = new();
    private bool exiting;
    private int refreshQueued;
    private void RefreshFromEvent()
    {
        if (Dispatcher.HasShutdownStarted || Interlocked.Exchange(ref refreshQueued, 1) != 0) return;
        Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() =>
        {
            Interlocked.Exchange(ref refreshQueued, 0);
            if (!exiting) RefreshItems();
        }));
    }
    private bool preparing;
    private CancellationTokenSource? preparationCancellation;
    private void CancelPreparation(object s, RoutedEventArgs e) => preparationCancellation?.Cancel();
    private bool loadingTheme = true;
    private bool receiverStarted;
    private bool startingReceiver;
    private string connectionError = "";
    private FirewallStatus? firewallStatus;
    private readonly string firewallPromptFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Nerqo", "firewall-prompt-v1.txt");
    private readonly string themeFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Nerqo", "appearance.txt");
    public MainWindow()
    {
        InitializeComponent();
        IncomingList.ItemsSource = displayedIncoming;
        TransferNav.IsChecked = true;
        try { DarkModeToggle.IsChecked = File.Exists(themeFile) && File.ReadAllText(themeFile) == "dark"; }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { DarkModeToggle.IsChecked = false; }
        ApplyTheme(false);
        loadingTheme = false;
        PairCodeLabel.Text = service.PairCode;
        IdentityLabel.Text = service.PairingIdentity;
        // All host state changes and transfer progress trigger a queued refresh.
        // Coalescing avoids rebuilding WPF lists for every incoming file chunk.
        service.Changed += RefreshFromEvent;
        Loaded += Start;
        Closing += (s, e) =>
        {
            if (!exiting && tray != null) { e.Cancel = true; Hide(); tray.ShowBalloonTip(1500, "Nerqo", "Still receiving on your local Wi-Fi.", System.Windows.Forms.ToolTipIcon.Info); }
        };
    }
    private async void Start(object sender, RoutedEventArgs e)
    {
        if (startingReceiver || receiverStarted) return;
        startingReceiver = true;
        RetryReceiverButton.IsEnabled = false;
        connectionError = "";
        ReceiverErrorLabel.Visibility = RetryReceiverButton.Visibility = Visibility.Collapsed;
        try
        {
            bool smoke = Environment.GetCommandLineArgs().Contains("--ui-smoke");
            try
            {
                NativeUpdateService.CleanupOlderCachedInstallers(
                    Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Nerqo", "Updates"),
                    typeof(MainWindow).Assembly.GetName().Version!);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
            // Check before listening so our consent flow can install precise rules
            // before Windows considers displaying its generic app permission prompt.
            await RefreshFirewallAsync(!smoke && !Environment.GetCommandLineArgs().Contains("--upgrade-smoke"));
            await service.StartAsync();
            receiverStarted = true;
            UpdateFirewallHeader();
            await UpdateNetworkAndQrAsync();
            SetupTray(); RefreshItems();
            if (smoke) await CaptureUiSmokeAsync();
        }
        catch (Exception ex)
        {
            connectionError = ex.GetType().Name + ": " + ex.Message + "\n" + ex.GetBaseException().GetType().Name + ": " + ex.GetBaseException().Message;
            ReceiverErrorLabel.Text = (receiverStarted ? "Receiver started; a later setup step failed. " : "Receiver could not start. ") + connectionError;
            ReceiverErrorLabel.Visibility = Visibility.Visible;
            FirewallCard.Visibility = Visibility.Visible;
            if (!receiverStarted)
            {
                StatusLabel.Text = "●  Receiver failed to start";
                RetryReceiverButton.Visibility = Visibility.Visible;
                FooterLabel.Text = "Receiver failed. Copy connection details to see the actual startup error.";
            }
            else { UpdateFirewallHeader(); FooterLabel.Text = "Receiver is running; connection details could not be fully refreshed."; }
            MessageBox.Show(this, ReceiverErrorLabel.Text + "\n\nUse Copy connection details to share the error. If the address is already in use, exit any older Nerqo instance from the tray before retrying.", "Nerqo connection", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally { startingReceiver = false; RetryReceiverButton.IsEnabled = !receiverStarted; }
    }
    private void RetryReceiver(object sender, RoutedEventArgs e) => Start(sender, e);
    private void CopyConnectionDetails(object sender, RoutedEventArgs e)
    {
        string addresses;
        try { addresses = string.Join(", ", service.Addresses); }
        catch (Exception ex) { addresses = ex.GetType().Name + ": " + ex.Message; }
        Clipboard.SetText($"Nerqo {typeof(MainWindow).Assembly.GetName().Version}\nReceiver started: {receiverStarted}\nFirewall: {firewallStatus?.State} — {firewallStatus?.Message}\nLocal addresses: {addresses}\nPort: {LanHost.Port}\nError: {connectionError}");
        FooterLabel.Text = "Connection details copied. Pairing codes, tokens and file contents are not included.";
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
        if (!displayedShares.SequenceEqual(entries)) { displayedShares = entries; ItemList.ItemsSource = entries.Select(x => new ActivityRow(x)).ToList(); }
        var peers = service.PairedDevices;
        if (!displayedPeers.SequenceEqual(peers)) { displayedPeers = peers; PeerList.ItemsSource = peers; }
        var incoming = service.Transfers;
        var ids = incoming.Select(x => x.Id).ToHashSet();
        foreach (var removed in displayedIncoming.Where(x => !ids.Contains(x.Value.Id)).ToArray()) displayedIncoming.Remove(removed);
        foreach (var item in incoming) {
            var row = displayedIncoming.FirstOrDefault(x => x.Value.Id == item.Id);
            if (row == null) displayedIncoming.Add(new LiveTransferRow(item)); else row.Update(item);
        }
        EmptyIncomingLabel.Visibility = incoming.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        HistoryWarningLabel.Text = service.HistoryWarning;
        HistoryWarningLabel.Visibility = service.HistoryWarning.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        var history = service.History;
        if (!displayedHistory.SequenceEqual(history)) { displayedHistory = history; RenderHistory(); }
        EmptyPeersLabel.Visibility = peers.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptyItemsLabel.Visibility = entries.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        ItemCountLabel.Text = $"{entries.Count} items";
        TransferHint.Text = peers.Count == 0 ? "Pair your phone from Devices to get started." : $"{peers.Count} trusted device(s) · Ready to share";
    }
    private void RevokeSelectedPeer(object s, RoutedEventArgs e)
    {
        if (PeerList.SelectedItem is not PairedDevice peer) return;

        if (service.SetPeerSuspended(peer.TokenHash, true)) { RefreshItems(); FooterLabel.Text = $"Disconnected: {peer.Name}. Pairing is saved. Use Reconnect to restore access; Wi-Fi stays connected."; }
    }
    private void ReconnectSelectedPeer(object s, RoutedEventArgs e) { if (PeerList.SelectedItem is PairedDevice peer) service.SetPeerSuspended(peer.TokenHash, false); }
    private void ForgetSelectedPeer(object s, RoutedEventArgs e) {
        if (PeerList.SelectedItem is PairedDevice peer && MessageBox.Show($"Remove saved access for {peer.Name}? Pair again using QR to reconnect.", "Forget device", MessageBoxButton.YesNo) == MessageBoxResult.Yes) service.RevokePeer(peer.TokenHash);
    }
    private void ControlTransfer(object s, RoutedEventArgs e) {
        try { if (s is System.Windows.Controls.Button { DataContext: LiveTransferRow transfer, Tag: string action }) service.ControlTransfer(transfer.Value.Id, action); }
        catch (Exception ex) { FooterLabel.Text = "Could not change transfer: " + ex.Message; }
    }
    private void ClearHistory(object s, RoutedEventArgs e) {
        if (MessageBox.Show("Clear the transfer log? Your files will be kept.", "Clear History", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
        try { service.ClearHistory(); RefreshItems(); } catch (Exception ex) { FooterLabel.Text = ex.Message; }
    }
    private void OpenHistoryFile(object s, RoutedEventArgs e) => OpenHistorySelection(false);
    private void ShowHistoryFile(object s, RoutedEventArgs e) => OpenHistorySelection(true);
    private void OpenHistorySelection(bool folder) {
        if (HistoryList.SelectedItem is not HistoryRow row) return;
        string? path = service.HistoryPath(row.Entry);
        if (path == null) { FooterLabel.Text = "File moved or unavailable on this device."; return; }
        try {
            if (folder) Process.Start(new ProcessStartInfo("explorer.exe") { UseShellExecute = true, ArgumentList = { "/select,", path } });
            else Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
        } catch (Exception ex) { FooterLabel.Text = ex.Message; }
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
        bool direct = FastShareToggle.IsChecked == true;
        preparationCancellation = new();
        var cancellation = preparationCancellation.Token;
        CancelPreparationButton.Visibility = Visibility.Visible;
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
                    bool folder = Directory.Exists(path);
                    Dispatcher.Invoke(() => { FooterLabel.Text = "Preparing " + Path.GetFileName(path) + "…"; OperationProgress.IsIndeterminate = folder; OperationProgress.Value = 0; });
                    if (folder) await service.ShareFolderDirectAsync(path, ct: cancellation);
                    else {
                    Action<long, long> preparationProgress = (done, total) => Dispatcher.BeginInvoke(new Action(() =>
                    {
                        OperationProgress.IsIndeterminate = false;
                        OperationProgress.Value = total > 0 ? done * 100.0 / total : 100;
                        FooterLabel.Text = $"Preparing {Path.GetFileName(path)} · {OperationProgress.Value:0}% · {(direct ? "verifying original without copying" : "copying and verifying in one pass")}";
                    }));
                    if (direct) await service.ShareFileDirectAsync(path, cancellation, preparationProgress);
                    else await service.ShareFileAsync(path, cancellation, preparationProgress);
                    }
                }
            });
            RefreshItems();
            FooterLabel.Text = "Ready to save from your paired Android device";
        }
        finally
        {
            preparing = false;
            preparationCancellation?.Dispose(); preparationCancellation = null;
            CancelPreparationButton.Visibility = Visibility.Collapsed;
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
        HistoryPage.Visibility = page == "History" ? Visibility.Visible : Visibility.Collapsed;
        DevicesPage.Visibility = page == "Devices" ? Visibility.Visible : Visibility.Collapsed;
        SettingsPage.Visibility = page == "Settings" ? Visibility.Visible : Visibility.Collapsed;
        PageTitle.Text = page;
        PageScroll?.ScrollToTop();
        UpdateFirewallCardVisibility();
        PageSubtitle.Text = page switch { "History" => "Your transfers, remembered.", "Devices" => "A secure connection starts here.", "Settings" => "A few things to make Nerqo yours.", _ => "A little closer. A lot easier to share." };
    }
    private void GoToDevices(object sender, RoutedEventArgs e) => DevicesNav.IsChecked = true;
    private void ChangeTheme(object sender, RoutedEventArgs e)
    {
        if (DarkModeToggle != null) ApplyTheme(!loadingTheme);
    }
    private void ApplyTheme(bool persist)
    {
        bool dark = DarkModeToggle.IsChecked == true;
        string[] names = { "Background", "Panel", "Surface", "Foreground", "Muted", "Stroke", "Accent", "OnAccent" };
        string[] colors = dark
            ? new[] { "#17272C", "#20363B", "#2B464A", "#EEF7F3", "#AEC7C2", "#3C565A", "#A8EBD0", "#163D35" }
            : new[] { "#F3F6F5", "#FFFFFF", "#EAF1EE", "#173638", "#5B7375", "#D7E4DE", "#176C5B", "#FFFFFF" };
        for (int i = 0; i < names.Length; i++)
            System.Windows.Application.Current.Resources[names[i]] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
        if (!persist) return;
        try { Directory.CreateDirectory(Path.GetDirectoryName(themeFile)!); File.WriteAllText(themeFile, dark ? "dark" : "light"); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { FooterLabel.Text = "Appearance changed for this session"; }
    }
    private void UpdateFirewallCardVisibility()
    {
        if (FirewallCard == null) return;
        FirewallCard.Visibility = connectionError.Length == 0 && firewallStatus?.State == "ready" && (TransfersPage.Visibility == Visibility.Visible || HistoryPage.Visibility == Visibility.Visible)
            ? Visibility.Collapsed : Visibility.Visible;
    }
    private void UpdateFirewallHeader()
    {
        if (!receiverStarted) return;
        StatusLabel.Text = firewallStatus?.State switch
        {
            "ready" => "●  Ready on private network",
            "public" => "●  Check network type",
            "missing" or "blocked" or "managed" => "●  Firewall needs attention",
            _ => "●  Local receiver running"
        };
    }
    private async Task RefreshFirewallAsync(bool firstUse = false)
    {
        CheckFirewallButton.IsEnabled = AllowFirewallButton.IsEnabled = false;
        try
        {
            firewallStatus = await Task.Run(FirewallAccess.Inspect);
            FirewallLabel.Text = firewallStatus.Message;
            AllowFirewallButton.Visibility = firewallStatus.CanAllow ? Visibility.Visible : Visibility.Collapsed;
            NetworkSettingsButton.Visibility = firewallStatus.State == "public" ? Visibility.Visible : Visibility.Collapsed;
            UpdateFirewallCardVisibility();
            UpdateFirewallHeader();
        }
        catch (Exception) { FirewallLabel.Text = "Could not check Windows Firewall. Open Windows Firewall settings to review Nerqo."; }
        finally { CheckFirewallButton.IsEnabled = AllowFirewallButton.IsEnabled = true; }
        if (firstUse && firewallStatus?.CanAllow == true && !File.Exists(firewallPromptFile))
        {
            var answer = MessageBox.Show(this, "Allow Nerqo to receive files and find your phone on private Wi-Fi or a hotspot?\n\nWindows will ask for administrator permission. Only Nerqo's local sharing ports on private networks will be allowed. Public-network access and existing blocking rules are not changed.\n\nYou can also do this later with Allow private network access.", "Nerqo — allow local sharing", MessageBoxButton.YesNo, MessageBoxImage.Question);
            try { Directory.CreateDirectory(Path.GetDirectoryName(firewallPromptFile)!); File.WriteAllText(firewallPromptFile, "shown"); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
            if (answer == MessageBoxResult.Yes) await AllowFirewallAsync();
        }
    }
    private async void CheckFirewall(object sender, RoutedEventArgs e) => await RefreshFirewallAsync();
    private async void AllowFirewall(object sender, RoutedEventArgs e) => await AllowFirewallAsync();
    private async Task AllowFirewallAsync()
    {
        AllowFirewallButton.IsEnabled = CheckFirewallButton.IsEnabled = false;
        try
        {
            FooterLabel.Text = "Approve the Windows permission request to allow private-network sharing";
            bool applied = await Task.Run(FirewallAccess.RequestPrivateAccess);
            FooterLabel.Text = applied ? "Private-network permission applied; checking Windows Firewall…" : "Permission was not applied. Review Windows Firewall settings or administrator policy.";
        }
        catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == 1223)
        { FooterLabel.Text = "Windows permission was cancelled. You can allow access later."; }
        catch (Exception) { FooterLabel.Text = "Could not request permission. Review Windows Firewall settings or administrator policy."; }
        finally { await RefreshFirewallAsync(); }
    }
    private void OpenFirewallSettings(object sender, RoutedEventArgs e) => OpenSystemSettings("windowsdefender://network/");
    private void OpenHotspotSettings(object sender, RoutedEventArgs e) => OpenSystemSettings("ms-settings:network-mobilehotspot");
    private void OpenNetworkSettings(object sender, RoutedEventArgs e) => OpenSystemSettings("ms-settings:network-status");
    private void OpenSystemSettings(string target)
    {
        try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); }
        catch (Exception) { FooterLabel.Text = "Open Windows Settings → Privacy & security → Windows Security → Firewall & network protection."; }
    }
    private sealed class LiveTransferRow(LiveTransfer value) : System.ComponentModel.INotifyPropertyChanged
    {
        public LiveTransfer Value { get; private set; } = value;
        public string Name => Value.Name;
        public string Detail => Value.Detail;
        public double Percent => Value.Percent;
        public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;
        public void Update(LiveTransfer next) {
            if (Value == next) return;
            Value = next; PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(null));
        }
    }
    private sealed record HistoryRow(TransferHistoryEntry Entry)
    {
        public string Name => Entry.Name;
        public string Detail => $"{Entry.Direction} · {Entry.Status} · {Entry.Peer} · {Entry.Size / 1048576.0:0.#} MB · {Entry.When.LocalDateTime:g}";
    }
    private void FilterHistory(object sender, RoutedEventArgs e) => RenderHistory();
    private void RenderHistory()
    {
        if (HistoryList == null || HistorySearch == null || HistoryFilter == null) return;
        string query = HistorySearch.Text.Trim();
        string status = (HistoryFilter.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "All";
        var rows = displayedHistory.Where(x => (status == "All" || x.Status == status)
            && (x.Name + " " + x.Peer + " " + x.Status).Contains(query, StringComparison.OrdinalIgnoreCase)).Select(x => new HistoryRow(x)).ToList();
        HistoryList.ItemsSource = rows;
        EmptyHistoryLabel.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
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
        // Regression: render an in-progress row with a read-only Percent property.
        displayedIncoming.Add(new LiveTransferRow(new LiveTransfer(
            "binding-smoke", "In-progress transfer", "Receiving", "Running", 512, 1024, 0)));
        EmptyIncomingLabel.Visibility = Visibility.Collapsed;
        TransferNav.IsChecked = true;
        IncomingList.UpdateLayout();
        UpdateLayout();
        File.WriteAllText(Path.Combine(output, "progress-binding-verification.txt"),
            "PASS: live transfer row with read-only Percent rendered successfully.\n");
        foreach (var nav in new[] { TransferNav, HistoryNav, DevicesNav, SettingsNav })
        {
            nav.IsChecked = true;
            await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
            UpdateLayout();
            var bitmap = new RenderTargetBitmap((int)ActualWidth, (int)ActualHeight, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(this);
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var file = File.Create(Path.Combine(output, nav.Tag + ".png")); encoder.Save(file);
        }
        var peer = new System.Windows.Automation.Peers.ToggleButtonAutomationPeer(DarkModeToggle);
        var toggle = (System.Windows.Automation.Provider.IToggleProvider)peer.GetPattern(System.Windows.Automation.Peers.PatternInterface.Toggle);
        if (DarkModeToggle.IsChecked == true) toggle.Toggle();
        toggle.Toggle();
        if (DarkModeToggle.IsChecked != true || File.ReadAllText(themeFile) != "dark"
            || ((SolidColorBrush)System.Windows.Application.Current.Resources["Background"]).Color != (Color)ColorConverter.ConvertFromString("#17272C"))
            throw new InvalidOperationException("Dark appearance toggle did not apply and persist");
        SettingsNav.IsChecked = true;
        await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
        UpdateLayout();
        var settingsBitmap = new RenderTargetBitmap((int)ActualWidth, (int)ActualHeight, 96, 96, PixelFormats.Pbgra32);
        settingsBitmap.Render(this);
        var settingsEncoder = new PngBitmapEncoder(); settingsEncoder.Frames.Add(BitmapFrame.Create(settingsBitmap));
        using (var file = File.Create(Path.Combine(output, "Settings-dark.png"))) settingsEncoder.Save(file);
        TransferNav.IsChecked = true;
        await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
        UpdateLayout();
        var darkBitmap = new RenderTargetBitmap((int)ActualWidth, (int)ActualHeight, 96, 96, PixelFormats.Pbgra32);
        darkBitmap.Render(this);
        var darkEncoder = new PngBitmapEncoder(); darkEncoder.Frames.Add(BitmapFrame.Create(darkBitmap));
        using (var file = File.Create(Path.Combine(output, "Transfers-dark.png"))) darkEncoder.Save(file);
        toggle.Toggle();
        if (DarkModeToggle.IsChecked != false || File.ReadAllText(themeFile) != "light"
            || ((SolidColorBrush)System.Windows.Application.Current.Resources["Background"]).Color != (Color)ColorConverter.ConvertFromString("#F3F6F5"))
            throw new InvalidOperationException("Light appearance toggle did not apply and persist");
        File.WriteAllText(Path.Combine(output, "theme-verification.txt"), "PASS: accessible theme toggle; dark and light resources applied immediately; selection persisted.\n");
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
            string downloadDescription = release.Delta == null
                ? $"Full download: {release.Size / 1048576.0:0.0} MB"
                : $"Delta: {release.Delta.Size / 1048576.0:0.0} MB (full download: {release.Size / 1048576.0:0.0} MB)";
            if (MessageBox.Show($"Nerqo {release.Version} is available.\n\nDownload verified Windows installer?\n{downloadDescription} Your files and paired devices will be preserved.\n\nUpdates require internet. File transfers do not.", "Nerqo updates", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
                return;
            OperationProgress.IsIndeterminate = false;
            OperationProgress.Visibility = Visibility.Visible;
            var progress = new Progress<int>(percent => { OperationProgress.Value = percent; FooterLabel.Text = $"Downloading Windows update: {percent}%"; });
            var installer = await NativeUpdateService.DownloadAsync(release, progress, CancellationToken.None);
            FooterLabel.Text = "Update downloaded and SHA-256 verified";
            if (MessageBox.Show("The download passed the SHA-256 check. Launch Windows setup? Windows may warn because the installer does not yet have a trusted Authenticode signature.", "Install Nerqo", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes)
            {
                Process.Start(new ProcessStartInfo(installer) { UseShellExecute = true });
                await Quit();
            }
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
