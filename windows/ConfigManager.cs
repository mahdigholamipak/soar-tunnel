using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

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

    public class ServerProfile
    {
        [JsonPropertyName("id")]
        public string Id { get; set; } = Guid.NewGuid().ToString("N");

        [JsonPropertyName("name")]
        public string Name { get; set; } = "Default Server";

        [JsonPropertyName("domains")]
        public List<string> Domains { get; set; } = new();

        [JsonPropertyName("serverPublicKey")]
        public string ServerPublicKey { get; set; } = "";

        [JsonPropertyName("listenPort")]
        public int ListenPort { get; set; } = 10850;

        [JsonPropertyName("resolversFile")]
        public string ResolversFile { get; set; } = "client_resolvers.txt";

        [JsonPropertyName("isLocked")]
        public bool IsLocked { get; set; } = false;

        public override string ToString() => IsLocked ? $"🔒 {Name} (Protected)" : Name;
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

        #region Profiles Management

        public static List<ServerProfile> LoadProfiles(string baseDir)
        {
            var path = Path.Combine(baseDir, "profiles.json");
            if (File.Exists(path))
            {
                try
                {
                    var json = File.ReadAllText(path, Encoding.UTF8);
                    var list = JsonSerializer.Deserialize<List<ServerProfile>>(json);
                    if (list != null && list.Count > 0) return list;
                }
                catch { }
            }

            // Fallback: migrate from client_config.toml if present
            var tomlPath = Path.Combine(baseDir, "client_config.toml");
            var cfg = Load(tomlPath);
            var initial = new ServerProfile
            {
                Name = "Default Server",
                Domains = cfg.Domains,
                ServerPublicKey = cfg.ServerPublicKey,
                ListenPort = cfg.ListenPort,
                ResolversFile = cfg.ResolversFile
            };
            return new List<ServerProfile> { initial };
        }

        public static void SaveProfiles(string baseDir, List<ServerProfile> profiles)
        {
            try
            {
                var path = Path.Combine(baseDir, "profiles.json");
                var json = JsonSerializer.Serialize(profiles, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(path, json, Encoding.UTF8);
            }
            catch { }
        }

        public static string ExportProfileUri(ServerProfile profile, bool locked)
        {
            var nameEscaped = Uri.EscapeDataString(profile.Name);
            if (locked)
            {
                var payload = new
                {
                    name = profile.Name,
                    domains = profile.Domains,
                    key = profile.ServerPublicKey,
                    port = profile.ListenPort,
                    locked = true
                };
                var json = JsonSerializer.Serialize(payload);
                var b64 = Convert.ToBase64String(Encoding.UTF8.GetBytes(json));
                return $"soar://locked?name={nameEscaped}&data={Uri.EscapeDataString(b64)}";
            }
            else
            {
                var domainsJoined = Uri.EscapeDataString(string.Join(",", profile.Domains));
                var keyEscaped = Uri.EscapeDataString(profile.ServerPublicKey);
                return $"soar://profile?name={nameEscaped}&domains={domainsJoined}&key={keyEscaped}&port={profile.ListenPort}";
            }
        }

        public static ServerProfile? ImportProfileUri(string uriString)
        {
            if (string.IsNullOrWhiteSpace(uriString)) return null;
            uriString = uriString.Trim();

            if (uriString.StartsWith("soar://profile?", StringComparison.OrdinalIgnoreCase))
            {
                var query = uriString.Substring("soar://profile?".Length);
                var pairs = ParseQueryString(query);
                var name = pairs.GetValueOrDefault("name", "Imported Server");
                var domainsStr = pairs.GetValueOrDefault("domains", "");
                var key = pairs.GetValueOrDefault("key", "");
                var portStr = pairs.GetValueOrDefault("port", "10850");
                int.TryParse(portStr, out var port);
                if (port <= 0) port = 10850;

                var domains = domainsStr.Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries)
                                        .Select(d => d.Trim()).ToList();

                return new ServerProfile
                {
                    Name = name,
                    Domains = domains,
                    ServerPublicKey = key,
                    ListenPort = port,
                    IsLocked = false
                };
            }
            else if (uriString.StartsWith("soar://locked?", StringComparison.OrdinalIgnoreCase))
            {
                var query = uriString.Substring("soar://locked?".Length);
                var pairs = ParseQueryString(query);
                var name = pairs.GetValueOrDefault("name", "Protected Server");
                var data = pairs.GetValueOrDefault("data", "");
                if (string.IsNullOrEmpty(data)) return null;

                try
                {
                    var jsonBytes = Convert.FromBase64String(data);
                    var json = Encoding.UTF8.GetString(jsonBytes);
                    using var doc = JsonDocument.Parse(json);
                    var root = doc.RootElement;

                    var profileName = root.TryGetProperty("name", out var n) ? n.GetString() ?? name : name;
                    var key = root.TryGetProperty("key", out var k) ? k.GetString() ?? "" : "";
                    var port = root.TryGetProperty("port", out var p) ? p.GetInt32() : 10850;
                    var domainsList = new List<string>();
                    if (root.TryGetProperty("domains", out var d) && d.ValueKind == JsonValueKind.Array)
                    {
                        foreach (var elem in d.EnumerateArray())
                        {
                            var s = elem.GetString();
                            if (!string.IsNullOrEmpty(s)) domainsList.Add(s);
                        }
                    }

                    return new ServerProfile
                    {
                        Name = profileName,
                        Domains = domainsList,
                        ServerPublicKey = key,
                        ListenPort = port,
                        IsLocked = true
                    };
                }
                catch { }
            }

            return null;
        }

        private static Dictionary<string, string> ParseQueryString(string query)
        {
            var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            var pairs = query.Split('&', StringSplitOptions.RemoveEmptyEntries);
            foreach (var pair in pairs)
            {
                var parts = pair.Split('=', 2);
                if (parts.Length == 2)
                {
                    var k = Uri.UnescapeDataString(parts[0]);
                    var v = Uri.UnescapeDataString(parts[1]);
                    dict[k] = v;
                }
            }
            return dict;
        }

        #endregion

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
