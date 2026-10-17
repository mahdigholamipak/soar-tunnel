using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using QRCoder;

namespace SoarClient
{
    public partial class MainWindow : Window
    {
        private const string CurrentVersion = "v1.2.0";
        private const string GitHubRepo = "mahdigholamipak/soar-tunnel";

        private Process? _tunnelProcess;
        private bool _isConnected = false;
        private string _configPath = "client_config.toml";
        private List<ServerProfile> _profiles = new();
        private ServerProfile? _activeProfile;

        // Resolver Scanner
        private System.Threading.CancellationTokenSource? _scanCts;
        private readonly List<(string Addr, long PingMs)> _healthyScanResults = new();
        private int _totalScanTested = 0;

        // System Tray
        private System.Windows.Forms.NotifyIcon? _notifyIcon;
        private bool _isExplicitExit = false;

        // Clipboard Auto-Detect
        private string _lastAutoDetectedClipboard = "";
        private bool _isSystemProxySet = false;

        // Syntax Highlighting Brushes
        private readonly SolidColorBrush _brushGreen = new((Color)ColorConverter.ConvertFromString("#10B981"));
        private readonly SolidColorBrush _brushRed = new((Color)ColorConverter.ConvertFromString("#EF4444"));
        private readonly SolidColorBrush _brushAmber = new((Color)ColorConverter.ConvertFromString("#F59E0B"));
        private readonly SolidColorBrush _brushCyan = new((Color)ColorConverter.ConvertFromString("#38BDF8"));
        private readonly SolidColorBrush _brushPurple = new((Color)ColorConverter.ConvertFromString("#A855F7"));
        private readonly SolidColorBrush _brushGray = new((Color)ColorConverter.ConvertFromString("#64748B"));
        private readonly SolidColorBrush _brushDefault = new((Color)ColorConverter.ConvertFromString("#94A3B8"));
        private const int MaxLogParagraphs = 800;

        public MainWindow()
        {
            InitializeComponent();
            Loaded += MainWindow_Loaded;
            Closing += MainWindow_Closing;
            StateChanged += MainWindow_StateChanged;
            Activated += MainWindow_Activated;
        }

        private void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            ProxyHelper.BackupCurrentProxy();
            InitSystemTray();
            LoadProfilesList();
            UpdateFooterProxyText();
            LoadResolverBank();
            _ = CheckForUpdatesAsync(silent: true);
        }

        private void MainWindow_Activated(object? sender, EventArgs e)
        {
            if (ChkAutoDetectClipboard?.IsChecked != true) return;

            try
            {
                if (Clipboard.ContainsText())
                {
                    var text = Clipboard.GetText()?.Trim() ?? "";
                    if (text.StartsWith("soar://", StringComparison.OrdinalIgnoreCase) && text != _lastAutoDetectedClipboard)
                    {
                        _lastAutoDetectedClipboard = text;
                        var res = MessageBox.Show(
                            "Detected a Soar profile link in your clipboard:\n\n" +
                            text + "\n\n" +
                            "Would you like to import this server profile now?",
                            "Clipboard Auto-Detect",
                            MessageBoxButton.YesNo,
                            MessageBoxImage.Question);

                        if (res == MessageBoxResult.Yes)
                        {
                            ImportProfileFromUri(text);
                        }
                    }
                }
            }
            catch { }
        }

        private void InitSystemTray()
        {
            try
            {
                _notifyIcon = new System.Windows.Forms.NotifyIcon
                {
                    Text = "Soar DNS Tunnel",
                    Visible = true,
                    Icon = System.Drawing.SystemIcons.Shield
                };
                _notifyIcon.DoubleClick += (s, e) =>
                {
                    Show();
                    WindowState = WindowState.Normal;
                    Activate();
                };

                var contextMenu = new System.Windows.Forms.ContextMenuStrip();
                contextMenu.Items.Add("Open Soar Client", null, (s, e) =>
                {
                    Show();
                    WindowState = WindowState.Normal;
                    Activate();
                });
                contextMenu.Items.Add("Connect / Disconnect", null, (s, e) =>
                {
                    Dispatcher.Invoke(() => BtnConnect_Click(this, new RoutedEventArgs()));
                });
                contextMenu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
                contextMenu.Items.Add("Exit", null, (s, e) =>
                {
                    _isExplicitExit = true;
                    Close();
                });
                _notifyIcon.ContextMenuStrip = contextMenu;
            }
            catch { }
        }

        private void MainWindow_StateChanged(object? sender, EventArgs e)
        {
            if (WindowState == WindowState.Minimized && ChkMinimizeToTray.IsChecked == true)
            {
                Hide();
                _notifyIcon?.ShowBalloonTip(2000, "Soar DNS Tunnel", "Running minimized in system tray.", System.Windows.Forms.ToolTipIcon.Info);
            }
        }

        private async void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
        {
            if (!_isExplicitExit && ChkMinimizeToTray.IsChecked == true)
            {
                e.Cancel = true;
                Hide();
                _notifyIcon?.ShowBalloonTip(2000, "Soar DNS Tunnel", "Minimized to tray. Right-click icon to exit.", System.Windows.Forms.ToolTipIcon.Info);
                return;
            }

            if (_isConnected)
            {
                await StopTunnelAsync();
            }

            if (_isSystemProxySet || ChkSystemProxy.IsChecked == true)
            {
                ProxyHelper.RestoreOriginalProxy();
                _isSystemProxySet = false;
            }

            _notifyIcon?.Dispose();
            _notifyIcon = null;
        }

        #region Profile Management

        private void LoadProfilesList()
        {
            var appDir = AppContext.BaseDirectory;
            _profiles = ConfigManager.LoadProfiles(appDir);
            CmbProfiles.ItemsSource = null;
            CmbProfiles.ItemsSource = _profiles;
            if (_profiles.Count > 0)
            {
                CmbProfiles.SelectedIndex = 0;
            }
        }

        private void CmbProfiles_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (CmbProfiles.SelectedItem is ServerProfile profile)
            {
                _activeProfile = profile;
                ApplyProfileToUI(profile);
            }
        }

        private void ApplyProfileToUI(ServerProfile profile)
        {
            if (profile.IsLocked)
            {
                TxtServerKey.Text = "•••••••••••••••••••••••••••••••• (Protected)";
                TxtServerKey.IsReadOnly = true;
                TxtDomains.Text = "Protected Domains (Hidden)";
                TxtDomains.IsReadOnly = true;
            }
            else
            {
                TxtServerKey.Text = profile.ServerPublicKey;
                TxtServerKey.IsReadOnly = false;
                TxtDomains.Text = string.Join(", ", profile.Domains);
                TxtDomains.IsReadOnly = false;
            }

            TxtListenPort.Text = profile.ListenPort.ToString();
            TxtResolversFile.Text = profile.ResolversFile;
            UpdateFooterProxyText();
            AppendLog($"[PROFILE] Active profile: {profile.Name} (Locked: {profile.IsLocked})");
        }

        private void SaveCurrentProfileFromUI()
        {
            if (_activeProfile == null) return;

            if (!_activeProfile.IsLocked)
            {
                _activeProfile.ServerPublicKey = TxtServerKey.Text.Trim();
                _activeProfile.Domains = TxtDomains.Text.Trim()
                    .Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries)
                    .Select(d => d.Trim()).ToList();
            }

            if (int.TryParse(TxtListenPort.Text.Trim(), out var p) && p > 0)
                _activeProfile.ListenPort = p;

            _activeProfile.ResolversFile = TxtResolversFile.Text.Trim();

            var appDir = AppContext.BaseDirectory;
            ConfigManager.SaveProfiles(appDir, _profiles);
        }

        private void BtnAddProfile_Click(object sender, RoutedEventArgs e)
        {
            var newProfile = new ServerProfile
            {
                Name = $"Server {_profiles.Count + 1}",
                Domains = new List<string>(),
                ServerPublicKey = "",
                ListenPort = 10850,
                IsLocked = false
            };
            _profiles.Add(newProfile);
            CmbProfiles.ItemsSource = null;
            CmbProfiles.ItemsSource = _profiles;
            CmbProfiles.SelectedItem = newProfile;
            AppendLog($"[PROFILE] Created new profile: {newProfile.Name}");
        }

        private void BtnDeleteProfile_Click(object sender, RoutedEventArgs e)
        {
            if (_profiles.Count <= 1)
            {
                MessageBox.Show("Cannot delete the only remaining profile.", "Info", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }

            if (_activeProfile != null)
            {
                _profiles.Remove(_activeProfile);
                var appDir = AppContext.BaseDirectory;
                ConfigManager.SaveProfiles(appDir, _profiles);
                LoadProfilesList();
            }
        }

        private void BtnImportLink_Click(object sender, RoutedEventArgs e)
        {
            var text = Clipboard.GetText()?.Trim() ?? "";
            if (!text.StartsWith("soar://", StringComparison.OrdinalIgnoreCase))
            {
                MessageBox.Show(
                    "Please copy your 'soar://profile?...' or 'soar://locked?...' link to the clipboard first, then click 'Import Link'.",
                    "Import Server Link",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information);
                return;
            }

            if (!ImportProfileFromUri(text))
            {
                MessageBox.Show("Invalid or unsupported link format. Must start with soar://profile or soar://locked", "Import Error", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private bool ImportProfileFromUri(string text)
        {
            var imported = ConfigManager.ImportProfileUri(text);
            if (imported != null)
            {
                _profiles.Add(imported);
                var appDir = AppContext.BaseDirectory;
                ConfigManager.SaveProfiles(appDir, _profiles);
                CmbProfiles.ItemsSource = null;
                CmbProfiles.ItemsSource = _profiles;
                CmbProfiles.SelectedItem = imported;
                AppendLog($"[PROFILE] Imported new profile '{imported.Name}' (Locked: {imported.IsLocked}).");
                MessageBox.Show($"Profile '{imported.Name}' successfully imported!", "Import Success", MessageBoxButton.OK, MessageBoxImage.Information);
                return true;
            }
            return false;
        }

        private void BtnShareProfile_Click(object sender, RoutedEventArgs e)
        {
            if (_activeProfile == null) return;

            var res = MessageBox.Show(
                "Choose sharing mode:\n\n" +
                "[YES] - Share Protected (Locked): Hides domains and keys in recipient's UI.\n" +
                "[NO]  - Standard Share: Plain visible connection link.",
                "Share Server Profile",
                MessageBoxButton.YesNoCancel,
                MessageBoxImage.Question);

            if (res == MessageBoxResult.Cancel) return;

            bool isLocked = (res == MessageBoxResult.Yes) || _activeProfile.IsLocked;
            var link = ConfigManager.ExportProfileUri(_activeProfile, isLocked);
            Clipboard.SetText(link);

            ShowQrCodeWindow(_activeProfile.Name, link, isLocked);
        }

        private void ShowQrCodeWindow(string profileName, string link, bool isLocked)
        {
            try
            {
                using var qrGenerator = new QRCodeGenerator();
                using var qrCodeData = qrGenerator.CreateQrCode(link, QRCodeGenerator.ECCLevel.M);
                using var qrCode = new PngByteQRCode(qrCodeData);
                byte[] qrBytes = qrCode.GetGraphic(12);

                var bmp = new BitmapImage();
                using (var ms = new MemoryStream(qrBytes))
                {
                    bmp.BeginInit();
                    bmp.CacheOption = BitmapCacheOption.OnLoad;
                    bmp.StreamSource = ms;
                    bmp.EndInit();
                    bmp.Freeze();
                }

                var qrWindow = new Window
                {
                    Title = $"Share Profile: {profileName}",
                    Width = 380,
                    Height = 490,
                    WindowStartupLocation = WindowStartupLocation.CenterOwner,
                    Owner = this,
                    Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#0B0F17")),
                    Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#F8FAFC")),
                    ResizeMode = ResizeMode.NoResize
                };

                var rootStack = new StackPanel { Margin = new Thickness(20) };

                var titleBlock = new TextBlock
                {
                    Text = profileName,
                    FontSize = 16,
                    FontWeight = FontWeights.Bold,
                    Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#38BDF8")),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Margin = new Thickness(0, 0, 0, 4)
                };
                rootStack.Children.Add(titleBlock);

                var modeBlock = new TextBlock
                {
                    Text = isLocked ? "🔒 Protected (Locked) Profile" : "🌐 Standard Profile",
                    FontSize = 11,
                    Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#94A3B8")),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Margin = new Thickness(0, 0, 0, 16)
                };
                rootStack.Children.Add(modeBlock);

                var qrBorder = new Border
                {
                    Background = Brushes.White,
                    CornerRadius = new CornerRadius(12),
                    Padding = new Thickness(14),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Margin = new Thickness(0, 0, 0, 16)
                };
                var qrImg = new Image
                {
                    Source = bmp,
                    Width = 240,
                    Height = 240,
                    Stretch = Stretch.Uniform
                };
                qrBorder.Child = qrImg;
                rootStack.Children.Add(qrBorder);

                var noteBlock = new TextBlock
                {
                    Text = "Scan with Soar Tunnel Android or copy link below.",
                    FontSize = 10.5,
                    Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#64748B")),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Margin = new Thickness(0, 0, 0, 14)
                };
                rootStack.Children.Add(noteBlock);

                var btnCopy = new Button
                {
                    Content = "Copy Link to Clipboard",
                    Height = 36,
                    Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#0284C7")),
                    Foreground = Brushes.White,
                    FontWeight = FontWeights.SemiBold,
                    FontSize = 12,
                    BorderThickness = new Thickness(0),
                    Cursor = Cursors.Hand
                };
                btnCopy.Click += (s, ev) =>
                {
                    Clipboard.SetText(link);
                    MessageBox.Show("Profile link copied to clipboard!", "Copied", MessageBoxButton.OK, MessageBoxImage.Information);
                };
                rootStack.Children.Add(btnCopy);

                qrWindow.Content = rootStack;
                qrWindow.ShowDialog();
            }
            catch (Exception ex)
            {
                AppendLog($"[SHARE] QR generation error: {ex.Message}");
                MessageBox.Show($"Could not render QR Code: {ex.Message}\n\nLink copied to clipboard:\n{link}", "Share Profile", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }

        #endregion

        #region Tunnel Execution

        private async void BtnConnect_Click(object sender, RoutedEventArgs e)
        {
            if (_isConnected)
            {
                await StopTunnelAsync();
            }
            else
            {
                await StartTunnelAsync();
            }
        }

        private async Task StartTunnelAsync()
        {
            if (_activeProfile == null) return;
            SaveCurrentProfileFromUI();

            if (string.IsNullOrEmpty(_activeProfile.ServerPublicKey))
            {
                MessageBox.Show("Server Public Key is required.", "Validation Error", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            if (_activeProfile.Domains.Count == 0)
            {
                MessageBox.Show("At least one tunnel domain is required.", "Validation Error", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            var appDir = AppContext.BaseDirectory;
            var targetPath = Path.Combine(appDir, "client_config.toml");
            var cfg = new ClientConfigModel
            {
                Domains = _activeProfile.Domains,
                ServerPublicKey = _activeProfile.ServerPublicKey,
                ListenIP = TxtListenIP.Text.Trim(),
                ListenPort = _activeProfile.ListenPort,
                ResolversFile = _activeProfile.ResolversFile
            };
            ConfigManager.Save(targetPath, cfg);
            _configPath = targetPath;

            var exePath = Path.Combine(appDir, "soar-client.exe");
            if (!File.Exists(exePath))
            {
                exePath = "soar-client.exe";
            }

            UpdateUIState(connecting: true);
            AppendLog($"[START] Launching Soar tunnel client with profile '{_activeProfile.Name}'...");

            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = exePath,
                    Arguments = $"-config \"{targetPath}\"",
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    WorkingDirectory = appDir
                };

                _tunnelProcess = new Process { StartInfo = psi, EnableRaisingEvents = true };
                _tunnelProcess.OutputDataReceived += (s, e) => { if (!string.IsNullOrEmpty(e.Data)) Dispatcher.Invoke(() => HandleProcessOutput(e.Data)); };
                _tunnelProcess.ErrorDataReceived += (s, e) => { if (!string.IsNullOrEmpty(e.Data)) Dispatcher.Invoke(() => HandleProcessOutput(e.Data)); };
                _tunnelProcess.Exited += (s, e) => Dispatcher.BeginInvoke(new Action(HandleProcessExited));

                _tunnelProcess.Start();
                _tunnelProcess.BeginOutputReadLine();
                _tunnelProcess.BeginErrorReadLine();

                await Task.Delay(800);

                if (_tunnelProcess != null && !_tunnelProcess.HasExited)
                {
                    _isConnected = true;
                    UpdateUIState(connected: true);
                    AppendLog("[STATUS] Tunnel connection active.");

                    if (ChkSystemProxy.IsChecked == true)
                    {
                        ProxyHelper.SetSystemProxy(cfg.ListenIP, cfg.ListenPort);
                        _isSystemProxySet = true;
                        AppendLog($"[PROXY] Windows system proxy routed to {cfg.ListenIP}:{cfg.ListenPort}");
                    }
                    else
                    {
                        ProxyHelper.RestoreOriginalProxy();
                        _isSystemProxySet = false;
                    }
                }
                else
                {
                    UpdateUIState(connected: false);
                    AppendLog("[ERROR] Failed to start tunnel process.");
                }
            }
            catch (Exception ex)
            {
                UpdateUIState(connected: false);
                AppendLog($"[ERROR] {ex.Message}");
                MessageBox.Show($"Failed to launch soar-client: {ex.Message}", "Process Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private void ChkSystemProxy_Click(object sender, RoutedEventArgs e)
        {
            if (_isConnected)
            {
                if (ChkSystemProxy.IsChecked == true)
                {
                    var ip = string.IsNullOrWhiteSpace(TxtListenIP?.Text) ? "127.0.0.1" : TxtListenIP.Text.Trim();
                    var port = int.TryParse(TxtListenPort?.Text.Trim(), out var p) ? p : 10850;
                    ProxyHelper.SetSystemProxy(ip, port);
                    _isSystemProxySet = true;
                    AppendLog($"[PROXY] Windows system proxy enabled ({ip}:{port})");
                }
                else
                {
                    ProxyHelper.RestoreOriginalProxy();
                    _isSystemProxySet = false;
                    AppendLog("[PROXY] Windows system proxy disabled.");
                }
            }
            else
            {
                if (ChkSystemProxy.IsChecked != true)
                {
                    ProxyHelper.RestoreOriginalProxy();
                    _isSystemProxySet = false;
                }
            }
        }

        private async Task StopTunnelAsync()
        {
            if (!_isConnected && _tunnelProcess == null) return;

            // Immediately mark disconnected and disarm exit events to avoid race conditions and UI freezes
            _isConnected = false;
            UpdateUIState(connecting: true);
            StatusText.Text = "Disconnecting...";
            BtnConnect.IsEnabled = false;
            AppendLog("[STOP] Disconnecting tunnel...");

            var proc = _tunnelProcess;
            _tunnelProcess = null;
            if (proc != null)
            {
                try { proc.EnableRaisingEvents = false; } catch { }
            }

            var restoreProxy = _isSystemProxySet || (ChkSystemProxy.IsChecked == true);
            _isSystemProxySet = false;

            await Task.Run(() =>
            {
                if (proc != null)
                {
                    try
                    {
                        if (!proc.HasExited)
                        {
                            proc.Kill(entireProcessTree: true);
                            proc.WaitForExit(1500);
                        }
                    }
                    catch { }
                    finally
                    {
                        try { proc.Dispose(); } catch { }
                    }
                }

                if (restoreProxy)
                {
                    try
                    {
                        ProxyHelper.RestoreOriginalProxy();
                    }
                    catch { }
                }
            });

            if (restoreProxy)
            {
                AppendLog("[PROXY] System proxy settings restored.");
            }

            UpdateUIState(connected: false);
            AppendLog("[STATUS] Disconnected.");
        }

        private void HandleProcessOutput(string line)
        {
            if (line.StartsWith("[TELEMETRY]"))
            {
                try
                {
                    var parts = line.Substring("[TELEMETRY]".Length).Trim().Split(' ');
                    var dict = new Dictionary<string, string>();
                    foreach (var part in parts)
                    {
                        var kv = part.Split('=');
                        if (kv.Length == 2) dict[kv[0]] = kv[1];
                    }

                    if (dict.TryGetValue("tx_rate", out var txr))
                    {
                        TxtSpeedTx.Text = $"{txr} KB/s";
                        TxtHeroTx.Text = $"{txr} KB/s";
                    }
                    if (dict.TryGetValue("rx_rate", out var rxr))
                    {
                        TxtSpeedRx.Text = $"{rxr} KB/s";
                        TxtHeroRx.Text = $"{rxr} KB/s";
                    }
                    if (dict.TryGetValue("streams", out var s))
                    {
                        TxtHeroStreams.Text = s;
                    }
                    if (dict.TryGetValue("tx", out var tx) && dict.TryGetValue("rx", out var rx))
                    {
                        ulong txBytes = ulong.TryParse(tx, out var txb) ? txb : 0;
                        ulong rxBytes = ulong.TryParse(rx, out var rxb) ? rxb : 0;
                        ulong total = txBytes + rxBytes;
                        TxtHeroTotal.Text = FormatBytes(total);
                        TxtStats.Text = $"Streams: {dict.GetValueOrDefault("streams", "0")} | Total: {FormatBytes(total)} (TX {FormatBytes(txBytes)}, RX {FormatBytes(rxBytes)})";
                    }
                }
                catch { }
                return;
            }

            AppendLog(line);
        }

        private void HandleProcessExited()
        {
            if (_isConnected)
            {
                AppendLog("[WARN] Tunnel process terminated unexpectedly.");
                _isConnected = false;
                UpdateUIState(connected: false);

                if (_isSystemProxySet || ChkSystemProxy.IsChecked == true)
                {
                    _isSystemProxySet = false;
                    Task.Run(() =>
                    {
                        try
                        {
                            ProxyHelper.RestoreOriginalProxy();
                            Dispatcher.Invoke(() => AppendLog("[PROXY] Restored original system proxy."));
                        }
                        catch { }
                    });
                }
            }
        }

        private void TxtListenPort_TextChanged(object sender, TextChangedEventArgs e)
        {
            UpdateFooterProxyText();
        }

        private void UpdateFooterProxyText()
        {
            var ip = string.IsNullOrWhiteSpace(TxtListenIP?.Text) ? "127.0.0.1" : TxtListenIP.Text.Trim();
            var port = string.IsNullOrWhiteSpace(TxtListenPort?.Text) ? "10850" : TxtListenPort.Text.Trim();
            if (TxtFooterProxy != null)
            {
                TxtFooterProxy.Text = $"SOCKS5: socks5://{ip}:{port} | Telegram: tg://socks?server={ip}&port={port}";
            }
        }

        private void TxtFooterProxy_MouseDown(object sender, MouseButtonEventArgs e)
        {
            var ip = string.IsNullOrWhiteSpace(TxtListenIP?.Text) ? "127.0.0.1" : TxtListenIP.Text.Trim();
            var port = string.IsNullOrWhiteSpace(TxtListenPort?.Text) ? "10850" : TxtListenPort.Text.Trim();
            var tgLink = $"tg://socks?server={ip}&port={port}";
            try
            {
                Clipboard.SetText(tgLink);
                AppendLog($"[CLIPBOARD] Copied Telegram proxy link: {tgLink}");
                MessageBox.Show($"Copied Telegram proxy link to clipboard:\n\n{tgLink}", "Proxy Link Copied", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            catch { }
        }

        private void UpdateUIState(bool connected = false, bool connecting = false)
        {
            if (connecting)
            {
                StatusDot.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#F59E0B"));
                StatusText.Text = "Connecting...";
                BtnConnect.IsEnabled = false;
            }
            else if (connected)
            {
                StatusDot.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#10B981"));
                StatusText.Text = "Connected";
                BtnConnect.Content = "DISCONNECT";
                BtnConnect.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#EF4444"));
                BtnConnect.IsEnabled = true;
                _notifyIcon?.ShowBalloonTip(1500, "Soar DNS Tunnel", "Connected successfully.", System.Windows.Forms.ToolTipIcon.Info);
            }
            else
            {
                StatusDot.Fill = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#EF4444"));
                StatusText.Text = "Disconnected";
                BtnConnect.Content = "CONNECT";
                BtnConnect.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#0284C7"));
                BtnConnect.IsEnabled = true;
                TxtSpeedTx.Text = "0.0 KB/s";
                TxtSpeedRx.Text = "0.0 KB/s";
                TxtHeroTx.Text = "0.0 KB/s";
                TxtHeroRx.Text = "0.0 KB/s";
                TxtHeroStreams.Text = "0";
                TxtHeroTotal.Text = "0 MB";
                TxtStats.Text = "Idle";
            }
        }

        #endregion

        #region Resolvers & Bank Management

        private static byte[] BuildDnsQuery(string domain)
        {
            domain = domain.Trim().TrimEnd('.');
            var query = new List<byte> { 0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
            foreach (var label in domain.Split('.'))
            {
                if (label.Length > 0)
                {
                    query.Add((byte)label.Length);
                    query.AddRange(System.Text.Encoding.ASCII.GetBytes(label));
                }
            }
            query.Add(0x00);
            query.AddRange(new byte[] { 0x00, 0x06 }); // Type SOA
            query.AddRange(new byte[] { 0x00, 0x01 }); // Class IN
            return query.ToArray();
        }

        private void BtnImportResolversFile_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                var ofd = new Microsoft.Win32.OpenFileDialog
                {
                    Title = "Import Candidate DNS Resolvers (.txt)",
                    Filter = "Text Files (*.txt)|*.txt|All Files (*.*)|*.*",
                    Multiselect = false
                };

                if (ofd.ShowDialog() == true)
                {
                    var selectedFile = ofd.FileName;
                    var lines = File.ReadAllLines(selectedFile)
                        .Select(l => l.Trim())
                        .Where(l => !string.IsNullOrEmpty(l) && !l.StartsWith("#") && !l.StartsWith(";"))
                        .Distinct()
                        .ToList();

                    if (lines.Count == 0)
                    {
                        MessageBox.Show("No valid resolver IP addresses found in the selected file.", "Import Resolvers", MessageBoxButton.OK, MessageBoxImage.Warning);
                        return;
                    }

                    var appDir = AppContext.BaseDirectory;
                    var targetFileName = string.IsNullOrWhiteSpace(TxtResolversFile.Text) ? "client_resolvers.txt" : TxtResolversFile.Text.Trim();
                    var targetPath = Path.Combine(appDir, targetFileName);

                    var res = MessageBox.Show(
                        $"Found {lines.Count} resolvers in '{Path.GetFileName(selectedFile)}'.\n\n" +
                        $"Choose an import action for '{targetFileName}':\n\n" +
                        $"• [Yes] Overwrite '{targetFileName}' with these {lines.Count} resolvers\n" +
                        $"• [No] Append to existing resolvers in '{targetFileName}'\n" +
                        $"• [Cancel] Abort import",
                        "Import Candidate Resolvers",
                        MessageBoxButton.YesNoCancel,
                        MessageBoxImage.Question);

                    if (res == MessageBoxResult.Cancel) return;

                    if (res == MessageBoxResult.Yes)
                    {
                        File.WriteAllLines(targetPath, lines);
                        AppendLog($"[RESOLVER] Imported {lines.Count} candidate resolvers from '{Path.GetFileName(selectedFile)}' into '{targetFileName}'.");
                    }
                    else if (res == MessageBoxResult.No)
                    {
                        var existing = File.Exists(targetPath) ? File.ReadAllLines(targetPath).Select(l => l.Trim()).Where(l => !string.IsNullOrEmpty(l)).ToList() : new List<string>();
                        var combined = existing.Concat(lines).Distinct().ToList();
                        File.WriteAllLines(targetPath, combined);
                        AppendLog($"[RESOLVER] Appended {lines.Count} candidate resolvers into '{targetFileName}' (Total: {combined.Count}).");
                    }

                    MessageBox.Show($"Successfully processed {lines.Count} resolvers into '{targetFileName}'!", "Import Successful", MessageBoxButton.OK, MessageBoxImage.Information);
                }
            }
            catch (Exception ex)
            {
                AppendLog($"[RESOLVER] Error importing resolvers file: {ex.Message}");
                MessageBox.Show($"Failed to import file: {ex.Message}", "Import Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private void BtnRefreshBank_Click(object sender, RoutedEventArgs e)
        {
            LoadResolverBank();
        }

        private void LoadResolverBank()
        {
            try
            {
                var appDir = AppContext.BaseDirectory;
                var bankPath = Path.Combine(appDir, "resolvers_bank.json");
                if (!File.Exists(bankPath))
                {
                    TxtBankCount.Text = "0 cached (Bank starts automatically on connect)";
                    LvResolverBank.ItemsSource = null;
                    return;
                }

                var json = File.ReadAllText(bankPath);
                var entries = JsonSerializer.Deserialize<Dictionary<string, BankEntryItem>>(json, new JsonSerializerOptions
                {
                    PropertyNameCaseInsensitive = true
                });

                if (entries == null || entries.Count == 0)
                {
                    TxtBankCount.Text = "0 cached";
                    LvResolverBank.ItemsSource = null;
                    return;
                }

                var list = entries.Values.Select(v => new ResolverBankItemViewModel
                {
                    Addr = v.Addr,
                    RttMs = v.RttMs,
                    Score = v.Score,
                    SuccessCount = v.SuccessCount,
                    FailCount = v.FailCount,
                    MaxEDNS0 = v.MaxEDNS0,
                    LastVerified = v.LastVerified
                }).OrderByDescending(x => x.Score).ToList();

                LvResolverBank.ItemsSource = list;
                TxtBankCount.Text = $"{list.Count} cached in bank (Ranked by EWMA score)";
                AppendLog($"[BANK] Loaded {list.Count} persistent resolver metrics from bank cache.");
            }
            catch (Exception ex)
            {
                AppendLog($"[BANK] Error reading resolvers_bank.json: {ex.Message}");
            }
        }

        private void BtnDeleteBankEntry_Click(object sender, RoutedEventArgs e)
        {
            DeleteSelectedBankEntry();
        }

        private void LvResolverBank_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Delete)
            {
                DeleteSelectedBankEntry();
            }
        }

        private void DeleteSelectedBankEntry()
        {
            if (LvResolverBank.SelectedItem is not ResolverBankItemViewModel selected)
            {
                MessageBox.Show("Please select a resolver in the bank to delete.", "Delete Resolver", MessageBoxButton.OK, MessageBoxImage.Information);
                return;
            }

            var res = MessageBox.Show($"Are you sure you want to delete resolver '{selected.Addr}' from the persistent bank?", "Delete Resolver", MessageBoxButton.YesNo, MessageBoxImage.Question);
            if (res != MessageBoxResult.Yes) return;

            try
            {
                var appDir = AppContext.BaseDirectory;
                var bankPath = Path.Combine(appDir, "resolvers_bank.json");
                if (File.Exists(bankPath))
                {
                    var json = File.ReadAllText(bankPath);
                    var entries = JsonSerializer.Deserialize<Dictionary<string, BankEntryItem>>(json, new JsonSerializerOptions
                    {
                        PropertyNameCaseInsensitive = true
                    }) ?? new();

                    if (entries.Remove(selected.Addr))
                    {
                        var updatedJson = JsonSerializer.Serialize(entries, new JsonSerializerOptions { WriteIndented = true });
                        File.WriteAllText(bankPath, updatedJson);
                        AppendLog($"[BANK] Deleted resolver {selected.Addr} from persistent bank cache.");
                        LoadResolverBank();
                    }
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Failed to delete bank entry: {ex.Message}", "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private void BtnClearBank_Click(object sender, RoutedEventArgs e)
        {
            var res = MessageBox.Show("Are you sure you want to clear the entire persistent resolver bank?\n\nAll cached latency scores and history will be reset.", "Clear Resolver Bank", MessageBoxButton.YesNo, MessageBoxImage.Warning);
            if (res != MessageBoxResult.Yes) return;

            try
            {
                var appDir = AppContext.BaseDirectory;
                var bankPath = Path.Combine(appDir, "resolvers_bank.json");
                if (File.Exists(bankPath))
                {
                    File.Delete(bankPath);
                }
                LvResolverBank.ItemsSource = null;
                TxtBankCount.Text = "0 cached (Bank cleared)";
                AppendLog("[BANK] Cleared all persistent resolver bank entries.");
                MessageBox.Show("Resolver bank cleared successfully.", "Bank Cleared", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Failed to clear bank: {ex.Message}", "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private async void BtnTestResolvers_Click(object sender, RoutedEventArgs e)
        {
            if (_scanCts != null)
            {
                _scanCts.Cancel();
                _scanCts = null;
                BtnTestResolvers.Content = "⚡ Test Latency";
                BtnTestResolvers.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#38BDF8"));
                AppendLog("[RESOLVER] Scanner stopped by user.");

                if (_healthyScanResults.Count > 0)
                {
                    List<(string Addr, long PingMs)> sorted;
                    lock (_healthyScanResults)
                    {
                        sorted = _healthyScanResults.OrderBy(x => x.PingMs).ToList();
                    }
                    ShowHealthyResolversDialog(sorted, _totalScanTested);
                }
                return;
            }

            string domain = "";
            if (!string.IsNullOrWhiteSpace(TxtDomains.Text))
            {
                var parts = TxtDomains.Text.Split(new[] { ',', ';', '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length > 0) domain = parts[0].Trim();
            }
            if (string.IsNullOrEmpty(domain) || domain.Contains("Protected"))
            {
                domain = "google.com";
            }

            var filePath = Path.Combine(AppContext.BaseDirectory, TxtResolversFile.Text.Trim());
            if (!File.Exists(filePath))
            {
                AppendLog($"[RESOLVER] Resolvers file not found: {filePath}");
                MessageBox.Show($"Resolvers file not found:\n{filePath}", "File Not Found", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            var lines = File.ReadAllLines(filePath)
                .Select(l => l.Trim())
                .Where(l => !string.IsNullOrEmpty(l) && !l.StartsWith("#") && !l.StartsWith(";"))
                .ToList();

            if (lines.Count == 0)
            {
                AppendLog($"[RESOLVER] No candidate resolvers found in: {filePath}");
                return;
            }

            lock (_healthyScanResults)
            {
                _healthyScanResults.Clear();
            }
            _totalScanTested = lines.Count;

            _scanCts = new System.Threading.CancellationTokenSource();
            var token = _scanCts.Token;

            BtnTestResolvers.Content = "⏹ Stop Test";
            BtnTestResolvers.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#EF4444"));
            AppendLog($"[RESOLVER] Testing {lines.Count} resolvers for {domain} in parallel (Click 'Stop Test' anytime)...");

            var query = BuildDnsQuery(domain);

            try
            {
                await Task.Run(async () =>
                {
                    using var semaphore = new System.Threading.SemaphoreSlim(12);
                    var tasks = new List<Task>();

                    foreach (var r in lines)
                    {
                        if (token.IsCancellationRequested) break;
                        await semaphore.WaitAsync(token);

                        tasks.Add(Task.Run(() =>
                        {
                            try
                            {
                                if (token.IsCancellationRequested) return;

                                var ep = r.Contains(':') ? r : r + ":53";
                                var parts = ep.Split(':');
                                var host = parts[0];
                                var port = int.Parse(parts[1]);

                                var sw = Stopwatch.StartNew();
                                using var client = new System.Net.Sockets.UdpClient();
                                client.Client.ReceiveTimeout = 900;
                                client.Send(query, query.Length, host, port);
                                var remote = new System.Net.IPEndPoint(System.Net.IPAddress.Any, 0);
                                var resp = client.Receive(ref remote);
                                sw.Stop();

                                if (resp.Length >= 12)
                                {
                                    bool qr = (resp[2] & 0x80) != 0;
                                    bool ra = (resp[3] & 0x80) != 0;
                                    int rcode = resp[3] & 0x0F;
                                    int an = (resp[6] << 8) | resp[7];
                                    int ns = (resp[8] << 8) | resp[9];

                                    if (qr && ra && rcode == 0 && (an > 0 || ns > 0))
                                    {
                                        lock (_healthyScanResults)
                                        {
                                            _healthyScanResults.Add((ep, sw.ElapsedMilliseconds));
                                        }
                                        Dispatcher.Invoke(() => AppendLog($"[RESOLVER] {ep} -> {sw.ElapsedMilliseconds} ms (OK)"));
                                    }
                                }
                            }
                            catch { }
                            finally
                            {
                                semaphore.Release();
                            }
                        }));
                    }

                    try
                    {
                        await Task.WhenAll(tasks);
                    }
                    catch { }
                }, token);
            }
            catch (OperationCanceledException) { }
            catch (Exception ex)
            {
                AppendLog($"[RESOLVER] Scanner error: {ex.Message}");
            }
            finally
            {
                _scanCts = null;
                BtnTestResolvers.Content = "⚡ Test Latency";
                BtnTestResolvers.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#38BDF8"));

                List<(string Addr, long PingMs)> sorted;
                lock (_healthyScanResults)
                {
                    sorted = _healthyScanResults.OrderBy(x => x.PingMs).ToList();
                }

                if (sorted.Count > 0)
                {
                    ShowHealthyResolversDialog(sorted, _totalScanTested);
                }
                else
                {
                    MessageBox.Show("No responsive resolvers found.", "Test Complete", MessageBoxButton.OK, MessageBoxImage.Information);
                }
            }
        }

        private void ShowHealthyResolversDialog(List<(string Addr, long PingMs)> results, int totalTested)
        {
            var sb = new System.Text.StringBuilder();
            sb.AppendLine($"Found {results.Count} of {totalTested} responsive resolvers:\n");

            for (int i = 0; i < Math.Min(results.Count, 12); i++)
            {
                var (addr, ping) = results[i];
                var badge = ping < 120 ? "🟢" : (ping < 300 ? "🟡" : "🟠");
                sb.AppendLine($"{badge} {addr} ({ping} ms)");
            }
            if (results.Count > 12)
            {
                sb.AppendLine($"... and {results.Count - 12} more.");
            }

            sb.AppendLine("\nOptions:");
            sb.AppendLine("• Click [Yes] to save the fastest resolvers into your active resolvers file.");
            sb.AppendLine("• Click [No] to copy all healthy resolvers to clipboard.");
            sb.AppendLine("• Click [Cancel] to dismiss.");

            var res = MessageBox.Show(sb.ToString(), "Healthy Resolvers", MessageBoxButton.YesNoCancel, MessageBoxImage.Information);
            if (res == MessageBoxResult.Yes)
            {
                var top = results.Take(Math.Min(results.Count, 10)).Select(x => x.Addr).ToList();
                var filePath = Path.Combine(AppContext.BaseDirectory, TxtResolversFile.Text.Trim());
                try
                {
                    File.WriteAllLines(filePath, top);
                    AppendLog($"[RESOLVER] Saved {top.Count} fastest resolvers to {filePath}");
                    MessageBox.Show($"Updated {TxtResolversFile.Text} with top {top.Count} fastest resolvers!", "Resolvers Saved", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                catch (Exception ex)
                {
                    AppendLog($"[RESOLVER] Error saving file: {ex.Message}");
                }
            }
            else if (res == MessageBoxResult.No)
            {
                var all = string.Join(Environment.NewLine, results.Select(x => x.Addr));
                try
                {
                    Clipboard.SetText(all);
                    AppendLog($"[RESOLVER] Copied {results.Count} healthy resolvers to clipboard.");
                    MessageBox.Show($"Copied {results.Count} healthy resolvers to clipboard!", "Copied", MessageBoxButton.OK, MessageBoxImage.Information);
                }
                catch { }
            }
        }

        #endregion

        #region Actions & Console Logs

        private void BtnLaunchTelegram_Click(object sender, RoutedEventArgs e)
        {
            var ip = string.IsNullOrWhiteSpace(TxtListenIP?.Text) ? "127.0.0.1" : TxtListenIP.Text.Trim();
            var port = string.IsNullOrWhiteSpace(TxtListenPort?.Text) ? "10850" : TxtListenPort.Text.Trim();
            var tgUrl = $"tg://socks?server={ip}&port={port}";
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = tgUrl,
                    UseShellExecute = true
                });
                AppendLog($"[TELEGRAM] Launched Telegram with proxy: {tgUrl}");
            }
            catch (Exception ex)
            {
                AppendLog($"[TELEGRAM] Failed to launch Telegram automatically: {ex.Message}");
                Clipboard.SetText(tgUrl);
                MessageBox.Show($"Could not automatically open Telegram. Link copied to clipboard:\n\n{tgUrl}", "Telegram Link", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }

        private void BtnCopyProxy_Click(object sender, RoutedEventArgs e)
        {
            var ip = string.IsNullOrWhiteSpace(TxtListenIP?.Text) ? "127.0.0.1" : TxtListenIP.Text.Trim();
            var port = string.IsNullOrWhiteSpace(TxtListenPort?.Text) ? "10850" : TxtListenPort.Text.Trim();
            var proxyUrl = $"socks5://{ip}:{port}";
            Clipboard.SetText(proxyUrl);
            AppendLog($"[CLIPBOARD] Copied SOCKS5 proxy address: {proxyUrl}");
            MessageBox.Show($"Proxy link copied to clipboard:\n{proxyUrl}", "Copied", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void BtnSaveConfig_Click(object sender, RoutedEventArgs e)
        {
            SaveCurrentProfileFromUI();
            MessageBox.Show("Profile successfully saved!", "Saved", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void BtnClearLogs_Click(object sender, RoutedEventArgs e)
        {
            RtbConsoleLog.Document?.Blocks.Clear();
        }

        private void BtnCopyLogs_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                if (RtbConsoleLog.Document == null) return;
                var textRange = new TextRange(RtbConsoleLog.Document.ContentStart, RtbConsoleLog.Document.ContentEnd);
                Clipboard.SetText(textRange.Text);
                AppendLog("[CLIPBOARD] All console logs copied to clipboard.");
                MessageBox.Show("All console logs copied to clipboard!", "Logs Copied", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Failed to copy logs: {ex.Message}", "Error", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private void AppendLog(string message)
        {
            if (RtbConsoleLog == null) return;

            if (!Dispatcher.CheckAccess())
            {
                Dispatcher.BeginInvoke(new Action(() => AppendLog(message)));
                return;
            }

            var ts = DateTime.Now.ToString("HH:mm:ss");
            var doc = RtbConsoleLog.Document;
            if (doc == null)
            {
                doc = new FlowDocument();
                RtbConsoleLog.Document = doc;
            }

            if (doc.Blocks.Count > MaxLogParagraphs)
            {
                for (int i = 0; i < 100 && doc.Blocks.Count > 0; i++)
                {
                    doc.Blocks.Remove(doc.Blocks.FirstBlock);
                }
            }

            var p = new Paragraph();
            p.Inlines.Add(new Run($"[{ts}] ") { Foreground = _brushGray });

            SolidColorBrush lineBrush = _brushDefault;
            if (message.StartsWith("[ERROR]", StringComparison.OrdinalIgnoreCase) || message.Contains("failed", StringComparison.OrdinalIgnoreCase))
            {
                lineBrush = _brushRed;
            }
            else if (message.StartsWith("[WARN]", StringComparison.OrdinalIgnoreCase))
            {
                lineBrush = _brushAmber;
            }
            else if (message.StartsWith("[STATUS]", StringComparison.OrdinalIgnoreCase) || message.StartsWith("[TUNNEL]", StringComparison.OrdinalIgnoreCase) || message.StartsWith("[PROXY]", StringComparison.OrdinalIgnoreCase) || message.StartsWith("[START]", StringComparison.OrdinalIgnoreCase))
            {
                lineBrush = _brushGreen;
            }
            else if (message.StartsWith("[TELEMETRY]", StringComparison.OrdinalIgnoreCase) || message.StartsWith("[STATS]", StringComparison.OrdinalIgnoreCase) || message.StartsWith("[CLIPBOARD]", StringComparison.OrdinalIgnoreCase) || message.StartsWith("[PROFILE]", StringComparison.OrdinalIgnoreCase))
            {
                lineBrush = _brushCyan;
            }
            else if (message.StartsWith("[BANK]", StringComparison.OrdinalIgnoreCase) || message.StartsWith("[RESOLVER]", StringComparison.OrdinalIgnoreCase))
            {
                lineBrush = _brushPurple;
            }

            p.Inlines.Add(new Run(message) { Foreground = lineBrush });
            doc.Blocks.Add(p);

            RtbConsoleLog.ScrollToEnd();
        }

        private async void BtnCheckUpdate_Click(object sender, RoutedEventArgs e)
        {
            await CheckForUpdatesAsync(silent: false);
        }

        private async Task CheckForUpdatesAsync(bool silent)
        {
            try
            {
                using var client = new HttpClient();
                client.DefaultRequestHeaders.UserAgent.ParseAdd("SoarClient/1.0");
                var json = await client.GetStringAsync($"https://api.github.com/repos/{GitHubRepo}/releases/latest");
                using var doc = JsonDocument.Parse(json);
                var tag = doc.RootElement.GetProperty("tag_name").GetString() ?? "";

                if (!string.IsNullOrEmpty(tag) && tag != CurrentVersion)
                {
                    var msg = $"New version {tag} is available!\nCurrent version is {CurrentVersion}.\n\nWould you like to open the GitHub Release download page?";
                    var res = MessageBox.Show(msg, "Update Available", MessageBoxButton.YesNo, MessageBoxImage.Information);
                    if (res == MessageBoxResult.Yes)
                    {
                        Process.Start(new ProcessStartInfo
                        {
                            FileName = $"https://github.com/{GitHubRepo}/releases/latest",
                            UseShellExecute = true
                        });
                    }
                }
                else if (!silent)
                {
                    MessageBox.Show($"You are running the latest version ({CurrentVersion}).", "No Updates", MessageBoxButton.OK, MessageBoxImage.Information);
                }
            }
            catch (Exception ex)
            {
                if (!silent)
                {
                    MessageBox.Show($"Could not check for updates: {ex.Message}", "Check Update Error", MessageBoxButton.OK, MessageBoxImage.Warning);
                }
            }
        }

        private void BtnAbout_Click(object sender, RoutedEventArgs e)
        {
            var msg = "SOAR DNS TUNNEL\n" +
                      "Version: " + CurrentVersion + "\n\n" +
                      "Credits & Attribution:\n" +
                      "Based on the foundational Soar DNS tunneling protocol developed and open-sourced by Lantern (github.com/getlantern/soar) under the Apache License 2.0.\n\n" +
                      "Ecosystem Features:\n" +
                      "• Multi-domain failover & racing\n" +
                      "• Dynamic resolver probing & EWMA latency ranking\n" +
                      "• Persistent Resolver Bank cache\n" +
                      "• Candidate resolver TXT file import\n" +
                      "• Privacy-preserving locked profile sharing & QR Codes\n" +
                      "• Windows System Proxy integration\n\n" +
                      "Repository: https://github.com/" + GitHubRepo;

            MessageBox.Show(msg, "About Soar DNS Tunnel", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private static string FormatBytes(ulong bytes)
        {
            if (bytes >= 1024 * 1024 * 1024) return $"{bytes / (1024.0 * 1024 * 1024):0.2f} GB";
            if (bytes >= 1024 * 1024) return $"{bytes / (1024.0 * 1024):0.2f} MB";
            if (bytes >= 1024) return $"{bytes / 1024.0:0.1f} KB";
            return $"{bytes} B";
        }

        #endregion
    }

    public class ResolverBankItemViewModel
    {
        public string Addr { get; set; } = "";
        public double RttMs { get; set; }
        public double Score { get; set; }
        public int SuccessCount { get; set; }
        public int FailCount { get; set; }
        public int MaxEDNS0 { get; set; }
        public DateTime LastVerified { get; set; }

        public string RTTFormatted => $"{RttMs:F1} ms";
        public string ScoreFormatted => $"{Score:F1}";
        public string SuccessFailFormatted => $"{SuccessCount} / {FailCount}";
        public string EDNSFormatted => MaxEDNS0 > 0 ? $"{MaxEDNS0} B" : "1232 B";
        public string LastVerifiedFormatted => LastVerified.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss");
    }

    public class BankEntryItem
    {
        public string Addr { get; set; } = "";
        public double RttMs { get; set; }
        public DateTime LastVerified { get; set; }
        public int SuccessCount { get; set; }
        public int FailCount { get; set; }
        public double Score { get; set; }
        public int MaxEDNS0 { get; set; }
    }
}
