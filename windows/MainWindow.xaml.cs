using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;

namespace SoarClient
{
    public partial class MainWindow : Window
    {
        private Process? _tunnelProcess;
        private bool _isConnected = false;
        private string _configPath = "client_config.toml";

        public MainWindow()
        {
            InitializeComponent();
            Loaded += MainWindow_Loaded;
            Closing += MainWindow_Closing;
        }

        private void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            ProxyHelper.BackupCurrentProxy();
            LocateAndLoadConfig();
            AppendLog("[SYSTEM] Soar DNS Tunnel GUI initialized. Ready to connect.");
        }

        private void LocateAndLoadConfig()
        {
            var appDir = AppContext.BaseDirectory;
            var procDir = Path.GetDirectoryName(Environment.ProcessPath) ?? appDir;
            var currentDir = Directory.GetCurrentDirectory();

            var searchDirs = new[] { procDir, appDir, currentDir }.Distinct();
            var fileNames = new[] { "client_config.toml", "config.toml", "client_config.sample.toml" };

            var candidates = new List<string>();
            foreach (var dir in searchDirs)
            {
                foreach (var file in fileNames)
                {
                    candidates.Add(Path.Combine(dir, file));
                }
            }
            candidates.Add("client_config.toml");

            foreach (var path in candidates)
            {
                if (File.Exists(path))
                {
                    _configPath = path;
                    var cfg = ConfigManager.Load(path);
                    if (cfg.Domains.Any())
                        TxtDomains.Text = string.Join(", ", cfg.Domains);
                    if (!string.IsNullOrEmpty(cfg.ServerPublicKey))
                        TxtServerKey.Text = cfg.ServerPublicKey;
                    TxtListenIP.Text = cfg.ListenIP;
                    TxtListenPort.Text = cfg.ListenPort.ToString();
                    TxtResolversFile.Text = cfg.ResolversFile;
                    AppendLog($"[CONFIG] Loaded settings from: {path}");
                    return;
                }
            }

            AppendLog("[CONFIG] No existing config file found. Please enter your settings.");
        }

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
            var key = TxtServerKey.Text.Trim();
            var domains = TxtDomains.Text.Trim();
            var ip = TxtListenIP.Text.Trim();
            var portStr = TxtListenPort.Text.Trim();
            var resolvers = TxtResolversFile.Text.Trim();

            if (string.IsNullOrEmpty(key))
            {
                MessageBox.Show("Please enter the Server Public Key.", "Validation Error", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            if (string.IsNullOrEmpty(domains))
            {
                MessageBox.Show("Please enter at least one tunnel domain.", "Validation Error", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            if (!int.TryParse(portStr, out var port) || port <= 0 || port > 65535)
            {
                MessageBox.Show("Invalid port number.", "Validation Error", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            // Save config to file
            var cfg = new ClientConfigModel
            {
                ServerPublicKey = key,
                Domains = domains.Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries).Select(d => d.Trim()).ToList(),
                ListenIP = ip,
                ListenPort = port,
                ResolversFile = resolvers
            };
            var appDir = AppContext.BaseDirectory;
            var targetPath = Path.Combine(appDir, "client_config.toml");
            ConfigManager.Save(targetPath, cfg);
            _configPath = targetPath;

            // Locate soar-client binary
            var binCandidates = new[]
            {
                "soar-client.exe",
                Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "soar-client.exe"),
                Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "bin", "soar-client.exe"),
                Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "soar-client.exe")
            };

            var clientBin = binCandidates.FirstOrDefault(File.Exists);
            if (string.IsNullOrEmpty(clientBin))
            {
                MessageBox.Show("soar-client.exe not found! Please place soar-client.exe in the same folder as this application.", "File Not Found", MessageBoxButton.OK, MessageBoxImage.Error);
                return;
            }

            SetConnectingUI();
            AppendLog($"[START] Launching {Path.GetFileName(clientBin)} with {_configPath}...");

            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = clientBin,
                    Arguments = $"-config \"{_configPath}\"",
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    CreateNoWindow = true,
                    WorkingDirectory = AppDomain.CurrentDomain.BaseDirectory
                };

                _tunnelProcess = new Process { StartInfo = psi, EnableRaisingEvents = true };

                _tunnelProcess.OutputDataReceived += (s, e) =>
                {
                    if (!string.IsNullOrEmpty(e.Data))
                        Dispatcher.InvokeAsync(() => ProcessLogLine(e.Data));
                };

                _tunnelProcess.ErrorDataReceived += (s, e) =>
                {
                    if (!string.IsNullOrEmpty(e.Data))
                        Dispatcher.InvokeAsync(() => ProcessLogLine(e.Data));
                };

                _tunnelProcess.Exited += (s, e) =>
                {
                    Dispatcher.InvokeAsync(() =>
                    {
                        AppendLog($"[STOP] Tunnel process exited with code {_tunnelProcess?.ExitCode ?? 0}");
                        SetDisconnectedUI();
                    });
                };

                _tunnelProcess.Start();
                _tunnelProcess.BeginOutputReadLine();
                _tunnelProcess.BeginErrorReadLine();

                // Wait a moment for startup
                await Task.Delay(1000);
                if (_tunnelProcess != null && !_tunnelProcess.HasExited)
                {
                    SetConnectedUI(ip, port);
                }
            }
            catch (Exception ex)
            {
                AppendLog($"[ERROR] Failed to start tunnel process: {ex.Message}");
                SetDisconnectedUI();
            }
        }

        private void ProcessLogLine(string line)
        {
            AppendLog(line);

            if (line.Contains("[STATS]"))
            {
                var idx = line.IndexOf("[STATS]");
                TxtStats.Text = line.Substring(idx + 7).Trim();
            }
        }

        private async Task StopTunnelAsync()
        {
            AppendLog("[SHUTDOWN] Stopping tunnel...");
            if (ChkSystemProxy.IsChecked == true)
            {
                ProxyHelper.RestoreProxy();
                AppendLog("[PROXY] Windows system proxy restored.");
            }

            if (_tunnelProcess != null && !_tunnelProcess.HasExited)
            {
                try
                {
                    _tunnelProcess.Kill();
                    await _tunnelProcess.WaitForExitAsync();
                }
                catch { }
                finally
                {
                    _tunnelProcess.Dispose();
                    _tunnelProcess = null;
                }
            }

            SetDisconnectedUI();
        }

        private void SetConnectingUI()
        {
            StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0xF5, 0x9E, 0x0B)); // Amber
            StatusText.Text = "Connecting...";
            BtnConnect.IsEnabled = false;
        }

        private void SetConnectedUI(string ip, int port)
        {
            _isConnected = true;
            StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0x10, 0xB9, 0x81)); // Green
            StatusText.Text = "Connected";
            BtnConnect.Content = "DISCONNECT";
            BtnConnect.Background = new SolidColorBrush(Color.FromRgb(0xEF, 0x44, 0x44)); // Red
            BtnConnect.IsEnabled = true;

            if (ChkSystemProxy.IsChecked == true)
            {
                ProxyHelper.SetSocksProxy(ip, port);
                AppendLog($"[PROXY] Windows system proxy redirected to socks={ip}:{port}");
            }
        }

        private void SetDisconnectedUI()
        {
            _isConnected = false;
            StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0xEF, 0x44, 0x44)); // Red
            StatusText.Text = "Disconnected";
            BtnConnect.Content = "CONNECT";
            BtnConnect.Background = new SolidColorBrush(Color.FromRgb(0x02, 0x84, 0xC7)); // Blue
            BtnConnect.IsEnabled = true;
            TxtStats.Text = "Idle";
        }

        private void AppendLog(string message)
        {
            TxtConsoleLog.AppendText(message + Environment.NewLine);
            TxtConsoleLog.ScrollToEnd();
        }

        private void BtnClearLogs_Click(object sender, RoutedEventArgs e)
        {
            TxtConsoleLog.Clear();
        }

        private void BtnCopyProxy_Click(object sender, RoutedEventArgs e)
        {
            var port = TxtListenPort.Text.Trim();
            Clipboard.SetText($"socks5://127.0.0.1:{port}");
            MessageBox.Show($"Copied to clipboard: socks5://127.0.0.1:{port}", "Copied", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void BtnSaveConfig_Click(object sender, RoutedEventArgs e)
        {
            var appDir = AppContext.BaseDirectory;
            var targetPath = Path.Combine(appDir, "client_config.toml");
            var cfg = new ClientConfigModel
            {
                ServerPublicKey = TxtServerKey.Text.Trim(),
                Domains = TxtDomains.Text.Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries).Select(d => d.Trim()).ToList(),
                ListenIP = TxtListenIP.Text.Trim(),
                ListenPort = int.TryParse(TxtListenPort.Text.Trim(), out var p) ? p : 10850,
                ResolversFile = TxtResolversFile.Text.Trim()
            };
            ConfigManager.Save(targetPath, cfg);
            _configPath = targetPath;
            AppendLog($"[CONFIG] Saved configuration to: {targetPath}");
            MessageBox.Show("Configuration saved successfully!", "Saved", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        private void MainWindow_Closing(object? sender, System.ComponentModel.CancelEventArgs e)
        {
            if (_isConnected)
            {
                ProxyHelper.RestoreProxy();
                if (_tunnelProcess != null && !_tunnelProcess.HasExited)
                {
                    try { _tunnelProcess.Kill(); } catch { }
                }
            }
        }
    }
}
