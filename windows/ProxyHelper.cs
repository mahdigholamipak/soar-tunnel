using System;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace SoarClient
{
    public static class ProxyHelper
    {
        private const int INTERNET_OPTION_SETTINGS_CHANGED = 39;
        private const int INTERNET_OPTION_REFRESH = 37;

        [DllImport("wininet.dll", SetLastError = true)]
        private static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);

        private const string RegistryKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";

        private static bool _originalProxyEnable = false;
        private static string _originalProxyServer = "";

        public static void BackupCurrentProxy()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RegistryKeyPath, false);
                if (key != null)
                {
                    _originalProxyEnable = (int)(key.GetValue("ProxyEnable") ?? 0) == 1;
                    _originalProxyServer = (string)(key.GetValue("ProxyServer") ?? "");
                }
            }
            catch { }
        }

        public static bool SetSocksProxy(string ip, int port)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RegistryKeyPath, true);
                if (key == null) return false;

                key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
                key.SetValue("ProxyServer", $"socks={ip}:{port}", RegistryValueKind.String);

                RefreshSettings();
                return true;
            }
            catch
            {
                return false;
            }
        }

        public static bool RestoreProxy()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RegistryKeyPath, true);
                if (key == null) return false;

                if (_originalProxyEnable)
                {
                    key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
                    key.SetValue("ProxyServer", _originalProxyServer, RegistryValueKind.String);
                }
                else
                {
                    key.SetValue("ProxyEnable", 0, RegistryValueKind.DWord);
                    key.SetValue("ProxyServer", "", RegistryValueKind.String);
                }

                RefreshSettings();
                return true;
            }
            catch
            {
                return false;
            }
        }

        private static void RefreshSettings()
        {
            InternetSetOption(IntPtr.Zero, INTERNET_OPTION_SETTINGS_CHANGED, IntPtr.Zero, 0);
            InternetSetOption(IntPtr.Zero, INTERNET_OPTION_REFRESH, IntPtr.Zero, 0);
        }
    }
}
