#!/usr/bin/env bash
# ==============================================================================
# Soar DNS Tunnel - Automated Server Installer
# Supported OS: Ubuntu 20.04+, Debian 11+, CentOS 8+, AlmaLinux, RockyLinux
# ==============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}=====================================================${NC}"
echo -e "${CYAN}        Soar DNS Tunnel - Server Installer           ${NC}"
echo -e "${CYAN}=====================================================${NC}"

# 1. Root check
if [ "$EUID" -ne 0 ]; then
    echo -e "${RED}[ERROR] Please run this script as root (sudo bash install.sh)${NC}"
    exit 1
fi

# 2. Detect Architecture
ARCH_RAW=$(uname -m)
case "$ARCH_RAW" in
    x86_64|amd64)
        ARCH="amd64"
        ;;
    aarch64|arm64)
        ARCH="arm64"
        ;;
    *)
        echo -e "${RED}[ERROR] Unsupported architecture: $ARCH_RAW${NC}"
        exit 1
        ;;
esac
echo -e "${GREEN}[OK] Detected architecture: $ARCH ($ARCH_RAW)${NC}"

# 3. Detect Public IP
SERVER_IP=$(curl -s4 --max-time 5 https://api.ipify.org || curl -s4 --max-time 5 https://icanhazip.com || echo "YOUR_SERVER_IP")
echo -e "${GREEN}[OK] Detected Server IP: $SERVER_IP${NC}"

# 4. Check & Install Dependencies
echo -e "${BLUE}[INFO] Checking required utilities...${NC}"
if ! command -v curl &> /dev/null; then
    if command -v apt-get &> /dev/null; then
        apt-get update -y && apt-get install -y curl iptables
    elif command -v dnf &> /dev/null; then
        dnf install -y curl iptables
    elif command -v yum &> /dev/null; then
        yum install -y curl iptables
    fi
fi

# 5. Download or Copy Binary
INSTALL_DIR="/usr/local/bin"
BIN_PATH="$INSTALL_DIR/soar-server"
CONFIG_DIR="/etc/soar"
KEY_PATH="$CONFIG_DIR/server.key"

mkdir -p "$CONFIG_DIR"
mkdir -p "$INSTALL_DIR"

# Check if running from cloned repository
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_BIN="$SCRIPT_DIR/../soar-server"

if [ -f "$LOCAL_BIN" ]; then
    echo -e "${BLUE}[INFO] Found local binary at $LOCAL_BIN, installing...${NC}"
    cp -f "$LOCAL_BIN" "$BIN_PATH"
else
    # Default GitHub repository release URL
    REPO="mahdigholamipak/soar-tunnel"
    DOWNLOAD_URL="https://github.com/${REPO}/releases/latest/download/soar-server-linux-${ARCH}"
    echo -e "${BLUE}[INFO] Fetching latest release binary from GitHub (${DOWNLOAD_URL})...${NC}"
    if ! curl -fsSL -o "$BIN_PATH" "$DOWNLOAD_URL"; then
        echo -e "${YELLOW}[WARN] Could not download from GitHub release. Looking for local build...${NC}"
        if command -v go &> /dev/null; then
            echo -e "${BLUE}[INFO] Compiling soar-server from source with local Go...${NC}"
            cd "$SCRIPT_DIR/.."
            go build -trimpath -ldflags="-s -w" -o "$BIN_PATH" ./cmd/soar-server
        else
            echo -e "${RED}[ERROR] Failed to download pre-built binary and Go compiler is not installed.${NC}"
            echo -e "${YELLOW}Please upload the soar-server binary to $BIN_PATH manually.${NC}"
            exit 1
        fi
    fi
fi

chmod +x "$BIN_PATH"
echo -e "${GREEN}[OK] Installed soar-server to $BIN_PATH${NC}"

# 6. Generate Server Keypair if not present
if [ ! -f "$KEY_PATH" ]; then
    echo -e "${BLUE}[INFO] Generating new Ed25519 keypair for Soar...${NC}"
    KEYGEN_OUTPUT=$("$BIN_PATH" keygen)
    PRIV_KEY=$(echo "$KEYGEN_OUTPUT" | grep "private" | awk '{print $2}')
    PUB_KEY=$(echo "$KEYGEN_OUTPUT" | grep "public" | awk '{print $2}')
    echo "$PRIV_KEY" > "$KEY_PATH"
    chmod 600 "$KEY_PATH"
    echo -e "${GREEN}[OK] Private key saved to $KEY_PATH${NC}"
else
    echo -e "${BLUE}[INFO] Existing key found at $KEY_PATH${NC}"
    PUB_KEY=$("$BIN_PATH" pubkey "$KEY_PATH")
fi

# 7. Ask for Tunnel Domains (Zones)
if [ -n "$1" ]; then
    ZONES="$1"
else
    echo ""
    echo -e "${YELLOW}Enter your tunnel delegated domains/zones.${NC}"
    echo -e "You can provide one or multiple zones separated by commas (e.g. s.he7.ir,s.he6.ir):"
    read -r -p "Domains: " ZONES
    while [ -z "$ZONES" ]; do
        echo -e "${RED}Domains cannot be empty!${NC}"
        read -r -p "Domains: " ZONES
    done
fi

# 8. Check Port 53 UDP Availability
LISTEN_PORT=53
PORT_CONFLICT=false

if ss -lupn | grep -q ":53 "; then
    PORT_CONFLICT=true
    CONFLICT_PROC=$(ss -lupn | grep ":53 " | head -n 1)
    echo -e "${YELLOW}[WARN] UDP port 53 is already used by another process:${NC}"
    echo -e "       $CONFLICT_PROC"
    echo -e "${BLUE}[INFO] Configuring Soar to listen on port 5303 and setting up iptables UDP:53 redirection...${NC}"
    LISTEN_PORT=5303

    # Setup iptables redirect
    iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5303 2>/dev/null || true
    iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5303
fi

# Open firewall if ufw is active
if command -v ufw &> /dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 53/udp >/dev/null 2>&1 || true
    ufw allow 5303/udp >/dev/null 2>&1 || true
fi

# 9. Create Systemd Service
SERVICE_FILE="/etc/systemd/system/soar.service"
cat <<EOF > "$SERVICE_FILE"
[Unit]
Description=Soar High-Performance DNS Tunnel Server
After=network.target

[Service]
Type=simple
User=root
ExecStart=$BIN_PATH -zones "$ZONES" -key-file "$KEY_PATH" -listen ":$LISTEN_PORT"
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable soar
systemctl restart soar

sleep 1
if systemctl is-active --quiet soar; then
    echo -e "${GREEN}[OK] Soar DNS Tunnel Service is active and running!${NC}"
else
    echo -e "${RED}[ERROR] Soar failed to start. Check logs with: journalctl -u soar -e${NC}"
    exit 1
fi

# 10. Summary & Cloudflare Setup Instructions
FIRST_ZONE=$(echo "$ZONES" | cut -d',' -f1 | tr -d ' ')
DOMAIN_ROOT=$(echo "$FIRST_ZONE" | awk -F. '{print $(NF-1)"."$NF}')

echo ""
echo -e "${CYAN}=====================================================${NC}"
echo -e "${GREEN}      Soar Server Successfully Installed!            ${NC}"
echo -e "${CYAN}=====================================================${NC}"
echo ""
echo -e "${YELLOW}1. Your Server Details:${NC}"
echo -e "   - Public IP:       ${GREEN}$SERVER_IP${NC}"
echo -e "   - Tunnel Zones:    ${GREEN}$ZONES${NC}"
echo -e "   - Server PubKey:   ${GREEN}$PUB_KEY${NC}"
echo -e "   - Listen UDP Port: ${GREEN}:$LISTEN_PORT${NC}"
echo ""
echo -e "${YELLOW}2. Cloudflare DNS Setup (CRITICAL STEP):${NC}"
echo -e "   In your Cloudflare dashboard for ${CYAN}$DOMAIN_ROOT${NC}, add these 2 DNS records:"
echo ""
echo -e "   Record 1 (Glue A Record):"
echo -e "     - Type:    ${GREEN}A${NC}"
echo -e "     - Name:    ${GREEN}ns.${FIRST_ZONE#*.} (or ns)${NC}"
echo -e "     - Target:  ${GREEN}$SERVER_IP${NC}"
echo -e "     - Proxy:   ${RED}DNS only (Grey Cloud - OFF)${NC}"
echo ""
echo -e "   Record 2 (Delegation NS Record):"
echo -e "     - Type:    ${GREEN}NS${NC}"
echo -e "     - Name:    ${GREEN}${FIRST_ZONE%%.*}${NC} (e.g. 's' for s.$DOMAIN_ROOT)"
echo -e "     - Target:  ${GREEN}ns.$DOMAIN_ROOT${NC}"
echo ""
echo -e "${YELLOW}3. Client Configuration Snippet (for client_config.toml):${NC}"
echo -e "-----------------------------------------------------"
echo -e "DOMAINS = [\"$(echo "$ZONES" | sed 's/,/", "/g')\"]"
echo -e "SERVER_PUBLIC_KEY = \"$PUB_KEY\""
echo -e "LISTEN_IP = \"127.0.0.1\""
echo -e "LISTEN_PORT = 10850"
echo -e "RESOLVERS_FILE = \"client_resolvers.txt\""
echo -e "STREAM_IDLE_TIMEOUT_SECONDS = 60.0"
echo -e "-----------------------------------------------------"
echo ""
echo -e "${GREEN}Done! You can manage the service anytime with:${NC}"
echo -e "  systemctl status soar"
echo -e "  journalctl -u soar -f"
echo -e "${CYAN}=====================================================${NC}"
