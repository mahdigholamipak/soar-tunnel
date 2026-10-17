package main

import (
	"encoding/json"
	"math"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// BankEntry stores performance and reliability telemetry for a single resolver.
type BankEntry struct {
	Addr         string    `json:"addr"`
	RTTMs        float64   `json:"rtt_ms"`
	LastVerified time.Time `json:"last_verified"`
	SuccessCount int       `json:"success_count"`
	FailCount    int       `json:"fail_count"`
	Score        float64   `json:"score"`
	MaxEDNS0     int       `json:"max_edns0,omitempty"`
}

// ResolverBank implements persistent caching and loss-weighted ranking of healthy resolvers,
// inspired by TheFeed and MasterDnsVPN bank architectures.
type ResolverBank struct {
	mu       sync.RWMutex
	filePath string
	Entries  map[string]*BankEntry `json:"entries"`
}

// NewResolverBank initializes a bank, loading any existing cache from filePath.
func NewResolverBank(filePath string) *ResolverBank {
	b := &ResolverBank{
		filePath: filePath,
		Entries:  make(map[string]*BankEntry),
	}
	_ = b.Load()
	return b
}

// Load reads the bank entries from disk.
func (b *ResolverBank) Load() error {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.filePath == "" {
		return nil
	}
	data, err := os.ReadFile(b.filePath)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, &b.Entries)
}

// Save atomically writes the bank entries to disk.
func (b *ResolverBank) Save() error {
	b.mu.RLock()
	defer b.mu.RUnlock()

	if b.filePath == "" || len(b.Entries) == 0 {
		return nil
	}
	dir := filepath.Dir(b.filePath)
	if dir != "" && dir != "." {
		_ = os.MkdirAll(dir, 0755)
	}

	data, err := json.MarshalIndent(b.Entries, "", "  ")
	if err != nil {
		return err
	}

	tmpPath := b.filePath + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0644); err != nil {
		return err
	}
	return os.Rename(tmpPath, b.filePath)
}

// RecordSuccess updates a resolver's EWMA RTT, increments success count, and recalculates its health score.
func (b *ResolverBank) RecordSuccess(addr string, rtt time.Duration) {
	b.RecordSuccessWithEDNS(addr, rtt, 1232)
}

// RecordSuccessWithEDNS records a successful probe along with the discovered EDNS0 buffer capability.
func (b *ResolverBank) RecordSuccessWithEDNS(addr string, rtt time.Duration, ednsSize int) {
	b.mu.Lock()
	defer b.mu.Unlock()

	rttMs := float64(rtt.Microseconds()) / 1000.0
	e, ok := b.Entries[addr]
	if !ok {
		e = &BankEntry{
			Addr:     addr,
			RTTMs:    rttMs,
			MaxEDNS0: ednsSize,
		}
		b.Entries[addr] = e
	} else {
		// EWMA: 70% history, 30% current measurement
		if e.RTTMs <= 0 {
			e.RTTMs = rttMs
		} else {
			e.RTTMs = 0.7*e.RTTMs + 0.3*rttMs
		}
		if ednsSize > 0 {
			e.MaxEDNS0 = ednsSize
		}
	}
	if e.MaxEDNS0 == 0 {
		e.MaxEDNS0 = 1232
	}
	e.SuccessCount++
	e.LastVerified = time.Now()
	b.recomputeScore(e)
}

// RecordFail records a timeout or failure against this resolver.
func (b *ResolverBank) RecordFail(addr string) {
	b.mu.Lock()
	defer b.mu.Unlock()

	e, ok := b.Entries[addr]
	if !ok {
		e = &BankEntry{
			Addr: addr,
		}
		b.Entries[addr] = e
	}
	e.FailCount++
	b.recomputeScore(e)
}

func (b *ResolverBank) recomputeScore(e *BankEntry) {
	total := e.SuccessCount + e.FailCount
	if total == 0 {
		e.Score = 0
		return
	}
	successRatio := float64(e.SuccessCount) / float64(total)
	latencyFactor := 1000.0 / math.Max(e.RTTMs, 10.0)

	// EDNS0 buffer size bonus: higher buffer sizes carry larger payload frames per query
	ednsBonus := 1.0
	if e.MaxEDNS0 >= 1400 {
		ednsBonus = 1.3
	} else if e.MaxEDNS0 >= 1232 {
		ednsBonus = 1.15
	}

	// Quadratic success ratio penalizes intermittent or flapping resolvers
	e.Score = latencyFactor * math.Pow(successRatio, 2.0) * ednsBonus
}

// GetTopHealthy returns the highest-scoring verified resolvers, filtered by maxAge.
func (b *ResolverBank) GetTopHealthy(maxCount int, maxAge time.Duration) []string {
	b.mu.RLock()
	defer b.mu.RUnlock()

	now := time.Now()
	type scored struct {
		addr  string
		score float64
		rtt   float64
	}
	var candidates []scored

	for addr, e := range b.Entries {
		if e.SuccessCount == 0 {
			continue
		}
		if maxAge > 0 && now.Sub(e.LastVerified) > maxAge {
			continue
		}
		if e.FailCount > e.SuccessCount && e.FailCount >= 3 {
			continue
		}
		candidates = append(candidates, scored{addr: addr, score: e.Score, rtt: e.RTTMs})
	}

	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].score == candidates[j].score {
			return candidates[i].rtt < candidates[j].rtt
		}
		return candidates[i].score > candidates[j].score
	})

	var result []string
	for i := 0; i < len(candidates) && i < maxCount; i++ {
		result = append(result, candidates[i].addr)
	}
	return result
}

// GetAllVerified returns all verified resolvers recorded in the bank.
func (b *ResolverBank) GetAllVerified() []string {
	b.mu.RLock()
	defer b.mu.RUnlock()

	var result []string
	for addr, e := range b.Entries {
		if e.SuccessCount > 0 {
			result = append(result, addr)
		}
	}
	return result
}

// Delete removes an entry from the bank and saves changes.
func (b *ResolverBank) Delete(addr string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	if _, ok := b.Entries[addr]; ok {
		delete(b.Entries, addr)
		_ = b.Save()
		return true
	}
	return false
}

// Clear removes all entries from the bank and saves changes.
func (b *ResolverBank) Clear() error {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.Entries = make(map[string]*BankEntry)
	return b.Save()
}
