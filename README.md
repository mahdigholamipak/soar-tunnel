# Soar DNS Tunnel — High-Performance Loss-Tolerant DNS Tunnel

[![CI](https://github.com/mahdigholamipak/soar-tunnel/actions/workflows/ci.yml/badge.svg)](https://github.com/mahdigholamipak/soar-tunnel/actions/workflows/ci.yml)
[![Release](https://github.com/mahdigholamipak/soar-tunnel/actions/workflows/release.yml/badge.svg)](https://github.com/mahdigholamipak/soar-tunnel/actions/workflows/release.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

An open-source, production-ready DNS tunneling ecosystem built upon the modern, loss-tolerant **Soar** protocol. Engineered specifically for severely restricted, filtered, or censorship-heavy environments (such as Iran, Russia, and China) where traditional VPN/TLS protocols are actively blocked or throttled.

---

## 🌟 Key Features

- **Blazing Fast Performance**: Zero-RTT connection initiation, loss-tolerant selective retransmissions, and out-of-order segment assembly.
- **Multi-Domain Failover & Racing**: Configurable multi-zone pool (`DOMAINS = ["s1.domain.com", "s2.domain.com"]`). Automatically switches domains if one is blocked or throttled by an ISP.
- **Stream Idle Watchdog**: Eliminates memory/stream leaks by actively closing idle or abandoned streams after an inactivity timeout (default 60s).
- **Curated High-Performance Resolvers**: Pre-bundled with verified Anycast and domestic WhiteDNS resolvers that bypass ISP rate limits without choking.
- **Native Windows GUI Client**: Clean, modern dark-themed desktop app with real-time streaming telemetry and one-click **Windows System Proxy** integration.
- **Android Proxy-Only App**: Lightweight Android app running as a background Foreground Service, exposing a local SOCKS5 proxy (`127.0.0.1:10850`) for seamless use with Telegram, v2rayNG, and browsers.
- **Automated Server Installer**: Single-line bash script that installs, configures systemd, generates cryptographic keys, and prints Cloudflare DNS records.
- **100% Automated CI/CD**: All platform builds (Linux, Windows GUI, Android APK) are compiled and published directly inside GitHub Actions.

---

## 🚀 0 to 100 Setup Guide

### Phase 1: Cloudflare DNS Setup (CRITICAL)

DNS tunneling works by delegating a subdomain to your VPS nameserver. You need **one registered domain** added to [Cloudflare](https://dash.cloudflare.com).

Assuming your domain is `example.com` and your VPS IP is `198.51.100.1`:

| Type | Name | Content / Target | Proxy Status | Description |
| :--- | :--- | :--- | :--- | :--- |
| **A** | `ns` | `198.51.100.1` | **DNS Only (Grey Cloud - OFF)** | Glue record pointing to your VPS IP |
| **NS** | `s` | `ns.example.com` | **DNS Only (Auto)** | Delegates `s.example.com` to your VPS |

> [!IMPORTANT]
> The `A` record (`ns`) MUST have proxying **turned OFF** (Grey cloud icon). Cloudflare cannot proxy arbitrary UDP port 53 traffic.
> If you have multiple domains, you can repeat this setup for each domain (e.g., `s.example1.com`, `s.example2.net`).

---

### Phase 2: One-Line Server Installation

SSH into your Linux VPS (Ubuntu 20.04+, Debian 11+, or CentOS/AlmaLinux) as `root`:

```bash
curl -sSL https://raw.githubusercontent.com/mahdigholamipak/soar-tunnel/main/scripts/install.sh | bash
```

The script will:
1. Detect system architecture (`amd64` / `arm64`).
2. Download and verify the `soar-server` binary.
3. Automatically generate a unique Ed25519 cryptographic keypair.
4. Prompt for your delegated domains (e.g. `s.example.com` or `s.domain1.com,s.domain2.com`).
5. Configure and launch the `systemd` background service (`soar.service`).
6. Print your **Server Public Key** and the client configuration snippet.

#### Useful Server Commands:
```bash
# Check service status
systemctl status soar

# View live tunnel logs
journalctl -u soar -f

# Print your Server Public Key again
soar-server pubkey /etc/soar/server.key
```

---

### Phase 3: Client Setup

#### 🖥️ Option A: Windows (GUI App)
1. Download `Soar-Windows-GUI-win64.zip` from the [Latest Release](https://github.com/mahdigholamipak/soar-tunnel/releases/latest).
2. Extract the ZIP file into a folder.
3. Launch `Soar-Windows-GUI.exe`.
4. Enter your **Server Public Key** and **Tunnel Domains** (e.g. `s.example.com`).
5. Click **CONNECT**.
   - Check **"Set as Windows System Proxy"** if you want your entire browser and Windows apps to immediately tunnel through Soar.
   - Monitor the **Live Console Logs** on the right side of the window.

#### 📱 Option B: Android (Proxy-Only App)
1. Download `Soar-Android-ProxyOnly.apk` from the [Latest Release](https://github.com/mahdigholamipak/soar-tunnel/releases/latest) and install it on your phone.
2. Open the app, enter your **Server Public Key** and **Tunnel Domains**.
3. Tap **CONNECT**. A foreground notification will appear indicating the tunnel is running.
4. Configure your apps to use the local SOCKS5 proxy:
   - **Telegram**: Tap **"Set in Telegram"** or go to `Settings -> Data & Storage -> Proxy Settings -> Add SOCKS5`:
     - Server: `127.0.0.1`
     - Port: `10850`
   - **v2rayNG / Matsuri / Nekobox / Browsers**: Set SOCKS5 outbound to `127.0.0.1:10850`.

#### 💻 Option C: CLI (Cross-Platform)
Run the client via terminal on Windows, Linux, or macOS:

```bash
./soar-client -config client_config.toml
```

Or using direct flags:
```bash
./soar-client -zones "s.example1.com,s.example2.com" -pubkey "YOUR_KEY=" -listen 127.0.0.1:10850
```

---

## ⚙️ Configuration Reference (`client_config.toml`)

```toml
# Tunnel domains (supports multiple for automatic failover)
DOMAINS = ["s.example1.com", "s.example2.com"]

# Server Ed25519 Public Key
SERVER_PUBLIC_KEY = "YOUR_BASE64_PUBLIC_KEY_HERE="

# Local SOCKS5 listener
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = 10850

# Path to resolvers list
RESOLVERS_FILE = "client_resolvers.txt"

# Inactive stream timeout in seconds (prevents leaking hanging connections)
STREAM_IDLE_TIMEOUT_SECONDS = 60.0

# Timeout per dial attempt before trying next domain
DIAL_TIMEOUT_SECONDS = 12.0

# Logging verbosity: "INFO", "DEBUG", "WARN", "ERROR"
LOG_LEVEL = "INFO"
```

---

## 🛠️ Multi-Domain Failover Explained

Vanilla Soar tunnels bind a session to a single domain name. However, in heavily filtered regions, national firewalls frequently throttle or block individual subdomains after high data transfers.

Soar Tunnel features an intelligent **Multi-Domain Dial Engine**:
1. When you configure multiple domains, the client monitors the health of each zone.
2. If connection handshakes or packets stall on `s.domain1.com`, the client automatically routes through `s.domain2.com` within milliseconds without disconnecting the user.
3. Cooldown periods ensure failed domains are retried only after a recovery window.

---

## 🔒 Security Model

- **End-to-End Cryptography**: Communication is authenticated and encrypted end-to-end using Ed25519 and ChaCha20-Poly1305.
- **Censorship Evasion**: Payloads are packed into valid DNS query labels (base32) and responses (TXT/AAAA records).
- **Public Only Policy**: The server default configuration blocks connections to non-public/private IP addresses (RFC 1918) preventing unauthorized LAN access.

---

#### Server Management & One-Line Updates:
Run the `soar` command anytime on your VPS to open the management dashboard:
```bash
soar              # Open interactive server management menu
soar status       # Check service status, active IP, and domains
soar share        # Display standard connection link & terminal QR code
soar share-locked # Generate privacy-protected locked share link (hides domains/keys)
soar update       # Single-command update to latest GitHub release
soar logs         # Follow live server traffic logs
```

To update the server directly in one line from anywhere:
```bash
curl -sSL https://raw.githubusercontent.com/mahdigholamipak/soar-tunnel/main/scripts/install.sh | bash -s -- --update
```

---

### 🔀 Co-existence with CottenRouter (Running Multiple DNS Tunnels)

If you use [CottenRouter](https://github.com/TaJirax/CottenRouter) as a DNS reverse proxy on port 53 to host multiple DNS tunneling services concurrently:

1. **Dynamic Port Discovery (Zero Conflict with StormDNS & Others):**
   The installer automatically detects CottenRouter, reserves well-known ports (5301 CottenDNS, 5302 SlipGate, 5303 StormDNS), and dynamically binds Soar to the first available `53xx` port (e.g. `127.0.0.1:5304`) without adding iptables port 53 redirects:
   ```bash
   curl -sSL https://raw.githubusercontent.com/mahdigholamipak/soar-tunnel/main/scripts/install.sh | bash -s -- --cotten
   ```
2. **Add Route to CottenRouter:**
   The installer and `soar cotten` output the exact configuration rule matching the dynamically allocated port. Add it to `/etc/cottenrouter/config.json` (or via `cottenrouter tui`):
   ```json
   {
     "suffix": "s.example.com",
     "address": "127.0.0.1:5304"
   }
   ```
   Then reload CottenRouter: `systemctl restart cottenrouter`.
3. **Clients unchanged:** Windows and Android clients continue to communicate via standard DNS ports without needing any custom port configuration.

---

## 🙏 Credits & Attribution

This project is built directly upon the core **Soar** loss-tolerant DNS tunneling protocol invented and open-sourced by **Lantern** ([github.com/getlantern/soar](https://github.com/getlantern/soar)) under the Apache License 2.0. We deeply appreciate the Lantern engineering team for their groundbreaking research in censorship evasion and loss-tolerant transport over DNS.

This ecosystem extends the foundational protocol with:
- Multi-domain failover and concurrent racing
- Dynamic resolver latency probing and ranking
- Windows GUI client with System Tray & Live Telemetry
- Android SOCKS5 proxy client with in-app updates and persistent signing
- Privacy-preserving locked configuration sharing (`soar://locked`)

---

## 📄 License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
