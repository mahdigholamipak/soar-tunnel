using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;

namespace SoarClient
{
    public partial class MainWindow : Window
    {
        private const string CurrentVersion = "v1.0.2";
        private const string GitHubRepo = "mahdigholamipak/soar-tunnel";

        private Process? _tunnelProcess;
        private bool _isConnected = false;
        private string _configPath = "client_config.toml";
        private List<ServerProfile> _profiles = new();
        private ServerProfile? _activeProfile;

        // System Tray
        private System.Windows.Forms.NotifyIcon? _notifyIcon;
        private bool _isExplicitExit = false;

        public MainWindow()
        {
            InitializeComponent();
            Loaded += MainWindow_Loaded;
            Closing += MainWindow_Closing;
            StateChanged += MainWindow_StateChanged;
        }

        private void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            ProxyHelper.BackupCurrentProxy();
            InitSystemTray();
            LoadProfilesList();
            _ = CheckForUpdatesAsync(silent: true);
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

        private void CmbProfiles_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
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

            var imported = ConfigManager.ImportProfileUri(text);
            if (imported != null)
            {
                _profiles.Add(imported);
                var appDir = AppContext.BaseDirectory;
                ConfigManager.SaveProfiles(appDir, _profiles);
                CmbProfiles.ItemsSource = null;
                CmbProfiles.ItemsSource = _profiles;
                CmbProfiles.SelectedItem = imported;
                MessageBox.Show($"Profile '{imported.Name}' successfully imported from clipboard!", "Import Success", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            else
            {
                MessageBox.Show("Invalid or unsupported link format. Must start with soar://profile or soar://locked", "Import Error", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
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

            MessageBox.Show(
                $"Connection link copied to clipboard!\n\nLink: {link}\n\nMode: {(isLocked ? "Privacy Protected (Locked)" : "Standard")}",
                "Profile Shared",
                MessageBoxButton.OK,
                MessageBoxImage.Information);
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
                _tunnelProcess.Exited += (s, e) => Dispatcher.Invoke(HandleProcessExited);

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
                        AppendLog($"[PROXY] Windows system proxy routed to {cfg.ListenIP}:{cfg.ListenPort}");
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

        private async Task StopTunnelAsync()
        {
            AppendLog("[STOP] Disconnecting tunnel...");

            if (ChkSystemProxy.IsChecked == true)
            {
                ProxyHelper.RestoreOriginalProxy();
                AppendLog("[PROXY] System proxy settings restored.");
            }

            if (_tunnelProcess != null && !_tunnelProcess.HasExited)
            {
                try
                {
                    _tunnelProcess.Kill(entireProcessTree: true);
                    await _tunnelProcess.WaitForExitAsync();
                }
                catch { }
                _tunnelProcess.Dispose();
                _tunnelProcess = null;
            }

            _isConnected = false;
            UpdateUIState(connected: false);
            AppendLog("[STATUS] Disconnected.");
        }

        private void HandleProcessOutput(string line)
        {
            if (line.StartsWith("[TELEMETRY]"))
            {
                // Parse machine readable telemetry: [TELEMETRY] tx=... rx=... tx_rate=... rx_rate=... streams=...
                try
                {
                    var parts = line.Substring("[TELEMETRY]".Length).Trim().Split(' ');
                    var dict = new Dictionary<string, string>();
                    foreach (var part in parts)
                    {
                        var kv = part.Split('=');
                        if (kv.Length == 2) dict[kv[0]] = kv[1];
                    }

                    if (dict.TryGetValue("tx_rate", out var txr)) TxtSpeedTx.Text = $"{txr} KB/s";
                    if (dict.TryGetValue("rx_rate", out var rxr)) TxtSpeedRx.Text = $"{rxr} KB/s";
                    if (dict.TryGetValue("streams", out var s) && dict.TryGetValue("tx", out var tx) && dict.TryGetValue("rx", out var rx))
                    {
                        TxtStats.Text = $"Streams: {s} | TX: {FormatBytes(ulong.Parse(tx))} | RX: {FormatBytes(ulong.Parse(rx))}";
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

                if (ChkSystemProxy.IsChecked == true)
                {
                    ProxyHelper.RestoreOriginalProxy();
                    AppendLog("[PROXY] Restored original system proxy.");
                }
            }
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
                TxtStats.Text = "Idle";
            }
        }

        #endregion

        #region Resolvers & Utilities

        private void BtnTestResolvers_Click(object sender, RoutedEventArgs e)
        {
            AppendLog("[RESOLVER] Testing candidate resolvers latency...");
            var filePath = Path.Combine(AppContext.BaseDirectory, TxtResolversFile.Text.Trim());
            if (!File.Exists(filePath))
            {
                AppendLog($"[RESOLVER] Resolvers file not found: {filePath}");
                return;
            }

            Task.Run(() =>
            {
                var lines = File.ReadAllLines(filePath)
                    .Select(l => l.Trim())
                    .Where(l => !string.IsNullOrEmpty(l) && !l.StartsWith("#") && !l.StartsWith(";"))
                    .Take(15).ToList();

                foreach (var r in lines)
                {
                    var sw = Stopwatch.StartNew();
                    try
                    {
                        var ep = r.Contains(':') ? r : r + ":53";
                        var host = ep.Split(':')[0];
                        var port = int.Parse(ep.Split(':')[1]);
                        using var client = new System.Net.Sockets.UdpClient();
                        client.Client.ReceiveTimeout = 1000;
                        var dummy = new byte[] { 0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01 };
                        client.Send(dummy, dummy.Length, host, port);
                        var remote = new System.Net.IPEndPoint(System.Net.IPAddress.Any, 0);
                        client.Receive(ref remote);
                        sw.Stop();
                        Dispatcher.Invoke(() => AppendLog($"[RESOLVER] {ep} -> {sw.ElapsedMilliseconds} ms (OK)"));
                    }
                    catch
                    {
                        Dispatcher.Invoke(() => AppendLog($"[RESOLVER] {r} -> Timeout / Blocked"));
                    }
                }
            });
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
                      "• Dynamic resolver probing\n" +
                      "• Privacy-preserving locked profile sharing\n" +
                      "• Windows System Proxy integration\n\n" +
                      "Repository: https://github.com/" + GitHubRepo;

            MessageBox.Show(msg, "About Soar DNS Tunnel", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void BtnCopyProxy_Click(object sender, RoutedEventArgs e)
        {
            var port = TxtListenPort.Text.Trim();
            var proxyUrl = $"socks5://127.0.0.1:{port}";
            Clipboard.SetText(proxyUrl);
            MessageBox.Show($"Proxy link copied to clipboard:\n{proxyUrl}", "Copied", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void BtnSaveConfig_Click(object sender, RoutedEventArgs e)
        {
            SaveCurrentProfileFromUI();
            MessageBox.Show("Profile successfully saved!", "Saved", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void BtnClearLogs_Click(object sender, RoutedEventArgs e)
        {
            TxtConsoleLog.Clear();
        }

        private void AppendLog(string message)
        {
            var ts = DateTime.Now.ToString("HH:mm:ss");
            TxtConsoleLog.AppendText($"[{ts}] {message}\n");
            TxtConsoleLog.ScrollToEnd();
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
}
