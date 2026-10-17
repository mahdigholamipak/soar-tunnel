#!/usr/bin/env bash
# ==============================================================================
# Soar DNS Tunnel - Automated Server Installer & Management Tool
# Based on the foundational Soar protocol by Lantern (https://github.com/getlantern/soar)
# Licensed under Apache License 2.0
# Supported OS: Ubuntu 20.04+, Debian 11+, CentOS 8+, AlmaLinux, RockyLinux
# ==============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

REPO="mahdigholamipak/soar-tunnel"
INSTALL_DIR="/usr/local/bin"
BIN_PATH="$INSTALL_DIR/soar-server"
CONFIG_DIR="/etc/soar"
KEY_PATH="$CONFIG_DIR/server.key"
CONF_PATH="$CONFIG_DIR/server.conf"
CLI_PATH="$INSTALL_DIR/soar"

# ------------------------------------------------------------------------------
# Helper: Detect Architecture
# ------------------------------------------------------------------------------
detect_arch() {
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
}

# ------------------------------------------------------------------------------
# Helper: Find First Available 53xx Port (avoiding StormDNS/CottenDNS/SlipGate)
# ------------------------------------------------------------------------------
find_free_53xx_port() {
    local is_cotten="${1:-false}"
    local cotten_conf="/etc/cottenrouter/config.json"
    local candidate=""
    
    # Scan ports 5301-5399
    # In CottenRouter environments, standard assignments are:
    # 5301 = CottenDNS
    # 5302 = SlipGate
    # 5303 = StormDNS
    for port in $(seq 5301 5399); do
        # 1. Proactively avoid well-known standard ports (5301-5303) in CottenRouter environments
        if [ "$is_cotten" = "true" ] && [ "$port" -ge 5301 ] && [ "$port" -le 5303 ]; then
            continue
        fi

        # 2. Check if port is in active listening state (UDP or TCP)
        if ss -lun -ltn 2>/dev/null | grep -qE "[:.]${port}[[:space:]]"; then
            continue
        fi
        
        # 3. Check if port is already configured inside CottenRouter config file
        if [ -f "$cotten_conf" ] && grep -qE ":${port}[^0-9]" "$cotten_conf" 2>/dev/null; then
            continue
        fi
        if [ -d "/etc/cottenrouter" ] && grep -rqE ":${port}[^0-9]" /etc/cottenrouter/ 2>/dev/null; then
            continue
        fi

        candidate="$port"
        break
    done
    
    # Fallback to any free port in 5301-5399 if standard candidate wasn't set
    if [ -z "$candidate" ]; then
        for port in $(seq 5301 5399); do
            if ! ss -lun -ltn 2>/dev/null | grep -qE "[:.]${port}[[:space:]]"; then
                candidate="$port"
                break
            fi
        done
    fi

    echo "${candidate:-5353}"
}

# ------------------------------------------------------------------------------
# Helper: One-Line Auto Update
# ------------------------------------------------------------------------------
do_update() {
    echo -e "${CYAN}=====================================================${NC}"
    echo -e "${CYAN}      Checking for Soar Server Updates...            ${NC}"
    echo -e "${CYAN}=====================================================${NC}"
    detect_arch
    
    echo -e "${BLUE}[INFO] Querying latest release from GitHub...${NC}"
    LATEST_JSON=$(curl -s "https://api.github.com/repos/${REPO}/releases/latest")
    LATEST_TAG=$(echo "$LATEST_JSON" | grep '"tag_name":' | head -n 1 | cut -d'"' -f4)
    
    if [ -z "$LATEST_TAG" ]; then
        echo -e "${RED}[ERROR] Could not determine latest version from GitHub.${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}[INFO] Latest available release: ${LATEST_TAG}${NC}"
    DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${LATEST_TAG}/soar-server-linux-${ARCH}"
    
    TMP_BIN="/tmp/soar-server-new"
    echo -e "${BLUE}[INFO] Downloading ${DOWNLOAD_URL}...${NC}"
    if curl -fL --progress-bar -o "$TMP_BIN" "$DOWNLOAD_URL"; then
        chmod +x "$TMP_BIN"
        systemctl stop soar 2>/dev/null || true
        mv -f "$TMP_BIN" "$BIN_PATH"
        systemctl daemon-reload
        systemctl restart soar
        echo -e "${GREEN}[OK] Soar Server successfully updated to ${LATEST_TAG} and restarted!${NC}"
    else
        echo -e "${RED}[ERROR] Failed to download update binary.${NC}"
        rm -f "$TMP_BIN"
        exit 1
    fi
    exit 0
}

# Check if called with --update or update
if [ "$1" = "--update" ] || [ "$1" = "update" ]; then
    do_update
fi

# 1. Root check
if [ "$EUID" -ne 0 ]; then
    echo -e "${RED}[ERROR] Please run this script as root (sudo bash install.sh)${NC}"
    exit 1
fi

echo -e "${CYAN}=====================================================${NC}"
echo -e "${CYAN}        Soar DNS Tunnel - Server Installer           ${NC}"
echo -e "${CYAN}   Based on Lantern Soar Protocol (Apache 2.0)       ${NC}"
echo -e "${CYAN}=====================================================${NC}"

# 2. Detect Architecture
detect_arch
echo -e "${GREEN}[OK] Detected architecture: $ARCH ($ARCH_RAW)${NC}"

# 3. Detect Public IP
SERVER_IP=$(curl -s4 --max-time 5 https://api.ipify.org || curl -s4 --max-time 5 https://icanhazip.com || echo "YOUR_SERVER_IP")
echo -e "${GREEN}[OK] Detected Server IP: $SERVER_IP${NC}"

# 4. Check & Install Dependencies
echo -e "${BLUE}[INFO] Checking required utilities...${NC}"
PKG_CMD=""
if command -v apt-get &> /dev/null; then
    PKG_CMD="apt-get"
    apt-get update -y >/dev/null 2>&1
    apt-get install -y curl iptables qrencode jq >/dev/null 2>&1 || true
elif command -v dnf &> /dev/null; then
    PKG_CMD="dnf"
    dnf install -y curl iptables qrencode jq >/dev/null 2>&1 || true
elif command -v yum &> /dev/null; then
    PKG_CMD="yum"
    yum install -y curl iptables qrencode jq >/dev/null 2>&1 || true
fi

# 5. Download Binary
mkdir -p "$CONFIG_DIR"
mkdir -p "$INSTALL_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_BIN="$SCRIPT_DIR/../soar-server"

if [ -f "$LOCAL_BIN" ]; then
    echo -e "${BLUE}[INFO] Found local binary at $LOCAL_BIN, installing...${NC}"
    cp -f "$LOCAL_BIN" "$BIN_PATH"
else
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

# 8. Check Port 53 UDP Availability & CottenRouter Co-existence
LISTEN_PORT=53
LISTEN_ADDR=":53"
BEHIND_COTTEN=false

# Auto-detect CottenRouter
if pgrep -x "cottenrouter" >/dev/null 2>&1 || systemctl is-active --quiet cottenrouter 2>/dev/null || [ -d "/etc/cottenrouter" ]; then
    echo -e "${MAGENTA}[DETECT] CottenRouter DNS reverse proxy detected on this system!${NC}"
    BEHIND_COTTEN=true
fi

if echo "$*" | grep -q -- "--cotten"; then
    BEHIND_COTTEN=true
fi

if ss -lupn | grep -q ":53 "; then
    CONFLICT_PROC=$(ss -lupn | grep ":53 " | head -n 1)
    echo -e "${YELLOW}[WARN] UDP port 53 is already in use:${NC}"
    echo -e "       $CONFLICT_PROC"
    
    if [ "$BEHIND_COTTEN" = true ] || echo "$CONFLICT_PROC" | grep -qi "cottenrouter"; then
        BEHIND_COTTEN=true
    else
        echo -e "${YELLOW}Is port 53 used by CottenRouter or another DNS reverse proxy?${NC}"
        read -r -p "Run in reverse-proxy co-existence mode (loopback without iptables hijacking)? [y/N]: " PROXY_CHOICE
        if [[ "$PROXY_CHOICE" =~ ^[Yy]$ ]]; then
            BEHIND_COTTEN=true
        fi
    fi

    if [ "$BEHIND_COTTEN" = true ]; then
        DETECTED_PORT=$(find_free_53xx_port "true")
        echo -e "${GREEN}[INFO] CottenRouter co-existence active!${NC}"
        echo -e "${BLUE}[INFO] Scanning for first available 53xx port (avoiding 5301 CottenDNS, 5302 SlipGate, 5303 StormDNS)...${NC}"
        echo -e "${GREEN}[OK] Dynamically selected free port: ${DETECTED_PORT}${NC}"
        if [ -t 0 ]; then
            read -r -p "Press Enter to use port $DETECTED_PORT, or enter custom port [$DETECTED_PORT]: " CUSTOM_PORT
            LISTEN_PORT="${CUSTOM_PORT:-$DETECTED_PORT}"
        else
            LISTEN_PORT="$DETECTED_PORT"
        fi
        LISTEN_ADDR="127.0.0.1:$LISTEN_PORT"
        echo -e "${GREEN}[INFO] Binding Soar to loopback ${LISTEN_ADDR} (preserving port 53 for CottenRouter).${NC}"
        # Clean up any previous conflicting iptables redirect
        iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports "$LISTEN_PORT" 2>/dev/null || true
    else
        DETECTED_PORT=$(find_free_53xx_port "false")
        echo -e "${BLUE}[INFO] Dynamically selecting free 53xx port for iptables UDP:53 redirection: ${DETECTED_PORT}${NC}"
        LISTEN_PORT="$DETECTED_PORT"
        LISTEN_ADDR=":$LISTEN_PORT"
        iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports "$LISTEN_PORT" 2>/dev/null || true
        iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-ports "$LISTEN_PORT"
    fi
elif [ "$BEHIND_COTTEN" = true ]; then
    DETECTED_PORT=$(find_free_53xx_port "true")
    echo -e "${GREEN}[OK] CottenRouter mode specified.${NC}"
    echo -e "${BLUE}[INFO] Scanning for first available 53xx port (avoiding 5301 CottenDNS, 5302 SlipGate, 5303 StormDNS)...${NC}"
    echo -e "${GREEN}[OK] Dynamically selected free port: ${DETECTED_PORT}${NC}"
    if [ -t 0 ]; then
        read -r -p "Press Enter to use port $DETECTED_PORT, or enter custom port [$DETECTED_PORT]: " CUSTOM_PORT
        LISTEN_PORT="${CUSTOM_PORT:-$DETECTED_PORT}"
    else
        LISTEN_PORT="$DETECTED_PORT"
    fi
    LISTEN_ADDR="127.0.0.1:$LISTEN_PORT"
    echo -e "${GREEN}[OK] Binding Soar to loopback ${LISTEN_ADDR}.${NC}"
fi

# Save config info
cat <<EOF > "$CONF_PATH"
SERVER_IP="$SERVER_IP"
ZONES="$ZONES"
PUB_KEY="$PUB_KEY"
LISTEN_ADDR="$LISTEN_ADDR"
BEHIND_COTTEN="$BEHIND_COTTEN"
EOF
chmod 600 "$CONF_PATH"

# Open firewall if ufw is active
if command -v ufw &> /dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 53/udp >/dev/null 2>&1 || true
    if [ "$BEHIND_COTTEN" != "true" ] && [ "$LISTEN_PORT" != "53" ]; then
        ufw allow "${LISTEN_PORT}/udp" >/dev/null 2>&1 || true
    fi
fi

# 9. Create Systemd Service
SERVICE_FILE="/etc/systemd/system/soar.service"
cat <<EOF > "$SERVICE_FILE"
[Unit]
Description=Soar High-Performance DNS Tunnel Server (Lantern Protocol)
After=network.target

[Service]
Type=simple
User=root
ExecStart=$BIN_PATH -zones "$ZONES" -key-file "$KEY_PATH" -listen "$LISTEN_ADDR"
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

# 10. Install Management CLI (/usr/local/bin/soar)
cat << 'EOFSCRIPT' > "$CLI_PATH"
#!/usr/bin/env bash
# Soar DNS Tunnel Server Management CLI
set -e

CONFIG_DIR="/etc/soar"
CONF_PATH="$CONFIG_DIR/server.conf"
KEY_PATH="$CONFIG_DIR/server.key"
BIN_PATH="/usr/local/bin/soar-server"
REPO="mahdigholamipak/soar-tunnel"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'

if [ -f "$CONF_PATH" ]; then
    source "$CONF_PATH"
fi

if [ -f "$KEY_PATH" ] && [ -x "$BIN_PATH" ]; then
    PUB_KEY=$("$BIN_PATH" pubkey "$KEY_PATH" 2>/dev/null || echo "$PUB_KEY")
fi

build_standard_link() {
    local name="SoarServer"
    local encoded_zones=$(echo "$ZONES" | tr -d ' ' | sed 's/,/%2C/g')
    local encoded_key=$(echo "$PUB_KEY" | tr -d '\n\r' | sed 's/+/%2B/g; s/\//%2F/g; s/=/%3D/g')
    echo "soar://profile?name=${name}&domains=${encoded_zones}&key=${encoded_key}&port=10850"
}

build_locked_payload() {
    # Generate encrypted/locked base64 payload
    local json="{\"name\":\"Protected Server\",\"domains\":[\"$(echo "$ZONES" | sed 's/,/\",\"/g')\"],\"key\":\"$PUB_KEY\",\"port\":10850,\"locked\":true}"
    local b64=$(echo -n "$json" | base64 | tr -d '\n\r')
    echo "soar://locked?name=Protected+Server&data=${b64}"
}

print_qr() {
    local text="$1"
    if command -v qrencode &>/dev/null; then
        qrencode -t ANSI256 "$text" || qrencode -t UTF8 "$text"
    else
        echo -e "${YELLOW}(Install 'qrencode' to see a terminal QR code: apt install qrencode)${NC}"
    fi
}

show_status() {
    echo -e "${CYAN}=====================================================${NC}"
    echo -e "${CYAN}             Soar Server Status                      ${NC}"
    echo -e "${CYAN}=====================================================${NC}"
    if systemctl is-active --quiet soar; then
        echo -e "Service Status:     ${GREEN}ACTIVE (Running)${NC}"
    else
        echo -e "Service Status:     ${RED}INACTIVE (Stopped)${NC}"
    fi
    echo -e "Server Public IP:   ${GREEN}${SERVER_IP:-Unknown}${NC}"
    echo -e "Tunnel Domains:     ${GREEN}${ZONES:-None}${NC}"
    echo -e "Public Key:         ${GREEN}${PUB_KEY:-Unknown}${NC}"
    echo -e "Listen Address:     ${GREEN}${LISTEN_ADDR:-:$LISTEN_PORT}${NC}"
    if [ "$BEHIND_COTTEN" = "true" ]; then
        echo -e "CottenRouter Mode:  ${MAGENTA}ACTIVE (Loopback ${LISTEN_ADDR:-127.0.0.1:5304})${NC}"
    fi
    echo -e "Systemd Service:    soar.service"
    echo -e "${CYAN}-----------------------------------------------------${NC}"
}

show_cotten() {
    local target_addr="${LISTEN_ADDR:-127.0.0.1:5304}"
    echo -e "${CYAN}=====================================================${NC}"
    echo -e "${MAGENTA}       CottenRouter Configuration Snippet            ${NC}"
    echo -e "${CYAN}=====================================================${NC}"
    echo -e "Add this routing rule to your CottenRouter config file"
    echo -e "(usually /etc/cottenrouter/config.json) or enter via 'cottenrouter tui':"
    echo ""
    for zone in $(echo "$ZONES" | tr ',' ' '); do
        echo -e "  {"
        echo -e "    \"suffix\": \"${zone}\","
        echo -e "    \"address\": \"${target_addr}\""
        echo -e "  },"
    done
    echo ""
    echo -e "After updating config.json, restart CottenRouter:"
    echo -e "${GREEN}systemctl restart cottenrouter${NC}"
    echo -e "${CYAN}=====================================================${NC}"
}

show_share() {
    local link=$(build_standard_link)
    echo -e "${CYAN}=====================================================${NC}"
    echo -e "${GREEN}          Client Connection Link (Standard)          ${NC}"
    echo -e "${CYAN}=====================================================${NC}"
    echo -e "Direct Link (click or copy into client):"
    echo -e "${YELLOW}$link${NC}"
    echo ""
    echo -e "Terminal QR Code:"
    print_qr "$link"
    echo ""
    echo -e "Manual TOML config snippet:"
    echo -e "DOMAINS = [\"$(echo "$ZONES" | sed 's/,/", "/g')\"]"
    echo -e "SERVER_PUBLIC_KEY = \"$PUB_KEY\""
    echo -e "LISTEN_IP = \"127.0.0.1\""
    echo -e "LISTEN_PORT = 10850"
    echo -e "${CYAN}=====================================================${NC}"
}

show_locked_share() {
    local locked_link=$(build_locked_payload)
    echo -e "${CYAN}=====================================================${NC}"
    echo -e "${MAGENTA}     Privacy-Protected / Locked Share Link           ${NC}"
    echo -e "${CYAN}=====================================================${NC}"
    echo -e "${YELLOW}Notice: This link hides your domain and key in the client UI.${NC}"
    echo -e "${YELLOW}The recipient can connect and re-share without seeing raw server details.${NC}"
    echo ""
    echo -e "Locked Share Link:"
    echo -e "${CYAN}$locked_link${NC}"
    echo ""
    echo -e "Terminal QR Code:"
    print_qr "$locked_link"
    echo -e "${CYAN}=====================================================${NC}"
}

update_server() {
    echo -e "${CYAN}Updating Soar Server from GitHub Releases...${NC}"
    ARCH_RAW=$(uname -m)
    case "$ARCH_RAW" in
        x86_64|amd64) ARCH="amd64" ;;
        aarch64|arm64) ARCH="arm64" ;;
        *) echo -e "${RED}Unsupported architecture: $ARCH_RAW${NC}"; exit 1 ;;
    esac
    
    LATEST_JSON=$(curl -s "https://api.github.com/repos/${REPO}/releases/latest")
    LATEST_TAG=$(echo "$LATEST_JSON" | grep '"tag_name":' | head -n 1 | cut -d'"' -f4)
    
    if [ -z "$LATEST_TAG" ]; then
        echo -e "${RED}Could not fetch latest release tag.${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}Found latest release: $LATEST_TAG${NC}"
    DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${LATEST_TAG}/soar-server-linux-${ARCH}"
    TMP_BIN="/tmp/soar-server-update"
    
    if curl -fL --progress-bar -o "$TMP_BIN" "$DOWNLOAD_URL"; then
        chmod +x "$TMP_BIN"
        systemctl stop soar 2>/dev/null || true
        mv -f "$TMP_BIN" "$BIN_PATH"
        systemctl restart soar
        echo -e "${GREEN}Soar server updated to $LATEST_TAG successfully!${NC}"
    else
        echo -e "${RED}Download failed.${NC}"
        rm -f "$TMP_BIN"
        exit 1
    fi
}

case "$1" in
    status)
        show_status
        ;;
    restart)
        systemctl restart soar
        echo -e "${GREEN}Soar server restarted.${NC}"
        ;;
    start)
        systemctl start soar
        echo -e "${GREEN}Soar server started.${NC}"
        ;;
    stop)
        systemctl stop soar
        echo -e "${YELLOW}Soar server stopped.${NC}"
        ;;
    logs)
        journalctl -u soar -f
        ;;
    share)
        show_share
        ;;
    share-locked|locked)
        show_locked_share
        ;;
    cotten)
        show_cotten
        ;;
    qr)
        print_qr "$(build_standard_link)"
        ;;
    update)
        update_server
        ;;
    *)
        # Interactive Menu
        clear
        echo -e "${CYAN}=====================================================${NC}"
        echo -e "${CYAN}      Soar DNS Tunnel - Server Management            ${NC}"
        echo -e "${CYAN}   Based on Lantern Soar Protocol (Apache 2.0)       ${NC}"
        echo -e "${CYAN}=====================================================${NC}"
        echo -e "1) Service Status & Information"
        echo -e "2) View Live Logs"
        echo -e "3) Restart Soar Service"
        echo -e "4) Stop Soar Service"
        echo -e "5) Start Soar Service"
        echo -e "6) Show Standard Connection Link & QR"
        echo -e "7) Show Privacy-Protected Locked Link (Hidden Domain)"
        echo -e "8) Update Server to Latest GitHub Release"
        echo -e "9) Show CottenRouter Configuration Snippet"
        echo -e "0) Exit"
        echo -e "${CYAN}=====================================================${NC}"
        read -r -p "Select option [0-9]: " OPT
        case "$OPT" in
            1) show_status ;;
            2) journalctl -u soar -f ;;
            3) systemctl restart soar ; echo -e "${GREEN}Restarted.${NC}" ;;
            4) systemctl stop soar ; echo -e "${YELLOW}Stopped.${NC}" ;;
            5) systemctl start soar ; echo -e "${GREEN}Started.${NC}" ;;
            6) show_share ;;
            7) show_locked_share ;;
            8) update_server ;;
            9) show_cotten ;;
            0) exit 0 ;;
            *) echo -e "${RED}Invalid option.${NC}" ;;
        esac
        ;;
esac
EOFSCRIPT

chmod +x "$CLI_PATH"
echo -e "${GREEN}[OK] Installed Soar CLI tool: run 'soar' anytime to manage your server!${NC}"

# 11. Final Summary
FIRST_ZONE=$(echo "$ZONES" | cut -d',' -f1 | tr -d ' ')
DOMAIN_ROOT=$(echo "$FIRST_ZONE" | awk -F. '{print $(NF-1)"."$NF}')

echo ""
echo -e "${CYAN}=====================================================${NC}"
echo -e "${GREEN}      Soar Server Successfully Installed!            ${NC}"
echo -e "${CYAN}=====================================================${NC}"
echo -e "${YELLOW}Server Details:${NC}"
echo -e "   - Public IP:       ${GREEN}$SERVER_IP${NC}"
echo -e "   - Tunnel Zones:    ${GREEN}$ZONES${NC}"
echo -e "   - Server PubKey:   ${GREEN}$PUB_KEY${NC}"
echo -e "   - Listen Address:  ${GREEN}$LISTEN_ADDR${NC}"
if [ "$BEHIND_COTTEN" = true ]; then
    echo -e "   - Routing Mode:    ${MAGENTA}CottenRouter Co-existence (${LISTEN_ADDR})${NC}"
    echo ""
    echo -e "${MAGENTA}>>> CottenRouter Setup Action Required <<<${NC}"
    echo -e "Add the following rule to ${YELLOW}/etc/cottenrouter/config.json${NC} (or via ${YELLOW}cottenrouter tui${NC}):"
    echo ""
    for zone in $(echo "$ZONES" | tr ',' ' '); do
        echo -e "  {"
        echo -e "    \"suffix\": \"${zone}\","
        echo -e "    \"address\": \"${LISTEN_ADDR}\""
        echo -e "  },"
    done
    echo ""
    echo -e "Then restart CottenRouter: ${GREEN}systemctl restart cottenrouter${NC}"
fi
echo ""
echo -e "${YELLOW}Quick Management:${NC}"
echo -e "   - Open Control Menu:     ${GREEN}soar${NC}"
echo -e "   - Show Client Link & QR: ${GREEN}soar share${NC}"
echo -e "   - Privacy-Protected Link:${GREEN}soar share-locked${NC}"
echo -e "   - CottenRouter Snippet:  ${GREEN}soar cotten${NC}"
echo -e "   - One-Line Auto Update:  ${GREEN}soar update${NC}"
echo ""
echo -e "${CYAN}=====================================================${NC}"
