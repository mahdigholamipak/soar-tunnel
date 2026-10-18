// Command soar-server is the authoritative name server for a soar tunnel zone.
//
//	soar-server keygen                      # prints a private and public key
//	soar-server pubkey key                  # prints the public key for a private key file
//	soar-server -zone t.example.com -key-file key -listen :53
package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/getlantern/soar"
)

func main() {
	if len(os.Args) > 2 && os.Args[1] == "pubkey" {
		raw, err := os.ReadFile(os.Args[2])
		if err != nil {
			fatal(err)
		}
		seed, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
		if err != nil || len(seed) != ed25519.SeedSize {
			fatal(fmt.Errorf("key file must hold a base64 %d-byte seed", ed25519.SeedSize))
		}
		fmt.Println(base64.StdEncoding.EncodeToString(ed25519.NewKeyFromSeed(seed).Public().(ed25519.PublicKey)))
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "keygen" {
		pub, priv, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			fatal(err)
		}
		fmt.Println("private", base64.StdEncoding.EncodeToString(priv.Seed()))
		fmt.Println("public ", base64.StdEncoding.EncodeToString(pub))
		return
	}
	zone := flag.String("zone", "", "tunnel zone delegated to this server")
	zones := flag.String("zones", "", "comma-separated list of tunnel zones delegated to this server")
	keyFile := flag.String("key-file", "", "file holding the base64 private key (from keygen)")
	listen := flag.String("listen", ":53", "UDP address to serve DNS on")
	ports := flag.String("allow-ports", "80,443", `destination ports streams may reach, or "any"`)
	proxy := flag.String("proxy", "", `optional egress SOCKS5 proxy (e.g. "127.0.0.1:1080" or "socks5://127.0.0.1:1080")`)
	private := flag.Bool("allow-private", false, "allow non-public destinations (lab use only)")
	verbose := flag.Bool("v", false, "debug logging")
	flag.Parse()
	if *zone == "" && *zones == "" || *keyFile == "" {
		flag.Usage()
		os.Exit(2)
	}
	raw, err := os.ReadFile(*keyFile)
	if err != nil {
		fatal(err)
	}
	seed, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || len(seed) != ed25519.SeedSize {
		fatal(fmt.Errorf("key file must hold a base64 %d-byte seed", ed25519.SeedSize))
	}
	level := slog.LevelInfo
	if *verbose {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))

	dialer := &net.Dialer{Control: soar.PublicOnly}
	if *private {
		dialer.Control = nil
	}
	allowed := strings.Split(*ports, ",")
	proxyAddr := strings.TrimSpace(*proxy)
	if strings.HasPrefix(proxyAddr, "socks5://") {
		proxyAddr = strings.TrimPrefix(proxyAddr, "socks5://")
	}

	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		_, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		if *ports != "any" && !slices.Contains(allowed, port) {
			return nil, fmt.Errorf("port %s not allowed", port)
		}
		if proxyAddr != "" {
			return socks5DialContext(ctx, proxyAddr, addr)
		}
		return dialer.DialContext(ctx, network, addr)
	}

	pc, err := net.ListenPacket("udp", *listen)
	if err != nil {
		fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	priv := ed25519.NewKeyFromSeed(seed)
	// Log hygiene: never the zone or the key. Either one in a leaked log hands a censor the
	// string it needs to block this server; `soar-server pubkey` prints the key on demand.
	var allZones []string
	if *zones != "" {
		for _, z := range strings.Split(*zones, ",") {
			z = strings.TrimSpace(z)
			if z != "" {
				allZones = append(allZones, z)
			}
		}
	}
	if *zone != "" && !slices.Contains(allZones, *zone) {
		allZones = append(allZones, *zone)
	}

	if proxyAddr != "" {
		log.Info("serving", "listen", pc.LocalAddr(), "zones", len(allZones), "egress_proxy", proxyAddr)
	} else {
		log.Info("serving", "listen", pc.LocalAddr(), "zones", len(allZones))
	}

	if err := soar.Serve(ctx, pc, soar.ServerConfig{
		Zones:        allZones,
		Zone:         *zone,
		PrivateKey:   priv,
		Dial:         dial,
		Logger:       log,
		StreamWindow: 1024 << 10,
		SendBuffer:   1024 << 10,
		MaxHold:      2000 * time.Millisecond,
	}); err != nil {
		fatal(err)
	}
}

func socks5DialContext(ctx context.Context, socksProxy, targetAddr string) (net.Conn, error) {
	d := &net.Dialer{Timeout: 10 * time.Second}
	conn, err := d.DialContext(ctx, "tcp", socksProxy)
	if err != nil {
		return nil, fmt.Errorf("connect to egress proxy %s: %w", socksProxy, err)
	}

	// 1. Handshake (SOCKS5, 1 auth method, NO_AUTH)
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		conn.Close()
		return nil, err
	}
	var resp [2]byte
	if _, err := io.ReadFull(conn, resp[:]); err != nil {
		conn.Close()
		return nil, err
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		conn.Close()
		return nil, fmt.Errorf("egress proxy authentication error 0x%02x", resp[1])
	}

	// 2. Parse target host & port
	host, portStr, err := net.SplitHostPort(targetAddr)
	if err != nil {
		conn.Close()
		return nil, err
	}
	portNum, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil {
		conn.Close()
		return nil, err
	}

	// 3. Connect Request
	req := make([]byte, 0, 262)
	req = append(req, 0x05, 0x01, 0x00) // VER=5, CMD=CONNECT(1), RSV=0
	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			req = append(req, 0x01) // IPv4
			req = append(req, ip4...)
		} else {
			req = append(req, 0x04) // IPv6
			req = append(req, ip...)
		}
	} else {
		if len(host) > 255 {
			conn.Close()
			return nil, fmt.Errorf("host too long: %s", host)
		}
		req = append(req, 0x03, byte(len(host))) // Domain
		req = append(req, host...)
	}
	var portBytes [2]byte
	binary.BigEndian.PutUint16(portBytes[:], uint16(portNum))
	req = append(req, portBytes[:]...)

	if _, err := conn.Write(req); err != nil {
		conn.Close()
		return nil, err
	}

	// 4. Read reply
	var replyHeader [4]byte
	if _, err := io.ReadFull(conn, replyHeader[:]); err != nil {
		conn.Close()
		return nil, err
	}
	if replyHeader[0] != 0x05 || replyHeader[1] != 0x00 {
		conn.Close()
		return nil, fmt.Errorf("egress proxy connect failed with code 0x%02x", replyHeader[1])
	}

	var toRead int
	switch replyHeader[3] {
	case 0x01: // IPv4
		toRead = 4 + 2
	case 0x04: // IPv6
		toRead = 16 + 2
	case 0x03: // Domain
		var l [1]byte
		if _, err := io.ReadFull(conn, l[:]); err != nil {
			conn.Close()
			return nil, err
		}
		toRead = int(l[0]) + 2
	default:
		conn.Close()
		return nil, fmt.Errorf("unknown address type 0x%02x from egress proxy", replyHeader[3])
	}

	discard := make([]byte, toRead)
	if _, err := io.ReadFull(conn, discard); err != nil {
		conn.Close()
		return nil, err
	}

	return conn, nil
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "soar-server:", err)
	os.Exit(1)
}
