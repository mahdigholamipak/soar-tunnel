package main

import (
	"bufio"
	"os"
	"strconv"
	"strings"
)

type ClientConfig struct {
	Domains                  []string
	ServerPublicKey          string
	ListenIP                 string
	ListenPort               int
	ResolversFile            string
	StreamIdleTimeoutSeconds float64
	DialTimeoutSeconds       float64
	LogLevel                 string
	Country                  string
}

func DefaultConfig() ClientConfig {
	return ClientConfig{
		Domains:                  nil,
		ServerPublicKey:          "",
		ListenIP:                 "127.0.0.1",
		ListenPort:               10850,
		ResolversFile:            "client_resolvers.txt",
		StreamIdleTimeoutSeconds: 60.0,
		DialTimeoutSeconds:       15.0,
		LogLevel:                 "INFO",
		Country:                  "IR",
	}
}

// LoadConfigFile loads simple TOML/key-value configuration files.
func LoadConfigFile(path string) (ClientConfig, error) {
	cfg := DefaultConfig()
	f, err := os.Open(path)
	if err != nil {
		return cfg, err
	}
	defer f.Close()

	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") || strings.HasPrefix(line, "[") {
			continue
		}

		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}

		key := strings.ToUpper(strings.TrimSpace(parts[0]))
		val := strings.TrimSpace(parts[1])
		// Strip comments from value if present
		if idx := strings.Index(val, "#"); idx != -1 {
			val = strings.TrimSpace(val[:idx])
		}

		switch key {
		case "DOMAINS":
			cfg.Domains = parseStringArray(val)
		case "DOMAIN", "ZONE":
			clean := cleanString(val)
			if clean != "" {
				cfg.Domains = []string{clean}
			}
		case "SERVER_PUBLIC_KEY", "ENCRYPTION_KEY", "PUBKEY":
			cfg.ServerPublicKey = cleanString(val)
		case "LISTEN_IP":
			cfg.ListenIP = cleanString(val)
		case "LISTEN_PORT":
			if p, err := strconv.Atoi(cleanString(val)); err == nil && p > 0 {
				cfg.ListenPort = p
			}
		case "RESOLVERS_FILE":
			cfg.ResolversFile = cleanString(val)
		case "STREAM_IDLE_TIMEOUT_SECONDS", "TUNNEL_PACKET_TIMEOUT_SECONDS":
			if secs, err := strconv.ParseFloat(cleanString(val), 64); err == nil && secs > 0 {
				cfg.StreamIdleTimeoutSeconds = secs
			}
		case "DIAL_TIMEOUT_SECONDS":
			if secs, err := strconv.ParseFloat(cleanString(val), 64); err == nil && secs > 0 {
				cfg.DialTimeoutSeconds = secs
			}
		case "LOG_LEVEL":
			cfg.LogLevel = strings.ToUpper(cleanString(val))
		case "COUNTRY":
			cfg.Country = strings.ToUpper(cleanString(val))
		}
	}

	return cfg, sc.Err()
}

func cleanString(s string) string {
	s = strings.TrimSpace(s)
	s = strings.Trim(s, `"'`)
	return strings.TrimSpace(s)
}

func parseStringArray(val string) []string {
	val = strings.TrimSpace(val)
	if strings.HasPrefix(val, "[") && strings.HasSuffix(val, "]") {
		val = val[1 : len(val)-1]
	}
	parts := strings.Split(val, ",")
	var result []string
	for _, p := range parts {
		cleaned := cleanString(p)
		if cleaned != "" {
			result = append(result, cleaned)
		}
	}
	return result
}
