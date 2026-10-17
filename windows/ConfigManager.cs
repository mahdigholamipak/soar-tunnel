using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace SoarClient
{
    public class ClientConfigModel
    {
        public List<string> Domains { get; set; } = new();
        public string ServerPublicKey { get; set; } = "";
        public string ListenIP { get; set; } = "127.0.0.1";
        public int ListenPort { get; set; } = 10850;
        public string ResolversFile { get; set; } = "client_resolvers.txt";
        public double StreamIdleTimeoutSeconds { get; set; } = 60.0;
        public string LogLevel { get; set; } = "INFO";
    }

    public static class ConfigManager
    {
        public static ClientConfigModel Load(string path)
        {
            var config = new ClientConfigModel();
            if (!File.Exists(path)) return config;

            try
            {
                var lines = File.ReadAllLines(path);
                for (int i = 0; i < lines.Length; i++)
                {
                    var rawLine = lines[i];
                    var line = rawLine.Trim();
                    if (string.IsNullOrWhiteSpace(line) || line.StartsWith("#") || line.StartsWith(";"))
                        continue;

                    var parts = line.Split('=', 2);
                    if (parts.Length != 2) continue;

                    var key = parts[0].Trim().ToUpperInvariant();
                    var val = parts[1].Trim();
                    if (val.Contains('#'))
                    {
                        val = val.Substring(0, val.IndexOf('#')).Trim();
                    }

                    // Handle multiline array (e.g. DOMAINS = [\n "domain1",\n "domain2"\n])
                    if (val.StartsWith("[") && !val.Contains("]"))
                    {
                        var arrayBuilder = new StringBuilder(val);
                        while (++i < lines.Length)
                        {
                            var nextLine = lines[i].Trim();
                            if (nextLine.Contains('#'))
                                nextLine = nextLine.Substring(0, nextLine.IndexOf('#')).Trim();
                            arrayBuilder.Append(" ").Append(nextLine);
                            if (nextLine.Contains("]")) break;
                        }
                        val = arrayBuilder.ToString();
                    }

                    switch (key)
                    {
                        case "DOMAINS":
                            config.Domains = ParseArray(val);
                            break;
                        case "DOMAIN":
                        case "ZONE":
                            var single = Clean(val);
                            if (!string.IsNullOrEmpty(single))
                                config.Domains = new List<string> { single };
                            break;
                        case "SERVER_PUBLIC_KEY":
                        case "ENCRYPTION_KEY":
                        case "PUBKEY":
                            config.ServerPublicKey = Clean(val);
                            break;
                        case "LISTEN_IP":
                            config.ListenIP = Clean(val);
                            break;
                        case "LISTEN_PORT":
                            if (int.TryParse(Clean(val), out var port) && port > 0)
                                config.ListenPort = port;
                            break;
                        case "RESOLVERS_FILE":
                            config.ResolversFile = Clean(val);
                            break;
                        case "STREAM_IDLE_TIMEOUT_SECONDS":
                            if (double.TryParse(Clean(val), out var timeout) && timeout > 0)
                                config.StreamIdleTimeoutSeconds = timeout;
                            break;
                        case "LOG_LEVEL":
                            config.LogLevel = Clean(val);
                            break;
                    }
                }
            }
            catch { }

            return config;
        }

        public static void Save(string path, ClientConfigModel config)
        {
            try
            {
                var sb = new StringBuilder();
                sb.AppendLine("# Soar DNS Tunnel Client Configuration");
                sb.AppendLine();
                sb.AppendLine("# Tunnel Domains (comma-separated list)");
                var domainsJoined = string.Join("\", \"", config.Domains.Select(d => d.Trim()));
                sb.AppendLine($"DOMAINS = [\"{domainsJoined}\"]");
                sb.AppendLine();
                sb.AppendLine("# Server Ed25519 Public Key");
                sb.AppendLine($"SERVER_PUBLIC_KEY = \"{config.ServerPublicKey.Trim()}\"");
                sb.AppendLine();
                sb.AppendLine("# SOCKS5 Proxy Listener");
                sb.AppendLine($"LISTEN_IP = \"{config.ListenIP.Trim()}\"");
                sb.AppendLine($"LISTEN_PORT = {config.ListenPort}");
                sb.AppendLine();
                sb.AppendLine("# Resolvers File");
                sb.AppendLine($"RESOLVERS_FILE = \"{config.ResolversFile.Trim()}\"");
                sb.AppendLine();
                sb.AppendLine("# Reliability Timeouts");
                sb.AppendLine($"STREAM_IDLE_TIMEOUT_SECONDS = {config.StreamIdleTimeoutSeconds:0.0}");
                sb.AppendLine();
                sb.AppendLine($"LOG_LEVEL = \"{config.LogLevel.Trim()}\"");

                File.WriteAllText(path, sb.ToString(), Encoding.UTF8);
            }
            catch { }
        }

        private static string Clean(string s)
        {
            return s.Trim().Trim('"', '\'', '[', ']').Trim();
        }

        private static List<string> ParseArray(string s)
        {
            var res = new List<string>();
            s = s.Trim();
            if (s.StartsWith("[") && s.EndsWith("]"))
                s = s.Substring(1, s.Length - 2);

            var items = s.Split(new[] { ',', '\n', '\r' }, StringSplitOptions.RemoveEmptyEntries);
            foreach (var item in items)
            {
                var c = Clean(item);
                if (!string.IsNullOrEmpty(c)) res.Add(c);
            }
            return res;
        }
    }
}
