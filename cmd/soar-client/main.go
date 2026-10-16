package main

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/getlantern/soar"
)

var (
	totalBytesTx  atomic.Uint64
	totalBytesRx  atomic.Uint64
	activeStreams atomic.Int64
	totalStreams  atomic.Uint64
)

func logMsg(level, format string, args ...any) {
	ts := time.Now().Format("2006-01-02 15:04:05")
	msg := fmt.Sprintf(format, args...)
	fmt.Fprintf(os.Stderr, "[%s] [%s] %s\n", ts, level, msg)
}

type clientEntry struct {
	zone     string
	client   *soar.Client
	failures atomic.Int32
	lastFail time.Time
}

type MultiClient struct {
	mu          sync.RWMutex
	clients     []*clientEntry
	activeIdx   int
	dialTimeout time.Duration
	logger      *slog.Logger
}

func NewMultiClient(zones []string, serverKey ed25519.PublicKey, resolvers []string, country string, dialTimeout time.Duration, logger *slog.Logger) (*MultiClient, error) {
	if len(zones) == 0 {
		return nil, errors.New("at least one tunnel domain/zone is required")
	}

	var entries []*clientEntry
	for _, z := range zones {
		z = strings.TrimSpace(z)
		if z == "" {
			continue
		}
		cfg := soar.Config{
			Zone:      z,
			ServerKey: serverKey,
			Resolvers: resolvers,
			Discovery: soar.Discovery{
				Enabled:    true,
				Country:    country,
				Candidates: resolvers,
			},
			Logger: logger,
		}
		c, err := soar.NewClient(cfg)
		if err != nil {
			logMsg("WARN", "Failed to initialize domain %s: %v", z, err)
			continue
		}
		entries = append(entries, &clientEntry{
			zone:   z,
			client: c,
		})
	}

	if len(entries) == 0 {
		return nil, errors.New("failed to initialize any tunnel domain")
	}

	return &MultiClient{
		clients:     entries,
		activeIdx:   0,
		dialTimeout: dialTimeout,
		logger:      logger,
	}, nil
}

func (m *MultiClient) Close() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, e := range m.clients {
		e.client.Close()
	}
}

func (m *MultiClient) Dial(ctx context.Context, network, addr string) (net.Conn, string, error) {
	m.mu.RLock()
	count := len(m.clients)
	startIdx := m.activeIdx
	m.mu.RUnlock()

	var lastErr error
	for i := 0; i < count; i++ {
		idx := (startIdx + i) % count
		entry := m.clients[idx]

		// Skip temporarily if recent consecutive failures and more domains are available
		if count > 1 && entry.failures.Load() >= 3 && time.Since(entry.lastFail) < 30*time.Second {
			continue
		}

		dialCtx, cancel := context.WithTimeout(ctx, m.dialTimeout)
		conn, err := entry.client.DialContext(dialCtx, network, addr)
		cancel()

		if err == nil {
			entry.failures.Store(0)
			if idx != startIdx {
				m.mu.Lock()
				m.activeIdx = idx
				m.mu.Unlock()
				logMsg("INFO", "Failover: switched active domain to %s", entry.zone)
			}
			return conn, entry.zone, nil
		}

		lastErr = err
		entry.failures.Add(1)
		entry.lastFail = time.Now()
		if count > 1 {
			logMsg("WARN", "Domain %s dial error (%v), trying fallback...", entry.zone, err)
		}
	}

	return nil, "", fmt.Errorf("all domains failed to dial %s: %w", addr, lastErr)
}

func main() {
	configPath := flag.String("config", "", "path to client configuration TOML file")
	flag.StringVar(configPath, "c", "", "alias for -config")
	zonesFlag := flag.String("zones", "", "tunnel domains (comma-separated, e.g. s1.domain.com,s2.domain.com)")
	zoneFlag := flag.String("zone", "", "single tunnel domain (legacy)")
	pubKeyB64 := flag.String("pubkey", "", "server Ed25519 public key (base64)")
	flag.StringVar(pubKeyB64, "k", "", "alias for -pubkey")
	resolversFile := flag.String("resolvers", "", "file with candidate DNS resolvers")
	flag.StringVar(resolversFile, "r", "", "alias for -resolvers")
	listen := flag.String("listen", "", "local address for SOCKS5 proxy (e.g. 127.0.0.1:10850)")
	country := flag.String("country", "", "country code for built-in discovery (e.g. IR)")
	idleSecs := flag.Float64("idle-timeout", 0, "idle stream timeout in seconds (default 60)")
	verbose := flag.Bool("v", false, "debug logging")
	flag.Parse()

	// 1. Locate config file
	cfg := DefaultConfig()
	configFileToLoad := *configPath
	if configFileToLoad == "" {
		candidates := []string{"client_config.toml", "config.toml"}
		// Also check in executable directory
		if exe, err := os.Executable(); err == nil {
			exeDir := filepath.Dir(exe)
			candidates = append(candidates, filepath.Join(exeDir, "client_config.toml"), filepath.Join(exeDir, "config.toml"))
		}
		for _, c := range candidates {
			if _, err := os.Stat(c); err == nil {
				configFileToLoad = c
				break
			}
		}
	}

	if configFileToLoad != "" {
		if loaded, err := LoadConfigFile(configFileToLoad); err == nil {
			logMsg("INFO", "Loaded configuration from %s", configFileToLoad)
			cfg = loaded
		} else {
			logMsg("WARN", "Could not load configuration file %s: %v", configFileToLoad, err)
		}
	}

	// 2. Command line overrides
	if *zonesFlag != "" {
		cfg.Domains = parseStringArray(*zonesFlag)
	} else if *zoneFlag != "" {
		cfg.Domains = []string{cleanString(*zoneFlag)}
	}
	if *pubKeyB64 != "" {
		cfg.ServerPublicKey = cleanString(*pubKeyB64)
	}
	if *resolversFile != "" {
		cfg.ResolversFile = cleanString(*resolversFile)
	}
	if *listen != "" {
		if host, portStr, err := net.SplitHostPort(*listen); err == nil {
			cfg.ListenIP = host
			if p, err := strconv.Atoi(portStr); err == nil {
				cfg.ListenPort = p
			}
		}
	}
	if *country != "" {
		cfg.Country = cleanString(*country)
	}
	if *idleSecs > 0 {
		cfg.StreamIdleTimeoutSeconds = *idleSecs
	}
	if *verbose {
		cfg.LogLevel = "DEBUG"
	}

	// Validation
	if len(cfg.Domains) == 0 {
		logMsg("ERROR", "No tunnel domains configured. Specify via config file or -zones/-zone flag.")
		os.Exit(1)
	}
	if cfg.ServerPublicKey == "" {
		logMsg("ERROR", "No server public key configured. Specify via config file or -pubkey flag.")
		os.Exit(1)
	}

	keyBytes, err := base64.StdEncoding.DecodeString(cfg.ServerPublicKey)
	if err != nil || len(keyBytes) != ed25519.PublicKeySize {
		logMsg("ERROR", "Invalid server public key: must be 32 bytes base64 (got %d bytes)", len(keyBytes))
		os.Exit(1)
	}

	// 3. Load resolvers
	resolvers := loadResolvers(cfg.ResolversFile)
	if len(resolvers) == 0 {
		logMsg("WARN", "No resolvers loaded from %s; falling back to default resolvers (1.1.1.1:53, 8.8.8.8:53)", cfg.ResolversFile)
		resolvers = []string{"1.1.1.1:53", "1.0.0.1:53", "8.8.8.8:53", "8.8.4.4:53", "9.9.9.9:53"}
	}

	level := slog.LevelInfo
	if cfg.LogLevel == "DEBUG" {
		level = slog.LevelDebug
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{Level: level}))

	// 4. Initialize Multi-Domain Tunnel Client
	listenAddr := fmt.Sprintf("%s:%d", cfg.ListenIP, cfg.ListenPort)
	logMsg("INFO", "Initializing Soar DNS Tunnel...")
	logMsg("INFO", "Configured domains (%d): %s", len(cfg.Domains), strings.Join(cfg.Domains, ", "))
	logMsg("INFO", "Loaded resolvers: %d candidate IPs", len(resolvers))

	dialTimeout := time.Duration(cfg.DialTimeoutSeconds * float64(time.Second))
	if dialTimeout <= 0 {
		dialTimeout = 15 * time.Second
	}

	multiClient, err := NewMultiClient(cfg.Domains, ed25519.PublicKey(keyBytes), resolvers, cfg.Country, dialTimeout, logger)
	if err != nil {
		logMsg("ERROR", "Tunnel client init failed: %v", err)
		os.Exit(1)
	}
	defer multiClient.Close()

	// 5. Start SOCKS5 Listener
	listener, err := net.Listen("tcp", listenAddr)
	if err != nil {
		logMsg("ERROR", "Failed to bind SOCKS5 listener on %s: %v", listenAddr, err)
		os.Exit(1)
	}
	defer listener.Close()

	logMsg("INFO", "=====================================================")
	logMsg("INFO", "  Soar DNS Tunnel Active & Ready")
	logMsg("INFO", "  SOCKS5 Proxy:  socks5://%s", listenAddr)
	logMsg("INFO", "  Idle Timeout:  %.1fs per inactive stream", cfg.StreamIdleTimeoutSeconds)
	logMsg("INFO", "=====================================================")

	// Stats reporter
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			active := activeStreams.Load()
			tx := totalBytesTx.Load()
			rx := totalBytesRx.Load()
			total := totalStreams.Load()
			if active > 0 || total > 0 {
				logMsg("STATS", "Active: %d | Total Conns: %d | TX: %s | RX: %s",
					active, total, formatBytes(tx), formatBytes(rx))
			}
		}
	}()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		<-ctx.Done()
		logMsg("INFO", "Shutting down listener...")
		listener.Close()
	}()

	idleTimeout := time.Duration(cfg.StreamIdleTimeoutSeconds * float64(time.Second))
	if idleTimeout <= 0 {
		idleTimeout = 60 * time.Second
	}

	for {
		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				break
			}
			continue
		}
		go handleSocks5(ctx, conn, multiClient, idleTimeout)
	}

	logMsg("INFO", "Soar client stopped cleanly.")
}

func loadResolvers(filename string) []string {
	var candidates []string
	if filename != "" {
		candidates = append(candidates, filename)
		if exe, err := os.Executable(); err == nil {
			candidates = append(candidates, filepath.Join(filepath.Dir(exe), filename))
		}
	}
	candidates = append(candidates, "client_resolvers.txt", "resolvers.txt")

	for _, p := range candidates {
		if f, err := os.Open(p); err == nil {
			defer f.Close()
			var list []string
			sc := bufio.NewScanner(f)
			for sc.Scan() {
				line := strings.TrimSpace(sc.Text())
				if line != "" && !strings.HasPrefix(line, "#") && !strings.HasPrefix(line, ";") {
					// ensure port
					if !strings.Contains(line, ":") {
						line = line + ":53"
					}
					list = append(list, line)
				}
			}
			if len(list) > 0 {
				return list
			}
		}
	}
	return nil
}

func handleSocks5(ctx context.Context, clientConn net.Conn, multiClient *MultiClient, idleTimeout time.Duration) {
	defer clientConn.Close()
	clientConn.SetDeadline(time.Now().Add(10 * time.Second))

	// 1. SOCKS5 Auth Handshake
	var buf [256]byte
	if _, err := io.ReadFull(clientConn, buf[:2]); err != nil {
		return
	}
	if buf[0] != 0x05 {
		return
	}
	nmethods := int(buf[1])
	methods := make([]byte, nmethods)
	if _, err := io.ReadFull(clientConn, methods); err != nil {
		return
	}
	// Reply: No authentication required (0x00)
	if _, err := clientConn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// 2. SOCKS5 Request
	if _, err := io.ReadFull(clientConn, buf[:4]); err != nil {
		return
	}
	if buf[0] != 0x05 || buf[1] != 0x01 { // Only TCP CONNECT is handled
		clientConn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Command not supported
		return
	}

	atyp := buf[3]
	var targetHost string
	switch atyp {
	case 0x01: // IPv4
		if _, err := io.ReadFull(clientConn, buf[:4]); err != nil {
			return
		}
		targetHost = net.IP(buf[:4]).String()
	case 0x03: // Domain name
		if _, err := io.ReadFull(clientConn, buf[:1]); err != nil {
			return
		}
		dLen := int(buf[0])
		if _, err := io.ReadFull(clientConn, buf[:dLen]); err != nil {
			return
		}
		targetHost = string(buf[:dLen])
	case 0x04: // IPv6
		if _, err := io.ReadFull(clientConn, buf[:16]); err != nil {
			return
		}
		targetHost = net.IP(buf[:16]).String()
	default:
		clientConn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Address type not supported
		return
	}

	if _, err := io.ReadFull(clientConn, buf[:2]); err != nil {
		return
	}
	targetPort := binary.BigEndian.Uint16(buf[:2])
	targetAddr := net.JoinHostPort(targetHost, strconv.Itoa(int(targetPort)))

	// 3. Dial through DNS Tunnel
	startTime := time.Now()
	targetConn, zoneUsed, err := multiClient.Dial(ctx, "tcp", targetAddr)
	if err != nil {
		logMsg("WARN", "Dial failed for %s: %v", targetAddr, err)
		clientConn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Host unreachable
		return
	}
	defer targetConn.Close()

	rtt := time.Since(startTime).Milliseconds()
	logMsg("TUNNEL", "Connected to %s via [%s] in %dms", targetAddr, zoneUsed, rtt)

	// SOCKS5 success reply
	if _, err := clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	// Remove handshake deadlines
	clientConn.SetDeadline(time.Time{})

	// 4. Bidirectional Relay with Stream Idle Timeout
	relayStreams(clientConn, targetConn, idleTimeout)
}

// relayStreams proxies data between client and tunneled connection with an active idle monitor.
// If neither side reads or writes data for idleTimeout, both connections are cleanly closed.
func relayStreams(c1, c2 net.Conn, idleTimeout time.Duration) {
	activeStreams.Add(1)
	totalStreams.Add(1)
	defer activeStreams.Add(-1)

	var lastActivity atomic.Int64
	lastActivity.Store(time.Now().UnixNano())

	stopMonitor := make(chan struct{})
	var closeOnce sync.Once
	closeBoth := func() {
		closeOnce.Do(func() {
			close(stopMonitor)
			c1.Close()
			c2.Close()
		})
	}

	// Idle watchdog timer
	go func() {
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-stopMonitor:
				return
			case <-ticker.C:
				last := time.Unix(0, lastActivity.Load())
				if time.Since(last) > idleTimeout {
					closeBoth()
					return
				}
			}
		}
	}()

	errCh := make(chan error, 2)

	// c1 -> c2 (Upload)
	go func() {
		buf := make([]byte, 32*1024)
		for {
			nr, err := c1.Read(buf)
			if nr > 0 {
				lastActivity.Store(time.Now().UnixNano())
				totalBytesTx.Add(uint64(nr))
				nw, ew := c2.Write(buf[:nr])
				if ew != nil {
					errCh <- ew
					break
				}
				if nw != nr {
					errCh <- io.ErrShortWrite
					break
				}
			}
			if err != nil {
				errCh <- err
				break
			}
		}
		closeBoth()
	}()

	// c2 -> c1 (Download)
	go func() {
		buf := make([]byte, 32*1024)
		for {
			nr, err := c2.Read(buf)
			if nr > 0 {
				lastActivity.Store(time.Now().UnixNano())
				totalBytesRx.Add(uint64(nr))
				nw, ew := c1.Write(buf[:nr])
				if ew != nil {
					errCh <- ew
					break
				}
				if nw != nr {
					errCh <- io.ErrShortWrite
					break
				}
			}
			if err != nil {
				errCh <- err
				break
			}
		}
		closeBoth()
	}()

	// Wait for at least one side to complete
	<-errCh
	closeBoth()
}

func formatBytes(b uint64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := uint64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(b)/float64(div), "KMGTPE"[exp])
}
